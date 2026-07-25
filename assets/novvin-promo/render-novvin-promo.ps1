param(
    [string]$OutputPath = "$PSScriptRoot\conectate-presenta-novvin.avi",
    [string]$Mp4Path = "$PSScriptRoot\conectate-presenta-novvin.mp4",
    [string]$PreviewPath = "$PSScriptRoot\novvin-promo-preview.png",
    [string]$FinalPreviewPath = "$PSScriptRoot\novvin-promo-final.png",
    [string]$LogoPath = "$PSScriptRoot\novvin-mascot.png",
    [string]$Mp4StartPreviewPath = "$PSScriptRoot\novvin-promo-start-mp4.png",
    [string]$Mp4MiddlePreviewPath = "$PSScriptRoot\novvin-promo-middle-mp4.png",
    [string]$Mp4FinalPreviewPath = "$PSScriptRoot\novvin-promo-final-mp4.png",
    [string]$FfmpegPath = $env:FFMPEG_PATH
)

Add-Type -AssemblyName System.Drawing

$ErrorActionPreference = 'Stop'
$Width = 360
$Height = 640
$Fps = 12
$DurationSeconds = 20
$FrameCount = $Fps * $DurationSeconds
$Stride = $Width * 3
$FrameSize = $Stride * $Height

function Write-FourCC([System.IO.BinaryWriter]$Writer, [string]$Value) {
    $Writer.Write([System.Text.Encoding]::ASCII.GetBytes($Value))
}

function Resolve-Ffmpeg([string]$RequestedPath) {
    if ($RequestedPath) {
        if (-not (Test-Path -LiteralPath $RequestedPath -PathType Leaf)) {
            throw "FFmpeg was not found at '$RequestedPath'."
        }
        return (Resolve-Path -LiteralPath $RequestedPath).Path
    }

    $command = Get-Command ffmpeg -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command) { return $command.Source }

    $knownPaths = @(
        'C:\Program Files\ffmpeg\bin\ffmpeg.exe',
        'C:\ProgramData\chocolatey\bin\ffmpeg.exe'
    )
    foreach ($candidate in $knownPaths) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    return $null
}

function Invoke-Ffmpeg([string]$Executable, [string[]]$Arguments, [string]$Operation) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "FFmpeg failed while $Operation (exit code $LASTEXITCODE)."
    }
}

function New-Color([int]$Alpha, [int]$Red, [int]$Green, [int]$Blue) {
    [System.Drawing.Color]::FromArgb([Math]::Max(0, [Math]::Min(255, $Alpha)), $Red, $Green, $Blue)
}

function Ease([double]$Value) {
    $Value = [Math]::Max(0.0, [Math]::Min(1.0, $Value))
    return $Value * $Value * (3 - 2 * $Value)
}

function Fade([double]$Time, [double]$From, [double]$To) {
    return [Math]::Sin([Math]::PI * [Math]::Max(0.0, [Math]::Min(1.0, ($Time - $From) / ($To - $From))))
}

function Draw-Text(
    [System.Drawing.Graphics]$Graphics,
    [string]$Value,
    [float]$X,
    [float]$Y,
    [float]$Size,
    [System.Drawing.Color]$Color,
    [System.Drawing.FontStyle]$Style = [System.Drawing.FontStyle]::Bold,
    [float]$Alpha = 1.0
) {
    $brush = [System.Drawing.SolidBrush]::new((New-Color ([int](255 * $Alpha)) $Color.R $Color.G $Color.B))
    $font = [System.Drawing.Font]::new('Arial', $Size, $Style, [System.Drawing.GraphicsUnit]::Pixel)
    $format = [System.Drawing.StringFormat]::new()
    $format.Alignment = [System.Drawing.StringAlignment]::Center
    $format.LineAlignment = [System.Drawing.StringAlignment]::Center
    $Graphics.DrawString($Value, $font, $brush, $X, $Y, $format)
    $format.Dispose(); $font.Dispose(); $brush.Dispose()
}

