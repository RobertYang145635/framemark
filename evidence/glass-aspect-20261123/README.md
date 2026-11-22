# 玻璃三张卡的画幅核对（rc57 → 本轮）

`before-after.png`：同一份样片下 rc57 与本轮结果的并排对照（左 rc57、右修后），
每张下面带画布尺寸、照片起点、照片占比与留白。

## 结论（数字见 `geometry.md`）

- **玻璃 05**：照片起点 13.18% → **45.93%**、照片宽占比 73.64% → **40.89%**、
  上下留白 13.18% → **5.65%**，四组输入（4:3 / 3:4 / 1:1 / 16:9）一致；
  画布由构图推出来（宽高比 = 照片宽高比 × 2.1692），照片完整入卡、不再被裁。
- **玻璃色卡**：面板宽占比 4:3 由 54.05% 回到 **89.2%** 通栏；高度按面板自身
  676:310（由 `panelWidthPct`/`panelHeightPct` 换算）给出，极宽画布按 60% 封顶后
  网格等比缩小。
- **液态玻璃**：胶囊宽高比 3:4 **4.03 → 7.16**（1:1 5.37 → 7.16、16:9 9.55 → 7.16、
  4:3 不变），字号按胶囊高度换算，不再「框显厚、字变小」。

## 为什么画布跟着照片走（而不是写死 1.4971）

原版 Electron（`D:/border&watermark/framemark`，goldens 的来源）对玻璃 05 是**写死
1.4971 画布**：4:3 输入 → 1400×936、3:4 输入 → 800×534，照片按竖幅卡窗裁。
`options/original-app-4-aspects.png` 是它的实测四联，`options/glass05-four-semantics.png`
是当时比较过的四种语义（A 原版 / B 画布跟照片 / C 构图不变画布加宽 / D 画布固定卡片跟照片）。
产品选了 **C**：构图不变、照片不裁、画布随照片比例加宽。

## 复现

1. 样片必须让 `--photo` 与 `card.photo.path` 相同，否则引擎去读文件自身的 EXIF
   （品牌/日期/参数行会空，看起来像版式坏了）。
2. `e4hand.exe render --card <id> --cards <cards.json> --hand hand --fonts fonts
   --extra-fonts e4-extra-fonts --photo <fixture> --preview-max-edge 1100 --out a.png
   --report a.json`。
3. 读 `a.json` 的 `canvas`、`photo_rect`（照片框）与
   `decor[kind=glass.backdrop].rect`（色卡面板 / 液态胶囊），按占比比对。

## 回归面

69 张手写卡 × 4:3 / 3:4 逐卡对比（画布、照片框、每条文字绘制的字号与墨迹框）：
只有 `glass-pill-5`（画布 + 照片框）、`glass-palette` / `glass-liquid`（文字）、
`glass-card 3:4`（文字，修掉了数值压照片）变化，其余 65 张与 rc57 完全一致。

## 已知代价

- 画布跟照片比例后，4:3 照片的输出是 2.89:1 的长条（16:9 → 3.85:1）；预览缩略图
  也因此是 480×166。
- 照片卡仍只占画布宽的 40.89%，成片里照片的像素是被缩到这个卡片尺寸的（与 rc57、
  原版同一类行为，只是卡片高度从 88.7%×画布高 变成了 88.7%×更矮的画布）。
- golden `liquid-glass/glass-pill-5.png`（2667×1781）不再逐像素可比，原因记在
  `qa/release-gate/known-deviations.json`。
