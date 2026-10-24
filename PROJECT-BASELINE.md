# FrameMark 项目基线（Project Baseline）

> **本页是唯一的“当前状态”页面（single source of truth）。**
> 其他文档只保留方法、分类、门槛与历史；凡涉及当前计数、平台状态、交付哈希、已知缺口或范围，一律链接本页，不再各自抄写数字。
>
> 文档收口：2026-10-25（W0b）；交付基线时间：**2026-10-25 02:08**（本机时区）；最近更新：2026-10-25（Batch B2a 收口）。
> 工作日志与历史证据：[../HANDOFF.md](../HANDOFF.md) §7（§7.5 起为按时间顺序的历史记录，数字是当时值）。

---

## 0. 当前波次（Wave 状态）

| 波次 | 内容 | 状态 | 产物 / 入口 |
|---|---|---|---|
| W0a | 源码快照 + `framemark/` 内 git 基线 | 完成 | HEAD `08fe989`；`_research/snapshots/framemark-src-20261025-0235.zip` |
| **W0b** | **文档单一事实源（本页 + REQUIREMENTS）** | **完成** | `PROJECT-BASELINE.md`、`REQUIREMENTS.md`、`_research/cleanup/docs-report.md` |
| W0c | 审计工具修复 | 完成 | `_research/cleanup/`、`scripts/audit-original47.cjs` |
| W0d | 47 套清单（TemplateSpec 骨架） | 完成 | `_research/original-47/manifest.json`（已接入本页 §2.1） |
| G0 | 主管验收 | 待 W0a/W0b/W0c/W0d（均已收口） | — |
| W1 | 47 套像素审计与修复 | 审计完成；**W1b A1 PASS 5/5、A2 PASS 3/3**（Batch A 8/8）、**B1a PASS 5/5**、**B1b glass-card PASS + glass-palette PASS + glass-pill-5 PROVISIONAL**、**B2a PASS 6/6（wm-classic/black/canon/left/wide/right）**；B2b/B3 + C 待启动 | `_research/original-47/specs/`、`specs-after/`、`sheets-after/`、`triage.json`；[`w1-triage.md`](../_research/cleanup/w1-triage.md)、[`w1b-a1-report.md`](../_research/cleanup/w1b-a1-report.md)、[`w1b-a2-report.md`](../_research/cleanup/w1b-a2-report.md)、[`b1a-verify-report.md`](../_research/cleanup/b1a-verify-report.md)、[`w1b-b1b1-report.md`](../_research/cleanup/w1b-b1b1-report.md)、[`w1b-b1b2-report.md`](../_research/cleanup/w1b-b1b2-report.md)、[`w1b-b2a1-report.md`](../_research/cleanup/w1b-b2a1-report.md)、[`w1b-b2a2-report.md`](../_research/cleanup/w1b-b2a2-report.md) |
| **W2** | **核心工作流 E2E** | **完成（PASS 106/106）** | `scripts/e2e-workflow.mjs`；[`w2-fix-report.md`](../_research/cleanup/w2-fix-report.md) |
| W3 | 最新厂商边框对齐 | 审计完成；目录未更新 | `_research/vendor-latest/`；[`oppo-extract-report.md`](../_research/cleanup/oppo-extract-report.md)、[`vendor-inventory-report.md`](../_research/cleanup/vendor-inventory-report.md) |
| W4–W5 | Android 真机、打包终验 | 未开始 | 本页 §3 |

本页是**唯一当前状态页**；表格里的“完成”只表示对应产物存在且可复核，不表示 47 套或 stock 已验收。

