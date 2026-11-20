# 问题清单（按严重度）

判定口径与阈值见 `README.md` §3。所有数值都可由 `data/*.json` 与 `*.csv` 复现。
「白底」默认指 `#FFFFFF`（`theme=white`，`EngineArgs.DEFAULT_THEME`）。

---

## P0 — 会让字标在默认卡面上完全看不见

### P0-1 `huawei` · `autoCutKey()` 选中 `huawei_white`，白底对比度 **1.00**

* 证据：`coverage.csv` → `huawei`，`autoCut选中项=huawei_white`，`autoCut在浅底对比度=1.00`。
* 根因：目录里**没有** `huawei_black` 这个 assetKey——基线文件叫 `huawe_black.png`（少一个 `i`，`logos/` 目录清单里只有 `huawe_black.png`、`huawei.png`、`huawei_white.png`）。`autoCutKey()` 找不到 `<key>_black` 就落到第二顺位 `<key>_white`，而 `huawei_white` 在白底上是白字。
* 影响面：`design.logoBrand=="auto"` 时引擎走 EXIF 品牌路径，实际画的是红色的 `huawei.png`（5.07），所以**缩略图/所选值看不见、导出却是好的**；但只要用户按「自动匹配」给出的那一枚点确认，就是真·白底白字。
* 建议：把 `files/logos/huawe_black.png` 重命名为 `huawei_black.png`（并在目录里补 `huawei_black` cut），`autoCutKey` 即可命中第一顺位。

---

## P1 — 缺一侧可用字标 / 缺基线位图

### P1-1 `godox` 只有深底可用：浅底最高 **2.94**（阈值 3.0）

* 证据：`ver:godox:godox-wordmark` 墨迹 `(254.9,102.0,0.1)`（橙），四档浅底分别是白 2.94 / 纸白 2.70 / 奶油 2.56 / 石色 2.07，**只有石色以上全部不合格**；深底 7.15。唯一另一枚 `--light` 是白字（白底 1.42）。
* 影响：浅色卡（white/paper/cream/stone）上，godox 没有任何可辨认的字标。
* 建议：加一枚深色（墨/深灰）godox wordmark，或把橙色压到 `#C24A00` 以下以满足 3.0。

### P1-2 `meyeroptik` 只有浅底可用：深底最高 **2.31**

* 证据：唯一一枚 `ver:meyeroptik:meyer-optik-goerlitz` 墨迹 `(72.3,72.4,72.3)`，浅底 9.09、深底 2.31。
* 建议：补一枚白/浅色版；该品牌目录里只有 1 个 cut。

### P1-3 41 个品牌没有 `files/logos` 基线 PNG —— 照片 EXIF 自动匹配画不出字标

* 名单：`alcatel asus blackberry blackmagic ferrania holga htc ilford infinix kowa kyocera lenovo lg manfrotto panavision sirui tcl vivitar wiko zte viltrox instax angenieux schneider pentacon ttartisan godox broncolor novoflex lensbaby meyeroptik adox cinestill redmagic tecno sharp vertu meitu autel yuneec kandao`
* 证据：`coverage.csv` 的 `基线PNG数=0`；代码 `engine/e2b/src/brand.rs:151 choose_brand_slot()` 只遍历 `logos/` 目录里以 `{key}_` 开头的 stem，找不到就返回 `None`（品牌槽不画）。目录里这些品牌只有 `ver:` cut，`ver:` 键不是 stem。
* 后果：这 41 个品牌在「选 Logo → 自动匹配 / 按照片品牌」这条最常用的路径上是空白的；从 Inspector 手动选中版本仍能渲染（`readCutBytes` 会把 webp stage 成 PNG stem）。
* 建议：为每个品牌补一枚 `<key>_black.png` + `<key>_white.png`（可从现成 `ver:` cut 导出），或让 `choose_brand_slot` 在 stem 目录为空时回退到目录里品牌首枚 cut 的打包 webp。

### P1-4 `autoCutKey` 在另外 7 个品牌上选中白底 < 3.0 的字标（huawei 见 P0-1）

| 品牌 | 选中 | 白底 | 说明 |
| --- | --- | --- | --- |
| `zte` | `ver:zte:zte-logo` | 2.14 | 退回 canonical 首枚 |
| `vivitar` | `ver:vivitar:vivitar` | 2.18 | 同上 |
| `htc` | `ver:htc:htc` | 2.58 | 同上 |
| `wiko` | `ver:wiko:wiko-logo` | 2.64 | 同上 |
| `xiaomi` | `ver:xiaomi:xiaomi-1` | 2.89 | 橙色 mi 标 |
| `alcatel` | `ver:alcatel:alcatel-mobile-3` | 2.91 | 同上 |
| `godox` | `ver:godox:godox-wordmark` | 2.94 | 同 P1-1 |

