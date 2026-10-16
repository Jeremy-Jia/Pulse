# Pulse 设备数据来源与解释

验证设备：Xiaomi 15（dada），HyperOS OS4.0.0.19.XOCCNXM，Android 17，KernelSU。验证时间：2026-10-16。应用通过用户授予的 Root 读取性能计数；不修改频率、调度器、温控策略、系统分区或 SELinux。

## 读数语义

| 指标 | 来源 | 换算和边界 |
| --- | --- | --- |
| CPU 总体 / 每核使用率 | `/proc/stat` | 两次采样的 busy / total 差分；第一帧不显示伪造的 0。idle 包含 iowait；不重复计算 guest。 |
| 每核频率 | `cpuN/cpufreq/scaling_cur_freq` | 内核 kHz 转 MHz；此节点通常表示共享策略的请求频率，不能当成每核实际执行时钟。 |
| 最高频率 / 核心类别 | `cpuinfo_max_freq`、`related_cpus` | 本机 0–5 为 3.5328 GHz 策略，6–7 为 4.32 GHz 策略。仅 dada 且策略/频率匹配时标为性能核 / 超大核；不把性能核叫小核。其他机型显示中性的频率簇名称。 |
| CPU 平均 / 最高温度 | 按 `thermal_zone*/type` 发现具名 CPU 传感器 | milli°C 转 °C；平均值为已识别 CPU die 传感器的算术平均，而非所有 thermal zone 混合平均。 |
| 单个温度传感器 | `thermal_zone*/type` 与 `temp` | 显示原传感器名称，稳定 ID 由名称生成，不硬编码 zone 编号。排除 bcl、ibat、vbat、socd 等非温度意义节点。 |
| 每逻辑核温度 | 尚无可验证映射 | 本机存在 16 个 cpu-* 温度传感器，不能未经验证映射为 8 个逻辑核。逐核温度明确显示不可用，所有真实传感器可独立选择。 |
| GPU 频率 | Qualcomm KGSL `gpuclk` | Hz 转 MHz。 |
| GPU 使用率 | Qualcomm KGSL `gpubusy` | 本次读出的 busy / total 时间比例。此节点是读取区间的计数，可能受其他监控工具同时读取影响。 |
| GPU 温度 | KGSL `temp`，具名 gpuss thermal sensors | milli°C 转 °C；同时提供 GPU 传感器最高值和各传感器。 |
| 可用 / 已用内存 | `/proc/meminfo` 的 MemAvailable、MemTotal | 可用内存包含可回收缓存。已用 = 总量 − 可用；GB 为十进制 10⁹ 字节。无法读 proc 时使用 ActivityManager。 |
| 交换空间 | SwapTotal − SwapFree | 包含 zRAM 等交换空间，不能解释为物理 RAM 占用。 |
| 电池电流 / 电压 | `power_supply/battery/current_now`、`voltage_now` | µA 转 mA，µV 转 V。保留驱动的电流正负号，另显示系统充电状态；不猜各厂商符号方向。 |
| 电池净功率 | `abs(I) × V` | 是电池端的净功率幅值，不是整机输入、充电器功率或 SoC 单独功耗。USB 接通且满电时电流读数 0 是可能的真实状态。 |
| 电池温度 / 电量 | battery temp、capacity，或 Android 电池广播 | sysfs battery temp 为 0.1°C。支持剩余容量、循环次数（节点可用时）。 |
| 屏幕刷新率 | Display.getRefreshRate | Hz，只是刷新速率，绝不当成应用 FPS。 |
| 前台实际呈现帧率 | SurfaceFlinger `--latency` 第 2 列 | 依据前台图层的 actual-present 时间。过滤零、最大整型和重复/异常时间戳。当前本机该列重复不变，因此不可宣称已支持真实呈现 FPS；自动退到下述 frameReady 时，主项名称会明确变成“绘制帧率”，说明中保留限制。 |
| 前台图层更新率 | SurfaceFlinger `--latency` 第 3 列 | 依据 frameReady 的最近 1 秒完成记录。可反映图层更新/绘制活动，不保证每帧实际呈现，也不是刷新率；静止画面可显示 0。 |
| SoC 独立功耗 | 暂无验证的电源轨计量 | 显示不可用，不用电池功率冒充。 |

