# FrameMark release gate C (desktop): capture the shipped Compose desktop UI
# across pages, palettes, light/dark, window sizes and design font scales.
#
# Every launch is isolated in execution-ui-v2-20261106/sandbox/release-gate:
# appearance.json and project.json are seeded before the JVM starts, so the
# palette/mode/font-scale state is deterministic and the real user profile is
# never touched.  tools\run-desktop.ps1 launches, screenshots the window and
# kills the app; this script only sequences scenarios and collects pass/fail.
#
#   pwsh -NoProfile -File desktop-matrix.ps1 [-Out DIR] [-RuntimeRoot DIR] [-Only name]
param(
    [string] $Out = 'E:\FrameMark\qa\release-gate-20261117\ui\desktop',
    [string] $RuntimeRoot = '',
    [string] $Only = ''
)
$ErrorActionPreference = 'Stop'
$exRoot = 'E:\FrameMark\execution-ui-v2-20261106'
$runDesktop = Join-Path $exRoot 'tools\run-desktop.ps1'
$run = 'release-gate'
$sandbox = Join-Path $exRoot ('sandbox\' + $run)
$dataDir = Join-Path $sandbox 'data'
$settingsDir = Join-Path $dataDir 'settings'
New-Item -ItemType Directory -Force -Path $Out, $settingsDir | Out-Null

if (-not $RuntimeRoot) {
    $runs = Get-ChildItem -LiteralPath (Join-Path $exRoot 'build\runs') -Directory |
        Sort-Object LastWriteTime -Descending
    foreach ($dir in $runs) {
        $candidate = Join-Path $dir.FullName 'artifacts\desktop-runtime'
        if (Test-Path -LiteralPath (Join-Path $candidate 'bridge\desktop\cards-175.json')) {
            $RuntimeRoot = $candidate
            break
        }
    }
}
if (-not $RuntimeRoot) { throw 'no desktop runtime found' }
Write-Host "[desktop-matrix] runtime = $RuntimeRoot"

$photo = Join-Path $RuntimeRoot 'data\photo.png'
if (-not (Test-Path -LiteralPath $photo)) { throw "missing sample photo: $photo" }
$photoJson = $photo.Replace('\', '/')

function Write-Project {
    param([string] $CardId, [double] $FontScale = 0)
    $design = 'null'
    if ($CardId) {
        $fs = if ($FontScale -gt 0) { ',"fontScale":' + $FontScale.ToString([System.Globalization.CultureInfo]::InvariantCulture) } else { '' }
        $design = '{"cardId":"' + $CardId + '","styleId":null' + $fs + ',"baseDesign":false,"params":{},"text":{},"overlay":null}'
    }
    $designs = if ($design -eq 'null') { '{}' } else { '{"wp0-photo-1":' + $design + '}' }
    $doc = '{"document":"framemark.project","schemaVersion":1,' +
        '"id":"release-gate",' +
        '"activePhotoId":"wp0-photo-1",' +
        '"selectedCardId":' + $(if ($CardId) { '"' + $CardId + '"' } else { 'null' }) + ',' +
        '"createdAt":0,"updatedAt":0,' +
        '"photos":[{"id":"wp0-photo-1","path":"' + $photoJson + '","displayName":"photo.png",' +
        '"orientation":1,"widthPx":2667,"heightPx":2000}],' +
        '"designs":' + $designs + ',' +
        '"sharedDesign":null}'
    Set-Content -LiteralPath (Join-Path $dataDir 'project.json') -Value $doc -Encoding utf8NoBOM
}

function Write-Appearance {
    param([string] $Palette, [string] $Mode)
    $doc = '{"version":2,"palette":"' + $Palette + '","mode":"' + $Mode + '","stageTone":"dark"}'
    Set-Content -LiteralPath (Join-Path $settingsDir 'appearance.json') -Value $doc -Encoding utf8NoBOM
}

$card = 'stock-oppo-hassel_text_style_2'

# The release build writes a classpath that points at its own QA-free classes;
# a stale dump would launch a build without QaSurface hooks (FM_WINDOW and the
# capture env vars silently do nothing). Force the dev classpath once.
$cpFile = 'E:\FrameMark\work\ui-v2\ui\composeApp\build\desktop-classpath.txt'
if (Test-Path -LiteralPath $cpFile) { Remove-Item -LiteralPath $cpFile -Force }

# name, window, palette, mode, card, fontScale, env
$scenarios = @(
    @{ n = 'library-light';        w = '1440x900';  p = 'forest';   m = 'light'; c = '';     f = 0;   e = @{} },
    @{ n = 'library-dark';         w = '1440x900';  p = 'forest';   m = 'dark';  c = '';     f = 0;   e = @{} },
    @{ n = 'editor-style-light';   w = '1440x900';  p = 'forest';   m = 'light'; c = $card; f = 0;   e = @{ FM_OPEN_TAB = 'style' } },
    @{ n = 'editor-style-dark';    w = '1440x900';  p = 'forest';   m = 'dark';  c = $card; f = 0;   e = @{ FM_OPEN_TAB = 'style' } },
    @{ n = 'editor-text-light';    w = '1440x900';  p = 'forest';   m = 'light'; c = $card; f = 0;   e = @{ FM_OPEN_TAB = 'text' } },
    @{ n = 'editor-text-dark';     w = '1440x900';  p = 'forest';   m = 'dark';  c = $card; f = 0;   e = @{ FM_OPEN_TAB = 'text' } },
    @{ n = 'editor-logo-light';    w = '1440x900';  p = 'forest';   m = 'light'; c = $card; f = 0;   e = @{ FM_OPEN_TAB = 'logo' } },
    @{ n = 'editor-logo-dark';     w = '1440x900';  p = 'forest';   m = 'dark';  c = $card; f = 0;   e = @{ FM_OPEN_TAB = 'logo' } },
    @{ n = 'editor-water-light';   w = '1440x900';  p = 'forest';   m = 'light'; c = $card; f = 0;   e = @{ FM_OPEN_TAB = 'watermark' } },
    @{ n = 'editor-water-dark';    w = '1440x900';  p = 'forest';   m = 'dark';  c = $card; f = 0;   e = @{ FM_OPEN_TAB = 'watermark' } },
    @{ n = 'sheet-presets-light';  w = '1440x900';  p = 'forest';   m = 'light'; c = $card; f = 0;   e = @{ FM_OPEN_PRESETS = '1' } },
    @{ n = 'sheet-presets-dark';   w = '1440x900';  p = 'forest';   m = 'dark';  c = $card; f = 0;   e = @{ FM_OPEN_PRESETS = '1' } },
    @{ n = 'sheet-export-light';   w = '1440x900';  p = 'forest';   m = 'light'; c = $card; f = 0;   e = @{ FM_OPEN_EXPORT = '1' } },
    @{ n = 'sheet-export-dark';    w = '1440x900';  p = 'forest';   m = 'dark';  c = $card; f = 0;   e = @{ FM_OPEN_EXPORT = '1' } },
    @{ n = 'batch-light';          w = '1440x900';  p = 'forest';   m = 'light'; c = $card; f = 0;   e = @{ FM_OPEN_BATCH = '1'; FM_BATCH_COUNT = '4' } },
    @{ n = 'batch-dark';           w = '1440x900';  p = 'forest';   m = 'dark';  c = $card; f = 0;   e = @{ FM_OPEN_BATCH = '1'; FM_BATCH_COUNT = '4' } },
    @{ n = 'palette-graphite-light'; w = '1440x900'; p = 'graphite'; m = 'light'; c = $card; f = 0;  e = @{ FM_OPEN_TAB = 'style' } },
    @{ n = 'palette-graphite-dark';  w = '1440x900'; p = 'graphite'; m = 'dark';  c = $card; f = 0;  e = @{ FM_OPEN_TAB = 'style' } },
    @{ n = 'palette-indigo-light';   w = '1440x900'; p = 'indigo';   m = 'light'; c = $card; f = 0;  e = @{ FM_OPEN_TAB = 'style' } },
    @{ n = 'palette-indigo-dark';    w = '1440x900'; p = 'indigo';   m = 'dark';  c = $card; f = 0;  e = @{ FM_OPEN_TAB = 'style' } },
    @{ n = 'palette-clay-light';     w = '1440x900'; p = 'clay';     m = 'light'; c = $card; f = 0;  e = @{ FM_OPEN_TAB = 'style' } },
    @{ n = 'palette-clay-dark';      w = '1440x900'; p = 'clay';     m = 'dark';  c = $card; f = 0;  e = @{ FM_OPEN_TAB = 'style' } },
    @{ n = 'palette-classic-light';  w = '1440x900'; p = 'classic';  m = 'light'; c = $card; f = 0;  e = @{ FM_OPEN_TAB = 'style' } },
    @{ n = 'palette-classic-dark';   w = '1440x900'; p = 'classic';  m = 'dark';  c = $card; f = 0;  e = @{ FM_OPEN_TAB = 'style' } },
    @{ n = 'size-narrow-light';      w = '1120x780'; p = 'forest';   m = 'light'; c = $card; f = 0;  e = @{ FM_OPEN_TAB = 'style' } },
    @{ n = 'size-narrow-dark';       w = '1120x780'; p = 'forest';   m = 'dark';  c = $card; f = 0;  e = @{ FM_OPEN_TAB = 'style' } },
    @{ n = 'size-wide-light';        w = '1920x1080'; p = 'forest';  m = 'light'; c = $card; f = 0;  e = @{ FM_OPEN_TAB = 'style' } },
    @{ n = 'size-wide-dark';         w = '1920x1080'; p = 'forest';  m = 'dark';  c = $card; f = 0;  e = @{ FM_OPEN_TAB = 'style' } },
    @{ n = 'size-small-light';       w = '1024x700'; p = 'forest';   m = 'light'; c = '';     f = 0;  e = @{} },
    @{ n = 'fontscale-1.3';          w = '1440x900'; p = 'forest';   m = 'light'; c = 'blur-classic'; f = 1.3; e = @{ FM_OPEN_TAB = 'text' } },
    @{ n = 'fontscale-1.5';          w = '1440x900'; p = 'forest';   m = 'light'; c = 'blur-classic'; f = 1.5; e = @{ FM_OPEN_TAB = 'text' } },
    @{ n = 'fontscale-2.0';          w = '1440x900'; p = 'forest';   m = 'light'; c = 'blur-classic'; f = 2.0; e = @{ FM_OPEN_TAB = 'text' } }
)

$results = @()
foreach ($s in $scenarios) {
    if ($Only -and $s.n -notlike "*$Only*") { continue }
    Write-Host "[desktop-matrix] $($s.n) $($s.w) $($s.p)/$($s.m) card=$($s.c) fontScale=$($s.f)"
    Write-Appearance -Palette $s.p -Mode $s.m
    Write-Project -CardId $s.c -FontScale $s.f
    $shot = Join-Path $Out ($s.n + '.png')
    $log = Join-Path $Out ($s.n + '.log')
    $envArgs = @{}
    foreach ($k in $s.e.Keys) { $envArgs[$k] = $s.e[$k] }
    # run-desktop.ps1 writes the child environment into this process, so a hook
    # from the previous scenario (FM_OPEN_PRESETS, FM_OPEN_BATCH, ...) would leak
    # into the next launch. Clear every FM_* variable first.
    Get-ChildItem env: | Where-Object { $_.Name -like 'FM_*' } | ForEach-Object {
        Remove-Item -LiteralPath ('env:' + $_.Name) -ErrorAction SilentlyContinue
    }
    try {
        $info = & $runDesktop -Run $run -RuntimeRoot $RuntimeRoot -Window $s.w -Shot $shot -ShotDelaySec 6 -Env $envArgs 2>&1
        $info | Out-File -LiteralPath $log -Encoding utf8
        $ok = (Test-Path -LiteralPath $shot) -and ((Get-Item -LiteralPath $shot).Length -gt 4096)
        $results += [pscustomobject]@{ name = $s.n; palette = $s.p; mode = $s.m; window = $s.w; shot = $shot; ok = $ok }
    } catch {
        $_ | Out-File -LiteralPath $log -Encoding utf8
        $results += [pscustomobject]@{ name = $s.n; palette = $s.p; mode = $s.m; window = $s.w; shot = $shot; ok = $false; error = "$_" }
        Write-Warning "[desktop-matrix] $($s.n) failed: $_"
    }
}

$results | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $Out 'desktop-matrix.json') -Encoding utf8
$okCount = @($results | Where-Object ok).Count
Write-Host "[desktop-matrix] $okCount/$($results.Count) captures ok -> $Out"
if ($okCount -ne $results.Count) { exit 2 }
