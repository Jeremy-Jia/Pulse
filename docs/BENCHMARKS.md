# 原生性能测试实现与边界

## 数据来源

- CoreMark：EEMBC 官方仓库，commit `1f483d5b8316753a742cbf5590caf5bd0a4e4777`，Apache-2.0。`core_list_join.c`、`core_main.c`、`core_matrix.c`、`core_state.c`、`core_util.c` 和 `coremark.h` 保持原样。
- Dhrystone 2.1：Keith S. Thompson 保管的历史源码，commit `66bb9df1a5dea67f33437b856bf68ae52bd5c90f`。保留完整作者、README 和历史说明；这份历史发行没有附带明确现代许可证，不将其误标为 Apache/MIT/GPL。
- Whetstone：Netlib 的 C Double Precision 1.2（1998）。原文明确允许使用、复制、发布，要求保留完整版权注释；该注释完整保留。

所有下载文件的 SHA-256 均记录在 `app/src/main/cpp/vendor-lock.json`，每次原生构建先核对。厂商源码留在 `vendor/`，平台适配仅生成到 `app/build/native/ported/`，不会覆盖原文件。

官方 `coremark.md5` 的五个 C 算法文件校验全部匹配。上游随后在 commit `4ee6ecaa9a081bc3378afc0f58f2f35b61f10b55` 将头文件注释 `ultithread` 改为 `multithread`，但未同步 MD5 清单，因此当前官方头文件与旧 MD5 不同；本项目保留当前官方原文件，并以锁定 commit 的 SHA-256 校验。

源链接：

- <https://github.com/eembc/coremark>
- <https://github.com/Keith-S-Thompson/dhrystone>
- <https://www.netlib.org/benchmark/whetstone.c>

## 编译和适配

共同使用 NDK r29 / Clang 21，Android API 26 ARM64，`-O2 -fno-lto -fPIE -pie`，ELF LOAD 段最大页大小 16KB。Dhrystone 和 Whetstone 另使用 `-fno-builtin -fno-inline-functions`，避免跨库函数替换及内联造成无法比较的变化。

CoreMark 仅在其允许修改的 port 层改为 `CLOCK_MONOTONIC`，声明单上下文、堆内存和编译参数。Dhrystone 仅适配 LP64 的函数声明、增加现代标准头文件、按 `sysconf(_SC_CLK_TCK)` 解释 `times()`，以及确定成功退出码。Whetstone 仅替换整数秒时钟为单调高精度时钟并调整时长输出类型；运算段不改。

## 运行协议

```text
libpulsebench.so --probe
libpulsebench.so --probe --raw-event 0x11
libpulsebench.so --cpu 6 --seconds 10 --kind coremark --path /absolute/path/libcoremark.so
libpulsebench.so --cpu 6 --seconds 10 --kind dhrystone --path /absolute/path/libdhrystone.so
libpulsebench.so --cpu 6 --seconds 10 --kind whetstone --path /absolute/path/libwhetstone.so
libpulsebench.so --cpu 6 --seconds 20 --kind custom --path /absolute/path/case.elf --arg first --arg second
```

正式跑分同样接受重复的 `--raw-event 0xNN` 参数。每个值须带 `0x` 前缀、含 1–16 位十六进制字符，最多 8 个，重复配置被拒绝。它们使用 `PERF_TYPE_RAW` 原样请求，JSON 名称为 `raw_0x...`；不猜测 Qualcomm 专用代码含义，不将其他 PMU 类型的编码误当作 CPU 原始事件。

参数以 `execv` 逐项传递，不经过 shell。可执行文件须为 ARM64 ELF；绑核失败会直接失败，不偷偷换核。标准项目先作短时校准，随后运行指定时长附近的完整迭代。CoreMark 还独立运行验证种子，因此完整总时间约为目标时长的两倍以上。标准项目目标至少 10 秒；自定义项目的 `--seconds` 是最长运行时间，超时不会当作成功。

stdout 为逐行 JSON：

- `event=started`：`pid` 是包装器 PID，供应用取消。
- `event=phase`：`calibration`、`crc_validation`、`measurement`。
- `event=result`：结果、分数、频率、PMU 和完整标准程序输出。

包装器接收 SIGTERM/SIGINT 后终止基准进程组，逾期升级为 SIGKILL。每次子进程均有时限。子进程设置父进程死亡信号，防止包装器退出后继续占用 CPU。正常结束也清理自定义程序遗留的同组子进程。

## 分数与频率

CoreMark 使用 2000-byte 输入、单上下文，两组标准种子。只有两组均通过 CRC 且各自正式计时至少 10 秒，`score.valid` 才为真。`benchmark_stdout` 和 `validation_stdout` 留存原文供核对。

Dhrystone 输出 DMIPS（每秒 Dhrystone 次数除以 1757），核对关键最终变量。Whetstone 输出 MWIPS；其上游程序没有同等级的 CRC 自检，所以界面不能宣称经过官方 CRC 认证。

`score_per_mhz` 优先使用 `cycles / task-clock` 得到的有效 MHz，task-clock 不可用时尝试 wait4 进程 CPU 时间；硬件计数器不可用时使用每 100ms 读取的 `scaling_cur_freq` 均值。两者均为动态频率下的归一化估计；不是固定频率实验，也不满足官方 CoreMark/MHz 对内存/缓存频率比披露的完整条件。不会锁频、调压、改 CPU 调度器或写 sysctl。系统若改变绑核范围或运行核心，结果会标记亲和性不稳定，不提供指定核心的每 MHz 成绩。

## PMU

通过 Linux `perf_event_open` 读取进程级用户态事件，并包含继承的子任务。计数覆盖正式性能进程（包含极少量启动和输出工作），不包含校准和 CoreMark 验证进程。提供通用 cycles、instructions、branch、cache、L1D/LLC、stalls、task-clock、切换、迁移和缺页事件。

每项分别报告 `available`、`raw`、`scaled`、`time_enabled_ns`、`time_running_ns`、`running_ratio`、`errno` 和 `error`。不支持或权限拒绝保留原错误，计数为 null。复用计数按 enabled/running 比例缩放；cycles 和 instructions 作为同一事件组调度，IPC 使用这两个事件的缩放值；其他事件可能在不同时间片运行，相关比值必须结合运行比例解释。通用 cache 和 stall 事件依赖内核/SoC 映射，并不声称覆盖全部 Qualcomm 专用 PMU。

探测只测试当前权限下事件是否能打开和调度；真实跑分仍可能因内核策略、后台环境或所选 CPU 不同而拒绝部分事件。

`--probe` 还返回 `/sys/bus/event_source/devices` 的实际目录清单：名称、type、cpus/cpumask、events 的原始编码和 format。最多列出 64 个设备、每设备 128 个事件及 32 个 format；这些信息用于查看内核公开的能力，不代表任意专用 PMU 已能通过当前通用事件接口采集。读取失败、无编码和不支持分别保留为错误或空值。

非 Root 的访问模式、ADB 激活及限制见 [NON_ROOT.md](NON_ROOT.md)。
