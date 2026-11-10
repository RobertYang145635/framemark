const fs = require('node:fs')
const file = 'legacy-web/src/renderer/src/styles.css'
const css = fs.readFileSync(file, 'utf8')
const marker = '@media (max-width: 700px) {'
const start = css.indexOf(marker)
if (start < 0) throw new Error('mobile block not found')
const block = `@media (max-width: 700px) {
  html,
  body,
  #root {
    height: 100%;
  }
  body {
    overscroll-behavior: none;
  }
  .app-shell {
    height: 100dvh;
    min-height: 0;
    padding-bottom: 0;
    overflow: hidden;
  }
  .topbar {
    height: calc(54px + env(safe-area-inset-top));
    flex: 0 0 calc(54px + env(safe-area-inset-top));
    padding: env(safe-area-inset-top) 9px 0;
    gap: 7px;
  }
  .brand {
    flex: 1;
    min-width: 0;
    gap: 7px;
  }
  .brand-text strong {
    font-size: 16px;
  }
  .brand-text em {
    display: none;
  }
  .hide-mobile {
    display: none !important;
  }
  .brand-mark {
    width: 31px;
    height: 31px;
    border-radius: 9px;
  }
  .brand-mark svg {
    width: 21px;
    height: 21px;
  }
  .topbar-actions {
    gap: 4px;
  }
  .topbar-actions .icon-btn {
    width: 36px;
    height: 36px;
  }
  .topbar-actions .btn-primary {
    width: auto;
    height: 36px;
    padding: 0 12px;
    border-radius: 10px;
    gap: 5px;
  }
  .topbar-actions .btn-primary span {
    display: inline;
    font-size: 12px;
  }
  .template-open-btn {
    display: inline-flex;
    height: 36px;
    padding: 0 9px;
    gap: 4px;
    border: 1px solid var(--line);
    border-radius: 10px;
  }
  .template-open-btn span {
    font-size: 12px;
  }
  .workspace {
    flex: 1;
    min-height: 0;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }
  .center-column {
    flex: 1;
    min-height: 0;
    overflow: hidden;
  }
  .quick-strip {
    display: none;
  }
  .preview-stage {
    height: auto;
    min-height: 0;
    flex: 1;
    padding: 2px 8px 0;
  }
  .stage-topline {
    padding: 0 2px 4px;
    align-items: center;
  }
  .stage-title {
    gap: 5px;
  }
  .stage-title strong {
    font-size: 13px;
  }
  .stage-meta {
    display: none;
  }
  .stage-tools {
    gap: 4px;
  }
  .stage-tools .btn {
    width: 32px;
    height: 32px;
    padding: 0;
  }
  .stage-tools .btn span {
    display: none;
  }
  .canvas-viewport {
    border-radius: 12px;
  }
  .canvas-frame {
    padding: 6px;
  }
  .sample-banner {
    top: 8px;
    left: 8px;
    right: auto;
    transform: none;
    max-width: calc(100% - 16px);
    gap: 6px;
    padding: 6px 7px;
    flex-wrap: nowrap;
    border-radius: 10px;
  }
  .sample-banner > span:nth-child(2) {
    display: none;
  }
  .sample-banner .btn-ghost {
    display: none;
  }
  .sample-banner .btn-primary {
    height: 28px;
    padding: 0 9px;
    font-size: 11px;
  }
  .stage-foot {
    display: none;
  }
  .filmstrip {
    flex: 0 0 auto;
    padding: 5px 10px 7px;
    border-top: 0;
    background: transparent;
  }
  .filmstrip-head {
    display: none;
  }
  .thumbs {
    min-height: 0;
    gap: 6px;
    padding: 2px 0;
  }
  .thumb,
  .thumb-add {
    width: 57px;
    height: 46px;
    border-radius: 7px;
  }
  .thumb-add span {
    font-size: 9px;
  }
  .thumb-remove {
    opacity: 1;
    top: 2px;
    right: 2px;
    width: 19px;
    height: 19px;
    background: rgba(8, 8, 9, 0.66);
  }
  .filmstrip-empty {
    display: none;
  }
  .inspector-panel {
    flex: 0 0 auto;
    height: clamp(300px, 43dvh, 420px);
    margin-top: -10px;
    border-radius: 18px 18px 0 0;
    border: 1px solid var(--line);
    border-bottom: 0;
    background: linear-gradient(180deg, #17171a, #101012 38%);
    box-shadow: 0 -18px 42px rgba(0, 0, 0, 0.44);
    overflow: hidden;
    position: relative;
    z-index: 12;
  }
  .sheet-grabber {
    display: flex;
    flex: 0 0 18px;
    height: 18px;
    align-items: center;
    justify-content: center;
  }
  .sheet-grabber span {
    width: 36px;
    height: 4px;
    border-radius: 999px;
    background: #3b3b42;
  }
  .inspector-tabs {
    order: 2;
    position: static;
    top: auto;
    flex: 0 0 auto;
    padding: 4px 10px calc(4px + env(safe-area-inset-bottom));
    border-top: 1px solid var(--line);
    border-bottom: 0;
    background: #101012;
  }
  .inspector-tabs button {
    height: 46px;
    flex-direction: column;
    gap: 3px;
    font-size: 11.5px;
    border-radius: 10px;
  }
  .inspector-tabs button svg {
    width: 19px;
    height: 19px;
  }
  .inspector-tabs button.active {
    color: var(--gold);
    background: rgba(216, 180, 119, 0.09);
    border-color: rgba(216, 180, 119, 0.22);
  }
  .inspector-tabs .tab-dot {
    top: 8px;
    right: calc(50% - 23px);
  }
  .inspector-scroll {
    order: 1;
    overscroll-behavior: contain;
    -webkit-overflow-scrolling: touch;
  }
  .inspector-pane {
    padding: 10px 16px 18px;
  }
  .current-template {
    padding: 2px 0 12px;
  }
  .current-template h3 {
    font-size: 19px;
  }
  .current-template p {
    font-size: 11.5px;
    max-width: none;
  }
  .panel-section {
    padding: 14px 0;
  }
  .section-head {
    margin-bottom: 10px;
  }
  .section-head h3 {
    font-size: 11.5px;
    letter-spacing: 0.09em;
  }
  .section-head p {
    font-size: 11px;
    max-width: none;
  }
  .field {
    font-size: 12px;
    gap: 6px;
  }
  .field input {
    height: 38px;
    font-size: 13px;
    border-radius: 9px;
  }
  .field.compact {
    margin: 10px 0;
    gap: 12px;
  }
  .slider-row {
    margin: 13px 0;
    font-size: 12px;
    gap: 5px 10px;
  }
  .slider-label em {
    font-size: 10.5px;
  }
  .slider-value {
    font-size: 11px;
  }
  .slider-row input[type='range'] {
    height: 26px;
  }
  .segmented {
    border-radius: 10px;
    padding: 3px;
  }
  .segmented button {
    height: 34px;
    font-size: 12px;
    border-radius: 7px;
  }
  .segmented.compact button {
    height: 32px;
    font-size: 11.5px;
  }
  .switch-row {
    padding: 10px 0;
  }
  .switch-row strong {
    font-size: 12.5px;
  }
  .switch-row em {
    font-size: 10.5px;
  }
  .switch {
    width: 38px;
    height: 22px;
  }
  .toggle-chip {
    min-height: 34px;
    font-size: 11.5px;
  }
  .inspector-pane .btn {
    min-height: 36px;
    font-size: 12px;
  }
  .export-actions {
    padding-top: 14px;
    gap: 8px;
  }
  .export-actions .btn {
    height: 44px;
    font-size: 13px;
  }
  .hint-note,
  .warn-note {
    font-size: 11px;
  }
  .toast {
    bottom: calc(72px + env(safe-area-inset-bottom));
    max-width: calc(100vw - 28px);
  }
  .debug-assets {
    bottom: calc(74px + env(safe-area-inset-bottom));
  }
  .modal-backdrop {
    padding: 0;
  }
  .modal {
    width: 100vw;
    height: 100dvh;
    max-height: 100dvh;
    border-radius: 0;
    border: 0;
  }
  .modal-head {
    padding: calc(14px + env(safe-area-inset-top)) 14px 11px;
  }
  .modal-body {
    padding: 12px 14px;
  }
  .modal-foot {
    padding: 10px 14px calc(10px + env(safe-area-inset-bottom));
  }
  .template-modal .template-gallery.modal-mode {
    height: calc(100dvh - 126px - env(safe-area-inset-top));
  }
  .template-gallery.modal-mode .tpl-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .brand-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .reference-grid,
  .sample-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .preset-save-box {
    flex-direction: column;
    align-items: stretch;
  }
  .preset-foot {
    flex-wrap: wrap;
  }
  .export-card {
    padding: 16px;
  }
}
@media (max-width: 360px) {
  .brand-text,
  .topbar-actions .ref-btn {
    display: none;
  }
  .template-open-btn {
    padding: 0 7px;
  }
  .template-open-btn span {
    display: none;
  }
}
@media (max-width: 700px) and (orientation: landscape) {
  .inspector-panel {
    height: 58dvh;
  }
}
`
fs.writeFileSync(file, css.slice(0, start) + block)
console.log('mobile block replaced; bytes', fs.statSync(file).size)
