'use strict'

// Windows-only build orchestrator. All project-relative paths are derived from
// this checkout; tool locations are supplied through the FM_* environment vars.
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { spawn } = require('node:child_process')
const { createRequire } = require('node:module')

const ROOT = path.resolve(__dirname, '../..')
const UI = path.join(ROOT, 'ui')
const CONFIG = JSON.parse(fs.readFileSync(path.join(__dirname, 'toolchain.json'), 'utf8'))
const PREBUILT_MANIFEST = JSON.parse(fs.readFileSync(path.join(__dirname, 'android-prebuilt-manifest.json'), 'utf8'))
const ARTIFACT_ROOT = path.resolve(ROOT, process.env.FM_BUILD_ARTIFACT_ROOT || CONFIG.artifactDirectory)
const DESKTOP_BUILD_INPUTS = ['scripts/native/toolchain.json', 'scripts/native/build.cjs']
const E2B_CRATE_SOURCE_INPUTS = ['engine/e2b/Cargo.toml', 'engine/e2b/Cargo.lock', 'engine/e2b/src', 'engine/e2b/assets']
const E4HAND_CRATE_SOURCE_INPUTS = ['engine/e4-hand/Cargo.toml', 'engine/e4-hand/Cargo.lock', 'engine/e4-hand/src']
const E5_WORKER_CRATE_SOURCE_INPUTS = ['engine/e5/stream-proto/Cargo.toml', 'engine/e5/stream-proto/Cargo.lock', 'engine/e5/stream-proto/src']
const DESKTOP_SOURCE_INPUTS = [...E2B_CRATE_SOURCE_INPUTS, ...DESKTOP_BUILD_INPUTS]
const E4HAND_SOURCE_INPUTS = [...E4HAND_CRATE_SOURCE_INPUTS, ...DESKTOP_BUILD_INPUTS]
const E5_WORKER_SOURCE_INPUTS = [...E5_WORKER_CRATE_SOURCE_INPUTS, ...DESKTOP_BUILD_INPUTS]
const ANDROID_SOURCE_INPUTS = [
  'bridge/android/Cargo.toml', 'bridge/android/Cargo.lock', 'bridge/android/src',
  'bridge/android-hand/Cargo.toml', 'bridge/android-hand/Cargo.lock', 'bridge/android-hand/src',
  'bridge/android-export/Cargo.toml', 'bridge/android-export/Cargo.lock', 'bridge/android-export/src',
  'engine/e2b/src', 'engine/e2b/assets', 'engine/e4-hand/src', 'engine/e5/src',
  'scripts/native/toolchain.json', 'scripts/native/build.cjs',
  'ui/composeApp/src/androidMain/cpp/CMakeLists.txt'
]
// The JNI shim and its packaging declarations are compiled into the app, not
// into the Rust static libraries. Fingerprint this boundary independently.
const ANDROID_PACKAGE_SOURCE_INPUTS = [
  'ui/composeApp/src/androidMain/cpp/fm_engine_jni.cpp',
  'ui/composeApp/src/androidMain/cpp/CMakeLists.txt',
  'ui/composeApp/build.gradle.kts'
]
function parseArgs (args) {
  const out = { dryRun: false, skipEngine: false, skipAndroidNative: false, skipGradle: false, release: false, sign: false, releaseQa: false, skipGates: false, jobName: '', qaSuffix: process.env.FM_QA_SUFFIX || '.qa20261102', qaReleaseSuffix: process.env.FM_QA_RELEASE_SUFFIX || '.qa20261102release' }
  for (let i = 0; i < args.length; i++) {
    const arg = args[i]
    if (arg === '--dry-run') out.dryRun = true
    else if (arg === '--skip-engine') out.skipEngine = true
    else if (arg === '--skip-android-native') out.skipAndroidNative = true
    else if (arg === '--skip-gradle') out.skipGradle = true
    else if (arg === '--release') out.release = true
    else if (arg === '--sign') out.sign = true
    else if (arg === '--release-qa') out.releaseQa = true
    else if (arg === '--skip-gates') out.skipGates = true
    else if (arg === '--job-name') out.jobName = args[++i] || ''
    else if (arg === '--help' || arg === '-h') out.help = true
    else throw new Error(`Unknown option: ${arg}`)
  }
  if (out.sign && !out.release) throw new Error('--sign requires --release')
  if (out.releaseQa && !out.release) throw new Error('--release-qa requires --release')
  if (out.releaseQa && out.sign) throw new Error('--release-qa uses the Android debug signing key; do not combine with --sign')
  if (out.jobName && !/^[a-zA-Z0-9._-]{1,80}$/.test(out.jobName)) throw new Error('--job-name may contain only letters, digits, dot, underscore and dash')
  if (!/^\.[A-Za-z][A-Za-z0-9_.]*$/.test(out.qaSuffix)) throw new Error('FM_QA_SUFFIX must be a dot-prefixed Android application ID suffix')
  if (!/^\.[A-Za-z][A-Za-z0-9_.]*$/.test(out.qaReleaseSuffix)) throw new Error('FM_QA_RELEASE_SUFFIX must be a dot-prefixed Android application ID suffix')
  return out
}

function usage () {
  return `FrameMark native build (Windows)

Usage: build-native.cmd [options]
  --dry-run                 print the plan and current release blockers
  --skip-engine             skip the e2b source build (no complete desktop runtime; release blocked)
  --skip-android-native     reuse hash-checked arm64 libraries (debug only)
  --skip-gradle             do not compile the desktop app or APKs
  --release                 also build and verify the Android release APK
  --sign                    sign the release APK (requires FM_SIGNING_* variables)
  --release-qa              build a debug-signed, isolated release variant for local QA only
  FM_QA_SUFFIX              isolated debug application id suffix (default: .qa20261102)
  FM_QA_RELEASE_SUFFIX      isolated release QA suffix (default: .qa20261102release)
  --skip-gates              mark required gates skipped; the command exits nonzero
  --job-name <name>         label logs under ${CONFIG.artifactDirectory}

Tool locations are configurable through the FM_* variables in toolchain.json.
Rust 1.99.1 is version-checked before source builds; both target triples must be installed.
`
}

function sha256File (filename) {
  return crypto.createHash('sha256').update(fs.readFileSync(filename)).digest('hex')
}

// This pinned skia-bindings loader strips "file://" and reads the remainder
// directly as a filesystem path; standard file:///D:/... URLs fail on Windows.
function skiaLocalArchiveUrl (filename) {
  return `file://${path.resolve(filename).replaceAll('\\', '/')}`
}

function listFiles (dir) {
  if (!fs.existsSync(dir)) return []
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name)
    return entry.isDirectory() ? listFiles(full) : [full]
  }).sort()
}

function sha256Tree (relativePaths) {
  const hash = crypto.createHash('sha256')
  for (const relative of relativePaths) {
    const full = path.join(ROOT, relative)
    if (!fs.existsSync(full)) throw new Error(`Missing source input: ${relative}`)
    const files = fs.statSync(full).isDirectory() ? listFiles(full) : [full]
    for (const filename of files) {
      const rel = path.relative(ROOT, filename).replaceAll(path.sep, '/')
      hash.update(rel).update('\0').update(fs.readFileSync(filename)).update('\0')
    }
  }
  return hash.digest('hex')
}

function locateOnPath (name, env = process.env) {
  const extensions = process.platform === 'win32' ? ['.exe', '.cmd', '.bat', ''] : ['']
  for (const dir of String(env.PATH || '').split(path.delimiter)) {
    if (!dir) continue
    for (const ext of extensions) {
      const candidate = path.join(dir, name.endsWith(ext) ? name : `${name}${ext}`)
      if (fs.existsSync(candidate) && fs.statSync(candidate).isFile()) return candidate
    }
  }
  return null
}

function resolveTools () {
  const javaHome = process.env.FM_JAVA_HOME || process.env.JAVA_HOME || ''
  const gradleHome = process.env.FM_GRADLE_HOME || process.env.GRADLE_HOME || ''
  const androidSdk = process.env.FM_ANDROID_SDK_ROOT || process.env.ANDROID_SDK_ROOT || process.env.ANDROID_HOME || ''
  const ndkHome = androidSdk && path.join(androidSdk, 'ndk', String(CONFIG.versions.androidNdk))
  const rustToolchain = CONFIG.versions.rustToolchain
  const cargo = process.env.FM_CARGO_EXE || locateOnPath('cargo.exe') || ''
  const rustc = process.env.FM_RUSTC_EXE || (cargo && path.join(path.dirname(cargo), 'rustc.exe')) || locateOnPath('rustc.exe') || ''
  const rustup = process.env.FM_RUSTUP_EXE || (cargo && path.join(path.dirname(cargo), 'rustup.exe')) || locateOnPath('rustup.exe') || ''
  const rustEnv = compactEnv({
    ...process.env,
    RUSTUP_HOME: process.env.FM_RUSTUP_HOME || process.env.RUSTUP_HOME,
    CARGO_HOME: process.env.FM_CARGO_HOME || process.env.CARGO_HOME,
    RUSTUP_TOOLCHAIN: rustToolchain
  })
  const rustcInfo = readToolOutput(rustc, ['-Vv'], rustEnv)
  const cargoInfo = readToolOutput(cargo, ['-V'], rustEnv)
  const installedTargets = readToolOutput(rustup, ['target', 'list', '--installed', '--toolchain', rustToolchain], rustEnv).split(/\r?\n/).filter(Boolean)
  const rustcVersion = (rustcInfo.match(/^rustc ([^\s]+)/m) || [])[1] || ''
  const rustcHost = (rustcInfo.match(/^host: ([^\s]+)/m) || [])[1] || ''
  const cargoVersion = (cargoInfo.match(/^cargo ([^\s]+)/m) || [])[1] || ''
  const java = javaHome && path.join(javaHome, 'bin', 'java.exe')
  const jar = javaHome && path.join(javaHome, 'bin', 'jar.exe')
  const gradleLauncher = gradleHome && path.join(gradleHome, 'lib', `gradle-launcher-${CONFIG.versions.gradle}.jar`)
  const skiaWindowsArchive = process.env.FM_SKIA_WINDOWS_ARCHIVE || ''
  const skiaAndroidArchive = process.env.FM_SKIA_ANDROID_ARCHIVE || ''
  const vsDevCmd = process.env.FM_VSDEVCMD || ''
  const checks = [
    { id: 'node', ok: process.version === `v${CONFIG.versions.node}`, detail: `expected ${CONFIG.versions.node}; found ${process.version}` },
    { id: 'java', ok: Boolean(java && fs.existsSync(java)), detail: java || 'Set FM_JAVA_HOME or JAVA_HOME' },
    { id: 'java-version', ok: javaHome ? readJavaVersion(javaHome) === CONFIG.versions.jdk : false, detail: `expected ${CONFIG.versions.jdk}; found ${javaHome ? (readJavaVersion(javaHome) || 'unknown') : 'no JDK path'}` },
    { id: 'gradle', ok: Boolean(gradleLauncher && fs.existsSync(gradleLauncher)), detail: gradleHome ? `Gradle ${CONFIG.versions.gradle} launcher at ${gradleLauncher}` : 'Set FM_GRADLE_HOME or GRADLE_HOME' },
    { id: 'android-sdk', ok: Boolean(androidSdk && fs.existsSync(path.join(androidSdk, `platforms/android-${CONFIG.versions.androidCompileSdk}/android.jar`))), detail: androidSdk || 'Set FM_ANDROID_SDK_ROOT, ANDROID_SDK_ROOT or ANDROID_HOME' },
    { id: 'android-ndk', ok: Boolean(ndkHome && fs.existsSync(ndkHome)), detail: ndkHome || `NDK ${CONFIG.versions.androidNdk} not resolved` },
    { id: 'cargo', ok: Boolean(cargo && fs.existsSync(cargo)), detail: cargo || 'Set FM_CARGO_EXE or put cargo.exe on PATH' },
    { id: 'rustc-version', ok: rustcVersion === CONFIG.versions.rustc && rustcHost === CONFIG.targets.windowsRustTarget, detail: rustcInfo || `Expected rustc ${CONFIG.versions.rustc}` },
    { id: 'cargo-version', ok: cargoVersion === CONFIG.versions.cargo, detail: cargoInfo || `Expected cargo ${CONFIG.versions.cargo}` },
    { id: 'rust-target-windows', ok: installedTargets.includes(CONFIG.targets.windowsRustTarget), detail: installedTargets.join(', ') || 'Windows Rust target is not installed for the pinned toolchain' },
    { id: 'rust-target-android', ok: installedTargets.includes(CONFIG.targets.androidRustTarget), detail: installedTargets.join(', ') || 'Android Rust target is not installed for the pinned toolchain' },
    { id: 'rust-pin', ok: Boolean(rustToolchain && rustcInfo && cargoInfo), detail: rustToolchain || 'No verified Rust toolchain pin is recorded in toolchain.json' },
    { id: 'skia-windows', ok: fileMatches(skiaWindowsArchive, CONFIG.skiaArchives.windowsX64.sha256), detail: skiaWindowsArchive || 'Set FM_SKIA_WINDOWS_ARCHIVE to the hash-pinned archive' },
    { id: 'skia-android', ok: fileMatches(skiaAndroidArchive, CONFIG.skiaArchives.androidArm64.sha256), detail: skiaAndroidArchive || 'Set FM_SKIA_ANDROID_ARCHIVE to the hash-pinned archive' },
    { id: 'vsdevcmd', ok: Boolean(vsDevCmd && fs.existsSync(vsDevCmd)), detail: vsDevCmd || 'Set FM_VSDEVCMD to a Visual Studio x64 developer prompt script' }
  ]
  return { javaHome, java, jar, gradleHome, gradleLauncher, androidSdk, ndkHome, cargo, rustc, rustup, rustToolchain, rustcInfo, cargoInfo, rustcVersion, rustcHost, cargoVersion, installedTargets, skiaWindowsArchive, skiaAndroidArchive, vsDevCmd, checks }
}

