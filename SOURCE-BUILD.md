# FrameMark / 框印 — 源码包构建说明

- 版本以仓库根 `version.properties` 为唯一来源（`versionName` / `packageVersion` / `versionCode`）；
  `ui/composeApp/build.gradle.kts` 在配置阶段读取它，Android 与桌面打包共用同一组值。
  源码包的版本号与提交号以包内实际内容为准，不在本文件里重复抄写。
- 本包是源码包，**不包含** `gradlew` / `gradlew.bat`。仓库只跟踪
  `ui/gradle/wrapper/gradle-wrapper.properties`，其中 `distributionUrl` 固定为 **Gradle 8.14.3**
  （`gradle-8.14.3-all.zip`）；请用本机同版本 Gradle 执行，不要假设包内有 wrapper 脚本。

## 包内两种形态

- `FrameMark-<版本>-source.zip`（完整包）：包含全部构建输入，可直接构建两端。
- `FrameMark-<版本>-source-slim.zip`（瘦身包）：**去掉了** 4 个预编译/打包输入——
  `ui/composeApp/src/androidMain/cpp/prebuilt/arm64-v8a/libfm_android_{engine,hand,export}.a`
  与 `ui/composeApp/src/androidMain/assets/x1ir.zip`。其余受控文件与完整包一致。
  瘦身包只能读代码/评审；要构建 Android 端必须先补齐这 4 个文件（见下）。

## 前置工具

精确版本见 `scripts/native/toolchain.json`：Node.js 24.15.0、JDK 21.0.8（JVM target 17）、
Gradle 8.14.3、Android compileSdk 36 / NDK 26.3.11579264、Rust 1.99.1、MSVC x64。
`scripts/native/build.cjs` 用环境变量定位工具（`FM_JAVA_HOME` / `JAVA_HOME`、
`FM_GRADLE_HOME` / `GRADLE_HOME`、`FM_ANDROID_SDK_ROOT` / `ANDROID_SDK_ROOT` / `ANDROID_HOME`、
`FM_CARGO_EXE` / `FM_RUSTC_EXE` / `FM_RUSTUP_EXE`、`FM_VSDEVCMD` 等），不依赖本机 PATH 布局。

## 桌面端

只跑共享代码的单元测试（不需要 Android SDK，但配置 Android 目标仍需 `ANDROID_HOME`）：

```powershell
# Gradle 三选一：PATH 上的 gradle / $env:GRADLE_HOME\bin\gradle / 控制目录 env-uiv2.ps1 提供的 $env:FM_GRADLE
gradle -p ui :composeApp:desktopTest --no-daemon
```

完整桌面运行时（Rust/Skia 引擎 + 资源）由仓库根 `build-native.cmd` 驱动：

```powershell
.\build-native.cmd --dry-run
.\build-native.cmd
```

`build-native.cmd` 实际调用 `node scripts\native\build.cjs`，它用 JDK 直接启动
`org.gradle.launcher.GradleMain`（Gradle 取自 `FM_GRADLE_HOME` / `GRADLE_HOME`），
所以本仓库完全不需要 `gradlew`。请勿把 `gradle` 换成 `.\gradlew`——该文件不存在。

## Android 端必须补齐的输入

Android 构建依赖两个**输入**，不是构建产物：

1. `ui/composeApp/src/androidMain/cpp/prebuilt/arm64-v8a/libfm_android_engine.a`、
   `libfm_android_hand.a`、`libfm_android_export.a`（三份静态库）
2. `ui/composeApp/src/androidMain/assets/x1ir.zip`

重建方法：

- 三份 `.a`：由 `bridge/android`、`bridge/android-hand`、`bridge/android-export` 的 Cargo 源码构建。
  直接跑 `.\build-native.cmd`（或 `node scripts\native\build.cjs`）即可；构建会把三份 `.a`
  落到 `<FM_BUILD_ARTIFACT_ROOT>\runs\<runId>\artifacts\android-prebuilt\arm64-v8a\`，并在同一次 Gradle 调用里通过
  `FM_ANDROID_PREBUILT_ROOT` 指过去。单独用 Gradle 时需自己设置：
  ```powershell
  $env:FM_ANDROID_PREBUILT_ROOT = '<FM_BUILD_ARTIFACT_ROOT>\runs\<runId>\artifacts\android-prebuilt\arm64-v8a'
  gradle -p ui :composeApp:assembleDebug --no-daemon
  ```
  `node scripts\native\verify-prebuilt.cjs` 会按 `scripts/native/android-prebuilt-manifest.json`
  校验仓库内归档的哈希；哈希只证明文件未被改动，不证明其来源。
- `ui/composeApp/src/androidMain/assets/x1ir.zip`：仓库跟踪的必需资源（`.gitignore` 里专门放行）。
  **本仓库内没有生成器**；它由设计资源导出流程产出，完整源码包里自带。
  瘦身包缺这个文件时，从完整包复制回来；若手上有 git 检出，执行
  `git checkout -- ui/composeApp/src/androidMain/assets/x1ir.zip` 即可恢复。

## 版本

`version.properties` 是唯一需要人工修改的版本文件，当前值以包内该文件为准：

```properties
versionName=<发布标签，与 packageVersion 前缀一致，例如 X.Y.Z-rcN>
packageVersion=<数字 X.Y.Z 基版本>
versionCode=<正整数>
```

`packageVersion` 必须是 `versionName` 的数字前缀（`X.Y.Z` 或 `X.Y.Z-<后缀>`），否则构建直接报错。
改动后可这样核对构建实际解析到的值：

```powershell
gradle -p ui -q :composeApp:printVersion --no-daemon
```
