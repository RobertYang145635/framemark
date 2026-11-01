'use strict'

// Windows-only build orchestrator. All project-relative paths are derived from
// this checkout; tool locations are supplied through the FM_* environment vars.
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { spawn } = require('node:child_process')

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
const options = parseArgs(process.argv.slice(2))

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
  const source = fs.readFileSync(sourcePath, 'utf8').replaceAll('\r\n', '\n')
  const manifest = fs.readFileSync(manifestPath, 'utf8').replaceAll('\r\n', '\n')
  const guard = source.indexOf('if (BuildConfig.DEBUG) {')
  const content = source.indexOf('setContent { FrameMarkApp() }')
  const close = source.lastIndexOf('\n        }\n        setContent { FrameMarkApp() }', content)
  const qaStart = source.indexOf('LaunchOptions.openCardId = intent?.getStringExtra("fmCard")')
  const contained = guard >= 0 && qaStart > guard && close >= guard && content > close
  const exportedLauncher = /<activity[\s\S]*?android:exported="true"[\s\S]*?<\/activity>/.test(manifest) &&
    /<action android:name="android\.intent\.action\.MAIN"\s*\/>/.test(manifest) &&
    /<category android:name="android\.intent\.category\.LAUNCHER"\s*\/>/.test(manifest)
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

function verifyDesktopRuntimeInputs () {
  const paths = [
    'ui/composeApp/src/androidMain/assets/x1ir.zip',
    'bridge/desktop/cards-175.json',
    'bridge/desktop/ir-slot-binding.json',
    'engine/data/taxonomy.json', 'engine/data/capabilities.json', 'engine/data/controls.json', 'engine/data/logos.json',
    'ui/composeApp/src/commonMain/composeResources/files/data/inspector-meta.json',
    'src/renderer/src/core/controls/data/controls.oppo.json',
    'src/renderer/src/core/controls/data/controls.xiaomi.json',
    'src/renderer/src/core/controls/data/controls.vivo.json'
  ]
  const rows = paths.map((relative) => {
    const filename = path.join(ROOT, relative)
    return { path: relative, status: fs.existsSync(filename) ? 'PASS' : 'FAIL', sha256: fs.existsSync(filename) ? sha256File(filename) : null }
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

async function main () {
  if (options.help) { process.stdout.write(usage()); return 0 }
  if (process.platform !== 'win32') throw new Error('This build entry point requires Windows; use the Gradle/Cargo commands directly on other hosts.')

  const tools = resolveTools()
  const prebuilt = verifyCheckedPrebuilt()
  const releaseSurface = verifyReleaseSurface()
  const buildConfiguration = verifyPinnedBuildConfiguration()
  const sharedLogoData = verifySharedLogoData()
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
  } else if (desktopEnginesProvenance.length !== 3 || desktopEnginesProvenance.some((row) => row.status !== 'PASS') || !desktopRuntimeInputs.status || desktopRuntimeInputs.status !== 'PASS') {
    steps.push({ id: 'desktop-runtime', title: 'Stage the complete relocatable desktop QA runtime', required: runtimeStepRequired, status: 'SKIP', exitCode: 0, detail: 'runtime staging requires all three source-built engines and valid repository asset inputs', log: null })
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
      if (row && desktopRuntimeProvenance) row.artifact = desktopRuntimeProvenance.runtime
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
  const debugCertificate = /certificate DN:\s*CN=Android Debug(?:,|$)/im.test(verification.stdout)
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

main().then((code) => { process.exitCode = code }).catch((error) => {
  process.stderr.write(`FrameMark build error: ${error.stack || error.message}\n`)
  process.exitCode = 2
})
