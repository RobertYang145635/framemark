# 底板 / 边框库 / 品牌选择器的上圆角外侧还有一个直角

用户反馈两轮，前两轮都在删「先刷一层方形底色再 clip」的补丁，删完**还在**。这次不猜，先在真机上把
那个直角量出来，再顺着颜色找到画它的那一层。

## 病因：不是补色，是「面板背后那一层是方的」

面板自己圆了，但**背后那一层是矩形**，而且它的上沿正好压在面板的包围盒顶边上。于是每个上圆角外侧都
留着一个直角缺口，缺口里填的是那一层的颜色。三处同款，缺口里露的东西不一样：

| 面板 | 背后那一层 | 缺口里露出来的 |
| --- | --- | --- |
| 编辑器底板（`PhoneSheetHost`） | `PhoneShell` 根 Box 的 `background(FmPalette.canvas)`（`shell/PhoneShell.kt` 里那个整屏 Box） | **页面底色 `FmPalette.canvas`** |
| 边框库覆盖层（`PhoneBorderPage`） | 遮罩是 `Column` 里 `weight(1f)` 的一条带，只铺到面板顶边 | **没压暗的舞台** |
| 品牌选择器覆盖层（`PhoneLogoPicker`） | 同上（同一份代码形状） | **没压暗的舞台** |

为什么前两轮删了补色还在：补色不是病因。底板缺口露的是**页面底色**，而 `canvas` 在每套配色里都只比
`surface` 深一档（暖灰墨绿深色：`#181C19` vs `#222923`），所以那两块直角看起来像「与面板同色的小
耳朵」；浅色（`#F2F1ED` vs `#FCFBF8`）同理。两个覆盖层的缺口更直接：深色遮罩边上露一整块舞台色，
是亮方角。

## 改了什么

1. **舞台色一个来源**（`shell/phone/PhoneStagePane.kt`）：新增 `phoneStageColor()`，舞台、编辑器顶栏
   （含状态栏条带）、底板背板三处共用 `AppearanceEnv` 的那一档 `StageTone`。三处必须同色，才可能
   「圆角是唯一的分界」。
2. **底板：先铺舞台色，再 clip**（`shell/phone/PhoneSheetHost.kt`）
   `background(phoneStageColor())` 放在 `clip(RoundedCornerShape(topStart/topEnd))` **之前**：整块矩形
   先刷成舞台色，圆角再把面板裁出来。缺口露出舞台色，与面板正上方那条舞台带接得上。
   *顺序是关键* —— 背景放在 clip 之后会被一起裁掉，缺口又露回底色。
3. **两个覆盖层：遮罩铺满整屏**（`shell/phone/PhoneBorderPage.kt`、`inspector/PhoneLogoPicker.kt`）
   遮罩改成独立一层 `fillMaxSize()`，面板盖在它上面；原来那条「遮罩带」只剩点击。面板不透光，所以
   这一层对面板本身一个像素都不改，缺口却从「没压暗的舞台」变成「遮罩色」。
4. 没有写死任何颜色：三处读的都是主题/设置里的真实令牌，浅灰 / 中灰 / 深灰画布 × 深浅外观 ×
   四套配色都成立（下面的测试逐格跑）。

## 真机取证（同一坐标、同一数据、同一串点击）

1440×3168 @density 3.5，`com.framemark.native.qa20261106uiv2`；画布背景 = **中灰**（`#5A5C61`），
外观 = **深色**（暖灰墨绿，`surface=#222923`、`canvas=#181C19`）。两次测量之间的唯一差别是那 3 行修法：

* PRE = 本地 debug 包（`HEAD` 的 5 个文件里只回滚这 3 处），POST = 同一个包带上修法。
* 两次都是：`am force-stop` → 启动 → **继续最近编辑** → 同一张照片、同一个边框（经典双行）→
  「更换」打开边框库。点击坐标由 `uiautomator` 现查，不写死。
* 圆角弧线的光栅位置两次完全相同（底板 x=5 处 2233–2236、边框库 x=3 处 572–576），所以布局没有被
  改动，差异只可能来自颜色。

### 编辑器底板（面板顶边 y=2181，两侧对称）

| 坐标 | PRE（未修复） | POST（修复后） | 该点是什么 |
| --- | --- | --- | --- |
| (5, 2185) | **(24,28,25)** | **(90,92,97)** | 上圆角外侧缺口 |
| (5, 2200) | **(24,28,25)** | **(90,92,97)** | 缺口（更靠内） |
| (1434, 2200) | **(24,28,25)** | **(90,92,97)** | 右侧缺口 |
| (720, 2170) | (90,92,97) | (90,92,97) | 面板正上方那条舞台带（不动） |

`(24,28,25)=#181C19` 是深色 `canvas`，`(90,92,97)=#5A5C61` 是中灰舞台色。PRE 在 x=5 上从 2181 到
2232 全是 canvas（52 px 高的方块），POST 从 0 到 2232 全是舞台色 —— 方块没了，圆弧之外与圆弧之上
同色。

### 边框库覆盖层（面板顶边 y=514，两侧对称）

