/* Pulse Android port configuration; CoreMark algorithms remain unmodified. */
#ifndef PULSE_COREMARK_PORT_OVERRIDES
#define PULSE_COREMARK_PORT_OVERRIDES
#define COMPILER_FLAGS "Clang 21.0.0 -O2 -fno-lto -fPIE -pie, Android arm64 API26"
#define MEM_LOCATION "Heap in Android process; dynamic CPU/memory clocks, ratios not controlled"
#define MULTITHREAD 1
#define HAS_FLOAT 1
#define HAS_TIME_H 1
#define HAS_STDIO 1
#define HAS_PRINTF 1
#define USE_CLOCK 0
#define ITERATIONS 0
#define PERFORMANCE_RUN 1
#endif
