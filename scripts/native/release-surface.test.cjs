'use strict'

// Release-surface guard tests. They exercise the pure gate functions directly so
// the structural rules are verified without reading or modifying the app.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const test = require('node:test')

const {
  checkReleaseSurface,
  classifyApkClass,
  collectRequiredFailures,
  enumerateDebugGuardedIntentHooks,
  findQaSurfaceSources,
  parseChecksumLines,
  purgePublishableDeliverables,
  qaSurfaceClassOffenders,
  verifyDesktopCompileNotFromCache,
  verifyPackagingSurface,
  writeReleaseStatus
} = require('./build.cjs')

const ROOT = path.resolve(__dirname, '../..')
const SOURCE_PATH = path.join(ROOT, 'ui/composeApp/src/androidMain/kotlin/com/framemark/native/MainActivity.kt')
const MANIFEST_PATH = path.join(ROOT, 'ui/composeApp/src/androidMain/AndroidManifest.xml')
const realSource = fs.readFileSync(SOURCE_PATH, 'utf8')
const realManifest = fs.readFileSync(MANIFEST_PATH, 'utf8')

const GUARD_OPEN = '        if (BuildConfig.DEBUG) {'
const QA_HOOK = '        LaunchOptions.openCardId = intent?.getStringExtra("fmCard")?.takeIf { it.isNotBlank() }'
const PHOTO_HOOK = '        LaunchOptions.userPhotoPath = intent?.getStringExtra("fmPhoto")?.takeIf { it.isNotBlank() }'
const BARE_INTENT_HOOK = '        watermarkSlot = intent?.getIntExtra("fmWmAnchor", 8) ?: 8'
const CLOSE = '        }'
const MULTI_LINE_CONTENT = ['        setContent {', '            FrameMarkApp()', '        }']
const SINGLE_LINE_CONTENT = ['        setContent { FrameMarkApp() }']

function activity (body) {
  return ['package com.framemark.native', '', 'class MainActivity {', '    fun onCreate() {', ...body, '    }', '}', ''].join('\n')
}

function tempDir (prefix) {
  return fs.mkdtempSync(path.join(os.tmpdir(), prefix))
}

function expectPass (source, manifest = realManifest) {
  const result = checkReleaseSurface(source, manifest)
  assert.deepEqual(result, { status: 'PASS', debugGuardContainsQaHooks: true, normalExportedLauncherPreserved: true })
  return result
}

function expectGuardFailure (source, manifest = realManifest) {
  const result = checkReleaseSurface(source, manifest)
  assert.equal(result.status, 'FAIL')
  assert.equal(result.debugGuardContainsQaHooks, false)
  assert.equal(result.normalExportedLauncherPreserved, true)
  return result
}

test('current MainActivity.kt passes', () => {
  expectPass(realSource, realManifest)
})

test('legacy single-line setContent still passes', () => {
  expectPass(activity([GUARD_OPEN, QA_HOOK, CLOSE, ...SINGLE_LINE_CONTENT]))
})

test('QA hook moved after setContent fails', () => {
  expectGuardFailure(activity([GUARD_OPEN, '            debugOnlyLaunchSetup()', CLOSE, ...MULTI_LINE_CONTENT, QA_HOOK]))
})

test('missing debug guard fails', () => {
  expectGuardFailure(activity([
    QA_HOOK,
    '        LaunchOptions.userPhotoPath = intent?.getStringExtra("fmPhoto")?.takeIf { it.isNotBlank() }',
    ...MULTI_LINE_CONTENT
  ]))
})

test('code inserted between the guard close and setContent fails', () => {
  expectGuardFailure(activity([GUARD_OPEN, QA_HOOK, CLOSE, '        applySystemBarInk()', ...MULTI_LINE_CONTENT]))
})

