# Pulse 1.5.1 悬浮窗独立配色验证

日期：2026-10-20
设备：Xiaomi 18 Pro / hongkong / 495fb913
构建：`versionCode 7` / `versionName 1.5.1`，API 36，JDK 17，Build Tools 36.0.0

## 改动

- 新增 `OverlayStyle`，使用 `overlay_label_color`、`overlay_value_color`、`overlay_background_color` 三个独立偏好键；
  未自选数值颜色时继续使用当前主题强调色，不可用读数仍保留专用灰色。
- `OverlayPanel` 不再写死名称色 `#A7B7C9`、背景 RGB `13,22,33` 和主题值色，改为读取 `OverlayStyle`；
  `overlay_alpha` 与 `overlay_borderless` 的既有行为不变。
- `PulseViews.Preview` 的悬浮窗预览同步使用相同配色。
- 「悬浮窗」页新增「悬浮窗配色」卡片，复用 `ColorPicker` 的预设色与 `#RRGGBB` 输入；
  「恢复悬浮窗默认配色」只移除三个颜色键，不重置不透明度和边框。
- 补齐 `ColorPicker` 的无障碍描述区分（悬浮窗/小部件），并新增外观检查。
- 修正 1.4 遗留的机型文案：首页温控卡改为 `ROOT / XIAOMI 15 · 18`，关于页明确只对已校验的
  小米 15（dada）与小米 18 Pro（hongkong）固件开放写入。

## 验证

新增专项 Instrumentation，不启动 Activity，避免 HyperOS 在前台切换时冻结测试：

```bash
adb shell am instrument -w -r -e overlaycolors true \
  dev.pulse.monitor.overlaytest.test/dev.pulse.monitor.MonitorInstrumentation
```

结果：

```json
{"result":"pass","version":"1.5.1","overlay_label_color":true,
 "overlay_value_color":true,"overlay_background_color":true,
 "overlay_opacity":true,"overlay_unavailable_color":true,
 "overlay_reset_theme":true}
```

检查内容：

- 设置 `#FFCE85` 名称色、`#78E8C6` 数值色、`#19324D` 背景色、55% 不透明度；
- 实际 `GradientDrawable` 为 `0x8C19324D`（alpha 140，RGB 0x19324D），两行标签/数值均取到自定义色；
- 不可用读数仍使用自定义数值色（符合小部件的 `customValue` 规则）；
- 移除三个颜色键后，名称/背景恢复默认，数值颜色重新跟随 `ice` 主题，不可用色恢复 `#66778B`。

截图：`overlay-colors.png`（630×110，悬浮窗实际渲染）；
`settings-overlay-colors.png`（设置页「悬浮窗配色」卡片，643×1400）；
`device-installed-overlay.png`（安装 1.5.1 后、在原桌面上的实际悬浮窗）；
`thermal-after-migration.png`（迁移后温控页：ROOT 已连接、策略运行中）。

## 实机安装与数据迁移

手机上原有 Pulse 1.5.0（UID 10394）使用 Windows 开发证书，本机构建的 1.5.1 使用 Mac 开发证书，
不能直接覆盖。按“备份 → 卸载 → 安装 → 恢复数据”执行：

1. 停止应用后备份 `/data/data/dev.pulse.monitor`（209 KB）和
   `/data/user_de/0/dev.pulse.monitor`（53 KB），使用 `tar --selinux --numeric-owner`；
   备份文件与原始 APK 保存在本机工作区的 `verification-20261020/pulse-backup-1.5.0/`（未纳入仓库）。
2. `pm uninstall -k` 保留了旧签名记录，导致新 APK 仍报
   `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；改用 `pm uninstall --user all` 清除包记录后再安装。
3. 安装 1.5.1 后 UID 为 10395。将备份解包回 `/data/data`，`chown -R 10395:10395`，
   并 `chcon -R` 为新数据目录的 SELinux 上下文；删除 cache/code_cache 让系统重建。
4. 校验恢复结果：
   - `pulse_config.xml` SHA-256 `206ca7cd…b10a76` 与备份一致；
   - `thermal_policy_hongkong.xml` SHA-256 `8886fc8c…f04da` 与备份一致；
   - `files/benchmarks/` 下 17 个历史结果恢复。
5. 重新授予 `POST_NOTIFICATIONS`、`SYSTEM_ALERT_WINDOW`，并把应用加入 Doze 白名单。
6. 打开温控页确认 `ROOT 已连接`、`策略运行中`；`ThermalService` 以前台服务 71 恢复运行。
7. 用测试色实际验证安装版悬浮窗后，将 `pulse_config.xml` 恢复为备份原文件（同一 SHA-256），
   测试色已清除，原有 mono 主题、39% 不透明度、无边框和 17 项指标保持不变。

`pm uninstall -k` 的旧签名记录是本次迁移中唯一需要额外处理的点；仅用 `-k` 卸载不足以换签。

## 签名边界

当前手机已安装的 1.5.1 使用 Mac 开发证书
`540e097b6be81963997ca6b0bf0906116e3782591cd4f4962a8fd504b5b62893`。
后续更新必须继续使用同一 Mac keystore 签名；仓库发布的 Windows 1.5.0
（`64233655…`）不能直接覆盖本安装。原始 1.5.0 APK 已保留在本机工作区的
`verification-20261020/pulse-backup-1.5.0/Pulse-1.5.0-original.apk` 以便回滚。

## 构建说明

本机没有 NDK，验证构建从已安装 1.5.0 APK 提取 `lib/arm64-v8a/*.so` 放入
`app/src/main/jniLibs/arm64-v8a/`，并使用：

```bash
./gradlew assembleDebug -x buildPulseBenchmarks
```

正式重新构建原生测试库时仍应按 `docs/BUILD.md` 配置 NDK r29。
