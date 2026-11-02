$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$buildRoot = Join-Path $projectRoot 'App/desktopApp/build'
$runtimeRoot = Join-Path $buildRoot 'vlc'
$resources = Join-Path $buildRoot 'native-resources/windows/vlc'
$icons = Join-Path $buildRoot 'native-icons'
New-Item -ItemType Directory -Force -Path $runtimeRoot,$resources,$icons | Out-Null

$archive = Join-Path $runtimeRoot 'vlc.zip'
$expectedHash = 'fcf30850371ad10c9373cc4f0f4501e7dee49e3e9ae9f20c72fb2661a1ca6323'
if (-not (Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest 'https://download.videolan.org/pub/videolan/vlc/3.0.24/win64/vlc-3.0.24-win64.zip' -OutFile $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ine $expectedHash) { throw 'VLC archive checksum mismatch' }
Expand-Archive -LiteralPath $archive -DestinationPath $runtimeRoot -Force
Copy-Item -Path (Join-Path $runtimeRoot 'vlc-3.0.24/*') -Destination $resources -Recurse -Force

# Native ICO packaging of bxane's supplied mark; no replacement artwork.
Add-Type -AssemblyName System.Drawing
$source = [System.Drawing.Image]::FromFile((Join-Path $projectRoot 'core/assets/brand/flat/01_silver_white.png'))
$bitmap = [System.Drawing.Bitmap]::new(256,256)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$graphics.DrawImage($source,0,0,256,256)
$png = [System.IO.MemoryStream]::new()
$bitmap.Save($png,[System.Drawing.Imaging.ImageFormat]::Png)
$bytes = $png.ToArray()
$iconPath = Join-Path $icons 'nuvx.ico'
$writer = [System.IO.BinaryWriter]::new([System.IO.File]::Create($iconPath))
try {
    $writer.Write([uint16]0); $writer.Write([uint16]1); $writer.Write([uint16]1)
    $writer.Write([byte]0); $writer.Write([byte]0); $writer.Write([byte]0); $writer.Write([byte]0)
    $writer.Write([uint16]1); $writer.Write([uint16]32); $writer.Write([uint32]$bytes.Length); $writer.Write([uint32]22)
    $writer.Write($bytes)
} finally { $writer.Dispose(); $png.Dispose(); $graphics.Dispose(); $bitmap.Dispose(); $source.Dispose() }
$icon = [System.Drawing.Icon]::new($iconPath)
try { if ($icon.Width -ne 256 -or $icon.Height -ne 256) { throw 'Invalid generated icon' } } finally { $icon.Dispose() }
Write-Output 'Verified VLC 3.0.24 runtime and generated nuvx Windows icon.'
