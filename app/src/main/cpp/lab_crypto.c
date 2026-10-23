#define _GNU_SOURCE
#include "lab_common.h"
#include <errno.h>
#include <math.h>
#include <sched.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "sign.h"
#include "fips202.h"
#include "kat_vectors.h"
#ifndef PULSE_BACKEND
#define PULSE_BACKEND "scalar"
#endif
#ifndef PULSE_REQUIRE
#define PULSE_REQUIRE 0
#endif

/* Only published test keys and reproducible public benchmark inputs are used. */
void randombytes(uint8_t *out,size_t n){for(size_t i=0;i<n;i++)out[i]=(uint8_t)(i*73+19);}
static int sign_full(uint8_t *sig,size_t *len,const uint8_t *m,size_t mlen,
                     const uint8_t *ctx,size_t ctxlen,const uint8_t rnd[32],const uint8_t *sk){
 if(ctxlen>255)return -1;uint8_t pre[257]={0};pre[1]=(uint8_t)ctxlen;if(ctxlen)memcpy(pre+2,ctx,ctxlen);
 return crypto_sign_signature_internal(sig,len,m,mlen,pre,ctxlen+2,rnd,sk);
}
static int kat_check(void){
 uint8_t sig[CRYPTO_BYTES],zero[32]={0};size_t len=0;
 for(unsigned i=0;i<sizeof(lab_kats)/sizeof(lab_kats[0]);i++){
  const LabKat *k=&lab_kats[i];
  if(sign_full(sig,&len,k->message,k->mlen,k->context,k->ctxlen,k->rndlen?k->rnd:zero,k->sk)||len!=CRYPTO_BYTES||memcmp(sig,k->signature,CRYPTO_BYTES))return -1;
  if(crypto_sign_verify(sig,len,k->message,k->mlen,k->context,k->ctxlen,k->pk))return -2;
  sig[CRYPTO_BYTES/2]^=1;
  if(crypto_sign_verify(sig,len,k->message,k->mlen,k->context,k->ctxlen,k->pk)==0)return -3;
 }return 6;
}
static void make_message(uint8_t *m,uint8_t rnd[32],unsigned i,unsigned round){
 for(unsigned j=0;j<1024;j++)m[j]=(uint8_t)(j*37+11);
 for(unsigned j=0;j<4;j++){m[j]=(uint8_t)(i>>(j*8));m[4+j]=(uint8_t)(round>>(j*8));}
 shake256(rnd,32,m,1024);
}
static int latency_compare(const void *a,const void *b){double x=*(const double*)a,y=*(const double*)b;return (x>y)-(x<y);}
int main(int argc,char **argv){
 int cpu=0,count=128,round=0;long cap=0;int selftest_only=0;
 for(int i=1;i<argc;i++){
  if(!strcmp(argv[i],"--cpu")&&i+1<argc)cpu=atoi(argv[++i]);
  else if(!strcmp(argv[i],"--signatures")&&i+1<argc)count=atoi(argv[++i]);
  else if(!strcmp(argv[i],"--round")&&i+1<argc)round=atoi(argv[++i]);
  else if(!strcmp(argv[i],"--cap")&&i+1<argc)cap=strtol(argv[++i],NULL,10);
  else if(!strcmp(argv[i],"--selftest"))selftest_only=1;
  else return 2;
 }
 if(count<8||count>8192||round<0||cap<0)return 2;
 if(lab_init(cpu))return 2;
 int sve=lab_sve_bits(),supported=PULSE_REQUIRE<2||sve>0;
 if(PULSE_REQUIRE==3&&!(lab_hwcap2()&2UL))supported=0;
 if(!supported){printf("{\"event\":\"result\",\"kind\":\"mldsa87\",\"backend\":\"%s\",\"result\":\"unsupported\",\"valid\":false,\"sve_bits\":%d}\n",PULSE_BACKEND,sve);return 0;}
 int kat=kat_check();
 if(kat<0){printf("{\"event\":\"result\",\"kind\":\"mldsa87\",\"backend\":\"%s\",\"result\":\"failed\",\"valid\":false,\"kat_error\":%d}\n",PULSE_BACKEND,kat);return 3;}
 if(selftest_only){printf("{\"event\":\"result\",\"kind\":\"mldsa87\",\"backend\":\"%s\",\"result\":\"completed\",\"valid\":true,\"kat_passed\":%d,\"sve_bits\":%d,\"sme_bits\":%d}\n",PULSE_BACKEND,kat,sve,lab_sme_bits());return 0;}
 uint8_t *signatures=malloc((size_t)count*CRYPTO_BYTES),*messages=malloc((size_t)count*1024),*randomness=malloc((size_t)count*32);double *latencies=malloc((size_t)count*sizeof(double));
 if(!signatures||!latencies||!messages||!randomness){free(signatures);free(latencies);free(messages);free(randomness);return 4;}
 for(int i=0;i<count;i++)make_message(messages+(size_t)i*1024,randomness+(size_t)i*32,(unsigned)i,(unsigned)round);
 uint8_t m[1024],rnd[32],scratch[CRYPTO_BYTES];size_t siglen;
 for(unsigned i=0;i<8;i++){make_message(m,rnd,i,round);if(sign_full(scratch,&siglen,m,sizeof(m),NULL,0,rnd,lab_kats[0].sk))return 5;}
 int cap_result=lab_cap_begin(cpu,cap);
 if(cap_result>0){
  printf("{\"event\":\"result\",\"kind\":\"mldsa87\",\"backend\":\"%s\",\"result\":\"unsupported\",\"valid\":false,\"cap_restored\":true,\"detail\":\"Requested cap is outside current system policy bounds; no cap applied\"",PULSE_BACKEND);
  lab_telemetry_json(0,0);puts("}");free(signatures);free(latencies);free(messages);free(randomness);return 0;
 }
 if(cap_result<0){perror("Frequency cap");free(signatures);free(latencies);free(messages);free(randomness);return 6;}
 lab_measure_begin(cpu);double start=lab_now(),cstart=lab_cpu_time();int done=0,stable=1,errors=0;
 for(int i=0;i<count&&!lab_stop;i++){
  double t=lab_now();int rc=sign_full(signatures+(size_t)i*CRYPTO_BYTES,&siglen,messages+(size_t)i*1024,1024,NULL,0,randomness+(size_t)i*32,lab_kats[0].sk);
  latencies[i]=(lab_now()-t)*1e6;
  if(rc||siglen!=CRYPTO_BYTES){errors++;break;}
  if(sched_getcpu()!=cpu)stable=0;done++;
 }
 double cpu_seconds=lab_cpu_time()-cstart,elapsed=lab_now()-start;lab_measure_end();int restored=lab_cap_end();
 /* All verification and transcript hashing are outside the signing measurement. */
 for(int i=0;i<done;i++){make_message(m,rnd,(unsigned)i,(unsigned)round);if(crypto_sign_verify(signatures+(size_t)i*CRYPTO_BYTES,CRYPTO_BYTES,m,sizeof(m),NULL,0,lab_kats[0].pk))errors++;}
 uint8_t digest[32];shake256(digest,sizeof(digest),signatures,(size_t)done*CRYPTO_BYTES);
 qsort(latencies,(size_t)done,sizeof(double),latency_compare);
 int valid=done==count&&!errors&&stable&&!lab_stop&&restored;
 printf("{\"event\":\"result\",\"kind\":\"mldsa87\",\"backend\":\"%s\",\"result\":\"%s\",\"valid\":%s,\"kat_passed\":%d,\"signatures\":%d,\"verified\":%d,\"verify_errors\":%d,\"message_bytes\":1024,\"signature_bytes\":%d,\"round\":%d,\"cpu\":%d,\"affinity_stable\":%s,\"sve_bits\":%d,\"sme_bits\":%d,\"cap_restored\":%s,\"elapsed_seconds\":%.9f,\"signatures_per_second\":%.6f,\"median_us\":%.6f,\"p95_us\":%.6f,\"transcript_sha3shake256\":\"",PULSE_BACKEND,valid?"completed":lab_stop?"cancelled":"failed",valid?"true":"false",kat,done,done-errors,errors,CRYPTO_BYTES,round,cpu,stable?"true":"false",sve,lab_sme_bits(),restored?"true":"false",elapsed,done/elapsed,done?latencies[done/2]:0,done?latencies[(done-1)*95/100]:0);
 for(unsigned i=0;i<sizeof(digest);i++)printf("%02x",digest[i]);fputs("\"",stdout);
 lab_telemetry_json(elapsed,cpu_seconds);puts("}");free(signatures);free(latencies);free(messages);free(randomness);return valid?0:7;
}
