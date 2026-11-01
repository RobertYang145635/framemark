# 框印 FrameMark（原生重写中）

> 2026-11-01：主管审查后的首批源码修复与验证见 `../_research/audit-20261101/FIXES.md`。当前147项桌面测试与Android Kotlin编译通过；正式安装包未替换。以下2026-10-29进度表为历史快照，不能据此判断当前交付状态。

> **状态：迁移进行中，尚未达到交付 DoD。** 本仓库当前是原生重写工程：桌面/Android 界面用 Compose Multiplatform，渲染引擎用 Rust + Skia/HarfBuzz（自带字体，不依赖系统文字栈）。旧的 Electron + Capacitor/WebView 版本已冻结归档，只作为像素 oracle 和旧版安装包保留，不再是本仓库的构建目标。

- 迁移任务书：`C:\Users\YHYTRUMP\.qoder-cn\plans\hushed-cloud-moose.md`
- 执行台账：`../_research/native/TRACEABILITY.md`、`../_research/native/SUPERVISOR.md`
- 回归台架与门状态：`../_research/native/X4.md`
- 决策记录：`../_research/native/DECISIONS.md`

## 当前完成度（2026-10-29）

| 模块 | 状态 | 证据 / 说明 |
|---|---|---|
| 数据迁移 D1–D9 | **基本完成，round-trip 已出结果** | `engine/data/**`：IR 106 样式、手写 39·69 参数、996 控件、175 卡 taxonomy、22 字体、338 Logo、175 预览；E1b-2 全量 175 重放：**171 全等 + 3 容忍 + 1 失败**（`stock-xiaomi-tag-9` 9B/Δ1，超 8B cap 1B，未放宽），见 `../_research/native/e1b2/compare-full175.md` |
| 引擎骨架 E2 | **部分** | E2a 1 个 IR 样式通过；E2b 文字 AA 有 GAP |
| IR 99 套 E3 | **进行中** | 三族合并后 outside-text **27/106**（vivo 8/14、小米 3/40、OPPO 16/52）；文字带仍按 D-021/D-026 记 GAP |
| 手写模板 E4 | **台架分析中** | 69 卡/32 def 的只读分析已出；逐套原生渲染未开始 |
| IO/导出 E5 | **台架完成** | q100 4:4:4 与 Chromium 逐字节、PNG 解码无损、EXIF 复制/GPS 剥离、TIFF 读写通过；见 D-027 与 `../_research/native/E5.md` |
| UI U0–U5 | **U5a 绿，U5b 待** | Compose 双端；175 卡库/7 类、10 套控件抽查、预设/撤销/批量（FakeEngine）、桌面三栏；APK `0.1.0-u5a` 为 debug 包 |
| X1 引擎接真 | **进行中** | UI 侧 `NativeRender`/`WorkflowEnv` 接线已开始；debug APK 已带 `libframemark_engine.so` 并完成 1 张卡真机 CPU 渲染；desktop/debug 构建已恢复（2026-10-29 21:1x），release 本轮未重跑 |
| X2 性能/内存 | **未开始** | G3/G4/G5 未验收；E5 只有引擎级基线 |
| X3 打包/签名 | **进行中** | 便携 app-image zip + MSI `_research/native/x3/installer/FrameMark-0.1.0.msi` 已有；**release 正式签名 APK** `_research/native/x3/FrameMark-native-0.1.0-release.apk`（证书 = 冻结版） |
| G1 无浏览器内核 | **PASS（APK+桌面）** | APK 带 4 ABI `libframemark_engine.so`；便携桌面包含引擎 exe、0 条浏览器证据；启动后产品目录进程仅 `composeApp.exe`；Android 端进程扫描未做 |
| G2 两端像素一致 | **未通过** | 1.0 原生 only outside-text 27/106；strict 0/175；1.22 档 0/175；手写 69 张未测 |
| G3/G4/G5 | **未测** | 真机/真壳测量未开始（阈值为 600MB / 8s / p95 120ms / 冷启动 1.5s） |
| G6 功能不缩水 | **未验收** | 数据/UI 样本/FakeEngine 证据在，真引擎工作流与重启续传待 X1 |

**不要把上表读成“已完成”。** 交付物 1–7 中的安装包、release APK、175×2 逐卡表、迁移说明都还没有齐；已有开发用桌面 classpath 和 Android debug APK 产物，但 X1 接线期间源可能暂时编译不过（见下方构建说明）。

## 目录

```
framemark/
  engine/              Rust + Skia/HarfBuzz 引擎（一份，两端共用）
    e2b/               当前主引擎（IR + 手写模板渲染内核，Windows 可执行）
    e5/                IO/编码/EXIF/TIFF/内存台架
    e4-hand/           手写模板分析/移植台架
    data/              D1–D9 迁移后的 JSON（taxonomy/capabilities/IR/hand/logos/fonts…）
    assets/            字体、Logo、品牌切版等资源
  ui/                  Compose Multiplatform（desktop + Android）
    composeApp/        UI 源码、资源和构建脚本
  contracts/           FrameMarkRender v1 / capabilities schema + 契约夹具
  src/                 【冻结】旧 Electron 主进程与 React 渲染器（oracle 源，不再发布）
  android/             【冻结】旧 Capacitor Android 工程（旧版归档/签名素材）
  release/             【冻结】旧 Windows 便携包（不删除）
  scripts/             【冻结】旧验收/一次性脚本
  ../_research/        仓库外证据：oracle 黄金图、截图、X4 台架、tokens
```