**W1**：47/47 套 TemplateSpec 已生成；审计基线 **5 pass / 39 fail / 3 provisional**（基线冻结，用于批次划分，不随修复回写）。修复批次 **A=8 / B=30 / C=4**；**Batch A 已完成 8/8**：A1 复测 PASS 5/5（white-2、white-3、person-2、person-3、person-4，EXIF 宽度偏差 ≤0.23pp），A2 复测 PASS 3/3（card-2/3/4，照片窗口宽高比 1.2832/1.2686/1.2846，偏差 ≤0.003%；画布比例偏差 ≤0.16%；边距/页脚/logo ≤2pp），A 修复 39 个 fail 中的 8 个。A2 说明：4:3 输入下卡片画布宽度按设计收窄（保留裁切高度、采用参考图宽高比），长边仍 ≤ maxEdge。**Batch B1a 已完成 5/5**（glass-white-1/2/3、glass-card-4、glass-liquid）：从当前工作区独立重渲后逐像素复测，5/5 模板全过，各套门槛指标 19/19、19/19、19/19、17/17、8/8（合计 82 项、0 失败，无 gated 未过项），重新导出与工作区重渲逐张 SHA-256 一致。头条值：白卡画布比 0.6926（目标 0.692，偏差 ≤0.21 %rel）；行距 11.679 / 11.679 / 11.726 %S（旧 7.317）；card-4 参数行 27.102 %W、分隔线 50.000 %W（参考实际规则 50.083 %W）；液态玻璃 logo 中心 48.911 %W、日期左 8.292 %W、EXIF 左 57.414 %W。证据 `_research/original-47/specs-after/*-verified.json`、`exports/4x3-b1a-verify/`、`sheets-after/*-ref-ours-verify.png`。**Batch B1b 已完成 3/3**：glass-card PASS（卡片比 1.3333、宽 56.25 %W、右距 6.35 %W，纵向差为 4:3 画布下的几何强制）、glass-palette PASS（画布比 0.7498、面板 89.22 %W × 29.66 %H、2 行 × 3 列六色卡、直径 10.94 %W）、glass-pill-5 **PROVISIONAL**（结构已对齐卡片比 0.6903 / 88.70 %H，参考图置信度低，字标与等宽字宽为字体替换）。**Batch B2a 已完成 6/6**：wm-classic、wm-black、wm-canon、wm-left、wm-wide、wm-right 逐一复渲复测 PASS；wm-black 为**全出血 overlay**（照片 100 %H、无底栏，标题/logo/品牌左列 + 右列 exif/规格/日期），wm-right 为**左右 1.32 %W + 上 0.88 %H 外框** + 8.38 %W 底栏；槽位偏差大多 ≤0.3pp（wm-black 的 exif/date 左位 -1.74/+1.59pp 仍在门槛内）。品牌字标差异（fixture Panasonic/LUMIX 字标 vs 参考 Canon/Leica 徽标）为**素材残余，不是结构失败**。**当前累计 26 pass / 17 fail / 4 provisional（共 47）= 原有 5 + Batch A 8 + B1a 5 + glass-card + glass-palette + B2a 6；glass-pill-5 由 fail 转为 provisional**。**剩余 21 套未 pass**：B2b 3（grad-1/2、video-cover）、B3 13、C 4（color-3 fail；simple-1/2/4 provisional），以及 B1b 的 glass-pill-5（provisional）。B 与 C 尚未跑完，**47 套仍不代表整体通过，0 套 `verified`。**

**W2**：核心工作流 E2E **PASS 106/106 断言，failed 0，pageerror 0**；覆盖双图批量、预设保存/重载/套用、自定义 PNG 水印、全部参数、文件名 token、JPG/PNG/WebP/TIFF、取消导出、预设 JSON 往返；预设 `outputDir` 可移植性缺陷已修复。

**W3**：OPPO Gallery **17.10.7** 已从 Find X9 Ultra 包提取；43 个最新 Gallery 样式为 **25 same / 14 outdated / 5 missing**（14 outdated = 13 changed + 1 不再随包），另有 **10** 个相机包样式 `unverifiable`；小米 **7 套 outdated**；vivo **39 套仍 blocked**。目录与变体尚未按最新包更新。

---

## 1. 目录计数（当前）

| 项目 | 当前值 | 说明 |
|---|---:|---|
| 模板库卡片 | **160** | 手写 **68** + 系统相机 **92** |
| 手写变体 | **68** | `V(...)`；其中 47 个为原版复刻，其余为自研/精选 |
| 系统相机 stock | **92** | 分解见下 |
| 原版参考图 | **47** | `src/renderer/src/assets/previews/` 根目录（45 webp + 1 png + 1 jpg） |
| 手写渲染预览 | **73** | `assets/previews/rendered/*.webp` |
| 系统相机预览 | **87** | `assets/previews/system/*.webp`；5 套无独立预览 |
| 生成 stock 样式 | **82** | 39 OPPO Gallery + 9 CameraPro + 34 小米；全部 `approx`，**0 `verified`** |
| 品牌字标 | **62 可见 / 63 定义** | Samyang 无可靠独立字标，隐藏不列出 |
| blocked | **4 组 / 42 个样式** | 小米 Leica 5/88/89 + vivo 全部（39 套），见 §2.3 |

