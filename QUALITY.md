# FrameMark 质量与发布门槛

更新：2026-10-24

## 当前质量状态

- `typecheck` / `build`：通过。
- `npm run test:e2e`：通过（158 卡、系统相机 90、blocked 6、白/黑主题、Polaroid 4:3 导出、0 pageerror）。
- Windows 包冷启动：通过（158 卡 / 系统 90 / blocked 6 / 无 pageerror）。
- Android APK：构建、签名、安装通过；20:21 包完成 144/76/17 冷读/选图/导出/分享；21:13 的 158/90/6 新包已安装，界面复测待补。
- 系统相机 90 套：全部 `approx`，未逐套视觉验收；本轮新增 OPPO GR5、小米 10 套 overlay、vivo Zeiss 3 套已按 4:3 逐张导出。
- 原版 47 套：已知结构错配已清理；13 套完成 4:3 几何测量和 5 比例导出，完整逐像素报告仍未完成。

## 保真度规则

- `verified`：布局、字体、素材与参考一致，且通过多比例/导出自检。当前系统相机没有 verified。
- `approx`：可导出，但存在明确近似（字体替换、texture/gradient 未完整实现、液态玻璃折射、GR5 参数缺失等）；UI/目录标“近似”。
- `blocked`：缺布局、素材或运行数据，卡片灰显并写原因，不导出。
- 不用无可靠来源的 Logo/参考帧冒充官方素材。品牌库隐藏无源品牌，保留来源清单。

## 不允许的“通过”

- 只通过统计量、未看图就宣布对齐。
- 把自动转换失败的 in-style/overlay 样式标 verified。
- 用原版参考图当卡片缩略图，导致“缩略图原版、导出另一套”。
- 用假字标或猜测的滤镜数值填充 blocked 样式。

## 发布门槛

1. `typecheck`、`build`、`npm run test:e2e` 全绿。
2. 158 张卡片全部使用静态图片或等比缩放的 canvas 兜底，滚动无逐卡全尺寸 canvas；禁止把非 4:3 canvas 直接铺满卡片。
3. 至少抽检 OPPO/小米/vivo 各一套导出；Windows 包冷启动通过。
4. Android 真机完成选图→预览→导出→相册/分享全链路；当前已通过 PGEM10 签名包实测（上一版 144/76/17，待新包复测）。
5. blocked 样式在目录中可见且不可导出；approx 标注保留。
6. 交付 zip/APK/docx/PDF 与 `SHA256.txt` 同批生成，README 数字一致。
7. public 构建若发布，必须替换无权再分发的字体/位图/Logo，并重新跑本清单。

## 已知限制

- 系统相机全部 approx；小米 film 12/103 的 band 背景、texture/gradient 未按原素材实现。
- vivo 42 套未接入；小米 15 套 in-style、OPPO GR5 blocked。
- Android 分享仅覆盖单张导出成片；批量导出结果需在相册中自行分享。
- 系统相机 90 套中 5 套小米侧栏与 39 套 vivo 仍 blocked；GR5 滤镜值未存 EXIF，侧栏值以“—”占位。
- 模板/水印缩放：EXIF 行不再横向 `scaleX`；品牌/位图节点按素材真实比例 `contain`；OPPO Gallery 适配器已修 7 处 `fill` 拉伸（含 `胶片 · 1` 61% 横向拉伸）；无静态预览的 canvas 兜底不再铺满 4:3。PC 4:3 全量目检 90+68 张，已修 EXIF 部件重复、侧栏裁切、overlay 前导 space、film 12/103 主题与 film 15 页脚；证据在 `_research/pc-audit-4x3/` 与 `_research/pc-audit-originals/`。
- 原版 47 套尚无完整逐像素报告；TemplateSpec 尚未落地。
- 品牌素材仅个人使用；public 授权审计未完成。

## 相关

- 测试证据：`TEST-REPORT.md`
- 模板清单：`TEMPLATES.md`
- 交付目录：`../交付/`