test('QA hook between two debug blocks fails', () => {
  expectGuardFailure(activity([
    GUARD_OPEN,
    '            debugOnlyLaunchSetup()',
    CLOSE,
    QA_HOOK,
    GUARD_OPEN,
    '            anotherDebugOnlySetup()',
    CLOSE,
    ...MULTI_LINE_CONTENT
  ]))
})

test('manifest without a LAUNCHER category fails', () => {
  const manifestWithoutLauncher = realManifest.replace(/\s*<category android:name="android\.intent\.category\.LAUNCHER"\s*\/>/, '')
  assert.notEqual(manifestWithoutLauncher, realManifest)
  const result = checkReleaseSurface(realSource, manifestWithoutLauncher)
  assert.equal(result.status, 'FAIL')
  assert.equal(result.normalExportedLauncherPreserved, false)
  assert.equal(result.debugGuardContainsQaHooks, true)
})

test('CRLF source is accepted', () => {
  const source = activity([GUARD_OPEN, QA_HOOK, CLOSE, ...MULTI_LINE_CONTENT]).replaceAll('\n', '\r\n')
  expectPass(source)
})

// ---------------------------------------------------------------------------
// WP-RELEASE: the source guard enumerates EVERY Intent-extra hook, not the one
// `openCardId` line. Moving any other hook out of a DEBUG block must fail.
// ---------------------------------------------------------------------------

test('real MainActivity has every Intent hook inside a DEBUG block', () => {
  const analysis = enumerateDebugGuardedIntentHooks(realSource)
  assert.ok(analysis.hooks.length >= 12, `expected the ~12 QA hooks, found ${analysis.hooks.length}`)
  assert.deepEqual(analysis.unguarded, [])
  assert.ok(analysis.blocks.length >= 1)
  assert.ok(analysis.hooks.some((hook) => hook.owner === 'LaunchOptions.openCardId'))
})

test('a second LaunchOptions hook moved outside the DEBUG block fails', () => {
  expectGuardFailure(activity([
    GUARD_OPEN,
    QA_HOOK,
    PHOTO_HOOK,
    CLOSE,
    ...MULTI_LINE_CONTENT,
    PHOTO_HOOK // same shape, now after setContent and outside every DEBUG block
  ]))
})

test('a bare intent extra read outside the DEBUG block fails', () => {
  const result = expectGuardFailure(activity([
    GUARD_OPEN,
    QA_HOOK,
    CLOSE,
    ...MULTI_LINE_CONTENT,
    BARE_INTENT_HOOK
  ]))
  const analysis = enumerateDebugGuardedIntentHooks(activity([
    GUARD_OPEN, QA_HOOK, CLOSE, ...MULTI_LINE_CONTENT, BARE_INTENT_HOOK
  ]))
  assert.equal(analysis.unguarded.length, 1)
  assert.match(analysis.unguarded[0].text, /fmWmAnchor/)
  assert.equal(result.debugGuardContainsQaHooks, false)
})

test('a hook inside a non-DEBUG block after setContent fails', () => {
  expectGuardFailure(activity([
    GUARD_OPEN,
    QA_HOOK,
    CLOSE,
    ...MULTI_LINE_CONTENT,
    '        if (Build.VERSION.SDK_INT > 0) {',
    PHOTO_HOOK,
    CLOSE
  ]))
})

test('hooks split across two DEBUG blocks pass when the anchor block ends at setContent', () => {
  expectPass(activity([
    GUARD_OPEN,
    '            debugOnlyLaunchSetup()',
    CLOSE,
    GUARD_OPEN,
    QA_HOOK,
    PHOTO_HOOK,
    CLOSE,
    ...MULTI_LINE_CONTENT
  ]))
})

test('a multi-line LaunchOptions assignment inside the DEBUG block passes', () => {
  expectPass(activity([
    GUARD_OPEN,
    '        LaunchOptions.crashResumeOnMarkerRemoval =',
    '            intent?.getBooleanExtra("fmCrashResumeOnMarkerRemoval", false) == true',
    QA_HOOK,
    CLOSE,
    ...MULTI_LINE_CONTENT
  ]))
})

