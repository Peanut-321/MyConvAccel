/** sw32.c — 32×32 software conv only, absolute minimal output for fast sim */
#include <stdio.h>
#include <stdint.h>
static inline unsigned long rdcycle(void){unsigned long c;asm volatile("rdcycle %0":"=r"(c));return c;}
static inline int16_t fmul(int16_t a,int16_t b){return(int16_t)(((int32_t)a*(int32_t)b)>>8);}
static void conv5(const int16_t*in,const int16_t*k,int16_t*out,int N){
  for(int or=0;or<N;or++)for(int oc=0;oc<N;oc++){
    int32_t s=0;
    for(int kr=0;kr<5;kr++)for(int kc=0;kc<5;kc++){
      int ir=or+kr-2,ic=oc+kc-2;
      int16_t p=(ir>=0&&ir<N&&ic>=0&&ic<N)?in[ir*N+ic]:0;
      s+=fmul(p,k[kr*5+kc]);
    }
    if(s>32767)s=32767;else if(s<-32768)s=-32768;
    out[or*N+oc]=(int16_t)s;
  }
}
int main(void){
  static int16_t in[32*32]__attribute__((aligned(8)));
  static int16_t k[25]__attribute__((aligned(4)));
  static int16_t o[32*32]__attribute__((aligned(8)));
  unsigned seed=42;
  for(int i=0;i<1024;i++){seed=seed*1103515245+12345;in[i]=(int16_t)(((seed>>16)&0x7F)-64);}
  for(int i=0;i<25;i++){seed=seed*1103515245+12345;k[i]=(int16_t)(((seed>>16)&0x7F)-64);}
  printf("W");
  conv5(in,k,o,32); /* warmup */
  printf(".");
  unsigned long t0=rdcycle();
  conv5(in,k,o,32);
  unsigned long t1=rdcycle();
  unsigned long cyc=t1-t0;
  printf(" SW32=%lu\n",cyc);
  /* verify: run again and check */
  static int16_t v[32*32];
  conv5(in,k,v,32);
  int ok=1;for(int i=0;i<1024;i++)if(o[i]!=v[i])ok=0;
  printf(" OK=%d\n",ok);
  printf(" RATIO_SW_HW=%lu (HW_CYCLES use 2500 as estimate)\n",cyc/2500);
  return ok?0:1;
}
