#define _GNU_SOURCE
#include "lab_common.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/perf_event.h>
#include <math.h>
#include <poll.h>
#include <pthread.h>
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/auxv.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include <stdatomic.h>

volatile sig_atomic_t lab_stop;
static pthread_t sampler;
static atomic_int sampling;
static int sampler_created, selected_cpu, pmu[2]={-1,-1}, pmu_error;
static double freq_sum,freq_min,freq_max,power_sum,peak_battery;
static long freq_n,power_n;
static uint64_t counts[5];
static int charging,temperature_stop,cap_pipe=-1,cap_lock=-1;
static pid_t cap_guard=-1;
static char cap_path[512];
static long cap_original,cap_requested,cap_at_start,cap_at_end;
static const char *vote_path="/sys/kernel/msm_performance/parameters/cpu_max_freq";
static int cap_cpu,cap_restore_status;
static long vote_original,vote_after;
static void stop_signal(int signo){(void)signo;lab_stop=1;}
double lab_now(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return t.tv_sec+t.tv_nsec/1e9;}
double lab_cpu_time(void){struct timespec t;clock_gettime(CLOCK_THREAD_CPUTIME_ID,&t);return t.tv_sec+t.tv_nsec/1e9;}
long lab_read_long(const char *path){FILE *f=fopen(path,"r");long n=-1;if(f){if(fscanf(f,"%ld",&n)!=1)n=-1;fclose(f);}return n;}
unsigned long lab_hwcap(void){return getauxval(AT_HWCAP);}
unsigned long lab_hwcap2(void){return getauxval(AT_HWCAP2);}
int lab_sve_bits(void){if(!(lab_hwcap()&(1UL<<22)))return 0;int n=prctl(51,0,0,0,0);return n<0?0:(n&0xffff)*8;}
int lab_sme_bits(void){if(!(lab_hwcap2()&(1UL<<23)))return 0;int n=prctl(64,0,0,0,0);return n<0?0:(n&0xffff)*8;}
int lab_init(int cpu){
 setvbuf(stdout,NULL,_IOLBF,0);signal(SIGTERM,stop_signal);signal(SIGINT,stop_signal);alarm(180);
 if(cpu<0||cpu>=CPU_SETSIZE)return -1;
 cpu_set_t set;CPU_ZERO(&set);CPU_SET(cpu,&set);
 if(sched_setaffinity(0,sizeof(set),&set)){perror("CPU affinity");return -1;}
 printf("{\"event\":\"started\",\"pid\":%d}\n",getpid());return 0;
}
static int write_cap(const char *path,long value){char buf[40];int n=snprintf(buf,sizeof(buf),"%ld",value);int fd=open(path,O_WRONLY|O_CLOEXEC);if(fd<0)return -1;int ok=write(fd,buf,n)==n?0:-1;close(fd);return ok;}
/* This getter returns the raw QoS request, unlike scaling_max_freq (the
 * aggregate effective ceiling). Never write an effective ceiling back as a vote. */
