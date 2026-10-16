#define _GNU_SOURCE
#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <inttypes.h>
#include <linux/perf_event.h>
#include <math.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define OUT_CAP 131072
#define MAX_ARGS 40
#define CACHE(c,o,r) ((c) | ((uint64_t)(o)<<8) | ((uint64_t)(r)<<16))
typedef struct { const char *name; uint32_t type; uint64_t config; } EventSpec;
#define MAX_EVENTS 28
static EventSpec specs[MAX_EVENTS] = {
 {"cycles", PERF_TYPE_HARDWARE, PERF_COUNT_HW_CPU_CYCLES},
 {"instructions", PERF_TYPE_HARDWARE, PERF_COUNT_HW_INSTRUCTIONS},
 {"branches", PERF_TYPE_HARDWARE, PERF_COUNT_HW_BRANCH_INSTRUCTIONS},
 {"branch_misses", PERF_TYPE_HARDWARE, PERF_COUNT_HW_BRANCH_MISSES},
 {"cache_references", PERF_TYPE_HARDWARE, PERF_COUNT_HW_CACHE_REFERENCES},
 {"cache_misses", PERF_TYPE_HARDWARE, PERF_COUNT_HW_CACHE_MISSES},
 {"stalled_frontend", PERF_TYPE_HARDWARE, PERF_COUNT_HW_STALLED_CYCLES_FRONTEND},
 {"stalled_backend", PERF_TYPE_HARDWARE, PERF_COUNT_HW_STALLED_CYCLES_BACKEND},
 {"reference_cycles", PERF_TYPE_HARDWARE, PERF_COUNT_HW_REF_CPU_CYCLES},
 {"bus_cycles", PERF_TYPE_HARDWARE, PERF_COUNT_HW_BUS_CYCLES},
 {"l1d_read_accesses", PERF_TYPE_HW_CACHE, CACHE(PERF_COUNT_HW_CACHE_L1D,PERF_COUNT_HW_CACHE_OP_READ,PERF_COUNT_HW_CACHE_RESULT_ACCESS)},
 {"l1d_read_misses", PERF_TYPE_HW_CACHE, CACHE(PERF_COUNT_HW_CACHE_L1D,PERF_COUNT_HW_CACHE_OP_READ,PERF_COUNT_HW_CACHE_RESULT_MISS)},
 {"llc_read_accesses", PERF_TYPE_HW_CACHE, CACHE(PERF_COUNT_HW_CACHE_LL,PERF_COUNT_HW_CACHE_OP_READ,PERF_COUNT_HW_CACHE_RESULT_ACCESS)},
 {"llc_read_misses", PERF_TYPE_HW_CACHE, CACHE(PERF_COUNT_HW_CACHE_LL,PERF_COUNT_HW_CACHE_OP_READ,PERF_COUNT_HW_CACHE_RESULT_MISS)},
 {"task_clock", PERF_TYPE_SOFTWARE, PERF_COUNT_SW_TASK_CLOCK},
 {"context_switches", PERF_TYPE_SOFTWARE, PERF_COUNT_SW_CONTEXT_SWITCHES},
 {"cpu_migrations", PERF_TYPE_SOFTWARE, PERF_COUNT_SW_CPU_MIGRATIONS},
 {"page_faults", PERF_TYPE_SOFTWARE, PERF_COUNT_SW_PAGE_FAULTS},
 {"minor_faults", PERF_TYPE_SOFTWARE, PERF_COUNT_SW_PAGE_FAULTS_MIN},
 {"major_faults", PERF_TYPE_SOFTWARE, PERF_COUNT_SW_PAGE_FAULTS_MAJ}
};
static int event_count=20,raw_count=0;
static char raw_names[8][32];
#define EVENT_COUNT event_count
typedef struct { int fd, error; uint64_t raw, enabled, running; } Event;
typedef struct {
 char output[OUT_CAP]; size_t used; int truncated, exit_code, signal, timed_out, cancelled, affinity_error;
 double elapsed, freq_sum, freq_min, freq_max; int freq_samples;
 Event events[MAX_EVENTS];
} Run;
static volatile sig_atomic_t cancelled = 0;
static volatile sig_atomic_t active_pid = 0;
static void cancel_handler(int sig) { (void)sig; cancelled=1; if(active_pid>0) kill(-(pid_t)active_pid,SIGTERM); }
static double seconds_now(void) { struct timespec ts; clock_gettime(CLOCK_MONOTONIC,&ts);return ts.tv_sec+ts.tv_nsec/1e9; }
static void string_json(const char *s) {
 putchar('"'); for(const unsigned char *p=(const unsigned char *)(s?s:"");*p;p++) {
  if(*p=='"'||*p=='\\') {putchar('\\');putchar(*p);} else if(*p=='\n')fputs("\\n",stdout);
  else if(*p=='\r')fputs("\\r",stdout); else if(*p=='\t')fputs("\\t",stdout);
  else if(*p<32)printf("\\u%04x",*p);else putchar(*p);
 } putchar('"');
}
static void number_json(double n) { if(isfinite(n))printf("%.9g",n);else fputs("null",stdout); }
static int event_open(Event *e,int i,pid_t pid) {
 struct perf_event_attr a; memset(&a,0,sizeof(a));a.size=sizeof(a);a.type=specs[i].type;a.config=specs[i].config;
 a.disabled=1;a.inherit=1;a.exclude_kernel=1;a.exclude_hv=1;
 a.read_format=PERF_FORMAT_TOTAL_TIME_ENABLED|PERF_FORMAT_TOTAL_TIME_RUNNING;
 e->fd=(int)syscall(__NR_perf_event_open,&a,pid,-1,-1,PERF_FLAG_FD_CLOEXEC);
 e->error=e->fd<0?errno:0;
 return e->fd;
}
static void event_finish(Event *e) {
 if(e->fd<0)return;
 ioctl(e->fd,PERF_EVENT_IOC_DISABLE,0);
 uint64_t v[3];ssize_t n=read(e->fd,v,sizeof(v));
 if(n==(ssize_t)sizeof(v)) {e->raw=v[0];e->enabled=v[1];e->running=v[2];}
 else e->error=errno?errno:EIO;
 close(e->fd);e->fd=-1;
}
static double scaled(const Event *e) {return !e->error&&e->running>0 ? (double)e->raw*(double)e->enabled/e->running : NAN;}
static void events_json(const Event *events) {
 putchar('{');for(int i=0;i<EVENT_COUNT;i++) {const Event *e=&events[i];if(i)putchar(',');string_json(specs[i].name);
  int available=!e->error&&e->running>0;
  printf(":{\"available\":%s,\"raw\":",available?"true":"false");
  if(available)printf("%" PRIu64,e->raw);else fputs("null",stdout);
  fputs(",\"scaled\":",stdout);number_json(scaled(e));
  printf(",\"time_enabled_ns\":%" PRIu64 ",\"time_running_ns\":%" PRIu64 ",\"running_ratio\":",e->enabled,e->running);
  number_json(e->enabled>0?(double)e->running/e->enabled:NAN);
  printf(",\"errno\":%d,\"error\":",e->error);
  if(e->error)string_json(strerror(e->error));else if(!available)string_json("Event opened but was not scheduled");else fputs("null",stdout);
  putchar('}');
 }putchar('}');
}
static double read_freq(int cpu) {
 char path[160];snprintf(path,sizeof(path),"/sys/devices/system/cpu/cpu%d/cpufreq/scaling_cur_freq",cpu);
 FILE *f=fopen(path,"r");if(!f)return NAN;double khz=NAN;if(fscanf(f,"%lf",&khz)!=1)khz=NAN;fclose(f);
 return khz>0&&khz<1e8?khz/1000.0:NAN;
}
static void frequency_sample(Run *r,int cpu) {double f=read_freq(cpu);if(!isfinite(f))return;
 r->freq_sum+=f;if(!r->freq_samples||f<r->freq_min)r->freq_min=f;if(!r->freq_samples||f>r->freq_max)r->freq_max=f;r->freq_samples++;
}
static void drain(int fd,Run *r) {
 char b[4096];ssize_t n;while((n=read(fd,b,sizeof(b)))>0){size_t room=OUT_CAP-1-r->used;size_t take=(size_t)n<room?(size_t)n:room;
  memcpy(r->output+r->used,b,take);r->used+=take;r->output[r->used]=0;if(take<(size_t)n)r->truncated=1;}
}
static int run_child(const char *path,char *const args[],const char *input,int cpu,double limit,int measure,Run *r) {
 memset(r,0,sizeof(*r));for(int i=0;i<EVENT_COUNT;i++)r->events[i].fd=-1;r->exit_code=-1;
 int out[2],in[2],aff[2];if(pipe2(out,O_CLOEXEC)||pipe2(in,O_CLOEXEC)||pipe2(aff,O_CLOEXEC))return -1;
 pid_t pid=fork();if(pid<0)return -1;
 if(pid==0) {
  setpgid(0,0);prctl(PR_SET_PDEATHSIG,SIGKILL);signal(SIGTERM,SIG_DFL);signal(SIGINT,SIG_DFL);
  close(out[0]);close(in[1]);close(aff[0]);dup2(out[1],STDOUT_FILENO);dup2(out[1],STDERR_FILENO);dup2(in[0],STDIN_FILENO);
  close(out[1]);close(in[0]);cpu_set_t set;CPU_ZERO(&set);CPU_SET(cpu,&set);int err=sched_setaffinity(0,sizeof(set),&set)==0?0:errno;
  (void)write(aff[1],&err,sizeof(err));close(aff[1]);if(err){fprintf(stderr,"CPU affinity failed: %s\n",strerror(err));_exit(125);}
  raise(SIGSTOP);execv(path,args);fprintf(stderr,"execv failed: %s\n",strerror(errno));_exit(126);
 }
 setpgid(pid,pid);active_pid=pid;close(out[1]);close(in[0]);close(aff[1]);
 (void)read(aff[0],&r->affinity_error,sizeof(r->affinity_error));close(aff[0]);
 if(input)(void)write(in[1],input,strlen(input));close(in[1]);
 fcntl(out[0],F_SETFL,fcntl(out[0],F_GETFL)|O_NONBLOCK);
 int status=0;while(waitpid(pid,&status,WUNTRACED)<0&&errno==EINTR){}
 double start=seconds_now(),sample_at=start,cancel_at=0;
 if(WIFSTOPPED(status)) {
  if(measure)for(int i=0;i<EVENT_COUNT;i++)if(event_open(&r->events[i],i,pid)>=0)ioctl(r->events[i].fd,PERF_EVENT_IOC_ENABLE,0);
  kill(pid,SIGCONT);
  for(;;) {
   drain(out[0],r);pid_t done=waitpid(pid,&status,WNOHANG);if(done==pid)break;if(done<0&&errno!=EINTR)break;
   double now=seconds_now();
   if(measure&&now>=sample_at){frequency_sample(r,cpu);sample_at=now+.1;}
   if(cancelled||now-start>limit) {
    r->cancelled=cancelled!=0;r->timed_out=!cancelled;
    if(!cancel_at){kill(-pid,SIGTERM);cancel_at=now;}
    if(now-cancel_at>.3)kill(-pid,SIGKILL);
   }
   struct pollfd p={out[0],POLLIN,0};poll(&p,1,30);
  }
 }
 r->elapsed=seconds_now()-start;
 if(measure)for(int i=0;i<EVENT_COUNT;i++)event_finish(&r->events[i]);
 kill(-pid,SIGKILL);drain(out[0],r);close(out[0]);active_pid=0;
 if(WIFEXITED(status))r->exit_code=WEXITSTATUS(status);if(WIFSIGNALED(status))r->signal=WTERMSIG(status);
 return r->affinity_error?-1:0;
}
static double field(const char *text,const char *label) {const char *p=strstr(text,label);if(!p)return NAN;p+=strlen(label);
 while(*p==' '||*p=='\t'||*p==':')p++;char *end;double n=strtod(p,&end);return end!=p&&isfinite(n)?n:NAN;}
