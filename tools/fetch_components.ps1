param()

$ErrorActionPreference = 'Stop'

$repoDir = Split-Path -Parent $PSScriptRoot
$dest = Join-Path $repoDir 'app\src\main\assets\bundled_components'
# GitHub forks do not inherit release assets; these pinned runtimes live upstream.
$releaseUrl = if ($env:COMPONENTS_URL) { $env:COMPONENTS_URL } else { 'https://github.com/jaredgei/wow-forever-android/releases/download/components-v1' }

$files = @(
    'proton-11.0-90624-arm64ec.wcp',
    'dxvk-2.4.1-wow-aarch64-test.wcp',
    'turnip-wow-scheduler-test.zip',
    'turnip-V32-RP6sched.zip'
)

New-Item -ItemType Directory -Path $dest -Force | Out-Null

$curl = Get-Command curl.exe -ErrorAction SilentlyContinue
if (-not $curl) { throw "curl.exe not found on PATH" }

foreach ($f in $files) {
    $destFile = Join-Path $dest $f
    if ((Test-Path $destFile) -and (Get-Item $destFile).Length -gt 0) {
        Write-Host "have $f"
    } elseif ($args.Count -gt 0 -and $args[0] -and (Test-Path (Join-Path $args[0] $f)) -and (Get-Item (Join-Path $args[0] $f)).Length -gt 0) {
        Copy-Item (Join-Path $args[0] $f) $destFile -Force
        Write-Host "copied $f"
    } else {
        if ($f -eq 'turnip-V32-RP6sched.zip') {
            $url = 'https://raw.githubusercontent.com/arusiasotto/wow-forever-a840/main/driver/turnip-V32-RP6sched.zip'
        } else {
            $url = "$releaseUrl/$f"
        }
        $part = "$destFile.part"
        & $curl.Source -fL --retry 3 -o $part $url
        if ($LASTEXITCODE -ne 0) { throw "download failed for $f" }
        Move-Item -Force $part $destFile
        Write-Host "downloaded $f"
    }
}

# Verify checksums.
$shaFile = Join-Path $repoDir 'tools\components.sha256'
foreach ($line in (Get-Content $shaFile)) {
    $parts = $line -split '\s+'
    if ($parts.Count -ge 2) {
        $expected = $parts[0].ToLower()
        $name = $parts[1]
        $file = Join-Path $dest $name
        if (-not (Test-Path $file)) { throw "missing $name" }
        $actual = (Get-FileHash $file -Algorithm SHA256).Hash.ToLower()
        if ($actual -ne $expected) { throw "checksum mismatch for $name" }
        Write-Host "OK $name"
    }
}
