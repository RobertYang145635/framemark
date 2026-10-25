# FrameMark 项目基线（Project Baseline）

> **本页是唯一的“当前状态”页面（single source of truth）。**
> 其他文档只保留方法、分类、门槛与历史；凡涉及当前计数、平台状态、交付哈希、已知缺口或范围，一律链接本页，不再各自抄写数字。
>
> 文档收口：2026-10-25（W0b）；交付基线时间：**2026-10-25 23:22**（ALIGN-PACKAGE 重打包，本机时区）；最近更新：2026-10-25（**ANCHOR-PACKAGE**：`ff32b4b` feat(system-camera) 3 套 OPPO 侧边条带样式支持厂商 band 旋转（`personalize_film_2` / `personalize_film_realme_2` / `personalize_masterSign_3`，仍 `approx`、待真机样张判定）与 `3b48e89` feat(system-camera) **系统相机九宫格锚点选择器**落库——stock 水印位置由 `AnchorGrid`（与自定义水印面板共用控件）+ 第 10 项「厂商原版」默认位选择，整组可动条带（含旋转条带）作为刚性整体平移到照片画布锚点位并限幅不出界，X/Y 滑块降级为水平/垂直微调；默认渲染与 rotation-verify 基线**逐字节一致**（4 张默认导出 Buffer.equals 全 true）；typecheck/build 0、e2e 164/96/4、workflow 106/106、validate-ir 90 样式 0 error、watermark-e2e 锚点选择器 `.anchor-grid button[title="右下"]` 未破坏；打包版 exe 复测 **164/96/4/62、0 pageerror 且锚点实测可用**（`anchor-packaged.json`）；真机覆盖安装冷启动 **764 ms / LaunchState COLD**（`fm-anchor-cold.png`）；交付集（zip/APK/docx/PDF/SHA256.txt）全部重签发，`sha256sum -c` **4/4 OK**；此前 **CLOSE-OUT 收口**：REPACKAGE 之后又有四个提交落库——`394ebe1` fix(vendor) 小米 FrameLayout gravity（leica-111 字标散落修复）、`549a653` feat(system-camera) 品牌字标改随照片 EXIF 品牌 / Inspector 手选、`652807f` fix(vendor) 小米 leica-92 焦距行重力、`ddd9dec` chore(previews) wm-black 预览重生成；全量重打包并刷新交付集，HEAD **`ddd9dec`**；**W1 终审 47/47 PASS**、**W2 workflow 106/106**、**W3 厂商同步（OPPO Gallery 17.10.7 + 小米 NeoHyper 17 Ultra）**、**W4 Android 真机走查四项全 PASS**、**CLOSE-OUT 打包版复测 164/96/4/62、0 pageerror，真机冷启动 365 ms / LaunchState COLD**、**打包版品牌字标抽检：DC-S1 样张下 leica-91/111 字标均为 Panasonic**；绘制级文字对文字重叠全库仅剩 1 处（`hassel_style_xpan_1` 厂商负行距，字形可读，按保真原则保留）；目录 **164 卡 = 68 手写 + 96 系统相机**，95 套 stock 全 `approx`、**0 `verified`**）。
> **当前状态：待用户目视签字**——机器门槛（typecheck / build / e2e / workflow / validate:ir / 47 套逐套测量 / 真机走查）全绿，唯一欠项是用户对 47 套与厂商线的目视签收；签收前不宣称 pixel-perfect、不升级任何 `verified`。
> 提交链（新→旧）：`e256965` fix(system-camera) 品牌字标按厂商槽墨迹高度/中心线/自身推导间距对齐（37 套预览随迁） · `3b48e89` feat(system-camera) 九宫格锚点选择器 · `de48ff9` docs 更正小米 Leica blocked 原因 · `ff32b4b` feat(system-camera) 3 套 OPPO 侧边条带旋转 · `b242f7c` fix(store) 模板切换文字开关复位/模糊日期用拍摄日期 · `be150e1` fix(templates) wm-black 镜头行恢复 + EXIF 内容去重 · `0ef4f83` fix(render) 文字/图形重叠清除 + realme 条带修复 · `5af87d7` docs 交付文档口径 · `418f737` docs 清理收口 · `3e4af60` feat(vendor) OPPO 17.10.7 + 小米 17U · `5dac9c6` W1 47 套终审 · `fc12d99` Batch C · `edfb484` B3b-3 · `73a3fd7` B3b-1/2 · `f35d4f2` B3a · `27484c0` B2b · `23f00d9` B2a · `f69d77f` B1b · `f1410c2` B1a · `e002e18` A2 · `9657624` A1 · `438c7a1` W2 · `76448c2` W0 文档/工具 · `08fe989` 基线卫生 · `3311904` git 基线。
> 工作日志与历史证据：[../HANDOFF.md](../HANDOFF.md) §7（§7.5 起为按时间顺序的历史记录，数字是当时值）、§14（W0–W5 收口）。

---

## 0. 当前波次（Wave 状态）

