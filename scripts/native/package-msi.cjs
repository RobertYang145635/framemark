'use strict'

// 把已经打好的 Windows 便携目录封装成 per-user MSI（WiX 3.x）。
//
// 安装布局：%LOCALAPPDATA%\FrameMark\ 下原样放 portable 目录（app/ runtime/ bin/ data/ bridge/
// FrameMark.bat）；开始菜单与桌面各放一个快捷方式，直接指向 runtime\bin\javaw.exe，
// 参数与 FrameMark.bat 一致，所以安装后不会闪控制台窗口。
//
// 用法：
//   node scripts/native/package-msi.cjs --staging <便携目录> --out <msi路径> \
//     --version 0.9.0 --label 0.9.0-rc13 --icon resources/brand/framemark.ico \
//     --wix "D:\dev\tmp\wix311\tools"
//
// 需要 WiX 3.x 的 heat/candle/light。这里只做本机 QA 包，不做签名。

const fs = require('node:fs')
const path = require('node:path')
const { spawnSync } = require('node:child_process')

const ROOT = path.resolve(__dirname, '../..')

function parseArgs (argv) {
  const args = {}
  for (let i = 2; i < argv.length; i += 2) {
    const key = argv[i]
    if (!key?.startsWith('--') || argv[i + 1] == null) throw new Error('参数必须是 --name value')
    args[key.slice(2)] = argv[i + 1]
  }
  return args
}

function run (exe, argv, logFile) {
  const result = spawnSync(exe, argv, { cwd: ROOT, encoding: 'utf8', windowsHide: true, timeout: 10 * 60 * 1000 })
  if (logFile) fs.writeFileSync(logFile, `${result.stdout || ''}\n${result.stderr || ''}`, 'utf8')
  if (result.status !== 0) {
    throw new Error(`${path.basename(exe)} failed (${result.status}): ${(result.stderr || result.stdout || result.error?.message || '').slice(-2000)}`)
  }
  return result
}

function esc (value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
}