static long read_vote(void){
 FILE *f=fopen(vote_path,"r");if(!f)return -1;
 int cpu;long value,result=-1;
 while(fscanf(f," %d:%ld",&cpu,&value)==2)if(cpu==cap_cpu){result=value;break;}
 fclose(f);return result;
}
static int write_vote(long value){
 char buf[64];int n=snprintf(buf,sizeof(buf),"%d:%ld",cap_cpu,value);
 int fd=open(vote_path,O_WRONLY|O_CLOEXEC);if(fd<0)return -1;
 int ok=write(fd,buf,n)==n?0:-1;close(fd);return ok;
}
static int detach_guard(void){
 const char *groups[]={"/sys/fs/cgroup/cgroup.procs","/dev/cpuset/cgroup.procs",
  "/dev/cpuctl/cgroup.procs","/dev/blkio/cgroup.procs","/sys/fs/cgroup/freezer/cgroup.procs"};
 if(setsid()<0)return -1;
 for(unsigned i=0;i<sizeof(groups)/sizeof(*groups);i++)
  if(access(groups[i],F_OK)==0&&write_cap(groups[i],getpid()))return -1;
 write_cap("/proc/self/oom_score_adj",-900);
 return 0;
}
int lab_cap_begin(int cpu,long cap){
 if(!cap)return 0;
 /* The guardian inherits this lock, so a restarted app cannot overlap restorations. */
 char policy[512],resolved[512],lock_path[512];
 snprintf(policy,sizeof(policy),"/sys/devices/system/cpu/cpu%d/cpufreq",cpu);
 if(!realpath(policy,resolved))return -1;
 const char *name=strrchr(resolved,'/');if(!name){errno=EINVAL;return -1;}name++;
 if(mkdir("/data/adb/pulse-lab",0700)&&errno!=EEXIST)return -1;
 snprintf(lock_path,sizeof(lock_path),"/data/adb/pulse-lab/%s.lock",name);
 cap_lock=open(lock_path,O_CREAT|O_RDWR|O_CLOEXEC|O_NOFOLLOW,0600);
 if(cap_lock<0)return -1;
 if(flock(cap_lock,LOCK_EX|LOCK_NB)){close(cap_lock);cap_lock=-1;return -1;}
 snprintf(cap_path,sizeof(cap_path),"/sys/devices/system/cpu/cpu%d/cpufreq/scaling_max_freq",cpu);
 cap_original=lab_read_long(cap_path);cap_requested=cap;cap_cpu=cpu;vote_original=read_vote();
 char minpath[512];snprintf(minpath,sizeof(minpath),"/sys/devices/system/cpu/cpu%d/cpufreq/scaling_min_freq",cpu);
 long minimum=lab_read_long(minpath);
 if(cap_original<=0||minimum<0){close(cap_lock);cap_lock=-1;errno=EACCES;return -1;}
 /* A platform without a readable independent vote cannot be restored safely. */
 if(vote_original<0){close(cap_lock);cap_lock=-1;return 2;}
 if(cap>cap_original||cap<minimum){close(cap_lock);cap_lock=-1;return 1;}
 int p[2];if(pipe2(p,O_CLOEXEC)){close(cap_lock);cap_lock=-1;return -1;}
 int ready[2];if(pipe2(ready,O_CLOEXEC)){close(p[0]);close(p[1]);close(cap_lock);cap_lock=-1;return -1;}
 cap_guard=fork();
 if(cap_guard==0){
  close(p[1]);close(ready[0]);signal(SIGTERM,SIG_IGN);signal(SIGINT,SIG_IGN);alarm(185);
  if(detach_guard()){close(ready[1]);_exit(3);}
  char ok='1';if(write(ready[1],&ok,1)!=1)_exit(3);close(ready[1]);
  struct pollfd f={p[0],POLLIN|POLLHUP,0};int rc;
  do{rc=poll(&f,1,175000);}while(rc<0&&errno==EINTR);
  long current=read_vote();int restored=0;
  if(current==cap_requested)restored=write_vote(vote_original)||read_vote()!=vote_original?3:0;
  else if(current!=vote_original)restored=2; /* A newer foreign vote owns the node. */
  close(p[0]);_exit(restored);
 }
 close(p[0]);close(ready[1]);
 if(cap_guard<0){close(p[1]);close(ready[0]);close(cap_lock);cap_lock=-1;return -1;}cap_pipe=p[1];
 struct pollfd ack={ready[0],POLLIN|POLLHUP,0};char ok=0;
 int prepared=poll(&ack,1,3000)>0&&read(ready[0],&ok,1)==1&&ok=='1';close(ready[0]);
 if(!prepared||write_vote(cap)||read_vote()!=cap){lab_cap_end();return -1;}
 /* sysfs policy updates can settle asynchronously on vendor kernels. */
 for(int i=0;i<25&&!lab_stop;i++){cap_at_start=lab_read_long(cap_path);if(cap_at_start==cap)break;usleep(10000);}
 printf("{\"event\":\"frequency_cap\",\"original_khz\":%ld,\"requested_khz\":%ld,\"observed_khz\":%ld}\n",cap_original,cap,cap_at_start);
 return 0;
}
int lab_cap_end(void){
 if(cap_guard<0)return 1;
 cap_at_end=lab_read_long(cap_path);
 if(cap_pipe>=0){close(cap_pipe);cap_pipe=-1;}
 int status=0;pid_t waited;do{waited=waitpid(cap_guard,&status,0);}while(waited<0&&errno==EINTR);
 cap_guard=-1;vote_after=read_vote();
 cap_restore_status=waited>0&&WIFEXITED(status)?WEXITSTATUS(status):3;
 if(cap_lock>=0){close(cap_lock);cap_lock=-1;}
 return cap_restore_status==0&&vote_after==vote_original;
}
static int is_charging(void){
 FILE *f=fopen("/sys/class/power_supply/battery/status","r");char text[48]={0};
 if(f){fgets(text,sizeof(text),f);fclose(f);}else return -1;
 if(strncmp(text,"Discharging",11)==0)return 0;
 return 1;
}
static void *sample_loop(void *unused){
 (void)unused;char p[512];snprintf(p,sizeof(p),"/sys/devices/system/cpu/cpu%d/cpufreq/scaling_cur_freq",selected_cpu);
 while(atomic_load(&sampling)){
  long f=lab_read_long(p);if(f>0){double mhz=f/1000.;freq_sum+=mhz;if(!freq_n||mhz<freq_min)freq_min=mhz;if(mhz>freq_max)freq_max=mhz;freq_n++;}
  long i=lab_read_long("/sys/class/power_supply/battery/current_now"),v=lab_read_long("/sys/class/power_supply/battery/voltage_now");
  if(v>=2000000&&v<=10000000&&i!=-1&&labs(i)<30000000){power_sum+=fabs((double)i*v/1e12);power_n++;}
  long t=lab_read_long("/sys/class/power_supply/battery/temp");if(t>0){if(t/10.>peak_battery)peak_battery=t/10.;if(t>=480){temperature_stop=1;lab_stop=1;}}
  if(is_charging()!=0)charging=1;
  struct timespec nap={0,20000000};nanosleep(&nap,NULL);
 }return NULL;
}
void lab_measure_begin(int cpu){
 selected_cpu=cpu;freq_sum=freq_min=freq_max=power_sum=peak_battery=0;freq_n=power_n=0;charging=is_charging();temperature_stop=0;memset(counts,0,sizeof(counts));pmu_error=0;
 for(int j=0;j<2;j++){
  struct perf_event_attr a={0};a.size=sizeof(a);a.type=PERF_TYPE_HARDWARE;a.config=j?PERF_COUNT_HW_INSTRUCTIONS:PERF_COUNT_HW_CPU_CYCLES;
  a.disabled=1;a.exclude_kernel=1;a.exclude_hv=1;a.read_format=PERF_FORMAT_GROUP|PERF_FORMAT_TOTAL_TIME_ENABLED|PERF_FORMAT_TOTAL_TIME_RUNNING;
  pmu[j]=syscall(__NR_perf_event_open,&a,0,-1,j?pmu[0]:-1,PERF_FLAG_FD_CLOEXEC);
  if(pmu[j]<0){pmu_error=errno;for(int k=0;k<j;k++){close(pmu[k]);pmu[k]=-1;}break;}
 }
 if(pmu[0]>=0){ioctl(pmu[0],PERF_EVENT_IOC_RESET,PERF_IOC_FLAG_GROUP);ioctl(pmu[0],PERF_EVENT_IOC_ENABLE,PERF_IOC_FLAG_GROUP);}
 atomic_store(&sampling,1);sampler_created=pthread_create(&sampler,NULL,sample_loop,NULL)==0;
}
void lab_measure_end(void){
 if(pmu[0]>=0){ioctl(pmu[0],PERF_EVENT_IOC_DISABLE,PERF_IOC_FLAG_GROUP);if(read(pmu[0],counts,sizeof(counts))!=(ssize_t)sizeof(counts))memset(counts,0,sizeof(counts));}
 atomic_store(&sampling,0);if(sampler_created)pthread_join(sampler,NULL);
 for(int j=0;j<2;j++)if(pmu[j]>=0){close(pmu[j]);pmu[j]=-1;}
}
static void num(double x){if(isfinite(x))printf("%.8f",x);else fputs("null",stdout);}
void lab_telemetry_json(double elapsed,double cpu_seconds){
 double cycles=counts[2]?counts[3]*(double)counts[1]/counts[2]:NAN;
 double instr=counts[2]?counts[4]*(double)counts[1]/counts[2]:NAN;
 printf(",\"telemetry\":{\"frequency_samples\":%ld,\"sampled_mean_mhz\":",freq_n);num(freq_n?freq_sum/freq_n:NAN);
 fputs(",\"sampled_min_mhz\":",stdout);num(freq_n?freq_min:NAN);fputs(",\"sampled_max_mhz\":",stdout);num(freq_n?freq_max:NAN);
 fputs(",\"effective_mhz\":",stdout);num(cpu_seconds>0?cycles/cpu_seconds/1e6:NAN);
 fputs(",\"cycles\":",stdout);num(cycles);fputs(",\"instructions\":",stdout);num(instr);fputs(",\"ipc\":",stdout);num(cycles>0?instr/cycles:NAN);
 printf(",\"pmu_errno\":%d,\"pmu_running_ratio\":",pmu_error);num(counts[1]?(double)counts[2]/counts[1]:NAN);
 fputs(",\"battery_net_watts\":",stdout);num(power_n?power_sum/power_n:NAN);
 fputs(",\"battery_net_joules\":",stdout);num(power_n?power_sum/power_n*elapsed:NAN);
 printf(",\"power_samples\":%ld,\"charging_or_unknown\":%s,\"energy_comparable\":%s,\"battery_peak_c\":",power_n,charging?"true":"false",!charging&&power_n>1&&elapsed>=2?"true":"false");num(peak_battery>0?peak_battery:NAN);
 printf(",\"temperature_stop\":%s,\"cpu_at_end\":%d,\"cap_requested_khz\":%ld,\"cap_observed_start_khz\":%ld,\"cap_observed_end_khz\":%ld,\"cap_original_khz\":%ld,\"cap_backend\":\"msm_performance_raw_vote\",\"cap_vote_original\":%ld,\"cap_vote_after\":%ld,\"cap_restore_status\":%d,\"pmu_scope\":\"main benchmark thread; excludes kernel and telemetry thread\"}",temperature_stop?"true":"false",sched_getcpu(),cap_requested,cap_at_start,cap_at_end,cap_original,vote_original,vote_after,cap_restore_status);
}
