import path from 'node:path'
import fs from 'node:fs/promises'
import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { packager } from '@electron/packager'

const project = path.resolve(import.meta.dirname, '..')
const release = path.join(project, 'release')

function findElectronZip() {
  const version = JSON.parse(readFileSync(path.join(project, 'node_modules/electron/package.json'), 'utf8')).version
  const filename = `electron-v${version}-win32-x64.zip`
  const roots = [
    path.join(process.env.LOCALAPPDATA || '', 'electron', 'Cache'),
    path.join(process.env.APPDATA || '', 'electron', 'Cache')
  ].filter(Boolean)
  for (const root of roots) {
    if (!existsSync(root)) continue
    for (const first of readdirSync(root, { withFileTypes: true })) {
      if (!first.isDirectory()) continue
      const direct = path.join(root, first.name, filename)
      if (existsSync(direct)) return direct
      const nested = path.join(root, first.name)
      for (const second of readdirSync(nested, { withFileTypes: true })) {
        if (!second.isDirectory()) continue
        const candidate = path.join(nested, second.name, filename)
        if (existsSync(candidate)) return candidate
      }
    }
    const direct = path.join(root, filename)
    if (existsSync(direct)) return direct
  }
  return null
}

async function main() {
  await fs.rm(release, { recursive: true, force: true })
  await fs.mkdir(release, { recursive: true })
  const ignore = (p) => {
    const n = p.split(path.sep).join('/').replace(/^\/+/, '')
    if (!n) return false
    if (/^(android|src|scripts|_research|release|\.git)(\/|$)/.test(n)) return true
    if (/^(package-lock\.json|capacitor\.config\.json|tsconfig.*\.json|electron\.vite\.config\.ts)$/.test(n)) return true
    if (/^resources(\/|$)/.test(n)) return true
    if (/^node_modules\/(@capacitor|@fontsource|react|react-dom|lucide-react|zustand|@types|@electron|electron|electron-vite|vite|typescript|playwright-core)(\/|$)/.test(n)) return true
    return false
  }
  const zipSource = findElectronZip()
  const [appDir] = await packager({
    dir: project,
    out: release,
    name: 'FrameMark',
    platform: 'win32',
    arch: 'x64',
    icon: path.join(project, 'resources', 'icon.ico'),
    appVersion: '1.0.0',
    asar: false,
    prune: true,
    overwrite: true,
    ignore,
    ...(zipSource ? { electronZipDir: path.dirname(zipSource) } : {}),
    win32metadata: {
      CompanyName: 'FrameMark',
      FileDescription: '框印 FrameMark — 边框 · 水印 · 批量',
      ProductName: '框印 FrameMark',
      InternalName: 'FrameMark',
      OriginalFilename: 'FrameMark.exe',
      LegalCopyright: 'Personal use'
    }
  })
  const zip = path.join(release, 'FrameMark-Windows-x64.zip')
  const target = appDir.replace(/'/g, "''")
  execFileSync('powershell.exe', ['-NoProfile', '-Command', `Compress-Archive -Path '${target}' -DestinationPath '${zip.replace(/'/g, "''")}' -CompressionLevel Optimal -Force`], { stdio: 'inherit' })
  const exe = path.join(appDir, 'FrameMark.exe')
  const stat = await fs.stat(exe)
  const zstat = await fs.stat(zip)
  console.log(JSON.stringify({ appDir, exe, exeMB: +(stat.size / 1048576).toFixed(1), zip, zipMB: +(zstat.size / 1048576).toFixed(1) }, null, 2))
}
main().catch((e) => { console.error(e); process.exit(1) })
