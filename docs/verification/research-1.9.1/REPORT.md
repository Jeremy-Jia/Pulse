# Pulse 1.9.1：限频请求恢复与温控守护

2026-10-23，hongkong / M610BB，Android 17，临时 KernelSU，使用无线 ADB。`versionCode 12`。同签名覆盖安装，未卸载、未清数据。小米 15 未连接，本轮没有宣称完成其硬件回归。

## 确认的问题与修复

1. 旧能效扫描读取 `scaling_max_freq`（所有请求合并后的有效上限），退出时却将读值写回用户 QoS 请求。系统在两档之间降频时，扫描可能把低上限锁存。新版本使用 `/sys/kernel/msm_performance/parameters/cpu_max_freq` 的独立原始请求；保留无限制哨兵和已有非默认限制，恢复验证比较原始请求，不要求系统动态有效上限保持不变。其他程序接管同一请求时不覆盖，报告恢复未确认。
2. 接手时旧会话磁盘 phase 为 active，主/第二守护 PID 已被无关应用复用，修改后的配置仍挂载/加载。没有足够证据认定两个旧进程退出的具体原因。新守护通过 setsid 和根 cgroup 脱离应用生命周期，记录 PID、启动时钟与命令行身份；用心跳识别无响应。双守护相互检查；两者都失效时，应用下次连接恢复原厂并核对，而非显示“运行中”。
3. UI、通知与记录在 Root 状态未知时显示待核对；恢复中、恢复失败、守护失效有独立状态。重新进入正在运行的会话会接回前台通知服务。控制器升级先退回原厂，再迁移正在运行的不限时原策略；不延长计时，也不跳过等待降温。
4. 实测发现并修复两项竞态：重启系统温控服务时 GPU 用户档位会短暂变化，启动保留更严格基线；心跳检查必须先读对方心跳再读当前时钟，否则跨秒读取会将正常心跳判为未来值。应用核对为系统调度暂停后的心跳刷新留出短暂窗口，启动过程也有独立宽限时间。

背景与硬件事实：`cpuinfo_max_freq` 及 Boost 表提供 5011200 kHz；恢复的是可选上限，不保证所有负载、系统场景或温度下持续在该频率运行。系统其他场景仍可限制频率。场景 7 时会话保持、策略暂停，普通场景返回后自动恢复，状态如实显示。

## 验证证据

- [frequency-restoration.json](frequency-restoration.json)：正常完成 8192 次签名、TERM、KILL、原先已有非默认请求、运行中外部有效上限改变、同一原始请求被其他工具接管。并发第二所有者被拒绝。外部有效上限改变保留在 2438400 kHz，没有被错误覆盖；测试最终还原至 5011200 kHz。
- [efficiency-service.json](efficiency-service.json)：实际安装的 Pulse 前台服务执行完整五档扫描；每档 8192 次签名、6 个 KAT、全部验签、亲和性和跨路径签名比对通过；每档结束后原始 QoS 请求为 2147483647。该轮系统初始有效上限为 3782400 kHz，扫描遵守它，并未声称已测到 5.01 GHz。
- [thermal-guards.json](thermal-guards.json)：强制停止 App 后同一会话和心跳保留，重新进入 UI 正确；分别杀死任一守护，由另一个恢复并验证原厂明文哈希；双守护被杀显示 unprotected；无关活进程占用 PID 文件时不被误杀；冻结守护且心跳过期可识别恢复；模拟系统整体调度间隙恢复不误停；强制传入过期的调用者时钟仍接受真实新心跳。
- [background-observation.json](background-observation.json)：不依赖 App 前台时，系统场景 7 下两个守护仍持续刷新心跳，保留 paused 会话。
- [force-stop-during-scan.json](force-stop-during-scan.json)：Pulse 实际扫描已经施加 1708800 kHz 请求时强制停止 App，原始请求恢复到 2147483647，温控仍为同一 active 会话。
- [final-state.json](final-state.json) 与 [控制台截图](dashboard.png)：最后连续读取均为 active、双守护健康；实际 CPU 上限为 3744000 / 4032000 / 5011200 kHz，GPU 上限 1450000000 Hz。最后一条 CPU 热点 45.4°C、电池 36.1°C、估算机身 37.012°C。

故障测试主动结束并重建会话，因此测试期间会看到原厂状态与短暂的驱动上限变化。最后重新应用用户原有的不限时 fullmax 设置：电池 50°C、机身反馈 52°C、CPU 独立保护 105°C、自动降温恢复开启。没有提高这些阈值。高温边界沿用既有实现，本轮没有故意加热至保护温度。

## 构建与复现

`assembleDebug`、`assembleDebugAndroidTest`、`lintDebug` 通过。Lint 的既有非 SDK 接口等警告保留，无错误。APK 使用已有 Mac 开发证书，SHA-256 `540e097b6be81963997ca6b0bf0906116e3782591cd4f4962a8fd504b5b62893`，私钥未入库。

发布 APK SHA-256：`4e4733e739168e42cd3e5bf94218071c8935158d391f75939930fc216e113f56`。

```powershell
python tools/test_frequency_restore.py --adb <adb.exe> --device <serial>
python tools/test_thermal_guards.py --adb <adb.exe> --device <serial>
adb shell am instrument -w -e research true -e research_scope efficiency dev.pulse.monitor.test/dev.pulse.monitor.MonitorInstrumentation
```

这些是显式硬件测试，会暂时修改频率请求、杀死测试守护并恢复原厂。需要已授权 Root、原先已运行的不限时会话、手机解锁且保持普通场景；不要与其他调优工具同时运行。恢复是可观测、有失败状态的过程，不宣称进程永远不会被杀。临时 Root 的重启限制保持原样。
