const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const sharp = require('sharp')
const {
  Document, Packer, Paragraph, TextRun, HeadingLevel, AlignmentType, Table, TableRow, TableCell,
  WidthType, ShadingType, BorderStyle, ImageRun, Footer, PageNumber, PageBreak
} = require('docx')

const root = path.resolve(__dirname, '..')
const out = 'D:/border&watermark/交付/FrameMark-使用与验收说明.docx'
const docs = 'D:/border&watermark/_research/docs'
const apk = path.join(root, 'android/app/build/outputs/apk/release/app-release.apk')
const zip = path.join(root, 'release/FrameMark-Windows-x64.zip')
const sha = (p) => crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex')
const fmt = (n) => (n / 1048576).toFixed(1) + ' MB'
const FONT = 'Microsoft YaHei'
const GOLD = 'B08D57'
const CREAM = 'F7F3EA'
const DARK = '242320'

function text(t, opts = {}) {
  return new TextRun({ text: t, font: FONT, size: opts.size ?? 21, bold: opts.bold, color: opts.color, italics: opts.italics })
}
function p(t, opts = {}) {
  return new Paragraph({ spacing: { after: opts.after ?? 110, before: opts.before ?? 0 }, alignment: opts.align, children: Array.isArray(t) ? t : [text(t, opts)] })
}
function heading(t, level = 1) {
  return new Paragraph({ heading: level === 1 ? HeadingLevel.HEADING_1 : level === 2 ? HeadingLevel.HEADING_2 : HeadingLevel.HEADING_3, spacing: { before: level === 1 ? 280 : 180, after: 100 }, children: [text(t, { size: level === 1 ? 30 : level === 2 ? 25 : 22, bold: true, color: DARK })] })
}
function bullet(t) {
  return new Paragraph({ bullet: { level: 0 }, spacing: { after: 60 }, children: [text(t, { size: 20 })] })
}
function cell(t, header = false, width) {
  return new TableCell({
    width: width ? { size: width, type: WidthType.DXA } : undefined,
    shading: header ? { type: ShadingType.CLEAR, fill: 'E8DFCF' } : undefined,
    margins: { top: 80, bottom: 80, left: 90, right: 90 },
    children: String(t).split(String.fromCharCode(10)).map((line) => new Paragraph({ spacing: { after: 0 }, children: [text(line, { bold: header, size: 18, color: header ? '4A3B2A' : undefined })] }))
  })
}
function table(headers, rows, widths) {
  return new Table({
    width: { size: 100, type: WidthType.PERCENTAGE },
    borders: {
      top: { style: BorderStyle.SINGLE, size: 4, color: 'D8CDBA' }, bottom: { style: BorderStyle.SINGLE, size: 4, color: 'D8CDBA' },
      left: { style: BorderStyle.SINGLE, size: 4, color: 'D8CDBA' }, right: { style: BorderStyle.SINGLE, size: 4, color: 'D8CDBA' },
      insideHorizontal: { style: BorderStyle.SINGLE, size: 2, color: 'E6DDCF' }, insideVertical: { style: BorderStyle.SINGLE, size: 2, color: 'E6DDCF' }
    },
    rows: [
      new TableRow({ tableHeader: true, children: headers.map((h, i) => cell(h, true, widths?.[i])) }),
      ...rows.map((r) => new TableRow({ children: r.map((c, i) => cell(c, false, widths?.[i])) }))
    ]
  })
}
function note(t) {
  return new Paragraph({
    spacing: { before: 80, after: 140 },
    shading: { type: ShadingType.CLEAR, fill: CREAM },
    border: { left: { style: BorderStyle.SINGLE, size: 12, color: GOLD } },
    indent: { left: 160, right: 160 },
    children: [text(t, { size: 19, color: '5B5144' })]
  })
}
async function image(pimg, width) {
  const meta = await sharp(pimg).metadata()
  const height = Math.round((width * meta.height) / meta.width)
  return new Paragraph({ alignment: AlignmentType.CENTER, spacing: { before: 80, after: 40 }, children: [new ImageRun({ data: fs.readFileSync(pimg), type: 'jpg', transformation: { width, height } })] })
}
function caption(t) { return new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 160 }, children: [text(t, { size: 17, color: '7A7166' })] }) }
function spacer() { return new Paragraph({ spacing: { after: 60 }, children: [] }) }