| 波次 | 内容 | 状态 | 产物 / 入口 |
|---|---|---|---|
| W0a | 源码快照 + `framemark/` 内 git 基线 | 完成 | 当时 HEAD `08fe989`（当前 HEAD `b242f7c`）；`_research/snapshots/framemark-src-20261025-0235.zip` |
| **W0b** | **文档单一事实源（本页 + REQUIREMENTS）** | **完成** | `PROJECT-BASELINE.md`、`REQUIREMENTS.md`、`_research/cleanup/docs-report.md` |
| W0c | 审计工具修复 | 完成 | `_research/cleanup/`、`scripts/audit-original47.cjs` |
| W0d | 47 套清单（TemplateSpec 骨架） | 完成 | `_research/original-47/manifest.json`（已接入本页 §2.1） |
| G0 | 主管验收 | **完成**（W0 四件套均已收口并提交 `76448c2`） | — |
| W1 | 47 套像素审计与修复 | **完成（已提交 `9657624`…`fc12d99`，终审 `5dac9c6`）**；**W1b A1 PASS 5/5、A2 PASS 3/3**（Batch A 8/8）、**B1a PASS 5/5**、**B1b glass-card PASS + glass-palette PASS + glass-pill-5 PROVISIONAL（后由 C 复测 PASS）**、**B2a PASS 6/6（wm-classic/black/canon/left/wide/right）**、**B2b PASS 3/3（grad-1/grad-2/video-cover；前两者使用 APK 原版毕业贴纸）**、**B3a PASS 5/5（movie-1/2、ratio-1/2/3）**、**B3b-1/2 PASS 5/5（color-1/2/3、color-walk、color-reveal）**、**B3b-3 PASS 4/4（new-color-2、ticket-1、ticket-2、simple-3）**；B3b 已全部收口；**Batch C PASS 4/4（simple-1/2/4、glass-pill-5）**；**W1 终审完成：47/47 PASS（13 套独立重测 + 34 套批次逐字节一致），0 provisional / 0 fail，无结构失败；用户目视签收待做** | `_research/original-47/specs/`、`specs-after/`、`sheets-after/`、`triage.json`；[`w1-triage.md`](../_research/cleanup/w1-triage.md)、[`w1b-a1-report.md`](../_research/cleanup/w1b-a1-report.md)、[`w1b-a2-report.md`](../_research/cleanup/w1b-a2-report.md)、[`b1a-verify-report.md`](../_research/cleanup/b1a-verify-report.md)、[`w1b-b1b1-report.md`](../_research/cleanup/w1b-b1b1-report.md)、[`w1b-b1b2-report.md`](../_research/cleanup/w1b-b1b2-report.md)、[`w1b-b2a1-report.md`](../_research/cleanup/w1b-b2a1-report.md)、[`w1b-b2a2-report.md`](../_research/cleanup/w1b-b2a2-report.md)、[`w1b-b2b-report.md`](../_research/cleanup/w1b-b2b-report.md)、[`w1b-b3a1-report.md`](../_research/cleanup/w1b-b3a1-report.md)、[`w1b-b3a2-report.md`](../_research/cleanup/w1b-b3a2-report.md)、[`w1b-b3b1-report.md`](../_research/cleanup/w1b-b3b1-report.md)、[`w1b-b3b2-report.md`](../_research/cleanup/w1b-b3b2-report.md)、[`w1b-b3b3a-report.md`](../_research/cleanup/w1b-b3b3a-report.md)、[`w1b-b3b3b-report.md`](../_research/cleanup/w1b-b3b3b-report.md)、[`w1b-c-report.md`](../_research/cleanup/w1b-c-report.md)、[`FINAL-AUDIT.md`](../_research/cleanup/FINAL-AUDIT.md)、[`final-verify-b-audit47.json`](../_research/cleanup/final-verify-b-audit47.json) |
| **W2** | **核心工作流 E2E** | **完成（已提交 `438c7a1`；PASS 106/106）** | `scripts/e2e-workflow.mjs`；[`w2-fix-report.md`](../_research/cleanup/w2-fix-report.md) |
| W3 | 最新厂商边框对齐 | **完成（已提交 `3e4af60`）**：OPPO Gallery **17.10.7** 43 套已重导（13 套过时样式已同步、+5 realme、`brand_3` 退役、10 个 CameraPro 样式 `unverifiable`）、小米 **NeoHyper 17 Ultra** 7 套已同步（`film 15` / `leica 2,91` / `standard 6,7,8,107`，后者由手写迁入生成 IR、计数不变）；**vivo 39 套仍 blocked（缺宿主布局与可信参考帧）**；95 套 stock 全 `approx`、**0 `verified`**；ASSESS-W3 GREEN / KEEP-ALL | `_research/vendor-latest/`、[`w3-assess.md`](../_research/cleanup/w3-assess.md)、[`w3b-oppo-data-report.md`](../_research/cleanup/w3b-oppo-data-report.md)、[`w3b-oppo-ui-report.md`](../_research/cleanup/w3b-oppo-ui-report.md)、[`w3b-xiaomi-report.md`](../_research/cleanup/w3b-xiaomi-report.md)；[`oppo-extract-report.md`](../_research/cleanup/oppo-extract-report.md)、[`vendor-inventory-report.md`](../_research/cleanup/vendor-inventory-report.md) |
| **W4** | **Android 真机走查（PGEM10）** | **完成**：冷启动 300 ms；2 张批量导出 PASS；自定义 PNG 水印上画布 PASS；新 W3 样式带 `近似` 角标正常渲染 PASS；单张导出 → 分享 → Android 系统选择器 PASS（`s3_share.png`）；上报的 4 项「缺陷」全部裁定为测试脚本产物或设计行为（P0 0 / P1 0） | `_research/android-walkthrough/`、[`walkthrough-report.md`](../_research/android-walkthrough/walkthrough-report.md)、[`mobile-triage.md`](../_research/cleanup/mobile-triage.md) |
| **W5** | **打包与最终验收** | **完成**：Windows zip **281,392,888 B** / APK **30,040,222 B**（哈希见 §4）；打包版 exe 实测 **164 / 96 / 4 / 62、0 pageerror**；汇总报告 [`FINAL-ACCEPTANCE.md`](../_research/cleanup/FINAL-ACCEPTANCE.md)。**当前状态：待用户目视签字** | `_research/packaging/`、[`packaging-report.md`](../_research/cleanup/packaging-report.md)；本页 §3–§5 |
| **REPACKAGE** | **修复后重打包（本轮）** | **完成**：`0ef4f83`+`be150e1`+`b242f7c` 落库后 typecheck/build 0、e2e 164/96/4、workflow 106/106、validate:ir 90/90/95 0 error；zip **281,403,373 B** / APK **30,046,986 B**（见 §4）；打包版 exe 复测 **164/96/4/62、0 pageerror**；真机覆盖安装冷启动 **416 ms / LaunchState COLD**、数据保留；交付集（docx/PDF/SHA256.txt）全部重签发，`sha256sum -c` **4/4 OK**；保真重导出评审集 `_research/review-164/exports-real/`（164 卡，**0 项真实变形/重叠缺陷**）。**已知状态泄漏缺陷已修**：切换模板会残留上一个模板的文字开关（EXIF/文字类 flag 不随变体重置），`b242f7c` 起切换即复位 | `_research/cleanup/repackage-report.md`、`_research/packaging/win-packaged-counts-fixed.json`、`_research/android-verify/fm-fixed-cold.png`、`_research/review-164/exports-real/` |
| **CLOSE-OUT** | **品牌字标 + 厂商版式修复后重打包（本轮）** | **完成**：`394ebe1`+`549a653`+`652807f`+`ddd9dec` 落库后 typecheck/build 0；zip **281,408,593 B** / APK **30,051,126 B**（见 §4）；打包版 exe 复测 **164/96/4/62、0 pageerror**（`win-packaged-counts-brandswap.json`）；打包版品牌字标抽检 PASS：DC-S1 样张（`_research/test-assets/ratio-study/4x3.jpg`）下 `stock-xiaomi-leica-91` / `stock-xiaomi-leica-111` 字标均为 **Panasonic**（非 xiaomi），leica-111 版式无散落（`_research/packaging/brandswap-check/`）；真机覆盖安装冷启动 **365 ms / LaunchState COLD**、数据保留（`fm-brandswap-cold.png`）；交付集（docx/PDF/SHA256.txt）全部重签发，`sha256sum -c` **4/4 OK**；leica-92 焦距行重力修复后绘制级文字对文字命中 4→1（余 1 为 xpan_1 厂商负行距，保真保留），95 套 stock 0 新增命中、仅 leica-92 的 rect 移动 | `_research/cleanup/lastoverlap/fix-report.md`、`_research/cleanup/close-out-report.md`、`_research/packaging/win-packaged-counts-brandswap.json`、`_research/packaging/brandswap-check/`、`_research/android-verify/fm-brandswap-cold.png` |
| **ROTATION** | **OPPO 侧边条带旋转** | **完成（已提交 `ff32b4b`）**：全库仅有的 3 套 band 级 `rotation:90` 样式（`personalize_film_2`、`personalize_film_realme_2`、`personalize_masterSign_3`）渲染修复并走真实导出路径复核（`_research/cleanup/rotation/verify/`，逐字节基线即本轮锚点默认导出的对照基线）；三套仍为 `approx`，**升级 `verified` 待真机样张** | `_research/cleanup/rotation/impl-report.md`、`rotation/verify/exports/` |
| **ANCHOR-PACKAGE** | **系统相机九宫格锚点（本轮）** | **完成（已提交 `3b48e89`）**：stock 卡片「版式参数」新增水印位置九宫格锚点（复用自定义水印面板同一 `AnchorGrid` 控件与标签）+ 第 10 项「厂商原版」为默认，整组可动条带（含旋转条带，无轴交换）刚性平移并限幅在画布内，X/Y 降级为水平/垂直微调；**真实 UI 证明**：哈苏文字·1 上 居中/右上/下中 三处锚点画布哈希均变化且文字组确实移动、无出界无重叠，切回厂商原版画布哈希与初始一致（`3442034355998acd…`）；胶片·2 旋转条带锚点抽检 PASS；真实导出路径 6 张（3 锚点 + 3 旋转默认），**4 张默认导出与 rotation-verify 基线逐字节一致**；门全绿：e2e 164/96/4、workflow 106/106、validate-ir 90/0、watermark-e2e `.anchor-grid button[title="右下"]` 选择器未破坏；打包版 exe 复测 164/96/4/62、0 pageerror、锚点功能实测（`anchor-packaged.json` verdict PASS）；zip **281,415,003 B** / APK **30,055,822 B**（见 §4）；真机覆盖安装冷启动 **764 ms / LaunchState COLD**、数据保留（`fm-anchor-cold.png`）；交付集全量重签发 `sha256sum -c` **4/4 OK** | `_research/cleanup/anchor-package/`（`report.md`、`ui/`、`exports/`、`tools/`）、`_research/packaging/anchor-packaged.json`、`_research/android-verify/fm-anchor-cold.png` |
| **BRAND-ALIGN / ALIGN-PACKAGE** | **品牌字标对齐修复 + 交付重打包（本轮）** | **完成（已提交 `e256965`）**：用户上报的 37 个含品牌槽系统样式「Panasonic/DC-S1 粘连偏高」缺陷修复——**对齐规则**：① 品牌字标按**墨迹高度**（非画布高度）缩放并计入厂商位图内边距（ink-height matching，vendor inkH/canvasH≈0.716 vs 替身≈0.983）；② 与相邻文字共享厂商位图的**光学中心线**（厂商内边距对称＝居中，与 `textBaseline:'middle'` 同线）；③ 与文字的间距由**厂商位图自身推导**（vendor-derived gap = IR 节点该侧 margin + 位图该侧 ink padding×box.w，下限 2px），增长在**墨迹边缘**限幅，不重叠保证与内在宽高比均不破坏。spacing 违例 **37/37→0**、墨迹中心偏差 **0.00px**、8 项目视全 PASS；门全绿：e2e 164/96/4、workflow 106/106、validate-ir 0 error；zip **281,417,923 B** / APK **30,057,358 B**（见 §4）；打包版 exe 复测 **164/96/4/62、0 pageerror** 且 `经典 · 19` 字标间距/对齐实测 PASS（`brandalign-packaged.png`）；真机覆盖安装冷启动 **381 ms / LaunchState COLD**、数据保留（`fm-brandalign-cold.png`）；评审页 `index-real.html` 刷新至 **v3 集**：40 卡指向 `exports-real-v3/`（37 张 CHANGED 徽标 + 3 对照），其余 124 卡沿用像素一致的 `exports-real-v2/`，v2 页备份为 `index-real.pre-v3.bak.html`；交付集全量重签发 `sha256sum -c` **4/4 OK** | `_research/cleanup/brandalign/fix-report.md`、`_research/cleanup/align-package-report.md`、`_research/packaging/brandalign-counts.json`、`_research/android-verify/fm-brandalign-cold.png`、`_research/review-164/exports-real-v3/` |

