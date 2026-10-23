#include <stdio.h>
#include <sys/prctl.h>
#include <errno.h>
int main(void){int s=prctl(51,0,0,0,0),m=prctl(64,0,0,0,0);int a=prctl(50,256,0,0,0),b=prctl(63,256,0,0,0);printf("{\"sve_current_bits\":%d,\"sve_max_bits\":%d,\"sme_current_bits\":%d,\"sme_max_bits\":%d}\n",s<0?0:(s&65535)*8,a<0?0:(a&65535)*8,m<0?0:(m&65535)*8,b<0?0:(b&65535)*8);return 0;}