* 证据：`coverage.csv` 的 `autoCut在浅底对比度`；`autoCut选中方式=canonical-first-cut` 共 54 个品牌。
* 补充：`xiaomi` 连路径 A 的自选也只有 **3.2**（`xiaomi_loing_black`，tan 色 wordmark），因为 `choose_brand_slot` 先按「是不是 wordmark 形状」再按色调排，把 3.2 的长标排在 4.78 的橙色 mi 标前面——浅底上是「勉强过线」。
* 建议：`autoCutKey` 的兜底不要取「canonical 第一枚」，而是取「该品牌里在浅底对比度最高的一枚」（目录已有 `light_best` 所需的一切信息），或强制 `<key>_black` 缺失时落到 `ink=dark` 的 cut。

---

## P2 — 毛刺 / 缩小采样（105 / 166 枚 < 26 dB）

* 判据：200 px 实绘宽度、`2tap`（无 mipmap 的双线性）vs `LANCZOS` 的 PSNR，**< 26 dB 判高风险**。题面口径的 `Image.BILINEAR` 因为自带面积预滤波，同阈值下 **0 枚**触发（中位数 32.64 dB），所以这张表用 2-tap 模型。
* 视觉复核：`sheet-risk.png`（左 2-tap、右 LANCZOS，4× 放大）。
* 分布：< 20 dB 6 枚、< 22 dB 30 枚、< 26 dB 105 枚；最细笔画中位数 6.0 px @200，37 枚 < 3 px、9 枚 < 2 px。

### P2-1 风险最高的 20 枚（按 art 去重，黑白版同图并列）

| # | assetKey | 画布 | PSNR@200 (dB) | MAD | 最细笔画@200 (px) | 同图别名 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `tokina_black` | 1620x231 | 19.13 | 13.31 | 2.60 | tokina_black/tokina_white |
| 2 | `horseman_black` | 1620x169 | 19.41 | 14.05 | 2.40 | horseman_black/horseman_white |
| 3 | `hand-30566632c0e1` | 1475x118 | 19.44 | 12.21 | 2.00 | hand-30566632c0e1 |
| 4 | `hassel_wordmark_black` | 1475x118 | 19.44 | 12.21 | 2.00 | hassel_wordmark_black/hassel_wordmark_white |
| 5 | `praktica_black` | 1620x191 | 20.04 | 12.12 | 7.60 | praktica_black/praktica_white |
| 6 | `nothing_black` | 1620x231 | 20.25 | 11.41 | 2.40 | nothing_black/nothing_white |
| 7 | `insta360_black` | 1024x142 | 20.33 | 11.49 | 2.80 | insta360_black/insta360_white |
| 8 | `konica_minolta_black` | 1620x315 | 20.36 | 9.23 | 2.00 | konica_minolta_black/konica_minolta_white |
| 9 | `bronica_black` | 1620x254 | 20.52 | 11.25 | 3.40 | bronica_black/bronica_white |
| 10 | `phaseone_black` | 1620x241 | 20.84 | 10.57 | 2.80 | phaseone_black/phaseone_white |
| 11 | `voigtlander_black` | 1156x231 | 20.91 | 10.18 | 1.80 | voigtlander_black/voigtlander_white |
| 12 | `tamron_black` | 1620x281 | 21.20 | 9.98 | 12.00 | tamron_black/tamron_white |
| 13 | `hasu_long_black` | 1024x70 | 21.24 | 9.93 | 1.20 | hasu_long_black/hasu_long_white（`hand-4bf2fbfe5f78` 同图） |
| 14 | `konica_black` | 1620x273 | 21.43 | 9.28 | 9.00 | konica_black/konica_white |
| 15 | `samsung_black` | 1024x159 | 21.66 | 9.19 | 5.21 | samsung_black/samsung_blue/samsung_white |
| 16 | `minox_black` | 1620x281 | 21.68 | 8.44 | 13.20 | minox_black/minox_white |
| 17 | `yashica_black` | 1620x311 | 21.75 | 8.69 | 5.40 | yashica_black/yashica_white |
| 18 | `graflex_black` | 1620x588 | 22.01 | 8.51 | 2.20 | graflex_black/graflex_white |
| 19 | `aolinbasi_black` | 1024x194 | 22.04 | 8.45 | 3.00 | aolinbasi/aolinbasi_black/aolinbasi_white |
| 20 | `hand-4bf2fbfe5f78` | 1024x70 | 21.24 | 9.93 | 1.20 | 与 `hasu_long_black` 同图 |

