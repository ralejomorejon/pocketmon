# Downloads the latest Intel PresentMon console exe (MIT) from GitHub releases
# into pc-agent\PcStats.Agent\tools\. The agent build copies tools\** to its output.
[CmdletBinding()]
param(
    [string]$Destination = (Join-Path $PSScriptRoot '..\pc-agent\PcStats.Agent\tools')
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$release = Invoke-RestMethod -Uri 'https://api.github.com/repos/GameTechDev/PresentMon/releases/latest' `
    -Headers @{ 'User-Agent' = 'PcStatsAgent' }

# The console app is published as PresentMon-<version>-x64.exe (the MSI is the GUI overlay).
$asset = $release.assets | Where-Object { $_.name -match '^PresentMon.*-x64\.exe$' } | Select-Object -First 1
if (-not $asset) {
    $asset = $release.assets | Where-Object { $_.name -match '^PresentMon.*\.exe$' } | Select-Object -First 1
}
if (-not $asset) { throw "No PresentMon console exe found in release $($release.tag_name)." }

New-Item -ItemType Directory -Force -Path $Destination | Out-Null
$Destination = (Resolve-Path $Destination).Path

Get-ChildItem -Path $Destination -Filter 'PresentMon*.exe' -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -ne $asset.name } | Remove-Item -Force

$target = Join-Path $Destination $asset.name
Write-Host "Downloading $($asset.name) ($($release.tag_name)) to $target"
Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $target -UseBasicParsing
Write-Host 'Done. Rebuild the agent so the exe is copied to the output folder.'