**系统相机 92 套分解**

- **OPPO 49** = Gallery 39 + CameraPro 9 + Polaroid PLD 1
- **小米 40** = 生成 34 + 侧栏 classic 22/99 2 + 标准 6/7/8/107 4
- **vivo 3** = Zeiss 上框 / 上下框 / 内嵌

---

## 2. 保真度状态

### 2.1 原版 47 套（手写原版复刻）

- 47 张原版参考图 **1:1 映射到 68 套手写变体中的 47 套**（`variants.ts` 中带原版 `preview` 的那一批）；其余 21 套为自研/精选，不在 L2 范围。
- 早期只有 **13 套**有 manifest 矩形：纯白 1–4、卡片 1–4、人物 1–5（来自 [HANDOFF](../HANDOFF.md) §6.2）；W1 已对 47 套逐套重新测量。
- **47/47 套 TemplateSpec 已生成**（`_research/original-47/specs/`）；审计基线判定 **5 pass / 39 fail / 3 provisional**，修复批次 A=8 / B=30 / C=4。Batch A 已完成 8/8：A1 修复 white-2/3、person-2/3/4，A2 修复 card-2/3/4（照片窗口宽高比 1.2832/1.2686/1.2846，均复测 PASS，证据 `_research/original-47/specs-after/`）；Batch B1a 已完成 5/5：glass-white-1/2/3、glass-card-4、glass-liquid 独立复渲复测全部 PASS（各套门槛 19/19、19/19、19/19、17/17、8/8，合计 82 项 0 失败，导出与重渲 SHA-256 一致）。Batch B1b 已完成 3/3：glass-card 与 glass-palette 复测 PASS（glass-palette 画布比 0.7498、面板 89.22 %W × 29.66 %H、2×3 色卡），glass-pill-5 结构对齐但参考置信度低，判 PROVISIONAL。Batch B2a 已完成 6/6：wm-classic、wm-black、wm-canon、wm-left、wm-wide、wm-right 独立复渲复测 PASS（wm-black 全出血 overlay、无底栏；wm-right 左右/上外框 + 8.38 %W 底栏；槽位偏差大多 ≤0.3pp）；品牌字标差异（fixture Panasonic/LUMIX vs 参考 Canon/Leica）为素材残余，不是结构失败。**26 个已测量模板现在全部通过各自规格**（基线已 pass 的 white-1/4、card-1、person-1/5 ＋ A1/A2 修复的 8 套 ＋ B1a 修复的 5 套 ＋ B1b 的 glass-card、glass-palette ＋ B2a 的 6 套 wm-*），累计 **26 pass / 17 fail / 4 provisional**；剩余 21 套未 pass（B2b 3、B3 13、C 4 共 20 套 ＋ glass-pill-5 provisional），由 B2b/B3 与 C 继续。
- 因此 47 套**仍都不能称为 `verified`**：审计的 5 pass 只表示该轮测量项达标；Batch A 的 8 套、B1a 的 5 套、B1b 的 2 套（glass-card、glass-palette）与 B2a 的 6 套（wm-*）已完成修复复测，仍需主管复核；glass-pill-5 保持 provisional，其余套别尚未修复与复验。
- **字体残余**：EXIF 字形仍以 Chakra Petch 水平缩放近似（`exifScaleX` 1.09–1.49），不是原版的真实扩展字面（跨批次残余，未修复）；`FONT_TECH` 未预加载导致冷启动首渲回退 OplusSans 的问题已由 A2 修复（`ensureFonts()` 预加载 Chakra Petch 600 常规 + 斜体）。
- 几何输入是 `_research/original-47/geometry-diff.json`，由 `_research/original-47/manifest.json` 驱动（47 行；早期 13 已知 / 34 未测量，W1 已用 47 套 TemplateSpec 补测）。旧的 `_research/preview-analysis/geometry-diff.json`、`_research/pair-sheet/` 只作历史证据，不等于 47 套验收完成。

