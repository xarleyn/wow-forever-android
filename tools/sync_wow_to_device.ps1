param()

$ErrorActionPreference = 'Stop'

# Resolve source/destination roots (env vars first, then positional args, then defaults).
$srcRoot = $env:WOW_SRC
if (-not $srcRoot) {
    if ($args.Count -gt 0) { $srcRoot = $args[0] } else { $srcRoot = 'C:\Program Files (x86)\World of Warcraft' }
}
$dstRoot = $env:WOW_DST
if (-not $dstRoot) {
    if ($args.Count -gt 1) { $dstRoot = $args[1] } else { $dstRoot = '/storage/emulated/0/WoW Forever' }
}

$repoDir = Split-Path -Parent $PSScriptRoot
$adbExe = Join-Path $PSScriptRoot 'adb.exe'
if (-not (Test-Path $adbExe)) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $adbExe = $cmd.Source } else { throw "adb.exe not found in $PSScriptRoot and not on PATH" }
}

# Locate python.
$python = $null
foreach ($name in 'python3', 'python') {
    $c = Get-Command $name -ErrorAction SilentlyContinue
    if ($c) { $python = $c.Source; break }
}
if (-not $python) { throw "python3/python not found on PATH" }

# Temp work dir with cleanup.
$work = Join-Path ([System.IO.Path]::GetTempPath()) ("wow-sync-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work | Out-Null
try {
    # Verify adb.
    & $adbExe get-state 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "adb get-state failed; is a device connected?" }

    function Sort-Ordinal($items) {
        if ($null -eq $items) { return @() }
        $arr = @($items | Where-Object { $null -ne $_ })
        if ($arr.Count -gt 0) { [Array]::Sort($arr, [System.StringComparer]::Ordinal) }
        return $arr
    }

    function Get-LocalList {
        $dataDir = Join-Path $srcRoot 'Data'
        if (-not (Test-Path $dataDir)) { throw "source Data folder not found: $dataDir" }
        $files = Get-ChildItem -Path $dataDir -Recurse -File | Where-Object { $_.Name -ne 'shmem' }
        $lines = foreach ($fi in $files) {
            $rel = $fi.FullName.Substring($dataDir.Length).TrimStart('\', '/') -replace '\\', '/'
            "$($fi.Length) $rel"
        }
        return Sort-Ordinal $lines
    }

    function Get-DeviceList {
        $shellCmd = "cd '$dstRoot/Data' 2>/dev/null && find . -type f ! -name shmem -exec stat -c '%s %n' {} +"
        $output = & $adbExe shell $shellCmd 2>$null
        $lines = @()
        foreach ($raw in $output) {
            $line = ($raw -replace '\r', '')
            if ($line -match '^\s*(\d+)\s+\./(.+)$') {
                $lines += "$($Matches[1]) $($Matches[2])"
            } elseif ($line -match '^\s*(\d+)\s+(.+)$') {
                $lines += "$($Matches[1]) $($Matches[2])"
            }
        }
        return Sort-Ordinal $lines
    }

    function Get-Names($lines) {
        $names = foreach ($l in $lines) {
            $idx = $l.IndexOf(' ')
            if ($idx -ge 0) { $l.Substring($idx + 1) } else { $l }
        }
        return Sort-Ordinal $names
    }

    function Comm-LeftOnly($left, $right) {
        # lines present in left but not in right
        $l = @($left); $r = @($right)
        $i = 0; $j = 0; $out = @()
        while ($i -lt $l.Count -and $j -lt $r.Count) {
            $cmp = [string]::Compare($l[$i], $r[$j], [System.StringComparison]::Ordinal)
            if ($cmp -eq 0) { $i++; $j++ }
            elseif ($cmp -lt 0) { $out += $l[$i]; $i++ }
            else { $j++ }
        }
        while ($i -lt $l.Count) { $out += $l[$i]; $i++ }
        return $out
    }

    function Comm-RightOnly($left, $right) {
        # lines present in right but not in left
        $l = @($left); $r = @($right)
        $i = 0; $j = 0; $out = @()
        while ($i -lt $l.Count -and $j -lt $r.Count) {
            $cmp = [string]::Compare($l[$i], $r[$j], [System.StringComparison]::Ordinal)
            if ($cmp -eq 0) { $i++; $j++ }
            elseif ($cmp -lt 0) { $i++ }
            else { $out += $r[$j]; $j++ }
        }
        while ($j -lt $r.Count) { $out += $r[$j]; $j++ }
        return $out
    }

    $localList = Get-LocalList
    $deviceList = Get-DeviceList

    $pushLines = Comm-LeftOnly $localList $deviceList
    $pushFiles = foreach ($l in $pushLines) {
        $idx = $l.IndexOf(' ')
        if ($idx -ge 0) { $l.Substring($idx + 1) } else { $l }
    }

    $localNames = Get-Names $localList
    $deviceNames = Get-Names $deviceList
    $deleteFiles = Comm-RightOnly $localNames $deviceNames

    Write-Host "Deleting $($deleteFiles.Count) stale files, pushing $($pushFiles.Count) changed files"

    foreach ($f in $deleteFiles) {
        $rmOut = & $adbExe shell -n rm -f "'$dstRoot/Data/$f'" 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "adb rm returned $LASTEXITCODE for $f : $rmOut"
        }
    }

    foreach ($f in $pushFiles) {
        $lastSlash = $f.LastIndexOf('/')
        $dir = if ($lastSlash -ge 0) { $f.Substring(0, $lastSlash) } else { '' }
        if ($dir -and $dir -ne '.') {
            $mkOut = & $adbExe shell -n mkdir -p "'$dstRoot/Data/$dir'" 2>&1
            if ($LASTEXITCODE -ne 0) {
                Write-Warning "adb mkdir returned $LASTEXITCODE for $dir : $mkOut"
            }
        }
        $localFile = Join-Path (Join-Path $srcRoot 'Data') ($f -replace '/', '\')
        $remoteFile = "$dstRoot/Data/$f"
        $pushOut = & $adbExe push "$localFile" "$remoteFile" 2>&1
        if ($LASTEXITCODE -ne 0) { throw "adb push failed for $f : $pushOut" }
        Write-Host "pushed $f"
    }

    $localBuild = ''
    $localBuildFile = Join-Path $srcRoot '.build.info'
    if (Test-Path $localBuildFile) {
        $localBuild = (Get-Content $localBuildFile -Raw).TrimEnd("`r", "`n")
    }
    $deviceBuild = ''
    try {
        $raw = & $adbExe shell "cat '$dstRoot/.build.info'" 2>$null
        $deviceBuild = ($raw -join "`n") -replace '\r', ''
        $deviceBuild = $deviceBuild.TrimEnd("`n")
    } catch { $deviceBuild = '' }

    & $adbExe shell -n mkdir -p "'$dstRoot/_classic_beta_'" 2>$null | Out-Null
    & $adbExe push "$srcRoot\.build.info" "$dstRoot/.build.info" 2>$null | Out-Null
    if (Test-Path (Join-Path $srcRoot '_classic_beta_\.flavor.info')) {
        & $adbExe push "$srcRoot\_classic_beta_\.flavor.info" "$dstRoot/_classic_beta_/.flavor.info" 2>$null | Out-Null
    }

    $forceExe = $env:FORCE_EXE -eq '1'
    if ($localBuild -ne $deviceBuild -or $forceExe) {
        $env:WOW_DIR = $srcRoot
        $exeDir = Join-Path $work 'exe'
        New-Item -ItemType Directory -Path $exeDir -Force | Out-Null
        & $python (Join-Path $repoDir 'tools\download_wow_arm64.py') $exeDir
        if ($LASTEXITCODE -ne 0) { throw "download_wow_arm64.py failed" }
        & $adbExe shell -n mkdir -p "'$dstRoot/_classic_beta_'" 2>$null | Out-Null
        $exePushOut = & $adbExe push (Join-Path $exeDir 'Wow-ARM64.exe') "$dstRoot/_classic_beta_/WowB-ARM64.exe" 2>&1
        if ($LASTEXITCODE -ne 0) { throw "adb push Wow-ARM64.exe -> WowB-ARM64.exe failed: $exePushOut" }
    } else {
        Write-Host "Build unchanged, keeping WowB-ARM64.exe"
    }

    $deviceList2 = Get-DeviceList
    $equal = $localList.Count -eq $deviceList2.Count
    if ($equal) {
        for ($i = 0; $i -lt $localList.Count; $i++) {
            if ($localList[$i] -ne $deviceList2[$i]) { $equal = $false; break }
        }
    }
    if ($equal) {
        Write-Host "Device Data matches $srcRoot\Data"
    } else {
        Write-Error "Device Data still differs from $srcRoot\Data"
        exit 1
    }
} finally {
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}