## 构建

> 所有命令用绝对路径；Windows 侧默认工具链在 `D:`（JAVA_HOME `D:/dev/jdk21`、Android SDK `D:/dev/android-sdk`、Gradle `D:/dev/gradle-8.14.3`、Rust/NDK/CMake/Ninja 见 `D:/dev/native-env.cmd`）。C 盘空间紧张，构建临时目录已重定向到 `D:/dev/tmp`。

### 一键构建（推荐入口）

```bat
cd /d D:\border&watermark\framemark
build-native.cmd --skip-engine
```

`build-native.cmd` 会依次做：引擎构建（可跳过）→ Compose 桌面编译/运行 classpath → Android debug APK → X4 验收门（G1/G2/G6/交付物/H3/红测）。规则：

- `--skip-engine`：复用现有 `D:/dev/tmp/e2b/target/release/e2b.exe`，不抢 cargo；
- 检测到其它 Gradle 构建或 cargo/rustc 时，对应步骤 **SKIP**，不并发；
- **验收门允许 FAIL，脚本不因门失败而退出报错**；
- 不启动 Electron、不执行 adb/真机操作；
- 报告写到 `../_research/native/tools/x4/evidence/build-all.json`（`.md` 同步）。

常用变体：

```bat
build-native.cmd --skip-engine --release --sign   :: 构建 release APK 并用环境变量中的凭据签名
build-native.cmd --skip-engine --skip-gates       :: 只构建，不跑门
build-native.cmd --dry-run                        :: 只打印计划，不执行
```

> 2026-10-29 18:00 时 `--release` 曾因 X1 接线中的 UI 源（`NativeRender.kt`/`ExportSheet.kt`）编译失败而停在 `apk-release`；解冻后 21:1x 的 `--skip-engine` 已让 desktop 与 debug APK 通过（Gradle 报 UP-TO-DATE，APK sha `ad1c5c94…` 未变）。release 本轮未重跑；引擎构建默认会先检测其它 cargo/rustc，检测到就 SKIP。

### 引擎

```bat
cd /d D:\border&watermark\framemark\engine\e2b
build-win.cmd
```

产物：`D:/dev/tmp/e2b/target/release/e2b.exe`。构建会用 `D:/dev/native-env.cmd` + VS 2022 Build Tools（MSVC）和 T0b 的 Skia 预编译包，不编译 Skia 源码。E5 台架同理在 `engine/e5/build-win.cmd`。

### 桌面 UI

```bat
cd /d D:\border&watermark\framemark\ui
D:\dev\gradle-8.14.3\bin\gradle.bat :composeApp:compileKotlinDesktop :composeApp:dumpDesktopClasspath
```

启动/截图（需要真实桌面会话）：

```powershell
powershell -ExecutionPolicy Bypass -File D:\border&watermark\framemark\ui\tools\run-desktop.ps1 -OutDir D:\border&watermark\_research\native\u0
```

桌面 MSI 已用本地 NuGet WiX 3.11.2 产出：`_research/native/x3/installer/FrameMark-0.1.0.msi`（Compose 无 `packageMsi` 任务，使用 `jpackage --app-image`）。便携 app-image zip 在 `_research/native/x3/`；U5 的桌面验证仍可用 `java -cp`，见 `ui/README.md`。

### Android

```bat
cd /d D:\border&watermark\framemark\ui
D:\dev\gradle-8.14.3\bin\gradle.bat :composeApp:assembleDebug
```

产物：`ui/composeApp/build/outputs/apk/debug/composeApp-debug.apk`（`com.framemark.native`，debug 签名）。release 签名见下一节；正式 release APK 已含真实引擎 `.so`。

## 签名（H1：不落明文）

明文 `android/keystore.properties` 已退役；现有签名值在仓库外 `D:/border&watermark/.secrets/framemark-signing.env`（自忽略，未提交）。旧 `android/app/build.gradle` 已改为读环境变量/凭据文件，不再读明文 properties。新签名流程只从环境变量或仓库外的凭据文件读取：

| 变量 | 含义 | 当前状态 |
|---|---|---|
| `FM_KEYSTORE_PATH` | release keystore 的绝对路径 | 已迁移（`android/keystore/framemark-release.jks`） |
| `FM_KEY_ALIAS` | key alias | 已迁移 |
| `FM_KEYSTORE_PASSWORD` | keystore 口令 | 已迁移 |
| `FM_KEY_PASSWORD` | key 口令 | 已迁移 |
| `FM_SIGNING_ENV_FILE` | 可选：凭据文件绝对路径，默认 `D:/border&watermark/.secrets/framemark-signing.env` | 已存在 |

凭据文件格式（只写变量名，值由你填；不要提交到 git）：

