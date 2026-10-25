# FrameMark 质量与发布门槛

更新：2026-10-25（W0b 文档收口）

> 本页只定义**保真度口径、禁止项与发布门槛**。当前通过项、计数、平台状态与交付哈希见 [PROJECT-BASELINE.md](PROJECT-BASELINE.md) §3–§4；需求分层见 [REQUIREMENTS.md](REQUIREMENTS.md)。

## 当前质量状态（摘要，明细以 PROJECT-BASELINE §3 为准）

- `typecheck` / `build` / `npm run test:e2e` / `npm run validate:ir`：通过（0 pageerror、0 error、37 条主题近似 warning）。
- Windows 打包版冷启动：通过；品牌库 62 卡、套用正常。
- Android：签名包安装、冷启动、选图/导出/系统分享通过；**品牌选择弹窗尚未目视**，属未完成项。
- 系统相机 92 套：全部 `approx`，**0 `verified`**；结构级核对覆盖 90 套（不含后补的小米 classic 22/99），逐套视觉验收未做。
- 原版 47 套：仅 13 套有 4:3 几何实测，34 套未测量；**完整 TemplateSpec 尚不存在，0 套达到像素级验收**。

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

1. `typecheck`、`build`、`npm run test:e2e`、`npm run validate:ir` 全绿。
2. 全部卡片（当前 160）使用静态图片或等比缩放的 canvas 兜底，滚动无逐卡全尺寸 canvas；禁止把非 4:3 canvas 直接铺满卡片。
3. 至少抽检 OPPO/小米/vivo 各一套导出；Windows 包冷启动通过。
4. Android 真机完成选图→预览→导出→相册/分享全链路，并完成一次品牌选择弹窗目视；当前前者已通过，后者仍欠。
5. blocked 样式在目录中可见且不可导出；approx 标注保留；未达到 `verified` 条件不得改状态。
6. 交付 zip/APK/docx/PDF 与 `SHA256.txt` 同批生成；文档数字与 `PROJECT-BASELINE.md` 一致，不各自维护。
7. 本项目仅供个人使用：不做 licensing / 公开发布授权审计；若要公开发布，属范围外的新任务，需先重做素材授权与替换，本清单不覆盖。

## 已知限制

- 系统相机 92 套全部 `approx`；小米 film 12/103 的 band 背景、texture/gradient 未按原素材实现。
- blocked 4 组：小米 Leica 5/88/89（侧栏绝对布局缺可用总宽度 / 底部 218dp 容器 + 照片内叠加 / 侧栏与叠加混合，真实障碍是绝对带宽与叠加布局超出 IR 表达，**非旋转文字**——三套小米 config 无任何 `rotation` 字段）+ vivo 39 套（已接 3 套 Zeiss，其余缺宿主布局或可信参考帧）。真正使用 band 级 `rotation:90` 的仅 3 套 OPPO 样式（`personalize_film_2`、`personalize_film_realme_2`、`personalize_masterSign_3`），均未被 blocked，旋转渲染另作独立特性推进。
- 5 套无厂商原版预览（Polaroid PLD、小米标准 6/7/8/107），只能按宿主 config 与素材核对，不能升级 `verified`。
- Android 分享仅覆盖单张导出成片；批量导出结果需在相册中自行分享。
- GR5 滤镜值未存 EXIF，侧栏值以“—”占位。
- 模板/水印缩放：EXIF 行不再横向 `scaleX`；品牌/位图节点按素材真实比例 `contain`；OPPO Gallery 适配器已修 7 处 `fill` 拉伸（含 `胶片 · 1` 61% 横向拉伸）；无静态预览的 canvas 兜底不再铺满 4:3。**历史证据（2026-10-24）**：PC 4:3 全量目检当时 90+68 张，已修 EXIF 部件重复、侧栏裁切、overlay 前导 space、film 12/103 主题与 film 15 页脚；证据在 `_research/pc-audit-4x3/` 与 `_research/pc-audit-originals/`。
- 原版 47 套尚无完整逐像素报告；TemplateSpec 尚未落地（13 套有几何实测，34 套未测量）。
- 品牌素材仅个人使用；按 2026-10-25 范围冻结，不做 public 授权审计（不是待办）。

## 相关

- 测试证据：`TEST-REPORT.md`
- 模板清单：`TEMPLATES.md`
- 交付目录：`../交付/`
