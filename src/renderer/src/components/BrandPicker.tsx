import React, { useMemo, useState } from 'react'
import { Search, Sparkles } from 'lucide-react'
import { useAppStore } from '../store'
import { BRANDS, LOGO_FILES, pickVariant, variantTone } from '../core/brands'
import { Modal, Segmented } from './ui'

export function BrandPicker() {
  const open = useAppStore((s) => s.brandPickerOpen)
  const setOpen = useAppStore((s) => s.setBrandPickerOpen)
  const setLogoFromBrand = useAppStore((s) => s.setLogoFromBrand)
  const autoMatchLogo = useAppStore((s) => s.autoMatchLogo)
  const design = useAppStore((s) => s.design)
  const photos = useAppStore((s) => s.photos)
  const selectedId = useAppStore((s) => s.selectedId)
  const [query, setQuery] = useState('')
  const [group, setGroup] = useState<'all' | 'camera' | 'phone'>('all')
  const photo = photos.find((p) => p.id === selectedId) ?? photos[0]
  const dark = ['ink', 'black'].includes(String(design.params.theme))
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    return BRANDS.filter((brand) => {
      if (group !== 'all' && brand.group !== group) return false
      return !q || `${brand.label} ${brand.cn} ${brand.files.join(' ')}`.toLowerCase().includes(q)
    })
  }, [group, query])

  const choose = (file: string | null) => {
    if (!file) return
    void setLogoFromBrand(file)
    setOpen(false)
  }

  return (
    <Modal
      open={open}
      onClose={() => setOpen(false)}
      title="品牌 Logo 库"
      subtitle={photo ? `当前照片：${photo.exif.make || '未知品牌'} ${photo.exif.model || ''}` : '添加照片后可按 EXIF 自动匹配'}
      wide
      footer={<span className="modal-note">{BRANDS.length} 个品牌 · 点击品牌套用适配深浅的版本，或点下方色标选具体版本。停产老牌优先使用公开矢量字标或原厂说明书/资料扫描件复原；暂无可靠字标者暂不列出。</span>}
    >
      <div className="brand-toolbar">
        <div className="gallery-search">
          <Search size={15} />
          <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="搜索品牌，如 Contax / 美能达 / 柯达…" />
        </div>
        <Segmented
          value={group}
          options={[
            { value: 'all', label: '全部' },
            { value: 'camera', label: '相机' },
            { value: 'phone', label: '手机' }
          ]}
          onChange={setGroup}
          compact
        />
        <button className="btn btn-ghost btn-sm" onClick={() => void autoMatchLogo()} type="button">
          <Sparkles size={14} />
          自动匹配
        </button>
      </div>
      <div className="brand-grid">
        {filtered.map((brand) => {
          const active = Boolean(design.logo && brand.files.includes(design.logo.name))
          const preview = brand.files.find((f) => /_black$/.test(f)) ?? brand.files.find((f) => !/_white$/.test(f)) ?? brand.files[0]
          return (
            <div key={brand.key} className={`brand-card ${active ? 'active' : ''}`}>
              <button
                className="brand-tile"
                onClick={() => choose(pickVariant(brand.key, dark ? 'dark' : 'light'))}
                type="button"
                title={`${brand.label} · 套用${dark ? '白色' : '黑色'}版本`}
              >
                <span className="brand-logo-box light">
                  <img src={LOGO_FILES[preview]} alt={brand.label} loading="lazy" />
                </span>
                <span className="brand-tile-meta">
                  <strong>{brand.label}</strong>
                  <em>
                    {brand.cn} · {brand.files.length} 款
                  </em>
                </span>
              </button>
              <div className="brand-tones">
                {brand.files.map((file) => (
                  <button
                    key={file}
                    className={`brand-tone ${design.logo?.name === file ? 'active' : ''}`}
                    onClick={() => choose(file)}
                    type="button"
                    title={`${brand.label} · ${variantTone(file)}`}
                  >
                    {variantTone(file)}
                  </button>
                ))}
              </div>
            </div>
          )
        })}
      </div>
      {!filtered.length ? <div className="empty-note">没有匹配的 Logo。</div> : null}
    </Modal>
  )
}