本页是**唯一当前状态页**；表格里的“完成”只表示对应产物存在且可复核，不表示 47 套或 stock 已验收。

**W1**：47/47 套 TemplateSpec 已生成；审计基线 **5 pass / 39 fail / 3 provisional**（基线冻结，用于批次划分，不随修复回写）。修复批次 **A=8 / B=30 / C=4**；**Batch A 已完成 8/8**：A1 复测 PASS 5/5（white-2、white-3、person-2、person-3、person-4，EXIF 宽度偏差 ≤0.23pp），A2 复测 PASS 3/3（card-2/3/4，照片窗口宽高比 1.2832/1.2686/1.2846，偏差 ≤0.003%；画布比例偏差 ≤0.16%；边距/页脚/logo ≤2pp），A 修复 39 个 fail 中的 8 个。A2 说明：4:3 输入下卡片画布宽度按设计收窄（保留裁切高度、采用参考图宽高比），长边仍 ≤ maxEdge。**Batch B1a 已完成 5/5**（glass-white-1/2/3、glass-card-4、glass-liquid）：从当前工作区独立重渲后逐像素复测，5/5 模板全过，各套门槛指标 19/19、19/19、19/19、17/17、8/8（合计 82 项、0 失败，无 gated 未过项），重新导出与工作区重渲逐张 SHA-256 一致。头条值：白卡画布比 0.6926（目标 0.692，偏差 ≤0.21 %rel）；行距 11.679 / 11.679 / 11.726 %S（旧 7.317）；card-4 参数行 27.102 %W、分隔线 50.000 %W（参考实际规则 50.083 %W）；液态玻璃 logo 中心 48.911 %W、日期左 8.292 %W、EXIF 左 57.414 %W。证据 `_research/original-47/specs-after/*-verified.json`、`exports/4x3-b1a-verify/`、`sheets-after/*-ref-ours-verify.png`。**Batch B1b 已完成 3/3**：glass-card PASS（卡片比 1.3333、宽 56.25 %W、右距 6.35 %W，纵向差为 4:3 画布下的几何强制）、glass-palette PASS（画布比 0.7498、面板 89.22 %W × 29.66 %H、2 行 × 3 列六色卡、直径 10.94 %W）、glass-pill-5 **PROVISIONAL**（结构已对齐卡片比 0.6903 / 88.70 %H，参考图置信度低，字标与等宽字宽为字体替换；后由 Batch C 复测 PASS）。**Batch B2a 已完成 6/6**：wm-classic、wm-black、wm-canon、wm-left、wm-wide、wm-right 逐一复渲复测 PASS；wm-black 为**全出血 overlay**（照片 100 %H、无底栏，标题/logo/品牌左列 + 右列 exif/规格/日期），wm-right 为**左右 1.32 %W + 上 0.88 %H 外框** + 8.38 %W 底栏；槽位偏差大多 ≤0.3pp（wm-black 的 exif/date 左位 -1.74/+1.59pp 仍在门槛内）。品牌字标差异（fixture Panasonic/LUMIX 字标 vs 参考 Canon/Leica 徽标）为**素材残余，不是结构失败**。**Batch B2b 已完成 3/3**：grad-1/grad-2 改用本地 `gradFooter`（满幅照片 + 12 % 照片宽白页脚，左侧 EXIF 品牌/参数、右侧 APK 内原版毕业贴纸；`graduation1.webp`/`graduation2.webp` 为 260×90 APK 素材逐字节副本），video-cover 改为 `bareCover` 纯 16:9 满幅封面（无叠加文字/logo/渐变）；三套独立复渲复测 PASS，画布比例偏差 0.23 %/0.23 %/0 %、照片比例 0.29 %/0.29 %/0 %、页脚偏差 0.05pp；参考图 GUANGYING 100 为 EXIF 文字（非图片素材），未伪造字标。**Batch B2 完成（B2a 6/6 + B2b 3/3）**。**Batch B3a 已完成 5/5**：movie-1/movie-2 恢复 2.34:1 剧照 + 底栏，照片 AR 2.3405（-0.14 %），底栏 11.71 / 7.89 %H，字幕分别位于底栏内 / 照片内（+8.19 / -4.71 %H），两套已差异化；ratio-1/2/3 改为 3:2 画布 + 模糊背景 + 参考比例 matte/正方形 inset，照片 AR 1.4850 / 1.7485 / 1.0000（偏差 ≤0.003 %），inset 偏差 ≤0.35pp（ratio-3 为 88.27 %S 居中 1:1，无任何文字）。**Batch B3b-1/2 已完成 5/5**：color-1/2/3 色卡行跨距 120.90 / 120.81 / 115.75 %S（参考 120.53 / 120.35 / 115.39；`paletteRowPct`/`paletteGapPct` 为 opt-in，其余 7 个调用方不变），color-3 色带 16.12 %S、照片 T/L/R matte 0（全出血），color-walk 时间顶 52.5 %header（参考 52.75，-0.25pp），color-reveal 16:9（1.7788，-0.19 %）、单帧彩色、纵向 3 点（x 49.96 %W、y 46.25/53.94/61.62 %H），5/5 PASS。**Batch B3b-3 已完成 4/4**：new-color-2 画布比 1.0098→0.8725（+0.07 %）、照片窗 AR 1.0811（参考 1.0810）、调色板由 5 圆点+hex 改为平铺 5 格色条（31.81 %W，参考 31.68）、最大位置偏差 1.34pp；ticket-1 卡宽 87.8 %W、照片 AR 1.7987（参考 1.7992）、最大偏差 0.38pp；ticket-2 卡 0.7627 W × 0.776 H、照片 AR 0.9610→1.0802（-0.09 %）、标题/日期/条码/序列号偏差 ≤1.06pp；simple-3 内边距拆为可分轴 opt-in 参数（`paramPadXPct`/`paramPadYPct`），右/上边缘偏差 ≤0.07pp；4/4 PASS。**Batch C 已完成 4/4**：simple-1/2/4 画布比 1.3336→1.3046（+0.008%）、角标偏差 ≤1.43pp（块高 +0.82pp、行距 −0.25pp）；glass-pill-5 画布比 1.4963（−0.053%）、卡片比 0.6903、槽位最大偏差 1.62pp（品牌 x），卡片相对文字比例未改；四套 `typecheck`/`build` PASS（按报告）。**按批次修复证据累计 47 pass / 0 fail / 0 provisional（共 47）= 原有 5 + Batch A 8 + B1a 5 + glass-card + glass-palette + B2a 6 + B2b 3 + B3a 5 + B3b-1/2 5 + B3b-3 4 + Batch C 4**。**W1 终审（FINAL-VERIFY-B）已完成**：47/47 终审 PASS（**13 套独立重测** + **34 套终审导出与批次逐字节一致**），0 provisional / 0 fail，无结构失败；47 张终审导出全部存在、非零、可解码，SHA-256 与 `final/manifest.json` 一致。证据 `_research/cleanup/FINAL-AUDIT.md`、`final-verify-b-audit47.json`。**用户目视签收仍待完成**，签收前不把 47 套标为最终验收完成、也不升级 `verified`；字体替换、品牌字标素材、画布/比例取值与 glass-pill-5 参考置信度四类残余仍在，不宣称 pixel-perfect。

