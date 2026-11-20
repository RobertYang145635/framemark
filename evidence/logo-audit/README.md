# FrameMark 品牌字标资产库审计（只读）

审计对象：`ui/composeApp/src/commonMain/composeResources/files/` 下的品牌字标位图与 `data/logos.json` 目录。
本目录只由脚本生成，**没有改动任何源码、资产或版本号**，也没有构建、没有连设备。

---

## 0. 一句话结论

102 个品牌里 **100 个两侧都有可用字标**，**1 个只有浅底**（meyeroptik）、**1 个只有深底**（godox），**0 个两侧都没有**；
但 **41 个品牌没有任何 `files/logos` 基线位图**，只能靠目录里 `ver:` 版本 cut 渲染。
166 枚基线 PNG 里，**105 枚**在「约 200 px 绘制宽度 + 无 mipmap 的 2-tap 双线性」模型下 PSNR < 26 dB（30 枚 < 22 dB）；
而题面指定的 `Image.BILINEAR` 一枚都测不出来（Pillow 的 BILINEAR 会随缩放比放大核，自带面积预滤波），见 §4.3。
`autoCutKey()` 在 8 个品牌上会选中在默认白底上 < 3.0 的字标，其中 **huawei 选中 `huawei_white`（白底 1.00，等于看不见）**，根因是一个拼写错误（`huawe_black.png`）。

---

## 1. 数字摘要

| 指标 | 数值 | 来源 |
| --- | --- | --- |
| 品牌 | 102 | `logos.json brands[]` |
| 目录 cut | 474（brand-key 113 + `ver:` 361） | `cuts[]` |
| 基线位图（真正被引擎按品牌画的） | 166 PNG | `files/logos/*.png` |
| 可渲染资产（并集，去重） | 527 | 166 + 361 |
| 有基线 PNG 的品牌 | 61 | — |
| **无基线 PNG 的品牌** | **41** | 只能靠 `ver:` cut |
| 两侧都可用的品牌 | **100** | coverage.csv |
| 只有浅底 / 只有深底 / 两侧都没有 | **1 / 1 / 0** | meyeroptik / godox / — |
| autoCutKey 在白底 < 3.0 的品牌 | **8** | huawei 1.00 最低 |
| 毛刺高风险（2-tap，< 26 dB） | **105 / 166**（基线 PNG）·193 / 474（全部 cut） | §4.3 |
| 毛刺高风险（PIL BILINEAR，< 26 dB） | **0 / 166** | §4.3 |
| 最细笔画 < 1 px @200 px | 1（`logo_empty` 占位图）·< 2 px 9 枚·< 3 px 37 枚 | cuts.csv |
| 半透明边 RGB 距离 > 40 | 17 枚（其中单色标记真毛边 **2** 枚） | §4.4 |
| 目录 declared ≠ actual | **121 / 474**（actual 与真实像素 474/474 一致） | §4.5 |
| 极性元数据与像素矛盾 | **25 / 474** | §4.6 |

---

## 2. 审计对象：两条真正在跑的绘制路径

| | 路径 A（EXIF 品牌替代） | 路径 B（Inspector 选 cut） |
| --- | --- | --- |
| 入口 | `engine/e2b/src/brand.rs` `choose_brand_slot()` | `LogoBrandCatalog.readCutBytes(assetKey)` |
| 位图 | `files/logos/<stem>.png`（166） | `files/logov/<packaged>.webp`（474）→ stage 成 PNG（`fmlogo_<sha256>.png`） |
| 选谁 | 品牌键前缀 + `_black`/`_white` 后缀按色调排序 | 用户/自动匹配选中的那一枚 `assetKey` |
| 备注 | `brand.rs:151` 只认 `{key}_` 前缀的 stem，所以 `hassel_*`、`lumix_*`、`huawe_*` 对 `hasu`/`panasionic`/`huawei` 是**不可见**的 | `ver:` 键也能渲染，因为 webp 会被 stage 成引擎 stem |