function Draw-RoundRect(
    [System.Drawing.Graphics]$Graphics,
    [float]$X,
    [float]$Y,
    [float]$BoxWidth,
    [float]$BoxHeight,
    [float]$Radius,
    [System.Drawing.Color]$Color
) {
    $path = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $diameter = $Radius * 2
    $path.AddArc($X, $Y, $diameter, $diameter, 180, 90)
    $path.AddArc($X + $BoxWidth - $diameter, $Y, $diameter, $diameter, 270, 90)
    $path.AddArc($X + $BoxWidth - $diameter, $Y + $BoxHeight - $diameter, $diameter, $diameter, 0, 90)
    $path.AddArc($X, $Y + $BoxHeight - $diameter, $diameter, $diameter, 90, 90)
    $path.CloseFigure()
    $brush = [System.Drawing.SolidBrush]::new($Color)
    $Graphics.FillPath($brush, $path)
    $brush.Dispose(); $path.Dispose()
}

function Draw-NovvinLogo([System.Drawing.Graphics]$Graphics, [float]$X, [float]$Y, [float]$Scale, [float]$Alpha) {
    $state = $Graphics.Save()
    $Graphics.TranslateTransform($X, $Y)
    $Graphics.ScaleTransform($Scale, $Scale)
    $navy = [System.Drawing.SolidBrush]::new((New-Color ([int](255 * $Alpha)) 23 34 77))
    $mint = [System.Drawing.SolidBrush]::new((New-Color ([int](255 * $Alpha)) 108 230 213))
    $coral = [System.Drawing.SolidBrush]::new((New-Color ([int](255 * $Alpha)) 255 107 107))
    $white = [System.Drawing.SolidBrush]::new((New-Color ([int](255 * $Alpha)) 243 255 249))
    $body = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $body.AddBezier(-58, -18, -58, -53, -34, -70, 0, -70)
    $body.AddBezier(0, -70, 34, -70, 58, -53, 58, -18)
    $body.AddBezier(58, -18, 58, 15, 41, 37, 19, 45)
    $body.AddLine(0, 64, -19, 45)
    $body.AddBezier(-19, 45, -41, 37, -58, 15, -58, -18)
    $body.CloseFigure()
    $Graphics.FillPath($navy, $body)
    $Graphics.FillEllipse($mint, -44, -57, 88, 76)
    $Graphics.FillEllipse($white, -33, -48, 66, 56)
    $Graphics.FillPolygon($coral, [System.Drawing.PointF[]]@(
        [System.Drawing.PointF]::new(-47, -28), [System.Drawing.PointF]::new(-60, -17), [System.Drawing.PointF]::new(-47, -7)
    ))
    $Graphics.FillPolygon($coral, [System.Drawing.PointF[]]@(
        [System.Drawing.PointF]::new(47, -28), [System.Drawing.PointF]::new(60, -17), [System.Drawing.PointF]::new(47, -7)
    ))
    $Graphics.FillEllipse($navy, -22, -31, 10, 14)
    $Graphics.FillEllipse($navy, 12, -31, 10, 14)
    $smile = [System.Drawing.Pen]::new((New-Color ([int](255 * $Alpha)) 255 107 107), 6)
    $smile.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $smile.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $Graphics.DrawArc($smile, -19, -25, 38, 34, 35, 110)
    $Graphics.FillPolygon($coral, [System.Drawing.PointF[]]@(
        [System.Drawing.PointF]::new(-15, 27), [System.Drawing.PointF]::new(15, 27),
        [System.Drawing.PointF]::new(10, 43), [System.Drawing.PointF]::new(-10, 43)
    ))
    $points = [System.Drawing.PointF[]]@(
        [System.Drawing.PointF]::new(39, -76), [System.Drawing.PointF]::new(45, -60),
        [System.Drawing.PointF]::new(61, -54), [System.Drawing.PointF]::new(45, -48),
        [System.Drawing.PointF]::new(39, -32), [System.Drawing.PointF]::new(33, -48),
        [System.Drawing.PointF]::new(17, -54), [System.Drawing.PointF]::new(33, -60)
    )
    $Graphics.FillPolygon($white, $points)
    $smile.Dispose(); $body.Dispose(); $white.Dispose(); $coral.Dispose(); $mint.Dispose(); $navy.Dispose()
    $Graphics.Restore($state)
}