### 2.2 系统相机 92 套

- 较早一批 90 套完成过 4:3 结构级核对（其中 85 套有厂商原版预览、5 套 config-only）；后补的小米 classic 22/99 按小米 17 Ultra config 接入。
- **82 个生成 stock 样式 + 手工覆盖样式全部为 `approx`，0 `verified`。**
- 已知近似来源：字体替换、图标细节、透明/纹理/渐变的纯色兜底、精确 dp→px 边距；GR5 参数边栏字号/行距偏大。
- **5 套无厂商原版预览**（Polaroid PLD、小米标准 6/7/8/107），只能按宿主 config 与素材核对，不能升级 `verified`。

### 2.3 blocked（4 组 / 42 个样式）

| 组 | 原因 | 可导出 |
|---|---|---|
| 小米 Leica 5 | outer-border 侧栏为绝对布局，缺可用总宽度 | 否 |
| 小米 Leica 88 | 底部 218dp 容器 + 照片内叠加，根结构不匹配 | 否 |
| 小米 Leica 89 | 侧栏与照片内叠加混合，缺可用总宽度 | 否 |
| vivo 全部（39 套） | 已接 3 套 Zeiss；其余缺宿主布局/可信参考帧 | 否 |

blocked 卡片在目录内可见、写明原因、不可点击导出；没有依据时不伪造布局、滤镜数值或字标。

---

## 3. 平台与验证状态

| 平台 / 检查 | 状态 | 备注 |
|---|---|---|
| `typecheck` / `build` | 通过 | 02:05 批次；A1、A2 模板改动后各自复跑通过（按两份报告）；B1a 独立复渲前 `npm run build` + `npm run typecheck`（exit 0）通过；B1b、B2a 各报告记录 `npm run typecheck` + `npm run build` PASS（本页按报告核对，未复跑） |
| `npm run test:e2e` | 通过 | legacy：160 卡 / 系统 92 / blocked 4；errors 0 |
| `npm run test:workflow` | 通过 | 106/106 断言 / failed 0 / pageerror 0（W2 核心工作流） |
| `npm run validate:ir` | 通过 | 82 生成样式 / 82 生成变体 / 87 预览；0 error；37 条主题近似 warning |
| Windows 桌面 | 通过 | 打包版冷启动正常；品牌库 62 卡、套用正常、0 pageerror |
| Android | **冷启动通过，细项目检欠 1 项** | 02:05 APK 已安装并冷启动；底栏（bottom-tab）移动端界面已建；选图/导出/系统分享已验；**品牌选择弹窗尚未目视** |
| 多比例 | 历史全量目检通过 | 上一轮 4:3 + 3:2/2:3/16:9/1:1/4:5/3:4/9:16 未见拉伸/裁切/重叠；后续新增模式未重跑同等全量 |

---

## 4. 交付基线（2026-10-25 02:08）

| 文件 | 字节数 | SHA-256 | 校验 |
|---|---:|---|---|
| `交付/FrameMark-Windows-x64.zip` | **280,969,776** | `b1011ff1853929e18c3ebaa49d6b82ca65ab16b78a2c2cda809a54f6ecedf33f` | OK |
| `交付/FrameMark-Android-v1.0.0.apk` | **29,635,439** | `1f77d3ab112b873796d4cb8231eeac0e6d0ed4c5e18e07af4bde6127afe6cf58` | OK |
| `交付/FrameMark-使用与验收说明.docx` | **878,978** | `38cb66252432f7ee060a73cc6bddbeea2c54b956b3daf7263fd002d9ca5a8f4b` | OK |
| `交付/FrameMark-使用与验收说明.pdf` | **1,116,992** | `446017b511e91a00e6dce64d4217f63a12a3fb16abe5cfa4a5de5f030d983489` | OK |

`交付/SHA256.txt` 四文件校验 **4/4 OK**。任何代码、素材或文档数字改动都应重新打包并重算本表；只做审计不改代码时不重打包。W2 已改 `presets.ts` / `store.ts`，交付包需在 W5 重新打包并重算。

