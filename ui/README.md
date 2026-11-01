# FrameMark Compose Multiplatform UI

原生 Windows/JVM 与 Android 使用共享 Compose 界面、项目模型、预设和任务队列。两端接入真实渲染实现；`FakeEngine` 仅用于明确的测试/示例路径，不能据其文件名推断产品仍使用占位导出。

## 工具与入口

版本以 `../scripts/native/toolchain.json` 为准：Kotlin 2.2.20、Compose 1.9.0、AGP 8.13.0、Gradle 8.14.3、JDK 21.0.8，JVM target 17；Android minSdk26/targetSdk36，仅打包完整的 arm64-v8a。

完整构建使用仓库根 `build-native.cmd`，先阅读 [构建说明](../scripts/native/README.md)。本机 Gradle 的针对性命令可在本目录执行：

```powershell
gradle :composeApp:desktopTest
gradle :composeApp:assembleDebug '-PframemarkQaSuffix=.qa20261102'
gradle :composeApp:assembleRelease '-PframemarkQaReleaseSuffix=.qa20261102release'
```

这些命令要求已配置 SDK/JDK，并通过 `FM_ANDROID_PREBUILT_ROOT` 指向本轮从源码构建的三份静态库；直接使用历史库不构成来源核验通过。QA suffix 生成独立包，示例命令不安装应用。

## 调用与数据

- 共享层：照片/设计、预设、命名、批量队列与导出提交契约。
- Windows：`DesktopEngineBridge` 调用三份包内 exe，运行时资源由 `RuntimePaths` 发现。
- Android：`AndroidNativeRender` 调用 JNI 和三个 Rust 原生库；正式构建忽略 debug 专用验收参数，正常启动入口保留。
- 卡数从当前权威清单读取，不使用历史文件名中的数字作为计数。
- 输出目录和 JPEG 质量是本机偏好；Windows 可调60–100，Android固定100，不写入新跨端预设。

## 验收边界

源码编译、单元测试、渲染台架、真实界面与发布门禁分别记录。双端真实操作、预设往返、失败恢复、辅助技术和性能未测项必须保持未覆盖。当前来源/状态见 `../../_research/acceptance-dual-20261101/execution-20261102/`，尚未正式放行。

整改前 U0–U5/FakeEngine/175卡阶段说明已保留在 [历史 UI 说明](../docs/native-history/UI-README-before-execution-20261102.md)，不作为现行构建或验收结论。