function Draw-ConectateLogo([System.Drawing.Graphics]$Graphics, [float]$X, [float]$Y, [float]$Scale, [float]$Alpha) {
    $state = $Graphics.Save()
    $Graphics.TranslateTransform($X, $Y)
    $Graphics.ScaleTransform($Scale, $Scale)
    Draw-RoundRect $Graphics -62 -62 124 124 24 (New-Color ([int](255 * $Alpha)) 33 150 243)
    $white = [System.Drawing.SolidBrush]::new((New-Color ([int](255 * $Alpha)) 255 255 255))
    $blue = [System.Drawing.Pen]::new((New-Color ([int](255 * $Alpha)) 33 150 243), 7)
    $green = [System.Drawing.Pen]::new((New-Color ([int](255 * $Alpha)) 76 175 80), 7)
    $orange = [System.Drawing.Pen]::new((New-Color ([int](255 * $Alpha)) 255 152 0), 7)
    foreach ($pen in @($blue, $green, $orange)) {
        $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
        $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    }
    Draw-RoundRect $Graphics -46 -43 92 70 14 (New-Color ([int](255 * $Alpha)) 255 255 255)
    $Graphics.FillPolygon($white, [System.Drawing.PointF[]]@(
        [System.Drawing.PointF]::new(-24, 22),
        [System.Drawing.PointF]::new(-24, 49),
        [System.Drawing.PointF]::new(2, 24)
    ))
    $Graphics.DrawLine($blue, -27, -22, 27, -22)
    $Graphics.DrawLine($green, -27, -2, 18, -2)
    $Graphics.DrawLine($orange, -27, 18, 8, 18)
    $orange.Dispose(); $green.Dispose(); $blue.Dispose(); $white.Dispose()
    $Graphics.Restore($state)
}

