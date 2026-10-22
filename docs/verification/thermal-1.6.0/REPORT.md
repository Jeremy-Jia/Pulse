# Pulse 1.6.0 · 小米 18 GPU 曲线控制

2026-10-22；hongkong / OS4.0.24.0.XFRCNXM / Android 17 / 临时 KernelSU。包名 `dev.pulse.monitor`，versionCode 8。沿用 Mac 开发证书覆盖安装，未卸载主应用、未清数据、未重启或刷分区。小米 15 的 CPU 方案和 GPU 配置保持原样，本次未连接 15 实机复测。

## 功能与边界

「策略」页新增独立 GPU 开关（默认关闭）与 26 档 GPU 曲线目标；控制台显示实际 GPU 当前频率和上限。只允许已匹配的 hongkong 固件和完整 GPU 频率顺序，在普通配置中修改 `[MONITOR-GPU]` 六个目标索引，不改触发温度/回落滞后、BCL、充电或其他底层保护。低温未触发该曲线时不强制锁频。GPU 与 CPU 共用停止线、定时器和两个独立 Root 守护，恢复时加载逐字节匹配的原厂配置，移除自己的运行时挂载。

## 验证

- 主 APK 和验证 APK 编译通过；`git diff --check` 通过。
- `Thermal18CodecTest`：原厂编码往返一致，GPU 关闭时保留历史 CPU 明文字节；所有 26 档 GPU 索引映射、仅指定目标字段变化、非法频率拒绝通过；15 不开放 GPU。
- 实际 Pulse UID 申请 Root 并应用 GPU 曲线，KGSL 实际上限变为 1,450,000,000 Hz。配置加载和驱动执行异步，首次检查需要等待数秒，不能只按明文哈希宣称实际频率已变化。
- 手动恢复、一分钟定时恢复、主守护 SIGKILL 后独立守护恢复均通过；每次恢复核对原厂明文哈希、运行时挂载移除、原厂覆盖目录为空。
- CSV 真实导出通过；修正首次采样前导出误返回失败、空错误被误报 Root 拒绝的问题。
- 从真实界面启用 GPU 并应用 CPU+GPU 策略；保留用户电池 50°C、机身 50°C、CPU 热点 105°C 停止线。

原始测试输出见 [device-checks.txt](device-checks.txt)，实际加载策略与版本见 [policy-verification.txt](policy-verification.txt)。

## CPU 调查

设备 `dynamic_tj` 实读为 95000，原厂普通配置也将 `MONITOR-DYNAMIC-TTJ` 目标设为 95000。Pulse 的 105°C 是退出停止线，不会覆盖此厂商目标。新的 CPU 测试阶段再次捕获到 `scmi_limit_notify_cb` 更新 MAX 频率 QoS；与此同时 Pulse 策略仍 active，配置哈希匹配。见 [CPU trace 摘录](cpu-limit-excerpt.txt)。这证明存在独立的固件限频路径，尚不能从这些数据区分每次通知究竟由温度、电流还是功率规则触发，也不能据此推定两代 CPU 的架构性能。

## DevCheck 对照

| 运行 | 总分 | CPU | 内存 | 磁盘 | GPU |
| --- | ---: | ---: | ---: | ---: | ---: |
| 之前 CPU 曲线放开，GPU 原厂 | 5939 | 6631 | 3779 | 14235 | 3487 |
| 本轮 CPU+GPU 启用，但中途触线恢复 | 5733 | 6462 | 3627 | 13275 | 3471 |

DevCheck 6.60；本轮从约 33.2°C 机身 / 32.8°C 电池开始，前轮约 30.0°C / 29.3°C。20:30:35 采样机身 50.506°C，Pulse 自动恢复原厂（`BODY_LIMIT`），发生在 GPU 项目之前。因此 5733 **不是 GPU 放开后的有效总分**，不能据此说 GPU 加速无效或声称总分已恢复。该轮最高记录机身 50.746°C、电池 43.4°C、CPU 热点 95.8°C；恢复有采样间隔及温度余升，并非硬件温度锁。原始 442 点记录见 CSV 和停止事件文件。USB 连接、Pulse 监测、采样及短时 CPU 跟踪均有开销，测试不作为跨机排名。

DevCheck 单项配置进入 Pro 购买页，本次没有购买或绕过权限。用下述独立硬件负载补充验证 GPU 开关的实际效果，不能把其 FPS 换算成 DevCheck 分数。

## 相同离屏 GPU 重负载 A/B/A

源码 [tools/gpu-probe.c](../../../tools/gpu-probe.c)：Android 原生 EGL / OpenGL ES 3，1024×1024 RGBA Pbuffer，片元高精度浮点 768 次依赖循环，每帧 `glFinish`；预热 2 秒、计时 12 秒，读取输出像素并检查 GL 错误。所有轮次使用同一二进制，渲染器实读 Adreno 845，未使用软件渲染。CPU 曲线和 50/50/105 停止线保持一致。每轮独立会话、每秒采样，结束后恢复原厂。

| 顺序 / 策略 | 开始机身温度 | GPU 上限节点 | 负载中频率节点 | 吞吐 |
| --- | ---: | --- | --- | ---: |
| A 仅 CPU，GPU 原厂 | 38.07°C | 759 MHz | 759 MHz | 62.19 FPS |
| B CPU+GPU 曲线放开 | 38.70°C | 最高 1450 MHz，运行中仍有其他限制 | 1025–1350 MHz | 91.57 FPS |
| A2 恢复原厂 GPU 曲线 | 40.61°C | 578 MHz | 578 MHz | 48.03 FPS |

B 对 A 的吞吐提升约 47.2%。三轮策略全程 active、GL 错误为 0；GPU 负载峰值 92–94%。A2 更热，进入了原厂 40°C 档，因此不能把 A2 与 A 当成完全相同温度下的重复；但频率和吞吐在恢复原厂曲线后均下降，A 与 A2 的每 MHz 吞吐接近。此结果支持放开曲线能提升受该规则限制的 GPU 负载；没有证明能持续固定 1450 MHz，也没有得到有效的全程放开 DevCheck 总分。

最初 192 次循环轻负载 A/B/A 为 162.05 / 182.10 / 180.44 FPS，GPU 负载最高 84–86%，恢复后未出现同样幅度回落，不能把这组约 12% 差异当成可靠加速量；因此另测上面的更重负载。两组 JSON 均保留，未隐去轻负载结果。

### 构建离屏测试（需 NDK）

```sh
"$PULSE_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android26-clang" -O2 -fPIE -pie tools/gpu-probe.c -o gpu-probe -lEGL -lGLESv3
adb push gpu-probe /data/local/tmp/pulse-gpu-probe
adb shell su -M -c 'chmod 700 /data/local/tmp/pulse-gpu-probe; /data/local/tmp/pulse-gpu-probe 12'
```

运行前从 Pulse 应用所需策略；完整固件、原厂密文和临时编码密钥不随验证资料分发。

## 结束状态

验证和采样均结束，私有 trace 实例已移除；最终从 Pulse 界面重新应用 CPU+GPU 持续策略，50/50/105°C 停止线保留。当前状态见 `final-state.txt`；GPU 实际上限仍可能受到保留的其他规则约束。用户随后在手机上使用其他应用，未继续操作其前台界面。