`coverage.csv` 的覆盖结论取**并集**：品牌只要有一条能画出来的 cut 在该底色上 ≥ 3.0 就算「可用」。`cuts.csv` 只列 166 枚基线 PNG（题面所指的「真正被渲染的那批」）；`cuts-catalog-474.csv` 列全部目录 cut（webp），两表的度量口径完全一致。

> 166 枚基线里只有 **113 枚**能对上目录的 brand-key cut（其余 53 枚如 `apple`、`canon`、`sony_black`、`hassel_*`、`hand-*` 没有对应的 `cuts[]` 条目），所以 `cuts.csv` 里这 53 行的 `surface`/`ink` 为空、`问题标签` 带「目录无此cut」。它们仍然会被路径 A 的 stem 匹配画出来，因此照样计入统计。

---

## 3. 判定方法

### 3.1 真实对比度（Q2）

* 墨迹 = `alpha ≥ 250` 的像素；取它们 RGB 的算术平均作为该 cut 的「墨迹颜色」（题面口径）。
* WCAG 2.x：`L = 0.2126R' + 0.7152G' + 0.0722B'`，`c' = c/12.92` 或 `((c+0.055)/1.055)^2.4`；`contrast = (Lmax+0.05)/(Lmin+0.05)`。
* 六种底板：浅 `#FFFFFF` / `#F7F5F0` / `#F4EFE6` / `#DCD8D0`，深 `#1C1D1F` / `#000000`（= `EngineArgs.THEMES` 的 white/paper/cream/stone/ink/black）。
* 「浅底最高对比度」= 该资产在 4 个浅底里的最大值；「深底最高」同理。**< 3.0 判为在该类底板上不可辨认**。
* 目录 `surface` 元数据只作初判（§4.6）。
* 已知偏差：多色牌面（白字压在红牌上，如 `laika_long`、`caisi`）的均值是两个颜色的混合，不等于任何一处的实际颜色；这类资产的对比度只能当量级参考，`ink_std` 列给出它的颜色离散度。

### 3.2 毛刺 / 缩小采样（Q3）

* 目标尺寸：**固定 200 px 宽**（题面「约 200 px」，即导出画布 200–600 px 的最坏一格）；另给字面 `0.2×`／`0.4×` 的结果（`PSNR@字面0.2×` 列）。1024 宽的资产两者只差 2 %。
* 先把 RGBA 合成到底板（白 `#FFFFFF` / 墨 `#1C1D1F`，按该资产更合适的一侧），再对合成后的 RGB 做缩放——这与渲染器「画到已有底色上」等价；
* 三种缩放：
  * `2tap`：真 2×2 双线性点采样，模拟**无 mipmap 的 IR 绘制**（1024 → 200）；
  * `pil`：`Image.BILINEAR`（题面口径；Pillow 会把三角核按缩放比放大，等于面积预滤波）；
  * `lanczos`：`Image.LANCZOS`，作为「正确缩小」的参考。
* 指标：`PSNR(2tap, lanczos)`、`MAD(2tap, lanczos)`，在整张小图上统计。**PSNR < 26 dB 判为高风险**（题面阈值）。
* 最细笔画：对 `alpha ≥ 128` 的掩膜求每个墨迹像素的水平连续段与垂直连续段，取 `min(h_run, v_run)`，再取 **5 % 分位**（避开单像素噪声）作为「最细笔画」；乘 `200/画布宽` 得到 `最细笔画@200px`。`< 1 px` 必然丢细节。

### 3.3 毛边（Q4）

* 半透明像素 = `8 < alpha < 250`；比较它们的平均 RGB 与**不透明墨迹的平均 RGB**，欧氏距离（0–441）。
* **> 40 报警**。但该指标对「牌面里本来就有两种不透明色」的组合（白字红牌）会误报，因此再用 `ink_std`（不透明像素各通道标准差）做二次判据：`ink_std < 40` 视为单色标记，此距离才是真毛边。

### 3.4 目录一致性（Q5）