**W2**（提交 `438c7a1`）：核心工作流 E2E **PASS 106/106 断言，failed 0，pageerror 0，consoleError 0**；覆盖双图批量、预设保存/重载/套用、自定义 PNG 水印、全部参数、文件名 token、JPG/PNG/WebP/TIFF、取消导出、预设 JSON 往返；预设 `outputDir` 可移植性缺陷（DEFECT-W2-1）已修复并有回归断言。

**W3**（提交 `3e4af60`）：最新厂商边框已同步入库。OPPO Gallery **17.10.7**（从 Find X9 Ultra 包 `com.coloros.gallery3d` 17.10.7 重导）生成 IR 现为 **43** 个 Gallery 样式：提取判定 25 same / 14 outdated / 5 missing，其中 **13 套仍上架的过时样式已按 17.10.7 配置同步**、第 14 项 `personalize_brand_3` 已干净退役（0 残留引用）、5 套 missing 即新增的 5 套 realme（`personalize_film_realme 1/2`、`realme_brand_imprint_inspirationPhoto 1/2/3`）；另有 **10 个 CameraPro 相机包样式 `unverifiable`**（第三方 `com.tlsu.opluscamerapro`，不在 ROM 内）。小米 **NeoHyper 17 Ultra**（`com.android.camera 6.7.000280.0` 素材 v36）**7 套 outdated 已同步**：`film 15`、`leica 2/91`、`standard 6/7/8/107`，其中 4 套 `standard` 由手写变体迁入生成 IR（**计数不变**，适配器取消 `standard` 家族过滤）。**vivo 39 套仍 blocked**——缺宿主布局与可信参考帧，不伪造。目录与预览已按最新包更新（见 §1）。**95 套 stock 全为 `approx`，0 `verified`**；小米 4:3 证据尚有 2/7 未判定项（`film 15` 画布比 1.2790、`leica 2` 1.0804），见 [`w3b-xiaomi-report.md`](../_research/cleanup/w3b-xiaomi-report.md) §6 残余。

**W4**（真机 PGEM10，Android 16，serial `8267fe3e`，1440×3168；装的是交付 APK，sha256 与 §4 一致，未重装）：冷启动 `am start -W` **TotalTime 300 ms / LaunchState COLD**；**2 张批量导出 PASS**（`walk_14_export_completion.png`「成功 2 张」+ `Pictures/FrameMark/` 落盘 `1000080711-玻璃 03.jpg` 与 `-玻璃 03 (1).jpg`，命名符合 `{name}-{template}`）；**自定义 PNG 水印上画布 PASS**（`walk_12_watermark_on.png`，右下角「Robert 阳」白色签名合成，切模板后仍保留＝独立图层）；**新 W3 样式渲染 PASS**（`近似 realme 胶片 · 1/2`、`近似 realme 品牌印记 · 1/2/3`、`近似 大师签名 · 1/2`、`近似 小米标准 · 6/7/107` 卡片均为真实版式缩略图、非空白兜底；`walk_19_final_state.png` 显示 `哈苏 · 4` 完整渲染）；**单张导出 → 分享 → Android 系统选择器 PASS**（`s3_completion.png` 完成卡含「分享」按钮、`s3_share.png` 拉起系统「分享 1 张图片」选择器，导出件 **`1000080681-哈苏 · 4.jpg`**）。上一轮走查上报的 **4 项「缺陷」全部裁定为测试脚本产物或设计行为**（Chromium/Android 无障碍层对滚动容器内节点上报未裁剪/越界 bounds、证据图误认为系统选择器、Android 图片选择器语义、示例图兜底 + 设计持久化），**P0 0 / P1 0**，详见 [`mobile-triage.md`](../_research/cleanup/mobile-triage.md)。口径说明：`walkthrough-report.md` 把 G4-3（分享）记为 BLOCKED、G4-4 记为 PARTIAL PASS，那是**上一会话**的结论；分享链路随后由 s2/s3 会话以单张导出路径完成验证（`s3_completion.png` → `s3_share.png`），新 W3 样式的渲染证据同时含卡片缩略图与 `哈苏 · 4` 实际进入编辑器两路。

**W5**（打包终验）：见 §4 交付基线与 [`FINAL-ACCEPTANCE.md`](../_research/cleanup/FINAL-ACCEPTANCE.md)。

---

## 1. 目录计数（当前）