test('a commented-out hook outside the DEBUG block does not fail the guard', () => {
  expectPass(activity([
    GUARD_OPEN,
    QA_HOOK,
    CLOSE,
    ...MULTI_LINE_CONTENT,
    '        // LaunchOptions.exportFormat = intent?.getStringExtra("fmFormat")'
  ]))
})

test('a source with no Intent hook at all fails instead of passing vacuously', () => {
  const result = checkReleaseSurface(activity([GUARD_OPEN, '            debugOnlySetup()', CLOSE, ...MULTI_LINE_CONTENT]))
  assert.equal(result.status, 'FAIL')
  assert.equal(result.debugGuardContainsQaHooks, false)
})

// ---------------------------------------------------------------------------
// WP-RELEASE: the desktop build-cache gate fails closed when its log is absent.
// ---------------------------------------------------------------------------

test('a missing gradle log fails the desktop build-cache gate', () => {
  const dir = tempDir('fm-release-cache-missing-')
  const result = verifyDesktopCompileNotFromCache(path.join(dir, 'gradle.log'))
  assert.equal(result.status, 'FAIL')
  assert.equal(result.logPresent, false)
  assert.match(result.detail, /log is missing/)
})

test('a clean gradle log passes and a FROM-CACHE log fails', () => {
  const dir = tempDir('fm-release-cache-')
  const clean = path.join(dir, 'clean.log')
  fs.writeFileSync(clean, '> Task :composeApp:compileKotlinDesktop\nBUILD SUCCESSFUL\n')
  assert.equal(verifyDesktopCompileNotFromCache(clean).status, 'PASS')
  const cached = path.join(dir, 'cached.log')
  fs.writeFileSync(cached, '> Task :composeApp:compileKotlinDesktop FROM-CACHE\nBUILD SUCCESSFUL\n')
  const result = verifyDesktopCompileNotFromCache(cached)
  assert.equal(result.status, 'FAIL')
  assert.deepEqual(result.fromCache, [':composeApp:compileKotlinDesktop FROM-CACHE'])
})

// ---------------------------------------------------------------------------
// WP-RELEASE: a failed run must not leave publishable artifacts.
// ---------------------------------------------------------------------------

test('a required FAIL or SKIP blocks publishing; optional rows do not', () => {
  const rows = [
    { id: 'desktop-tests', required: true, status: 'PASS' },
    { id: 'release-jar-surface', required: true, status: 'FAIL' },
    { id: 'desktop-class-manifest', required: true, status: 'SKIP' },
    { id: 'android-prebuilt-sha256', required: false, status: 'SKIP' }
  ]
  assert.deepEqual(collectRequiredFailures(rows).map((row) => row.id), ['release-jar-surface', 'desktop-class-manifest'])
})