function readToolOutput (executable, args, env) {
  if (!executable || !fs.existsSync(executable)) return ''
  try {
    const { execFileSync } = require('node:child_process')
    return execFileSync(executable, args, { cwd: ROOT, env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'], timeout: 15000 }).trim()
  } catch { return '' }
}

function readJavaVersion (home) {
  const release = path.join(home, 'release')
  if (!fs.existsSync(release)) return null
  const match = fs.readFileSync(release, 'utf8').match(/^JAVA_VERSION="([^"]+)"/m)
  return match && match[1]
}

function fileMatches (filename, expected) {
  return Boolean(filename && fs.existsSync(filename) && sha256File(filename) === expected)
}

function compactEnv (env) {
  return Object.fromEntries(Object.entries(env).filter(([, value]) => value !== undefined && value !== null))
}

function verifyCheckedPrebuilt () {
  const rootPath = path.join(ROOT, 'ui/composeApp/src/androidMain/cpp/prebuilt/arm64-v8a')
  const rows = PREBUILT_MANIFEST.libraries.map((item) => {
    const filename = path.join(ROOT, item.path)
    if (!fs.existsSync(filename)) return { path: filename, status: 'FAIL', reason: 'missing' }
    const actual = sha256File(filename)
    return { path: path.relative(ROOT, filename).replaceAll(path.sep, '/'), expectedSha256: item.sha256, actualSha256: actual, status: actual === item.sha256 ? 'PASS' : 'FAIL' }
  })
  return { root: rootPath, provenanceStatus: PREBUILT_MANIFEST.provenanceStatus, rows, status: rows.every((row) => row.status === 'PASS') ? 'PASS' : 'FAIL' }
}

function verifyReleaseSurface () {
  const sourcePath = path.join(ROOT, 'ui/composeApp/src/androidMain/kotlin/com/framemark/native/MainActivity.kt')
  const manifestPath = path.join(ROOT, 'ui/composeApp/src/androidMain/AndroidManifest.xml')
  const source = fs.readFileSync(sourcePath, 'utf8')
  const manifest = fs.readFileSync(manifestPath, 'utf8')
  return checkReleaseSurface(source, manifest)
}

// Returns the index of the "}" that closes the "{" at openIndex. Only brace
// characters are counted: the guarded QA hooks are plain statements, so their
// braces balance and no string or comment parsing is needed.
function findBlockEnd (text, openIndex) {
  let depth = 0
  for (let index = openIndex; index >= 0 && index < text.length; index++) {
    if (text[index] === '{') depth += 1
    else if (text[index] === '}') {
      depth -= 1
      if (depth === 0) return index
    }
  }
  return -1
}

// The normal UI entry is a setContent call at the eight-space content indent.
// It may open a single-line or a multi-line block, so only the opening is
// matched. The debug-only QA intent hooks must live inside the debug guard and
// the guard block must end on the line immediately above that entry: a bare
// "}" line followed by setContent, with no code in between.
function checkReleaseSurface (source, manifest) {
  const text = String(source).replaceAll('\r\n', '\n')
  const manifestText = String(manifest).replaceAll('\r\n', '\n')
  const qaStart = text.indexOf('LaunchOptions.openCardId = intent?.getStringExtra("fmCard")')
  // The guard that matters is the one nearest before the hooks. Taking the
  // first guard in the file would accept hooks sitting outside every debug
  // block as long as some earlier debug block exists.
  const guard = qaStart >= 0 ? text.lastIndexOf('if (BuildConfig.DEBUG) {', qaStart) : -1
  const content = text.search(/\n        setContent\s*\{/)
  let close = -1
  if (content >= 0) {
    const closeLineStart = text.lastIndexOf('\n', content - 1) + 1
    if (text.slice(closeLineStart, content) === '        }') close = closeLineStart
  }
  const guardBlockEnd = guard >= 0 ? findBlockEnd(text, text.indexOf('{', guard)) : -1
  // The close line must be the end of the guard block itself, not of a later
  // block that happens to sit between the hooks and the UI entry.
  const contained = guard >= 0 && qaStart > guard && close > qaStart && content > close &&
    guardBlockEnd >= 0 && guardBlockEnd === close + 8
  const exportedLauncher = /<activity[\s\S]*?android:exported="true"[\s\S]*?<\/activity>/.test(manifestText) &&
    /<action android:name="android\.intent\.action\.MAIN"\s*\/>/.test(manifestText) &&
    /<category android:name="android\.intent\.category\.LAUNCHER"\s*\/>/.test(manifestText)
  return { status: contained && exportedLauncher ? 'PASS' : 'FAIL', debugGuardContainsQaHooks: contained, normalExportedLauncherPreserved: exportedLauncher }
}

function verifyPinnedBuildConfiguration () {
  const rootBuild = fs.readFileSync(path.join(UI, 'build.gradle.kts'), 'utf8').replaceAll('\r\n', '\n')
  const appBuild = fs.readFileSync(path.join(UI, 'composeApp/build.gradle.kts'), 'utf8').replaceAll('\r\n', '\n')
  const cmakeBuild = fs.readFileSync(path.join(UI, 'composeApp/src/androidMain/cpp/CMakeLists.txt'), 'utf8').replaceAll('\r\n', '\n')
  const nativeEntry = fs.readFileSync(path.join(ROOT, 'build-native.cmd'), 'utf8').replaceAll('\r\n', '\n')
  const checks = [
    ['kotlin-multiplatform', rootBuild.includes(`kotlin("multiplatform") version "${CONFIG.versions.kotlin}"`)],
    ['android-gradle-plugin', rootBuild.includes(`id("com.android.application") version "${CONFIG.versions.androidGradlePlugin}"`)],
    ['compose-multiplatform', rootBuild.includes(`id("org.jetbrains.compose") version "${CONFIG.versions.composeMultiplatform}"`)],
    ['kotlin-compose-plugin', rootBuild.includes(`id("org.jetbrains.kotlin.plugin.compose") version "${CONFIG.versions.kotlin}"`)],
    ['android-compile-sdk', appBuild.includes(`compileSdk = ${CONFIG.versions.androidCompileSdk}`)],
    ['android-ndk', appBuild.includes(`ndkVersion = "${CONFIG.versions.androidNdk}"`)],
    ['generated-build-config', appBuild.includes('buildConfig = true')],
    ['android-arm64-filter', appBuild.includes('abiFilters += listOf("arm64-v8a")')],
    ['debug-qa-suffix-property', appBuild.includes('applicationIdSuffix = framemarkQaSuffix')],
    ['release-qa-suffix-property', appBuild.includes('applicationIdSuffix = framemarkQaReleaseSuffix') && appBuild.includes('signingConfig = signingConfigs.getByName("debug")')],
    ['cmake-arm64-only', cmakeBuild.includes('if(NOT ANDROID_ABI STREQUAL "arm64-v8a")') && cmakeBuild.includes('FATAL_ERROR')],
    ['cmake-requires-three-native-libraries', ['libfm_android_engine.a', 'libfm_android_hand.a', 'libfm_android_export.a'].every((name) => cmakeBuild.includes(name)) && cmakeBuild.includes('if(NOT EXISTS "${FM_REQUIRED_ARCHIVE}")')],
    ['checkout-local-build-entry', nativeEntry.includes('scripts\\native\\build.cjs') && !nativeEntry.includes('_research/native/')]
  ].map(([id, ok]) => ({ id, status: ok ? 'PASS' : 'FAIL' }))
  return { status: checks.every((row) => row.status === 'PASS') ? 'PASS' : 'FAIL', checks }
}

function verifySharedLogoData () {
  const source = path.join(ROOT, 'engine/data/logos.json')
  const bundled = path.join(UI, 'composeApp/src/commonMain/composeResources/files/data/logos.json')
  if (!fs.existsSync(source) || !fs.existsSync(bundled)) {
    return { status: 'FAIL', source: path.relative(ROOT, source).replaceAll(path.sep, '/'), bundled: path.relative(ROOT, bundled).replaceAll(path.sep, '/'), reason: !fs.existsSync(bundled) ? 'bundled Android/Compose logos.json is missing' : 'source logos.json is missing' }
  }
  const sourceSha256 = sha256File(source)
  const bundledSha256 = sha256File(bundled)
  return { status: sourceSha256 === bundledSha256 ? 'PASS' : 'FAIL', source: path.relative(ROOT, source).replaceAll(path.sep, '/'), bundled: path.relative(ROOT, bundled).replaceAll(path.sep, '/'), sourceSha256, bundledSha256 }
}

function verifyLogoImageToolchain () {
  const manifestPath = path.join(__dirname, 'logo-tools/package.json')
  const lockPath = path.join(__dirname, 'logo-tools/package-lock.json')
  const rootManifestPath = path.join(ROOT, 'package.json')
  const rootLockPath = path.join(ROOT, 'package-lock.json')
  const prefixValue = process.env.FM_NATIVE_SHARP_PREFIX || ''
  const installReportPath = prefixValue && path.isAbsolute(prefixValue)
    ? path.join(path.resolve(prefixValue), 'framemark-logo-tools-install.json')
    : ''
  const checks = []
  const check = (id, ok, detail) => checks.push({ id, status: ok ? 'PASS' : 'FAIL', detail })
  let manifest
  let lock
  let rootManifest
  let rootLock
  try { manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8')) } catch (error) { check('logo-tools-manifest', false, error.message) }
  try { lock = JSON.parse(fs.readFileSync(lockPath, 'utf8')) } catch (error) { check('logo-tools-lock', false, error.message) }
  try { rootManifest = JSON.parse(fs.readFileSync(rootManifestPath, 'utf8')) } catch (error) { check('repository-manifest', false, error.message) }
  try { rootLock = JSON.parse(fs.readFileSync(rootLockPath, 'utf8')) } catch (error) { check('repository-lock', false, error.message) }

  const lockedSharp = lock?.packages?.['node_modules/sharp']
  const repoSharp = rootLock?.packages?.['node_modules/sharp']
  const expectedVersion = manifest?.dependencies?.sharp
  check('pinned-version-source-integrity', Boolean(
    expectedVersion === '0.35.5' && lock?.lockfileVersion === 3 && lockedSharp?.version === expectedVersion &&
    lockedSharp?.resolved === repoSharp?.resolved && lockedSharp?.integrity === repoSharp?.integrity &&
    rootManifest?.dependencies?.sharp === expectedVersion && lockedSharp?.resolved?.startsWith('https://registry.npmjs.org/sharp/-/sharp-') &&
    lockedSharp?.integrity?.startsWith('sha512-')
  ), 'isolated and repository package manifests/locks must pin sharp 0.35.5 to the same registry URL and SHA-512 integrity')

  let prefix = null
  let installedVersion = null
  let installedPackageJsonSha256 = null
  let installReportSha256 = null
  let resolvedEntry = null
  if (!prefixValue || !path.isAbsolute(prefixValue)) {
    check('isolated-install-prefix', false, 'Set FM_NATIVE_SHARP_PREFIX to an absolute isolated install directory outside the checkout')
  } else {
    prefix = path.resolve(prefixValue)
    const rootPrefix = ROOT.replace(/[\\/]+$/, '') + path.sep
    const outsideCheckout = !prefix.toLowerCase().startsWith(rootPrefix.toLowerCase()) && prefix.toLowerCase() !== ROOT.toLowerCase()
    check('isolated-install-prefix', outsideCheckout && fs.existsSync(prefix), outsideCheckout ? prefix : 'Install prefix must be outside the source checkout')
    const stagedManifest = path.join(prefix, 'package.json')
    const stagedLock = path.join(prefix, 'package-lock.json')
    const manifestsMatch = fs.existsSync(stagedManifest) && fs.existsSync(stagedLock) &&
      sha256File(stagedManifest) === sha256File(manifestPath) && sha256File(stagedLock) === sha256File(lockPath)
    check('isolated-install-manifest-lock', manifestsMatch, 'the installed prefix must retain byte-identical checked-in package.json and package-lock.json')
    let installedPackage = null
    try {
      const resolver = createRequire(path.join(prefix, 'package.json'))
      resolvedEntry = path.resolve(resolver.resolve('sharp'))
      const modulesRoot = path.join(prefix, 'node_modules') + path.sep
      const resolvedInsidePrefix = resolvedEntry.toLowerCase().startsWith(modulesRoot.toLowerCase())
      check('resolved-from-isolated-prefix', resolvedInsidePrefix, resolvedInsidePrefix ? path.relative(prefix, resolvedEntry).replaceAll(path.sep, '/') : resolvedEntry)
      let search = path.dirname(resolvedEntry)
      while (search.toLowerCase().startsWith(modulesRoot.toLowerCase())) {
        const candidate = path.join(search, 'package.json')
        if (fs.existsSync(candidate) && JSON.parse(fs.readFileSync(candidate, 'utf8')).name === 'sharp') {
          installedPackage = candidate
          break
        }
        search = path.dirname(search)
      }
      if (!installedPackage) throw new Error('Could not find the sharp package manifest under the isolated node_modules directory')
      const installed = JSON.parse(fs.readFileSync(installedPackage, 'utf8'))
      installedVersion = installed.version
      installedPackageJsonSha256 = sha256File(installedPackage)
      check('installed-sharp-version', installed.name === 'sharp' && installedVersion === expectedVersion, `expected ${expectedVersion}; found ${installedVersion || '<missing>'}`)
      resolver('sharp')
      check('sharp-native-module-loads', true, 'sharp module loaded from the isolated prefix')
    } catch (error) {
      check('sharp-native-module-loads', false, error.message)
    }
    try {
      if (!installReportPath || !fs.existsSync(installReportPath)) throw new Error('Run scripts/native/prepare-logo-tools.ps1 to create isolated install provenance')
      const installReport = JSON.parse(fs.readFileSync(installReportPath, 'utf8').replace(/^\uFEFF/, ''))
      const installReportOkay = installReport.package === 'sharp' && installReport.version === expectedVersion &&
        installReport.source === lockedSharp.resolved && installReport.integrity === lockedSharp.integrity &&
        installReport.packageManifestSha256 === sha256File(manifestPath) && installReport.packageLockSha256 === sha256File(lockPath) &&
        installReport.installedPackageJsonSha256 === installedPackageJsonSha256
      if (!installReportOkay) throw new Error('Install report must match the checked-in lock and installed sharp package')
      installReportSha256 = sha256File(installReportPath)
      check('isolated-install-provenance', true, installReportPath)
    } catch (error) {
      check('isolated-install-provenance', false, error.message)
    }
  }
  const sourceInputs = [manifestPath, lockPath, rootManifestPath, rootLockPath]
  const present = sourceInputs.every((filename) => fs.existsSync(filename))
  return {
    status: checks.length > 0 && checks.every((row) => row.status === 'PASS') ? 'PASS' : 'FAIL',
    package: 'sharp',
    version: expectedVersion || null,
    resolved: lockedSharp?.resolved || null,
    integrity: lockedSharp?.integrity || null,
    prefix,
    resolvedEntry: resolvedEntry && prefix ? path.relative(prefix, resolvedEntry).replaceAll(path.sep, '/') : null,
    installedVersion,
    installedPackageJsonSha256,
    installReportSha256,
    packageManifestSha256: present ? sha256File(manifestPath) : null,
    packageLockSha256: present ? sha256File(lockPath) : null,
    repositoryPackageLockSha256: present ? sha256File(rootLockPath) : null,
    checks
  }
}

function verifyDesktopRuntimeInputs () {
  const paths = [
    'ui/composeApp/src/androidMain/assets/x1ir.zip',
    'bridge/desktop/cards-175.json',
    'bridge/desktop/ir-slot-binding.json',
    'engine/data/taxonomy.json', 'engine/data/capabilities.json', 'engine/data/controls.json', 'engine/data/logos.json',
    'ui/composeApp/src/commonMain/composeResources/files/data/logos.json',
    'ui/composeApp/src/commonMain/composeResources/files/logov',
    'ui/composeApp/src/commonMain/composeResources/files/data/inspector-meta.json',
    'scripts/native/runtime.cjs',
    'scripts/native/logo-tools/package.json', 'scripts/native/logo-tools/package-lock.json',
    'scripts/native/prepare-logo-tools.ps1', 'scripts/native/verify-logo-version-stage.cjs',
    'src/renderer/src/core/controls/data/controls.oppo.json',
    'src/renderer/src/core/controls/data/controls.xiaomi.json',
    'src/renderer/src/core/controls/data/controls.vivo.json'
  ]
  const rows = paths.map((relative) => {
    const filename = path.join(ROOT, relative)
    const exists = fs.existsSync(filename)
    const hash = exists ? (fs.statSync(filename).isDirectory() ? sha256Tree([relative]) : sha256File(filename)) : null
    return { path: relative, status: exists ? 'PASS' : 'FAIL', sha256: hash }
  })
  let manifest = null
  let cardCounts = null
  try {
    manifest = JSON.parse(fs.readFileSync(path.join(ROOT, 'bridge/desktop/cards-175.json'), 'utf8'))
    if (Array.isArray(manifest.cards)) {
      cardCounts = {
        total: manifest.cards.length,
        ir: manifest.cards.filter((card) => card.backend === 'ir').length,
        hand: manifest.cards.filter((card) => card.backend === 'hand').length
      }
    }
  } catch {}
  const manifestValid = Boolean(manifest && cardCounts && cardCounts.total > 0 && cardCounts.total === cardCounts.ir + cardCounts.hand)
  return { status: rows.every((row) => row.status === 'PASS') && manifestValid ? 'PASS' : 'FAIL', rows, manifestValid, cardCounts }
}

function verifyAndroidPackageSourceInputs () {
  const rows = ANDROID_PACKAGE_SOURCE_INPUTS.map((relative) => {
    const filename = path.join(ROOT, relative)
    return { path: relative, status: fs.existsSync(filename) ? 'PASS' : 'FAIL', sha256: fs.existsSync(filename) ? sha256File(filename) : null }
  })
  return {
    status: rows.every((row) => row.status === 'PASS') ? 'PASS' : 'FAIL',
    sourceInputsSha256: rows.every((row) => row.status === 'PASS') ? sha256Tree(ANDROID_PACKAGE_SOURCE_INPUTS) : null,
    rows
  }
}

function cmdQuote (value) {
  const text = String(value)
  if (/["\r\n%]/.test(text)) throw new Error(`Unsafe value for batch invocation: ${text}`)
  return `"${text}"`
}

function makeBatch (filename, { cwd, env, executable, args, vsDevCmd }) {
  const lines = ['@echo off', 'setlocal']
  if (vsDevCmd) {
    lines.push(`call ${cmdQuote(vsDevCmd)} -arch=amd64 -host_arch=amd64 >nul`)
    lines.push('if errorlevel 1 exit /b 1')
  }
  for (const key of ['RUSTUP_TOOLCHAIN', 'RUSTUP_HOME', 'CARGO_HOME', 'CARGO_TARGET_DIR', 'CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER', 'CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS', 'FM_E2B_EXE']) {
    if (env[key]) {
      const value = String(env[key])
      if (/["\r\n%]/.test(value)) throw new Error(`Unsafe ${key} value for batch invocation`)
      lines.push(`set "${key}=${value}"`)
    }
  }
  lines.push(`pushd ${cmdQuote(cwd)}`)
  lines.push(`${cmdQuote(executable)} ${args.map(cmdQuote).join(' ')}`)
  lines.push('set "FM_STEP_EXIT=%ERRORLEVEL%"')
  lines.push('popd')
  lines.push('exit /b %FM_STEP_EXIT%')
  fs.writeFileSync(filename, lines.join('\r\n') + '\r\n', 'utf8')
}

function writeGradleIsolationScript (filename) {
  const source = `allprojects {
    def outputRoot = System.getenv('FM_GRADLE_OUTPUT_ROOT')
    if (outputRoot) {
        def projectName = project == rootProject ? 'root' : project.path.substring(1).replace(':', '/')
        buildDir = new File(outputRoot, projectName)
    }
}
`
  fs.writeFileSync(filename, source, 'utf8')
}

function runProcess (executable, args, { cwd, env, logFile, timeoutMs = 60 * 60 * 1000 }) {
  return new Promise((resolve) => {
    const log = fs.createWriteStream(logFile, { flags: 'w' })
    // cmd.exe parses shell metacharacters even in a path without spaces. The
    // generated batch path therefore needs cmd's outer + inner quote pair.
    const isBatchShell = path.basename(executable).toLowerCase() === 'cmd.exe'
    const child = spawn(executable, args, { cwd, env, windowsHide: true,
      windowsVerbatimArguments: isBatchShell, stdio: ['ignore', 'pipe', 'pipe'] })
    let stdout = ''
    let stderr = ''
    let timedOut = false
    const timer = setTimeout(() => {
      timedOut = true
      child.kill()
    }, timeoutMs)
    child.stdout.on('data', (chunk) => { stdout += chunk.toString(); log.write(chunk) })
    child.stderr.on('data', (chunk) => { stderr += chunk.toString(); log.write(chunk) })
    child.on('error', (error) => {
      clearTimeout(timer)
      log.end()
      resolve({ exitCode: 1, stdout, stderr: `${stderr}${error.message}`, timedOut })
    })
    child.on('close', (code) => {
      clearTimeout(timer)
      log.end()
      resolve({ exitCode: timedOut ? 124 : (code ?? 1), stdout, stderr, timedOut })
    })
  })
}

function requiredCheck (id, status, detail) {
  return { id, required: true, status, detail }
}

/**
 * ARCH1: key desktop classes must still be declared where this manifest says they are.
 * This is a packaging guard ("the entry point and the QA surface did not vanish"), not an
 * architecture-rule scanner — the import graph and singleton concepts belong to Konsist
 * (`src/desktopTest/kotlin/com/framemark/native/arch/*KonsistTest.kt`).
 */
function verifyDesktopClassManifest () {
  const manifestPath = path.join(__dirname, 'desktop-class-manifest.json')
  if (!fs.existsSync(manifestPath)) return { status: 'FAIL', classes: 0, detail: `missing ${path.relative(ROOT, manifestPath)}`, rows: [] }
  const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'))
  const rows = (manifest.classes || []).map((entry) => {
    const abs = path.join(ROOT, entry.file)
    if (!fs.existsSync(abs)) return { name: entry.name, status: 'FAIL', detail: `source file missing: ${entry.file}` }
    const text = fs.readFileSync(abs, 'utf8')
    const ok = new RegExp(entry.declaration).test(text)
    return { name: entry.name, status: ok ? 'PASS' : 'FAIL', detail: ok ? entry.file : `/${entry.declaration}/ not found in ${entry.file}` }
  })
  const failed = rows.filter((row) => row.status !== 'PASS')
  return {
    status: rows.length && !failed.length ? 'PASS' : 'FAIL',
    classes: rows.length,
    failed: failed.map((row) => row.name),
    detail: failed.length ? `missing key classes: ${failed.map((row) => row.name).join(', ')}` : `${rows.length} key desktop classes verified`,
    rows,
  }
}

/**
 * ARCH1: the packaged desktop product must not ship the QA surface
 * (`com/framemark/native/verify/**`, `com/framemark/native/qaenv/**`).
 *
 * Two things are checked without building an artifact:
 *  1. `ui/composeApp/build.gradle.kts` keeps the `fmQaSurface` switch and excludes both
 *     packages when it is false (this is the mechanism WP-X3 §7 added);
 *  2. no in-repo script that builds a desktop jar may omit `-PfmQaSurface=false`.
 *
 * What this does NOT prove: that a released binary was built from a QA-free classpath.
 * `scripts/native/package-desktop.cjs` consumes prebuilt jars, so the flag has to be
 * passed by whoever invokes `:composeApp:desktopJar` (the runbook's `run-desktop`
 * entry point). That gap is recorded in `reports/ARCH1-REPORT.md`.
 */
function verifyPackagingSurface () {
  const checks = []
  const buildScript = path.join(ROOT, 'ui/composeApp/build.gradle.kts')
  const text = fs.existsSync(buildScript) ? fs.readFileSync(buildScript, 'utf8') : ''
  checks.push({ id: 'fmQaSurface-switch', ok: /fmQaSurface/.test(text), detail: 'ui/composeApp/build.gradle.kts defines the fmQaSurface switch' })
  checks.push({ id: 'verify-excluded', ok: /com\/framemark\/native\/verify\/\*\*/.test(text), detail: 'verify/** excluded when fmQaSurface=false' })
  checks.push({ id: 'qaenv-excluded', ok: /com\/framemark\/native\/qaenv\/\*\*/.test(text), detail: 'qaenv/** excluded when fmQaSurface=false' })
  const scripts = []
  const walkScripts = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const abs = path.join(dir, entry.name)
      if (entry.isDirectory()) walkScripts(abs)
      else if (/\.(cjs|mjs|js|ps1)$/.test(entry.name)) scripts.push(abs)
    }
  }
  walkScripts(path.join(ROOT, 'scripts'))
  const desktopBuilders = scripts.filter((file) => {
    const source = fs.readFileSync(file, 'utf8')
    return /desktopJar/.test(source) && /GradleMain|gradle\.bat|gradlew/.test(source)
  })
  const offenders = desktopBuilders.filter((file) => !/-PfmQaSurface=false/.test(fs.readFileSync(file, 'utf8')))
  checks.push({
    id: 'desktop-jar-builds-without-qa-surface',
    ok: offenders.length === 0,
    detail: desktopBuilders.length
      ? `${desktopBuilders.length} desktop jar entry point(s) checked; offenders: ${offenders.map((file) => path.relative(ROOT, file)).join(', ') || 'none'}`
      : 'no in-repo script builds :composeApp:desktopJar (packaged jars come from the runbook entry point); mechanism check only',
  })
  const failed = checks.filter((check) => !check.ok)
  return { status: failed.length ? 'FAIL' : 'PASS', checks, failed: failed.map((check) => check.id) }
}


async function main (options) {
  if (options.help) { process.stdout.write(usage()); return 0 }
  if (process.platform !== 'win32') throw new Error('This build entry point requires Windows; use the Gradle/Cargo commands directly on other hosts.')

  const tools = resolveTools()
  const prebuilt = verifyCheckedPrebuilt()
  const releaseSurface = verifyReleaseSurface()
  const buildConfiguration = verifyPinnedBuildConfiguration()
  const sharedLogoData = verifySharedLogoData()
  const logoImageToolchain = verifyLogoImageToolchain()
  const desktopRuntimeInputs = verifyDesktopRuntimeInputs()
  const androidPackageSourceInputs = verifyAndroidPackageSourceInputs()
  const plan = [
    options.skipEngine ? 'SKIP e2b Cargo build; source-build e4hand and e5-worker' : 'Cargo build e2b, e4hand and e5-worker from repository sources for x86_64-pc-windows-msvc',
    'Stage isolated relocatable Windows QA runtime from repository Android asset ZIP and current desktop manifest',
    options.skipAndroidNative ? 'SKIP Android staticlib rebuild; verify checked-in hashes' : 'Cargo build three Android arm64 staticlibs to staging',
    options.skipGradle ? 'SKIP desktop and APK Gradle tasks' : 'Gradle desktop compile and Android debug APK',
    options.release ? (options.releaseQa ? 'Gradle isolated debug-signed release QA APK + package/ABI gates' : 'Gradle Android release APK + ABI and source-provenance gates') : 'SKIP Android release APK'
  ]
  const preflight = {
    node: process.version,
    expectedNode: CONFIG.versions.node,
    tools: tools.checks,
    checkedInAndroidPrebuilt: prebuilt,
    releaseSurface,
    buildConfiguration,
    sharedLogoData,
    logoImageToolchain,
    desktopRuntimeInputs,
    androidPackageSourceInputs,
    plan
  }

  if (options.dryRun) {
    process.stdout.write(JSON.stringify({ schema: 'framemark-native-build-plan/v1', preflight, options }, null, 2) + '\n')
    return 0
  }

  fs.mkdirSync(ARTIFACT_ROOT, { recursive: true })
  const runId = `${new Date().toISOString().replaceAll(/[-:.TZ]/g, '')}${options.jobName ? `-${options.jobName}` : ''}`
  const runRoot = path.join(ARTIFACT_ROOT, 'runs', runId)
  const logRoot = path.join(runRoot, 'logs')
  const buildRoot = path.join(runRoot, 'artifacts')
  const gradleOutputRoot = path.join(buildRoot, 'gradle-output')
  const tmpRoot = path.join(runRoot, 'tmp')
  for (const dir of [logRoot, buildRoot, tmpRoot]) fs.mkdirSync(dir, { recursive: true })

  const steps = []
  const gates = []
  const envBase = compactEnv({
    ...process.env,
    ANDROID_HOME: tools.androidSdk || process.env.ANDROID_HOME,
    ANDROID_SDK_ROOT: tools.androidSdk || process.env.ANDROID_SDK_ROOT,
    ANDROID_NDK_HOME: tools.ndkHome || process.env.ANDROID_NDK_HOME,
    ANDROID_NDK_ROOT: tools.ndkHome || process.env.ANDROID_NDK_ROOT,
    JAVA_HOME: tools.javaHome || process.env.JAVA_HOME,
    GRADLE_USER_HOME: process.env.FM_GRADLE_USER_HOME || path.join(ARTIFACT_ROOT, 'cache', 'gradle'),
    FM_GRADLE_OUTPUT_ROOT: gradleOutputRoot,
    TEMP: tmpRoot,
    TMP: tmpRoot
  })
  const cargoPath = tools.cargo || ''
  const cargoEnv = compactEnv({ ...envBase, CARGO_HOME: process.env.FM_CARGO_HOME || process.env.CARGO_HOME, RUSTUP_HOME: process.env.FM_RUSTUP_HOME || process.env.RUSTUP_HOME, RUSTUP_TOOLCHAIN: CONFIG.versions.rustToolchain || '' })
  const step = async (id, title, command, required = true) => {
    const started = Date.now()
    const logFile = path.join(logRoot, `${id}.log`)
    let result
    try { result = await command(logFile) } catch (error) { result = { exitCode: 1, stdout: '', stderr: error.message } }
    const status = result.exitCode === 0 ? 'PASS' : 'FAIL'
    const row = { id, title, required, status, exitCode: result.exitCode, durationMs: Date.now() - started, log: path.relative(ARTIFACT_ROOT, logFile).replaceAll(path.sep, '/'), timedOut: Boolean(result.timedOut) }
    steps.push(row)
    return { ...row, ...result }
  }

  const needsRust = !options.skipEngine || !options.skipAndroidNative
  const rustCheckOkay = (id) => tools.checks.find((check) => check.id === id)?.ok === true
  const sourcePinOk = ['rust-pin', 'cargo', 'rustc-version', 'cargo-version'].every(rustCheckOkay)
  const engineToolchainOk = sourcePinOk && rustCheckOkay('rust-target-windows')
  const androidToolchainOk = sourcePinOk && rustCheckOkay('rust-target-android')
  const requiredRustChecks = [
    ...(!options.skipEngine ? ['rust-target-windows'] : []),
    ...(!options.skipAndroidNative ? ['rust-target-android'] : [])
  ]
  const rustGatePass = sourcePinOk && requiredRustChecks.every(rustCheckOkay)
  gates.push({
    ...requiredCheck('rust-toolchain-pin', needsRust ? (rustGatePass ? 'PASS' : 'FAIL') : 'SKIP', JSON.stringify({ toolchain: tools.rustToolchain, rustc: tools.rustcVersion, cargo: tools.cargoVersion, targets: tools.installedTargets, requiredChecks: requiredRustChecks })),
    required: needsRust
  })
  gates.push(requiredCheck('node-version', process.version === `v${CONFIG.versions.node}` ? 'PASS' : 'FAIL', `expected ${CONFIG.versions.node}; found ${process.version}`))
  const gradleReady = ['java', 'java-version', 'gradle', 'android-sdk', 'android-ndk'].every((id) => tools.checks.find((check) => check.id === id)?.ok)
  gates.push({ ...requiredCheck('gradle-toolchain', gradleReady ? 'PASS' : (options.skipGradle ? 'SKIP' : 'FAIL'), 'JDK, Gradle, Android SDK and NDK must match the pinned versions'), required: !options.skipGradle })
  gates.push({
    ...requiredCheck(
      'android-prebuilt-sha256',
      options.skipAndroidNative ? prebuilt.status : 'SKIP',
      options.skipAndroidNative
        ? `reusing checked-in archives; provenance=${prebuilt.provenanceStatus}; hashes verified=${prebuilt.rows.every((row) => row.status === 'PASS')}`
        : 'source build stages fresh archives for this run; checked-in archive hashes are informational'
    ),
    required: options.skipAndroidNative
  })
  gates.push(requiredCheck('release-intent-source-guard', releaseSurface.status, JSON.stringify(releaseSurface)))
  gates.push(requiredCheck('pinned-build-configuration', buildConfiguration.status, JSON.stringify(buildConfiguration.checks)))
  gates.push(requiredCheck('shared-logo-data-sha256', sharedLogoData.status, JSON.stringify(sharedLogoData)))
  gates.push({
    ...requiredCheck('isolated-logo-image-toolchain', options.skipEngine ? 'SKIP' : logoImageToolchain.status, JSON.stringify(logoImageToolchain)),
    required: !options.skipEngine
  })
  gates.push(requiredCheck('desktop-runtime-inputs', desktopRuntimeInputs.status, JSON.stringify(desktopRuntimeInputs)))
  gates.push({
    ...requiredCheck('android-package-source-inputs', androidPackageSourceInputs.status, JSON.stringify(androidPackageSourceInputs)),
    required: !options.skipGradle
  })

  let androidPrebuiltRoot = path.join(ROOT, 'ui/composeApp/src/androidMain/cpp/prebuilt/arm64-v8a')
  let desktopBuildProvenance = null
  const desktopEnginesProvenance = []
  let desktopRuntimeProvenance = null
  let engineExe = path.join(buildRoot, 'desktop-engine', 'e2b.exe')
  if (!options.skipEngine) {
    if (!engineToolchainOk) {
      steps.push({ id: 'engine', title: 'Build desktop e2b engine', required: true, status: 'FAIL', exitCode: 1, detail: 'Rust toolchain is not pinned', log: null })
    } else {
      const skiaOk = fileMatches(tools.skiaWindowsArchive, CONFIG.skiaArchives.windowsX64.sha256)
      const vsOk = Boolean(tools.vsDevCmd && fs.existsSync(tools.vsDevCmd))
      if (!skiaOk || !vsOk || !cargoPath) {
        steps.push({ id: 'engine', title: 'Build desktop e2b engine', required: true, status: 'FAIL', exitCode: 1, detail: 'Cargo, MSVC environment or hash-pinned Skia archive is missing', log: null })
      } else {
        const sourceInputsSha256 = sha256Tree(DESKTOP_SOURCE_INPUTS)
        const crateSourceLockSha256 = sha256Tree(E2B_CRATE_SOURCE_INPUTS)
        const cargoTarget = path.join(buildRoot, 'cargo', 'desktop')
        const env = { ...cargoEnv, CARGO_TARGET_DIR: cargoTarget, SKIA_BINARIES_URL: skiaLocalArchiveUrl(tools.skiaWindowsArchive) }
        const script = path.join(logRoot, 'engine.cmd')
        makeBatch(script, { cwd: ROOT, env, executable: cargoPath, vsDevCmd: tools.vsDevCmd, args: ['build', '--locked', '--release', '--manifest-path', path.join(ROOT, 'engine/e2b/Cargo.toml'), '--target', CONFIG.targets.windowsRustTarget, '--target-dir', cargoTarget] })
        const result = await step('engine', 'Build desktop e2b engine', (logFile) => runProcess('cmd.exe', ['/d', '/s', '/c', `"${cmdQuote(script)}"`], { cwd: ROOT, env, logFile }))
        const builtExe = path.join(cargoTarget, CONFIG.targets.windowsRustTarget, 'release', 'e2b.exe')
        if (result.status === 'PASS' && fs.existsSync(builtExe)) {
          const sourceInputsAfterBuildSha256 = sha256Tree(DESKTOP_SOURCE_INPUTS)
          const crateSourceLockAfterBuildSha256 = sha256Tree(E2B_CRATE_SOURCE_INPUTS)
          if (sourceInputsAfterBuildSha256 !== sourceInputsSha256 || crateSourceLockAfterBuildSha256 !== crateSourceLockSha256) {
            result.status = 'FAIL'
            result.exitCode = 1
            result.detail = 'Desktop engine source inputs changed while Cargo was building'
          } else {
            fs.mkdirSync(path.dirname(engineExe), { recursive: true })
            fs.copyFileSync(builtExe, engineExe)
            result.artifact = path.relative(ARTIFACT_ROOT, engineExe).replaceAll(path.sep, '/')
            desktopBuildProvenance = {
              schema: 'framemark-desktop-engine-build/v1',
              status: 'PASS',
              rustToolchain: tools.rustToolchain,
              rustcVersion: tools.rustcVersion,
              rustcHost: tools.rustcHost,
              cargoVersion: tools.cargoVersion,
              sourceInputsSha256,
              crateSourceLockSha256,
              skiaArchiveSha256: CONFIG.skiaArchives.windowsX64.sha256,
              executableSha256: sha256File(engineExe)
            }
            desktopEnginesProvenance.push({
              binary: 'e2b.exe',
              crateManifest: 'engine/e2b/Cargo.toml',
              status: 'PASS',
              sourceInputsSha256,
              crateSourceLockSha256,
              skiaArchiveSha256: CONFIG.skiaArchives.windowsX64.sha256,
              executableSha256: desktopBuildProvenance.executableSha256
            })
            fs.writeFileSync(path.join(runRoot, 'desktop-engine-provenance.json'), JSON.stringify(desktopBuildProvenance, null, 2) + '\n', 'utf8')
          }
        } else if (result.status === 'PASS') {
          result.status = 'FAIL'
          result.exitCode = 1
          result.detail = `Cargo exited 0 but expected engine is missing: ${builtExe}`
        }
        if (result.status !== 'PASS') {
          const recorded = steps.find((row) => row.id === 'engine')
          if (recorded) { recorded.status = 'FAIL'; recorded.exitCode = result.exitCode || 1; recorded.detail = result.detail || 'Desktop engine build failed' }
          desktopBuildProvenance = { schema: 'framemark-desktop-engine-build/v1', status: 'FAIL', detail: result.detail || 'Cargo source build failed', sourceInputsSha256, crateSourceLockSha256 }
        }
      }
    }
  } else {
    steps.push({ id: 'engine', title: 'Build desktop e2b engine', required: options.release, status: 'SKIP', exitCode: 0, detail: '--skip-engine; a release result cannot pass without the source-built desktop engine' })
  }

  if (!desktopEnginesProvenance.some((row) => row.binary === 'e2b.exe')) {
    const engineStep = steps.find((row) => row.id === 'engine')
    desktopEnginesProvenance.push({
      binary: 'e2b.exe',
      crateManifest: 'engine/e2b/Cargo.toml',
      status: engineStep?.status || 'FAIL',
      sourceInputsSha256: desktopBuildProvenance?.sourceInputsSha256 || null,
      crateSourceLockSha256: desktopBuildProvenance?.crateSourceLockSha256 || null,
      executableSha256: null,
      detail: engineStep?.detail || '--skip-engine; no repository-source executable was staged'
    })
  }

  async function buildWindowsDesktopExe ({ id, binary, crateManifest, cargoBin, sourceInputs, crateSourceInputs, targetDir, needsSkia }) {
    const skiaOkay = !needsSkia || fileMatches(tools.skiaWindowsArchive, CONFIG.skiaArchives.windowsX64.sha256)
    const vsOkay = Boolean(tools.vsDevCmd && fs.existsSync(tools.vsDevCmd))
    if (!engineToolchainOk || !cargoPath || !vsOkay || !skiaOkay) {
      const detail = !engineToolchainOk
        ? 'Pinned Rust toolchain or Windows target is unavailable'
        : !cargoPath || !vsOkay
          ? 'Cargo or the Visual Studio x64 developer environment is unavailable'
          : 'Hash-pinned Windows Skia archive is missing'
      steps.push({ id, title: `Build ${binary} from repository sources`, required: true, status: 'FAIL', exitCode: 1, detail, log: null })
      desktopEnginesProvenance.push({ binary, crateManifest, status: 'FAIL', sourceInputsSha256: null, executableSha256: null, detail })
      return false
    }

    let sourceInputsSha256
    let crateSourceLockSha256
    try {
      sourceInputsSha256 = sha256Tree(sourceInputs)
      crateSourceLockSha256 = sha256Tree(crateSourceInputs)
    } catch (error) {
      const detail = error.message
      steps.push({ id, title: `Build ${binary} from repository sources`, required: true, status: 'FAIL', exitCode: 1, detail, log: null })
      desktopEnginesProvenance.push({ binary, crateManifest, status: 'FAIL', sourceInputsSha256: null, crateSourceLockSha256: null, executableSha256: null, detail })
      return false
    }

    const env = { ...cargoEnv, CARGO_TARGET_DIR: targetDir }
    if (needsSkia) env.SKIA_BINARIES_URL = skiaLocalArchiveUrl(tools.skiaWindowsArchive)
    const script = path.join(logRoot, `${id}.cmd`)
    makeBatch(script, {
      cwd: ROOT,
      env,
      executable: cargoPath,
      vsDevCmd: tools.vsDevCmd,
      args: ['build', '--locked', '--release', '--manifest-path', path.join(ROOT, crateManifest), '--bin', cargoBin,
        '--target', CONFIG.targets.windowsRustTarget, '--target-dir', targetDir]
    })
    const result = await step(id, `Build ${binary} from repository sources`, (logFile) => runProcess('cmd.exe', ['/d', '/s', '/c', `"${cmdQuote(script)}"`], { cwd: ROOT, env, logFile, timeoutMs: 90 * 60 * 1000 }))
    const builtExe = path.join(targetDir, CONFIG.targets.windowsRustTarget, 'release', binary)
    const stagedExe = path.join(buildRoot, 'desktop-engine', binary)
    let status = result.status
    let detail = result.detail || null
    if (status === 'PASS' && !fs.existsSync(builtExe)) {
      status = 'FAIL'
      detail = `Cargo exited 0 but expected binary is missing: ${builtExe}`
    }
    if (status === 'PASS') {
      const afterHash = sha256Tree(sourceInputs)
      const crateAfterHash = sha256Tree(crateSourceInputs)
      if (afterHash !== sourceInputsSha256 || crateAfterHash !== crateSourceLockSha256) {
        status = 'FAIL'
        detail = `${binary} source inputs changed while Cargo was building`
      }
    }
    if (status === 'PASS') {
      fs.mkdirSync(path.dirname(stagedExe), { recursive: true })
      fs.copyFileSync(builtExe, stagedExe)
      const row = steps.find((entry) => entry.id === id)
      if (row) row.artifact = path.relative(ARTIFACT_ROOT, stagedExe).replaceAll(path.sep, '/')
    } else {
      const row = steps.find((entry) => entry.id === id)
      if (row) { row.status = 'FAIL'; row.exitCode = 1; row.detail = detail || `${binary} build failed` }
    }
    desktopEnginesProvenance.push({
      binary,
      crateManifest,
      status,
      sourceInputsSha256,
      crateSourceLockSha256,
      skiaArchiveSha256: needsSkia ? CONFIG.skiaArchives.windowsX64.sha256 : null,
      executableSha256: status === 'PASS' ? sha256File(stagedExe) : null,
      detail
    })
    return status === 'PASS'
  }

  const sharedDesktopCargoTarget = path.join(buildRoot, 'cargo', 'desktop')
  const e4handBuilt = await buildWindowsDesktopExe({
    id: 'e4hand', binary: 'e4hand.exe', crateManifest: 'engine/e4-hand/Cargo.toml', cargoBin: 'e4hand',
    sourceInputs: E4HAND_SOURCE_INPUTS, crateSourceInputs: E4HAND_CRATE_SOURCE_INPUTS, targetDir: sharedDesktopCargoTarget, needsSkia: true
  })
  const e5WorkerBuilt = await buildWindowsDesktopExe({
    id: 'e5-worker', binary: 'e5-worker.exe', crateManifest: 'engine/e5/stream-proto/Cargo.toml', cargoBin: 'e5-worker',
    sourceInputs: E5_WORKER_SOURCE_INPUTS, crateSourceInputs: E5_WORKER_CRATE_SOURCE_INPUTS, targetDir: path.join(buildRoot, 'cargo', 'e5-worker'), needsSkia: false
  })
  fs.writeFileSync(path.join(runRoot, 'desktop-engines-provenance.json'), JSON.stringify({
    schema: 'framemark-desktop-engines-provenance/v1',
    status: desktopEnginesProvenance.length === 3 && desktopEnginesProvenance.every((row) => row.status === 'PASS') ? 'PASS' : 'FAIL',
    target: CONFIG.targets.windowsRustTarget,
    rustToolchain: tools.rustToolchain,
    rustcVersion: tools.rustcVersion,
    cargoVersion: tools.cargoVersion,
    engines: desktopEnginesProvenance
  }, null, 2) + '\n', 'utf8')

  const runtimeStepRequired = !options.skipEngine
  if (options.skipEngine) {
    steps.push({ id: 'desktop-runtime', title: 'Stage the complete relocatable desktop QA runtime', required: options.release, status: 'SKIP', exitCode: 0, detail: '--skip-engine means e2b was not built from repository sources; no complete runtime was staged', log: null })
  } else if (desktopEnginesProvenance.length !== 3 || desktopEnginesProvenance.some((row) => row.status !== 'PASS') || desktopRuntimeInputs.status !== 'PASS' || logoImageToolchain.status !== 'PASS') {
    steps.push({ id: 'desktop-runtime', title: 'Stage the complete relocatable desktop QA runtime', required: runtimeStepRequired, status: 'SKIP', exitCode: 0, detail: 'runtime staging requires all three source-built engines, valid repository asset inputs, and the isolated pinned sharp toolchain', log: null })
  } else {
    const runtimeOut = path.join(buildRoot, 'desktop-runtime')
    const runtimeExtract = path.join(tmpRoot, 'desktop-runtime-extract')
    const smokeOut = path.join(buildRoot, 'desktop-runtime-smoke')
    const runtimeReport = path.join(runRoot, 'desktop-runtime-provenance.json')
    const runtimeScript = path.join(__dirname, 'runtime.cjs')
    const result = await step('desktop-runtime', 'Stage the complete relocatable desktop QA runtime', (logFile) => runProcess(process.execPath, [
      runtimeScript,
      '--root', ROOT,
      '--artifact-root', ARTIFACT_ROOT,
      '--out', runtimeOut,
      '--extract', runtimeExtract,
      '--bundle', path.join(ROOT, 'ui/composeApp/src/androidMain/assets/x1ir.zip'),
      '--engine-dir', path.join(buildRoot, 'desktop-engine'),
      '--engine-provenance', path.join(runRoot, 'desktop-engines-provenance.json'),
      '--smoke-dir', smokeOut,
      '--report', runtimeReport,
      '--build-id', runId
    ], { cwd: ROOT, env: envBase, logFile, timeoutMs: 30 * 60 * 1000 }))
    if (result.status === 'PASS' && fs.existsSync(runtimeReport)) {
      try { desktopRuntimeProvenance = JSON.parse(fs.readFileSync(runtimeReport, 'utf8')) } catch {
        const row = steps.find((entry) => entry.id === 'desktop-runtime')
        if (row) { row.status = 'FAIL'; row.exitCode = 1; row.detail = 'Runtime packager returned success without a valid provenance report' }
      }
      const row = steps.find((entry) => entry.id === 'desktop-runtime')
      if (row && desktopRuntimeProvenance) {
        // The packager proves the exact-version logo map is inside the package
        // and readable from both roots; a run that reports success without it
        // cannot ship a chosen logo version, so it must not pass the build.
        const logo = desktopRuntimeProvenance.logoVersions
        const expected = desktopRuntimeProvenance.registry?.logoVersions?.uniqueSelectedAssetKeys
        if (!logo || !Number.isInteger(logo.entries) || logo.entries <= 0) {
          row.status = 'FAIL'
          row.exitCode = 1
          row.detail = 'Runtime provenance carries no verified exact-logo version map'
        } else if (expected != null && logo.entries !== expected) {
          row.status = 'FAIL'
          row.exitCode = 1
          row.detail = `Runtime logo version count mismatch: registry=${expected}, verified=${logo.entries}`
        } else if (logo.verifiedBothRoots !== logo.entries) {
          row.status = 'FAIL'
          row.exitCode = 1
          row.detail = `Runtime logo versions verified in both roots: ${logo.verifiedBothRoots}/${logo.entries}`
        }
        row.artifact = desktopRuntimeProvenance.runtime
      }
    } else if (result.status === 'PASS') {
      const row = steps.find((entry) => entry.id === 'desktop-runtime')
      if (row) { row.status = 'FAIL'; row.exitCode = 1; row.detail = `Runtime provenance report is missing: ${runtimeReport}` }
    }
  }

  let nativeBuildProvenance = null
  if (options.skipAndroidNative) {
    const okay = prebuilt.status === 'PASS'
    steps.push({ id: 'android-native', title: 'Build Android static renderer libraries', required: true, status: okay ? 'PASS' : 'FAIL', exitCode: okay ? 0 : 1, detail: okay ? '--skip-android-native; checked-in archive hashes verified (debug reuse only)' : '--skip-android-native; one or more checked-in archive hashes failed' })
  } else if (!androidToolchainOk) {
    steps.push({ id: 'android-native', title: 'Build Android static renderer libraries', required: true, status: 'FAIL', exitCode: 1, detail: 'Rust toolchain is not pinned; source build is blocked', log: null })
  } else {
    const ndkClang = tools.ndkHome && path.join(tools.ndkHome, 'toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe')
    const androidSkiaOk = fileMatches(tools.skiaAndroidArchive, CONFIG.skiaArchives.androidArm64.sha256)
    if (!cargoPath || !ndkClang || !fs.existsSync(ndkClang) || !androidSkiaOk) {
      steps.push({ id: 'android-native', title: 'Build Android static renderer libraries', required: true, status: 'FAIL', exitCode: 1, detail: 'Cargo, NDK clang or hash-pinned Android Skia archive is missing', log: null })
    } else {
      androidPrebuiltRoot = path.join(buildRoot, 'android-prebuilt', CONFIG.targets.androidAbi)
      fs.mkdirSync(androidPrebuiltRoot, { recursive: true })
      const cargoTarget = path.join(buildRoot, 'cargo', 'android')
      const rustFlags = `-C link-arg=--target=aarch64-linux-android26 -C link-arg=--sysroot=${path.join(tools.ndkHome, 'toolchains/llvm/prebuilt/windows-x86_64/sysroot').replaceAll('\\', '/')}`
      const ndkBin = path.dirname(ndkClang)
      const pathKey = Object.keys(cargoEnv).find((key) => key.toLowerCase() === 'path') || 'PATH'
      const env = { ...cargoEnv, CARGO_TARGET_DIR: cargoTarget,
        SKIA_BINARIES_URL: skiaLocalArchiveUrl(tools.skiaAndroidArchive), ANDROID_NDK: tools.ndkHome,
        CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER: ndkClang,
        CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS: rustFlags,
        CC_aarch64_linux_android: ndkClang,
        CXX_aarch64_linux_android: path.join(ndkBin, 'clang++.exe'),
        AR_aarch64_linux_android: path.join(ndkBin, 'llvm-ar.exe'),
        CC_SHELL_ESCAPED_FLAGS: '1',
        CFLAGS_aarch64_linux_android: `--target=aarch64-linux-android26 --sysroot="${path.join(tools.ndkHome, 'toolchains/llvm/prebuilt/windows-x86_64/sysroot').replaceAll('\\', '/')}"`,
        [pathKey]: `${ndkBin}${path.delimiter}${cargoEnv[pathKey] || ''}` }
      const sources = [
        ['fm-android-engine', 'bridge/android/Cargo.toml'],
        ['fm-android-hand', 'bridge/android-hand/Cargo.toml'],
        ['fm-android-export', 'bridge/android-export/Cargo.toml']
      ]
      const sourceInputsSha256 = sha256Tree(ANDROID_SOURCE_INPUTS)
      let allPassed = true
      for (const [id, manifest] of sources) {
        const script = path.join(logRoot, `${id}.cmd`)
        makeBatch(script, { cwd: ROOT, env, executable: cargoPath, vsDevCmd: tools.vsDevCmd, args: ['build', '--locked', '--release', '--manifest-path', path.join(ROOT, manifest), '--target', CONFIG.targets.androidRustTarget, '--lib', '--target-dir', cargoTarget] })
        const built = await step(id, `Build ${id}`, (logFile) => runProcess('cmd.exe', ['/d', '/s', '/c', `"${cmdQuote(script)}"`], { cwd: ROOT, env, logFile }))
        if (built.status !== 'PASS') { allPassed = false; break }
        const staticName = id === 'fm-android-engine' ? 'libfm_android_engine.a' : id === 'fm-android-hand' ? 'libfm_android_hand.a' : 'libfm_android_export.a'
        const candidate = path.join(cargoTarget, CONFIG.targets.androidRustTarget, 'release', staticName)
        if (!fs.existsSync(candidate)) { allPassed = false; steps.push({ id: `${id}-artifact`, title: `Verify ${id} artifact`, required: true, status: 'FAIL', exitCode: 1, detail: `missing ${candidate}` }); break }
        fs.copyFileSync(candidate, path.join(androidPrebuiltRoot, staticName))
      }
      if (allPassed && sha256Tree(ANDROID_SOURCE_INPUTS) !== sourceInputsSha256) {
        allPassed = false
        steps.push({ id: 'android-native-source-stability', title: 'Verify Android native inputs stayed stable during build', required: true, status: 'FAIL', exitCode: 1, detail: 'Android native source inputs changed while Cargo was building' })
      }
      if (allPassed) {
        const libraryRows = fs.readdirSync(androidPrebuiltRoot).filter((name) => name.endsWith('.a')).sort().map((name) => ({ name, sha256: sha256File(path.join(androidPrebuiltRoot, name)) }))
        nativeBuildProvenance = { schema: 'framemark-android-native-build/v1', status: 'PASS', rustToolchain: tools.rustToolchain, rustcVersion: tools.rustcVersion, rustcHost: tools.rustcHost, cargoVersion: tools.cargoVersion, sourceInputsSha256, skiaArchiveSha256: CONFIG.skiaArchives.androidArm64.sha256, libraries: libraryRows }
        fs.writeFileSync(path.join(runRoot, 'android-native-provenance.json'), JSON.stringify(nativeBuildProvenance, null, 2) + '\n', 'utf8')
        steps.push({ id: 'android-native', title: 'Stage Android static renderer libraries', required: true, status: 'PASS', exitCode: 0, artifact: path.relative(ARTIFACT_ROOT, androidPrebuiltRoot).replaceAll(path.sep, '/'), log: null })
      } else {
        steps.push({ id: 'android-native', title: 'Stage Android static renderer libraries', required: true, status: 'FAIL', exitCode: 1, detail: 'one or more Cargo crate steps failed', log: null })
      }
    }
  }
  if (options.release) {
    const sourceBuildStatus = nativeBuildProvenance?.status === 'PASS' ? 'PASS' : (options.skipAndroidNative ? 'SKIP' : 'FAIL')
    const detail = sourceBuildStatus === 'PASS'
      ? 'all three Android static libraries were built from repository Cargo sources in this run'
      : options.skipAndroidNative
        ? 'release builds may not reuse provenance-unverified checked-in static archives'
        : 'Android source build did not produce a verified set of all three static libraries'
    gates.push(requiredCheck('release-native-source-build', sourceBuildStatus, detail))
  }

  const gradleTasks = [':composeApp:compileKotlinDesktop', ':composeApp:assembleDebug']
  if (options.release) gradleTasks.push(':composeApp:assembleRelease')
  const releaseInputsReady = !options.release || (
    steps.some((row) => row.id === 'desktop-runtime' && row.status === 'PASS') &&
    nativeBuildProvenance?.status === 'PASS'
  )
  if (!options.skipGradle) {
    if (!releaseInputsReady) {
      steps.push({ id: 'gradle', title: 'Build desktop and Android variants', required: true, status: 'SKIP', exitCode: 0, detail: 'release packaging requires a source-built desktop engine and all three source-built Android libraries' })
    } else if (!tools.java || !tools.gradleLauncher || !tools.androidSdk || !fs.existsSync(tools.java) || !fs.existsSync(tools.gradleLauncher)) {
      steps.push({ id: 'gradle', title: 'Build desktop and Android variants', required: true, status: 'FAIL', exitCode: 1, detail: 'JDK, Gradle or Android SDK is unresolved', log: null })
    } else {
      const gradleLibs = path.join(tools.gradleHome, 'lib', '*')
      const gradleIsolationScript = path.join(tmpRoot, 'gradle-isolated-output.init.gradle')
      writeGradleIsolationScript(gradleIsolationScript)
      const gradleArgs = ['-Dorg.gradle.appname=gradle', '-classpath', gradleLibs, 'org.gradle.launcher.GradleMain', '--no-daemon', '--project-cache-dir', path.join(tmpRoot, 'gradle-project-cache'), '--init-script', gradleIsolationScript, `-PframemarkQaSuffix=${options.qaSuffix}`]
      // Kotlin's incremental path converter cannot relativize generated sources
      // across Windows drive roots. This is an isolated full build anyway; avoid
      // a reproducible daemon failure/fallback when sources and outputs differ.
      if (process.platform === 'win32' && path.parse(UI).root.toLowerCase() !== path.parse(gradleOutputRoot).root.toLowerCase()) {
        gradleArgs.push('-Pkotlin.incremental=false')
      }
      if (options.releaseQa) gradleArgs.push(`-PframemarkQaReleaseSuffix=${options.qaReleaseSuffix}`)
      gradleArgs.push(...gradleTasks)
      if (options.release) gradleArgs.push('--stacktrace')
      const env = { ...envBase, FM_ANDROID_PREBUILT_ROOT: androidPrebuiltRoot, FM_E2B_EXE: engineExe }
      const built = await step('gradle', 'Build desktop and Android variants', (logFile) => runProcess(tools.java, gradleArgs, { cwd: UI, env, logFile, timeoutMs: 90 * 60 * 1000 }))
      if (built.status === 'PASS') {
        const debugApk = path.join(gradleOutputRoot, 'composeApp/outputs/apk/debug/composeApp-debug.apk')
        if (fs.existsSync(debugApk)) gates.push(await verifyApk('debug-apk-arm64', debugApk, tools, step, `com.framemark.native${options.qaSuffix}`))
        else gates.push(requiredCheck('debug-apk-arm64', 'FAIL', `debug APK missing: ${debugApk}`))
      }
    }
  } else {
    steps.push({ id: 'gradle', title: 'Build desktop and Android variants', required: true, status: 'SKIP', exitCode: 0, detail: '--skip-gradle' })
  }

  // ARCH1 (ARCH-MODULES.md §2): the architecture rules are Konsist tests that live in
  // :composeApp:desktopTest. This step runs only the `*KonsistTest*` classes, so the
  // architecture gate and the acceptance suite share one mechanism and one source of
  // truth — there is deliberately no parallel hand-written import scanner here.
  // `violations-allowlist.txt` is a shrink-only ratchet: a new violation fails this
  // step, and a stale allowlist entry fails ArchAllowlistHygieneKonsistTest.
  if (options.skipGradle) {
    steps.push({ id: 'arch-konsist', title: 'Architecture rules (Konsist tests)', required: true, status: 'SKIP', exitCode: 0, detail: '--skip-gradle' })
  } else if (!tools.java || !tools.gradleLauncher || !fs.existsSync(tools.java) || !fs.existsSync(tools.gradleLauncher)) {
    steps.push({ id: 'arch-konsist', title: 'Architecture rules (Konsist tests)', required: true, status: 'FAIL', exitCode: 1, detail: 'JDK or Gradle is unresolved', log: null })
  } else {
    const archIsolationScript = path.join(tmpRoot, 'gradle-arch-isolation.init.gradle')
    writeGradleIsolationScript(archIsolationScript)
    const archArgs = [
      '-Dorg.gradle.appname=gradle', '-classpath', path.join(tools.gradleHome, 'lib', '*'),
      'org.gradle.launcher.GradleMain', '--no-daemon',
      '--project-cache-dir', path.join(tmpRoot, 'gradle-arch-project-cache'),
      '--init-script', archIsolationScript,
      `-PframemarkQaSuffix=${options.qaSuffix}`,
      ':composeApp:desktopTest', '--tests', '*KonsistTest*',
    ]
    if (process.platform === 'win32' && path.parse(UI).root.toLowerCase() !== path.parse(gradleOutputRoot).root.toLowerCase()) archArgs.push('-Pkotlin.incremental=false')
    await step('arch-konsist', 'Architecture rules (Konsist tests)', (logFile) => runProcess(tools.java, archArgs, { cwd: UI, env: envBase, logFile, timeoutMs: 45 * 60 * 1000 }))
  }
  const archStep = steps.find((row) => row.id === 'arch-konsist')
  gates.push({
    ...requiredCheck(
      'arch-konsist',
      archStep ? archStep.status : 'FAIL',
      archStep
        ? `Konsist rules: layer-direction, platform-leak, single-source, qa-package-isolation, launch-state-isolation, evidence-hook-literal, control-coverage; allowlist=src/desktopTest/resources/arch/violations-allowlist.txt (shrink-only); log=${archStep.log || 'n/a'}`
        : 'architecture test step did not run'
    ),
    required: !options.skipGradle
  })

  // ARCH1: the only two node-level architecture guards that Konsist cannot express.
  // Both are file/metadata checks, so they cost milliseconds and never duplicate the
  // Konsist import-graph analysis.
  const desktopClassManifest = verifyDesktopClassManifest()
  gates.push(requiredCheck(
    'desktop-class-manifest',
    desktopClassManifest.status,
    JSON.stringify({ classes: desktopClassManifest.classes, failed: desktopClassManifest.failed, detail: desktopClassManifest.detail })
  ))
  const packagingSurface = verifyPackagingSurface()
  gates.push(requiredCheck(
    'desktop-package-surface',
    packagingSurface.status,
    JSON.stringify({ failed: packagingSurface.failed, checks: packagingSurface.checks })
  ))

  const gradleStep = steps.find((row) => row.id === 'gradle')
  let androidPackageSourceIntegrity
  if (options.skipGradle) {
    androidPackageSourceIntegrity = { status: 'SKIP', before: androidPackageSourceInputs, after: null, detail: '--skip-gradle' }
  } else if (!gradleStep || gradleStep.status !== 'PASS') {
    const after = verifyAndroidPackageSourceInputs()
    androidPackageSourceIntegrity = { status: 'SKIP', before: androidPackageSourceInputs, after, detail: 'Gradle did not complete; package compilation did not establish consumed-source stability' }
  } else {
    const after = verifyAndroidPackageSourceInputs()
    const stable = androidPackageSourceInputs.status === 'PASS' && after.status === 'PASS' && androidPackageSourceInputs.sourceInputsSha256 === after.sourceInputsSha256
    androidPackageSourceIntegrity = { status: stable ? 'PASS' : 'FAIL', before: androidPackageSourceInputs, after }
  }
  gates.push({
    ...requiredCheck('android-package-source-stability', androidPackageSourceIntegrity.status, JSON.stringify(androidPackageSourceIntegrity)),
    required: !options.skipGradle
  })

  if (options.release) {
    const releaseApk = findReleaseApk(gradleOutputRoot)
    if (releaseApk) {
      if (options.releaseQa) {
        gates.push(await verifyApk('release-qa-apk-arm64', releaseApk, tools, step, `com.framemark.native${options.qaReleaseSuffix}`))
        gates.push(await verifyQaSignature(releaseApk, tools, step))
      } else if (options.sign) {
        const signed = await signRelease(releaseApk, tools, envBase, runRoot, step)
        gates.push(requiredCheck('release-signature', signed.status, signed.detail || 'signing did not complete'))
        if (signed.status === 'PASS') gates.push(await verifyApk('signed-release-apk-arm64', signed.signedApk, tools, step, 'com.framemark.native'))
      } else {
        gates.push(await verifyApk('release-apk-arm64', releaseApk, tools, step, 'com.framemark.native'))
        gates.push(requiredCheck('release-signature', 'SKIP', 'release APK was built unsigned; --sign was not requested'))
      }
    } else {
      gates.push(requiredCheck('release-apk-arm64', 'FAIL', 'release APK does not exist after Gradle task'))
    }
  }

  if (options.skipGates) {
    gates.push(requiredCheck('required-gates-enabled', 'SKIP', '--skip-gates bypassed required build/release gates'))
  }
  if (!options.skipGates && !releaseSurface.debugGuardContainsQaHooks) {
    gates.push(requiredCheck('release-intent-source-guard', 'FAIL', 'QA Intent hook guard is missing or does not end before normal UI content'))
  }

  const requiredFailures = [
    ...steps.filter((row) => row.required && row.status !== 'PASS'),
    ...gates.filter((row) => row.required && row.status !== 'PASS')
  ]
  const exitCode = requiredFailures.length ? 1 : 0
  const report = {
    schema: 'framemark-native-build-report/v1',
    createdAt: new Date().toISOString(),
    repoRoot: ROOT,
    head: readHead(),
    options,
    releaseArtifactClassification: options.release ? (options.releaseQa ? 'LOCAL_QA_DEBUG_SIGNED_NOT_PUBLISHABLE' : 'UNSIGNED_OR_PRODUCTION_SIGNED') : null,
    toolchain: CONFIG,
    preflight,
    desktopBuildProvenance,
    desktopEnginesProvenance,
    desktopRuntimeProvenance,
    nativeBuildProvenance,
    androidPackageSourceIntegrity,
    steps,
    gates,
    summary: { exitCode, requiredFailures: requiredFailures.map((row) => row.id), result: exitCode === 0 ? 'PASS' : 'FAIL' }
  }
  fs.writeFileSync(path.join(runRoot, 'build-report.json'), JSON.stringify(report, null, 2) + '\n', 'utf8')
  process.stdout.write(JSON.stringify(report.summary, null, 2) + `\nreport: ${path.relative(ROOT, path.join(runRoot, 'build-report.json')).replaceAll(path.sep, '/')}\n`)
  return exitCode
}

function readHead () {
  try {
    const { execFileSync } = require('node:child_process')
    return execFileSync('git', ['rev-parse', 'HEAD'], { cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim()
  } catch { return null }
}

function findReleaseApk (gradleOutputRoot) {
  const dir = path.join(gradleOutputRoot, 'composeApp/outputs/apk/release')
  return fs.existsSync(dir) ? listFiles(dir).find((file) => file.toLowerCase().endsWith('.apk')) || null : null
}

async function verifyApk (id, apk, tools, step, expectedApplicationId = 'com.framemark.native') {
  const jarResult = await step(`${id}-entries`, 'Inspect APK native library entries', (logFile) => runProcess(path.join(tools.javaHome, 'bin', 'jar.exe'), ['tf', apk], { cwd: ROOT, env: process.env, logFile, timeoutMs: 30000 }))
  if (jarResult.status !== 'PASS') return requiredCheck(id, 'FAIL', `could not inspect APK: ${apk}`)
  const native = jarResult.stdout.split(/\r?\n/).filter((line) => /^lib\/[^/]+\/.*\.so$/i.test(line))
  const abis = [...new Set(native.map((line) => line.split('/')[1]))].sort()
  const hasEngine = native.includes('lib/arm64-v8a/libframemark_engine.so')
  const onlyArm64 = abis.length === 1 && abis[0] === CONFIG.targets.androidAbi && hasEngine
  const aapt = path.join(tools.androidSdk, 'build-tools', CONFIG.versions.androidBuildTools, 'aapt.exe')
  const badging = await step(`${id}-badging`, 'Verify APK application id', (logFile) => runProcess(aapt, ['dump', 'badging', apk], { cwd: ROOT, env: process.env, logFile, timeoutMs: 30000 }))
  const packageMatch = badging.stdout.match(/^package: name='([^']+)'/m)
  const packageOk = badging.status === 'PASS' && packageMatch?.[1] === expectedApplicationId
  return requiredCheck(id, onlyArm64 && packageOk ? 'PASS' : 'FAIL', JSON.stringify({ abis, hasEngine, packageOk, expectedApplicationId, actualApplicationId: packageMatch?.[1] || null, apk: path.relative(ROOT, apk).replaceAll(path.sep, '/') }))
}

async function verifyQaSignature (apk, tools, step) {
  const apksignerJar = path.join(tools.androidSdk, 'build-tools', CONFIG.versions.androidBuildTools, 'lib', 'apksigner.jar')
  const verification = await step('verify-release-qa-signature', 'Verify isolated QA release signature', (logFile) => runProcess(tools.java, ['-jar', apksignerJar, 'verify', '--verbose', '--print-certs', apk], { cwd: ROOT, env: process.env, logFile, timeoutMs: 30000 }))
  const subjects = [...verification.stdout.matchAll(/^Signer #\d+ certificate DN:\s*([^\r\n]+)$/gm)]
  const debugCertificate = subjects.length === 1 && subjects[0][1].split(',').some((part) => part.trim() === 'CN=Android Debug')
  return requiredCheck(
    'release-qa-debug-signature',
    verification.status === 'PASS' && debugCertificate ? 'PASS' : 'FAIL',
    JSON.stringify({ verification: verification.status, debugCertificate, apk: path.relative(ROOT, apk).replaceAll(path.sep, '/'), classification: 'LOCAL_QA_DEBUG_SIGNED_NOT_PUBLISHABLE' })
  )
}

async function signRelease (apk, tools, envBase, runRoot, step) {
  const keys = ['FM_KEYSTORE_PATH', 'FM_KEY_ALIAS', 'FM_KEYSTORE_PASSWORD', 'FM_KEY_PASSWORD', 'FM_SIGNING_CERT_SHA256']
  const missing = keys.filter((key) => !process.env[key])
  if (missing.length) return { status: 'FAIL', detail: `missing signing variables: ${missing.join(', ')}` }
  const apksignerJar = path.join(tools.androidSdk, 'build-tools', CONFIG.versions.androidBuildTools, 'lib', 'apksigner.jar')
  const signedApk = path.join(runRoot, 'artifacts', 'release-signed.apk')
  const signArgs = ['-jar', apksignerJar, 'sign', '--ks', process.env.FM_KEYSTORE_PATH, '--ks-key-alias', process.env.FM_KEY_ALIAS, '--ks-pass', 'env:FM_KEYSTORE_PASSWORD', '--key-pass', 'env:FM_KEY_PASSWORD', '--out', signedApk, apk]
  const sign = await step('sign-release', 'Sign Android release APK', (logFile) => runProcess(tools.java, signArgs, { cwd: ROOT, env: envBase, logFile, timeoutMs: 5 * 60 * 1000 }))
  if (sign.status !== 'PASS') return { status: 'FAIL', detail: 'apksigner failed' }
  const verify = await step('verify-signature', 'Verify signed APK certificate', (logFile) => runProcess(tools.java, ['-jar', apksignerJar, 'verify', '--verbose', '--print-certs', signedApk], { cwd: ROOT, env: envBase, logFile, timeoutMs: 5 * 60 * 1000 }))
  const actual = (verify.stdout.match(/certificate SHA-256 digest:\s*([0-9a-f]+)/i) || [])[1] || ''
  const expected = process.env.FM_SIGNING_CERT_SHA256.replaceAll(':', '').toLowerCase()
  return { status: verify.status === 'PASS' && actual.toLowerCase() === expected ? 'PASS' : 'FAIL', signedApk, detail: actual.toLowerCase() === expected ? 'certificate fingerprint verified' : 'certificate fingerprint mismatch' }
}

function runCli () {
  const options = parseArgs(process.argv.slice(2))
  return main(options).then((code) => { process.exitCode = code }).catch((error) => {
    process.stderr.write(`FrameMark build error: ${error.stack || error.message}\n`)
    process.exitCode = 2
  })
}

// Only execute the build when this file is the CLI entry point; node:test
// requires the module to expose checkReleaseSurface without starting a build.
if (require.main === module) runCli()

module.exports = { checkReleaseSurface, verifyDesktopClassManifest, verifyPackagingSurface }