async function main() {
  const apkBytes = fs.statSync(apk).size
  const zipBytes = fs.statSync(zip).size
  const apkSha = sha(apk)
  const zipSha = sha(zip)
  const children = []
  children.push(new Paragraph({ spacing: { before: 500, after: 80 }, alignment: AlignmentType.CENTER, children: [text('框 印  F r a m e M a r k', { size: 44, bold: true, color: DARK })] }))
  children.push(new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 100 }, children: [text('边框 · 透明 PNG 水印 · 预设 · 批量', { size: 24, color: GOLD })] }))
  children.push(new Paragraph({ alignment: AlignmentType.CENTER, spacing: { after: 320 }, children: [text('Windows 桌面版 + Android 版 交付与验收说明', { size: 22, color: '5C554A' })] }))
  children.push(table(['项目', '内容'], [
    ['版本', 'FrameMark 1.0.0'],
    ['交付日期', '2026-10-27'],
    ['桌面平台', 'Windows 10/11 x64 便携版（未签名）'],
    ['Android', 'Android 10+ (minSdk 29)，targetSdk 34，arm64 设备安装通过；PGEM10 覆盖安装数据保留（装机 base.apk md5 与本地 APK 一致）；三次 force-stop 冷启动 244/249/236 ms、全部 LaunchState COLD，首屏即新的「导入照片」页；状态栏改为浅色底 + 深色图标（与界面同色 #F0F2F5），真机导出 1 张 JPG 成功写入 Pictures/FrameMark'],
    ['渲染方式', 'Canvas 2D 本地渲染；桌面 sharp 编码与 EXIF 回写；Android MediaStore 相册写入'],
    ['离线', '不接入云服务，不上传照片、水印或预设']
  ], [1800, 7200]))
  children.push(spacer())
  children.push(note('版权提醒：内置相机品牌 Logo、参考图与部分模板风格来自个人研究和原版 App 素材；商标与原始素材版权归各权利人所有。请仅用于个人照片处理，不要将内置品牌素材用于商业分发。'))
  children.push(new Paragraph({ children: [new PageBreak()] }))

  children.push(heading('一、交付结论', 1))
  children.push(p('本版本已经把「加边框」和「加自定义水印」合并进同一个本地工作流：应用以「照片优先」启动——没有照片时显示全屏「导入照片」页（选择照片 / 使用示例照片），导入后进入工作区；边框库位于左栏（7 个互斥分类、175 卡：厂商边框 99 / 文字边框 21 / 印象边框 13 / 白边边框 12 / 卡片边框 19 / 色彩边框 6 / 简约边框 5），也可以从「更换」弹窗选择。右侧 Inspector 只有「边框」和「水印」两个工具，「边框」内分 Logo / 文字 / 布局 / 主题 四个编辑页签。可以从照片自动读取 EXIF，叠加任意透明 PNG/WebP 水印（新增画布直拖落点层），保存为预设，并通过新的「导出」对话框导出当前照片或全部 N 张。Windows 与 Android 共享同一套边框、水印和预设数据，桌面端负责写文件夹与完整格式编码，Android 端负责系统照片选择、相册写入与系统分享。'))
  children.push(p('调研结论是没有找到同时覆盖全部需求的开源项目：最接近的 time-letter 与 Qing-Fram-Shadow-web 都是 Windows Electron 项目且偏文字/Logo 水印；iOS 的 PICA、Reframe、Stamio、MatteFrame 不覆盖 Windows/Android 双端；PolarGrid 偏白边与拼贴；其余水印项目是 CLI 或脚本。详细对比见交付目录中的《FrameMark-同类开源项目调研.md》。'))
  children.push(p('需要明确的边界：JPG 叠加水印后不可能做到数学意义上的无损；本版本采用最高质量重编码并回写 EXIF/ICC。真正的无损导出使用 PNG / 桌面 TIFF / 桌面 WebP。'))
  children.push(p('本轮界面整体重做为 OPCameraPro 风格的浅色设计（HyperOS / Miuix 语言）：统一浅灰底、白卡片、蓝色强调色，覆盖导入页、顶栏、边框库、画布、照片条、Inspector 四个页签、Logo 库、水印面板、预设 / 导出 / 失败对话框、手机底部胶囊栏与全屏边框库，并同步 Android 状态栏与导航栏的浅色底 + 深色图标。这次只换视觉层：边框渲染、导出与预设数据未改动。'))

  children.push(heading('二、功能清单', 1))
  children.push(table(['能力', '实现情况'], [
    ['边框模板', '175 卡、7 个互斥分类：厂商边框 99（OPPO 47 / 小米 40 / vivo 12）、文字边框 21、印象边框 13、白边边框 12、卡片边框 19、色彩边框 6、简约边框 5；手写版式覆盖背景模糊、水印边框、留白、卡片、玻璃、色卡、电影字幕、胶片齿孔、拍立得等；175 张卡片常驻 DOM，左栏浏览或从「更换」弹窗选择'],
    ['厂商边框', '运行时 stock 样式 106 套按 7 类陈列：厂商边框 99（OPPO 47 / 小米 40 / vivo 12）+ 文字 4 + 卡片 1 + 色彩 1 + 简约 1；另有 4 组 blocked（小米 Leica 5/88/89 与 vivo 剩余 28 套）保持不可导出、不进入 175 卡边框库'],
    ['厂商边框水印位置', '版式参数内新增九宫格锚点（与自定义水印面板共用同一控件），默认「厂商原版」保持厂商位置并可一键复位；选择锚点后整组水印（含旋转条带）作为刚性整体平移到照片画布对应位置并限幅不出界，另有水平/垂直微调'],
    ['文字编辑', '手写卡「文字」页按画面位置直编：渲染时捕获每条文字的真实位置，按九宫格方位列出，每行可直接改字 / 隐藏（行级覆盖，不改照片）；照片信息只显示来源标记、不暴露品牌/机型/日期等字段；字号缩放保留'],
    ['手写逐卡参数', '10 张零控件卡接入 47 条经核实的参数（45 个面板项 + showDate 复用文字页日期行）：比例衬边/模糊方图/电影画幅/票据配色与存根文字/色卡漫步标题时间配色/颜色显现取色点/视频封面画幅；按卡保存'],
    ['EXIF', '自动读取品牌、机型、镜头、焦距（优先 35mm 等效）、光圈、快门、ISO、日期；支持手动覆盖；桌面/Android JPG 回写，等效焦距只写 0xA405 不污染物理焦距'],
    ['品牌 Logo', '版本库采集 63 品牌 / 268 版本 / 330 个切图，人工审定 218 个版本；应用内呈现 61 个展示品牌组（LUMIX → Panasonic、LUMO → OPPO 并入），「品牌 LOGO」区支持自动匹配 / 更换 / 清除 / 显示 / 大小；黑标只用于浅色底、白标只用于深色底，不兼容当前底的版本显示「已选 · 当前不显示」并给出警告，不自动替换或删除'],
    ['透明水印', '支持 PNG/WebP；照片内 / 整张画布；九宫格锚点；水平/垂直偏移；宽度、透明度、旋转；平铺与间距；新增画布落点层：可直接拖动水印、九个锚点、键盘微调，写回的 X/Y 偏移与滑杆一致'],
    ['预设', '保存当前模板、参数、Logo、水印和导出设置；保存到本地；跨设备 JSON 导出/导入（含水印 data URL）；入口保持在顶栏'],
    ['批量', '导出对话框可选范围（当前照片 / 全部 N 张）；多张照片逐个导出、逐张进度、取消；文件名变量 {name} {template} {preset} {date} {time} {index} {make} {model} {width} {height}'],
    ['桌面导出', '导出对话框：范围（当前照片 / 全部 N 张）、格式 JPG / PNG / WebP / TIFF、JPG 质量、长边限制（原始 / 4096 / 3072 / 2048 或手填）、输出文件夹（可选，默认 图片\\FrameMark）、移除 GPS；JPG 质量 100 + 4:4:4 + 原 EXIF，PNG / WebP 无损，TIFF LZW 无损'],
    ['Android 导出', '导出对话框在 Android 提供 JPG、PNG、WebP（无 TIFF）；写入系统相册 Pictures/FrameMark；JPG 回写 EXIF，PNG/WebP 不写 EXIF'],
    ['Android 分享', '单张导出完成后可直接打开系统分享面板，分享 MediaStore 中的成片'],
    ['移动端适配', '竖屏 411×905 CSS px 实测；状态栏/底部手势区安全区；底部仅「边框 / 水印」两个 48px 工具按钮；编辑器弹层保留 Logo / 文字 / 布局 / 主题 四个页签；边框库全屏打开；Android 状态栏不覆盖（WebView 从状态栏下方开始，状态栏与界面同为浅色 #F0F2F5、深色图标）']
  ], [1800, 7200]))

  children.push(heading('二·附：厂商边框（系统相机水印）目录', 2))
  children.push(p('厂商边框分类现包含 99 卡：OPPO 47、小米 40、vivo 12（vivo 的色调·右侧栏与蔡司·内嵌分别归入色彩边框与简约边框，Polaroid PLD 归入卡片边框）。另有 4 组 blocked：小米 3 套侧栏（Leica 5/88/89）与 vivo 28 套（合并为一组），不可导出、不进入 175 卡边框库。GR5 右侧参数边栏已接入，滤镜值不在 EXIF 时以“—”占位；运行时 106 套库存（stock）样式全部标记 approx，verified 为 0。vivo 14 套已接入 / 28 套仍 blocked；国风三套（地点/文字/水墨）的美术由厂商 VectorDrawable 原样转换，导出结果互不相同（4:3 md5 b79cfbd9… / 2655526c… / 75b57dbe…）。'))
  children.push(p('本轮厂商对齐（W3）：OPPO Gallery 同步至 ColorOS 17.10.7，修正 13 处过时配置、新增 5 套 realme 样式、下线 1 套已退役样式（personalize_brand_3）；小米同步至 17 Ultra，film 15 与 Leica 2/91 更新，standard 6/7/8/107 迁移入生成管线。厂商边框分类中的品牌字标不再固定为厂商名：字标现随照片 EXIF 品牌（或 Inspector 中手动选择的“品牌 LOGO”）自动切换，例如用松下 DC-S1 样张套用小米徕卡版式时，字标显示 Panasonic 而非 xiaomi；同轮修复 leica-111 字标散落与 leica-92 焦距行重力（focal-line gravity）两处厂商版式缺陷。本轮新增：厂商边框卡片的水印位置支持九宫格锚点选择（与自定义水印共用同一控件），默认「厂商原版」，选中锚点后整组水印刚性平移、旋转条带不做轴交换，且始终限幅在画布内。'))
  children.push(note('blocked 不是“已完成”：它们缺宿主布局、滤镜元数据或可靠参考帧。vivo 的 PAG 已能渲染，batch-1 的 5 套按 PAG 定态帧几何 + 厂商 PAG 栅格美术接入；batch-2 的 6 套无 PAG 组，改按 vivo 官方社区设置页预览卡 + edit.apk temp.json 文字顺序接入，fidelity 一律 approx。本轮新增能力：厂商 Android VectorDrawable（edit.apk res/*.xml）可经 scripts/system-camera/adapters/vivo-vector.cjs 解码为路径数据并原样绘制（国风福印/水墨印章、明信片邮戳），地点印直接采用厂商 wm_province_hebei webp；无法转换的动态/用户内容元素仍按规则省略并记录。'))

  children.push(heading('三、格式与“无损”边界', 1))
  children.push(table(['格式', 'Windows 桌面', 'Android', '说明'], [
    ['JPG / JPEG', '质量 100 + 4:4:4 + sRGB ICC；原 EXIF 回写，方向归正，GPS 默认移除', '质量 100；EXIF + ICC 回写', '叠加边框/水印后必然发生像素改动；这里的“无损”指最大程度保留画质和元数据，不是数学无损'],
    ['PNG', '真无损，sRGB ICC', '真无损，不写 EXIF', '适合透明/图形/需要二次编辑的成片'],
    ['TIFF', 'LZW 压缩真无损，sRGB ICC，不写 EXIF', '不支持', '桌面归档用；文件较大'],
    ['WebP', '真无损（sharp lossless）', '最高质量有损（Canvas 编码限制）', 'Android 端不宣称无损；UI 已明确提示'],
    ['HEIC/HEIF', '可读，依赖 sharp/libvips 解码', '可读，依赖系统 WebView 解码', '不是导出格式；不同机型兼容性可能不同']
  ], [1250, 2900, 2000, 2850]))
  children.push(note('元数据策略：桌面 JPG 从原文件读取 EXIF，保留相机、镜头、曝光、时间等字段，把尺寸和方向更新为成片值；默认清空 GPS。Android JPG 使用 piexifjs 在前端写回 EXIF；Android 的 PNG/WebP 编码器不提供 EXIF 写入通道。'))

  children.push(heading('四、实测与验收', 1))
  children.push(heading('4.1 Windows 桌面版', 2))
  children.push(table(['验收项', '环境', '结果'], [
    ['模板库与冷启动（v4 复测）', 'Windows 打包版 exe', '复测脚本 _research/v4-delivery/verify-packaged-v4.mjs 全 PASS（25 项断言）：导入页首屏、175 卡、七类、OPPO 47 · 小米 40 · vivo 12、编辑器四页签、边框/水印工具、去重主题、61 品牌组、Panasonic 版本页、水印落点层 1+9、导出对话框 4 格式、手机底栏 2×48px、手机边框库弹窗、零 pageerror、静态预览、文字按方位直编、每类 8 张 + 厂商系列、手写 10 卡 47 项面板（比例 01 实测 14 项）（截图 _research/v4-delivery/packaged-*.png）'],
    ['全库目检（4:3，历史）', 'Windows 打包版 / 高分辨率主画布', '175 卡（系统相机 stock 107 + 手写 68）逐张出图；已修 OPPO EXIF 部件重复、侧栏裁切、文字重叠、7 处图片 fill 拉伸与卡片 canvas 拉伸'],
    ['多比例全量目检', 'Windows 打包版 / 7 种比例', '158 套（新增 2 套待补）× 3:2 / 2:3 / 16:9 / 1:1 / 4:5 / 3:4 / 9:16 共 1106 张，0 页面错误；无拉伸、裁切、重叠；固定版式按设计比例出图'],
    ['原版模板 parity 审计（W1）', 'Node + sharp 像素级测量', '47/47 原版模板通过 ≤2% 结构差 / ≤2 百分点 像素差门禁：13 套本轮独立复测，34 套与已审计批次字节级一致'],
    ['渲染与状态修复（本轮）', '源码构建 + 打包版 Playwright 复核', '修复文字/图形重叠与 realme 条带版式；恢复 wm-black 镜头行并按内容去重 EXIF；切换模板不再残留上一模板的文字开关、模糊日期改用拍摄日期；175 卡保真重导出（exports-real）0 项真实变形/重叠缺陷；打包版复核 175 卡 / 7 类 / 61 品牌组、0 pageerror'],
    ['品牌字标与厂商版式（本轮）', 'Windows 打包版 Playwright + 真机冷启动', 'DC-S1 样张下 leica-91/111 字标均为 Panasonic（非 xiaomi），leica-111 版式无散落；leica-92 焦距行重力修复后全库文字对文字绘制重叠仅余 1 处（xpan_1 厂商负行距，字形可读）；打包版复核 175 卡 / 7 类 / 61 品牌组、0 pageerror；真机覆盖安装冷启动 COLD 395/280/238 ms（3 次 force-stop）'],
    ['全流程体验审计（本轮）', 'Windows 构建 / 1440 与 418 窄屏', '基线 18 步、复审 19 步均 0 失败；修复导出失败的原始 IPC 文案（改为“输出文件夹不可用…”）与模态过透；P2 登记：无效目录残留、长边双入口、取消导出不列文件'],
    ['界面视觉（本轮）', 'Windows 构建 / 1440 与 418 窄屏 / Android 真机', '全界面浅色 HyperOS 风格重做（导入页、顶栏、边框库、画布、照片条、Inspector、Logo 库、水印面板、对话框、手机胶囊栏）；19 步体验审计 0 失败、workflow 198/198；同构建仅换 CSS 的 6 卡真实导出 6/6 逐字节相同（渲染未受影响）；打包版 25/25；真机截图 _research/v4-delivery/fm-skin-*.png'],
    ['核心工作流回归', '打包版 Electron + Playwright', 'npm run test:workflow：198/198 断言、failed 0、defects 0、pageerror/console.error 0（隔离 --user-data-dir 连续两轮一致；覆盖 2 张批量、预设保存/重载/应用、自定义 PNG 水印、高级参数、文件名变量、JPG/PNG/WebP/TIFF、取消导出、预设 JSON 导入导出）；预设内 outputDir 跨机不互通问题已修复'],
    ['透明水印（单张 + 平铺）', 'Windows 打包版', '白色 PNG 叠加成功；平铺、透明度、宽度、九宫格位置均生效'],
    ['预设闭环', 'Windows 打包版', '保存 → 导出 JSON → 删除 → 导入 → 应用，水印与模板均恢复'],
    ['批量 JPG', 'Windows 打包版', '21MP+24MP+19MP 共 3 张，7.3 秒完成，全部写入 Pictures/FrameMark'],
    ['TIFF 无损', 'Windows 打包版', '24MP 单张 2.1 秒，LZW 压缩，尺寸 6000×4666，ICC 588 B'],
    ['桌面安装包冒烟', '解压后的 FrameMark.exe', '窗口、预加载、导出、批量均正常；页面错误 0']
  ], [2100, 2300, 4600]))
  children.push(await image(path.join(docs, 'windows-app.jpg'), 620))
  children.push(caption('Windows 打包版运行截图：经典双行模板 + 自动品牌 Logo + EXIF 参数'))
  children.push(await image(path.join(docs, 'desktop-wm.jpg'), 620))
  children.push(caption('桌面版透明 PNG 水印：右下角叠加，宽度 18%，不透明度 0.88'))
  children.push(heading('4.2 Android 真机', 2))
  children.push(p('测试设备：OPPO Find X6 Pro（PGEM10），Android 16 / SDK 36，WebView 155.0.8059.43，屏幕 1440×3168，DPR 3.5。所有时间均为点击“导出”到界面显示“导出完成”的墙钟时间，受温度、后台负载影响。'))
  children.push(table(['场景', '尺寸 / 输出', '耗时', '结果'], [
    ['单张 JPG', '5784×4297 / 21.07 MB', '17.4 s', '成功，EXIF 完整，方向 1，ICC 456 B'],
    ['单张 JPG', '6000×4666 / 11.61 MB', '15.7 s', '成功，EXIF 完整（含 100-400mm 镜头）'],
    ['单张 PNG', '6000×4666 / 22.54 MB', '16.8 s', '成功，无损，无 EXIF'],
    ['单张 WebP', '6000×4666 / 15.20 MB', '8.8 s', '成功，最高质量有损，ICC 456 B'],
    ['批量 JPG（3 张）', '21MP + 24MP + 19MP', '43.6 s', '历史实测（上一构建）；最终包按 2 张批量复验通过，见下行'],
    ['批量取消', '3 张 JPG', '取消后当前张完成即停止', '取消标志生效；已修复完成页把总张数误显示为成功张数的问题'],
    ['发版包', '签名 APK / 系统选择器', '—', '安装、启动、选图、导出到相册均成功；Debug 测试素材未进入 Release'],
    ['冷启动与图库（v4 复验）', '签名 APK / PGEM10', '—', '175 卡 / 7 类（厂商边框 99：OPPO 47 · 小米 40 · vivo 12）；0 页面错误'],
    ['2 张批量导出（W4 复验）', '签名 APK / PGEM10', '—', '逐张进度与完成页正确，成片写入 Pictures/FrameMark'],
    ['自定义水印（W4 复验）', '签名 APK / 透明 PNG', '—', '自定义 PNG 水印直接绘制到画布并随成片写入相册'],
    ['新厂商样式（W4 复验）', 'realme / 小米 standard 新样式', '—', '新样式正常渲染，卡片带“近似”徽章'],
    ['导出→分享（W4 复验）', '签名 APK / 系统分享面板', '—', '单张导出完成后点“分享”拉起 Android 系统选择器成功'],
    ['最终交付包冷启动（本轮复核）', '签名 APK / PGEM10', '244 / 249 / 236 ms', '三次 force-stop，全部 LaunchState COLD；首屏即「导入照片」页；状态栏浅底深图标；覆盖安装未清数据（装机 base.apk md5 与本地一致）；真机导出 1 张 JPG 成功写入 Pictures/FrameMark'],
    ['移动端弹窗与快捷条', 'Android 发版包', '—', '已修复模板库全屏弹窗标题/关闭按钮被状态栏压住；当前模板会自动补入快捷条并居中']
  ], [2000, 2500, 1800, 2700]))
  children.push(p('注：表内单张/批量的尺寸与耗时为上一构建的历史实测，仅作量级参考；W4 真机走查（2 张批量导出、自定义水印、新厂商样式渲染与导出后系统分享）作为历史证据保留；本轮 v4 交付包的真机复验为覆盖安装（数据保留，装机 base.apk md5 与本地一致）、三次冷启动 244/249/236 ms（全部 LaunchState COLD，首屏「导入照片」页）与 1 张 JPG 导出落盘。', { size: 19, color: '5B5144' }))
  children.push(await image(path.join(docs, 'android-wm.jpg'), 260))
  children.push(caption('Android 发版界面：浅色 HyperOS 风格工作区与底部「边框 / 水印」胶囊栏；状态栏不覆盖、浅底深色图标'))
  children.push(await image(path.join(docs, 'android-release.jpg'), 260))
  children.push(caption('Android 签名版：边框库全屏弹窗（浅色主题），状态栏安全区已适配，标题与关闭按钮不被遮挡'))

  children.push(new Paragraph({ children: [new PageBreak()] }))
  children.push(heading('五、已知限制', 1))
  children.push(bullet('JPG 无法数学无损：边框/水印是像素合成，必须重新编码；本版本用质量 100、4:4:4、ICC 与 EXIF 回写把损失压到最低。'))
  children.push(bullet('Android 的 PNG/WebP 不写 EXIF；WebP 来自 Canvas 有损编码，UI 已提示，不标“无损”。Android 暂不支持 TIFF。'))
  children.push(bullet('批量取消在当前照片完成后生效：编码/写入过程没有中断 API，取消不会立即掐断正在进行的单张；不会继续后续照片。'))
  children.push(bullet('24MP 以上照片导出会占用较多内存；建议单张处理或分批处理。真机 24MP 已验证，但低内存设备可能出现 WebView 被系统回收。'))
  children.push(bullet('Windows 便携包未做代码签名，SmartScreen 会提示；选择“更多信息 → 仍要运行”。'))
  children.push(bullet('内置品牌 Logo 与部分模板审美来源受第三方版权约束，仅供个人使用；商业分发请替换为自有素材。'))
  children.push(bullet('HEIC/HEIF 与 TIFF 的读取依赖平台解码器，极端编码可能无法预览。'))
  children.push(bullet('预设 JSON 包含水印图片的 data URL；水印过大时文件会变大，这是跨设备复现的代价。'))
  children.push(bullet('字体回退：部分模板因本机字体缺失发生替换（如 Chakra Petch 以横向缩放近似呈现；个别模板使用 Bebas/YouShe 替代原字体），字距与字重与原版存在可见差异。'))
  children.push(bullet('品牌字标视觉差异：parity 审计 fixture 为松下 DC-S1 样张，而参考截图多来自 Canon/Leica/SONY 机型，品牌区 artwork 无法逐像素对齐，属参考源限制而非渲染缺陷。'))
  children.push(bullet('106 套系统相机库存样式全部标记 approx，verified 为 0；vivo 尚有 28 套保持 blocked（其中 11 套无任何公开参考），全部 vivo 样式为 approx、verified 为 0。'))
  children.push(bullet('手写族约 927 条控件仍未逐条人工验收（比厂商族 439 条还多，疑为机械展开，待对账）；OPPO 相册哈苏档等少数厂商细节仍按 approx 标注。'))
  children.push(bullet('本项目为个人使用交付：未做任何公开发布、许可证选型或商用授权工作。'))
  children.push(bullet('界面统一为浅色 HyperOS 风格：本轮不提供深色模式，系统切成深色时应用仍显示浅色界面；模板缩略图内的深色“衬底”是模板自身的画布边缘，不属于主题。'))

  children.push(heading('六、安装与使用', 1))
  children.push(heading('Windows', 2))
  children.push(p('解压 FrameMark-Windows-x64.zip，运行 FrameMark.exe。首次启动是无照片的「导入照片」页：点「选择照片」导入，或先用「使用示例照片」试试。进入工作区后，左侧是边框库（7 类 175 卡，厂商边框可按 OPPO / 小米 / vivo 筛选，也可以从顶栏「更换」弹窗打开）；右侧 Inspector 先选「边框」或「水印」工具：「边框」分 Logo / 文字 / 布局 / 主题 四个页签，水印可切到画布落点层直接拖动。导出时点顶栏「导出当前」，或在照片条点「批量导出」打开导出对话框：范围、格式、JPG 质量、长边限制、文件名变量、输出文件夹（默认「图片\\FrameMark」）与移除 GPS 都在这里设置。'))
  children.push(heading('Android', 2))
  children.push(p('安装 FrameMark-Android-v1.0.0.apk（允许安装未知来源应用）。首次启动同样是「导入照片」页：点「选择照片」打开系统照片选择器，或「使用示例照片」。底部只有「边框 / 水印」两个 48px 按钮，边框库全屏打开；编辑器弹层保留 Logo / 文字 / 布局 / 主题 四个页签。导出走与桌面相同的导出对话框（Android 无 TIFF 格式），成片保存到系统相册的 Pictures/FrameMark，可在相册的 FrameMark 相簿查看。'))
  children.push(heading('预设跨设备', 2))
  children.push(p('在预设对话框点“导出预设”，Windows 保存到 Downloads\\FrameMark，Android 保存到 Download/FrameMark。把 JSON 传到另一台设备后，打开预设对话框点“导入预设”，选择该 JSON；水印图片与 Logo 会随预设一起迁移。'))

  children.push(heading('七、文件清单、签名与校验', 1))
  children.push(table(['文件', '大小', 'SHA-256'], [
    ['FrameMark-Windows-x64.zip', fmt(zipBytes), zipSha.slice(0, 32) + String.fromCharCode(10) + zipSha.slice(32)],
    ['FrameMark-Android-v1.0.0.apk', fmt(apkBytes), apkSha.slice(0, 32) + String.fromCharCode(10) + apkSha.slice(32)]
  ], [3000, 1400, 4600]))
  children.push(p('Windows 便携包解压后主程序：FrameMark-win32-x64\\FrameMark.exe。Android APK 已用项目内 release keystore 签名；证书 DN 为 CN=FrameMark, OU=Personal, O=FrameMark, L=Chengdu, ST=Sichuan, C=CN，证书 SHA-256：a3f8e1d9b22b92cc687c85cc4648f92678a01a50d99fe249cdedd2a7422c873f。'))
  children.push(p('签名文件：android/keystore/framemark-release.jks；口令与别名：android/keystore.properties。后续升级必须继续使用同一把钥匙，否则 Android 会拒绝覆盖安装。该文件只在本机保存，不要上传或公开。'))
  children.push(note('更新 Android 版本时，修改 android/app/build.gradle 的 versionCode/versionName 后重新运行 Gradle assembleRelease；保留 keystore 与 properties。'))

  children.push(new Paragraph({ children: [new PageBreak()] }))
  children.push(heading('附录 A：175 个模板总览', 1))
  children.push(p('当前模板库共 175 卡，按 7 个互斥分类陈列：厂商边框 99 / 文字边框 21 / 印象边框 13 / 白边边框 12 / 卡片边框 19 / 色彩边框 6 / 简约边框 5（其中运行时 stock 库存样式 106 套，保持 approx）。下图为上一轮 160 卡构建渲染的联系表（渲染预览 73 张 + 系统静态预览 87 张），用于总览版式分布；该联系表早于本轮 7 类边框库构建，未包含 realme、小米 standard 等样式与 blocked 标注，样式明细以应用内边框库及其当前预览为准。'))
  children.push(await image(path.join(docs, 'all-templates-160.jpg'), 500))
  children.push(caption('模板预览联系表（上一轮 160 卡渲染：预览 73 张 + 系统静态 87 张；早于本轮 7 类边框库构建，当前为 175 卡，见应用内预览）'))
  children.push(p('水印测试覆盖：白色水印、九宫格右下、宽度 18%、不透明度 0.88；平铺模式宽度 9%、间距 5%、不透明度 0.30。桌面与 Android 均生成成片；桌面平铺成片已保存到 _research/exports/desktop-watermark-tile.jpg。'))

  const doc = new Document({
    styles: {
      default: { document: { run: { font: FONT, size: 21, color: '2B2824' }, paragraph: { spacing: { line: 320 } } } },
      heading1: { run: { font: FONT, size: 30, bold: true, color: DARK }, paragraph: { spacing: { before: 280, after: 100 } } },
      heading2: { run: { font: FONT, size: 25, bold: true, color: DARK } },
      heading3: { run: { font: FONT, size: 22, bold: true, color: DARK } }
    },
    sections: [{
      properties: { page: { size: { width: 11906, height: 16838 }, margin: { top: 1134, right: 1134, bottom: 1134, left: 1134 } } },
      footers: { default: new Footer({ children: [new Paragraph({ alignment: AlignmentType.CENTER, children: [text('框印 FrameMark 1.0.0 · 交付说明 · ', { size: 16, color: '8A8175' }), new TextRun({ children: [PageNumber.CURRENT], font: FONT, size: 16, color: '8A8175' })] })] }) },
      children
    }]
  })
  fs.mkdirSync(path.dirname(out), { recursive: true })
  fs.writeFileSync(out, await Packer.toBuffer(doc))
  console.log(out, fs.statSync(out).size)
}
main().catch((e) => { console.error(e); process.exit(1) })