* 观察：`praktica`（7.6 px 笔画）、`tamron`（12 px）、`minox`（13.2 px）笔画并不细，仍然 < 22 dB——说明**问题不只是细笔画，而是 5× 点采样把任何硬边都抽歪了**；只要走 2-tap，所有带强反差的字标都掉到这个区间。
* 建议（按性价比）：① 缩小走 mipmap / 或按 `0.5×` 预生成一张半分辨率图供 200–400 px 使用；② 不愿改渲染就**批量预缩放**：把每枚 1024 px 资产另存一张 512 px 与 256 px 的 LANCZOS 版本，渲染按目标宽度选最接近的一档；③ 单靠换滤波核（bilinear→lanczos/mitchell）只能把毛刺换成发虚，救不回 2 px 笔画。

### P2-2 `_white` 资产不是白底透明图的问题（题面假设不成立）

* 所有 `*_white.png` 的不透明像素 RGB ≈ (255,255,255)，半透明边距离 **0.0**——不存在「白字被存成 RGB=0 黑底透明图」的情况（`coverage.csv`/`cuts.csv` 的 `半透明边RGB距离` 列可逐条核对）。

### P2-3 真毛边：2 枚单色标记的半透明边明显混向背景色

| assetKey | 芯色均值 | 半透明边均值 | 距离 | 症状 |
| --- | --- | --- | --- | --- |
| `ricoh_red` | (238,23,32) | (246,163,168) | **194.1** | 红字边缘发粉白，深底上一圈浅边 |
| `apple` | (104,104,102) | (146,146,145) | **72.9** | 灰标带浅色描边 |
| `agfa_red`（轻） | (81,44,32) | (40,18,10) | 53.8 | 边缘比芯更暗，浅底上一圈暗边 |
| `yijia_red`（轻） | (237,7,22) | — | 31.1 | 同上 |

* 其余 13 枚距离 > 40 的基线 PNG 与 83 枚目录 cut 是**牌面组合**（白字压在红/蓝牌上，如 `laika_long` 287.8、`caisi` 254.2、`xiaomi_loing` 200.3），距离来自同一张图里的第二种不透明色，不是毛边。判据：`ink_std < 40`（单色）才算真毛边。
* 建议：这 4 枚按芯色重做 alpha 边缘（或存 premultiplied PNG），批量脚本可复用 `tools/metrics.py` 的 `edge_dist`。

---

## P3 — 目录 / 元数据不一致（会误导选型与排版）

### P3-1 `declaredWidth/Height` 与真实尺寸不符：**121 / 474**；其中 **72 条连宽高比都变了**

* `actualWidth/Height` 与真实 webp 像素 **474/474 完全一致**（`dims-audit.csv` 的 `actual_vs_real` 列全为 `ok`），所以 `actual` 可信、`declared` 不可信。
* 121 条里 49 条只是等比缩小；72 条宽高比漂移，最大 3.97。若排版代码用 `declared` 算宽高比，会明显拉扁/拉长。
* 抽样 20 条（按宽高比漂移排序）：