---

## 5. 已知缺口

1. **原版 47 套未验收**：47/47 TemplateSpec 已生成，审计基线 5 pass / 39 fail / 3 provisional；修复批次 A=8 / B=30 / C=4，Batch A 已完成 8/8（A1 5/5 + A2 3/3）、Batch B1a 已完成 5/5、Batch B1b 已完成 3/3（glass-card PASS、glass-palette PASS、glass-pill-5 PROVISIONAL）、Batch B2a 已完成 6/6（wm-*）；累计 26 pass / 17 fail / 4 provisional，剩余 21 套未 pass（B2b 3、B3 13、C 4 = color-3 fail + simple-1/2/4 provisional，＋ glass-pill-5），0 套达到整体像素级验收。
2. **系统相机全部 `approx`**：82 个生成 stock 样式 0 `verified`；5 套无厂商原版预览；字体/纹理/渐变仍为近似。
3. **4 组 blocked / 42 个样式**：小米 Leica 5/88/89 + vivo 39；需要旋转文字、重叠布局或宿主参考帧支持。
4. **Android 品牌选择弹窗未目视**：02:05 包冷启动通过，但弹窗级 UI 只做了代码/入口确认，未完成真机目检。
5. **授权审计不做**：按 2026-10-25 范围冻结，项目只按个人使用维护，不做 licensing / 公开发布审计（这是范围决定，不是待办）。
6. **文档口径已收口**：历史数字只保留在 `HANDOFF.md` §7.5–§7.18 / §8 的标记历史区，当前事实只认本页。

---

## 6. 范围冻结（2026-10-25 用户决定）

- **只做两件事**：
  1. 原版 **47 套逐模板像素规格**（TemplateSpec + 几何/字体/颜色/素材 + 逐套目视结论）；
  2. 最新厂商边框线：只覆盖最新 **OPPO / vivo / 小米** 包 —— **Find X9 Ultra、X500 Pro Max、小米 17 Ultra / NeoHyper**。
- **个人使用**：不进行 licensing / 公开发布授权审计，不以公开分发为目标调整素材或代码。
- **不接旧厂商样式**：旧 OPPO/vivo/小米 历史包、停产样式不再扩充；现有旧样式保持现状，不新增。
- **不做拼图 / 视频 / 动态边框**（collage / video / motion borders）。
- **不伪造**：没有厂商参考帧或可信布局依据的样式保持 blocked；不编造滤镜值、字号或字标。
- **stock 默认 `approx`**：只有拿到原版参考帧并逐项目检通过，才可升级 `verified`。

---

## 7. 文档地图

| 文档 | 职责 | 是否含当前数字 |
|---|---|---|
| `framemark/PROJECT-BASELINE.md`（本页） | 唯一当前状态：计数、平台、交付、缺口、范围 | 是（唯一） |
| `framemark/REQUIREMENTS.md` | 需求分层与验收规则（L1/L2/L3） | 否（链接本页） |
| `framemark/README.md` | 功能、构建、隐私与使用说明 | 否（链接本页） |
| `framemark/TEMPLATES.md` | 模板分类与两条渲染路线 | 否（链接本页） |
| `framemark/QUALITY.md` | 保真度定义与发布门槛 | 否（链接本页） |
| `framemark/TEST-REPORT.md` | 测试方法、证据索引与结论 | 否（链接本页） |
| [`HANDOFF.md`](../HANDOFF.md) | 协作约定、最短恢复路径、**§7 历史工作日志** | 仅历史区保留旧值 |

---

## 8. 证据索引

- 原版几何：`_research/preview-analysis/geometry-diff.json`、`_research/pair-sheet/`
- 系统相机结构核对：`_research/stock-fidelity/report.md`、`_research/stock-fidelity-vendor/sheet-01..18.jpg`
- 4:3 全量目检：`_research/pc-audit-4x3/`、`_research/pc-audit-originals/`
- 多比例：`_research/ratio-audit/`
- Android：`_research/android-verify/`
- 交付校验：`交付/SHA256.txt`
- 清理基线：`_research/cleanup/baseline-report.md`、`baseline-hygiene.md`
