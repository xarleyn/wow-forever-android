$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$vswhere = 'C:/Program Files (x86)/Microsoft Visual Studio/Installer/vswhere.exe'
$install = & $vswhere -latest -prerelease -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $install) { throw 'Visual C++ x86 build tools are required' }
$vc = Get-ChildItem -LiteralPath "$install/VC/Tools/MSVC" -Directory | Sort-Object Name -Descending | Select-Object -First 1
$sdk = 'C:/Program Files (x86)/Windows Kits/10'
$version = Get-ChildItem -LiteralPath "$sdk/Include" -Directory | Sort-Object Name -Descending | Select-Object -First 1
$out = Join-Path $repo 'app/src/main/assets/battlenet'
New-Item -ItemType Directory -Path $out -Force | Out-Null
$temp = Join-Path $repo 'app/build/battlenet-probe'
New-Item -ItemType Directory -Path $temp -Force | Out-Null
$compiler = Join-Path $vc.FullName 'bin/Hostx64/x86/cl.exe'
& $compiler /nologo /O1 /GS- /Zl /W4 /WX /c /TC "/I$($vc.FullName)/include" "/I$sdk/Include/$($version.Name)/um" "/I$sdk/Include/$($version.Name)/shared" "/I$sdk/Include/$($version.Name)/ucrt" "/Fo$temp/probe.obj" (Join-Path $PSScriptRoot 'battlenet_probe.c')
if ($LASTEXITCODE -ne 0) { throw 'Probe compilation failed' }
& "$sdk/bin/$($version.Name)/x64/rc.exe" /nologo "/I$PSScriptRoot" "/fo$temp/probe.res" (Join-Path $PSScriptRoot 'battlenet_probe.rc')
if ($LASTEXITCODE -ne 0) { throw 'Probe manifest compilation failed' }
& (Join-Path $vc.FullName 'bin/Hostx64/x86/link.exe') /nologo /NODEFAULTLIB /MACHINE:X86 /SUBSYSTEM:CONSOLE /ENTRY:mainCRTStartup /DYNAMICBASE /NXCOMPAT /MANIFEST:NO "/OUT:$out/BattleNetProbe.exe" "$temp/probe.obj" "$temp/probe.res" "$sdk/Lib/$($version.Name)/um/x86/kernel32.lib"
if ($LASTEXITCODE -ne 0) { throw 'Probe link failed' }
Get-FileHash -Algorithm SHA256 -LiteralPath "$out/BattleNetProbe.exe"