三层尺寸：`declaredWidth/Height`（设计稿声明）→ `actualWidth/Height`（目录记录）→ 真实文件头（`files/logov/<packaged>` 的 webp 头，用 PIL 读）。逐条比对 474 cut。

### 3.5 autoCutKey（Q6）

按 `LogoPhotoBrand.autoCutKey()` 逐字复刻：先找 `<key>_black`，再 `<key>_white`（在**目录 cut 的 assetKey** 里找），都没有就取 canonical version 的第一枚 cut。默认底板 = `theme=white`（`EngineArgs.DEFAULT_THEME = "white"`）。对比度用**被选中那枚 cut 自己的位图**（`files/logov` 的 webp）墨迹对 `#FFFFFF` 计算——这正是「自动匹配」缩略图/所选值渲染出来的东西。

---

## 4. 发现

### 4.1 极性覆盖（Q1）

| 结论 | 品牌数 | 品牌 |
| --- | --- | --- |
| 两侧都有（ok） | 100 | — |
| 只有浅底（light-only） | 1 | **meyeroptik**：唯一一枚 `ver:meyeroptik:meyer-optik-goerlitz` 墨迹灰 (72,72,72)，深底最高 **2.31** |
| 只有深底（dark-only） | 1 | **godox**：橙 (255,102,0) 对白 **2.94**、白标 1.42；深底 21.0 |
| 两侧都没有 | 0 | — |

* 剩下一侧「近临界」（3.0–4.0）：`viltrox` 深底 3.81、`pentacon` 浅底 3.84。这两家在对应底色上勉强过线，实拍会显脏。
* **41 个品牌完全没有 `files/logos` 基线 PNG**（只有 `ver:` cut）：alcatel, asus, blackberry, blackmagic, ferrania, holga, htc, ilford, infinix, kowa, kyocera, lenovo, lg, manfrotto, panavision, sirui, tcl, vivitar, wiko, zte, viltrox, instax, angenieux, schneider, pentacon, ttartisan, godox, broncolor, novoflex, lensbaby, meyeroptik, adox, cinestill, redmagic, tecno, sharp, vertu, meitu, autel, yuneec, kandao。
  它们的 Inspector 选型路径能画（webp 会被 stage），但**路径 A（照片 EXIF 品牌自动替代）画不出字标**——`brand.rs choose_brand_slot()` 在 `logos/` 里找不到 `{key}_*` stem，直接返回 `None`，卡片只剩自带的烘焙厂商位图。这是「102 个品牌全覆盖」这一说法下面最实际的一个洞。

### 4.2 真实对比度（Q2）

* 基线 PNG：浅底最高对比度中位数 4.96（p10 1.42、最大 21.0）；深底中位数 4.23。逐资产 72 枚浅底不可辨、76 枚深底不可辨——这属正常，因为每枚只服务一侧；关键是**同一品牌要有另一侧的另一枚**（§4.1）。
* 彩色/灰阶里值得一提的：`caisi`（Zeiss 蓝牌）浅 9.86 / 深 2.13；`xiaomi` 浅 4.78 / 深 20.99；`pentacon` 青 3.84/5.47；`godox` 2.94/7.15；`viltrox` 5.51/3.81。
* `kodak_yellow` 被标成 `surface=light`，实测白底 **1.91**（§4.6）。

### 4.3 缩小采样 / 毛刺（Q3）

| 判据 | < 20 dB | < 22 dB | < 26 dB | 中位数 |
| --- | --- | --- | --- | --- |
| 2-tap（IR 模型）·166 PNG | 6 | 30 | **105** | 24.65 |
| 2-tap ·474 目录 cut | — | — | **193** | 26.86 |
| PIL BILINEAR（题面口径）·166 PNG | 0 | 0 | **0** | 32.64 |

**为什么两个口径差这么多**：Pillow 的 `BILINEAR` 在缩小时把三角核按缩放比放大（5× 缩小 = 10 px 宽的核），相当于做了面积平均；而 IR 路径是把 1024 px 的位图直接画进 200 px 的框，Skia/GPU 的双线性只有 2×2 抽头、没有 mipmap，5× 缩小等于**点采样**——笔画被抽样成粗细不均、边缘跳变的样子，就是肉眼看到的「毛刺」。所以题面「用 `Image.BILINEAR` 近似当前 IR 路径」这层近似会系统性低估问题；本报告两个数都给，**风险分级用 2-tap**。

