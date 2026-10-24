# FrameMark 测试与验收记录

更新：2026-10-24

## 自动化

| 命令 | 结果 | 覆盖 |
|---|---|---|
| `npm run typecheck` | 通过 | 主进程 + renderer TypeScript |
| `npm run build` | 通过 | electron-vite 生产构建 |
| `npm run test:e2e` | 通过 | 158 卡、90 系统、6 blocked、卡片无逐卡全尺寸 canvas、白/黑主题、Polaroid 导出、0 pageerror |
| Android 签名包冷读（20:21 包） | 通过 | PGEM10 冷启动 144 卡 / 系统 76 / blocked 17；系统选图、Polaroid 导出、系统分享面板。新 158 卡包待复测 |
| `npm run validate:ir` | 通过 | 71 生成样式、71 变体、71 预览；0 错误；37 条已知主题近似 warning |
| `npm run previews:stock` | 71/71 | 生成 71 张 stock 预览 + `_research/stock-audit/previews-manifest.json`（含 SHA256） |

E2E 脚本：`scripts/e2e.mjs`。输出目录 `_research/e2e-export/`。

## 几何/版式

- 13 套原版模板：4:3 几何报告 `_research/preview-analysis/geometry-diff.json`。
- 13 套 × 3:2/16:9/1:1/4:5/2:3：65 张导出，联系表 `_research/ratio-matrix/contact-*.jpg`，目视无页脚越界/照片变形。
- 47 套原版参考/当前渲染并排表：`_research/pair-sheet/page-1..4.jpg`；已知结构错配已修复。
- `validate:ir` warning 集中在 texture/gradient/blur 主题的近似兜底，不是缺资产/断引用。
- 玻璃 01–03 侧栏卡、玻璃 04 参数页脚、票据 01/02、色卡族、色影显现已按参考重做。
- Polaroid：1:1、2:3、16:9 选框实测正确；4:3 通过 E2E 导出成功。
- 本轮新增 14 套 vendor 样式已用 4:3 夹具逐张导出：`_research/vendor-new-4x3/`；GR5、经典 19/71/97、Disney 31/32/33、Leica 91、标签 9/10/11、Zeiss 上框/上下框/内嵌均成功。
- **变形修复**：原版模板 EXIF 行不再使用 `scaleX`/斜切做横向拉伸，改为等比字号；模板卡 fallback canvas 不再被 CSS 强制铺成 4:3。受影响 13 套的 `renderedPreview` 已重生成。
- **OPPO fill 拉伸**：OPPO Gallery 适配器原先把 7 个位图资产宽高记为 0 并用 `fill`；`胶片 · 1` 实际 368×256 被塞进 74×32，横向拉约 61%。现已读取真实尺寸，比例差 >2% 自动改 `contain`；同批修复复古相机 2/3、胶片 2、品牌 3/6、大师签名 4。全库 fill 比例冲突复扫为 0。
- **PC 端全量目检（4:3）**：90 套系统相机 + 68 套原版/手写逐张抓高分辨率主画布。证据：`_research/pc-audit-4x3/`（90 套，含 `sheets-final/`）与 `_research/pc-audit-originals/`（68 套）。本轮另修：OPPO EXIF 部件被重复成整行（改为按 `textSourcePart` 映射）、50dp 侧栏塞 97dp Logo 的 3 套（品牌 3/胶片 2/大师签名 3）与复古相机 5 重排、overlay 前导 space 导致的右侧裁切、film 12/103 主题、宋干节与标签/经典 71/97 对比度、film 15 页脚重排；文字超界统一等比缩放，band 内增加安全区。

## 打包/设备

- Windows zip：280,280,785 字节（22:52）；打包版冷启动 158 卡 / 系统 90 / blocked 6 / 0 pageerror。
- Android APK：28,948,531 字节（22:54）；`adb install -r` 成功，设备 PGEM10；158/90/6 界面复测待补。
- Android 冷读（20:21 包）：系统相机分类 76、blocked 17、总卡 144；Polaroid 导出 1184×1904 JPG 到 `Pictures/FrameMark/`，分享面板正常。证据图：`_research/android-verify/`。21:13 的 158/90/6 新包已安装，冷读待复测。
- 交付：zip/APK/docx/PDF/SHA256 已于 22:54 同步到 `交付/`（zip 280,280,785 B、APK 28,948,531 B、docx 1,579,670 B、PDF 944,931 B），`sha256sum -c` 4/4 OK。

## 已知失败/缺口

- 分享仅覆盖单张导出；批量导出结果需在相册中自行分享。
- 17 组 blocked（小米 15 + OPPO GR5 + vivo 42 合并）；76 套全部 approx。
- 原版 47 套完整逐像素报告未生成；TemplateSpec 未落地。
- 品牌库 15 个停产品牌因无可靠矢量源隐藏。
- 无 `verified` 系统相机样式。

## 复现

```bash
cd D:/border&watermark/framemark
npm run typecheck
npm run build
npm run test:e2e
```

单模板导出见 `scripts/export-variants.cjs`；Polaroid 导出参考 `_research/e2e-export/4x3-Polaroid · PLD.png`。