| 坐标 | PRE（未修复） | POST（修复后） | 该点是什么 |
| --- | --- | --- | --- |
| (3, 520) | **(90,92,97)** | **(36,37,39)** | 上圆角外侧缺口 |
| (3, 540) | **(90,92,97)** | **(36,37,39)** | 缺口 |
| (3, 560) | **(90,92,97)** | **(36,37,39)** | 缺口 |
| (1436, 520) | **(90,92,97)** | **(36,37,39)** | 右侧缺口 |
| (3, 495) | (36,37,39) | (36,37,39) | 遮罩带（不动） |

`(36,37,39)` = 60 % 黑压在中灰舞台上的遮罩色。PRE 在 x=3 上 514–571 是没压暗的舞台（58 px 高的亮
方块），POST 从 0 到 571 都是遮罩色。

## 截图

| 文件 | 说明 |
| --- | --- |
| `01-before-sheet-corner.jpg` | **主证据（前）**：底板左上角。灰色的舞台带下面，圆角外侧是一块近黑的方角（`canvas`），圆弧与直角叠在一起 —— 用户说的「小耳朵」。裁自 PRE 全屏图，4 倍最近邻放大。 |
| `02-after-sheet-corner.jpg` | **主证据（后）**：同一坐标。舞台色一直铺到圆弧，方角消失，圆弧是唯一的分界。 |
| `03-before-library-corner.jpg` | 边框库覆盖层左上角（前）：深色遮罩带下面是一块**亮的**中灰方角（没压暗的舞台）。 |
| `04-after-library-corner.jpg` | 同上（后）：遮罩一直铺到圆弧，缺口与遮罩带同色。 |

## 回归测试（像素级，无头渲染真实组件）

`ui/composeApp/src/desktopTest/kotlin/com/framemark/native/shell/phone/PhoneSheetCornerRenderTest.kt`，
411×905 dp @density 3.5，把**真实**的 `PhoneSheetHost` / `PhoneBorderLibraryOverlay` /
`PhoneLogoPickerOverlay` 渲染成图再量像素，三格一组：

* `theSheetCornerShowsTheStageAndNotThePageColour`：3 种画布 × 深浅外观 × 2 套配色（暖灰墨绿 / 石墨琥珀）；
* `theLibraryPanelCornerShowsTheScrimAndNotTheStage`；
* `theBrandPickerCornerShowsTheScrimAndNotTheStage`。

断言（不测「代码里有没有某个 background」，那种换个写法就假通过）：

1. 缺口色 **==** 面板正上方那条背景带的颜色（圆弧是唯一分界）；
2. 缺口色 **!=** 面板色（不允许与面板同色的直角）；
3. 缺口色 **!=** `FmPalette.canvas` / 没压暗的舞台（上一版就是这个）；
4. 跨过圆弧之后同一列已经是面板（圆弧真的存在，不是把面板挪走了）。

把三处修法回滚后这三条**立刻红**，红在点名的像素上，例如：

```
tone=dark dark=false palette=forest: the corner notch at (notchLeftX=2, sheetTop+6)
is not the stage: notch=(242,241,237) stage=(43,44,47) canvas=(242,241,237) surface=(252,251,248)
library tone=dark dark=false: the corner notch at (2, panelTop+6) is not the scrim:
notch=(43,44,47) scrim=(40,42,43) stage=(43,44,47)
```

恢复修法后全绿；PNG 落在 `ui/composeApp/build/panel-corner/` 供人眼复核。

## 验证

* `D:\dev\tmp\fm_kotlin_check.cmd`：通过。
* `D:\dev\tmp\fm_kotlin_test.cmd`：**1383 条全绿，0 失败**（本改动前基线 1345；新增 3 条即本文件的三个
  用例，其余增量来自并行线上已提交的测试）。
* 真机：PRE / POST 同一个包名、同一份数据、同一串点击，上表的同坐标像素对比。

## 复现

```text
adb install -r <本地 debug 包，-PframemarkQaSuffix=.qa20261106uiv2>
# 设置 → 画布背景 中灰、外观 深色；返回首页 → 继续最近编辑 → 「更换」→ 截图
adb exec-out screencap -p > x.png
```

截图上量 `(5, 面板顶边+4..+50)`（底板）与 `(3, 面板顶边+6..+57)`（边框库）这两条竖线即可。

## 边界说明

* 品牌选择器的真机截图没有单独留：它与边框库是同一份代码形状（整屏遮罩 + 面板），像素测试里
  单独一个用例覆盖（上面第 3 条），真机证据用的是边框库那一组。
* 本次装机的两个包都是**本地产物**（`:composeApp:assembleDebug`，QA 后缀复用已装包名以便
  `adb install -r` 保留数据），没有改 `version.properties`、没有动 `updates/latest.json`、没有发版、
  没有 push。
* 这 5 个文件的改动最终是被并行任务的 `git add -A` 一起提交进 `f0b4769` 的（提交说明写的是那一行
  的工作）；本提交补上病因、取证明细与回归测试的说明。
