# Generates PocketMon logo and icon assets (wordmark, app icons, Android launcher icons, web icons, Windows .ico).
# Usage: pwsh scripts/generate-branding.ps1   (Windows; uses the "Arial Black" system font)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$font = 'Arial Black'
$red = [System.Drawing.Color]::FromArgb(0xE1, 0x06, 0x00)
$white = [System.Drawing.Color]::White
$black = [System.Drawing.Color]::Black

function New-Canvas([int]$w, [int]$h) {
    $bmp = New-Object System.Drawing.Bitmap $w, $h
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.InterpolationMode = 'HighQualityBicubic'
    $g.PixelOffsetMode = 'HighQuality'
    return @($bmp, $g)
}

function Get-TextPath([string]$text) {
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $family = New-Object System.Drawing.FontFamily $font
    $path.AddString($text, $family, [int][System.Drawing.FontStyle]::Bold, 200, [System.Drawing.PointF]::new(0, 0), [System.Drawing.StringFormat]::GenericTypographic)
    return $path
}

# Scales/translates a set of paths (as one block) so their joint bounds fit centred inside $box.
function Fit-Paths($paths, [System.Drawing.RectangleF]$box) {
    $bounds = $null
    foreach ($p in $paths) {
        $b = $p.GetBounds()
        $bounds = if ($bounds) { [System.Drawing.RectangleF]::Union($bounds, $b) } else { $b }
    }
    $scale = [Math]::Min($box.Width / $bounds.Width, $box.Height / $bounds.Height)
    $m = New-Object System.Drawing.Drawing2D.Matrix
    $m.Translate($box.X + ($box.Width - $bounds.Width * $scale) / 2, $box.Y + ($box.Height - $bounds.Height * $scale) / 2)
    $m.Scale($scale, $scale)
    $m.Translate(-$bounds.X, -$bounds.Y)
    foreach ($p in $paths) { $p.Transform($m) }
}

function Add-RoundedRect($g, [System.Drawing.Color]$color, [float]$x, [float]$y, [float]$w, [float]$h, [float]$r) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = 2 * $r
    $p.AddArc($x, $y, $d, $d, 180, 90); $p.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
    $p.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90); $p.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
    $p.CloseFigure()
    $g.FillPath((New-Object System.Drawing.SolidBrush $color), $p)
}

# Wordmark: white "POCKET" + red "MON" on a black rounded banner.
function New-Wordmark([string]$out, [int]$w, [int]$h) {
    $bmp, $g = New-Canvas $w $h
    $g.Clear([System.Drawing.Color]::Transparent)
    Add-RoundedRect $g $black 0 0 $w $h ($h * 0.18)
    $pocket = Get-TextPath 'POCKET'
    $mon = Get-TextPath 'MON'
    # Place MON right after POCKET with a small gap (in font units), then fit both together.
    $gap = 18
    $shift = New-Object System.Drawing.Drawing2D.Matrix
    $shift.Translate($pocket.GetBounds().Right - $mon.GetBounds().X + $gap, 0)
    $mon.Transform($shift)
    Fit-Paths @($pocket, $mon) ([System.Drawing.RectangleF]::new($w * 0.06, $h * 0.2, $w * 0.88, $h * 0.6))
    $g.FillPath((New-Object System.Drawing.SolidBrush $white), $pocket)
    $g.FillPath((New-Object System.Drawing.SolidBrush $red), $mon)
    $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
}

# App icon: bold white "PM" on red. $full = square fill (Android adaptive/web maskable), else rounded square.
function New-Icon([string]$out, [int]$size, [string]$mode = 'rounded') {
    $bmp, $g = New-Canvas $size $size
    $g.Clear([System.Drawing.Color]::Transparent)
    $textBox = 0.62
    switch ($mode) {
        'rounded' { Add-RoundedRect $g $red 0 0 $size $size ($size * 0.22) }
        'full' { $g.Clear($red) }
        'foreground' { $textBox = 0.40 } # Android adaptive foreground: transparent, keep inside the 66/108 safe zone.
    }
    $pm = Get-TextPath 'PM'
    $inset = $size * (1 - $textBox) / 2
    Fit-Paths @($pm) ([System.Drawing.RectangleF]::new($inset, $inset, $size * $textBox, $size * $textBox))
    $g.FillPath((New-Object System.Drawing.SolidBrush $white), $pm)
    $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
}

# Multi-size .ico with PNG-compressed entries (supported since Windows Vista).
function New-Ico([string]$out, [int[]]$sizes) {
    $pngs = foreach ($s in $sizes) {
        $tmp = [IO.Path]::GetTempFileName()
        New-Icon $tmp $s 'rounded'
        , [IO.File]::ReadAllBytes($tmp)
        Remove-Item $tmp
    }
    $ms = New-Object IO.MemoryStream
    $bw = New-Object IO.BinaryWriter $ms
    $bw.Write([uint16]0); $bw.Write([uint16]1); $bw.Write([uint16]$sizes.Count)
    $offset = 6 + 16 * $sizes.Count
    for ($i = 0; $i -lt $sizes.Count; $i++) {
        $s = $sizes[$i]; $len = $pngs[$i].Length
        $bw.Write([byte]($(if ($s -ge 256) { 0 } else { $s }))); $bw.Write([byte]($(if ($s -ge 256) { 0 } else { $s })))
        $bw.Write([byte]0); $bw.Write([byte]0); $bw.Write([uint16]1); $bw.Write([uint16]32)
        $bw.Write([uint32]$len); $bw.Write([uint32]$offset)
        $offset += $len
    }
    foreach ($p in $pngs) { $bw.Write($p) }
    [IO.File]::WriteAllBytes($out, $ms.ToArray())
}

$branding = Join-Path $root 'branding'
New-Item -ItemType Directory -Force $branding | Out-Null
New-Wordmark (Join-Path $branding 'pocketmon-logo.png') 1600 400
New-Icon (Join-Path $branding 'pocketmon-icon.png') 1024 'rounded'

# Web app
$web = Join-Path $root 'web'
New-Icon (Join-Path $web 'icon-192.png') 192 'full'
New-Icon (Join-Path $web 'icon-512.png') 512 'full'
New-Icon (Join-Path $web 'apple-touch-icon.png') 180 'full'
New-Icon (Join-Path $web 'favicon.png') 64 'rounded'
New-Wordmark (Join-Path $web 'logo.png') 800 200

# Android launcher icons (legacy + adaptive foreground)
$res = Join-Path $root 'android\app\src\main\res'
$densities = @{ mdpi = 1; hdpi = 1.5; xhdpi = 2; xxhdpi = 3; xxxhdpi = 4 }
foreach ($d in $densities.Keys) {
    $dir = Join-Path $res "mipmap-$d"
    New-Item -ItemType Directory -Force $dir | Out-Null
    New-Icon (Join-Path $dir 'ic_launcher.png') ([int](48 * $densities[$d])) 'rounded'
    New-Icon (Join-Path $dir 'ic_launcher_foreground.png') ([int](108 * $densities[$d])) 'foreground'
}

# Windows agent
New-Ico (Join-Path $root 'pc-agent\PcStats.Agent\pocketmon.ico') @(16, 24, 32, 48, 256)

Write-Host 'Branding assets generated.'