## 可用性与性能

- 每个读数包含 available、source、原单位和数值。缺失、无权读取、不可信的读数显示“—”；不生成随机或演示数字。
- Root 使用一个持久 su 子进程和逐命令结束标记；每批读取有 4 秒超时。授权失败后延迟重试，避免连续触发授权弹窗。停止监控时关闭会话。
- thermal 目录按名称发现一次，后续通过 shell 内建 read 批量读取。固定协议标记用内建 echo，动态值用 mksh 内建 `print -r --` 保留选项样式/反斜杠/空值；不使用 Android 上实际为外部程序的 printf，避免每个节点启动两个进程。前台图层每 3 秒重新发现。
- App Root 授权与 ADB shell 的 Root 授权相互独立，用户需要在 KernelSU 给 Pulse 授权。
- `sample_duration` 给出当前一轮采样耗时，并分为 `sample_core_ms`、`sample_platform_ms`、`sample_thermal_ms`、`sample_frame_ms`，便于识别系统诊断接口造成的额外开销。
- 性能修复验证：同一持久 su 脚本中，旧 printf 协议的核心/热传感器读取分别约 669–703 ms / 1378–1756 ms；改为内建输出后的相同节点分别约 26–47 ms / 34–44 ms。此为 USB 调试下读取协议实测，应用完整采样耗时应以设备内的分段指标为准。
- 最终设备内 instrumentation 验证通过：首轮完整采样 81 ms，第二轮 137 ms（核心 53 ms、温度 54 ms、帧率 27 ms、平台接口 3 ms）。Root 有效，第二轮 129/144 个指标可用。服务按“配置周期减去采样用时”安排下一次读取，而非读完后额外再等一个周期；500 ms 配置能在当前实测开销下维持约 500 ms 周期。
- 最终报告共发现 74 个温度节点，其中 70 个可读；无线充电和 3 个射频传感器未提供数据，明确标不可用。每次 read 前清空接收变量，防止驱动报无数据时沿用上一个节点的温度。两轮 CPU 平均温度均与 16 个具名 CPU 传感器的算术平均完全一致；没有删除真实的短时高温读数。

## FPS 验证记录

对本机现有桌面图层查询，`--latency` 的 actual-present 列在多帧之间重复相同旧时间戳，frameReady 列会更新。`dumpsys gfxinfo com.miui.home framestats` 只返回 Impeller GPU 内存信息，没有标准 PROFILEDATA。临时开启 TimeStats 2 秒后得到的图层名称为 `none`，不能可靠定位前台应用；该试验已停用并清除只由试验产生的统计，应用不依赖此开关。参考工具 APK 仅被查阅数据源字符串；没有复制其代码或资产。

真实 UI 截图已验证主项自动改名“绘制帧率”，静态仪表盘读数为 1 fps，独立于屏幕的 60 Hz。最终 instrumentation 的两轮样本未获得有效前台帧记录，显示不可用；该集成测试本身不能证明所有游戏或视频的实际呈现 FPS 均受支持。

## 官方接口参考

- Linux thermal sysfs：[接口文档](https://www.kernel.org/doc/Documentation/thermal/sysfs-api.txt)
- Linux power supply 单位：[接口文档](https://www.kernel.org/doc/Documentation/power/power_supply_class.txt)
- Linux CPUFreq 频率含义：[内核文档](https://docs.kernel.org/admin-guide/pm/cpufreq.html)
- Android SurfaceFlinger FrameTracker：[AOSP 源码](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/main/services/surfaceflinger/FrameTracker.cpp)
