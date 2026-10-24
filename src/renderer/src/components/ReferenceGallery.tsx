import React, { useState } from 'react'
import { ImagePlus, Images } from 'lucide-react'
import { useAppStore } from '../store'
import { variantsByCategory } from '../core/variants'
import { Modal, Segmented } from './ui'

const sampleModules = import.meta.glob('../assets/samples/*.jpg', {
  eager: true,
  query: '?url',
  import: 'default'
}) as Record<string, string>
const samples = Object.entries(sampleModules).map(([path, url]) => ({
  url,
  name: path.split('/').pop() ?? 'sample.jpg'
}))

export function ReferenceGallery({ open, onClose }: { open: boolean; onClose: () => void }) {
  const setVariant = useAppStore((s) => s.setVariant)
  const addSample = useAppStore((s) => s.addSample)
  const [tab, setTab] = useState<'templates' | 'samples'>('templates')
  const groups = variantsByCategory().filter((g) => g.items.some((v) => v.preview))

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="原版参考与示例图"
      subtitle="原版模板预览、示例照片与全部品牌 Logo 都来自你提供的 App；仅作个人参考。"
      wide
      footer={<span className="modal-note">点击原版模板会应用对应版式；点击示例图会载入为可编辑照片。</span>}
    >
      <div className="reference-toolbar">
        <Segmented
          value={tab}
          options={[
            { value: 'templates', label: '原版模板' },
            { value: 'samples', label: '示例照片' }
          ]}
          onChange={setTab}
          compact
        />
      </div>
      {tab === 'templates' ? (
        <div className="reference-groups">
          {groups.map((g) => (
            <section key={g.category}>
              <h3>{g.category}</h3>
              <div className="reference-grid">
                {g.items
                  .filter((v) => v.preview)
                  .map((v) => (
                    <button
                      key={v.id}
                      className="reference-card"
                      onClick={() => {
                        setVariant(v.id)
                        onClose()
                      }}
                      type="button"
                    >
                      <img src={v.preview} alt={v.name} loading="lazy" />
                      <span>
                        <strong>{v.name}</strong>
                        <em>{v.category}</em>
                      </span>
                    </button>
                  ))}
              </div>
            </section>
          ))}
        </div>
      ) : (
        <div className="reference-grid sample-grid">
          {samples.map((s) => (
            <button
              key={s.name}
              className="reference-card sample-card"
              onClick={() => {
                void addSample(s.url, s.name)
                onClose()
              }}
              type="button"
            >
              <img src={s.url} alt={s.name} loading="lazy" />
              <span>
                <strong>
                  <ImagePlus size={13} />
                  {s.name.replace(/\.[^.]+$/, '')}
                </strong>
                <em>点击载入</em>
              </span>
            </button>
          ))}
        </div>
      )}
      {tab === 'templates' ? (
        <div className="sample-footnote">
          <Images size={14} />
          原版预览图保存在私有 legacy 资源包里，没有混入自研版式。
        </div>
      ) : null}
    </Modal>
  )
}