* 最细笔画（5 % 分位）中位数 6.0 px @200，p10 = 2.2 px；37 枚 < 3 px（在这个尺寸下已经是「边缘抖一根像素就变形」的量级）。
* 最差的 20 枚见 `PROBLEMS.md` §3 与 `sheet-risk.png`（左 2-tap、右 LANCZOS，4× 放大）；`tokina_black` 19.13 dB、`horseman_black` 19.41 dB、`hassel_wordmark_black` 19.44 dB（细笔画 2.0 px）是前三。
* 结论：**这套资产在 200 px 宽度下普遍需要 mipmap 或按 0.5× 预缩放**；只换滤波核不够（lanczos 也救不回 2 px 的细笔画，只能把「毛刺」换成「发虚」）。

### 4.4 毛边 / 半透明边（Q4）

* **题面担心的那种（白字存成 RGB=0 的黑底透明图）在这批里不存在**：所有 `*_white.png` 的不透明像素就是 (255,255,255)，半透明边距离 0.0；`oppo_white`/`canon_white`/`mamiya_white` 等白标实测边距均为 0。
* 距离 > 40 的 17 枚基线 PNG 里，多数是**牌面组合**：`laika_long`（287.8，白字＋红牌）、`caisi`（254.2，白字＋蓝牌）、`xiaomi_loing`（200.3）——半透明像素属于白色的字，不透明均值是「红牌＋白字」的混合，距离大是构图造成的，不是毛边。
* 真正的毛边（单色标记，`ink_std < 40`）只有 2 枚：
  * `ricoh_red`：芯 (238,23,32)、边 (246,163,168)，距离 **194.1** —— 红字被混向白，深底上会有一圈浅边；
  * `apple`：芯 (104,104,102)、边 (146,146,145)，距离 **72.9** —— 灰色苹果标带浅色描边。
  * 另有 `agfa_red`（53.8）与 `yijia_red`（31.1）边缘比芯更**暗**，浅底上会有一圈暗边，程度较轻。
* 目录 474 cut 里距离 > 40 的有 83 枚，同理多数是牌面组合。

### 4.5 目录一致性（Q5）

* `declared ≠ actual`：**121 / 474**（`counts.declaredVsActualDimensionMismatches` 报的 121 复现一致）。
* `actual ≠ 真实文件像素`：**0 / 474**。也就是说 **`actualWidth/Height` 完全可信**，`declared` 不可信。
* 121 条里 49 条只是「原图 vs 512 长边的等比缩小」，另有 **72 条连宽高比都变了**（`aolinbasi/olympus-3`：声明 2400×510 = 4.71，实际 512×59 = **8.68**；`konica/konica-5` 4.64 → 7.42；`sony/sony` 4.16 → 6.92）。这 72 条如果被排版代码用来算宽高比，会**把字标拉扁/拉长 30 %–85 %**，属于会直接误导排版的数据。
* 抽样 20 条见 `PROBLEMS.md` §4；全量在 `dims-audit.csv`。

### 4.6 元数据与像素的矛盾（Q6 的邻居）

* `surface` 与实测相反 **25 / 474（5.3 %）**：多数是**浅色/白色版本被标成 `light`**，例如 `canon-1953`（墨迹 206 灰，白底 1.57）、`minolta-1937`（225 灰，1.31）、`sigma-2025-lockup`（240 灰，1.25）、`kodak_yellow`（1.91）、`xiaomi-1`（2.89）。按元数据选「浅底版」会直接选到看不见的那枚。
* `ink` 与实测相反 4 / 474（`dark` 却只能用在深底，或 `light` 却只能用在浅底）。

### 4.7 autoCutKey 选型（Q6）