function Get-BitmapBytes([System.Drawing.Bitmap]$Bitmap) {
    $rectangle = [System.Drawing.Rectangle]::new(0, 0, $Bitmap.Width, $Bitmap.Height)
    $bitmapData = $Bitmap.LockBits($rectangle, [System.Drawing.Imaging.ImageLockMode]::ReadOnly, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    try {
        $bytes = New-Object byte[] $FrameSize
        for ($row = 0; $row -lt $Height; $row++) {
            $source = [System.IntPtr]::Add($bitmapData.Scan0, $row * $bitmapData.Stride)
            [System.Runtime.InteropServices.Marshal]::Copy($source, $bytes, ($Height - 1 - $row) * $Stride, $Stride)
        }
        return ,$bytes
    } finally {
        $Bitmap.UnlockBits($bitmapData)
    }
}

function Draw-Frame([System.Drawing.Graphics]$Graphics, [double]$Time) {
    $Graphics.Clear([System.Drawing.Color]::FromArgb(12, 16, 32))
    $linePen = [System.Drawing.Pen]::new([System.Drawing.Color]::FromArgb(30, 110, 140, 220), 1)
    for ($line = -4; $line -lt 12; $line++) {
        $offset = (($Time * 7) + ($line * 53)) % 90
        $Graphics.DrawLine($linePen, -30, ($line * 70) + $offset - 120, $Width + 30, ($line * 70) + $offset + 80)
    }
    $linePen.Dispose()
    $progressBrush = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(255, 255, 107, 107))
    $Graphics.FillRectangle($progressBrush, 0, 0, [int]($Width * ($Time / $DurationSeconds)), 4)
    $progressBrush.Dispose()

    if ($Time -lt 4) {
        $alpha = Fade $Time 0.1 3.9
        Draw-ConectateLogo $Graphics 180 225 (0.78 + (Ease (($Time - 0.2) / 1.2) * 0.08)) $alpha
        Draw-Text $Graphics 'CONECTATE' 180 372 29 ([System.Drawing.Color]::FromArgb(185, 203, 255)) ([System.Drawing.FontStyle]::Bold) $alpha
        Draw-Text $Graphics 'presenta' 180 405 17 ([System.Drawing.Color]::FromArgb(215, 226, 255)) ([System.Drawing.FontStyle]::Regular) $alpha
    } elseif ($Time -lt 8) {
        $alpha = Fade $Time 4.1 7.9
        Draw-NovvinLogo $Graphics 180 175 0.66 $alpha
        Draw-Text $Graphics 'NOVVIN' 180 305 42 ([System.Drawing.Color]::White) ([System.Drawing.FontStyle]::Bold) $alpha
        Draw-Text $Graphics 'La nueva inteligencia de Conectate' 180 350 16 ([System.Drawing.Color]::FromArgb(183, 197, 232)) ([System.Drawing.FontStyle]::Regular) $alpha
        Draw-Text $Graphics 'Una IA propia. Hecha para crecer contigo.' 180 485 17 ([System.Drawing.Color]::FromArgb(108, 230, 213)) ([System.Drawing.FontStyle]::Bold) $alpha
    } elseif ($Time -lt 12) {
        $alpha = Fade $Time 8.1 11.9
        Draw-Text $Graphics 'Tu guia dentro de Conectate' 180 122 25 ([System.Drawing.Color]::White) ([System.Drawing.FontStyle]::Bold) $alpha
        $labels = @('Mensajes', 'Grupos', 'Comunidades')
        for ($index = 0; $index -lt $labels.Count; $index++) {
            $y = 225 + ($index * 67)
            Draw-RoundRect $Graphics 72 $y 216 47 24 (New-Color ([int](45 * $alpha)) 108 230 213)
            Draw-Text $Graphics $labels[$index] 180 ($y + 24) 18 ([System.Drawing.Color]::FromArgb(217, 255, 250)) ([System.Drawing.FontStyle]::Bold) $alpha
        }
        Draw-NovvinLogo $Graphics 180 465 0.5 $alpha
        Draw-Text $Graphics 'Pregunta. Crea. Conecta.' 180 565 20 ([System.Drawing.Color]::FromArgb(255, 155, 155)) ([System.Drawing.FontStyle]::Bold) $alpha
    } elseif ($Time -lt 16) {
        $alpha = Fade $Time 12.1 15.9
        Draw-Text $Graphics 'Respuestas para empezar' 180 105 25 ([System.Drawing.Color]::White) ([System.Drawing.FontStyle]::Bold) $alpha
        Draw-RoundRect $Graphics 35 182 290 270 24 (New-Color ([int](230 * $alpha)) 23 34 77)
        Draw-NovvinLogo $Graphics 76 230 0.24 $alpha
        Draw-Text $Graphics 'Novvin' 138 215 18 ([System.Drawing.Color]::White) ([System.Drawing.FontStyle]::Bold) $alpha
        Draw-RoundRect $Graphics 99 240 195 48 20 (New-Color ([int](220 * $alpha)) 64 78 124)
        Draw-Text $Graphics '¿Como creo un grupo?' 196 264 13 ([System.Drawing.Color]::White) ([System.Drawing.FontStyle]::Regular) $alpha
        Draw-RoundRect $Graphics 61 314 220 62 20 (New-Color ([int](55 * $alpha)) 108 230 213)
        Draw-Text $Graphics 'En Grupos toca + y crea' 171 339 12 ([System.Drawing.Color]::FromArgb(217, 255, 250)) ([System.Drawing.FontStyle]::Bold) $alpha
        Draw-Text $Graphics 'tu espacio.' 171 359 12 ([System.Drawing.Color]::FromArgb(217, 255, 250)) ([System.Drawing.FontStyle]::Bold) $alpha
    } else {
        $alpha = Fade $Time 16.1 19.9
        Draw-ConectateLogo $Graphics 114 222 0.55 $alpha
        Draw-Text $Graphics '+' 180 222 30 ([System.Drawing.Color]::FromArgb(183, 197, 232)) ([System.Drawing.FontStyle]::Bold) $alpha
        Draw-NovvinLogo $Graphics 246 222 0.55 $alpha
        Draw-Text $Graphics 'CONECTATE + NOVVIN' 180 365 28 ([System.Drawing.Color]::White) ([System.Drawing.FontStyle]::Bold) $alpha
        Draw-Text $Graphics 'El siguiente paso comienza ahora.' 180 402 16 ([System.Drawing.Color]::FromArgb(183, 197, 232)) ([System.Drawing.FontStyle]::Regular) $alpha
        Draw-RoundRect $Graphics 85 495 190 52 26 (New-Color ([int](255 * $alpha)) 247 108 108)
        Draw-Text $Graphics 'Disponible en la app' 180 521 15 ([System.Drawing.Color]::White) ([System.Drawing.FontStyle]::Bold) $alpha
    }
}

