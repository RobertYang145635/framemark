'use strict'

// Build a QA app-image without installing or launching any desktop UI.
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const { spawnSync } = require('node:child_process')
const root = path.resolve(__dirname, '../..')
const config = JSON.parse(fs.readFileSync(path.join(__dirname, 'toolchain.json'), 'utf8'))
const artifactRoot = path.resolve(root, process.env.FM_BUILD_ARTIFACT_ROOT || config.artifactDirectory)
const args = {}
for (let i = 2; i < process.argv.length; i += 2) {
  const key = process.argv[i]
  if (!key.startsWith('--') || !process.argv[i + 1]) throw new Error('Arguments must be --name value pairs')
  args[key.slice(2)] = process.argv[i + 1]
}
for (const key of ['runtime', 'out', 'classpath', 'app-jar']) if (!args[key]) throw new Error(`Missing --${key}`)
const out = path.resolve(args.out)
const runtime = path.resolve(args.runtime)
const relative = path.relative(artifactRoot, out)
if (!relative || relative === '..' || relative.startsWith(`..${path.sep}`) || path.isAbsolute(relative)) throw new Error('QA output must stay under the configured artifact root')
if (fs.existsSync(out)) throw new Error('Refusing to replace an existing QA package directory')
const javaHome = process.env.FM_JAVA_HOME || process.env.JAVA_HOME
if (!javaHome) throw new Error('Set FM_JAVA_HOME or JAVA_HOME')
const release = fs.readFileSync(path.join(javaHome, 'release'), 'utf8')
if (!release.includes(`JAVA_VERSION="${config.versions.jdk}"`)) throw new Error('JDK version does not match the build pin')
const sha = filename => crypto.createHash('sha256').update(fs.readFileSync(filename)).digest('hex')
function run (exe, argv, log) {
  const result = spawnSync(exe, argv, { cwd: root, encoding: 'utf8', windowsHide: true, timeout: 300000 })
  fs.writeFileSync(path.join(out, log), `${result.stdout || ''}\n${result.stderr || ''}`, 'utf8')
  if (result.status !== 0) throw new Error(`${path.basename(exe)} failed: ${result.error?.message || result.stderr || result.stdout}`)
}
fs.mkdirSync(out, { recursive: true })
const input = path.join(out, 'input')
fs.mkdirSync(input)
const sources = [path.resolve(args['app-jar']), ...fs.readFileSync(args.classpath, 'utf8').trim().split(path.delimiter).filter(filename => filename.endsWith('.jar'))]
const jars = []
for (const source of sources) {
  let name = path.basename(source)
  let target = path.join(input, name)
  if (fs.existsSync(target)) {
    if (sha(target) === sha(source)) continue
    // Compose publishes a small forwarding jar and its AndroidX implementation
    // with the same basename. Preserve both instead of overwriting either one.
    name = path.basename(source, '.jar') + '-' + sha(source).slice(0, 12) + '.jar'
    target = path.join(input, name)
    if (fs.existsSync(target)) throw new Error(`Conflicting disambiguated classpath jar: ${name}`)
  }
  fs.copyFileSync(source, target)
  jars.push({ name, sha256: sha(source) })
}
// The previous verified runtime's modules plus management/Unsafe/accessibility,
// which the native UI and its runtime diagnostics actually use.
const modules = ['java.base', 'java.datatransfer', 'java.xml', 'java.prefs', 'java.desktop', 'java.logging', 'jdk.crypto.ec', 'java.management', 'jdk.unsupported', 'jdk.accessibility']
const jre = path.join(out, 'jre')
run(path.join(javaHome, 'bin/jlink.exe'), ['--module-path', path.join(javaHome, 'jmods'), '--add-modules', modules.join(','), '--output', jre, '--strip-debug', '--no-header-files', '--no-man-pages'], 'jlink.log')
const name = 'FrameMark-QA-20261102'
const destination = path.join(out, 'image')
run(path.join(javaHome, 'bin/jpackage.exe'), ['--type', 'app-image', '--name', name,
  '--dest', destination, '--input', input, '--main-jar', path.basename(args['app-jar']),
  '--main-class', 'com.framemark.native.MainKt', '--runtime-image', jre,
  '--app-version', '0.9.0', '--vendor', 'FrameMark', '--java-options', '-Dfile.encoding=UTF-8',
  '--java-options', '-Dframemark.build.label=20261102-QA'], 'jpackage.log')
const image = path.join(destination, name)
for (const part of ['bin', 'data', 'bridge']) {
  const source = path.join(runtime, part)
  if (!fs.statSync(source).isDirectory()) throw new Error(`Missing runtime part ${part}`)
  fs.cpSync(source, path.join(image, part), { recursive: true, errorOnExist: true, force: false })
}
const cfg = path.join(image, 'app', name + '.cfg')
let cfgText = fs.readFileSync(cfg, 'utf8')
// Match Gradle's classpath ordering, including disambiguated equal-name jars.
cfgText = cfgText.replace(/^app\.classpath=.*(?:\r?\n)?/gm, '')
cfgText = cfgText.replace('[Application]', '[Application]\n' + jars.map(jar => 'app.classpath=$APPDIR\\' + jar.name).join('\n'))
fs.writeFileSync(cfg, cfgText, 'utf8')
if (/[A-Za-z]:[\\/]|_research/.test(cfgText)) throw new Error('Launcher config contains a build-machine path')
const rows = []
function inventory (dir) {
  for (const item of fs.readdirSync(dir, { withFileTypes: true })) {
    const filename = path.join(dir, item.name)
    if (item.isSymbolicLink()) throw new Error('Packaged files may not be symlinks')
    if (item.isDirectory()) inventory(filename)
    else rows.push({ path: path.relative(image, filename).replaceAll(path.sep, '/'), bytes: fs.statSync(filename).size, sha256: sha(filename) })
  }
}
inventory(image)
const report = { schema: 'framemark-portable-qa/v1', classification: 'QA app-image; not installed, not launched, not a release approval', image, javaVersion: config.versions.jdk, modules, jars, files: rows, launcherSha256: sha(path.join(image, name + '.exe')) }
fs.writeFileSync(path.join(out, 'package-provenance.json'), JSON.stringify(report, null, 2) + '\n')
console.log(JSON.stringify({ image, files: rows.length, launcherSha256: report.launcherSha256 }))