复刻结果：48 个品牌命中 `<key>_black`，54 个品牌退回 canonical 首枚 cut（`ver:` 键）。

在白底（`theme=white`）上 **< 3.0** 的 8 个品牌：

| 品牌 | autoCutKey 选中 | 白底对比度 | 原因 |
| --- | --- | --- | --- |
| **huawei** | `huawei_white` | **1.00** | 目录里没有 `huawei_black` cut（基线文件叫 `huawe_black.png`，少一个 i），于是落到第二顺位的白标 |
| zte | `ver:zte:zte-logo` | 2.14 | 退回 canonical 首枚，浅色版 |
| vivitar | `ver:vivitar:vivitar` | 2.18 | 同上 |
| htc | `ver:htc:htc` | 2.58 | 同上 |
| wiko | `ver:wiko:wiko-logo` | 2.64 | 同上 |
| xiaomi | `ver:xiaomi:xiaomi-1` | 2.89 | canonical 首枚是橙色 mi 标 |
| alcatel | `ver:alcatel:alcatel-mobile-3` | 2.91 | 同上 |
| godox | `ver:godox:godox-wordmark` | 2.94 | 橙色 wordmark |

* 前 7 个只影响「自动匹配」的缩略图/所选值；`design.logoBrand=="auto"` 时引擎走的是路径 A（按 EXIF 品牌 + 色调重选），实际画出来的**不是**这一枚——所以现象是「缩略图看不见，导出却是好的」，属于会让人以为功能坏了的 UI 级缺陷。
* `huawei` 若用户手动点中 `huawei_white`，就是真·白底白字。
* 反向检查：路径 A 的自选（`choose_brand_slot`）在 61 个有基线的品牌上全部 ≥ 3.0（`coverage.csv` 的「引擎自选对比白」列），没发现不可辨认的情况。

### 4.8 资产卫生（顺带发现）

* `huawe_black.png`（拼写错误，应为 `huawei_black.png`）——直接造成 §4.7 的 huawei 缺陷。
* `zenit_black.png` / `zenit_white.png` 在目录里**没有对应的品牌**（`zenit` 不在 `brands[]`），是死资产。
* `logo.png`（132×132 应用图标）、`logo_empty.png`（全透明占位）混在品牌目录里，会被 `choose_brand_slot` 的品牌前缀规则漏掉，但会污染「166 枚字标」的统计。
* 11 个 `hand-*.png` 是 hand 卡片上传的副本，与正式资产**同图**（按 alpha 掩膜逐字节一致）：`hand-3c9070186b7a`≡`hassel_h_black`、`hand-0dc5124eb319`≡`zeiss`、`hand-1db93b19ac3c`≡`laika`、`hand-4bf2fbfe5f78`≡`hasu_long_black`、`hand-0e33c210b368`≡`hasu_long_white`、`hand-d4e75878241c`≡`laika_long` 等。
* `xiaomi_loing.png` / `xiaomi_loing_black.png` / `xiaomi_loing_white.png` **三份字节相同**（同一张暗色图，白标其实是黑标）。
* `hassel_h_white.png`（512×373）与 `hassel_h_black.png`（512×512）**不是同一套图的换色**；真正换色的是 `hassel_h_white_official.png`（alpha 与黑版逐像素一致）。
* 63 组同时有黑白版的品牌里，**43 组 alpha 完全一致**（真换色），3 组画布不同（`hassel_h`/`meizu`/`yijia`），17 组 alpha 有差——其中 `fushi`（225）、`honor`（116）、`lumix`（106）、`sony`（96）、`apple`（80）是两套不同的稿子，深浅底会看到不同字形。
* `google_black/white.png`、`motorola_black/white.png` 画布只有 **70×70**，在 200 px 宽度下要放大 2.9×，必然发虚（不是毛刺，是分辨率不够）。
* `hassel_*`、`lumix_*`、`huawe_*` 这几组前缀与品牌键（`hasu`/`panasionic`/`huawei`）对不上，路径 A 看不见它们（只能靠路径 B 选）。

---

## 5. 交付物

