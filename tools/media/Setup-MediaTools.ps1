param()
$ErrorActionPreference = 'Stop'
$destination = Join-Path $PSScriptRoot 'build'
New-Item -ItemType Directory -Force $destination | Out-Null

function Get-VerifiedDownload([string]$Url, [string]$Path, [string]$Sha256) {
    if ((Test-Path -LiteralPath $Path) -and (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -eq $Sha256) { return }
    $partial = "$Path.partial"
    & curl.exe --fail --location --silent --show-error --connect-timeout 20 --max-time 180 --output $partial $Url
    if ($LASTEXITCODE -ne 0) { throw "Download failed: $Url" }
    if ((Get-FileHash -LiteralPath $partial -Algorithm SHA256).Hash -ne $Sha256) { throw "SHA-256 mismatch: $Url" }
    Move-Item -LiteralPath $partial -Destination $Path -Force
}

# Pinned official release and publisher checksum manifests (see docs/YOUTUBE_VERIFICATION.md).
Get-VerifiedDownload 'https://github.com/yt-dlp/yt-dlp/releases/download/2026.08.19/yt-dlp.exe' `
    (Join-Path $destination 'yt-dlp.exe') '66674953fe251b89f4d08c5f0e35e0728679bd67ab3d7d05c0562af101dd3e7a'
Get-VerifiedDownload 'https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-9.0.2-essentials_build.7z' `
    (Join-Path $destination 'ffmpeg.7z') '4705843ccaaf54257c16ad90f3e952ece33c17df964ecf7bfdbb0f49c7171077'
# Windows tar supports 7z; include ffplay for TUI/Desktop fallback playback.
& tar.exe -xf (Join-Path $destination 'ffmpeg.7z') -C $destination --strip-components 2 `
    ffmpeg-9.0.2-essentials_build/bin/ffmpeg.exe ffmpeg-9.0.2-essentials_build/bin/ffprobe.exe ffmpeg-9.0.2-essentials_build/bin/ffplay.exe
if ($LASTEXITCODE -ne 0) { throw 'FFmpeg extraction failed' }
& (Join-Path $destination 'yt-dlp.exe') --version
& (Join-Path $destination 'ffmpeg.exe') -version | Select-Object -First 1
& (Join-Path $destination 'ffprobe.exe') -version | Select-Object -First 1
Write-Output 'Tools are local to tools/media/build; add that directory to PATH in the backend terminal.'