function main () {
  const args = parseArgs(process.argv)
  for (const key of ['staging', 'out', 'version', 'label', 'icon']) {
    if (!args[key]) throw new Error(`缺少 --${key}`)
  }
  const staging = path.resolve(args.staging)
  const out = path.resolve(args.out)
  const icon = path.resolve(args.icon)
  const wix = path.resolve(args.wix || process.env.FM_WIX || 'C:\\Program Files (x86)\\WiX Toolset v3.11\\bin')
  for (const tool of ['heat.exe', 'candle.exe', 'light.exe']) {
    if (!fs.existsSync(path.join(wix, tool))) throw new Error(`WiX 工具缺失: ${path.join(wix, tool)}`)
  }
  if (!fs.existsSync(path.join(staging, 'FrameMark.bat'))) throw new Error(`便携目录不像 FrameMark 包: ${staging}`)
  if (!/^\d+\.\d+\.\d+$/.test(args.version)) throw new Error('--version 必须是 X.Y.Z 数字版本')
  fs.mkdirSync(path.dirname(out), { recursive: true })

  const work = path.join(path.dirname(out), '.msi-work')
  fs.rmSync(work, { recursive: true, force: true })
  fs.mkdirSync(work, { recursive: true })
  const filesWxs = path.join(work, 'files.wxs')
  const productWxs = path.join(work, 'product.wxs')

  // 1) 收集便携目录里的全部文件（组件路径相对于 INSTALLDIR）。
  run(path.join(wix, 'heat.exe'), [
    'dir', staging,
    '-cg', 'AppFiles',
    '-dr', 'INSTALLDIR',
    '-gg', '-srd', '-sfrag', '-sreg',
    '-var', 'var.StageDir',
    '-out', filesWxs
  ], path.join(work, 'heat.log'))

  // 2) Product：per-user 安装，开始菜单 + 桌面快捷方式。
  const label = args.label
  const shortcutArgs = [
    '-Dfile.encoding=UTF-8',
    `-Dframemark.build.label=${label}`,
    '-cp "[INSTALLDIR]app\\classes;[INSTALLDIR]app\\resources;[INSTALLDIR]app\\lib\\*"',
    'com.framemark.native.MainKt'
  ].join(' ')
  const product = `<?xml version="1.0" encoding="UTF-8"?>
<Wix xmlns="http://schemas.microsoft.com/wix/2006/wi">
  <Product Id="*" Name="FrameMark" Language="2052" Version="${esc(args.version)}"
           Manufacturer="FrameMark" UpgradeCode="{8E0B6F9C-2D6A-4D1B-9C1B-6B7F3A2E9C10}">
    <Package InstallerVersion="500" Compressed="yes" InstallScope="perUser"
             InstallPrivileges="limited" Platform="x64" SummaryCodepage="1252" />
    <MajorUpgrade DowngradeErrorMessage="A newer FrameMark is already installed." />
    <MediaTemplate EmbedCab="yes" />
    <Icon Id="FrameMarkIcon" SourceFile="${esc(icon)}" />
    <Property Id="ARPPRODUCTICON" Value="FrameMarkIcon" />
    <Property Id="ARPHELPLINK" Value="https://github.com/RobertYang145635/framemark" />

    <Directory Id="TARGETDIR" Name="SourceDir">
      <Directory Id="LocalAppDataFolder">
        <Directory Id="INSTALLDIR" Name="FrameMark" />
      </Directory>
      <Directory Id="ProgramMenuFolder">
        <Directory Id="AppMenuFolder" Name="FrameMark" />
      </Directory>
      <Directory Id="DesktopFolder" />
    </Directory>

    <DirectoryRef Id="AppMenuFolder">
      <Component Id="StartMenuShortcut" Guid="*">
        <Shortcut Id="AppShortcut" Name="FrameMark" Description="Camera-style frames and EXIF watermarks for photos"
                  Target="[INSTALLDIR]runtime\\bin\\javaw.exe" WorkingDirectory="INSTALLDIR"
                  Arguments="${esc(shortcutArgs)}" Icon="FrameMarkIcon" />
        <RemoveFolder Id="RemoveAppMenuFolder" On="uninstall" />
        <RegistryValue Root="HKCU" Key="Software\\FrameMark" Name="StartMenuShortcut" Type="integer" Value="1" KeyPath="yes" />
      </Component>
    </DirectoryRef>

    <DirectoryRef Id="DesktopFolder">
      <Component Id="DesktopShortcut" Guid="*">
        <Shortcut Id="DesktopAppShortcut" Name="FrameMark" Description="Camera-style frames and EXIF watermarks for photos"
                  Target="[INSTALLDIR]runtime\\bin\\javaw.exe" WorkingDirectory="INSTALLDIR"
                  Arguments="${esc(shortcutArgs)}" Icon="FrameMarkIcon" />
        <RegistryValue Root="HKCU" Key="Software\\FrameMark" Name="DesktopShortcut" Type="integer" Value="1" KeyPath="yes" />
      </Component>
    </DirectoryRef>

    <Feature Id="Main" Title="FrameMark" Level="1">
      <ComponentGroupRef Id="AppFiles" />
      <ComponentRef Id="StartMenuShortcut" />
      <ComponentRef Id="DesktopShortcut" />
    </Feature>
  </Product>
</Wix>
`
  fs.writeFileSync(productWxs, product, 'utf8')

  // 3) 编译并链接。
  run(path.join(wix, 'candle.exe'), ['-nologo', '-arch', 'x64', `-dStageDir=${staging}`, '-out', path.join(work, 'product.wixobj'), productWxs], path.join(work, 'candle-product.log'))
  run(path.join(wix, 'candle.exe'), ['-nologo', '-arch', 'x64', `-dStageDir=${staging}`, '-out', path.join(work, 'files.wixobj'), filesWxs], path.join(work, 'candle-files.log'))
  run(path.join(wix, 'light.exe'), [
    '-nologo', '-sval', '-ext', 'WixUIExtension',
    '-out', out,
    path.join(work, 'product.wixobj'),
    path.join(work, 'files.wixobj')
  ], path.join(work, 'light.log'))

  const size = fs.statSync(out).size
  console.log(JSON.stringify({ msi: out, bytes: size }))
}

try {
  main()
} catch (error) {
  console.error(error.message || error)
  process.exit(1)
}
