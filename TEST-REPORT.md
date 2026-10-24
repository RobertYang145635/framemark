# FrameMark 测试与验收记录

更新：2026-10-25（W0b 文档收口）

> 本页保留测试**方法、证据索引与历史批次**。当前批次通过项、计数与交付哈希见 [PROJECT-BASELINE.md](PROJECT-BASELINE.md) §3–§4；凡带具体字节数或“某版包”的行均为当时记录，不代表当前状态。

## 当前批次（交付基线 02:08）

| 命令 / 检查 | 结果 | 覆盖 |
|---|---|---|
| `npm run typecheck` | 通过 | 主进程 + renderer TypeScript |
| `npm run build` | 通过 | electron-vite 生产构建 |
| `npm run test:e2e` | 通过 | 160 卡 / 系统相机 92 / blocked 4、卡片无逐卡全尺寸 canvas、白/黑主题、Polaroid 4:3 导出、0 pageerror |
| `npm run validate:ir` | 通过 | 82 生成样式 / 82 变体 / 87 预览；0 error；37 条已知主题近似 warning |
| `npm run previews:stock` | 通过 | 生成系统相机预览与 `_research/stock-audit/previews-manifest.json`（含 SHA256） |
| Windows 打包版 | 通过 | 冷启动、品牌库 62 卡、0 pageerror |
| Android 签名 APK | **通过（欠 1 项）** | 安装 + 冷启动、选图/导出/系统分享；**品牌选择弹窗未目视** |

E2E 脚本：`scripts/e2e.mjs`，输出目录 `_research/e2e-export/`。

## 几何/版式（2026-10-24 至 10-25 01:45 的证据，历史）

> 下列条目的套数（如 90 套 / 144 卡）是当时批次值；当前计数见 PROJECT-BASELINE §1，当前保真度结论见 §2。

- 13 套原版模板：4:3 几何报告 `_research/preview-analysis/geometry-diff.json`。
- 13 套 × 3:2/16:9/1:1/4:5/2:3：65 张导出，联系表 `_research/ratio-matrix/contact-*.jpg`，目视无页脚越界/照片变形。
- 47 套原版参考/当前渲染并排表：`_research/pair-sheet/page-1..4.jpg`；已知结构错配已修复。
- `validate:ir` warning 集中在 texture/gradient/blur 主题的近似兜底，不是缺资产/断引用。
- 玻璃 01–03 侧栏卡、玻璃 04 参数页脚、票据 01/02、色卡族、色影显现已按参考重做。
- Polaroid：1:1、2:3、16:9 选框实测正确；4:3 通过 E2E 导出成功。
- 本轮新增 14 套 vendor 样式已用 4:3 夹具逐张导出：`_research/vendor-new-4x3/`；GR5、经典 19/71/97、Disney 31/32/33、Leica 91、标签 9/10/11、Zeiss 上框/上下框/内嵌均成功。
- **变形修复**：原版模板 EXIF 行不再使用 `scaleX`/斜切做横向拉伸，改为等比字号；模板卡 fallback canvas 不再被 CSS 强制铺成 4:3。受影响 13 套的 `renderedPreview` 已重生成。
- **OPPO fill 拉伸**：OPPO Gallery 适配器原先把 7 个位图资产宽高记为 0 并用 `fill`；`胶片 · 1` 实际 368×256 被塞进 74×32，横向拉约 61%。现已读取真实尺寸，比例差 >2% 自动改 `contain`；同批修复复古相机 2/3、胶片 2、品牌 3/6、大师签名 4。全库 fill 比例冲突复扫为 0。
- **PC 端全量目检（4:3，2026-10-24）**：90 套系统相机 + 68 套原版/手写逐张抓高分辨率主画布。证据：`_research/pc-audit-4x3/`（90 套，含 `sheets-final/`）与 `_research/pc-audit-originals/`（68 套）。本轮另修：OPPO EXIF 部件被重复成整行（改为按 `textSourcePart` 映射）、50dp 侧栏塞 97dp Logo 的 3 套（品牌 3/胶片 2/大师签名 3）与复古相机 5 重排、overlay 前导 space 导致的右侧裁切、film 12/103 主题、宋干节与标签/经典 71/97 对比度、film 15 页脚重排；文字超界统一等比缩放，band 内增加安全区。

## 打包/设备

- **当前交付基线（02:08）**：Windows zip、Android APK、docx、PDF 四文件与 `交付/SHA256.txt` 同批生成并 4/4 校验通过；字节数与 SHA256 见 PROJECT-BASELINE §4。
- **历史设备记录（2026-10-24 20:21 包）**：PGEM10 冷读 144 卡 / 系统 76 / blocked 17；Polaroid 导出 1184×1904 JPG 到 `Pictures/FrameMark/`，分享面板正常。证据图：`_research/android-verify/`。
- **历史打包批次（2026-10-24 22:52–22:54）**：Windows zip 280,280,785 B、APK 28,948,531 B，`sha256sum -c` 4/4 OK；这些数字已被 02:08 基线取代，仅作记录。

## 已知失败/缺口

- 原版 47 套完整逐像素报告未生成，TemplateSpec 未落地（13 套有实测几何，34 套未测量，0 套 `verified`）。
- 系统相机 92 套全部 `approx`，**无 `verified`**；5 套无厂商原版预览；字体/纹理/渐变仍为近似。
- blocked 4 组（小米 Leica 5/88/89 + vivo 39）不可导出，原因见 PROJECT-BASELINE §2.3。
- Android 品牌选择弹窗未目视（唯一平台欠项）。
- 品牌库仅 Samyang 因无可靠字标隐藏（62 可见 / 63 定义）；历史记录中的“15 个停产品牌隐藏”是 §7.6 当时状态。

## 复现

```bash
cd D:/border&watermark/framemark
npm run typecheck
npm run build
npm run test:e2e
```

单模板导出见 `scripts/export-variants.cjs`；Polaroid 导出参考 `_research/e2e-export/4x3-Polaroid · PLD.png`。
