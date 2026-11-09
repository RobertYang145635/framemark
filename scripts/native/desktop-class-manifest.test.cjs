'use strict'

// WP-FIX1 gate tests: the desktop class manifest and the "desktop compile was not
// served from the Gradle build cache" assertion. Both gates are pure functions
// over a directory / a log file, so they are tested here without a build.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const test = require('node:test')

const {
  DESKTOP_REQUIRED_CLASSES,
  verifyDesktopClassManifest,
  verifyDesktopCompileNotFromCache
} = require('./build.cjs')

function tempDir (prefix) {
  return fs.mkdtempSync(path.join(os.tmpdir(), prefix))
}

function writeClasses (root, relativePaths) {
  for (const relative of relativePaths) {
    const filename = path.join(root, ...relative.split('/'))
    fs.mkdirSync(path.dirname(filename), { recursive: true })
    fs.writeFileSync(filename, Buffer.from([0xCA, 0xFE, 0xBA, 0xBE]))
  }
}

test('the required class list covers the four classes named by the brief', () => {
  // theme/FmRhythm, inspector/TextSlotNavigatorKt, inspector/TextSlotAxis and the
  // shell entry. The brief wrote `shell\desktop\DesktopShellKt.class`; the file is
  // `commonMain/.../shell/DesktopShell.kt`, so the class is `shell/DesktopShellKt`.
  for (const required of [
    'com/framemark/native/theme/FmRhythm.class',
    'com/framemark/native/inspector/TextSlotNavigatorKt.class',
    'com/framemark/native/inspector/TextSlotAxis.class',
    'com/framemark/native/shell/DesktopShellKt.class'
  ]) {
    assert.ok(DESKTOP_REQUIRED_CLASSES.includes(required), `missing from the gate: ${required}`)
  }
})

test('a complete class root passes', () => {
  const root = tempDir('fm-class-manifest-pass-')
  writeClasses(root, DESKTOP_REQUIRED_CLASSES)
  const result = verifyDesktopClassManifest(root)
  assert.equal(result.status, 'PASS')
  assert.deepEqual(result.missing, [])
  assert.equal(result.classCount, DESKTOP_REQUIRED_CLASSES.length)
  assert.equal(result.required, DESKTOP_REQUIRED_CLASSES.length)
  assert.ok(result.rows.every((row) => row.status === 'PASS' && row.bytes > 0))
})

test('one deleted class fails and names the file', () => {
  const root = tempDir('fm-class-manifest-missing-')
  writeClasses(root, DESKTOP_REQUIRED_CLASSES)
  const victim = 'com/framemark/native/inspector/TextSlotAxis.class'
  fs.rmSync(path.join(root, ...victim.split('/')))
  const result = verifyDesktopClassManifest(root)
  assert.equal(result.status, 'FAIL')
  assert.deepEqual(result.missing, [victim])
  assert.equal(result.classCount, DESKTOP_REQUIRED_CLASSES.length - 1)
})

test('an empty or absent class root fails instead of vacuously passing', () => {
  const empty = tempDir('fm-class-manifest-empty-')
  assert.equal(verifyDesktopClassManifest(empty).status, 'FAIL')
  assert.equal(verifyDesktopClassManifest(empty).classCount, 0)
  const absent = path.join(empty, 'does-not-exist')
  assert.equal(verifyDesktopClassManifest(absent).status, 'FAIL')
  assert.deepEqual(verifyDesktopClassManifest(absent).missing, DESKTOP_REQUIRED_CLASSES)
})

test('a directory named like the class does not count', () => {
  const root = tempDir('fm-class-manifest-dir-')
  writeClasses(root, DESKTOP_REQUIRED_CLASSES)
  const victim = 'com/framemark/native/inspector/TextSlotAxis.class'
  const filename = path.join(root, ...victim.split('/'))
  fs.rmSync(filename)
  fs.mkdirSync(filename, { recursive: true })
  assert.equal(verifyDesktopClassManifest(root).status, 'FAIL')
})

test('a compileKotlinDesktop FROM-CACHE line fails the cache gate', () => {
  const dir = tempDir('fm-cache-gate-')
  const log = path.join(dir, 'gradle.log')
  fs.writeFileSync(log, [
    '> Task :composeApp:compileKotlinDesktop FROM-CACHE',
    '> Task :composeApp:assembleDebug',
    'BUILD SUCCESSFUL in 3m 24s',
    ''
  ].join('\n'))
  const result = verifyDesktopCompileNotFromCache(log)
  assert.equal(result.status, 'FAIL')
  assert.deepEqual(result.fromCache, [':composeApp:compileKotlinDesktop FROM-CACHE'])
})

test('an executed compileKotlinDesktop passes the cache gate', () => {
  const dir = tempDir('fm-cache-gate-ok-')
  const log = path.join(dir, 'gradle.log')
  fs.writeFileSync(log, [
    '> Task :composeApp:compileKotlinDesktop',
    '> Task :composeApp:assembleDebug',
    'BUILD SUCCESSFUL in 5m 02s',
    ''
  ].join('\n'))
  const result = verifyDesktopCompileNotFromCache(log)
  assert.equal(result.status, 'PASS')
  assert.deepEqual(result.scheduled, [':composeApp:compileKotlinDesktop'])
})

test('the gate only inspects compileKotlinDesktop, not other cached tasks', () => {
  const dir = tempDir('fm-cache-gate-other-')
  const log = path.join(dir, 'gradle.log')
  fs.writeFileSync(log, [
    '> Task :composeApp:mergeDebugResources FROM-CACHE',
    '> Task :composeApp:compileKotlinDesktop',
    ''
  ].join('\n'))
  assert.equal(verifyDesktopCompileNotFromCache(log).status, 'PASS')
})

test('a missing gradle log is reported as absent', () => {
  const dir = tempDir('fm-cache-gate-missing-')
  const result = verifyDesktopCompileNotFromCache(path.join(dir, 'nope.log'))
  assert.equal(result.status, 'PASS')
  assert.equal(result.logPresent, false)
  assert.deepEqual(result.scheduled, [])
})
