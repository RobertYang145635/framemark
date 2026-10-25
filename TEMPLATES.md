# FrameMark 模板总览

更新：2026-10-25（W0b 文档收口）

> 本页只讲**分类与两条渲染路线**，不维护计数。当前卡片数、stock 分解、blocked 明细与保真度状态一律见 [PROJECT-BASELINE.md](PROJECT-BASELINE.md) §1–§2。

## 计数

本页不再单独维护数字。当前口径：**160 卡 = 手写 68 + 系统相机 92**（OPPO 49 / 小米 40 / vivo 3），blocked **4 组 / 42 个样式**（小米 Leica 5/88/89 + vivo 39），品牌 **62 可见 / 63 定义**（Samyang 隐藏）；分解、预览张数与证据链接见 [PROJECT-BASELINE](PROJECT-BASELINE.md) §1、§2.3。

## 原版 / 自研模板

| 分类 | 说明 |
|---|---|
| 厂商水印 | 品牌页脚、双行/单行相机水印 |
| 个人边框 | 头像/昵称/机型卡片 |
| 背景模糊 | 背景模糊 + 前景照片 |
| 水印边框 | 相机品牌页脚与 EXIF |
| 参数 | 四角 EXIF/参数堆叠 |
| 留白 | 白边、米白、色卡、自然诗篇 |
| 卡片 | 图标页脚、留白页脚 |
| 人物 | 头像页眉/浮层/页脚 |
| 液态玻璃 | 玻璃卡片、胶囊、色卡、侧栏卡、参数页脚 |
| 色卡 | 色卡大样、左右色卡、色彩显现、坐标卡 |
| 电影 | 宽银幕字幕 |
| 票据 | 侧存根、竖卡 |
| 毕业 / 比例 | 毕业边框、比例角标 |
| FrameMark 精选 | 自研编辑、拍立得、齿孔、竖排字幕 |

原版卡片使用当前渲染器生成的 `renderedPreview`；原版参考图只保留在“原版”参考画廊。47 套参考/渲染并排表在 `_research/pair-sheet/`。

## 系统相机

### OPPO Gallery 39

- `oppo-gallery:*`，含哈苏、LUMO、品牌/胶片/签名/复古相机等家族。
- 白/黑纯色主题可用；非纯色主题仍隐藏。

### OPPO CameraPro 9 + Polaroid PLD 1

- 理光 GR 1–5（上框/页脚/下框/叠加/参数边栏）、Tone 上框/页脚、Leica Live Moment、液态玻璃；Polaroid PLD 单列 1 套。
- Polaroid 按 1:1/4:3/16:9/wide 与横竖自动选 8 张 frame。
- GR5：右侧 120dp 参数边栏；滤镜值不在 EXIF，数值位显示“—”并在全部参数里保留可调空间。

### 小米 40

- v36 精选：classic/film/leica/standard/tag 等可转换样式；34 套生成 + 4 套手写 standard（6/7/8/107）+ 2 套侧栏 classic 22/99。
- 10 套 in-style overlay：classic 19/71/97、Disney 31/32/33、Leica 91、标签 9/10/11；背景透明、按宿主 anchor 贴照片。
- classic 22/99 按小米 17 Ultra（NeoHyper）config 接入右侧栏；Leica 5/88/89 仍 blocked（侧栏绝对布局缺可用总宽度 / 底部 218dp 容器与照片内叠加超出 IR 表达）；**非旋转文字**——三套小米 config 均无 `rotation` 字段，竖排仅为垂直堆叠（IR `column` 已支持），见 orient-audit.md。

### vivo 3 / 39 blocked

- 已归档 42 套 APK 资源、27 组 PAG。
- 已接 3 套 Zeiss：上框、上下框、内嵌；上框 305/220、上下框 220/220 来自 4:3 PAG 图层几何。
- 其余 39 套缺宿主布局/可信参考帧；目录中合并显示 blocked 原因。

## 状态说明

- `verified`：与参考几何/素材对齐并通过人工验收；当前系统相机 **0 套**。
- `approx`：可导出，但存在字体替换、纹理/渐变近似或未逐套验收；当前系统相机 **92 套全部 approx**（生成样式 82 + 手写接入 10）。
- `blocked`：缺布局/素材/元数据，不可导出；目录内明示原因。
- 逐套明细、生成/预览张数与证据索引见 [PROJECT-BASELINE.md](PROJECT-BASELINE.md) §2。

## 相关

- 当前状态与计数：`PROJECT-BASELINE.md`
- 需求分层与验收规则：`REQUIREMENTS.md`
- 品牌与素材来源：`_research/extracted/WATERMARK-CATALOG.md`
- 品牌字标来源：`_research/logos-real/manifest.json`
- 参考/渲染并排：`_research/pair-sheet/`
- 多比例回归：`_research/ratio-matrix/`（历史）、`_research/ratio-audit/`（全量）