static int validate_elf(const char *path) {unsigned char h[20];FILE *f=fopen(path,"rb");if(!f)return 0;size_t n=fread(h,1,sizeof(h),f);fclose(f);
 return n==sizeof(h)&&!memcmp(h,"\177ELF",4)&&h[4]==2&&h[5]==1&&h[18]==183&&h[19]==0;}
static void phase(const char *name) {fputs("{\"event\":\"phase\",\"name\":",stdout);string_json(name);fputs("}\n",stdout);fflush(stdout);}
static int dhry_valid(const char *s,long long loops) {return field(s,"Int_Glob:")==5&&field(s,"Bool_Glob:")==1&&
 field(s,"Arr_1_Glob[8]:")==7&&field(s,"Arr_2_Glob[8][7]:")==loops+10&&field(s,"Int_1_Loc:")==5&&
 field(s,"Int_2_Loc:")==13&&field(s,"Int_3_Loc:")==7&&strstr(s,"Ch_1_Glob:           A")&&strstr(s,"Ch_2_Glob:           B");}
static long long adjust_iterations(long long loops,double actual,double target,int dhry) {
 double next=actual>.000001?loops*target*1.2/actual:loops*10.0;
 return (long long)fmin(dhry?1500000000.0:2000000000.0,fmax(1,next));
}
static int add_raw_event(const char *value) {
 size_t n=strlen(value);if(n<3||n>18||value[0]!='0'||(value[1]!='x'&&value[1]!='X')||raw_count>=8)return 0;
 for(size_t i=2;i<n;i++)if(!((value[i]>='0'&&value[i]<='9')||(value[i]>='a'&&value[i]<='f')||(value[i]>='A'&&value[i]<='F')))return 0;
 errno=0;char *end;uint64_t config=strtoull(value,&end,16);if(errno||*end)return 0;
 for(int i=20;i<EVENT_COUNT;i++)if(specs[i].config==config)return 0;
 snprintf(raw_names[raw_count],sizeof(raw_names[raw_count]),"raw_0x%" PRIx64,config);
 specs[event_count++]=(EventSpec){raw_names[raw_count++],PERF_TYPE_RAW,config};return 1;
}
static int read_text(const char *path,char *out,size_t size) {
 FILE *f=fopen(path,"r");if(!f){out[0]=0;return errno;}
 size_t n=fread(out,1,size-1,f);int err=ferror(f)?EIO:0;fclose(f);out[n]=0;
 while(n&&(out[n-1]=='\n'||out[n-1]=='\r'||out[n-1]==' '||out[n-1]=='\t'))out[--n]=0;return err;
}
static void optional_text(const char *dir,const char *name) {
 char path[1024],text[512];snprintf(path,sizeof(path),"%s/%s",dir,name);
 if(read_text(path,text,sizeof(text)))fputs("null",stdout);else string_json(text);
}
static int inventory_entries(const char *directory,int max,int *read_error) {
 DIR *d=opendir(directory);*read_error=d?0:errno;putchar('[');if(!d){putchar(']');return 0;}
 struct dirent *e;int count=0,truncated=0;
 while((e=readdir(d))) {
  if(e->d_name[0]=='.')continue;
  if(count>=max){truncated=1;break;}
  char path[1024],value[512];snprintf(path,sizeof(path),"%s/%s",directory,e->d_name);
  int err=read_text(path,value,sizeof(value));if(count++)putchar(',');fputs("{\"name\":",stdout);string_json(e->d_name);
  fputs(",\"encoding\":",stdout);if(err)fputs("null",stdout);else string_json(value);
  fputs(",\"error\":",stdout);if(err)string_json(strerror(err));else fputs("null",stdout);putchar('}');
 }
 closedir(d);putchar(']');return truncated;
}
static void inventory_json(void) {
 const char *base="/sys/bus/event_source/devices";DIR *d=opendir(base);int err=d?0:errno,inventory_truncated=0;
 fputs(",\"event_source_error\":",stdout);if(err)string_json(strerror(err));else fputs("null",stdout);
 fputs(",\"event_source_devices\":[",stdout);
 if(d) {
  struct dirent *e;int count=0;
  while((e=readdir(d))) {
   if(e->d_name[0]=='.')continue;
   if(count>=64){inventory_truncated=1;break;}
   char directory[768],path[1024],type[128];snprintf(directory,sizeof(directory),"%s/%s",base,e->d_name);snprintf(path,sizeof(path),"%s/type",directory);
   int type_error=read_text(path,type,sizeof(type));if(count++)putchar(',');
   fputs("{\"name\":",stdout);string_json(e->d_name);fputs(",\"type\":",stdout);
   char *end;long type_id=type_error?-1:strtol(type,&end,10);
   if(type_error||type_id<0||end==type||*end)fputs("null",stdout);else printf("%ld",type_id);
   fputs(",\"type_error\":",stdout);if(type_error)string_json(strerror(type_error));else fputs("null",stdout);
   fputs(",\"cpumask\":",stdout);optional_text(directory,"cpumask");fputs(",\"cpus\":",stdout);optional_text(directory,"cpus");
   int events_error=0,formats_error=0;
   fputs(",\"events\":",stdout);snprintf(path,sizeof(path),"%s/events",directory);int truncated=inventory_entries(path,128,&events_error);
   fputs(",\"formats\":",stdout);snprintf(path,sizeof(path),"%s/format",directory);truncated|=inventory_entries(path,32,&formats_error);
   fputs(",\"events_error\":",stdout);if(events_error)string_json(strerror(events_error));else fputs("null",stdout);
   fputs(",\"formats_error\":",stdout);if(formats_error)string_json(strerror(formats_error));else fputs("null",stdout);
   printf(",\"truncated\":%s,\"error\":",truncated?"true":"false");
   if(type_error)string_json(strerror(type_error));else if(events_error&&events_error!=ENOENT)string_json(strerror(events_error));
   else if(formats_error&&formats_error!=ENOENT)string_json(strerror(formats_error));else fputs("null",stdout);putchar('}');
  }
  closedir(d);
 }
 printf("],\"event_source_inventory_truncated\":%s",inventory_truncated?"true":"false");
}
static int do_probe(void) {
 Event events[MAX_EVENTS];memset(events,0,sizeof(events));
 for(int i=0;i<EVENT_COUNT;i++){events[i].fd=-1;if(event_open(&events[i],i,0)>=0)ioctl(events[i].fd,PERF_EVENT_IOC_ENABLE,0);}
 volatile uint64_t x=1;double end=seconds_now()+.06;while(seconds_now()<end)for(int j=0;j<10000;j++)x=x*1664525+1013904223;
 for(int i=0;i<EVENT_COUNT;i++)event_finish(&events[i]);
 cpu_set_t set;CPU_ZERO(&set);int affinity_ok=sched_getaffinity(0,sizeof(set),&set)==0;
 fputs("{\"event\":\"result\",\"result\":\"probe\",\"schema\":1,\"allowed_cpus\":[",stdout);
 int first=1;if(affinity_ok)for(int i=0;i<CPU_SETSIZE;i++)if(CPU_ISSET(i,&set)){if(!first)putchar(',');printf("%d",i);first=0;}
 fputs("],\"pmu\":",stdout);events_json(events);
 inventory_json();
 fputs(",\"scope\":\"Per-process user-space events; generic Linux perf mappings, unsupported events remain unavailable\"}\n",stdout);
 return 0;
}
int main(int argc,char **argv) {
 signal(SIGTERM,cancel_handler);signal(SIGINT,cancel_handler);signal(SIGPIPE,SIG_IGN);prctl(PR_SET_PDEATHSIG,SIGTERM);
 int cpu=-1,arg_count=0,probe=0;double target=10;const char *kind=NULL,*path=NULL;char *custom[MAX_ARGS];
 for(int i=1;i<argc;i++) {
  if(!strcmp(argv[i],"--probe"))probe=1;
  else if(!strcmp(argv[i],"--raw-event")&&i+1<argc){if(!add_raw_event(argv[++i])){fputs("Invalid raw event: require unique 0x hexadecimal values, 1-16 digits, at most 8\n",stderr);return 2;}}
  else if(!strcmp(argv[i],"--cpu")&&i+1<argc)cpu=atoi(argv[++i]);
  else if(!strcmp(argv[i],"--seconds")&&i+1<argc)target=strtod(argv[++i],NULL);
  else if(!strcmp(argv[i],"--kind")&&i+1<argc)kind=argv[++i];
  else if(!strcmp(argv[i],"--path")&&i+1<argc)path=argv[++i];
  else if(!strcmp(argv[i],"--arg")&&i+1<argc&&arg_count<MAX_ARGS-2)custom[arg_count++]=argv[++i];
  else {fprintf(stderr,"Invalid or missing argument: %s\n",argv[i]);return 2;}
 }
 if(probe)return do_probe();
 if(cpu<0||cpu>=CPU_SETSIZE||!isfinite(target)||target<1||target>120||!kind||!path||path[0]!='/'||!validate_elf(path)) {
  fputs("Invalid CPU/duration or executable; expected an absolute ARM64 ELF path\n",stderr);return 2;
 }
 int core=!strcmp(kind,"coremark"),dhry=!strcmp(kind,"dhrystone"),whet=!strcmp(kind,"whetstone"),is_custom=!strcmp(kind,"custom");
 if(!core&&!dhry&&!whet&&!is_custom)return 2;
 if(!is_custom&&target<10)target=10;
 printf("{\"event\":\"started\",\"pid\":%d}\n",getpid());fflush(stdout);
 Run *r=calloc(1,sizeof(Run)),*validation=calloc(1,sizeof(Run));if(!r||!validation)return 3;
 long long loops=core?1000:dhry?1000000:1000;char loop_arg[40],input[64];char *args[MAX_ARGS];
 args[0]=(char *)path;args[1]=NULL;int attempts=0,validation_ok=0;double calibration_seconds=0;
 if(!is_custom) {
  phase("calibration");
  for(attempts=0;attempts<5&&!cancelled;attempts++) {
   snprintf(loop_arg,sizeof(loop_arg),"%lld",loops);
   if(core){args[1]="0";args[2]="0";args[3]="0x66";args[4]=loop_arg;args[5]="7";args[6]="1";args[7]="2000";args[8]=NULL;}
   else if(whet){args[1]=loop_arg;args[2]=NULL;}
   else {snprintf(input,sizeof(input),"%lld\n",loops);args[1]=NULL;}
   int rc=run_child(path,args,dhry?input:NULL,cpu,15,0,r);calibration_seconds+=r->elapsed;
   if(rc<0||r->timed_out||r->cancelled||r->signal||r->exit_code==126)break;
   if(r->elapsed>=.18){double scaled_loops=loops*target*1.15/r->elapsed;loops=(long long)fmin(dhry?1500000000.0:2000000000.0,fmax(1,scaled_loops));break;}
   long long increased=adjust_iterations(loops,1,10,dhry);if(increased==loops)break;loops=increased;
  }
 }
 int prep_failed=r->affinity_error||r->timed_out||r->signal||r->exit_code==126;
 if(!prep_failed&&!cancelled) {
  snprintf(loop_arg,sizeof(loop_arg),"%lld",loops);snprintf(input,sizeof(input),"%lld\n",loops);
  if(core) {
   phase("crc_validation");args[1]="0x3415";args[2]="0x3415";
   for(int retry=0;retry<3&&!cancelled;retry++) {
    snprintf(loop_arg,sizeof(loop_arg),"%lld",loops);
    run_child(path,args,NULL,cpu,target*3+15,0,validation);
    double duration=field(validation->output,"Total time (secs)");
    if(validation->exit_code||validation->signal||validation->timed_out||!isfinite(duration)||duration>=target)break;
    long long more=adjust_iterations(loops,duration,target,0);if(more==loops)break;loops=more;
   }
   validation_ok=validation->exit_code==0&&strstr(validation->output,"Correct operation validated")&&field(validation->output,"Total time (secs)")>=10;
   args[1]="0";args[2]="0";
  }
  if(!cancelled) {
   phase("measurement");
   if(is_custom){for(int i=0;i<arg_count;i++)args[i+1]=custom[i];args[arg_count+1]=NULL;}
   for(int retry=0;retry<3&&!cancelled;retry++) {
    snprintf(loop_arg,sizeof(loop_arg),"%lld",loops);snprintf(input,sizeof(input),"%lld\n",loops);
    run_child(path,args,dhry?input:NULL,cpu,is_custom?target:target*3+15,1,r);
    double duration=core?field(r->output,"Total time (secs)"):whet?field(r->output,"Duration:"):r->elapsed;
    if(is_custom||r->exit_code||r->signal||r->timed_out||!isfinite(duration)||duration>=target)break;
    long long more=adjust_iterations(loops,duration,target,dhry);if(more==loops)break;loops=more;
   }
  }
 }
 double score=NAN,bench_time=r->elapsed;const char *unit="",*detail="Custom executable: no standard benchmark score";
 int valid=0;const char *state=cancelled||r->cancelled?"cancelled":r->timed_out?"timeout":r->exit_code||r->signal||r->affinity_error?"failed":"completed";
 if(core){score=field(r->output,"Iterations/Sec");bench_time=field(r->output,"Total time (secs)");unit="CoreMark";
  valid=!strcmp(state,"completed")&&validation_ok&&bench_time>=10&&strstr(r->output,"Correct operation validated")&&isfinite(score)&&score>0;
  detail="Official unmodified CoreMark algorithms; 2000-byte heap, one context, both seed sets CRC-checked for >=10s. Dynamic frequency normalization is an estimate, not an official CoreMark/MHz submission.";
 } else if(dhry){score=field(r->output,"Dhrystones per Second")/1757.0;unit="DMIPS";
  valid=!strcmp(state,"completed")&&r->elapsed>=10&&dhry_valid(r->output,loops)&&isfinite(score)&&score>0;
  detail="Dhrystone 2.1; original workload, Android declarations/timer adaptation, separate compilation without LTO; DMIPS = runs/s divided by 1757. Final values checked.";
 } else if(whet){score=field(r->output,"C Converted Double Precision Whetstones");unit="MWIPS";
  if(strstr(r->output," KIPS"))score/=1000.0;
  valid=!strcmp(state,"completed")&&r->elapsed>=10&&isfinite(score)&&score>0;
  detail="Netlib Whetstone C 1.2 double precision, monotonic timer port; no authoritative CRC self-test. Compiler flags affect this synthetic benchmark.";
 }
 double sampled=r->freq_samples?r->freq_sum/r->freq_samples:NAN;
 double cycles=scaled(&r->events[0]),instructions=scaled(&r->events[1]),task_ns=scaled(&r->events[14]);
 double effective=task_ns>0&&cycles>0?cycles/task_ns*1000.0:NAN;
 double frequency=isfinite(effective)?effective:sampled;
 fputs("{\"event\":\"result\",\"schema\":1,\"result\":",stdout);string_json(state);
 fputs(",\"kind\":",stdout);string_json(kind);printf(",\"cpu\":%d,\"requested_seconds\":%.3f,\"elapsed_seconds\":%.6f,\"calibration_seconds\":%.6f,\"exit_code\":%d,\"signal\":%d,\"affinity_errno\":%d,\"timed_out\":%s,\"output_truncated\":%s",cpu,target,r->elapsed,calibration_seconds,r->exit_code,r->signal,r->affinity_error,r->timed_out?"true":"false",r->truncated?"true":"false");
 fputs(",\"score\":{\"value\":",stdout);number_json(score);fputs(",\"unit\":",stdout);string_json(unit);printf(",\"valid\":%s,\"detail\":",valid?"true":"false");string_json(detail);fputs("},\"benchmark_time_seconds\":",stdout);number_json(bench_time);
 printf(",\"frequency\":{\"samples\":%d,\"sampled_mean_mhz\":",r->freq_samples);number_json(sampled);fputs(",\"sampled_min_mhz\":",stdout);number_json(r->freq_samples?r->freq_min:NAN);fputs(",\"sampled_max_mhz\":",stdout);number_json(r->freq_samples?r->freq_max:NAN);fputs(",\"effective_mhz\":",stdout);number_json(effective);
 fputs(",\"normalization_source\":",stdout);string_json(isfinite(effective)?"user_cycles/task_clock":isfinite(sampled)?"100ms cpufreq sampling":"unavailable");fputs("},\"score_per_mhz\":",stdout);number_json(valid&&frequency>0?score/frequency:NAN);
 fputs(",\"ipc\":",stdout);number_json(cycles>0?instructions/cycles:NAN);fputs(",\"pmu\":",stdout);events_json(r->events);
 fputs(",\"pmu_scope\":\"Measurement process plus inherited children; userspace events; independently multiplexed generic perf events, raw counts with scaling and running ratio\",\"benchmark_stdout\":",stdout);string_json(r->output);
 fputs(",\"validation_stdout\":",stdout);string_json(validation->output);fputs("}\n",stdout);fflush(stdout);
 free(r);free(validation);return 0;
}
