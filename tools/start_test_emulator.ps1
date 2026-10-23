param(
    [string]$SdkRoot = 'C:\Android\Sdk',
    [string]$Avd = 'wow_battlenet_api35',
    [int]$Port = 5560,
    [switch]$ShowWindow,
    [switch]$FullArm64
)

$ErrorActionPreference = 'Stop'
if ($FullArm64) {
    Write-Warning 'Experimental ARM64-on-x86 engine: API35 failed during Android boot and API29 exited with 0xC0000005 on this host. This is a diagnostic mode, not a validated Wine test environment.'
    if (-not $PSBoundParameters.ContainsKey('Avd')) { $Avd = 'wow_battlenet_arm64_api35' }
    if (-not $PSBoundParameters.ContainsKey('Port')) { $Port = 5562 }
}
$emulator = Join-Path $SdkRoot 'emulator\emulator.exe'
if ($FullArm64) {
    # Use the installed target engine for a full software ARM64 experiment. The generic
    # launcher rejects ARM64 AVDs on x86 hosts; this mode is unsupported and can be slow.
    $binary = if ($ShowWindow) { 'qemu-system-aarch64.exe' } else { 'qemu-system-aarch64-headless.exe' }
    $emulator = Join-Path $SdkRoot "emulator\qemu\windows-x86_64\$binary"
    $env:ANDROID_EMULATOR_LAUNCHER_DIR = Join-Path $SdkRoot 'emulator'
    $env:PATH = (Join-Path $SdkRoot 'emulator') + ';' +
        (Join-Path $SdkRoot 'emulator\lib64') + ';' +
        (Join-Path $SdkRoot 'emulator\lib64\qt\lib') + ';' + $env:PATH
}
if (-not (Test-Path -LiteralPath $emulator)) { throw "Android Emulator is missing at $emulator" }
if (-not (Test-Path -LiteralPath (Join-Path $env:USERPROFILE ".android\avd\$Avd.ini"))) {
    throw "AVD '$Avd' is missing. Create the corresponding Google APIs API35 AVD first."
}
$arguments = @('-avd', $Avd, '-port', "$Port", '-no-snapshot', '-no-audio',
    '-gpu', 'swiftshader', '-memory', '4096', '-cores', $(if ($FullArm64) { '2' } else { '4' }),
    '-camera-back', 'none', '-camera-front', 'none')
if ($FullArm64) {
    $arguments += @('-no-boot-anim', '-show-kernel')
}
if (-not $ShowWindow) { $arguments += '-no-window' }
if ($FullArm64) {
    # The x86-host ARM engine defaults to the legacy ranchu board (no PCI).
    # Modern ARM64 images use virt on ARM hosts; select that board explicitly here too.
    $arguments += @('-qemu', '-machine', 'type=virt', '-cpu', 'max')
}
$windowStyle = if ($ShowWindow) { 'Normal' } else { 'Hidden' }
$logDirectory = Join-Path $env:USERPROFILE ".android\avd\$Avd.avd"
$process = Start-Process -FilePath $emulator -ArgumentList $arguments -WindowStyle $windowStyle -PassThru `
    -RedirectStandardOutput (Join-Path $logDirectory 'emulator-stdout.log') `
    -RedirectStandardError (Join-Path $logDirectory 'emulator-stderr.log')
Write-Output "Started $Avd (PID $($process.Id)); target ADB explicitly with: adb -s emulator-$Port ..."
