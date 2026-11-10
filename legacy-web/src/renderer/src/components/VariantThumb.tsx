import React, { useEffect, useRef } from 'react'
import type { TemplateVariant } from '../types'
import { makeDesignFromVariant } from '../core/presets'
import { getSampleAsset } from '../core/sample'
import { renderDesign } from '../core/scene'
import { loadImage } from '../core/images'

export function VariantThumb({ variant, className = 'tpl-canvas' }: { variant: TemplateVariant; className?: string }) {
  const ref = useRef<HTMLCanvasElement>(null)
  useEffect(() => {
    if (variant.preview) return
    let cancelled = false
    void (async () => {
      try {
        const asset = await getSampleAsset()
        const design = makeDesignFromVariant(variant)
        const image = await loadImage(asset.previewUrl)
        await document.fonts.ready
        if (cancelled || !ref.current) return
        await renderDesign(ref.current, design, asset, image, image.width, image.height, { preview: true })
      } catch {
        // canvas stays blank on failure
      }
    })()
    return () => {
      cancelled = true
    }
  }, [variant])
  if (variant.renderedPreview ?? variant.preview) {
    return (
      <img
        className={className}
        src={variant.renderedPreview ?? variant.preview}
        alt=""
        loading="lazy"
        decoding="async"
      />
    )
  }
  return <canvas ref={ref} className={className} />
}