| 项目 | 当前值 | 说明 |
|---|---:|---|
| 模板库卡片 | **164** | 手写 **68** + 系统相机 **96** |
| 手写变体 | **68** | `V(...)`；其中 47 个为原版复刻，其余为自研/精选 |
| 系统相机 stock | **96** | 分解见下 |
| 原版参考图 | **47** | `src/renderer/src/assets/previews/` 根目录（45 webp + 1 png + 1 jpg） |
| 手写渲染预览 | **69** | `assets/previews/rendered/*.webp`；= `build-variant-previews.cjs` 的 `expectedVariants` 69（68 手写 + 1 `oppo-polaroid`）。W3 迁移后 4 张孤立 `xiaomi-standard-*.webp` 已清理 |
| 系统相机预览 | **95** | `assets/previews/system/*.webp`；5 套无厂商原版预览（见 §2.2） |
| stock 样式（运行时 `__fmStock.list`） | **95** | OPPO 52 + 小米 40 + vivo 3；全部 `approx`，**0 `verified`** |
| 生成 stock 样式 | **90** | 43 OPPO Gallery + 9 CameraPro + 38 小米 |
| 生成 stock 变体 | **90** | 与生成样式一一对应 |
| 品牌字标 | **62 可见 / 63 定义** | Samyang 无可靠独立字标，隐藏不列出 |
| blocked | **4 组 / 42 个样式** | 小米 Leica 5/88/89 + vivo 全部（39 套），见 §2.3 |

**系统相机 96 套分解**

- **OPPO 53** = Gallery 43 + CameraPro 9 + Polaroid PLD 1（其中 52 个为 stock 样式条目，Polaroid 为独立变体，预览走 `previews/rendered/oppo-polaroid.webp`）
- **小米 40** = 生成 38（含已迁入 IR 的标准 6/7/8/107）+ 侧栏 classic 22/99 手工 2
- **vivo 3** = Zeiss 上框 / 上下框 / 内嵌

（53 + 40 + 3 = 96；stock 样式 52 + 40 + 3 = 95，差额是 Polaroid 只有变体没有独立 stock 样式。）

---

## 2. 保真度状态

### 2.1 原版 47 套（手写原版复刻）

- 47 张原版参考图 **1:1 映射到 68 套手写变体中的 47 套**（`variants.ts` 中带原版 `preview` 的那一批）；其余 21 套为自研/精选，不在 L2 范围。
- 早期只有 **13 套**有 manifest 矩形：纯白 1–4、卡片 1–4、人物 1–5（来自 [HANDOFF](../HANDOFF.md) §6.2）；W1 已对 47 套逐套重新测量。
- **47/47 套 TemplateSpec 已生成**（`_research/original-47/specs/`）；审计基线判定 **5 pass / 39 fail / 3 provisional**，修复批次 A=8 / B=30 / C=4。Batch A 已完成 8/8：A1 修复 white-2/3、person-2/3/4，A2 修复 card-2/3/4（照片窗口宽高比 1.2832/1.2686/1.2846，均复测 PASS，证据 `_research/original-47/specs-after/`）；Batch B1a 已完成 5/5：glass-white-1/2/3、glass-card-4、glass-liquid 独立复渲复测全部 PASS（各套门槛 19/19、19/19、19/19、17/17、8/8，合计 82 项 0 失败，导出与重渲 SHA-256 一致）。Batch B1b 已完成 3/3：glass-card 与 glass-palette 复测 PASS（glass-palette 画布比 0.7498、面板 89.22 %W × 29.66 %H、2×3 色卡），glass-pill-5 结构对齐但参考置信度低，判 PROVISIONAL（后由 Batch C 复测 PASS）。Batch B2a 已完成 6/6：wm-classic、wm-black、wm-canon、wm-left、wm-wide、wm-right 独立复渲复测 PASS（wm-black 全出血 overlay、无底栏；wm-right 左右/上外框 + 8.38 %W 底栏；槽位偏差大多 ≤0.3pp）；品牌字标差异（fixture Panasonic/LUMIX vs 参考 Canon/Leica）为素材残余，不是结构失败。Batch B2b 已完成 3/3：grad-1/grad-2 复测 PASS（页脚 12 % 照片宽、贴纸右对齐且尺寸/位置偏差 ≤0.32pp；品牌差异仍为 EXIF 文字残余），video-cover PASS（16:9 满幅、无叠加文字/logo/渐变）；`graduation1/2.webp` 为 APK 原版贴纸逐字节副本。Batch B3a 已完成 5/5：movie-1/movie-2 恢复 2.34:1 剧照与差异化底栏/字幕（底栏 11.71 / 7.89 %H，字幕在底栏内 / 照片内），ratio-1/ratio-2/ratio-3 改为 3:2 模糊背景 + 参考比例 matte/正方形 inset（照片 AR 1.4850 / 1.7485 / 1.0000，ratio-3 无文字），全部独立复渲复测 PASS。Batch B3b-1/2 已完成 5/5：color-1/2/3 色卡行铺满至 120.90 / 120.81 / 115.75 %S（参考 120.53 / 120.35 / 115.39；铺开/间距参数为 opt-in，不影响其他模板），color-3 色带 16.12 %S 且照片 T/L/R 全出血（matte 0），color-walk 时间锚点上移至 52.5 %header（-0.25pp），color-reveal 改为 16:9 单帧彩色 + 纵向 3 点取色器（1.7788，-0.19 %），全部 PASS。Batch B3b-3 已完成 4/4：new-color-2 画布比 1.0098→0.8725、照片窗 AR 1.0811（参考 1.0810）、调色板改为平铺 5 格色条（31.81 %W，参考 31.68、原 14.6）、最大位置偏差 1.34pp；ticket-1 卡 87.8 %W、照片 AR 1.7987（参考 1.7992）、最大偏差 0.38pp；ticket-2 卡 0.7627 W × 0.776 H、照片 AR 0.9610→1.0802（-0.09 %）、stub 锚点按参考重导、槽位偏差 ≤1.06pp；simple-3 拆出可分轴内边距（`paramPadXPct`/`paramPadYPct`，opt-in，其余变体不变），右/上边缘偏差 ≤0.07pp；4 套独立复渲复测 PASS。Batch C 已完成 4/4：simple-1/2/4 以变体级 `canvasAspect` 1.3045 把画布裁到参考比例（实测 1.3046，+0.008%），照片恢复全出血、锚点偏差 ≤1.43pp；glass-pill-5 以 `canvasAspect` 1.4971 得到 1.4963（−0.053%），卡片比 0.6903、右距 13.18 %W，品牌/EXIF 槽位偏差 ≤1.62pp；四套 `typecheck`/`build` PASS（按报告），共享模板邻居 simple-3 与 glass-card 重渲后与各自上一版逐字节一致。**W1 终审已执行**：47/47 终审 PASS（13 套独立重测 + 34 套批次逐字节一致），0 provisional / 0 fail，无结构失败。**用户目视签收前不把任何一套标为 `verified`**，也不宣称 pixel-perfect。
- 因此 47 套**仍都不能称为 `verified`**：终审 47/47 PASS 只是模板级几何/结构结论（ticket-2 终审标题 295 px、19/19 门槛通过），**用户目视签收尚未完成**，签收前不给 `verified`、不把整体验收标为完成。
- **字体残余**：EXIF 字形仍以 Chakra Petch 水平缩放近似（`exifScaleX` 1.09–1.49），不是原版的真实扩展字面（跨批次残余，未修复）；movie-1/2 的中文字面以 YouShe（`FONT_CN`）代替参考 CN sans；`FONT_TECH` 未预加载导致冷启动首渲回退 OplusSans 的问题已由 A2 修复（`ensureFonts()` 预加载 Chakra Petch 600 常规 + 斜体）。
- **其余残余**：simple-1/2/4 的参考字为宽斜体科技字面（现用 Bebas Neue 近似，大写高 2.17 vs 3.75 %H）；品牌字标为审计 fixture 驱动（EXIF 为 Panasonic DC-S1，自动渲染 LUMIX/Panasonic，参考图为 Canon/Leica/SONY/Nikon），属素材残余而非模板缺陷；glass-pill-5 为 SONY serif 字标 vs LUMIX autoLogo，仅槽位锚点可比，品牌 ink 宽不可比、参考置信度低；`canvasAspect` 是按各参考图取值的**变体级**参数（1.3045 / 1.4971），4:3 审计导出与参考原生比例的差异属画布/比例取值残余，不是全局画布策略，其它模板与多比例导出行为未变。
- 几何输入是 `_research/original-47/geometry-diff.json`，由 `_research/original-47/manifest.json` 驱动（47 行；早期 13 已知 / 34 未测量，W1 已用 47 套 TemplateSpec 补测）。旧的 `_research/preview-analysis/geometry-diff.json`、`_research/pair-sheet/` 只作历史证据，不等于 47 套验收完成。

