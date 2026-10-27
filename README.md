# 框印 FrameMark

本地运行的「照片边框 + 透明 PNG 水印」工具，提供 Windows 桌面版和 Android 版。所有照片、EXIF、水印和预设只在本机处理，不上传云端。应用以「照片优先」启动：没有照片时先显示「导入照片」页（选择照片 / 使用示例照片），导入后进入编辑器工作区。

## 功能

- 175 套边框按 7 个互斥分类陈列：厂商边框 99（OPPO 47 / 小米 40 / vivo 12）、文字边框 21、印象边框 13、白边边框 12、卡片边框 19、色彩边框 6、简约边框 5；边框库在左栏（另有「更换」弹窗），自动读取 EXIF
- 右侧 Inspector 只有「边框」「水印」两个工具：「边框」分 Logo / 文字 / 布局 / 主题 四个编辑页签；4 组 blocked（小米 Leica 5/88/89 与 vivo 剩余 28 套）保持不可导出、不进入边框库。当前计数、分解与 blocked 明细见 **[PROJECT-BASELINE](PROJECT-BASELINE.md) §1–§2**
- 厂商 Logo 版本库：采集 63 个品牌 / 268 个版本 / 330 个切图，选定 218 个版本；应用内呈现 61 个展示品牌组（LUMIX → Panasonic、LUMO → OPPO 并入），黑标只用于浅色底、白标只用于深色底，不可用的版本显示「已选 · 当前不显示」（状态见 PROJECT-BASELINE §1–§3）
- 自定义透明 PNG / WebP 水印：九宫格定位、照片内或整张画布、宽度、透明度、旋转、平铺与间距；新增画布落点层，可直接拖动水印、九个锚点、键盘微调
- 预设保存整个设计（含水印图片、Logo、导出设置），支持 JSON 导出 / 导入；入口保持在顶栏
- 导出走统一的「导出」对话框：范围（当前照片 / 全部 N 张）、格式、JPG 质量、长边限制、文件名变量、输出文件夹（默认 `图片\FrameMark`）、移除 GPS
- 桌面：JPG（质量 100 / 4:4:4）、PNG、无损 WebP、LZW TIFF，JPG 回写原 EXIF 并默认移除 GPS
- Android：JPG、PNG、WebP，保存到系统相册 `Pictures/FrameMark`；单张导出后可直接调用系统分享面板。签名 APK 覆盖安装数据保留，三次冷启动全部 COLD（状态见 PROJECT-BASELINE §3）

## 目录

- `src/`：Electron 主进程、preload、React 渲染器
- `android/`：Capacitor Android 工程
- `out/`：Web 构建产物
- `release/`：Windows 便携包（运行 `npm run package:win` 生成）
- `scripts/`：验收、打包和测试资产脚本

## 开发

```bash
npm install
npm run dev
npm run build
npm run test:e2e
npm run test:workflow
npm run validate:ir
npm run previews:stock
```

工程文档：[PROJECT-BASELINE.md](PROJECT-BASELINE.md)（当前状态唯一入口） · [REQUIREMENTS.md](REQUIREMENTS.md) · [QUALITY.md](QUALITY.md) · [TEMPLATES.md](TEMPLATES.md) · [TEST-REPORT.md](TEST-REPORT.md)

## Windows 打包

```bash
npm run package:win
```

输出 `release/FrameMark-Windows-x64.zip`，解压后运行 `FrameMark.exe`。程序未做代码签名，Windows SmartScreen 可能提示；选择“更多信息 → 仍要运行”。

## Android 构建

```bash
npm run build
npx cap copy android
cd android
./gradlew assembleRelease
```

需要 JDK 21、Android SDK 36。签名使用 `android/keystore.properties`（缺失时会产出未签名包）。APK 输出在 `android/app/build/outputs/apk/release/`。

## 无损与元数据边界

- 叠加边框或水印必然改动像素，因此 JPG 不存在数学意义上的“无损”；本项目采用最高质量重编码：质量 100、4:4:4、sRGB ICC，并从原 JPG 回写 EXIF（方向归正、GPS 默认移除）。
- PNG 和桌面 TIFF（LZW）是真无损；桌面 WebP 为无损；Android WebP 为最高质量有损（Canvas 编码限制）。
- Android 的 PNG / WebP 不写入 EXIF；JPG 会回写 EXIF 和 ICC。Android 不提供 TIFF 导出。
- HEIC/HEIF、TIFF 的读取依赖系统解码能力。

## 隐私与版权

- 应用离线运行，不上传照片或预设。Android 使用系统照片选择器，只读取用户主动选择的文件。
- `resources/` 与内置品牌 Logo、参考图来自个人研究素材；商标和原始模板版权归各自权利人。该工程仅供个人自用，请勿将内置品牌素材用于商业分发。

## 测试资产与调试

Android Debug 包在 `android/app/src/debug/assets/public/test-assets/` 内置三张测试照片和两张水印，Release 包不会包含。桌面测试脚本位于 `scripts/`，会把截图和联系表写入 `../_research/`。
