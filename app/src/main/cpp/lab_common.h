#ifndef PULSE_LAB_COMMON_H
#define PULSE_LAB_COMMON_H
#include <stdint.h>
#include <signal.h>
extern volatile sig_atomic_t lab_stop;
double lab_now(void);
double lab_cpu_time(void);
long lab_read_long(const char *path);
int lab_init(int cpu);
int lab_cap_begin(int cpu, long cap);
int lab_cap_end(void);
void lab_measure_begin(int cpu);
void lab_measure_end(void);
void lab_telemetry_json(double elapsed, double cpu_seconds);
int lab_sve_bits(void);
int lab_sme_bits(void);
unsigned long lab_hwcap(void);
unsigned long lab_hwcap2(void);
#endif
