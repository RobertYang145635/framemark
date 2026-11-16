# FrameMark 原生双端工程

当前产品为 Compose Multiplatform 界面与 Rust 渲染/导出实现。Windows 和 Android 共享部分模型、布局与文字代码；Windows 通过子进程绘制/编码，Android 通过 JNI 原生库调用，成片一致性需要分别验证。

## 当前范围

- `ui/`：共享模型、界面及 Windows/Android 平台适配。
- `engine/e2b/`、`engine/e4-hand/`、`engine/e5/stream-proto/`：Windows IR、手写绘制与导出 worker。
- `bridge/android*`：Android IR、手写与导出原生库。
- `engine/data/`、`contracts/`：卡库、控件和渲染数据契约。
- `scripts/native/`：仓库内构建入口、固定工具版本、来源校验与隔离运行时打包。
- `legacy-web/`：冻结的 Electron/Capacitor 历史实现（原 `src/`、`capacitor.config.json`、`electron.vite.config.ts` 已整体移入），仅用于功能与历史行为对照，不参与产品与构建；原生构建原先从 `src/` 读取的三份控件 JSON 现位于 `bridge/desktop/controls-data/`。

## 构建

先按 [原生构建说明](scripts/native/README.md) 配置工具和校验过的 Skia 归档路径，再执行：

```powershell
.\build-native.cmd --dry-run
.\build-native.cmd
.\build-native.cmd --release --release-qa
```

构建入口不安装或发布应用。`--release-qa` 生成独立包名、debug 签名、关闭验收 Intent 的本地验证包，不能作为正式发行包。必需构建或门禁失败/跳过时返回非零；`--skip-*` 仅用于部分诊断。

## 验证状态

本轮执行记录位于 `../_research/acceptance-dual-20261101/execution-20261102/`。各结论关联具体源码和工件，历史通过结果不能代替最新版本验收。Windows 真实界面操作仍处于停止状态，命令行渲染与自动测试不计为完整界面通过。尚未正式放行。

[UI 说明](ui/README.md)描述当前调用与本地验证方式；[历史根说明](docs/native-history/README-before-execution-20261102.md)保留整改前的阶段记录，里面的旧计数、路径和构建规则不再作为当前指引。

## 开源协议

本项目以 [MIT License](LICENSE) 开源。第三方组件与素材的许可见应用内「关于 → 许可与版权」；Windows 便携包的 `runtime/legal` 目录保留完整许可原文。

## 更新检查

应用内「关于 FrameMark → 检查更新」读取本仓库的 GitHub Releases 版本信息，可在直连与国内镜像之间切换；只读取版本元数据，不上传照片、工程或使用数据。