### 2.2 系统相机 96 套

- 较早一批完成过 4:3 结构级核对（其中 85 套有厂商原版预览、5 套 config-only）；小米 classic 22/99 按小米 17 Ultra config 接入；W3 已把 OPPO 17.10.7 与小米 NeoHyper 17U 的最新包重导进 IR，**新迁入的 4 套小米标准与 3 套刷新样式尚未重跑同等级 4:3 结构核对**。
- **90 个生成 stock 样式（+ 手工覆盖样式，运行时共 95 套）全部为 `approx`，0 `verified`。**
- 已知近似来源：字体替换、图标细节、透明/纹理/渐变的纯色兜底、精确 dp→px 边距；GR5 参数边栏字号/行距偏大。`validate-ir` 的 37 条 warning 全部属这类既有主题近似（`transparent theme uses fallback` / `texture theme not painted as texture`），0 error。
- **5 套无厂商原版预览**（Polaroid PLD、小米标准 6/7/8/107），只能按宿主 config 与素材核对，不能升级 `verified`。

### 2.3 blocked（4 组 / 42 个样式）

| 组 | 原因 | 可导出 |
|---|---|---|
| 小米 Leica 5 | outer-border 侧栏为绝对布局，缺可用总宽度 | 否 |
| 小米 Leica 88 | 底部 218dp 容器 + 照片内叠加，根结构不匹配 | 否 |
| 小米 Leica 89 | 侧栏与照片内叠加混合，缺可用总宽度 | 否 |
| vivo 全部（39 套） | 已接 3 套 Zeiss；其余缺宿主布局/可信参考帧 | 否 |

blocked 卡片在目录内可见、写明原因、不可点击导出；没有依据时不伪造布局、滤镜数值或字标。

> **更正说明（2026-10-31，DOC-CORRECT）**：上表 Leica 5/88/89 的原因为准。早期 HANDOFF/QUALITY 曾把这三套写成「需旋转文字」，经 `_research/cleanup/orient-audit.md` 核证，三套小米 config 均无任何 `rotation`/角度字段，「竖排」只是 LinearLayout 垂直堆叠（IR 的 `column` 已支持），真实障碍是绝对侧栏带宽与照片内叠加布局超出 IR 表达。全库真正使用 band 级 `rotation:90` 的仅 3 套 OPPO 样式（`personalize_film_2`、`personalize_film_realme_2`、`personalize_masterSign_3`），它们未被 blocked，旋转渲染作为独立特性单独推进。

---

## 3. 平台与验证状态

| 平台 / 检查 | 状态 | 备注 |
|---|---|---|
| `typecheck` / `build` | 通过 | ANCHOR-PACKAGE 轮（HEAD `3b48e89`）：`npm run typecheck` 与 `npm run build` exit 0；e2e **164/96/4、0 error**、workflow **106/106**、validate-ir **90 样式 / 0 error / 37 既有 warning**、watermark-e2e（含 `.anchor-grid button[title="右下"]` 断言）exit 0；日志 `_research/cleanup/anchor-package/tools/{e2e,workflow,package-win}.log` |
| `npm run test:e2e` | 通过 | W3 收口轮复跑：total **164** / system **96** / blocked **4** / errors 0，1 张导出成功 |
| `npm run test:workflow` | 通过 | **106/106 断言 / failed 0 / pageError 0 / consoleError 0**（W2 核心工作流；W3 审收轮 ASSESS-W3 复跑仍 106/106；W1 终审轮 FINAL-VERIFY-B 复跑仍 106/106） |
| `npm run validate:ir` | 通过 | W3 收口轮复跑：**90** 生成样式 / **90** 生成变体 / **95** 系统预览；0 error；37 条既有主题近似 warning |
| Windows 桌面（W5 打包版） | 通过 | `release/FrameMark-win32-x64/FrameMark.exe` 冷启动实测 `.tpl-card` **164**、系统相机 **96**、blocked **4**、品牌卡 **62**、静态 canvas **0**、**pageerror 0**；证据 `_research/packaging/win-packaged-counts.json`；**REPACKAGE 轮对最终交付包复测同口径全过**：证据 `_research/packaging/win-packaged-counts-fixed.json`（verdict PASS） |
| Android 真机（W4 走查） | **通过（走查四项全 PASS）** | PGEM10 / Android 16，交付 APK（sha256 与 §4 一致）；冷启动 **300 ms**；2 张批量导出、自定义水印上画布、新 W3 样式 `近似` 渲染、单张导出 → 分享 → 系统选择器均 PASS；上报的 4 项缺陷裁定为脚本产物/设计行为（P0 0 / P1 0）；证据 `_research/android-walkthrough/`。**欠项**：品牌库**弹窗本体**（点开后的 62 卡网格）仍未真机目视，本轮只确认入口与「从 62 个品牌中选择」文案在设备 DOM 中呈现。**REPACKAGE 轮**：最终 APK `adb install -r` 覆盖安装（未清数据）→ force-stop → `am start -W` **TotalTime 416 ms / LaunchState COLD / Status ok**，截图编辑器正常渲染、用户水印「Robert 阳」仍在＝数据保留；证据 `_research/android-verify/fm-fixed-cold.png`。**ANCHOR-PACKAGE 轮**：同法复装新 APK，冷启动 **764 ms / LaunchState COLD**，截图为编辑器正常渲染（哈苏·4 卡片 + 参数面板）、数据保留；证据 `_research/android-verify/fm-anchor-cold.png` |
| 多比例 | 历史全量目检通过 | 上一轮 4:3 + 3:2/2:3/16:9/1:1/4:5/3:4/9:16 未见拉伸/裁切/重叠；后续新增模式未重跑同等全量 |

---

## 4. 交付基线（ALIGN-PACKAGE 重打包：**2026-10-25 23:22**，源码 = HEAD `e256965`）

