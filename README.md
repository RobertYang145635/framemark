# 框印 FrameMark

本地运行的「照片边框 + 透明 PNG 水印」工具，提供 Windows 桌面版和 Android 版。所有照片、EXIF、水印和预设只在本机处理，不上传云端。

## 功能

- 68 个手写边框 / 卡片 / 电影字幕模板，自动读取 EXIF，62 个有可靠字标的相机/手机品牌（停产老牌使用官方历史资料或说明书扫描件复原；Samyang 暂无可靠字标不列出）
- 系统相机分类 90 套：OPPO 49（Gallery 39 + CameraPro 10）、小米 38、vivo 3（Zeiss 测试接入）；6 组 blocked 在目录内标注原因，不导出
- 自定义透明 PNG / WebP 水印：九宫格定位、照片内或整张画布、宽度、透明度、旋转、平铺与间距
- 预设保存整个设计（含水印图片、Logo、导出设置），支持 JSON 导出 / 导入
- 批量导出，逐张进度、取消、文件命名变量
- 桌面：JPG（质量 100 / 4:4:4）、PNG、无损 WebP、LZW TIFF，JPG 回写原 EXIF 并默认移除 GPS
- Android：JPG、PNG、WebP，保存到系统相册 `Pictures/FrameMark`；单张导出后可直接调用系统分享面板。最新 158 卡 APK 已安装，界面复测待补

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
npm run validate:ir
npm run previews:stock
```

工程文档：[QUALITY.md](QUALITY.md) · [TEMPLATES.md](TEMPLATES.md) · [TEST-REPORT.md](TEST-REPORT.md)

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
