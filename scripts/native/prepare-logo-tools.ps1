param(
    [Parameter(Mandatory = $false)]
    [string]$Destination = $env:FM_NATIVE_SHARP_PREFIX
)

$ErrorActionPreference = 'Stop'
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if ([string]::IsNullOrWhiteSpace($Destination)) {
    throw 'Pass -Destination on the project data drive or set FM_NATIVE_SHARP_PREFIX first.'
}
if (-not [System.IO.Path]::IsPathRooted($Destination)) {
    throw 'Destination must be an absolute path.'
}

$destinationPath = [System.IO.Path]::GetFullPath($Destination)
$repoPrefix = $repoRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
if ($destinationPath.StartsWith($repoPrefix, [System.StringComparison]::OrdinalIgnoreCase) -or
    $destinationPath.Equals($repoRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Install destination must be outside the source checkout.'
}
if (([System.IO.Path]::GetPathRoot($destinationPath)) -ne ([System.IO.Path]::GetPathRoot($repoRoot))) {
    throw 'Install destination must stay on the same drive as this checkout so native build scratch and tools remain on the configured project drive.'
}

$manifestSource = Join-Path $PSScriptRoot 'logo-tools/package.json'
$lockSource = Join-Path $PSScriptRoot 'logo-tools/package-lock.json'
$manifest = Get-Content -LiteralPath $manifestSource -Raw -Encoding UTF8 | ConvertFrom-Json
$lock = Get-Content -LiteralPath $lockSource -Raw -Encoding UTF8 | ConvertFrom-Json -AsHashtable
$repoManifest = Get-Content -LiteralPath (Join-Path $repoRoot 'package.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$repoLock = Get-Content -LiteralPath (Join-Path $repoRoot 'package-lock.json') -Raw -Encoding UTF8 | ConvertFrom-Json -AsHashtable
$lockedSharp = $lock['packages']['node_modules/sharp']
$repoLockedSharp = $repoLock['packages']['node_modules/sharp']
if ($manifest.dependencies.sharp -ne '0.35.5' -or $repoManifest.dependencies.sharp -ne $manifest.dependencies.sharp -or
    $lockedSharp.version -ne '0.35.5' -or $repoLockedSharp.version -ne $lockedSharp.version -or
    $repoLockedSharp.integrity -ne $lockedSharp.integrity -or $repoLockedSharp.resolved -ne $lockedSharp.resolved -or
    -not $lockedSharp.integrity -or $lockedSharp.resolved -notlike 'https://registry.npmjs.org/sharp/*') {
    throw 'The isolated sharp package and registry integrity lock are missing or inconsistent.'
}

New-Item -ItemType Directory -Path $destinationPath -Force | Out-Null
Copy-Item -LiteralPath $manifestSource -Destination (Join-Path $destinationPath 'package.json') -Force
Copy-Item -LiteralPath $lockSource -Destination (Join-Path $destinationPath 'package-lock.json') -Force
$cachePath = Join-Path $destinationPath '.npm-cache'
$npm = Get-Command 'npm.cmd' -ErrorAction SilentlyContinue
if (-not $npm) { throw 'npm.cmd was not found on PATH.' }
& $npm.Source 'ci' '--prefix' $destinationPath '--cache' $cachePath '--no-audit' '--no-fund'
if ($LASTEXITCODE -ne 0) { throw "Isolated npm ci failed with exit code $LASTEXITCODE." }

$installed = Get-Content -LiteralPath (Join-Path $destinationPath 'node_modules/sharp/package.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if ($installed.version -ne $lockedSharp.version) {
    throw "Installed sharp $($installed.version) does not match lock $($lockedSharp.version)."
}
$installedManifestHash = (Get-FileHash -LiteralPath (Join-Path $destinationPath 'node_modules/sharp/package.json') -Algorithm SHA256).Hash.ToLowerInvariant()
$manifestHash = (Get-FileHash -LiteralPath $manifestSource -Algorithm SHA256).Hash.ToLowerInvariant()
$lockHash = (Get-FileHash -LiteralPath $lockSource -Algorithm SHA256).Hash.ToLowerInvariant()
$installReport = [ordered]@{
    schema = 'framemark-native-logo-tools-install/v1'
    package = 'sharp'
    version = $installed.version
    source = $lockedSharp.resolved
    integrity = $lockedSharp.integrity
    packageManifestSha256 = $manifestHash
    packageLockSha256 = $lockHash
    repositoryPackageLockSha256 = (Get-FileHash -LiteralPath (Join-Path $repoRoot 'package-lock.json') -Algorithm SHA256).Hash.ToLowerInvariant()
    installedPackageJsonSha256 = $installedManifestHash
    destination = $destinationPath
    npmVersion = (& $npm.Source '--version').Trim()
}
$installReport | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $destinationPath 'framemark-logo-tools-install.json') -Encoding UTF8
$env:FM_NATIVE_SHARP_PREFIX = $destinationPath
$installReport | ConvertTo-Json -Depth 5
