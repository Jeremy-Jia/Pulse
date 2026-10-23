#define _GNU_SOURCE
#include "lab_common.h"
#include <errno.h>
#include <pthread.h>
#include <sched.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdatomic.h>
#include <unistd.h>

static pthread_barrier_t barrier;
static atomic_int abort_workers;
typedef struct {uint64_t *src,*dst;size_t bytes;int cpu,mode,affinity_error,affinity_changed,valid;double seconds,elapsed;uint64_t loops,checksum,expected;} Worker;
static uint64_t read_stream(const void *source,size_t bytes){
 const unsigned char *p=source;uint64_t a,b;
 __asm__ volatile(
  "movi v4.2d, #0\n movi v5.2d, #0\n movi v6.2d, #0\n movi v7.2d, #0\n"
  "1:\n ldp q0, q1, [%[p]], #32\n ldp q2, q3, [%[p]], #32\n"
  "add v4.2d, v4.2d, v0.2d\n add v5.2d, v5.2d, v1.2d\n add v6.2d, v6.2d, v2.2d\n add v7.2d, v7.2d, v3.2d\n"
  "subs %[n], %[n], #64\n b.ne 1b\n add v4.2d, v4.2d, v5.2d\n add v6.2d, v6.2d, v7.2d\n add v4.2d, v4.2d, v6.2d\n"
  "umov %[a], v4.d[0]\n umov %[b], v4.d[1]\n"
  :[p]"+r"(p),[n]"+r"(bytes),[a]"=r"(a),[b]"=r"(b)::"memory","cc","v0","v1","v2","v3","v4","v5","v6","v7");
 return a+b;
}
static void write_stream(void *target,size_t bytes,uint64_t value){
 unsigned char *p=target;
 __asm__ volatile("dup v0.2d, %[v]\n1:\n stp q0, q0, [%[p]], #32\n stp q0, q0, [%[p]], #32\n subs %[n], %[n], #64\n b.ne 1b\n"
 :[p]"+r"(p),[n]"+r"(bytes):[v]"r"(value):"memory","cc","v0");
}
static void *run_worker(void *arg){
 Worker *w=arg;cpu_set_t set;CPU_ZERO(&set);CPU_SET(w->cpu,&set);
 if(sched_setaffinity(0,sizeof(set),&set))w->affinity_error=errno;
 pthread_barrier_wait(&barrier);
 if(atomic_load(&abort_workers)||w->affinity_error)return NULL;
 double t=lab_now();uint64_t sum=0;
 do{
  if(w->mode==0)sum=read_stream(w->src,w->bytes);
  else if(w->mode==1)write_stream(w->dst,w->bytes,0x5a39e71bc8402fd6ULL);
  else{memcpy(w->dst,w->src,w->bytes);__asm__ volatile("":::"memory");}
  w->loops++;
  if(sched_getcpu()!=w->cpu)w->affinity_changed=1;
 }while(!lab_stop&&lab_now()-t<w->seconds);
 __asm__ volatile("dmb ish":::"memory");w->elapsed=lab_now()-t;w->checksum=sum;
 w->valid=!w->affinity_changed;
 if(w->mode==0)w->valid=w->valid&&sum==w->expected;
 else if(w->mode==1){for(size_t i=0;i<w->bytes/8;i++)if(w->dst[i]!=0x5a39e71bc8402fd6ULL){w->valid=0;break;}}
 else if(memcmp(w->dst,w->src,w->bytes))w->valid=0;
 return NULL;
}
static uint64_t mix(uint64_t x){x+=0x9e3779b97f4a7c15ULL;x=(x^(x>>30))*0xbf58476d1ce4e5b9ULL;x=(x^(x>>27))*0x94d049bb133111ebULL;return x^(x>>31);}
int main(int argc,char **argv){
 int cpu=0,threads=1,mode=0;double seconds=1;size_t bytes=128UL*1024*1024;
 const char *name="read";
 for(int i=1;i<argc;i++){
  if(!strcmp(argv[i],"--cpu")&&i+1<argc)cpu=atoi(argv[++i]);
  else if(!strcmp(argv[i],"--threads")&&i+1<argc)threads=atoi(argv[++i]);
  else if(!strcmp(argv[i],"--bytes")&&i+1<argc)bytes=strtoull(argv[++i],NULL,10);
  else if(!strcmp(argv[i],"--seconds")&&i+1<argc)seconds=strtod(argv[++i],NULL);
  else if(!strcmp(argv[i],"--mode")&&i+1<argc){name=argv[++i];if(!strcmp(name,"read"))mode=0;else if(!strcmp(name,"write"))mode=1;else if(!strcmp(name,"copy"))mode=2;else return 2;}
  else if(!strcmp(argv[i],"--probe")){
   printf("{\"event\":\"result\",\"kind\":\"isa\",\"neon\":true,\"sve\":%s,\"sve2\":%s,\"sve_bits\":%d,\"sme_bits\":%d,\"hwcap\":\"0x%lx\",\"hwcap2\":\"0x%lx\"}\n",lab_sve_bits()?"true":"false",lab_hwcap2()&2UL?"true":"false",lab_sve_bits(),lab_sme_bits(),lab_hwcap(),lab_hwcap2());return 0;
  }else return 2;
 }
 long online=sysconf(_SC_NPROCESSORS_ONLN);
 if(threads<1||threads>8||threads>online||bytes<65536||bytes>512UL*1024*1024||bytes%((size_t)threads*64)||seconds<.1||seconds>10)return 2;
 if(lab_init(cpu))return 2;
 Worker w[8]={0};pthread_t tids[8];size_t each=bytes/threads;
 for(int i=0;i<threads;i++){
  w[i].cpu=(cpu+i)%online;w[i].mode=mode;w[i].seconds=seconds;w[i].bytes=each;
  if(posix_memalign((void**)&w[i].src,4096,each)||posix_memalign((void**)&w[i].dst,4096,each))return 3;
  for(size_t j=0;j<each/8;j++){w[i].src[j]=mix(j+(uint64_t)i*each);w[i].expected+=w[i].src[j];w[i].dst[j]=0;}
 }
 pthread_barrier_init(&barrier,NULL,threads);int made=0;
 for(int i=1;i<threads;i++){if(pthread_create(&tids[i],NULL,run_worker,&w[i])){fputs("Thread creation failed\n",stderr);_Exit(4);}made++;}
 lab_measure_begin(cpu);double cpu_start=lab_cpu_time();run_worker(&w[0]);double cpu_seconds=lab_cpu_time()-cpu_start;
 for(int i=1;i<=made;i++)pthread_join(tids[i],NULL);lab_measure_end();
 double elapsed=0;uint64_t transferred=0;int valid=!lab_stop;
 for(int i=0;i<threads;i++){if(w[i].elapsed>elapsed)elapsed=w[i].elapsed;transferred+=w[i].loops*w[i].bytes;if(!w[i].valid||w[i].affinity_error)valid=0;}
 double payload=transferred/elapsed/1e9,traffic=payload*(mode==2?2:1);
 printf("{\"event\":\"result\",\"kind\":\"memory\",\"mode\":\"%s\",\"result\":\"%s\",\"valid\":%s,\"working_set_bytes\":%zu,\"source_plus_destination_bytes\":%zu,\"threads\":%d,\"cpu\":%d,\"elapsed_seconds\":%.9f,\"payload_gbps\":%.6f,\"logical_traffic_gbps\":%.6f,\"bytes_processed\":%llu,\"traffic_note\":\"copy payload counts source bytes once; logical traffic counts read+write twice; DRAM bus traffic is not measured\",\"workers\":[",name,valid?"completed":lab_stop?"cancelled":"failed",valid?"true":"false",bytes,bytes*2,threads,cpu,elapsed,payload,traffic,(unsigned long long)transferred);
 for(int i=0;i<threads;i++){if(i)putchar(',');printf("{\"cpu\":%d,\"passes\":%llu,\"affinity_errno\":%d,\"affinity_stable\":%s,\"valid\":%s}",w[i].cpu,(unsigned long long)w[i].loops,w[i].affinity_error,w[i].affinity_changed?"false":"true",w[i].valid?"true":"false");}
 putchar(']');lab_telemetry_json(elapsed,cpu_seconds);puts("}");
 for(int i=0;i<threads;i++){free(w[i].src);free(w[i].dst);}pthread_barrier_destroy(&barrier);return valid?0:5;
}
