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
// WP-FIX1: desktop classes that a package absolutely must ship. `string.split`
// keeps the list readable; paths are relative to the desktop main class root and
// were taken from a verified build's class directory (the brief's
// `shell\desktop\DesktopShellKt.class` is `shell\DesktopShellKt.class`: the file
//  is `commonMain/.../shell/DesktopShell.kt`, package `com.framemark.native.shell`;
//  `shell/desktop/` is a different package whose classes are also listed below).
const DESKTOP_REQUIRED_CLASSES = [
  'com/framemark/native/theme/FmRhythm.class',
  'com/framemark/native/inspector/TextSlotNavigatorKt.class',
  'com/framemark/native/inspector/TextSlotAxis.class',
  'com/framemark/native/shell/DesktopShellKt.class',
  'com/framemark/native/shell/desktop/ExportDialogKt.class'
]
// WP-FIX1: `ui/gradle.properties` enables the build cache, and a cached
// `compileKotlinDesktop` reuses stale classes (the WP-Q4 run shipped an APK whose
// desktop classes came `FROM-CACHE`). `GRADLE_OPTS=-Dorg.gradle.caching=false` is
// not honoured (measured in WP-Q4); `--no-build-cache` is. Both the flag and this
// log assertion are needed: the flag prevents the reuse, the assertion proves it.
const DESKTOP_CACHE_TASK = 'compileKotlinDesktop'

// The JNI shim and its packaging declarations are compiled into the app, not
// into the Rust static libraries. Fingerprint this boundary independently.
const ANDROID_PACKAGE_SOURCE_INPUTS = [
  'ui/composeApp/src/androidMain/cpp/fm_engine_jni.cpp',
  'ui/composeApp/src/androidMain/cpp/CMakeLists.txt',
  'ui/composeApp/build.gradle.kts'
]
function parseArgs (args) {
  const out = { dryRun: false, skipEngine: false, skipAndroidNative: false, skipGradle: false, release: false, sign: false, releaseQa: false, skipGates: false, package: false, debugApk: false, jobName: '', desktopClassCheck: '', qaSuffix: process.env.FM_QA_SUFFIX || '.qa20261102', qaReleaseSuffix: process.env.FM_QA_RELEASE_SUFFIX || '.qa20261102release' }
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
    else if (arg === '--package') out.package = true
    else if (arg === '--debug-apk') out.debugApk = true
    else if (arg === '--job-name') out.jobName = args[++i] || ''
    else if (arg === '--check-desktop-classes') {
      out.desktopClassCheck = args[++i] || ''
      if (!out.desktopClassCheck) throw new Error('--check-desktop-classes needs a class root directory')
    }
    else if (arg === '--help' || arg === '-h') out.help = true
    else throw new Error(`Unknown option: ${arg}`)
  }
  if (out.sign && !out.release && !out.package) throw new Error('--sign requires --release (or --package, to sign the APK that gets packaged)')
  if (out.releaseQa && !out.release) throw new Error('--release-qa requires --release')
  if (out.releaseQa && out.sign) throw new Error('--release-qa uses the Android debug signing key; do not combine with --sign')
  if (out.package && out.skipGradle) throw new Error('--package needs the Gradle steps; do not combine it with --skip-gradle')
  // --package and --release-qa both select the release variant's application-id
  // suffix; --package uses the debug build's suffix (install continuity) and
  // --release-qa uses its own, so one run can only mean one of them.
  if (out.package && out.releaseQa) throw new Error('--package builds the release APK with the debug build\'s application-id suffix; do not combine it with --release-qa')
  if (out.debugApk && !out.package) throw new Error('--debug-apk is only meaningful with --package: it packages the debug QA APK instead of the release APK')
  if (out.debugApk && (out.release || out.releaseQa || out.sign)) throw new Error('--debug-apk packages the debug QA APK; it cannot be combined with --release, --release-qa or --sign')
  if (out.jobName && !/^[a-zA-Z0-9._-]{1,80}$/.test(out.jobName)) throw new Error('--job-name may contain only letters, digits, dot, underscore and dash')
  if (!/^\.[A-Za-z][A-Za-z0-9_.]*$/.test(out.qaSuffix)) throw new Error('FM_QA_SUFFIX must be a dot-prefixed Android application ID suffix')
  if (!/^\.[A-Za-z][A-Za-z0-9_.]*$/.test(out.qaReleaseSuffix)) throw new Error('FM_QA_RELEASE_SUFFIX must be a dot-prefixed Android application ID suffix')
  // Report compatibility: the opt-in flags must not appear in `options` (and
  // therefore not in build-report.json) when the mode was not requested.
  if (!out.package) delete out.package
  if (!out.debugApk) delete out.debugApk
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
  --sign                    sign the release APK with FM_SIGNING_* variables (this is
                            the publishing path for --package; without it the shipped
                            release APK would still carry the Android debug key)
  --release-qa              build a debug-signed, isolated release variant for local QA only
  FM_QA_SUFFIX              isolated debug application id suffix (default: .qa20261102)
  FM_QA_RELEASE_SUFFIX      isolated release QA suffix (default: .qa20261102release)
  --skip-gates              mark required gates skipped; the command exits nonzero
  --package                 opt-in release packaging: run the desktop test suite,
                            build the QA-free product jar (-PfmQaSurface=false) and
                            the arm64 release APK (-PframemarkQaReleaseSuffix=<the
                            debug build's suffix>, so the app id stays installable
                            over the debug QA build), stage a jlink runtime, write
                            the portable win64 ZIP + arm64 APK + SHA256SUMS under
                            build/runs/<runId>/deliverables, and smoke the launcher.
                            The APK must not be application-debuggable. Without
                            --sign the release variant keeps the Android debug key:
                            SHA256SUMS.txt is stamped "QA ARTIFACT — DEBUG-SIGNED,
                            NOT PUBLISHABLE" and the report classifies it
                            LOCAL_QA_DEBUG_SIGNED_NOT_PUBLISHABLE. --sign with
                            FM_SIGNING_* produces the production-signed package.
  --debug-apk               with --package: produce the debug QA APK instead,
                            named <name>-arm64-DEBUGQA.apk, excluded from
                            SHA256SUMS.txt and not a release artifact. The APK is
                            application-debuggable, so package-apk-class fails and
                            the command exits nonzero.
  --job-name <name>         label logs under ${CONFIG.artifactDirectory}
  --check-desktop-classes <dir>
                            run only the WP-FIX1 desktop class manifest gate against
                            <dir> and exit (0 = every required class present, 1 = one
                            or more missing, printed as JSON)

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
  const analysis = enumerateDebugGuardedIntentHooks(source)
  return {
    ...checkReleaseSurface(source, manifest),
    intentHooks: analysis.hooks.length,
    guardedIntentHooks: analysis.hooks.filter((hook) => hook.guarded).length,
    unguardedIntentHooks: analysis.unguarded.map((hook) => ({ line: hook.line, owner: hook.owner, text: hook.text })),
    debugBlocks: analysis.blocks.length
  }
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

const DEBUG_GUARD_OPEN = 'if (BuildConfig.DEBUG) {'
// Every way MainActivity can read an Intent extra: LaunchOptions.<x> =
// intent?.getStringExtra(...), a bare `intent.getStringExtra(...)?.let { ... }`
// hook, and the `intent?.getBooleanExtra(...) == true` form. A QA hook that is
// not in this shape (e.g. `val raw = intent?.getStringExtra("fmX")`) is still
// caught because the match is the read itself, not the assignment.
const INTENT_EXTRA_READ_PATTERN = '\\bintent\\s*\\??\\.\\s*get[A-Za-z0-9_]*Extra\\s*\\('

function lineNumberAt (text, index) {
  return text.slice(0, index).split('\n').length
}

function findDebugBlocks (text) {
  const blocks = []
  let index = text.indexOf(DEBUG_GUARD_OPEN)
  while (index >= 0) {
    const brace = text.indexOf('{', index)
    const end = brace < 0 ? -1 : findBlockEnd(text, brace)
    blocks.push({ openIndex: index, line: lineNumberAt(text, index), end: end < 0 ? -1 : end + 1 })
    index = text.indexOf(DEBUG_GUARD_OPEN, index + DEBUG_GUARD_OPEN.length)
  }
  return blocks
}

/**
 * Enumerate every Intent-extra read in a MainActivity source and report whether
 * it sits inside an `if (BuildConfig.DEBUG) { ... }` block.
 *
 * The previous guard located exactly one line (`LaunchOptions.openCardId = ...`)
 * and checked that line against the nearest preceding DEBUG block, so moving any
 * other QA hook out of the block was undetected. This walks the whole file: the
 * gate passes only when EVERY read is inside some DEBUG block.
 *
 * Comment lines (`//`, `*`, `/*`) are ignored; a commented-out hook cannot run.
 */
function enumerateDebugGuardedIntentHooks (source) {
  const text = String(source).replaceAll('\r\n', '\n')
  const blocks = findDebugBlocks(text)
  const hooks = []
  const pattern = new RegExp(INTENT_EXTRA_READ_PATTERN, 'g')
  for (const match of text.matchAll(pattern)) {
    const index = match.index
    const lineStart = text.lastIndexOf('\n', index - 1) + 1
    const lineEnd = text.indexOf('\n', index)
    const line = text.slice(lineStart, lineEnd < 0 ? text.length : lineEnd)
    const trimmed = line.trim()
    if (trimmed.startsWith('//') || trimmed.startsWith('*') || trimmed.startsWith('/*')) continue
    const prefix = text.slice(lineStart, index)
    const launchOption = (prefix.match(/LaunchOptions\.([A-Za-z0-9_]+)\s*=\s*$/) || prefix.match(/LaunchOptions\.([A-Za-z0-9_]+)\s*=/))?.[1]
    const assignee = (prefix.match(/([A-Za-z0-9_]+)\s*=\s*$/))?.[1]
    const guarded = blocks.some((block) => block.end > block.openIndex && index > block.openIndex && index < block.end)
    hooks.push({
      line: lineNumberAt(text, index),
      index,
      guarded,
      owner: launchOption ? `LaunchOptions.${launchOption}` : (assignee || 'intent extra read'),
      text: trimmed
    })
  }
  return { hooks, unguarded: hooks.filter((hook) => !hook.guarded), blocks }
}

