# 构建 Pulse

这是原生 Java Android 项目。应用本身不申请联网权限；首次构建需要联网下载构建工具和依赖，后续可使用缓存。

## 已验证的环境

| 组件 | 固定版本 |
| --- | --- |
| Java | JDK 17 |
| Gradle | 8.11.1（项目 Wrapper） |
| Android Gradle Plugin | 8.10.1 |
| Android SDK Platform | API 36，revision 2 |
| Android SDK Build Tools | 36.0.0 |
| Android NDK | r29（29.0.14206865，Clang 21.0.0） |
| 原生构建脚本 | Python 3.10+，只用标准库 |
| 最低 Android 版本 | API 26（Android 8.0） |
| 目标 Android 版本 | API 36（Android 16） |

Google 的 AGP 8.10 兼容性说明确认 API 36、JDK 17 和 Gradle 8.11.1 可组合使用：
<https://developer.android.com/build/releases/agp-8-10-0-release-notes>

## 当前电脑

在项目目录执行：

```powershell
.\gradlew.bat assembleDebug --console=plain
```

Windows Wrapper 在环境变量未设置时，自动使用现有的 `C:\Program Files\Java\jdk-17`、项目相邻 `work\android-sdk` 和 `work\gradle-user-home`。这些设置仅对本次子进程生效，不修改电脑的全局环境。

APK 输出：`app\build\outputs\apk\debug\app-debug.apk`。

## 其他电脑

1. 安装 JDK 17，并在终端为当前会话设置 `JAVA_HOME`。
2. 从 Android 官方网站安装 Android SDK Command-line Tools：<https://developer.android.com/studio#command-tools>。
3. 在 SDK Manager 中安装 API 36 平台和 36.0.0 Build Tools；无须 Android Studio 或模拟器。性能测试功能另需 NDK r29。
4. 将 `ANDROID_HOME` 指向 SDK，或创建 `local.properties`，写入 `sdk.dir=C:/path/to/android-sdk`。
5. 运行项目的 Gradle Wrapper；它会获取固定的 Gradle 和 AGP 版本。

原生跑分使用 NDK r29。当前电脑复用项目相邻的 `work/android-tools/android-ndk-r29`。其他电脑设置 `PULSE_NDK_HOME` 指向 NDK 根目录，必要时设置 `PULSE_PYTHON` 指向 Python 可执行文件。`buildPulseBenchmarks` 在 APK 构建前自动编译 ARM64 PIE 可执行文件，以 `.so` 文件名安装进 `nativeLibraryDir`，无需 CMake。

旧版 SDK 命令行工具的包名使用分号：

```powershell
sdkmanager "platforms;android-36" "build-tools;36.0.0"
```

当前电脑下载的 Command-line Tools 23 使用新的 Android CLI；可使用：

```powershell
android --sdk=C:/path/to/android-sdk sdk install platforms/android-36 build-tools/36.0.0
```

macOS/Linux 使用 `./gradlew assembleDebug`，并按本机路径设置 JDK 和 SDK。

## 构建完整性与签名

Gradle distribution 的官方 SHA-256 已写入 `gradle/wrapper/gradle-wrapper.properties`：

```text
f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6
```

`assembleDebug` 使用 Android 的本地开发签名。当前 `assembleRelease` 也明确配置为开发签名，仅用于本地测试，不是生产发行签名。保留同一开发密钥才能覆盖安装既有 APK 并保留设置。正式发布前应配置独立保管的发行密钥。

## 验证

```powershell
.\gradlew.bat assembleDebug --console=plain
.\gradlew.bat lintDebug --console=plain
.\gradlew.bat assembleDebugAndroidTest --console=plain
```

静态构建不能验证手机的 Root 授权、系统悬浮窗权限、HyperOS 后台策略或特定传感器；这些需要在目标设备上检查。应用不需要刷写系统或更改 SELinux。

设备集成测试使用无 JUnit 依赖的自定义 Instrumentation，验证解析边界及两次实际采样：

```text
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r dev.pulse.monitor.test/dev.pulse.monitor.MonitorInstrumentation
```

测试不修改配置或权限。Instrumentation 会启动新的目标应用进程，测试前应停止正在进行的跑分；已有监测服务可能被测试启动过程打断。KernelSU 授权由设备所有者在手机上完成。输出包含 `report_json`、真实可用指标数和 Root 状态；缺失硬件指标不会伪装成零。

## 非 Root 兼容版验证

本次 macOS 构建使用 JDK 17、上述 Gradle/AGP/API 36/Build Tools 版本及 NDK r30（30.0.16248370）。通过 `PULSE_NDK_HOME` 选择 NDK；`app/build/native/build-manifest.json` 记录实际 NDK source.properties，不能将 r30 构建误标为 r29。

非 Root 功能、ADB 激活与限制见 [NON_ROOT.md](NON_ROOT.md)。测试 Runner 可传 `-e backend app|adb -e benchmark probe|coremark|dhrystone|whetstone|custom`；ADB 模式测试启动新的目标进程后会等待 25 秒，期间需重新执行激活脚本。HyperOS 可能冻结无可见 Activity 的 Instrumentation，需要同时使用 `adb shell am start --activity-single-top -n dev.pulse.monitor/.MainActivity` 保持目标可见。`-e cancel true` 检查中途取消；`-e overlay true` 检查 120 dp 窗口（附加 `-e overlay_width 100` 使用 100 dp / 10 sp）只含所选项、粗体、空选隐藏、锁定与物理屏幕贴边；`-e widget true` 注册临时 AppWidgetHost 验证实际 RemoteViews 渲染、滚动、样式切换、指标选择更新和点按采样。测试结束删除临时小部件并恢复原偏好，不在用户桌面放置小部件。Android 8–11 的集合工厂在本机测数据与行渲染，完整旧系统宿主仍需旧设备验证。

外观验证：`-e appearance true` 使用独立 SharedPreferences，不改用户指标和配色。RemoteViews 经 Parcel 往返后在 AppWidgetHostView 中检查名称/数值色、背景颜色、0/55/100% 不透明度和边框开关，并覆盖旧集合工厂、恢复默认与主题跟随、悬浮窗描边像素，以及颜色对话框的非法输入、应用和取消。该模式不分配桌面小部件 ID，不在用户桌面添加小部件。