| 文件 | 内容 |
| --- | --- |
| `README.md` | 本文 |
| `PROBLEMS.md` | 按严重度排序的问题清单（品牌级 + 资产级 + 目录级），每条带数值证据与一句话建议 |
| `coverage.csv` | 102 品牌 × 极性覆盖/最高对比度/autoCut/毛刺计数/备注 |
| `cuts.csv` | 166 枚基线 PNG 的逐资产度量 |
| `cuts-catalog-474.csv` | 474 枚目录 cut（webp）的同口径度量 |
| `dims-audit.csv` | 474 cut 的 declared / actual / 真实像素 三层比对 |
| `sheet-light.png` / `sheet-dark.png` | 102 品牌各选「该底色上最好的一枚」，按 160–200 px 实绘尺寸拼版（红框 = < 3.0） |
| `sheet-risk.png` | 毛刺最重的 16 枚：左 2-tap（IR 模型）、右 LANCZOS，200 px 绘制后 4× 放大 |
| `summary.json` | 上述所有汇总数字（机器可读） |
| `data/*.json` | 逐资产的原始度量（analysis 166 / versions 474 / dims 474） |
| `tools/*.py` | 全部脚本（只读，不写资产） |

## 6. 复现命令

```bash
PY="D:/dsh-home/dsh-runtimes/dsh-primary-runtime/dependencies/python/python.exe"
cd E:/FrameMark/work/ui-v2/evidence/logo-audit

# 1) 像素度量（约 45 s）
"$PY" tools/analyze.py          --out data/analysis.json    # 166 基线 PNG
# 2) 474 枚目录 cut 的 webp 度量（约 2 min 50 s）
"$PY" tools/analyze_versions.py --out data/versions.json
# 3) 目录尺寸三层比对（约 5 s）
"$PY" tools/dims.py             --out data/dims.json
# 4) CSV + summary.json
"$PY" tools/report.py .
# 5) 对照图
"$PY" tools/sheets.py .
```

脚本只 `open(..., "rb")` / `Image.open(...)` 读资产，输出全部写进本目录。

## 7. 已知局限

1. **毛刺模型不是真实引擎渲染**。`2tap` 是「无 mipmap 的双线性点采样」这一行为的模型，不是 Skia 的逐像素复刻：没有模拟 Skia 的采样位置取整、色彩空间（线性/sRGB 混合）、以及引擎侧手写卡自己的 mip 金字塔。真实引擎如果对某张卡开了 mipmap，那张卡不会像本报告预测得那么糟。反面同样成立：本报告没有模拟导出画布 4000 px 以上时字标被画到 600 px 的情形（那里缩放比只有 1.7×，风险显著更低）。
2. **对比度用的是「不透明像素平均色」**。对多色牌面（白字红牌）它既不是牌色也不是字色，`ink_std` 列可以识别这类资产；要更准需要按连通域/主色聚类，本报告没做。
3. **最细笔画是扫描近似**：`min(水平连续段, 垂直连续段)` 的 5 % 分位，对斜笔画和衬线端点会偏乐观；`stroke_min`（最小值）列给了更悲观的下界。
4. **毛边判据是启发式**：单色/多色用 `ink_std < 40` 硬分，`laika_long` 这类牌面组合会被判成「非毛边」而不再深究它对深底的实际观感。
5. **`surface` 元数据的对错以「平均色对比度」判定**，没有考虑设计上「浅色标就该配浅底外的深色描边」这类意图。
6. **autoCutKey 的对比度用 `ver:` cut 的 webp 位图**算；若某条 `ver:` cut 在打包时缺失，`readCutBytes` 会抛 `FM-E-LOGO-001`（本次核对 474/474 都在，`sha256` 与目录一致由 `LogoCatalogAssetIntegrityTest` 保证）。
7. 41 个「无基线 PNG」的品牌，其路径 A 行为是**读代码**（`brand.rs choose_brand_slot` 只扫 `logos/` 的 stem）推出来的，没有跑引擎验证（本次不构建、不连设备）。
