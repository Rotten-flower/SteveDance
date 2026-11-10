param(
    [Parameter(Mandatory=$true)][string]$FfmpegSource,
    [Parameter(Mandatory=$true)][string]$Toolchain,
    [Parameter(Mandatory=$true)][string]$Jdk
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$source=(Resolve-Path -LiteralPath $FfmpegSource).Path
$env:PATH=(Resolve-Path -LiteralPath (Join-Path $Toolchain 'bin')).Path+';'+$env:PATH
$target=Join-Path $root 'release-resources/native/windows-x86_64/stevedance-media.dll'
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target))|Out-Null
$args=@('-shared','-Os','-s','-ffunction-sections','-fdata-sections','-Wl,--gc-sections','-static-libgcc',
    ('-I'+(Join-Path $Jdk 'include')),('-I'+(Join-Path $Jdk 'include/win32')),('-I'+$source),
    (Join-Path $PSScriptRoot 'stevedance_media.c'))
foreach($lib in @('avformat','avcodec','swscale','avutil')){$args+=Join-Path $source "lib$lib/lib$lib.a"}
$args+=@('-lm','-latomic','-lole32','-luser32','-lbcrypt','-o',$target)
& gcc @args
if($LASTEXITCODE -ne 0){throw 'Native link failed'}
$hash=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
$row="windows-x86_64`tnative/windows-x86_64/stevedance-media.dll`t$((Get-Item -LiteralPath $target).Length)`t$hash`n"
[IO.File]::WriteAllText((Join-Path $root 'release-resources/META-INF/stevedance-video-native.list'),$row,[Text.UTF8Encoding]::new($false))
Write-Output "Native decoder: $((Get-Item -LiteralPath $target).Length) bytes, SHA256 $hash"