// The normal UI entry is a setContent call at the eight-space content indent.
// It may open a single-line or a multi-line block, so only the opening is
// matched. The debug-only QA intent hooks must live inside the debug guard and
// the guard block must end on the line immediately above that entry: a bare
// "}" line followed by setContent, with no code in between.
//
// Two independent conditions make this hard to defeat:
//   * structural: the DEBUG block that holds the openCardId anchor is the block
//     that ends immediately above `setContent {` (nothing can be inserted after
//     the guard and before the UI entry);
//   * exhaustive: every Intent-extra read in the file is inside a DEBUG block,
//     not only the one line the old guard looked at.
function checkReleaseSurface (source, manifest) {
  const text = String(source).replaceAll('\r\n', '\n')
  const manifestText = String(manifest).replaceAll('\r\n', '\n')
  const analysis = enumerateDebugGuardedIntentHooks(text)
  const qaStart = text.indexOf('LaunchOptions.openCardId = intent?.getStringExtra("fmCard")')
  // The guard that matters is the one nearest before the anchor. Taking the
  // first guard in the file would accept hooks sitting outside every debug
  // block as long as some earlier debug block exists.
  const guard = qaStart >= 0 ? text.lastIndexOf(DEBUG_GUARD_OPEN, qaStart) : -1
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
  // A source with no Intent hook at all cannot prove anything: the QA surface
  // was deleted, and the anchor check above already fails it. Requiring at
  // least one hook also keeps this from vacuously passing on an empty file.
  const everyHookGuarded = analysis.hooks.length > 0 && analysis.unguarded.length === 0
  const exportedLauncher = /<activity[\s\S]*?android:exported="true"[\s\S]*?<\/activity>/.test(manifestText) &&
    /<action android:name="android\.intent\.action\.MAIN"\s*\/>/.test(manifestText) &&
    /<category android:name="android\.intent\.category\.LAUNCHER"\s*\/>/.test(manifestText)
  const debugGuardContainsQaHooks = contained && everyHookGuarded
  return {
    status: debugGuardContainsQaHooks && exportedLauncher ? 'PASS' : 'FAIL',
    debugGuardContainsQaHooks,
    normalExportedLauncherPreserved: exportedLauncher
  }
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

// WP-FIX1: the desktop main class root. `writeGradleIsolationScript` maps every
// project's buildDir under FM_GRADLE_OUTPUT_ROOT, so the desktop classes land in
// `<gradle-output>/composeApp/classes/kotlin/desktop/main` (the same directory
// the `dumpDesktopClasspath` task puts on the runtime classpath).
function desktopClassRoot (gradleOutputRoot) {
  return path.join(gradleOutputRoot, 'composeApp', 'classes', 'kotlin', 'desktop', 'main')
}

/**
 * WP-FIX1 gate: the package must carry the critical desktop classes.
 *
 * A `compileKotlinDesktop FROM-CACHE` hit (or a partially written class tree)
 * still produces an APK/desktop runtime that starts, so nothing used to fail —
 * that is how a wrong build could ship. This asserts the exact classes by path
 * and prints the missing list; an empty class root also fails (no "0 missing out
 * of 0 checked" pass).
 */
function verifyDesktopClassManifest (classRoot, required = DESKTOP_REQUIRED_CLASSES) {
  const root = String(classRoot)
  const rows = required.map((relative) => {
    const filename = path.join(root, ...relative.split('/'))
    const exists = fs.existsSync(filename) && fs.statSync(filename).isFile()
    return { path: relative, status: exists ? 'PASS' : 'FAIL', bytes: exists ? fs.statSync(filename).size : null }
  })
  const missing = rows.filter((row) => row.status !== 'PASS').map((row) => row.path)
  const classCount = fs.existsSync(root) ? listFiles(root).filter((file) => file.endsWith('.class')).length : 0
  return {
    status: missing.length === 0 && classCount > 0 ? 'PASS' : 'FAIL',
    classRoot: root.replaceAll(path.sep, '/'),
    classCount,
    required: required.length,
    missing,
    rows
  }
}

/**
 * WP-FIX1 gate: `compileKotlinDesktop` must not be served from the Gradle build
 * cache. The build passes `--no-build-cache`; this reads the run's own gradle log
 * so a future edit that drops the flag (or a `gradle.properties` that re-enables
 * it through an init script) fails the build instead of shipping stale classes.
 *
 * A missing log is NOT a pass: without the log there is no evidence that the
 * flag was passed, so the gate fails closed (the previous version returned PASS
 * for an empty `fromCache` list even when the file did not exist).
 */
function verifyDesktopCompileNotFromCache (gradleLog, task = DESKTOP_CACHE_TASK) {
  const file = String(gradleLog)
  const logPresent = fs.existsSync(file)
  const text = logPresent ? fs.readFileSync(file, 'utf8') : ''
  const lines = text.split(/\r?\n/)
  const taskPrefix = `Task :composeApp:${task}`
  const fromCache = lines.filter((line) => line.includes(taskPrefix) && /FROM-CACHE/i.test(line))
  const scheduled = lines.filter((line) => line.includes(taskPrefix))
  const detail = logPresent
    ? (fromCache.length
        ? `${task} was served FROM-CACHE; re-run the Gradle invocation with --no-build-cache (see the WP-FIX1 note in scripts/native/build.cjs)`
        : `${task} was compiled in this run (log ${file.replaceAll(path.sep, '/')})`)
    : `the Gradle log is missing (${file.replaceAll(path.sep, '/')}); --no-build-cache cannot be proven. Run the Gradle steps (do not pass --skip-gradle) and keep logs/<step>.log`
  return {
    status: logPresent && fromCache.length === 0 ? 'PASS' : 'FAIL',
    task,
    log: file.replaceAll(path.sep, '/'),
    logPresent,
    detail,
    scheduled: scheduled.map((line) => line.replace(/^>\s*Task\s+/, '').trim()),
    fromCache: fromCache.map((line) => line.replace(/^>\s*Task\s+/, '').trim())
  }
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
    'bridge/desktop/controls-data/controls.oppo.json',
    'bridge/desktop/controls-data/controls.xiaomi.json',
    'bridge/desktop/controls-data/controls.vivo.json'
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

/**
 * Gate: the native build must not inherit a dependency on the frozen
 * Electron/Capacitor web app.
 *
 * The three stock-style control documents used to be read out of the legacy
 * web tree. They are now product-owned data under `bridge/desktop/controls-data/`.
 * This gate fails the run when either native script still names the legacy
 * directory, or when a control document is missing or is not valid JSON — an
 * unreadable control document silently degrades the stock-style registry, so it
 * must stop the build rather than ship as a missing feature.
 */
function verifyNoLegacyWebDependency () {
  // Assembled at runtime so this gate's own source cannot match its own needle.
  const legacyNeedle = ['src', 'renderer'].join('/')
  const scanned = ['scripts/native/build.cjs', 'scripts/native/runtime.cjs'].map((relative) => {
    const filename = path.join(ROOT, relative)
    if (!fs.existsSync(filename)) return { path: relative, status: 'FAIL', detail: 'native script is missing' }
    const text = fs.readFileSync(filename, 'utf8')
    const index = text.indexOf(legacyNeedle)
    const line = index < 0 ? null : text.slice(0, index).split('\n').length
    return {
      path: relative,
      status: index < 0 ? 'PASS' : 'FAIL',
      line,
      detail: index < 0 ? 'no legacy web app reference' : `references ${legacyNeedle} at line ${line}`
    }
  })
  const controlData = ['oppo', 'xiaomi', 'vivo'].map((family) => {
    const relative = `bridge/desktop/controls-data/controls.${family}.json`
    const filename = path.join(ROOT, relative)
    if (!fs.existsSync(filename)) return { path: relative, status: 'FAIL', detail: 'control document is missing' }
    try {
      const document = JSON.parse(fs.readFileSync(filename, 'utf8'))
      const records = Array.isArray(document.records) ? document.records.length : 0
      return { path: relative, status: 'PASS', detail: `parsed; records=${records}`, sha256: sha256File(filename) }
    } catch (error) {
      return { path: relative, status: 'FAIL', detail: `not valid JSON: ${error.message}` }
    }
  })
  const pass = scanned.every((row) => row.status === 'PASS') && controlData.every((row) => row.status === 'PASS')
  return { status: pass ? 'PASS' : 'FAIL', scanned, controlData }
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
 * Every required step/gate that is not PASS. `--package` uses this before it
 * writes a single deliverable: a FAIL or SKIP anywhere means the run must not
 * produce a publishable ZIP/APK/SHA256SUMS (the old flow scored gates only after
 * packaging, so a FAIL run still left a complete, hash-consistent package).
 */
function collectRequiredFailures (rows) {
  return rows.filter((row) => row.required && row.status !== 'PASS')
}

// Three-column checksum rows: `<name> <size> MB  <sha256>`. Non-matching lines
// (comments in DEBUG-QA-APK.txt) are returned as `{ line }` so callers can
// decide whether they are allowed.
function parseChecksumLines (text) {
  return String(text).split(/\r?\n/).map((line) => line.trim()).filter(Boolean).map((line) => {
    const match = line.match(/^(\S+)\s+([0-9][0-9,.]*) MB\s+([0-9a-f]{64})$/)
    return match ? { name: match[1], megabytes: match[2], sha256: match[3], line } : { line }
  })
}

/**
 * Remove the publishable files of a failed run. The ZIP, any release APK and
 * SHA256SUMS.txt are deleted; a `--debug-apk` artifact is kept because it is
 * explicitly not publishable (the file name carries `-DEBUGQA`, its hash lives
 * in DEBUG-QA-APK.txt, it is absent from the checksum file and RELEASE-STATUS.json
 * records the failure). The staged tree is kept for diagnosis as well. Returns
 * what was deleted.
 */
function purgePublishableDeliverables (deliverablesRoot) {
  const removed = []
  if (!deliverablesRoot || !fs.existsSync(deliverablesRoot)) return removed
  for (const entry of fs.readdirSync(deliverablesRoot, { withFileTypes: true })) {
    if (!entry.isFile()) continue
    const debugQaArtifact = /-DEBUGQA\.apk$/i.test(entry.name) || entry.name === 'DEBUG-QA-APK.txt'
    const isPublishable = !debugQaArtifact && (/\.(zip|apk)$/i.test(entry.name) || entry.name === 'SHA256SUMS.txt')
    if (!isPublishable) continue
    const filename = path.join(deliverablesRoot, entry.name)
    fs.rmSync(filename, { force: true })
    removed.push(entry.name)
  }
  return removed
}

/**
 * `RELEASE-STATUS.json`: the machine-readable record that replaces the
 * deliverables on a failed run (schema above; `blockers` is the reason).
 */
function writeReleaseStatus (deliverablesRoot, document) {
  fs.mkdirSync(deliverablesRoot, { recursive: true })
  const filename = path.join(deliverablesRoot, 'RELEASE-STATUS.json')
  const payload = {
    schema: 'framemark-release-status/v1',
    createdAt: new Date().toISOString(),
    ...document
  }
  fs.writeFileSync(filename, JSON.stringify(payload, null, 2) + '\n', 'utf8')
  return { path: filename, document: payload }
}

/**
 * ARCH1: key desktop classes must still be declared where this manifest says they are.
 * This is a packaging guard ("the entry point and the QA surface did not vanish"), not an
 * architecture-rule scanner — the import graph and singleton concepts belong to Konsist
 * (`src/desktopTest/kotlin/com/framemark/native/arch/*KonsistTest.kt`).
 */
function verifyDesktopClassSources () {
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
 * WP-RELEASE: the build-script exclusion only covers `desktopMain`, so a QA class
 * moved into `commonMain`/`androidMain`, or a renamed package, would not be
 * excluded. The source-set check below fails when any QA-token source lives
 * outside `desktopMain` (where the exclusion works), and the `--package`
 * `release-qa-surface-tokens` gate re-checks the compiled product class tree.
 *
 * What this does NOT prove: that a released binary was built from a QA-free classpath.
 * `scripts/native/package-desktop.cjs` consumes prebuilt jars, so the flag has to be
 * passed by whoever invokes `:composeApp:desktopJar` (the runbook's `run-desktop`
 * entry point). That gap is recorded in `reports/ARCH1-REPORT.md`.
 */
const QA_SURFACE_TOKENS = ['verify', 'qaenv', 'devtools']
const QA_SURFACE_SOURCE_SETS = ['commonMain', 'androidMain', 'desktopMain']

/** Every source file whose package or path names the QA surface, by source set. */
function findQaSurfaceSources () {
  const sourceRoot = path.join(UI, 'composeApp', 'src')
  const rows = []
  for (const sourceSet of QA_SURFACE_SOURCE_SETS) {
    const dir = path.join(sourceRoot, sourceSet)
    if (!fs.existsSync(dir)) continue
    for (const file of listFiles(dir).filter((name) => name.endsWith('.kt'))) {
      const relative = path.relative(ROOT, file).replaceAll(path.sep, '/')
      const text = fs.readFileSync(file, 'utf8').replaceAll('\r\n', '\n')
      const packageName = (text.match(/^package\s+([A-Za-z0-9_.]+)/m) || [])[1] || ''
      const tokens = QA_SURFACE_TOKENS.filter((token) =>
        packageName.split('.').includes(token) || relative.toLowerCase().includes(`/${token}/`))
      if (tokens.length) rows.push({ sourceSet, file: relative, package: packageName, tokens })
    }
  }
  return rows
}

/**
 * Packaged class names that still name the QA surface. Used against the product
 * jar entries and the staged class tree: `-PfmQaSurface=false` must remove the
 * whole surface regardless of the source set or the package name it lives in.
 */
function qaSurfaceClassOffenders (classPaths) {
  return [...classPaths].filter((entry) =>
    QA_SURFACE_TOKENS.some((token) => String(entry).toLowerCase().includes(token)))
}

function verifyPackagingSurface () {
  const checks = []
  const buildScript = path.join(ROOT, 'ui/composeApp/build.gradle.kts')
  const text = fs.existsSync(buildScript) ? fs.readFileSync(buildScript, 'utf8') : ''
  checks.push({ id: 'fmQaSurface-switch', ok: /fmQaSurface/.test(text), detail: 'ui/composeApp/build.gradle.kts defines the fmQaSurface switch' })
  checks.push({ id: 'verify-excluded', ok: /com\/framemark\/native\/verify\/\*\*/.test(text), detail: 'verify/** excluded when fmQaSurface=false' })
  checks.push({ id: 'qaenv-excluded', ok: /com\/framemark\/native\/qaenv\/\*\*/.test(text), detail: 'qaenv/** excluded when fmQaSurface=false' })
  // WP-RELEASE: the exclusion only reaches `desktopMain`. A QA class in
  // `commonMain`/`androidMain` (or a renamed package) would compile into the
  // product; moving it must fail here instead of silently shipping.
  let qaSurfaceSources = []
  try { qaSurfaceSources = findQaSurfaceSources() } catch (error) {
    checks.push({ id: 'qa-surface-source-sets', ok: false, detail: `could not scan ui/composeApp/src: ${error.message}` })
  }
  if (!checks.some((check) => check.id === 'qa-surface-source-sets')) {
    const outsideDesktop = qaSurfaceSources.filter((row) => row.sourceSet !== 'desktopMain')
    checks.push({
      id: 'qa-surface-source-sets',
      ok: outsideDesktop.length === 0,
      detail: outsideDesktop.length
        ? `QA-surface sources outside desktopMain: ${outsideDesktop.map((row) => `${row.file} (${row.tokens.join('/')})`).join(', ')}. ` +
          'Move them under desktopMain (excluded by -PfmQaSurface=false) or add their package to the exclusion in ui/composeApp/build.gradle.kts.'
        : `${qaSurfaceSources.length} QA-surface source(s), all under desktopMain where -PfmQaSurface=false excludes them`
    })
  }
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
  return { status: failed.length ? 'FAIL' : 'PASS', checks, failed: failed.map((check) => check.id), qaSurfaceSources }
}


async function main (options) {
  if (options.help) { process.stdout.write(usage()); return 0 }
  // WP-FIX1 standalone gate: `--check-desktop-classes <dir>` runs the class
  // manifest check on an existing class root (used by the negative proof and by
  // anyone verifying a package without a full rebuild).
  if (options.desktopClassCheck) {
    const manifest = verifyDesktopClassManifest(path.resolve(options.desktopClassCheck))
    process.stdout.write(JSON.stringify({ schema: 'framemark-desktop-class-manifest/v1', ...manifest }, null, 2) + '\n')
    return manifest.status === 'PASS' ? 0 : 1
  }
  if (process.platform !== 'win32') throw new Error('This build entry point requires Windows; use the Gradle/Cargo commands directly on other hosts.')

  const tools = resolveTools()
  const prebuilt = verifyCheckedPrebuilt()
  const releaseSurface = verifyReleaseSurface()
  const buildConfiguration = verifyPinnedBuildConfiguration()
  const sharedLogoData = verifySharedLogoData()
  const logoImageToolchain = verifyLogoImageToolchain()
  const desktopRuntimeInputs = verifyDesktopRuntimeInputs()
  const noLegacyWebDependency = verifyNoLegacyWebDependency()
  const androidPackageSourceInputs = verifyAndroidPackageSourceInputs()
  const plan = [
    options.skipEngine ? 'SKIP e2b Cargo build; source-build e4hand and e5-worker' : 'Cargo build e2b, e4hand and e5-worker from repository sources for x86_64-pc-windows-msvc',
    'Stage isolated relocatable Windows QA runtime from repository Android asset ZIP and current desktop manifest',
    options.skipAndroidNative ? 'SKIP Android staticlib rebuild; verify checked-in hashes' : 'Cargo build three Android arm64 staticlibs to staging',
    options.skipGradle
      ? 'SKIP desktop and APK Gradle tasks'
      : options.package && !options.debugApk
        ? `Gradle desktop compile, Android debug APK and Android release APK (-PframemarkQaReleaseSuffix=${options.qaSuffix})`
        : 'Gradle desktop compile and Android debug APK',
    options.package
      ? (options.debugApk ? 'Package the DEBUG QA arm64 APK (not a release artifact; not in SHA256SUMS.txt)' : 'Package the release arm64 APK (must not be debuggable or debug-signed)')
      : (options.release ? (options.releaseQa ? 'Gradle isolated debug-signed release QA APK + package/ABI gates' : 'Gradle Android release APK + ABI and source-provenance gates') : 'SKIP Android release APK')
  ]
  if (options.package) plan.push(options.debugApk
    ? 'Gradle desktopTest + QA-free desktopJar (-PfmQaSurface=false); jlink runtime; FrameMark-<versionName>-win64 portable ZIP + DEBUG QA arm64 APK (kept out of SHA256SUMS.txt) + SHA256SUMS; launcher smoke test'
    : `Gradle desktopTest + QA-free desktopJar (-PfmQaSurface=false); Android release APK with -PframemarkQaReleaseSuffix=${options.qaSuffix}; jlink runtime; FrameMark-<versionName>-win64 portable ZIP + release arm64 APK + SHA256SUMS; launcher smoke test`)
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
  gates.push(requiredCheck(
    'release-intent-source-guard',
    releaseSurface.status,
    releaseSurface.status === 'PASS'
      ? JSON.stringify({
          intentHooks: releaseSurface.intentHooks,
          guardedIntentHooks: releaseSurface.guardedIntentHooks,
          debugBlocks: releaseSurface.debugBlocks,
          detail: 'every Intent-extra read in MainActivity.kt is inside an if (BuildConfig.DEBUG) { ... } block, the block holding LaunchOptions.openCardId ends immediately above setContent {, and the exported MAIN/LAUNCHER activity is preserved'
        })
      : `move every LaunchOptions.<x> = intent?.get*Extra(...) / Intent-extra read back inside an if (BuildConfig.DEBUG) { ... } block in ` +
        `ui/composeApp/src/androidMain/kotlin/com/framemark/native/MainActivity.kt, keep the DEBUG block that holds ` +
        `LaunchOptions.openCardId as the statement immediately before setContent {, and keep the exported MAIN/LAUNCHER activity; ` +
        `unguarded: ${JSON.stringify(releaseSurface.unguardedIntentHooks || [])}`
  ))
  gates.push(requiredCheck('pinned-build-configuration', buildConfiguration.status, JSON.stringify(buildConfiguration.checks)))
  gates.push(requiredCheck('shared-logo-data-sha256', sharedLogoData.status, JSON.stringify(sharedLogoData)))
  gates.push({
    ...requiredCheck('isolated-logo-image-toolchain', options.skipEngine ? 'SKIP' : logoImageToolchain.status, JSON.stringify(logoImageToolchain)),
    required: !options.skipEngine
  })
  gates.push(requiredCheck('desktop-runtime-inputs', desktopRuntimeInputs.status, JSON.stringify(desktopRuntimeInputs)))
  gates.push(requiredCheck('no-legacy-web-dependency', noLegacyWebDependency.status, JSON.stringify(noLegacyWebDependency)))
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
  // A release-class artifact — including the release APK `--package` ships — may
  // not be built from provenance-unverified checked-in archives. The explicit
  // `--debug-apk` escape hatch keeps the debug reuse rules.
  if (options.release || (options.package && !options.debugApk)) {
    const sourceBuildStatus = nativeBuildProvenance?.status === 'PASS' ? 'PASS' : (options.skipAndroidNative ? 'SKIP' : 'FAIL')
    const detail = sourceBuildStatus === 'PASS'
      ? 'all three Android static libraries were built from repository Cargo sources in this run'
      : options.skipAndroidNative
        ? 'release builds may not reuse provenance-unverified checked-in static archives; drop --skip-android-native'
        : 'Android source build did not produce a verified set of all three static libraries'
    gates.push(requiredCheck('release-native-source-build', sourceBuildStatus, detail))
  }

  // WP-FIX1: one Gradle invocation shape for every app build/test step: the
  // isolated build directory (`FM_GRADLE_OUTPUT_ROOT` + init script), no daemon
  // and `--no-build-cache`. `ui/gradle.properties` sets `org.gradle.caching=true`,
  // and a FROM-CACHE `compileKotlinDesktop` is how a package shipped classes that
  // were never compiled from this tree (WP-Q4). `GRADLE_OPTS=
  // -Dorg.gradle.caching=false` does not override it; the CLI flag does — and it
  // covers desktop compile, desktopTest and desktopJar because it disables the
  // cache for the whole invocation. `--package` reuses this same helper, so the
  // product jar cannot come from a cache populated by another tree or another
  // fmQaSurface variant.
  const gradleIsolationScript = path.join(tmpRoot, 'gradle-isolated-output.init.gradle')
  writeGradleIsolationScript(gradleIsolationScript)
  const runGradleTask = (id, title, tasks, { projectCacheDir = path.join(tmpRoot, 'gradle-project-cache'), extraArgs = [], stacktrace = options.release, timeoutMs = 90 * 60 * 1000, qaReleaseSuffix } = {}) => {
    const args = ['-Dorg.gradle.appname=gradle', '-classpath', path.join(tools.gradleHome, 'lib', '*'), 'org.gradle.launcher.GradleMain', '--no-daemon',
      '--no-build-cache',
      '--project-cache-dir', projectCacheDir, '--init-script', gradleIsolationScript, `-PframemarkQaSuffix=${options.qaSuffix}`]
    // Kotlin's incremental path converter cannot relativize generated sources
    // across Windows drive roots. This is an isolated full build anyway; avoid
    // a reproducible daemon failure/fallback when sources and outputs differ.
    if (process.platform === 'win32' && path.parse(UI).root.toLowerCase() !== path.parse(gradleOutputRoot).root.toLowerCase()) {
      args.push('-Pkotlin.incremental=false')
    }
    // `--package` passes the DEBUG build's suffix to the release variant so the
    // delivered app id stays `com.framemark.native<qaSuffix>` and an APK can be
    // installed over the debug QA build. `--release-qa` keeps its own suffix.
    const releaseSuffix = qaReleaseSuffix !== undefined ? qaReleaseSuffix : (options.releaseQa ? options.qaReleaseSuffix : null)
    if (releaseSuffix) args.push(`-PframemarkQaReleaseSuffix=${releaseSuffix}`)
    args.push(...tasks, ...extraArgs)
    if (stacktrace) args.push('--stacktrace')
    const env = { ...envBase, FM_ANDROID_PREBUILT_ROOT: androidPrebuiltRoot, FM_E2B_EXE: engineExe }
    return step(id, title, (logFile) => runProcess(tools.java, args, { cwd: UI, env, logFile, timeoutMs }))
  }

  // A normal `--package` run must package the RELEASE variant; only the explicit
  // `--debug-apk` escape hatch keeps the debug variant. The two modes never share
  // one Gradle invocation's release task list.
  const packageRelease = Boolean(options.package && !options.debugApk)
  // Production intent: a plain `--release` run, or any run that requested the
  // production signing step. A `--package` QA run (no --release/--sign) is not
  // production intent: its QA-suffixed, debug-key-signed release APK is a
  // stamped QA artifact (LOCAL_QA_DEBUG_SIGNED_NOT_PUBLISHABLE), not a product.
  const productionIntent = Boolean(options.sign || (options.release && !options.releaseQa))
  const gradleTasks = [':composeApp:compileKotlinDesktop', ':composeApp:assembleDebug']
  if (options.release || packageRelease) gradleTasks.push(':composeApp:assembleRelease')
  const needsReleaseInputs = Boolean(options.release || packageRelease)
  const releaseInputsReady = !needsReleaseInputs || (
    steps.some((row) => row.id === 'desktop-runtime' && row.status === 'PASS') &&
    nativeBuildProvenance?.status === 'PASS'
  )
  if (!options.skipGradle) {
    if (!releaseInputsReady) {
      steps.push({ id: 'gradle', title: 'Build desktop and Android variants', required: true, status: 'SKIP', exitCode: 0, detail: 'release packaging requires a source-built desktop engine and all three source-built Android libraries' })
    } else if (!tools.java || !tools.gradleLauncher || !tools.androidSdk || !fs.existsSync(tools.java) || !fs.existsSync(tools.gradleLauncher)) {
      steps.push({ id: 'gradle', title: 'Build desktop and Android variants', required: true, status: 'FAIL', exitCode: 1, detail: 'JDK, Gradle or Android SDK is unresolved', log: null })
    } else {
      const built = await runGradleTask('gradle', 'Build desktop and Android variants', gradleTasks, {
        qaReleaseSuffix: packageRelease ? options.qaSuffix : undefined
      })
      if (built.status === 'PASS') {
        const debugApk = path.join(gradleOutputRoot, 'composeApp/outputs/apk/debug/composeApp-debug.apk')
        if (fs.existsSync(debugApk)) gates.push(await verifyApk('debug-apk-arm64', debugApk, tools, step, `com.framemark.native${options.qaSuffix}`))
        else gates.push(requiredCheck('debug-apk-arm64', 'FAIL', `debug APK missing: ${debugApk}`))
      }
    }
  } else {
    steps.push({ id: 'gradle', title: 'Build desktop and Android variants', required: true, status: 'SKIP', exitCode: 0, detail: '--skip-gradle' })
  }

  // WP-FIX1: two new gates that must FAIL the run rather than let a package ship
  // without these classes / with cached desktop classes. They read this run's own
  // class root and gradle log, which live under the run directory, so a stale
  // output from an earlier run can never satisfy them.
  if (options.skipGradle) {
    gates.push({ ...requiredCheck('desktop-class-manifest', 'SKIP', '--skip-gradle; the desktop class tree was not rebuilt'), required: false })
    gates.push({ ...requiredCheck('desktop-build-cache-disabled', 'SKIP', '--skip-gradle; there is no Gradle invocation to inspect'), required: false })
  } else {
    const classManifest = verifyDesktopClassManifest(desktopClassRoot(gradleOutputRoot))
    gates.push(requiredCheck(
      'desktop-class-manifest',
      classManifest.status,
      classManifest.missing.length
        ? `missing ${classManifest.missing.length}/${classManifest.required}: ${classManifest.missing.join(', ')}`
        : `${classManifest.required} required desktop classes present; ${classManifest.classCount} .class files under ${classManifest.classRoot}`
    ))
    const cacheGate = verifyDesktopCompileNotFromCache(path.join(logRoot, 'gradle.log'))
    gates.push(requiredCheck(
      'desktop-build-cache-disabled',
      cacheGate.status,
      cacheGate.status === 'PASS'
        ? `${cacheGate.task} was not FROM-CACHE (scheduled: ${cacheGate.scheduled.join(', ') || 'none'})`
        : `${cacheGate.fromCache.length ? `${cacheGate.task} was served FROM-CACHE: ${cacheGate.fromCache.join(' | ')}. ` : ''}${cacheGate.detail}`
    ))
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
  const desktopClassManifest = verifyDesktopClassSources()
  gates.push(requiredCheck(
    'desktop-class-sources',
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

  // The release-class APK this run built. `--package` packages this file (or the
  // signed copy when --sign ran); `--debug-apk` packages the debug APK instead
  // and never reaches here.
  let releasePackageApk = null
  const needsReleaseApk = Boolean(options.release || (options.package && !options.debugApk))
  if (needsReleaseApk) {
    // `--package` passes -PframemarkQaReleaseSuffix=<debug suffix> to the release
    // variant, so its app id must equal the debug build's; a plain --release
    // build carries no suffix. A mismatch here means the release Gradle
    // invocation did not receive the property.
    const releaseApplicationId = options.package ? `com.framemark.native${options.qaSuffix}` : 'com.framemark.native'
    const releaseApk = findReleaseApk(gradleOutputRoot)
    if (releaseApk) {
      if (options.releaseQa) {
        gates.push(await verifyApk('release-qa-apk-arm64', releaseApk, tools, step, `com.framemark.native${options.qaReleaseSuffix}`))
        gates.push(await verifyQaSignature(releaseApk, tools, step))
        releasePackageApk = releaseApk
      } else if (options.sign) {
        const signed = await signRelease(releaseApk, tools, envBase, runRoot, step)
        gates.push(requiredCheck('release-signature', signed.status, signed.detail || 'signing did not complete'))
        if (signed.status === 'PASS') {
          releasePackageApk = signed.signedApk
          gates.push(await verifyApk('signed-release-apk-arm64', signed.signedApk, tools, step, releaseApplicationId))
        }
      } else {
        gates.push(await verifyApk('release-apk-arm64', releaseApk, tools, step, releaseApplicationId))
        // A plain --release run is expected to be unsigned. `--package` without
        // --sign is judged by package-apk-class instead, which names the real
        // problem (the release variant built here carries the Android debug key
        // because it is built with the QA suffix).
        if (options.release) gates.push(requiredCheck('release-signature', 'SKIP', 'release APK was built unsigned; --sign was not requested'))
        releasePackageApk = releaseApk
      }
    } else {
      gates.push(requiredCheck(
        'release-apk-arm64',
        'FAIL',
        `release APK does not exist after the Gradle task; run :composeApp:assembleRelease (${path.join(gradleOutputRoot, 'composeApp/outputs/apk/release').replaceAll(path.sep, '/')}) and drop --skip-gradle`
      ))
    }
  }

  // ---------------------------------------------------------------------------
  // --package (opt-in): produce the shipped win64 portable package + arm64 APK
  // from this run, and prove the shipped files are the ones this pipeline built.
  // Without the flag none of these steps/gates exist and the report keeps its
  // existing shape.
  // ---------------------------------------------------------------------------
  let releaseDeliverables = null
  let releaseDeliverablesRoot = null
  let releaseVersionName = null
  let releaseStatus = null
  if (options.package) {
    // The label has one source: version.properties. The commit comes from git.
    const version = readVersionProperties()
    const versionName = version.versionName
    releaseVersionName = versionName
    const gitCommit = readGitValue(['rev-parse', '--short', 'HEAD'], 'unknown')
    const gitBranch = readGitValue(['rev-parse', '--abbrev-ref', 'HEAD'], 'unknown')
    const releaseCacheDir = path.join(tmpRoot, 'gradle-release-project-cache')
    const releaseClassRoot = desktopClassRoot(gradleOutputRoot)
    const releaseResourcesRoot = path.join(gradleOutputRoot, 'composeApp', 'processedResources', 'desktop', 'main')
    const releaseJarPath = path.join(gradleOutputRoot, 'composeApp', 'libs', 'composeApp-desktop.jar')
    const desktopClasspathFile = path.join(UI, 'composeApp', 'build', 'desktop-classpath.txt')
    const runtimeSource = path.join(buildRoot, 'desktop-runtime')
    const deliverablesRoot = path.join(runRoot, 'deliverables')
    releaseDeliverablesRoot = deliverablesRoot
    const stagingName = `FrameMark-${versionName}-win64`
    const stagingDir = path.join(deliverablesRoot, stagingName)
    // The default deliverable is the RELEASE APK. `--debug-apk` is the only way
    // to ship the debug one, and its file name says so.
    const apkName = options.debugApk ? `FrameMark-${versionName}-arm64-DEBUGQA.apk` : `FrameMark-${versionName}-arm64.apk`
    const apkPath = path.join(deliverablesRoot, apkName)
    const zipPath = path.join(deliverablesRoot, `${stagingName}-portable.zip`)
    const sumsPath = path.join(deliverablesRoot, 'SHA256SUMS.txt')
    const debugApkNotePath = path.join(deliverablesRoot, 'DEBUG-QA-APK.txt')
    const debugApkPath = path.join(gradleOutputRoot, 'composeApp', 'outputs', 'apk', 'debug', 'composeApp-debug.apk')

    // 1. release-tests: the acceptance suite through the same Gradle helper, the
    // same isolated build directory and the same `--no-build-cache`. The JUnit XML
    // totals decide the step: a failing test fails the run.
    const testsStarted = Date.now()
    const testsStep = await runGradleTask('release-tests', 'Run the desktop release test suite', [':composeApp:desktopTest'], { projectCacheDir: releaseCacheDir, timeoutMs: 60 * 60 * 1000 })
    const junitDir = path.join(gradleOutputRoot, 'composeApp', 'test-results', 'desktopTest')
    const junit = readJUnitTotals(junitDir, testsStarted - 5000)
    const testsOk = testsStep.status === 'PASS' && junit.files > 0 && junit.tests > 0 && junit.failures === 0 && junit.errors === 0
    const testsRow = steps.find((row) => row.id === 'release-tests')
    if (testsRow) {
      testsRow.status = testsOk ? 'PASS' : 'FAIL'
      testsRow.exitCode = testsOk ? 0 : 1
      testsRow.detail = JSON.stringify({ ...junit, gradle: testsStep.status, suiteDir: path.relative(ARTIFACT_ROOT, junitDir).replaceAll(path.sep, '/') })
    }

    // 2. release-desktop-jar: the rc4 trap is a class output that belongs to
    // another commit (or to a QA-surface compile). Delete it, forbid the build
    // cache and compile the product variant from source.
    fs.rmSync(releaseClassRoot, { recursive: true, force: true })
    fs.rmSync(desktopClasspathFile, { force: true })
    const jarStarted = Date.now()
    const jarStep = await runGradleTask('release-desktop-jar', 'Build the QA-free product desktop jar', [':composeApp:desktopJar', ':composeApp:dumpDesktopClasspath'], { projectCacheDir: releaseCacheDir, extraArgs: ['-PfmQaSurface=false'], timeoutMs: 60 * 60 * 1000 })
    const jarExists = fs.existsSync(releaseJarPath) && fs.statSync(releaseJarPath).isFile()
    const productJar = jarExists ? { path: releaseJarPath, bytes: fs.statSync(releaseJarPath).size, sha256: sha256File(releaseJarPath), builtByThisStep: fs.statSync(releaseJarPath).mtimeMs >= jarStarted - 1000 } : null
    // `dumpDesktopClasspath` writes into the project directory (not the isolated
    // build directory), so assert the file describes THIS run before app/lib uses it.
    const classpathEntries = fs.existsSync(desktopClasspathFile) ? fs.readFileSync(desktopClasspathFile, 'utf8').trim().split(path.delimiter).filter(Boolean) : []
    const classpathOk = classpathEntries.length > 1 &&
      path.resolve(classpathEntries[0]) === path.resolve(releaseClassRoot) &&
      path.resolve(classpathEntries[1]) === path.resolve(releaseResourcesRoot)
    const classCount = fs.existsSync(releaseClassRoot) ? listFiles(releaseClassRoot).filter((file) => file.endsWith('.class')).length : 0
    const jarOk = jarStep.status === 'PASS' && Boolean(productJar) && productJar.builtByThisStep && classpathOk && classCount > 0
    const jarRow = steps.find((row) => row.id === 'release-desktop-jar')
    if (jarRow) {
      jarRow.status = jarOk ? 'PASS' : 'FAIL'
      jarRow.exitCode = jarOk ? 0 : 1
      jarRow.artifact = productJar ? path.relative(ARTIFACT_ROOT, productJar.path).replaceAll(path.sep, '/') : null
      jarRow.detail = JSON.stringify({ jar: productJar, classRoot: path.relative(ARTIFACT_ROOT, releaseClassRoot).replaceAll(path.sep, '/'), classFiles: classCount, classpathEntries: classpathEntries.length, classpathDescribesThisRun: classpathOk, gradle: jarStep.status })
    }

    // 3. release-jar-surface: the QA-free proof has to read the artifact that is
    // packaged, so list the product jar's entries instead of trusting the class
    // directory. `-PfmQaSurface=false` excludes both packages at compile time, so
    // any entry here means the packaged build carried the QA surface.
    const jarList = await step('release-jar-surface-entries', 'List the product jar entries', (logFile) => runProcess(path.join(tools.javaHome, 'bin', 'jar.exe'), ['tf', releaseJarPath], { cwd: ROOT, env: process.env, logFile, timeoutMs: 60 * 1000 }))
    const jarEntries = jarList.stdout.split(/\r?\n/).map((line) => line.trim()).filter(Boolean)
    const qaEntries = jarEntries.filter((entry) => /^com\/framemark\/native\/(verify|qaenv)\//.test(entry))
    const mainClassEntry = jarEntries.includes('com/framemark/native/MainKt.class')
    // The package stages the class tree (not the jar), so the same proof has to
    // hold for app/classes.
    const classRootQaFiles = fs.existsSync(releaseClassRoot)
      ? ['verify', 'qaenv'].flatMap((name) => listFiles(path.join(releaseClassRoot, 'com', 'framemark', 'native', name))).filter((file) => file.endsWith('.class'))
      : []
    const jarSurfaceOk = jarList.status === 'PASS' && jarEntries.length > 0 && qaEntries.length === 0 && mainClassEntry && classRootQaFiles.length === 0
    gates.push(requiredCheck('release-jar-surface', jarSurfaceOk ? 'PASS' : 'FAIL', JSON.stringify({
      jar: path.relative(ARTIFACT_ROOT, releaseJarPath).replaceAll(path.sep, '/'),
      sha256: productJar?.sha256 || null,
      entries: jarEntries.length,
      verifyQaenvEntries: qaEntries.length,
      verifyQaenvExamples: qaEntries.slice(0, 5),
      mainClassEntry,
      classRootQaClassFiles: classRootQaFiles.length,
      ...(jarSurfaceOk ? {} : { hint: 'rebuild the product jar with -PfmQaSurface=false and keep the verify/qaenv packages out of the packaged class tree' })
    })))

    // 3b. release-qa-surface-tokens: the name regex above only knows the two
    // historical package names, and `-PfmQaSurface=false` only excludes
    // `desktopMain`. A QA class moved to commonMain/androidMain, or a renamed
    // package, would compile and be packaged with no gate noticing. This check
    // reads the compiled product tree (jar entries and staged classes) and
    // rejects ANY class whose name or package mentions the QA surface.
    const packagedClassEntries = jarEntries.filter((entry) => /^com\/framemark\/native\/.*\.class$/.test(entry))
    const classRootClassEntries = fs.existsSync(releaseClassRoot)
      ? listFiles(releaseClassRoot).filter((file) => file.endsWith('.class')).map((file) => path.relative(releaseClassRoot, file).replaceAll(path.sep, '/'))
      : []
    const tokenOffenders = [...new Set([
      ...qaSurfaceClassOffenders(packagedClassEntries),
      ...qaSurfaceClassOffenders(classRootClassEntries)
    ])]
    const qaSurfaceSourcesOutsideDesktop = (packagingSurface.qaSurfaceSources || []).filter((row) => row.sourceSet !== 'desktopMain')
    const qaSurfaceTokensOk = jarList.status === 'PASS' && classRootClassEntries.length > 0 &&
      tokenOffenders.length === 0 && qaSurfaceSourcesOutsideDesktop.length === 0
    gates.push(requiredCheck(
      'release-qa-surface-tokens',
      qaSurfaceTokensOk ? 'PASS' : 'FAIL',
      qaSurfaceTokensOk
        ? JSON.stringify({
            jarClassEntries: packagedClassEntries.length,
            classRootClassFiles: classRootClassEntries.length,
            qaSurfaceTokens: QA_SURFACE_TOKENS,
            qaSurfaceSourcesOutsideDesktop: 0,
            detail: 'no packaged class name or package contains verify/qaenv/devtools and every QA-surface source is excluded by -PfmQaSurface=false'
          })
        : JSON.stringify({
            jarClassEntries: packagedClassEntries.length,
            classRootClassFiles: classRootClassEntries.length,
            qaSurfaceTokens: QA_SURFACE_TOKENS,
            offenders: tokenOffenders.slice(0, 20),
            offenderCount: tokenOffenders.length,
            qaSurfaceSourcesOutsideDesktop: qaSurfaceSourcesOutsideDesktop.map((row) => row.file),
            hint: 'the packaged product must not contain the QA surface: move each QA class under desktopMain (excluded by -PfmQaSurface=false) or add its package to the desktopMain exclusion in ui/composeApp/build.gradle.kts, then rebuild the product jar with -PfmQaSurface=false and re-package'
          })
    ))

    // 3c. package-apk-present / package-apk-arm64 / package-apk-class: assert the
    // exact APK that would be copied into deliverables/ BEFORE anything is
    // written. The default deliverable is the release variant; `--debug-apk`
    // flips the assertion so the escape hatch has to prove it really is the
    // debug QA APK.
    const packageApkSource = options.debugApk ? debugApkPath : releasePackageApk
    const packageApplicationId = `com.framemark.native${options.qaSuffix}`
    let packageApkClassGate = null
    if (!packageApkSource || !fs.existsSync(packageApkSource)) {
      gates.push(requiredCheck(
        'package-apk-present',
        'FAIL',
        options.debugApk
          ? `--debug-apk needs this run's debug APK (${debugApkPath.replaceAll(path.sep, '/')}); build :composeApp:assembleDebug in this run`
          : '--package needs this run\'s release APK (composeApp/outputs/apk/release) or the --sign output; nothing will be packaged. ' +
            'Pass --debug-apk only to deliberately produce the (non-publishable) debug QA APK.'
      ))
    } else {
      gates.push(await verifyApk(
        'package-apk-arm64',
        packageApkSource,
        tools,
        step,
        packageApplicationId,
        options.debugApk ? null : 'the delivered APK must be the release variant with the debug build\'s application id; check that -PframemarkQaReleaseSuffix=<suffix> reached the release Gradle invocation'
      ))
      packageApkClassGate = await verifyApkClass('package-apk-class', packageApkSource, tools, step, {
        qaSuffix: options.qaSuffix,
        productionIntent
      })
      gates.push(packageApkClassGate)
    }

    // 4. release-package: jlink runtime + staging + launcher + VERSION.txt + ZIP +
    // APK copy + SHA256SUMS, the same layout the hand-run packaging recipe used.
    // A FAIL or SKIP anywhere above means nothing is written: the old flow
    // scored the required gates only after this step, so a failed run still left
    // a complete, hash-consistent deliverables/ that looked publishable.
    fs.rmSync(stagingDir, { recursive: true, force: true })
    fs.rmSync(apkPath, { force: true })
    fs.rmSync(zipPath, { force: true })
    fs.rmSync(sumsPath, { force: true })
    fs.rmSync(debugApkNotePath, { force: true })
    fs.mkdirSync(deliverablesRoot, { recursive: true })

    const packageBlockers = collectRequiredFailures([...steps, ...gates])
    // `--debug-apk` exists to produce a local QA APK. That APK is debuggable, so
    // `package-apk-class` always fails for it (the run still exits nonzero), but
    // the artifact is still produced: marked DEBUGQA, kept out of
    // SHA256SUMS.txt, and the post-packaging purge leaves it in place because it
    // is not a release artifact. Every other blocker still refuses packaging.
    const artifactBlockers = options.debugApk
      ? packageBlockers.filter((row) => row.id !== 'package-apk-class')
      : packageBlockers
    if (artifactBlockers.length) {
      const statusFile = writeReleaseStatus(deliverablesRoot, {
        status: 'FAIL',
        phase: 'refused-before-packaging',
        runId,
        versionName,
        head: readHead(),
        blockers: packageBlockers.map((row) => ({
          id: row.id,
          kind: steps.includes(row) ? 'step' : 'gate',
          status: row.status,
          detail: String(row.detail || '').slice(0, 600)
        })),
        deliverables: null,
        instruction: 'Fix every listed required step/gate and re-run. Nothing was published: no ZIP, no APK and no SHA256SUMS.txt was written.'
      })
      releaseStatus = statusFile.document
      steps.push({
        id: 'release-package',
        title: 'Assemble the portable win64 package and the arm64 APK',
        required: true,
        status: 'FAIL',
        exitCode: 1,
        log: null,
        detail: `refused to write deliverables: ${packageBlockers.length} required step/gate(s) are not PASS (${packageBlockers.map((row) => `${row.id}=${row.status}`).join(', ')}); failure recorded in ${path.relative(ARTIFACT_ROOT, statusFile.path).replaceAll(path.sep, '/')}`
      })
    } else {
      const packageStep = await step('release-package', 'Assemble the portable win64 package and the arm64 APK', async (logFile) => {
      fs.writeFileSync(logFile, '', 'utf8')
      const say = (line) => fs.appendFileSync(logFile, `${line}\n`, 'utf8')
      const runtimeStep = steps.find((row) => row.id === 'desktop-runtime')
      if (!runtimeStep || runtimeStep.status !== 'PASS') throw new Error('the desktop-runtime step did not PASS; the package needs the bin/data/bridge tree this run staged')
      if (!jarOk || !productJar) throw new Error('the product jar is missing or was not rebuilt by release-desktop-jar')
      const missingParts = ['bin', 'data', 'bridge'].filter((part) => !fs.existsSync(path.join(runtimeSource, part)))
      if (missingParts.length) throw new Error(`desktop runtime artifacts are missing: ${missingParts.join(', ')} (${runtimeSource})`)
      for (const input of [releaseClassRoot, releaseResourcesRoot]) {
        if (!fs.existsSync(input)) throw new Error(`staging input is missing: ${input}`)
      }
      fs.mkdirSync(stagingDir, { recursive: true })

      // jlink runtime: the exact module set the rc10 packaging recipe used.
      const jlinkOutput = path.join(tmpRoot, 'jlink-runtime')
      fs.rmSync(jlinkOutput, { recursive: true, force: true })
      const modules = ['java.base', 'java.datatransfer', 'java.xml', 'java.prefs', 'java.desktop', 'java.logging', 'java.management', 'java.security.sasl', 'java.naming', 'jdk.unsupported']
      const jlinkArgs = ['--module-path', path.join(tools.javaHome, 'jmods'), '--add-modules', modules.join(','), '--output', jlinkOutput, '--strip-debug', '--no-man-pages', '--no-header-files', '--compress=zip-6']
      say(`[1/5] jlink ${jlinkArgs.join(' ')}`)
      const jlinkResult = await runProcess(path.join(tools.javaHome, 'bin', 'jlink.exe'), jlinkArgs, { cwd: ROOT, env: process.env, logFile: path.join(logRoot, 'release-jlink.log'), timeoutMs: 20 * 60 * 1000 })
      if (jlinkResult.exitCode !== 0) throw new Error(`jlink failed (${jlinkResult.exitCode}): ${(jlinkResult.stderr || jlinkResult.stdout).slice(-1200)}`)
      if (!fs.existsSync(path.join(jlinkOutput, 'bin', 'javaw.exe'))) throw new Error('the jlink runtime has no bin/javaw.exe')

      // Staging: app/classes + app/resources + runtime/ + this run's bin/data/bridge.
      const copies = [[releaseClassRoot, path.join('app', 'classes')], [releaseResourcesRoot, path.join('app', 'resources')], [jlinkOutput, 'runtime'], ...['bin', 'data', 'bridge'].map((part) => [path.join(runtimeSource, part), part])]
      for (const [from, to] of copies) {
        fs.cpSync(from, path.join(stagingDir, to), { recursive: true })
        const stats = treeStats(path.join(stagingDir, to))
        say(`[2/5] staged ${to}: ${stats.files} files, ${stats.bytes} bytes`)
      }

      // app/lib: every jar the isolated Gradle run put on the desktop runtime
      // classpath. The class/resource directories are staged above.
      const libDir = path.join(stagingDir, 'app', 'lib')
      fs.mkdirSync(libDir, { recursive: true })
      const jars = []
      for (const entry of classpathEntries) {
        if (!/\.jar$/i.test(entry)) continue
        if (!fs.existsSync(entry)) throw new Error(`a jar on the desktop classpath is missing: ${entry}`)
        const digest = sha256File(entry)
        let name = path.basename(entry)
        if (fs.existsSync(path.join(libDir, name)) && sha256File(path.join(libDir, name)) !== digest) name = `${path.basename(entry, '.jar')}-${digest.slice(0, 12)}.jar`
        const target = path.join(libDir, name)
        if (!fs.existsSync(target)) fs.copyFileSync(entry, target)
        jars.push(name)
      }
      if (!jars.length) throw new Error('the desktop classpath listed no jars; app/lib would be empty')
      say(`[2/5] app/lib jars: ${jars.length}`)

      // Launcher + version stamp. The label is version.properties' versionName.
      const launcherLabel = versionName
      const launcher = [
        '@echo off',
        'setlocal',
        'pushd "%~dp0"',
        'set "FM_RUNTIME_ROOT=%CD%"',
        'set "FM_WORKSPACE_ROOT=%CD%"',
        `start "" "%CD%\\runtime\\bin\\javaw.exe" -Dfile.encoding=UTF-8 -Dframemark.build.label=${launcherLabel} -cp "%CD%\\app\\classes;%CD%\\app\\resources;%CD%\\app\\lib\\*" com.framemark.native.MainKt`,
        'popd',
        ''
      ].join('\r\n')
      fs.writeFileSync(path.join(stagingDir, 'FrameMark.bat'), launcher, 'ascii')

      const built = new Date().toISOString().replace('T', ' ').slice(0, 19)
      const versionText = [
        `FrameMark ${versionName}`,
        `built: ${built} (UTC)`,
        `git: ${gitCommit} (${gitBranch})`,
        `runId: ${runId}`,
        `launcher label: -Dframemark.build.label=${launcherLabel}`,
        `product jar: -PfmQaSurface=false, sha256 ${productJar.sha256}`,
        `product jar entries: ${jarEntries.length}, verify/qaenv entries: ${qaEntries.length}`,
        `apk: ${path.basename(apkPath)} (${options.debugApk ? 'DEBUG QA APK: application-debuggable, debug-signed, not in SHA256SUMS.txt, not publishable' : (packageApkClassGate && packageApkClassGate.qaSignedNotPublishable ? 'QA ARTIFACT: debug-signed, isolated QA application id, NOT PUBLISHABLE (see the SHA256SUMS.txt header)' : 'release APK: not application-debuggable, non-debug signing certificate')})`,
        `app/lib jars: ${jars.length}`,
        '运行：双击 FrameMark.bat（需要 Windows 10/11 x64，无需另装 Java）'
      ].join('\r\n') + '\r\n'
      fs.writeFileSync(path.join(stagingDir, 'VERSION.txt'), versionText, 'utf8')

      // ZIP through the Windows tar, exactly like the recipe: the archive holds
      // one top-level `FrameMark-<versionName>-win64/` directory.
      const systemTar = path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'tar.exe')
      const tarExecutable = fs.existsSync(systemTar) ? systemTar : 'tar.exe'
      say(`[3/5] ${tarExecutable} -a -c -f ${zipPath} ${stagingName}`)
      const tarResult = await runProcess(tarExecutable, ['-a', '-c', '-f', zipPath, stagingName], { cwd: deliverablesRoot, env: process.env, logFile: path.join(logRoot, 'release-tar.log'), timeoutMs: 30 * 60 * 1000 })
      if (tarResult.exitCode !== 0 || !fs.existsSync(zipPath)) throw new Error(`tar failed to create the portable ZIP (${tarResult.exitCode}): ${(tarResult.stderr || tarResult.stdout).slice(-1200)}`)

      // The arm64 APK is this run's verified APK: the release variant by
      // default, the debug QA APK only with --debug-apk. There is deliberately
      // no fallback to `composeApp-debug.apk` on the default path.
      if (!fs.existsSync(packageApkSource)) throw new Error(`the verified APK disappeared before packaging: ${packageApkSource}`)
      fs.copyFileSync(packageApkSource, apkPath)

      const apkSize = fs.statSync(apkPath).size
      const zipSize = fs.statSync(zipPath).size
      const apkSha256 = sha256File(apkPath)
      const zipSha256 = sha256File(zipPath)
      // Same three-column format as the packaging recipe: name, size in MB, sha256.
      const checksumRow = (name, size, sha256) => `${name.padEnd(46)} ${(size / (1024 * 1024)).toFixed(1).padStart(8)} MB  ${sha256}`
      const apkRow = checksumRow(path.basename(apkPath), apkSize, apkSha256)
      const zipRow = checksumRow(path.basename(zipPath), zipSize, zipSha256)
      // The device-QA release variant keeps the Android debug key on purpose, so
      // the checksum file is stamped: an artifact that cannot be published must
      // not look like one. `release-artifacts` asserts the stamp is present.
      const qaSigned = Boolean(packageApkClassGate && packageApkClassGate.qaSignedNotPublishable)
      const sumsLines = []
      if (options.debugApk) {
        // The debug QA APK is not a release artifact: SHA256SUMS.txt carries the
        // ZIP only, and the APK's size/hash live in a sidecar that says what it
        // is. `release-artifacts` asserts the APK is absent from the sums rows.
        sumsLines.push(`# DEBUG QA APK ${path.basename(apkPath)} is deliberately not listed below: it is application-debuggable and is not a release artifact.`)
        sumsLines.push(zipRow)
        fs.writeFileSync(debugApkNotePath, [
          '# DEBUG QA APK (packaged with --debug-apk)',
          '# This APK is application-debuggable and signed with the Android debug key.',
          '# It is deliberately absent from SHA256SUMS.txt and must not be published',
          '# as a release artifact.',
          apkRow
        ].join('\r\n') + '\r\n', 'utf8')
      } else {
        if (qaSigned) sumsLines.push('QA ARTIFACT — DEBUG-SIGNED, NOT PUBLISHABLE')
        sumsLines.push(apkRow, zipRow)
      }
      fs.writeFileSync(sumsPath, sumsLines.join('\r\n') + '\r\n', 'utf8')
      say(`[4/5] apk ${apkSize} B sha256 ${apkSha256} (${options.debugApk ? 'DEBUG QA; not in SHA256SUMS.txt' : (qaSigned ? 'QA artifact, debug-signed, NOT PUBLISHABLE' : 'release APK')})`)
      say(`[5/5] zip ${zipSize} B sha256 ${zipSha256}`)
      releaseDeliverables = {
        directory: deliverablesRoot,
        classification: options.debugApk
          ? 'LOCAL_QA_DEBUG_APK_NOT_PUBLISHABLE'
          : ((packageApkClassGate && packageApkClassGate.classification) || 'NOT_PUBLISHABLE'),
        apk: { path: apkPath, name: path.basename(apkPath), size: apkSize, sha256: apkSha256, inChecksumFile: !options.debugApk },
        zip: { path: zipPath, name: path.basename(zipPath), size: zipSize, sha256: zipSha256 },
        launcherLabel
      }
      return { exitCode: 0, stdout: JSON.stringify({ stagingDir, apkPath, zipPath, jars: jars.length }), stderr: '' }
    })
      const packageRow = steps.find((row) => row.id === 'release-package')
      if (packageRow) {
        packageRow.detail = releaseDeliverables
          ? JSON.stringify({ staging: path.relative(ARTIFACT_ROOT, stagingDir).replaceAll(path.sep, '/'), apk: path.relative(ARTIFACT_ROOT, apkPath).replaceAll(path.sep, '/'), zip: path.relative(ARTIFACT_ROOT, zipPath).replaceAll(path.sep, '/'), apkClass: releaseDeliverables.classification, launcherLabel: releaseDeliverables.launcherLabel })
          : String(packageStep.stderr || 'release packaging failed').slice(-2000)
      }
    }

    // 5. release-package-smoke: launch the packaged launcher once, require a live
    // javaw that owns a real window, capture it, kill it and require no leftovers.
    if (releaseDeliverables) {
      const smokeShot = path.join(stagingDir, 'smoke.png')
      const smokeScript = path.join(tmpRoot, 'release-smoke.ps1')
      fs.writeFileSync(smokeScript, RELEASE_SMOKE_SCRIPT, 'utf8')
      const powershellExe = locateOnPath('powershell.exe') || path.join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe')
      const smoke = await runProcess(powershellExe, ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', smokeScript, '-Launcher', path.join(stagingDir, 'FrameMark.bat'), '-WorkingDirectory', stagingDir, '-Shot', smokeShot, '-WaitSeconds', '25'], { cwd: ROOT, env: process.env, logFile: path.join(logRoot, 'release-package-smoke.log'), timeoutMs: 5 * 60 * 1000 })
      const smokeResult = parseSmokeResult(smoke.stdout)
      const smokeOk = Boolean(smokeResult) && smokeResult.ok === true && smokeResult.windowHandle > 0 && Array.isArray(smokeResult.remainingPids) && smokeResult.remainingPids.length === 0
      gates.push(requiredCheck('release-package-smoke', smokeOk ? 'PASS' : 'FAIL', JSON.stringify(smokeResult || { exitCode: smoke.exitCode, stderr: String(smoke.stderr || '').slice(-1500), stdout: String(smoke.stdout || '').slice(-500) })))
    } else {
      gates.push(requiredCheck('release-package-smoke', 'FAIL', JSON.stringify({
        notRun: true,
        detail: 'release-package did not produce a package to smoke; nothing was published. See the release-package step and RELEASE-STATUS.json.'
      })))
    }

    // 6. release-artifacts: the shipped files exist, are not truncated and match
    // the checksum file they travel with. With --debug-apk the APK must be absent
    // from the checksum rows and its hash must live in DEBUG-QA-APK.txt instead;
    // a QA-signed release must carry the NOT PUBLISHABLE stamp.
    const sumsText = fs.existsSync(sumsPath) ? fs.readFileSync(sumsPath, 'utf8') : ''
    const sumsRows = parseChecksumLines(sumsText)
    const checksumRows = sumsRows.filter((row) => Boolean(row.name && row.sha256))
    const expectedSumsNames = options.debugApk ? [path.basename(zipPath)] : [path.basename(apkPath), path.basename(zipPath)]
    const sumsShapeOk = checksumRows.length === expectedSumsNames.length &&
      expectedSumsNames.every((name) => checksumRows.some((row) => row.name === name))
    const sumsMatchOk = sumsShapeOk && checksumRows.every((row) => {
      const target = path.join(deliverablesRoot, row.name)
      return fs.existsSync(target) && fs.statSync(target).isFile() && sha256File(target) === row.sha256
    })
    const debugNoteRows = fs.existsSync(debugApkNotePath)
      ? parseChecksumLines(fs.readFileSync(debugApkNotePath, 'utf8')).filter((row) => row.name)
      : []
    const debugApkHashOk = !options.debugApk || (
      debugNoteRows.length === 1 &&
      debugNoteRows[0].name === path.basename(apkPath) &&
      fs.existsSync(apkPath) && sha256File(apkPath) === debugNoteRows[0].sha256
    )
    const debugApkExcludedFromSums = !options.debugApk || !checksumRows.some((row) => row.name === path.basename(apkPath))
    const qaStampExpected = Boolean(packageApkClassGate && packageApkClassGate.qaSignedNotPublishable && !options.debugApk)
    const qaStampPresent = !qaStampExpected || sumsText.includes('QA ARTIFACT — DEBUG-SIGNED, NOT PUBLISHABLE')
    const apkMinimum = 100 * 1024 * 1024
    const zipMinimum = 150 * 1024 * 1024
    const artifactsOk = Boolean(releaseDeliverables) &&
      fs.existsSync(releaseDeliverables.apk.path) && fs.statSync(releaseDeliverables.apk.path).size > apkMinimum &&
      fs.existsSync(releaseDeliverables.zip.path) && fs.statSync(releaseDeliverables.zip.path).size > zipMinimum &&
      sha256File(releaseDeliverables.apk.path) === releaseDeliverables.apk.sha256 &&
      sha256File(releaseDeliverables.zip.path) === releaseDeliverables.zip.sha256 &&
      sumsShapeOk && sumsMatchOk && debugApkHashOk && debugApkExcludedFromSums && qaStampPresent
    gates.push(requiredCheck('release-artifacts', artifactsOk ? 'PASS' : 'FAIL', JSON.stringify({
      apk: releaseDeliverables ? { path: path.relative(ARTIFACT_ROOT, releaseDeliverables.apk.path).replaceAll(path.sep, '/'), name: releaseDeliverables.apk.name, size: releaseDeliverables.apk.size, sha256: releaseDeliverables.apk.sha256, classification: releaseDeliverables.classification } : null,
      zip: releaseDeliverables ? { path: path.relative(ARTIFACT_ROOT, releaseDeliverables.zip.path).replaceAll(path.sep, '/'), size: releaseDeliverables.zip.size, sha256: releaseDeliverables.zip.sha256 } : null,
      thresholds: { apkMinimumBytes: apkMinimum, zipMinimumBytes: zipMinimum },
      sha256sums: { file: path.relative(ARTIFACT_ROOT, sumsPath).replaceAll(path.sep, '/'), rows: checksumRows.length, headerLines: sumsRows.length - checksumRows.length, shapeOk: sumsShapeOk, matchesFiles: sumsMatchOk, expectedNames: expectedSumsNames, qaStampExpected, qaStampPresent },
      debugApkNote: options.debugApk ? { file: path.relative(ARTIFACT_ROOT, debugApkNotePath).replaceAll(path.sep, '/'), rows: debugNoteRows.length, hashMatches: debugApkHashOk, absentFromSums: debugApkExcludedFromSums } : null,
      ...(artifactsOk ? {} : { hint: 'the deliverables are not publishable: fix the release-package step, then re-run; a failed run removes the ZIP/release APK/SHA256SUMS and writes RELEASE-STATUS.json instead (a -DEBUGQA.apk is kept because it is explicitly non-publishable)' })
    })))
  }

  if (options.skipGates) {
    gates.push(requiredCheck('required-gates-enabled', 'SKIP', '--skip-gates bypassed required build/release gates'))
  }
  if (!options.skipGates && !releaseSurface.debugGuardContainsQaHooks) {
    gates.push(requiredCheck('release-intent-source-guard', 'FAIL', 'QA Intent hook guard is missing or does not end before normal UI content'))
  }

  const requiredFailures = collectRequiredFailures([...steps, ...gates])
  const exitCode = requiredFailures.length ? 1 : 0

  // A failed run must not leave publishable artifacts. The pre-packaging check
  // already refused to write them; this second pass catches post-packaging gates
  // (smoke, checksum re-verification) and anything else that failed late: the
  // ZIP, the APK and SHA256SUMS.txt are removed and RELEASE-STATUS.json records
  // the failure instead.
  if (options.package && releaseDeliverablesRoot && requiredFailures.length) {
    const purged = purgePublishableDeliverables(releaseDeliverablesRoot)
    const keptForQa = fs.existsSync(releaseDeliverablesRoot)
      ? fs.readdirSync(releaseDeliverablesRoot).filter((name) => /-DEBUGQA\.apk$/i.test(name) || name === 'DEBUG-QA-APK.txt')
      : []
    const statusFile = writeReleaseStatus(releaseDeliverablesRoot, {
      status: 'FAIL',
      phase: releaseDeliverables ? 'after-packaging' : 'before-packaging',
      runId,
      versionName: releaseVersionName,
      head: readHead(),
      blockers: requiredFailures.map((row) => ({
        id: row.id,
        kind: steps.includes(row) ? 'step' : 'gate',
        status: row.status,
        detail: String(row.detail || '').slice(0, 600)
      })),
      purged,
      keptForQa,
      deliverables: null,
      instruction: keptForQa.length
        ? 'The run failed, so the ZIP, the release APK and SHA256SUMS.txt were removed. The -DEBUGQA.apk and DEBUG-QA-APK.txt were kept because they are explicitly not publishable (application-debuggable, absent from the checksum file). Fix the listed required steps/gates and re-run for a release artifact.'
        : (purged.length
            ? 'The run failed, so the ZIP, the APK and SHA256SUMS.txt were removed. Nothing in this directory is publishable: fix the listed required steps/gates and re-run.'
            : 'The run failed before any deliverable was written. Fix the listed required steps/gates and re-run.')
    })
    releaseStatus = statusFile.document
    releaseDeliverables = null
  }

  const releaseArtifactClassification = options.package
    ? (releaseDeliverables ? releaseDeliverables.classification : 'NOT_PUBLISHED')
    : (options.release ? (options.releaseQa ? 'LOCAL_QA_DEBUG_SIGNED_NOT_PUBLISHABLE' : 'UNSIGNED_OR_PRODUCTION_SIGNED') : null)
  const report = {
    schema: 'framemark-native-build-report/v1',
    createdAt: new Date().toISOString(),
    repoRoot: ROOT,
    head: readHead(),
    options,
    releaseArtifactClassification,
    toolchain: CONFIG,
    preflight,
    desktopBuildProvenance,
    desktopEnginesProvenance,
    desktopRuntimeProvenance,
    nativeBuildProvenance,
    androidPackageSourceIntegrity,
    // Present only in --package mode; the default report keeps its shape.
    ...(options.package ? { deliverables: releaseDeliverables, releaseStatus } : {}),
    steps,
    gates,
    summary: {
      exitCode,
      requiredFailures: requiredFailures.map((row) => row.id),
      result: exitCode === 0 ? 'PASS' : 'FAIL',
      ...(options.package ? { deliverables: releaseDeliverables, releaseStatus } : {})
    }
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

async function verifyApk (id, apk, tools, step, expectedApplicationId = 'com.framemark.native', hint = null) {
  const jarResult = await step(`${id}-entries`, 'Inspect APK native library entries', (logFile) => runProcess(path.join(tools.javaHome, 'bin', 'jar.exe'), ['tf', apk], { cwd: ROOT, env: process.env, logFile, timeoutMs: 30000 }))
  if (jarResult.status !== 'PASS') return requiredCheck(id, 'FAIL', `could not inspect APK: ${apk}${hint ? `; ${hint}` : ''}`)
  const native = jarResult.stdout.split(/\r?\n/).filter((line) => /^lib\/[^/]+\/.*\.so$/i.test(line))
  const abis = [...new Set(native.map((line) => line.split('/')[1]))].sort()
  const hasEngine = native.includes('lib/arm64-v8a/libframemark_engine.so')
  const onlyArm64 = abis.length === 1 && abis[0] === CONFIG.targets.androidAbi && hasEngine
  const aapt = path.join(tools.androidSdk, 'build-tools', CONFIG.versions.androidBuildTools, 'aapt.exe')
  const badging = await step(`${id}-badging`, 'Verify APK application id', (logFile) => runProcess(aapt, ['dump', 'badging', apk], { cwd: ROOT, env: process.env, logFile, timeoutMs: 30000 }))
  const packageMatch = badging.stdout.match(/^package: name='([^']+)'/m)
  const packageOk = badging.status === 'PASS' && packageMatch?.[1] === expectedApplicationId
  return requiredCheck(id, onlyArm64 && packageOk ? 'PASS' : 'FAIL', JSON.stringify({ abis, hasEngine, packageOk, expectedApplicationId, actualApplicationId: packageMatch?.[1] || null, apk: path.relative(ROOT, apk).replaceAll(path.sep, '/'), ...(packageOk && onlyArm64 ? {} : { hint }) }))
}

// `apksigner verify --print-certs` prints one DN line per signer.
function parseSignerSubjects (stdout) {
  return [...String(stdout || '').matchAll(/^Signer #\d+ certificate DN:\s*([^\r\n]+)$/gm)].map((match) => match[1].trim())
}

/**
 * The release-class decision for a delivered APK.
 *
 * The defect this gate exists for is an APK that says it is `application-
 * debuggable`: that is never a release artifact, with no exceptions. The signing
 * certificate is a separate axis:
 *
 *  - the device-QA release variant deliberately keeps the Android debug
 *    keystore, so a `CN=Android Debug` certificate is accepted as a QA artifact
 *    ONLY when the delivered applicationId still carries this run's QA suffix
 *    (that id cannot be published as the product); it is stamped
 *    `QA ARTIFACT — DEBUG-SIGNED, NOT PUBLISHABLE` in SHA256SUMS.txt and reported
 *    as `LOCAL_QA_DEBUG_SIGNED_NOT_PUBLISHABLE`;
 *  - a production-intent run (`--release` without `--release-qa`, or `--sign`)
 *    must carry a non-debug certificate, i.e. the production keystore;
 *  - anything unsigned is never shippable.
 */
function classifyApkClass ({ debuggable, signerSubjects, applicationId = null, qaSuffix = null, productionIntent = false }) {
  const subjects = Array.isArray(signerSubjects) ? signerSubjects : []
  const signed = subjects.length === 1
  const debugCertificate = signed && subjects[0].split(',').some((part) => part.trim() === 'CN=Android Debug')
  const qaApplicationId = Boolean(qaSuffix && applicationId && String(applicationId).endsWith(qaSuffix))
  const failures = []
  // Rule 1 (no exceptions): a debuggable APK is not a release artifact. The
  // --debug-apk escape hatch still packages it (marked DEBUGQA and kept out of
  // SHA256SUMS.txt), but it can never pass this gate.
  if (debuggable) {
    failures.push('the delivered APK is application-debuggable; --package must ship :composeApp:assembleRelease, not composeApp-debug.apk (--debug-apk produces a DEBUGQA artifact, never a release)')
  }
  if (productionIntent) {
    if (!signed) failures.push('this is a production-intent run (--release without --release-qa, or --sign) but the delivered APK is not signed; pass --sign with the FM_SIGNING_* production keystore')
    if (debugCertificate) failures.push('this is a production-intent run but the delivered APK is signed with the Android debug certificate (CN=Android Debug); pass --sign with the production keystore (FM_SIGNING_*)')
  } else if (!signed) {
    failures.push('the delivered APK is not signed; pass --sign with the FM_SIGNING_* variables to sign it')
  } else if (debugCertificate && !qaApplicationId) {
    failures.push(`the delivered APK is signed with the Android debug certificate (CN=Android Debug) but its application id (${applicationId || 'unknown'}) does not carry the QA suffix (${qaSuffix || 'not set'}); package a QA build or sign with the production keystore (FM_SIGNING_*)`)
  }
  const classification = failures.length
    ? 'NOT_PUBLISHABLE'
    : productionIntent
      ? 'PRODUCTION_SIGNED'
      : (debugCertificate ? 'LOCAL_QA_DEBUG_SIGNED_NOT_PUBLISHABLE' : 'RELEASE_APK_SIGNED')
  return {
    status: failures.length ? 'FAIL' : 'PASS',
    debuggable: Boolean(debuggable),
    signerCount: subjects.length,
    debugCertificate,
    qaApplicationId,
    productionIntent: Boolean(productionIntent),
    classification,
    stamp: classification === 'LOCAL_QA_DEBUG_SIGNED_NOT_PUBLISHABLE' ? 'QA ARTIFACT — DEBUG-SIGNED, NOT PUBLISHABLE' : null,
    failures
  }
}

/**
 * Packaging gate `package-apk-class`: read the delivered APK's badging (which
 * also gives the applicationId that decides the QA-suffix rule) and certificate
 * with the same aapt/apksigner tools `verifyApk`/`verifyQaSignature` use, then
 * apply classifyApkClass().
 */
async function verifyApkClass (id, apk, tools, step, { qaSuffix = null, productionIntent = false } = {}) {
  const aapt = path.join(tools.androidSdk, 'build-tools', CONFIG.versions.androidBuildTools, 'aapt.exe')
  const badging = await step(`${id}-badging`, 'Verify the delivered APK is not application-debuggable', (logFile) => runProcess(aapt, ['dump', 'badging', apk], { cwd: ROOT, env: process.env, logFile, timeoutMs: 30000 }))
  const debuggable = badging.status === 'PASS' && /^application-debuggable$/m.test(badging.stdout)
  const packageMatch = badging.stdout.match(/^package: name='([^']+)'/m)
  const applicationId = packageMatch?.[1] || null
  const apksignerJar = path.join(tools.androidSdk, 'build-tools', CONFIG.versions.androidBuildTools, 'lib', 'apksigner.jar')
  const verification = await step(`${id}-certificate`, 'Verify the delivered APK signing certificate', (logFile) => runProcess(tools.java, ['-jar', apksignerJar, 'verify', '--verbose', '--print-certs', apk], { cwd: ROOT, env: process.env, logFile, timeoutMs: 30000 }))
  const subjects = verification.status === 'PASS' ? parseSignerSubjects(verification.stdout) : []
  const result = classifyApkClass({ debuggable, signerSubjects: subjects, applicationId, qaSuffix, productionIntent })
  const gate = requiredCheck(id, result.status, JSON.stringify({
    ...result,
    badging: badging.status,
    apksigner: verification.status,
    certificateSubjects: subjects,
    apk: path.relative(ROOT, apk).replaceAll(path.sep, '/'),
    ...(result.status === 'PASS' ? {} : { hint: result.failures.join(' | ') })
  }))
  // Callers stamp SHA256SUMS.txt and the report from these fields. They live on
  // the gate object (not only inside the JSON detail) so the packaging step
  // does not have to re-parse its own detail string.
  gate.classification = result.classification
  gate.qaSignedNotPublishable = result.classification === 'LOCAL_QA_DEBUG_SIGNED_NOT_PUBLISHABLE'
  gate.stamp = result.stamp
  return gate
}

async function verifyQaSignature (apk, tools, step) {
  const apksignerJar = path.join(tools.androidSdk, 'build-tools', CONFIG.versions.androidBuildTools, 'lib', 'apksigner.jar')
  const verification = await step('verify-release-qa-signature', 'Verify isolated QA release signature', (logFile) => runProcess(tools.java, ['-jar', apksignerJar, 'verify', '--verbose', '--print-certs', apk], { cwd: ROOT, env: process.env, logFile, timeoutMs: 30000 }))
  const subjects = parseSignerSubjects(verification.stdout)
  const debugCertificate = subjects.length === 1 && subjects[0].split(',').some((part) => part.trim() === 'CN=Android Debug')
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

/** `--package`: the release label comes from the single version source, never a literal. */
function readVersionProperties () {
  const filename = path.join(ROOT, 'version.properties')
  if (!fs.existsSync(filename)) throw new Error('version.properties is missing; the release label must come from it')
  const values = {}
  for (const line of fs.readFileSync(filename, 'utf8').split(/\r?\n/)) {
    const text = line.trim()
    if (!text || text.startsWith('#')) continue
    const separator = text.indexOf('=')
    if (separator < 0) throw new Error(`Invalid version.properties line: ${line}`)
    values[text.slice(0, separator).trim()] = text.slice(separator + 1).trim()
  }
  const versionName = values.versionName || ''
  if (!versionName) throw new Error('version.properties has no versionName; the release package needs a label')
  if (!/^[0-9A-Za-z._-]+$/.test(versionName)) throw new Error(`versionName is not safe for file names or the launcher label: ${versionName}`)
  return values
}

function readGitValue (args, fallback = null) {
  try {
    const { execFileSync } = require('node:child_process')
    return execFileSync('git', args, { cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim() || fallback
  } catch { return fallback }
}

// JUnit XML totals of the isolated Gradle run. Reports written before the step
// started are ignored, so the Konsist subset from arch-konsist can never satisfy
// release-tests.
function readJUnitTotals (dir, sinceMs = 0) {
  const totals = { tests: 0, failures: 0, errors: 0, skipped: 0, suites: 0, files: 0 }
  const files = listFiles(dir).filter((file) => file.endsWith('.xml') && (!sinceMs || fs.statSync(file).mtimeMs >= sinceMs))
  for (const file of files) {
    const text = fs.readFileSync(file, 'utf8')
    for (const match of text.matchAll(/<testsuite\b([^>]*)>/g)) {
      const attributes = match[1]
      const count = (name) => Number((attributes.match(new RegExp(`(?:^|\\s)${name}="(\\d+)"`)) || [])[1] || 0)
      totals.tests += count('tests')
      totals.failures += count('failures')
      totals.errors += count('errors')
      totals.skipped += count('skipped')
      totals.suites += 1
    }
  }
  totals.files = files.length
  return totals
}

function treeStats (dir) {
  const files = listFiles(dir)
  return { files: files.length, bytes: files.reduce((sum, file) => sum + fs.statSync(file).size, 0) }
}

// The smoke probe prints exactly one `SMOKE_JSON:` line; everything else is noise.
function parseSmokeResult (stdout) {
  const line = String(stdout || '').split(/\r?\n/).find((entry) => entry.startsWith('SMOKE_JSON:'))
  if (!line) return null
  try { return JSON.parse(line.slice('SMOKE_JSON:'.length)) } catch { return null }
}

// --package smoke probe: start the packaged launcher once, require a live javaw
// that owns a main window handle, capture that window to PNG, kill the process
// tree's javaw and require that nothing is left behind. One machine-readable
// SMOKE_JSON line is printed; the release-package-smoke gate records it.
const RELEASE_SMOKE_SCRIPT = `param(
  [Parameter(Mandatory = $true)][string] $Launcher,
  [Parameter(Mandatory = $true)][string] $WorkingDirectory,
  [Parameter(Mandatory = $true)][string] $Shot,
  [int] $WaitSeconds = 25
)
$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }
$result = [ordered]@{
  schema = 'framemark-release-smoke/v1'
  ok = $false
  launcher = $Launcher
  workingDirectory = $WorkingDirectory
  shot = $Shot
  shotBytes = 0
  pid = 0
  windowHandle = 0
  windowTitle = ''
  windowWidth = 0
  windowHeight = 0
  sampledColors = 0
  captureMethod = ''
  foreground = $false
  printWindowColors = 0
  screenColors = 0
  foundAfterMs = 0
  killMs = 0
  beforePids = @()
  killedPids = @()
  remainingPids = @()
  error = $null
}
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public struct FmSmokeRect { public int Left; public int Top; public int Right; public int Bottom; }
public class FmSmokeWin32 {
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out FmSmokeRect rect);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
    [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, int hWndInsertAfter, int X, int Y, int cx, int cy, uint uFlags);
    [DllImport("user32.dll")] public static extern bool BringWindowToTop(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hWnd, IntPtr hdcBlt, uint nFlags);
}
'@
function Get-SampledColors($picture, $pictureWidth, $pictureHeight) {
  $colors = @{}
  $stepX = [Math]::Max(1, [int]($pictureWidth / 20))
  $stepY = [Math]::Max(1, [int]($pictureHeight / 20))
  for ($x = 0; $x -lt $pictureWidth; $x += $stepX) {
    for ($y = 0; $y -lt $pictureHeight; $y += $stepY) {
      $colors[$picture.GetPixel($x, $y).ToArgb()] = $true
      if ($colors.Count -ge 64) { return $colors.Count }
    }
  }
  return $colors.Count
}
$failure = $null
try {
  $result.beforePids = @(Get-Process -Name javaw -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })
  $started = Get-Date
  $arguments = if ($Launcher.Contains(' ')) { '"' + $Launcher + '"' } else { $Launcher }
  Start-Process -FilePath $env:ComSpec -ArgumentList '/c', $arguments -WorkingDirectory $WorkingDirectory -WindowStyle Hidden | Out-Null
  $deadline = $started.AddSeconds($WaitSeconds)
  $target = $null
  while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 500
    $candidates = @(Get-Process -Name javaw -ErrorAction SilentlyContinue | Where-Object { $result.beforePids -notcontains $_.Id })
    $visible = @($candidates | Where-Object { $_.MainWindowHandle -ne 0 })
    if ($visible.Count -gt 0) { $target = $visible[0]; break }
  }
  if ($null -eq $target) { throw ('no javaw main window appeared within ' + $WaitSeconds + 's') }
  $result.foundAfterMs = [int]((Get-Date) - $started).TotalMilliseconds
  $target.Refresh()
  $result.pid = $target.Id
  $result.windowHandle = [int64]$target.MainWindowHandle
  $title = [string]$target.MainWindowTitle
  if ($title.Length -gt 0) { $result.windowTitle = (@($title.ToCharArray()) | Where-Object { [int]$_ -ge 32 -and [int]$_ -lt 127 }) -join '' }
  [FmSmokeWin32]::ShowWindow($target.MainWindowHandle, 9) | Out-Null
  # Another foreground window (a browser, an editor) must not end up in the
  # screenshot: raise the app above the normal z-order, then ask for the
  # foreground. PrintWindow is preferred because it renders the window itself
  # even when Windows refuses the foreground change.
  [FmSmokeWin32]::SetWindowPos($target.MainWindowHandle, -1, 0, 0, 0, 0, 0x43) | Out-Null
  [FmSmokeWin32]::BringWindowToTop($target.MainWindowHandle) | Out-Null
  [FmSmokeWin32]::SetForegroundWindow($target.MainWindowHandle) | Out-Null
  Start-Sleep -Milliseconds 1500
  $result.foreground = ([FmSmokeWin32]::GetForegroundWindow().ToInt64() -eq $target.MainWindowHandle.ToInt64())
  $rect = New-Object -TypeName FmSmokeRect
  if (-not [FmSmokeWin32]::GetWindowRect($target.MainWindowHandle, [ref]$rect)) { throw 'GetWindowRect failed' }
  $width = $rect.Right - $rect.Left
  $height = $rect.Bottom - $rect.Top
  $result.windowWidth = $width
  $result.windowHeight = $height
  if ($width -le 0 -or $height -le 0) { throw ('the window has no area: ' + $width + 'x' + $height) }
  $bitmap = New-Object -TypeName System.Drawing.Bitmap -ArgumentList $width, $height
  $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
  try {
    $hdc = $graphics.GetHdc()
    try { $printed = [FmSmokeWin32]::PrintWindow($target.MainWindowHandle, $hdc, 2) } finally { $graphics.ReleaseHdc($hdc) }
    if ($printed) { $result.printWindowColors = Get-SampledColors $bitmap $width $height }
    if ($result.printWindowColors -ge 5) {
      $result.captureMethod = 'printwindow'
    } else {
      $result.captureMethod = 'screen'
      $size = New-Object -TypeName System.Drawing.Size -ArgumentList $width, $height
      $graphics.CopyFromScreen($rect.Left, $rect.Top, 0, 0, $size)
      $result.screenColors = Get-SampledColors $bitmap $width $height
    }
    if ($result.captureMethod -eq 'printwindow') { $result.sampledColors = $result.printWindowColors } else { $result.sampledColors = $result.screenColors }
    $directory = Split-Path -Parent $Shot
    if (-not (Test-Path -LiteralPath $directory)) { New-Item -ItemType Directory -Force -Path $directory | Out-Null }
    $bitmap.Save($Shot, [System.Drawing.Imaging.ImageFormat]::Png)
  } finally {
    $graphics.Dispose()
    $bitmap.Dispose()
  }
  $result.shotBytes = (Get-Item -LiteralPath $Shot).Length
  [FmSmokeWin32]::SetWindowPos($target.MainWindowHandle, -2, 0, 0, 0, 0, 0x43) | Out-Null
} catch {
  $failure = $_.Exception.Message
}
$killStarted = Get-Date
try {
  $result.killedPids = @(Get-Process -Name javaw -ErrorAction SilentlyContinue | Where-Object { $result.beforePids -notcontains $_.Id } | ForEach-Object { $_.Id })
  if ($result.pid -ne 0 -and $result.killedPids -notcontains $result.pid) { $result.killedPids = @($result.killedPids) + $result.pid }
  foreach ($processId in $result.killedPids) { Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue }
  $deadline = (Get-Date).AddSeconds(10)
  while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 250
    $alive = @(Get-Process -Name javaw -ErrorAction SilentlyContinue | Where-Object { $result.beforePids -notcontains $_.Id })
    if ($alive.Count -eq 0) { break }
  }
  $leftover = @(Get-Process -Name javaw -ErrorAction SilentlyContinue | Where-Object { $result.beforePids -notcontains $_.Id })
  foreach ($process in $leftover) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
  if ($leftover.Count -gt 0) {
    Start-Sleep -Milliseconds 500
    $leftover = @(Get-Process -Name javaw -ErrorAction SilentlyContinue | Where-Object { $result.beforePids -notcontains $_.Id })
  }
  $result.remainingPids = @($leftover | ForEach-Object { $_.Id })
} catch {
  if ($null -eq $failure) { $failure = $_.Exception.Message }
}
$result.killMs = [int]((Get-Date) - $killStarted).TotalMilliseconds
if ($null -ne $failure) { $result.error = $failure }
$result.ok = ($null -eq $failure) -and ($result.windowHandle -gt 0) -and ($result.shotBytes -gt 0) -and ($result.remainingPids.Count -eq 0)
'SMOKE_JSON:' + ($result | ConvertTo-Json -Compress -Depth 5)
if ($result.ok) { exit 0 } else { exit 1 }
`

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

module.exports = {
  DESKTOP_REQUIRED_CLASSES,
  QA_SURFACE_TOKENS,
  checkReleaseSurface,
  classifyApkClass,
  collectRequiredFailures,
  desktopClassRoot,
  enumerateDebugGuardedIntentHooks,
  findQaSurfaceSources,
  parseChecksumLines,
  purgePublishableDeliverables,
  qaSurfaceClassOffenders,
  verifyDesktopClassManifest,
  verifyDesktopClassSources,
  verifyDesktopCompileNotFromCache,
  verifyPackagingSurface,
  writeReleaseStatus
}
