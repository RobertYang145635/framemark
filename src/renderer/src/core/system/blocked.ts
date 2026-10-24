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
  { id: 'vivo-all', name: 'vivo 系统水印', vendor: 'vivo', reason: '已接 3 套 Zeiss；其余 39 套缺宿主布局/参考帧', count: 39 }
]
