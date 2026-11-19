# Manually sets up the USB connection: for each attached Android device, runs
#   adb -s <serial> reverse tcp:28555 tcp:28555
# so the phone app can connect to ws://127.0.0.1:28555/ws. (The agent also does this automatically every 3 s.)
[CmdletBinding()]
param(
    [int]$Port = 28555
)

$ErrorActionPreference = 'Stop'

function Find-Adb {
    $cmd = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $candidates = @(
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
    )
    foreach ($var in 'ANDROID_HOME', 'ANDROID_SDK_ROOT') {
        $root = [Environment]::GetEnvironmentVariable($var)
        if ($root) { $candidates += (Join-Path $root 'platform-tools\adb.exe') }
    }
    foreach ($c in $candidates) { if (Test-Path $c) { return $c } }
    return $null
}

$adb = Find-Adb
if (-not $adb) { throw 'adb.exe not found. Install Android SDK platform-tools or add adb to PATH.' }
Write-Host "Using $adb"

$serials = & $adb devices | Select-Object -Skip 1 |
    Where-Object { $_ -match '^(\S+)\s+device$' } |
    ForEach-Object { $Matches[1] }

if (-not $serials) {
    Write-Warning 'No authorized devices found. Enable USB debugging and accept the prompt on the phone.'
    exit 1
}

foreach ($serial in $serials) {
    & $adb -s $serial reverse "tcp:$Port" "tcp:$Port"
    if ($LASTEXITCODE -eq 0) {
        Write-Host "[$serial] reverse tcp:$Port -> tcp:$Port OK. Phone can connect to ws://127.0.0.1:$Port/ws"
    } else {
        Write-Warning "[$serial] adb reverse failed (exit $LASTEXITCODE)"
    }
}
