# 18 Pro 温控生效检查与 15 开机恢复修复

日期：2026-10-20
设备：Xiaomi 18 Pro / hongkong / 495fb913；Xiaomi 15 / dada / 3318667e
版本：1.5.1 / versionCode 7

## 18 Pro 当前生效状态

当前会话 `1792503405409`，`mode=boost`、`boost_percent=100`、`resume=0`：

```text
state=active
max0=3744000  max3=4032000  max6=5011200
battery_limit=500 (50.0°C)
body_limit=50000 (50.0°C)
cpu_limit=105000 (105°C)
reason=NONE
```

- 应用进程的 mountinfo 中可见
  `/data/adb/pulse-thermal/sessions/.../runtime-config` 绑定到
  `/data/vendor/thermal/config`；这是 18 的运行时配置覆盖，不是 UI 假象。
- `watch` 与 `sentry` 守护进程都在运行；遥测文件持续更新。
- 670 个连续样本全部为 `active`、场景 0，覆盖约 748 秒：
  - `max0=3.744 GHz` 出现 609 次；
  - `max3=4.032 GHz` 出现 609 次；
  - `max6=5.0112 GHz` 出现 511 次，`4.8576 GHz` 出现 95 次。
- 实际当前频率最高观察到 CPU0 3.744 GHz、CPU3 4.032 GHz、CPU6 4.9536 GHz；
  p95 当前频率分别为 1.536 / 2.4576 / 0.9024 GHz，说明这是“上限放开”，不是锁频。
- 样本温度：CPU 热点 39.4–89.9°C，电池 32.3–34.2°C，估算机身 32.5–34.5°C；
  当前快照为 CPU 40.4°C、电池 32.2°C、机身 32.6°C。

结论：18 的温控策略确实加载并生效，三组频率上限和 Boost 档可用；5.0112 GHz 是上限，
实际频率仍由调度、场景和温度决定。此前受控 Dhrystone 对照为 +77.38%，那是 4.704 GHz
上限下的单核短时结果，不外推到当前 5.0112 GHz 或长时满载。

## 15 开机自动恢复的问题与修复

原实现只在 `Policy.save()` 中保存 `resume` 偏好；运行中切换「重启后继续」时，当前会话的
`policy.env` 仍是 `resume_boot=0`。启动钩子虽然存在，开机时只恢复原厂、不会重新应用策略。
这会造成“界面显示已开启，重启后却没有温控”的现象。

修复：

- `RootEngine.updateBootResume()`：对 15 当前 `active/paused` 会话执行
  `sed -i 's/^resume_boot=.*/resume_boot=1/'`，并确保
  `/data/adb/service.d/70-pulse-thermal.sh` 存在、`root:root`、`700`；关闭时删除钩子。
- `ThermalHub` 在后台线程调用并反馈结果。
- `MainActivity` 在「重启后继续」切换时立即同步；切回定时模式时同时关闭启动钩子。
- 18 的 `runtimeMount=true`，方法直接返回，不改变“仅本次开机”的既有行为。

## 15 实际重启验证

2026-10-20 系统更新到 `OS4.0.0.22.XOCCNXM` 后 KernelSU 被覆盖，15 已解锁 BL。使用
OS3.0.307 线刷包里的 `init_boot.img`，由管理器内的 `ksud boot-patch` 选择
`android15-6.6` 内置 LKM 修补后，只刷当前槽位 `init_boot_b`。重启后 KernelSU 3.3.1
恢复，`su -c id` 为 uid 0，`/proc/modules` 显示 `kernelsu 180224 ... Live`。

温控兼容性：

- `/vendor/bin/mi_thermald` SHA-256 = `d6ddd1c6…6075`，匹配 `ConfigCodec.BINARY_SHA`；
- `/odm/etc/thermal-normal.conf` SHA-256 = `c121ffb8…0641`，匹配 `CIPHER_SHA`；
- OS4.0.0.22 虽不是文档最初列出的 19.D01，但这两项厂商材料与 MI15 白名单完全一致。

应用保存的持续运行配置（全频、3.5328/4.32 GHz、50/50/105°C、`resume=1`）已重新应用：

```text
session=1792506519022  phase=active
max0=3532800  max6=4320000
resume=1
```

清空 `boot.log` 后真实重启，得到：

```text
boot_id=ebabda2e-8cf7-4bef-abf0-bc3c74ae4342
boot.log=OK ACTIVE 1792506519022
snapshot.state=active  elapsed=8  resume=1
watch/sentry 两个 root 守护均在运行
```

结论：15 的开机恢复链路已经真实跑通，启动钩子执行、恢复原厂、校验并重新应用了同一会话。
此前 `boot.log` 里的 `ERROR NORMAL_PROFILE_REQUIRED` 是更早一次场景不匹配留下的旧记录；
这次重新应用并清空日志后为 `OK ACTIVE`。

仍可通过以下命令复查：

```sh
adb shell su -c 'cat /data/adb/pulse-thermal/boot.log'
adb shell su -c 'ls -lZ /data/adb/service.d /data/adb/service.d/70-pulse-thermal.sh'
adb shell su -c 'S=$(cat /data/adb/pulse-thermal/current); cat /data/adb/pulse-thermal/sessions/$S/policy.env; cat /data/adb/pulse-thermal/sessions/$S/phase'
```

- `boot.log` 为空/不存在：Root 管理器没有执行 `service.d` 钩子，需检查 KernelSU/Magisk 的
  启动脚本支持，而不是继续改应用内偏好。
- `policy.env` 的 `resume_boot=0`：当前会话仍未写入开机继续，需要重新应用或运行修复后的切换。
- 日志出现 `ERROR ...`：把该行和 `phase` 一起提供，才能继续定位是恢复失败、场景不匹配
  还是启动超时。