| cut | declared | actual | 真实 | 宽高比 declared→actual | 漂移 |
| --- | --- | --- | --- | --- | --- |
| `aolinbasi/olympus-3` | 2400x510 | 512x59 | 512x59 | 4.706 → 8.678 | 3.972 |
| `konica/konica-5` | 2390x515 | 512x69 | 512x69 | 4.641 → 7.420 | 2.779 |
| `sony/sony` | 2400x577 | 512x74 | 512x74 | 4.159 → 6.919 | 2.760 |
| `panasionic/panasonic-logo-blue` | 300x75 | 268x42 | 268x42 | 4.000 → 6.381 | 2.381 |
| `fushi/fujifilm-red-symbol-horizontal` | 2400x562 | 512x79 | 512x79 | 4.270 → 6.481 | 2.211 |
| `lomo/lomo-cyrillic` | 2400x678 | 512x92 | 512x92 | 3.540 → 5.565 | 2.025 |
| `motorola/motorola-1` | 2400x586 | 512x85 | 512x85 | 4.096 → 6.024 | 1.928 |
| `samsung/samsung-2` | 2400x662 | 512x93 | 512x93 | 3.625 → 5.505 | 1.880 |
| `yashica/yashica-serif` | 962x251 | 512x93 | 512x93 | 3.833 → 5.505 | 1.672 |
| `minolta/minolta-dot` | 2400x700 | 512x101 | 512x101 | 3.429 → 5.069 | 1.640 |
| `polaroid/polaroid-3` | 2400x704 | 512x102 | 512x102 | 3.409 → 5.020 | 1.611 |
| `motorola/motorola-4` | 2204x588 | 512x98 | 512x98 | 3.748 → 5.224 | 1.476 |
| `minolta/minolta-1957` | 1073x294 | 512x100 | 512x100 | 3.650 → 5.120 | 1.470 |
| `tamron/tamron-logo` | 2400x541 | 512x88 | 512x88 | 4.436 → 5.818 | 1.382 |
| `fushi/fujifilm-green-strip-thin` | 2400x672 | 512x104 | 512x104 | 3.571 → 4.923 | 1.352 |
| `konica/konica-3` | 2400x758 | 512x114 | 512x114 | 3.166 → 4.491 | 1.325 |
| `huawei/huawei-2` | 2137x658 | 512x113 | 512x113 | 3.248 → 4.531 | 1.283 |
| `insta360/insta360-2020` | 2328x655 | 512x106 | 512x106 | 3.554 → 4.830 | 1.276 |
| `sigma/sigma-legacy-red` | 2400x708 | 512x113 | 512x113 | 3.390 → 4.531 | 1.141 |
| `nubia/nubia-wordmark` | 1200x448 | 512x137 | 512x137 | 2.679 → 3.737 | 1.058 |

* 建议：排版只用 `actualWidth/Height`（它等于真实像素）；`declared` 要么改成真实像素，要么在 schema 里标成「原稿尺寸，仅供展示」。同时修生成脚本——121 条说明声明值是从另一份清单抄来的，没有和打包文件对齐。

### P3-2 `surface` 与像素相反：**25 / 474**

| 品牌 | cut | surface | 浅底 | 深底 |
| --- | --- | --- | --- | --- |
| canon | `ver:canon:canon-1953` | light | 1.57 | 13.33 |
| nikon | `ver:nikon:nikon-1917` | light | 1.60 | 13.16 |
| sony | `ver:sony:sony-1` | light | 2.30 | 9.14 |
| fushi | `ver:fushi:fujifilm-1980` | light | 2.83 | 7.41 |
| aolinbasi | `ver:aolinbasi:olympus-1970` | light | 1.84 | 11.44 |
| ricoh | `ver:ricoh:ricoh-1963` | light | 1.41 | 14.91 |
| minolta | `ver:minolta:minolta-1937` | light | 1.31 | 16.07 |
| kodak | `ver:kodak:kodak-1971` | light | 1.89 | 11.10 |
| kodak | `ver:kodak:kodak-2006` | light | 1.80 | 11.68 |
| kodak | `kodak_yellow` | light | 1.91 | 11.00 |
| alpa | `ver:alpa:alpa-14940` | light | 1.73 | 12.11 |
| sigma | `ver:sigma:sigma-1` | light | 2.65 | 7.94 |
| sigma | `ver:sigma:sigma-2025-lockup` | light | 1.25 | 18.52 |
| phaseone | `ver:phaseone:phase-one-logo` | light | 2.55 | 8.25 |
| motorola | `ver:motorola:motorola-1950` | light | 2.02 | 10.42 |
| google | `ver:google:google-1998` | light | 1.29 | 16.34 |
| apple | `ver:apple:apple-1` | light | 2.81 | 7.48 |
| laika | `ver:laika:leitz-1` | light | 2.56 | 8.20 |
| xiaomi | `ver:xiaomi:xiaomi-1` | light | 2.89 | 7.28 |
| xiaomi | `ver:xiaomi:xiaomi-logo-2` | light | 2.50 | 8.41 |
| xiaomi | `ver:xiaomi:xiaomi-brasil-logo-2` | light | 2.60 | 8.07 |
| alcatel | `ver:alcatel:alcatel-mobile-3` | light | 2.91 | 7.21 |
| vivitar | `ver:vivitar:vivitar` | light | 2.18 | 9.62 |
| wiko | `ver:wiko:wiko-logo` | light | 2.64 | 7.95 |
| godox | `ver:godox:godox-wordmark` | light | 2.94 | 7.15 |

