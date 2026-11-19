# 无边框首屏：只留一个「选择一个边框」

产品决策（用户原话）：

> 不要默认边框，我们的做法是导入照片，然后选择边框，然后进行微调，如果需要水印，
> 再添加水印，最后导出。

即：导入后**不自动套边框**，而「没有边框」这一屏必须把人引到唯一的下一步 —— 选边框。

## 改了什么

1. **入口只有一个，而且就在照片上**（`shell/phone/PhoneStagePane.kt` `StagePlaceholder`）
   画布正中那枚用的是全局主按钮 `PrimaryButton`，文案取
   `PhoneBorderPageState.CHOOSE_LABEL`；点它**直接打开边框库**覆盖层。
   以前这个回调接的是「切到边框工具」，而工具本来就在边框，等于点下去什么都不发生。
2. **面板里不再有第二个「选择一个边框」**（`shell/phone/PhoneBorderPanel.kt`）
   底部面板只剩一行「当前边框」；没边框时那一行写「未选边框」，**没有全宽按钮**。
   同一屏同一个动作出现两次、文案还不一样，读起来是两件事。
3. **不写任何解释性文案**（`PhoneBorderPageState.currentDescription`）
   旧的两句 ——「选一个边框后，样式 / 文字 / Logo / 水印才会出现。」与
   「这张照片还没有边框。选择边框后才能调整参数、文字、Logo、水印位置。」——整段删除，
   没有换成别的句子。无边框时那一行返回 `null`，第二行**整行不画**。
   判断标准：删掉这句话用户仍然知道该做什么，那它就是多余的。
4. **工具条收成只剩「边框」一格**（`PhoneToolMapping.phoneToolsFor(model, hasBorder)`）
   无边框时样式 / 文字 / 水印三格都是在一张还不存在的边框上做文章，点开只能回一句
   「先选择边框」——三次无效点击。`PhoneEditorScreen` 传 `hasBorder = card != null`，
   并沿用既有的 `LaunchedEffect(tools)` 把 `tool` 收回 `EditorTool.Border`
   （所以不可能是「视觉上少三格、状态里还选着文字」）。
   工具条尾部那枚「预设」是独立动作，不是工具格，仍然常驻。
5. **`PanelRequiresBorder` 整块删除**（`shell/phone/PhoneSheetHost.kt`）
   它承载的正是第 3 条里那两句说明 + 一枚「去选边框」。无边框时（以及刚删掉边框、
   工具条正在收口那一帧还在飞的过渡页）现在统一画边框面板。

面板高度因此从 204 dp（16 把手 + 44 标题 + 144 面板，其中 48 是那条按钮）收到
148 dp（16 + 44 + 88），并且**有边框 / 没边框同高** —— 点选边框前后底板不跳，
照片不会被顶一下。`panelHeight` 里随之删掉 `PANEL_PRIMARY_ACTION_HEIGHT_DP`
与 `PANEL_ACTION_GAP_DP`。

## 截图

真机 1440×3168，`com.framemark.native.qa20261106uiv2`（rc37 的包名），
装机的是 **2026-11-20 04:06 的本地 debug 构建**（`-PframemarkQaSuffix=.qa20261106uiv2`
+ `:composeApp:assembleDebug`，含本次改动 + 当时工作区里其它未提交改动；不是发版包）。

| 文件 | 说明 |
| --- | --- |
| `01-empty-state-light.jpg` | **主证据**：新导入的照片 1/1、未选边框。画布正中只有一枚「选择一个边框」；底部面板只有「边框 / — 未选边框」，没有第二个按钮、没有说明句；工具条只剩「边框」一格。 |
| `02-entry-opens-library.jpg` | 点那枚按钮 → 边框库覆盖层打开（标题行「当前：未选边框」），唯一入口是活的。 |
| `03-empty-state-dark.jpg` | 深色主题下的同一屏（同一套布局，两套配色都不出现第二入口）。 |

全尺寸原图（未压缩）：`E:\FrameMark\evidence\20261120-phone-empty-state\`
（`05-empty-state-final.png` / `09-entry-tap.png` / `10-empty-state-final-2.png`）。

复现：`adb uninstall <pkg>` → 装 debug 包 → 
`adb shell am start -n <pkg>/com.framemark.native.MainActivity --es fmImportPaths <一张 app 可读的图片路径> --es fmSelectPath <同一路径>`
（`fmImportPaths` 是 `BuildConfig.DEBUG` 才有的证据钩子），跳过新手教程。

## 验证

* `D:\dev\tmp\fm_kotlin_check.cmd`：通过。
* `D:\dev\tmp\fm_kotlin_test.cmd`：1383 条全绿（本改动前基线 1345，增加的是并行线上的新测试）。
* 测试跟着改的地方（没有靠删断言过关）：`PhoneBorderPageStateTest`
  （无边框时第二行必须是 `null`、工具条收口、「没边框 / 有边框」面板同高 88）、
  `PhoneToolTabMappingTest`（新增 `hasBorder = false` 只剩一格）、
  `PhoneEditorHeightsTest` / `PhoneEditorCompactSheetTest` / `PhoneEditorSheetHeightsTest` /
  `PhonePanelFrameTest`（sheet 204 → 148）。

## 一个不在本次改动范围内、但会盖住这一屏的问题

`shell/PhoneProjectStore.kt` 的 `adoptPaths → addPhoto(ref, inheritFrom(project))`，
而 `inheritFrom = sharedDesign ?: activeDesign ?: selectedCardId`：**在一个已经用过边框
的工程里再导一张照片，新照片会直接继承那个设计**（实测第 7 张一进来就顶着「玻璃色卡」），
于是「无边框首屏」根本不会出现 —— 这跟上面那条产品决策正面冲突。它属于数据层
（`PhoneProjectStore`）的有意行为，本次按范围约束没有动；上面的取证因此用的是
「全新无边框工程」。`pm clear` 在 MIUI 上被拒（`SecurityException: ... CLEAR_APP_USER_DATA`），
所以用 `adb uninstall` + 重装取得干净状态。