| 文件 | 字节数 | SHA-256 | 校验 |
|---|---:|---|---|
| `交付/FrameMark-Windows-x64.zip` | **281,417,923** | `67797acbb9d8ecc7eb922e0bd298bc4d0612cf70e39c1a1f6fcd2de55ebedc48` | OK（ALIGN-PACKAGE 新包，打包版 exe 复测 164/96/4/62、0 pageerror，`经典 · 19` 品牌字标间距/对齐实测 PASS：`_research/packaging/brandalign-packaged.png`、`brandalign-counts.json`） |
| `交付/FrameMark-Android-v1.0.0.apk` | **30,057,358** | `43515a503b9204751dd10823380e15fb7211fc79cac0ec09bd1d373f3b4cd26a` | OK（versionName 1.0.0 / versionCode 1 / minSdk 29 / targetSdk 34；capacitor copy + `assembleRelease` 全量重跑（6 executed，含 mergeReleaseAssets/packageRelease，APK 内 `index-BxPGv9Ed.js` 与本轮 build 一致），gradle 内置签名 `CN=FrameMark, OU=Personal, O=FrameMark`，证书 SHA-256 `a3f8e1d9b22b92cc687c85cc4648f92678a01a50d99fe249cdedd2a7422c873f`；真机覆盖安装冷启动 381 ms COLD PASS） |
| `交付/FrameMark-使用与验收说明.docx` | **882,222** | `79f197645f0d8c02e5b662b3737d03f8145e0eb53c8e48e71d4bde23481cd9a7` | ALIGN-PACKAGE 重签发（`scripts/make-delivery-docx.cjs`，运行时内嵌上表新哈希） |
| `交付/FrameMark-使用与验收说明.pdf` | **1,168,079** | `63a1cf04de922c802951d15ac9e14928ce5664d688a3a9037055b18bbda7ee0f` | ALIGN-PACKAGE 重签发（Word COM `SaveAs` 格式 17，先移走旧 PDF 再导出，`%PDF-1.7` 头校验通过） |

被取代的上一代哈希：zip `49e9a2eb…`、APK `1d6c5709…`、docx `43a0c49f…`、PDF `e5542ccd…`（ANCHOR-PACKAGE 22:08 基线）；更早：zip `39b1918e…`、APK `6a660041…`、docx `f52ae514…`、PDF `402d8b3b…`（CLOSE-OUT 基线）、zip `d10b60ee…`、APK `3ac92363…`、docx `fa11ef20…`、PDF `ed81743a…`（REPACKAGE 17:24 基线）、zip `164ff8ee…`、APK `4fa4c260…`（W5 11:54 基线）。

**`交付/SHA256.txt` 已随本轮重签发**（同格式四行：zip/APK/docx/PDF），`cd 交付 && sha256sum -c SHA256.txt` → **4/4 OK**。任何代码、素材或文档数字改动都应重新打包并重算本表；只做审计不改代码时不重打包。

---

## 5. 当前状态与残余（W5 收口）

**当前状态：待用户目视签字。** W0–W5、REPACKAGE、CLOSE-OUT、ROTATION、ANCHOR-PACKAGE 与 BRAND-ALIGN/ALIGN-PACKAGE 均已收口并提交（源码 HEAD `e256965`，本轮交付集已按其全量重签发）；**渲染修复（`e256965`）：37 个含品牌槽样式的品牌字标按厂商槽规则对齐——按墨迹高度缩放（计入厂商位图内边距）、与型号文字共享光学中心线、间距由厂商位图自身推导并在墨迹边缘限幅——spacing 违例 37/37→0、墨迹中心偏差 0.00px，用户上报的「Panasonic/DC-S1 粘连」缺陷消除**；**行为变更（`3b48e89`）：系统相机卡片的水印位置支持九宫格锚点（含「厂商原版」默认位），默认渲染与锚点上线前逐字节一致**；**行为变更（`549a653`）：厂商水印与原版模板的品牌字标不再固定跟随模板厂商，而是随照片 EXIF 品牌（或 Inspector 中手选的品牌 LOGO）自动切换**；47 套终审 47/47 PASS、workflow 106/106、e2e 164/96/4、validate:ir 90/90/95 0 error、真机走查四项 PASS、保真重导出（`_research/review-164/exports-real/`）0 项真实变形/重叠缺陷、绘制级文字对文字重叠 4→1（余 1 为厂商负行距保真项）、交付包已重打并复算哈希（`sha256sum -c` 4/4 OK）。**以下残余全部如实列出，不隐藏、不粉饰**；签字前不把任何模板标为 `verified`，也不宣称 pixel-perfect。

### 5.1 待用户签字（唯一未闭合项）

1. **原版 47 套目视签字**：终审 47/47 PASS 只是模板级几何/结构结论（13 套独立重测 + 34 套与批次逐字节一致，0 provisional / 0 fail，门槛 ≤2 %S / ≤2 pp；ticket-2 终审标题 295 px、19/19 门槛通过）。**用户目视签收尚未执行**。
2. **厂商线目视签字**：95 套 stock 样式（OPPO 52 + 小米 40 + vivo 3）全为 `approx`、**0 `verified`**；升级 `verified` 需要原版参考帧 + 逐项目检，W4 真机走查不构成升级依据。

### 5.2 保真度残余（已知、已量、未修）

3. **字体替换**：EXIF 字形仍以 **Chakra Petch 水平缩放**近似参考图的宽/扁科技字面（`exifScaleX` 1.09–1.49：person-3 1.09、person-2 1.285、person-4 1.33、white-2 1.47、white-3 1.49），不是真实扩展字面；simple-1/2/3/4 用 **Bebas Neue** 顶替宽斜体科技字面（大写高 3.75 vs 参考 2.17–2.23 %H）；movie-1/2 用 **YouShe（FONT_CN）** 顶替参考中文无衬线；ticket-2 日期与 color-walk 时间用 JetBrains Mono、new-color-2 用 Inter。simple 族与 movie 族是可见字面差最大的一组。
4. **品牌字标素材差异**：审计 EXIF 夹具是 **Panasonic DC-S1**，`autoLogo` 因此渲染 LUMIX/Panasonic，而参考图是 Canon / Leica / SONY / Nikon / FUJIFILM；受影响 wm-classic/black/canon/left/wide/right、white-2、glass-white-1..3、glass-card-4、glass-pill-5、glass-liquid、grad-1/2、person-5、ratio-1/2、new-color-2、card-1..4。属**测试夹具内容**而非模板缺陷，品牌墨宽对比因此不具意义。**CLOSE-OUT 起字标随照片品牌/Inspector 手选切换（`549a653`），本条差异在参考图与样张品牌一致时才会消失**。
5. **画布/比例取值**：审计统一用 4:3 输入，参考图是各自原生比例；固定比例原版（movie 2.34:1、ratio 3:2、glass-card、color-3、wm 族等）的画布比因此有 -23.94 %…+4.7 % 的差，门槛改用 %canvas / %S 保证横向几何可比，纵向差已逐套标注为不可比。`canvasAspect` 是**变体级**参数（simple-1/2/4 = 1.3045、glass-pill-5 = 1.4971），不是全局画布策略。
6. **glass-pill-5 参考置信度低**：参考品牌是 SONY 衬线字标、我方是 LUMIX autoLogo，品牌墨宽（8.60 vs 16.99 %W）本质不可比，只有槽位锚点可比；品牌 x 偏 +1.74 pp、门槛余量 0.26 pp。几何置信度高、字标置信度低。
7. **4 组 blocked / 42 个样式**：小米 Leica 5 / 88 / 89（侧栏绝对布局、底部 218dp 容器 + 照片内叠加、混合叠加）+ **vivo 全部 39 套**（缺宿主布局与可信参考帧）。卡片在目录内可见、写明原因、不可导出；**不伪造**布局、滤镜值或字标。
8. **10 个 OPPO CameraPro 样式 `unverifiable`**：属第三方包 `com.tlsu.opluscamerapro`，不在 Find X9 Ultra ROM 内，本轮无从校验（保持现状、`approx`）。
9. **5 套无厂商原版预览**（Polaroid PLD、小米标准 6/7/8/107）只能按宿主 config 与素材核对，结构级已核，**不能升级 `verified`**。
9a. **`hassel_style_xpan_1` 负行距保真项**：绘制级文字对文字全库唯一残余命中（px=338.09），源自厂商配置 `topMargin:-3` 的紧凑行距；字形墨迹不碰撞、文字可读，**按保真原则设计性保留**，不再"修复"成失真版式（证据 `_research/cleanup/lastoverlap/fix-report.md`）。
9b. **品牌字标 letterbox 约 32%**：字标槽位向右生长直至撞上模型文字，字标可见墨宽通常只占槽位约 68%（约 32% 为留白），属槽位策略残余，非渲染缺陷。
9c. **samyang 无字标文件**：品牌库 62 卡中 samyang 缺可靠字标素材文件，画布上保持隐藏（不伪造字标）。
9d. **3 套旋转条带样式仍 `approx`**：`personalize_film_2`、`personalize_film_realme_2`、`personalize_masterSign_3` 的 `rotation:90` 侧边条带已由 `ff32b4b` 修复渲染并经真实导出路径 + 几何/绘制矩形双重复核（无轴交换、锚点平移为同一刚性 delta），但**升级 `verified` 仍待真机样张对照**；`xpan_1` 厂商负行距按保真原则设计性保留（见 9a）；其余 95 套 stock 全 `approx`、**0 `verified`**；vivo 39 套 blocked 不变。