test('a blocked run removes the ZIP/release APK/SHA256SUMS and writes RELEASE-STATUS.json', () => {
  const root = tempDir('fm-release-status-')
  const stagingName = 'FrameMark-0.9.0-rc13-win64'
  fs.mkdirSync(path.join(root, stagingName), { recursive: true })
  const publishable = [
    'FrameMark-0.9.0-rc13-arm64.apk',
    `${stagingName}-portable.zip`,
    'SHA256SUMS.txt'
  ]
  // The explicit --debug-apk artifact is not publishable, so the failed-run purge
  // leaves it and its sidecar in place.
  const keptForQa = ['FrameMark-0.9.0-rc13-arm64-DEBUGQA.apk', 'DEBUG-QA-APK.txt']
  for (const name of [...publishable, ...keptForQa]) fs.writeFileSync(path.join(root, name), 'not a real artifact')

  const blockers = collectRequiredFailures([
    { id: 'package-apk-class', required: true, status: 'FAIL', detail: 'the delivered APK is application-debuggable' },
    { id: 'release-jar-surface', required: true, status: 'FAIL', detail: 'verify/qaenv entries: 1' }
  ])
  assert.equal(blockers.length, 2)

  const purged = purgePublishableDeliverables(root)
  assert.deepEqual(purged.sort(), [...publishable].sort())
  for (const name of publishable) assert.equal(fs.existsSync(path.join(root, name)), false, `${name} was not removed`)
  for (const name of keptForQa) assert.equal(fs.existsSync(path.join(root, name)), true, `${name} should have been kept`)

  const status = writeReleaseStatus(root, {
    status: 'FAIL',
    phase: 'refused-before-packaging',
    blockers: blockers.map((row) => ({ id: row.id, status: row.status, detail: row.detail })),
    purged,
    keptForQa,
    deliverables: null,
    instruction: 'nothing was published'
  })
  const written = JSON.parse(fs.readFileSync(status.path, 'utf8'))
  assert.equal(written.schema, 'framemark-release-status/v1')
  assert.equal(written.status, 'FAIL')
  assert.equal(written.phase, 'refused-before-packaging')
  assert.deepEqual(written.blockers.map((row) => row.id), ['package-apk-class', 'release-jar-surface'])
  assert.deepEqual(written.purged.sort(), [...publishable].sort())
  assert.deepEqual(written.keptForQa.sort(), [...keptForQa].sort())
  assert.equal(written.deliverables, null)
  assert.deepEqual(fs.readdirSync(root).sort(), [stagingName, 'RELEASE-STATUS.json', ...keptForQa].sort())
})

test('the debug QA sidecar parser ignores its comment header', () => {
  const rows = parseChecksumLines([
    '# DEBUG QA APK (packaged with --debug-apk)',
    '# not publishable',
    'FrameMark-0.9.0-rc13-arm64-DEBUGQA.apk      120.0 MB   ' + 'a'.repeat(64)
  ].join('\r\n'))
  const checksums = rows.filter((row) => row.name)
  assert.equal(checksums.length, 1)
  assert.equal(checksums[0].name, 'FrameMark-0.9.0-rc13-arm64-DEBUGQA.apk')
  assert.equal(checksums[0].sha256, 'a'.repeat(64))
})

test('the QA-artifact stamp is a header line, not a checksum row', () => {
  const rows = parseChecksumLines([
    'QA ARTIFACT — DEBUG-SIGNED, NOT PUBLISHABLE',
    'FrameMark-0.9.0-rc13-arm64.apk           120.0 MB   ' + 'b'.repeat(64),
    'FrameMark-0.9.0-rc13-win64-portable.zip  198.0 MB   ' + 'c'.repeat(64)
  ].join('\r\n'))
  assert.equal(rows.length, 3)
  assert.deepEqual(rows.filter((row) => row.name).map((row) => row.name), [
    'FrameMark-0.9.0-rc13-arm64.apk',
    'FrameMark-0.9.0-rc13-win64-portable.zip'
  ])
  assert.equal(rows[0].line, 'QA ARTIFACT — DEBUG-SIGNED, NOT PUBLISHABLE')
})

// ---------------------------------------------------------------------------
// WP-RELEASE: the packaged APK class — debuggable is always refused; the debug
// certificate is a separate axis that is QA-only and stamped.
// ---------------------------------------------------------------------------

const DEBUG_CERT = 'CN=Android Debug, O=Android, C=US'
const RELEASE_CERT = 'CN=FrameMark Release, O=FrameMark, C=CN'
const QA_APP_ID = 'com.framemark.native.qa20261102'
const QA_SUFFIX = '.qa20261102'

test('a debuggable APK is refused with no exceptions', () => {
  const result = classifyApkClass({
    debuggable: true,
    signerSubjects: [DEBUG_CERT],
    applicationId: QA_APP_ID,
    qaSuffix: QA_SUFFIX,
    productionIntent: false
  })
  assert.equal(result.status, 'FAIL')
  assert.match(result.failures.join(' | '), /application-debuggable/)
  assert.equal(result.classification, 'NOT_PUBLISHABLE')
})

