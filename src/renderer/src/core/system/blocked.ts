export interface BlockedStyle {
  id: string
  name: string
  vendor: 'oppo' | 'xiaomi' | 'vivo'
  reason: string
  count?: number
}

export const BLOCKED_STYLES: BlockedStyle[] = [
  { id: 'xiaomi-leica-5', name: '徕卡 5', vendor: 'xiaomi', reason: 'outer-border 侧栏为绝对布局，缺可用总宽度' },
  { id: 'xiaomi-leica-88', name: '徕卡 88', vendor: 'xiaomi', reason: 'with 布局含底部 218dp 容器与照片内叠加，根结构不匹配' },
  { id: 'xiaomi-leica-89', name: '徕卡 89', vendor: 'xiaomi', reason: 'outer-border 侧栏与照片内叠加混合，缺可用总宽度' },
  {
    id: 'vivo-all',
    name: 'vivo 系统水印',
    vendor: 'vivo',
    reason:
      '已接 14 套（3 Zeiss + 拍立得/邮票/色调/地理/明信片，按 PAG 定态帧几何 + 厂商 PAG 栅格美术，明信片邮戳为国风 edit.apk 矢量 res/ZS.xml 经 vivo-vector.cjs 转换；国风地点/文字/水墨印章 + 艺术签名 + 个性边框 + 功能边框 6 套，按 vivo 官方社区设置页预览卡 + edit.apk temp.json 文字顺序，福印/水墨印章为厂商 VectorDrawable 原样转换、地点印为厂商 wm_province_hebei webp，动态/用户内容元素仍省略并记录，fidelity=approx）；其余 28 套无厂商成片预览——PAG 仅提供 band 几何与装饰图（numTexts=0，文字宿主动态绘制），15 套还需设备截图确认样式归属',
    count: 28
  }
]
