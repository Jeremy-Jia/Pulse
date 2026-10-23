/* Benchmark-only SIMD kernels for ML-DSA's q=8380417 arithmetic. */
#ifndef PULSE_LAB_VECTOR_H
#define PULSE_LAB_VECTOR_H
#include <stdint.h>
#if defined(PULSE_NEON)
#include <arm_neon.h>
typedef int32x4_t pv;
#define PV_LANES 4
static inline pv pv_load(const int32_t *p,unsigned n){(void)n;return vld1q_s32(p);}
static inline void pv_store(int32_t *p,pv x,unsigned n){(void)n;vst1q_s32(p,x);}
static inline pv pv_dup(int32_t x){return vdupq_n_s32(x);}
static inline pv pv_add(pv a,pv b){return vaddq_s32(a,b);}
static inline pv pv_sub(pv a,pv b){return vsubq_s32(a,b);}
static inline pv pv_mont(pv a,pv b){
 pv t=vmulq_n_s32(vmulq_s32(a,b),58728449);
 int64x2_t lo=vmlsl_n_s32(vmull_s32(vget_low_s32(a),vget_low_s32(b)),vget_low_s32(t),8380417);
 int64x2_t hi=vmlsl_n_s32(vmull_high_s32(a,b),vget_high_s32(t),8380417);
 return vcombine_s32(vshrn_n_s64(lo,32),vshrn_n_s64(hi,32));
}
#else
#include <arm_sve.h>
typedef svint32_t pv;
#define PV_LANES ((unsigned)svcntw())
static inline svbool_t pv_pg(unsigned n){return svwhilelt_b32((uint64_t)0,(uint64_t)n);}
static inline pv pv_load(const int32_t *p,unsigned n){return svld1_s32(pv_pg(n),p);}
static inline void pv_store(int32_t *p,pv x,unsigned n){svst1_s32(pv_pg(n),p,x);}
static inline pv pv_dup(int32_t x){return svdup_s32(x);}
static inline pv pv_add(pv a,pv b){return svadd_s32_x(svptrue_b32(),a,b);}
static inline pv pv_sub(pv a,pv b){return svsub_s32_x(svptrue_b32(),a,b);}
static inline pv pv_mont(pv a,pv b){
 svbool_t p=svptrue_b32();pv t=svmul_n_s32_x(p,svmul_s32_x(p,a,b),58728449);
#if defined(PULSE_SVE2)
 svint64_t lo=svmlslb_s64(svmullb_s64(a,b),t,svdup_s32(8380417));
 svint64_t hi=svmlslt_s64(svmullt_s64(a,b),t,svdup_s32(8380417));
 return svshrnt_n_s64(svshrnb_n_s64(lo,32),hi,32);
#else
 return svsub_s32_x(p,svmulh_s32_x(p,a,b),svmulh_n_s32_x(p,t,8380417));
#endif
}
#endif
static inline void pulse_pointwise(int32_t *c,const int32_t *a,const int32_t *b){
 for(unsigned i=0;i<256;i+=PV_LANES){unsigned n=256-i;if(n>PV_LANES)n=PV_LANES;pv_store(c+i,pv_mont(pv_load(a+i,n),pv_load(b+i,n)),n);}
}
#endif