$directory = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$stream = [System.IO.File]::Open($OutputPath, [System.IO.FileMode]::Create, [System.IO.FileAccess]::ReadWrite)
$writer = [System.IO.BinaryWriter]::new($stream)
$index = [System.Collections.Generic.List[object]]::new()

try {
    Write-FourCC $writer 'RIFF'; $riffSizePosition = $stream.Position; $writer.Write([int]0); Write-FourCC $writer 'AVI '
    Write-FourCC $writer 'LIST'; $writer.Write([int]192); Write-FourCC $writer 'hdrl'
    Write-FourCC $writer 'avih'; $writer.Write([int]56)
    $writer.Write([int](1000000 / $Fps)); $writer.Write([int]($FrameSize * $Fps)); $writer.Write([int]0); $writer.Write([int]0x10)
    $writer.Write([int]$FrameCount); $writer.Write([int]0); $writer.Write([int]1); $writer.Write([int]$FrameSize)
    $writer.Write([int]$Width); $writer.Write([int]$Height); 1..4 | ForEach-Object { $writer.Write([int]0) }
    Write-FourCC $writer 'LIST'; $writer.Write([int]116); Write-FourCC $writer 'strl'
    Write-FourCC $writer 'strh'; $writer.Write([int]56); Write-FourCC $writer 'vids'; $writer.Write([int]0)
    $writer.Write([int]0); $writer.Write([uint16]0); $writer.Write([uint16]0); $writer.Write([int]0)
    $writer.Write([int]1); $writer.Write([int]$Fps); $writer.Write([int]0); $writer.Write([int]$FrameCount)
    $writer.Write([int]$FrameSize); $writer.Write([int]-1); $writer.Write([int]0)
    $writer.Write([int16]0); $writer.Write([int16]0); $writer.Write([int16]$Width); $writer.Write([int16]$Height)
    Write-FourCC $writer 'strf'; $writer.Write([int]40); $writer.Write([int]40); $writer.Write([int]$Width); $writer.Write([int]$Height)
    $writer.Write([int16]1); $writer.Write([int16]24); $writer.Write([int]0); $writer.Write([int]$FrameSize)
    $writer.Write([int]0); $writer.Write([int]0); $writer.Write([int]0); $writer.Write([int]0)
    Write-FourCC $writer 'LIST'; $moviSizePosition = $stream.Position; $writer.Write([int]0); Write-FourCC $writer 'movi'; $moviStart = $stream.Position

    $bitmap = [System.Drawing.Bitmap]::new($Width, $Height, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    for ($frame = 0; $frame -lt $FrameCount; $frame++) {
        $time = $frame / $Fps
        Draw-Frame $graphics $time
        if ($frame -eq 114) { $bitmap.Save($PreviewPath, [System.Drawing.Imaging.ImageFormat]::Png) }
        if ($frame -eq 210) { $bitmap.Save($FinalPreviewPath, [System.Drawing.Imaging.ImageFormat]::Png) }
        $offset = [int]($stream.Position - $moviStart)
        $raw = [byte[]](Get-BitmapBytes $bitmap)
        Write-FourCC $writer '00db'; $writer.Write([int]$raw.Length); $writer.Write([byte[]]$raw)
        $index.Add([pscustomobject]@{ Offset = $offset; Size = $raw.Length })
    }
    $graphics.Dispose(); $bitmap.Dispose()
    $moviEnd = $stream.Position
    $stream.Position = $moviSizePosition; $writer.Write([int]($moviEnd - ($moviSizePosition + 4))); $stream.Position = $moviEnd
    Write-FourCC $writer 'idx1'; $writer.Write([int]($index.Count * 16))
    foreach ($entry in $index) { Write-FourCC $writer '00db'; $writer.Write([int]0x10); $writer.Write([int]$entry.Offset); $writer.Write([int]$entry.Size) }
    $end = $stream.Position; $stream.Position = $riffSizePosition; $writer.Write([int]($end - 8)); $stream.Position = $end
} finally {
    $writer.Dispose(); $stream.Dispose()
}

$logoBitmap = [System.Drawing.Bitmap]::new(512, 512, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$logoGraphics = [System.Drawing.Graphics]::FromImage($logoBitmap)
$logoGraphics.Clear([System.Drawing.Color]::Transparent)
$logoGraphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
Draw-NovvinLogo $logoGraphics 256 266 3.1 1.0
$logoBitmap.Save($LogoPath, [System.Drawing.Imaging.ImageFormat]::Png)
$logoGraphics.Dispose(); $logoBitmap.Dispose()

Write-Host "Created $OutputPath"

$resolvedFfmpeg = Resolve-Ffmpeg $FfmpegPath
if (-not $resolvedFfmpeg) {
    Write-Warning 'FFmpeg 4 or newer was not found. The lossless AVI and PNG assets were created, but the compatible MP4 was skipped. Set FFMPEG_PATH or pass -FfmpegPath to create it.'
    return
}
$versionLine = (& $resolvedFfmpeg -version 2>&1 | Select-Object -First 1) -join ''
if (($versionLine -notmatch '^ffmpeg version (?<Major>\d+)\.') -or ([int]$Matches.Major -lt 4)) {
    throw "FFmpeg 4 or newer is required for deterministic BT.709 output. Found: $versionLine"
}

$mp4Directory = Split-Path -Parent $Mp4Path
New-Item -ItemType Directory -Path $mp4Directory -Force | Out-Null

# Keep the delivery file deliberately conservative for Android/Web hardware decoders:
# H.264 Main L3.1, 8-bit 4:2:0, limited range and an explicit BT.709 colour description.
# The prior implicit conversion left primaries/transfer unspecified, which lets some
# players choose a different matrix and can surface as a green/washed-out overlay.
$encodeArguments = @(
    '-loglevel', 'warning', '-y',
    '-i', $OutputPath,
    '-map', '0:v:0', '-an',
    '-vf', 'scale=720:1280:flags=lanczos:out_color_matrix=bt709:out_range=tv,format=yuv420p',
    '-r', [string]$Fps,
    '-c:v', 'libx264', '-profile:v', 'main', '-level:v', '3.1',
    '-preset', 'slow', '-crf', '18', '-g', [string]($Fps * 2),
    '-color_range', '1', '-color_primaries', '1', '-color_trc', '1', '-colorspace', '1',
    '-x264opts', 'colorprim=bt709:transfer=bt709:colormatrix=bt709:fullrange=off',
    '-movflags', '+faststart',
    $Mp4Path
)
Invoke-Ffmpeg $resolvedFfmpeg $encodeArguments 'encoding the colour-safe MP4'

# Decode the complete output once, then save representative frames from the encoded
# file (rather than the source bitmap) so codec/colour regressions are visible.
Invoke-Ffmpeg $resolvedFfmpeg @('-loglevel', 'error', '-i', $Mp4Path, '-map', '0:v:0', '-f', 'null', '-') 'validating the MP4'
$previewTimes = @(
    @{ Time = '2.0'; Path = $Mp4StartPreviewPath },
    @{ Time = '10.0'; Path = $Mp4MiddlePreviewPath },
    @{ Time = '18.0'; Path = $Mp4FinalPreviewPath }
)
foreach ($preview in $previewTimes) {
    $previewDirectory = Split-Path -Parent $preview.Path
    New-Item -ItemType Directory -Path $previewDirectory -Force | Out-Null
    Invoke-Ffmpeg $resolvedFfmpeg @(
        '-loglevel', 'error', '-y', '-i', $Mp4Path,
        '-ss', $preview.Time, '-frames:v', '1', $preview.Path
    ) "extracting the $($preview.Time)s verification frame"
}

Write-Host "Created $Mp4Path"
Write-Host "Verified MP4 frames: $Mp4StartPreviewPath, $Mp4MiddlePreviewPath, $Mp4FinalPreviewPath"