* 另有 4 条 `ink` 与实测相反（`ink=dark` 却深底可用 / `ink=light` 却浅底可用）：`quantile` 判定见 `cuts-catalog-474.csv` 的 `实测极性` 列。
* 建议：用渲染前的实际像素算一次 `light_best/dark_best` 回写 `surface`（脚本 `tools/metrics.py` 直接可复用）；至少把这 25 条改成 `dark`。

### P3-3 资产卫生

| 问题 | 证据 | 建议 |
| --- | --- | --- |
| `huawe_black.png` 拼写错误 | `logos/` 里没有 `huawei_black.png`；P0-1 的根因 | 重命名并补 cut |
| `zenit_black/white.png` 无对应品牌 | `brands[]` 里没有 `zenit` | 补品牌条目或删文件 |
| `logo.png`、`logo_empty.png` 混进品牌目录 | 132×132 应用图标 / 全透明占位 | 移到应用图标目录，别计入「166 枚字标」 |
| 11 个 `hand-*.png` 与正式资产同图重复 | 按 alpha 掩膜逐字节比对：`hand-3c9070186b7a`≡`hassel_h_black`/`hassel_h_white_official`；`hand-0dc5124eb319`≡`zeiss`；`hand-1db93b19ac3c`≡`laika`；`hand-4bf2fbfe5f78`≡`hasu_long_black`；`hand-0e33c210b368`≡`hasu_long_white`；`hand-d4e75878241c`≡`laika_long`；`hand-30566632c0e1`≡`hassel_wordmark_black/white`；`hand-52dedca27c4c`≡`panasionic_white`；`hand-a3039e969d6c`≡`panasionic_black`；`hand-ef16bd6fe783`≡`lumo_black/white`；`hand-427f3e6bae93`≡`fushi_white` | hand 卡片的用户图应放在独立目录，不要写进品牌 `logos/` |
| `xiaomi_loing` / `_black` / `_white` 三份字节相同 | 像素哈希一致（`cuts.csv` 的 `重复于` 列） | 白标其实是黑标，深底上会看不见；补真正的白版 |
| `hassel_h_white.png` 与黑版不是换色 | 白版 512×373、黑版 512×512；真正的换色是 `hassel_h_white_official.png`（alpha 逐像素一致） | 统一成 `hassel_h_white.png` = 官方换色版，删掉 `_official` |
| `google_*`、`motorola_*` 画布仅 70×70 | 200 px 宽度要放大 2.86× | 换 ≥512 px 的源图 |
| `hassel_*` / `lumix_*` / `huawe_*` 前缀对不上品牌键 | `brand.rs choose_brand_slot` 只认 `{key}_`；`hasu`/`panasionic`/`huawei` 看不见这些文件 | 统一命名，或在 `brand.rs` 增加别名表 |

### P3-4 极性成对资产里，20/63 组的白版不是黑版的换色

* 判据：同一品牌 `<key>_black.png` 与 `<key>_white.png` 的 alpha 掩膜是否逐字节一致（一致的换色在渲染上完全对称）。
* 结果：63 组同时有黑白版的品牌里，**43 组 alpha 完全一致**（真换色）、**3 组画布都不同**（`hassel_h` 512×512 vs 512×373、`meizu` 1024×189 vs 1024×194、`yijia` 1024×239 vs 1024×237）、**17 组 alpha 有差**，其中差得最大的是 `fushi` 225、`honor` 116、`lumix` 106、`sony` 96、`apple` 80——这几组是**两套不同的稿子**，浅底和深底卡片上看到的笔画粗细/字形并不一致。
* 建议：深浅两版从同一份矢量/路径派生，只换填充色；`fushi`（白版整体换了一套 lockup）、`honor`、`lumix`、`sony`、`apple` 这 5 组优先重导。

---

## 附：本次没有发现问题的地方

* `sha256` / 打包完整性：474 个 cut 的 `preview` webp 全部存在，`actualWidth/Height` 与真实像素 474/474 一致（`LogoCatalogAssetIntegrityTest` 的断言在本次抽样里成立）。
* 路径 A（EXIF 品牌自动替代）在 61 个有基线的品牌上，`choose_brand_slot` 选出的浅底资产全部 ≥ 3.0，没有「引擎自己选错色调」的情况。
* 白标不存在暗边/黑底透明图的经典缺陷（见 P2-2）。