### 5.3 代码/证据卫生（已记录，本轮不动源码）

10. **`xiaomi-film:15` 双样式源**：同时存在于 `generated/xiaomiStyles.ts`（适配器重导，`referenceWidth` 1080）与 `core/system/styles.ts` 的手工 `XIAOMI_FILM_15` 覆盖，手工项生效。本轮两处已同步、值一致，但属第二事实源：再跑一次小米适配器会静默分叉（OPPO 侧 HASSEL/LUMO/GR5/OPPO_FILM_2/OPPO_MASTERSIGN_3/OPPO_RETRO_5 为同类既有模式）。
11. **`xiaomiStandard` 基础模板已死**：`core/templates.ts:3843` 定义、`:4277` 注册、`types.ts:13` 联合类型仍在，但**已无任何变体引用**、无测试断言；留待后续波次清理。
12. **小米 4:3 证据 2/7 未判定**：`xiaomi-film:15` 画布比 **1.2790**、`xiaomi-leica:2` **1.0804**，超出 `after/manifest.json` 自述 `ratio: "4:3"` 的 1.3333 ±0.004；驱动脚本只 `console.log` 不断言，其自身 "4:3 check" 实为 false。侧栏（side-band）样式几何上可能合理，但**尚无裁定**。
13. **交付文档链已闭合（CLOSE-OUT）**：`交付/SHA256.txt`、docx、PDF 已随最终包全部重签发并 `sha256sum -c` 4/4 OK（见 §4）。历史：W5 轮任务边界只允许覆盖 zip/APK，当时未重生 docx/PDF。
14. **Android 品牌库弹窗本体未目视**：W4 走查未覆盖点开后的 62 卡网格；设备侧只确认了入口与「自动匹配 EXIF 品牌，或从 62 个品牌中选择」文案在 DOM 中呈现。桌面打包版品牌库 62 卡已实测 0 pageerror。
15. **移动端无障碍探针协议**（非应用缺陷）：Chromium/Android 无障碍层对 `overflow:auto` 容器内未裁剪/视口外节点会上报越界或 `[0,0][0,0]` bounds，真机 agent 必须「滚动后重新 dump + 截图核对后再点」，且 `adb input text` 打不了中文；已写入 [`mobile-triage.md`](../_research/cleanup/mobile-triage.md) 建议，作为后续走查规程。
16. **授权 / 公开发布审计不做**：按 2026-10-25 范围冻结，项目只按个人使用维护，**本轮未做任何 public-release / licensing 工作**（这是用户的范围决定，不是待办）。

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
| [`HANDOFF.md`](../HANDOFF.md) | 协作约定、最短恢复路径、**§7 历史工作日志**、**§14 W0–W5 收口记录** | 仅历史区保留旧值 |

---

## 8. 证据索引

- 原版几何：`_research/preview-analysis/geometry-diff.json`、`_research/pair-sheet/`
- **W1 47 套终审**：`_research/cleanup/FINAL-AUDIT.md`、`final-verify-b-audit47.json`（47 行机器可读）、`final-verify-b-*.log`；导出与规格 `_research/original-47/final/exports/`、`final/manifest.json`、`specs-final/`、`specs-after/`、`sheets-after/`；批次过程 `_research/cleanup/w1-triage.md`、`w1b-*-report.md`、`spec13-report.md`、`spec34a-report.md`、`spec34b-report.md`、`b1a-verify-report.md`、`ticket2-fix-report.md`、`final-export-report.md`
- **W2 工作流**：`_research/cleanup/w2-workflow-report.md`、`w2-fix-report.md`、`w2-fix-workflow-run.log`；导出 `_research/e2e-workflow/`
- **W3 厂商**：`_research/cleanup/w3-assess.md`、`w3b-oppo-data-report.md`、`w3b-oppo-ui-report.md`、`w3b-xiaomi-report.md`、`oppo-extract-report.md`、`vendor-inventory-report.md`；素材与 after 证据 `_research/vendor-latest/`、`_research/roms/neohyper/`
- **W4 真机**：`_research/android-walkthrough/`（`walkthrough-report.md` + `walk_*.png` + `s2_*`/`s3_*` 分享证据 + `*.xml` uiautomator 转储）、`_research/cleanup/mobile-triage.md`
- **W5 打包**：`_research/cleanup/packaging-report.md`、`_research/packaging/`（`typecheck.log`、`build.log`、`package-win.log`、`cap-copy.log`、`gradle-assembleRelease.log`、`win-packaged-counts.json`、打包版截图）
- **REPACKAGE 重打包**：`_research/cleanup/repackage-report.md`、`_research/packaging/`（`package-win-fixed.log`、`cap-copy-fixed.log`、`gradle-assembleRelease-fixed.log`、`win-packaged-counts-fixed.json`、`verify-packaged-fixed.mjs`、`win-packaged-fixed-*.png`）、`_research/android-verify/fm-fixed-cold.png`、`_research/cleanup/_repackage_build.log`
- **保真重导出评审集（当前）**：`_research/review-164/exports-real/`（164 卡走真实导出路径，配 `manifest-real.json`/`index-real.html`/`DELTA.md`；本轮修复复核以该集为准，取代旧 `exports/` 预览口径集）。**BRAND-ALIGN 轮起 `index-real.html` 已刷新至 v3 代际**：40 卡（37 品牌槽样式 + 3 对照）指向 `exports-real-v3/`（37 张右上角 CHANGED 徽标，逐像素比对 v2→v3 非零），其余 124 卡沿用像素一致的 `exports-real-v2/`，`manifest-real.json` buildHead=`e256965`；旧集 `exports-real/`、`exports-real-v2/` 与备份页 `index-real.pre-v3.bak.html` 均保留在盘上供对照；生成器 `tools-real/build-page-v3.cjs`，比对明细 `tools-real/_diff-v3.json`
- **总验收**：`_research/cleanup/FINAL-ACCEPTANCE.md`
- 系统相机结构核对：`_research/stock-fidelity/report.md`、`_research/stock-fidelity-vendor/sheet-01..18.jpg`
- 4:3 全量目检：`_research/pc-audit-4x3/`、`_research/pc-audit-originals/`
- 多比例：`_research/ratio-audit/`
- Android（上一轮）：`_research/android-verify/`
- 交付校验：`交付/SHA256.txt`（REPACKAGE 轮重签发，`sha256sum -c` 4/4 OK，见 §4）
- 清理基线：`_research/cleanup/baseline-report.md`、`baseline-hygiene.md`、`_research/snapshots/`
