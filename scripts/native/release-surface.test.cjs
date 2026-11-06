'use strict'

// Release-surface guard tests. They exercise checkReleaseSurface() directly so
// the structural rules are verified without reading or modifying the app.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const { checkReleaseSurface } = require('./build.cjs')

const ROOT = path.resolve(__dirname, '../..')
const SOURCE_PATH = path.join(ROOT, 'ui/composeApp/src/androidMain/kotlin/com/framemark/native/MainActivity.kt')
const MANIFEST_PATH = path.join(ROOT, 'ui/composeApp/src/androidMain/AndroidManifest.xml')
const realSource = fs.readFileSync(SOURCE_PATH, 'utf8')
const realManifest = fs.readFileSync(MANIFEST_PATH, 'utf8')

const GUARD_OPEN = '        if (BuildConfig.DEBUG) {'
const QA_HOOK = '        LaunchOptions.openCardId = intent?.getStringExtra("fmCard")?.takeIf { it.isNotBlank() }'
const CLOSE = '        }'
const MULTI_LINE_CONTENT = ['        setContent {', '            FrameMarkApp()', '        }']
const SINGLE_LINE_CONTENT = ['        setContent { FrameMarkApp() }']

function activity (body) {
  return ['package com.framemark.native', '', 'class MainActivity {', '    fun onCreate() {', ...body, '    }', '}', ''].join('\n')
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