test('a debug-signed release variant with the QA app id passes as a NOT PUBLISHABLE QA artifact', () => {
  const result = classifyApkClass({
    debuggable: false,
    signerSubjects: [DEBUG_CERT],
    applicationId: QA_APP_ID,
    qaSuffix: QA_SUFFIX,
    productionIntent: false
  })
  assert.equal(result.status, 'PASS')
  assert.equal(result.classification, 'LOCAL_QA_DEBUG_SIGNED_NOT_PUBLISHABLE')
  assert.equal(result.stamp, 'QA ARTIFACT — DEBUG-SIGNED, NOT PUBLISHABLE')
  assert.deepEqual(result.failures, [])
})

test('a production-intent run refuses an unsigned APK', () => {
  const result = classifyApkClass({
    debuggable: false,
    signerSubjects: [],
    applicationId: QA_APP_ID,
    qaSuffix: QA_SUFFIX,
    productionIntent: true
  })
  assert.equal(result.status, 'FAIL')
  assert.match(result.failures.join(' | '), /not signed/)
})

test('production intent refuses the debug certificate and accepts the production certificate', () => {
  const debugSigned = classifyApkClass({
    debuggable: false,
    signerSubjects: [DEBUG_CERT],
    applicationId: QA_APP_ID,
    qaSuffix: QA_SUFFIX,
    productionIntent: true
  })
  assert.equal(debugSigned.status, 'FAIL')
  assert.match(debugSigned.failures.join(' | '), /production keystore/)
  const production = classifyApkClass({
    debuggable: false,
    signerSubjects: [RELEASE_CERT],
    applicationId: QA_APP_ID,
    qaSuffix: QA_SUFFIX,
    productionIntent: true
  })
  assert.equal(production.status, 'PASS')
  assert.equal(production.classification, 'PRODUCTION_SIGNED')
})

test('a debug-signed APK whose app id does not carry the QA suffix is refused', () => {
  const result = classifyApkClass({
    debuggable: false,
    signerSubjects: [DEBUG_CERT],
    applicationId: 'com.framemark.native',
    qaSuffix: QA_SUFFIX,
    productionIntent: false
  })
  assert.equal(result.status, 'FAIL')
  assert.match(result.failures.join(' | '), /does not carry the QA suffix/)
})

// ---------------------------------------------------------------------------
// WP-RELEASE: the QA-surface check cannot be defeated by moving a class to
// another source set or renaming its package.
// ---------------------------------------------------------------------------

test('QA surface sources are all under desktopMain today', () => {
  const rows = findQaSurfaceSources()
  assert.ok(rows.length >= 11, `expected the verify/qaenv sources, found ${rows.length}`)
  assert.deepEqual(rows.filter((row) => row.sourceSet !== 'desktopMain'), [])
  const surface = verifyPackagingSurface()
  assert.equal(surface.status, 'PASS')
  assert.ok(surface.checks.some((check) => check.id === 'qa-surface-source-sets' && check.ok))
})

test('packaged classes that name the QA surface are offenders, product classes are not', () => {
  const offenders = qaSurfaceClassOffenders([
    'com/framemark/native/verify/P2bVerifyKt.class',
    'com/framemark/native/qaenv/EnvQaSurface.class',
    'com/framemark/native/devtools/Probe.class',
    'com/framemark/native/shell/DesktopShellKt.class',
    'com/framemark/native/product/ProjectCodec.class'
  ])
  assert.deepEqual(offenders, [
    'com/framemark/native/verify/P2bVerifyKt.class',
    'com/framemark/native/qaenv/EnvQaSurface.class',
    'com/framemark/native/devtools/Probe.class'
  ])
})