```ini
FM_KEYSTORE_PATH=D:/secure/framemark-release.jks
FM_KEY_ALIAS=<alias>
FM_KEYSTORE_PASSWORD=<口令>
FM_KEY_PASSWORD=<口令>
```

`build-native.cmd --release --sign` 通过 `java -jar apksigner.jar` 调用（不经过 cmd/PowerShell，避免仓库路径里的 `&` 被截断），并使用 `--ks-pass env:…` / `--key-pass env:…`，口令不会出现在命令行、日志或本仓库任何文件中；工具只打印证书 SHA-256。签名后必须与冻结版的证书指纹一致：

```
SHA-256 a3f8e1d9b22b92cc687c85cc4648f92678a01a50d99fe249cdedd2a7422c873f
```

当前 debug APK 用的是 Android Debug 证书（`65917433…`），**不是**上述冻结证书，不能作为交付包。正式 release APK 已用上述 keystore 签名并通过 `apksigner verify`，证书 SHA-256 = 冻结版；签名包：`_research/native/x3/FrameMark-native-0.1.0-release.apk`。

## 已知 GAP（按决策记录）

| 决策 | 内容 | 对交付的影响 |
|---|---|---|
| **D-021** | G2 文字 AA GAP：native Skia 与 Chromium 的字形边缘 profile 不同；最佳文字卡 0.4071%/Δ47（判据 0.01%/Δ1），photo Δ0、layout 已对齐 | 严格 G2 在当前 Skia 路径不可达；逐卡登记，不静默放宽 |
| **D-022** | Design replay 用逐卡新 profile；`stock-xiaomi-classic-22/99` 是仅有的 >4096px 宽画布，右上 wordmark 重采样差 Δ11 | 这两张列为已知 GAP；不重签 oracle、不扩例外表 |
| **D-026** | G2 分层口径：照片/背景 Δ0、Logo ≤2、布局 ≤0.01px 严格；文字 A8 边缘逐卡 GAP，最终由用户看并排成品决定 | 不把文字层标成达标；最终 G2 报告必须带并排图 |
| **D-027** | 导出编码：JPEG 默认 q100 + 4:4:4（q<100 也锁 4:4:4）、PNG level 6、EXIF 保留缩略图、MM/II 归一化；50MP IO 峰值 519–547MB 已贴线 | X2 落地前不宣称 G3/G4；PNG L9 17–24s 不进默认路径 |

其它未完成项：Android 端像素未测（APK 无引擎 `.so`）、1.22 档 0/175、手写 69 张待 E4、真机 G3/G4/G5 未测、WebP VP8L 无原生编码证据、U5b 桌面适配待做。

## 测试与验收门

```bat
:: 台架可信度（先见红，不启动 Electron/真机）
node D:\border&watermark\_research\native\tools\x4\red-green.cjs
:: G1 静态扫描
node D:\border&watermark\_research\native\tools\x4\g1-scan.cjs
:: G2 现状聚合 / 单档 175 表
node D:\border&watermark\_research\native\tools\x4\g2-aggregate.cjs
node D:\border&watermark\_research\native\tools\x4\g2-compare.cjs --a <渲染树> --b D:\border&watermark\_research\oracle\goldens --scale 1.0
:: G6 / 交付物 / H3 / H4
node D:\border&watermark\_research\native\tools\x4\g6-static.cjs
node D:\border&watermark\_research\native\tools\x4\deliverables-check.cjs
node D:\border&watermark\_research\native\tools\x4\h3-dead-paths.cjs
node D:\border&watermark\_research\native\tools\x4\h4-gated-audit.cjs
```

门状态、阈值和证据路径统一记录在 `../_research/native/X4.md`。任何门 FAIL 都按证据处理，不用平均值或“大概没问题”放行。

## 旧版归档

旧 Electron/Capacitor 版不删除：

- tag：`fm-desktop-freeze`（`ec7ec9778a6270ed82b7bf2c3256d58ba3284bd4`）
- Windows 便携包：`release/FrameMark-Windows-x64.zip`
- Android 旧签名 APK：`android/app/build/outputs/apk/release/app-release.apk`

旧版的 `src/`、`android/`、`scripts/`、`release/` 只作 oracle/归档，不再作为新构建入口。旧 `android/keystore.properties` 含明文口令，X3 会改为纯环境变量/凭据文件；在此之前不要把它复制进新仓库或交付包。

## 文档索引

- 原生 UI 细节与构建：`ui/README.md`
- 数据迁移：`engine/data/README.md`、`../_research/native/E1A.md`、`E1B-1.md`、`E1B-2.md`
- 引擎 IO/编码：`../_research/native/E5.md`
- 回归台架：`../_research/native/X4.md`
- 决策：`../_research/native/DECISIONS.md`
- 任务追溯：`../_research/native/TRACEABILITY.md`

## 隐私与版权

应用离线运行，不上传照片或预设。照片、EXIF、水印和预设只在本机处理。内置品牌 Logo、参考图与模板来自个人研究素材；商标和原始模板版权归各自权利人。该工程仅供个人自用，请勿将内置品牌素材用于商业分发。
