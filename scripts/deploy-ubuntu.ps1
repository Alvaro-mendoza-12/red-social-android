#Requires -Version 5.1
<#
.SYNOPSIS
  Despliega el backend Ubuntu desde Windows sin copiar node_modules.

.DESCRIPTION
  - Solo OpenSSH de Windows (127.0.0.1:2222 de VirtualBox).
  - rsync de Git si existe; si no, tar de Windows (System32) + scp + extraccion remota.
  - NO usa tuberia tar|ssh (se cuelga porque ssh no puede pedir contrasena).
  - NO usa WSL (127.0.0.1 en WSL no es tu VM).

.EXAMPLE
  cd "d:\red social - copia"
  powershell -ExecutionPolicy Bypass -File .\scripts\deploy-ubuntu.ps1
#>

param(
    [string]$SshHost = "127.0.0.1",
    [int]$SshPort = 2222,
    [string]$SshUser = "alvaro",
    [string]$RemoteFallbackDir = "conectate-local-fallback",
    [string]$RemoteSignalingDir = "signaling-server",
    [string]$PublicBaseUrl = "",
    [switch]$SkipRepair,
    [switch]$SkipNpmInstall
)

$ErrorActionPreference = "Stop"

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$LocalFallbackSource = Join-Path $RepoRoot "infrastructure\local-fallback"
$SignalingSource = Join-Path $RepoRoot "signaling-server"

$ExcludeDirs = @(
    "node_modules", ".git", "dist", "build", ".gradle",
    ".cache", ".next", "coverage", "tmp", "logs"
)

$script:SshExe = $null
$script:ScpExe = $null
$script:GitUsrBin = $null
$script:Transport = $null

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Resolve-GitUsrBin {
    $dirs = [System.Collections.Generic.List[string]]::new()
    foreach ($root in @($env:ProgramFiles, ${env:ProgramFiles(x86)}, "$env:LocalAppData\Programs")) {
        if ($root) { $dirs.Add((Join-Path $root "Git\usr\bin")) }
    }
    $gitCmd = Get-Command git.exe -ErrorAction SilentlyContinue
    if ($gitCmd) {
        $gitRoot = Split-Path (Split-Path $gitCmd.Source -Parent) -Parent
        $dirs.Add((Join-Path $gitRoot "usr\bin"))
    }
    foreach ($dir in $dirs) {
        if (Test-Path (Join-Path $dir "tar.exe")) {
            return (Resolve-Path -LiteralPath $dir).Path
        }
    }
    return $null
}

function Resolve-OpenSshTool([string]$ToolName) {
    $winDir = Join-Path $env:WINDIR "System32\OpenSSH\$ToolName.exe"
    if (Test-Path -LiteralPath $winDir) { return (Resolve-Path -LiteralPath $winDir).Path }
    $cmd = Get-Command $ToolName.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    if ($script:GitUsrBin) {
        $gitTool = Join-Path $script:GitUsrBin "$ToolName.exe"
        if (Test-Path -LiteralPath $gitTool) { return (Resolve-Path -LiteralPath $gitTool).Path }
    }
    return $null
}

function Resolve-WindowsTar {
    foreach ($rel in @("System32\tar.exe", "Sysnative\tar.exe")) {
        $candidate = Join-Path $env:WINDIR $rel
        if (Test-Path -LiteralPath $candidate) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    $cmd = Get-Command tar.exe -ErrorAction SilentlyContinue
    if ($cmd -and $cmd.Source -notmatch '[\\/]Git[\\/]') {
        return $cmd.Source
    }
    return $null
}

function ConvertTo-MsysPath([string]$Path) {
    $full = [System.IO.Path]::GetFullPath($Path)
    if ($full -match '^([A-Za-z]):\\(.*)$') {
        $drive = $Matches[1].ToLower()
        $rest = $Matches[2] -replace '\\', '/'
        return "/$drive/$rest"
    }
    return ($full -replace '\\', '/')
}

function Resolve-Transport {
    if ($script:GitUsrBin) {
        $rsync = Join-Path $script:GitUsrBin "rsync.exe"
        if (Test-Path -LiteralPath $rsync) {
            $gitTar = Join-Path $script:GitUsrBin "tar.exe"
            return @{
                Name     = "git-rsync"
                Rsync    = (Resolve-Path -LiteralPath $rsync).Path
                Tar      = $(if (Test-Path -LiteralPath $gitTar) { (Resolve-Path -LiteralPath $gitTar).Path } else { $null })
                UseGzip  = $true
            }
        }
    }

    $winTar = Resolve-WindowsTar
    if ($winTar) {
        return @{
            Name    = "win-tar-scp"
            Rsync   = $null
            Tar     = $winTar
            UseGzip = $true
        }
    }

    if ($script:GitUsrBin) {
        $gitTar = Join-Path $script:GitUsrBin "tar.exe"
        if (Test-Path -LiteralPath $gitTar) {
            $gzip = Join-Path $script:GitUsrBin "gzip.exe"
            return @{
                Name    = "git-tar-scp"
                Rsync   = $null
                Tar     = (Resolve-Path -LiteralPath $gitTar).Path
                UseGzip = (Test-Path -LiteralPath $gzip)
            }
        }
    }

    throw @"
No hay herramienta para empaquetar archivos.
Windows 10/11 trae tar.exe en System32, o instala Git for Windows.
"@
}

function Test-SshKeyAuth {
    # BatchMode falla sin clave; ssh escribe en stderr y con Stop PowerShell aborta si no lo suprimimos.
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    try {
        $null = & $script:SshExe -p $SshPort -o BatchMode=yes -o ConnectTimeout=5 -o StrictHostKeyChecking=accept-new "${SshUser}@${SshHost}" "exit 0" 2>&1
        return $LASTEXITCODE -eq 0
    } finally {
        $ErrorActionPreference = $prevEap
    }
}

function Show-SshPasswordHint {
    if (Test-SshKeyAuth) {
        Write-Host "SSH: clave configurada (no pedira contrasena en cada paso)." -ForegroundColor DarkGreen
        return
    }
    Write-Host ""
    Write-Host "AVISO: Sin clave SSH te pedira contrasena varias veces." -ForegroundColor Yellow
    Write-Host "Para configurarla una sola vez (recomendado), en PowerShell:" -ForegroundColor Yellow
    Write-Host "  ssh-keygen -t ed25519 -N `"`" -f `"$env:USERPROFILE\.ssh\id_ed25519`"" -ForegroundColor Gray
    Write-Host "  type `"$env:USERPROFILE\.ssh\id_ed25519.pub`" | ssh -p $SshPort ${SshUser}@${SshHost} `"mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys`"" -ForegroundColor Gray
    Write-Host ""
}

function Invoke-Ssh([string]$RemoteCommand) {
    $remote = ($RemoteCommand -replace "`r`n", "`n" -split "`n" |
        ForEach-Object { $_.Trim() } | Where-Object { $_ } |
        ForEach-Object { if ($_ -match ';$') { $_ } else { "$_;" } }) -join ' '
    $remote = $remote.TrimEnd(';').Trim()

    Write-Host "    ssh: $remote" -ForegroundColor DarkGray
    & $script:SshExe -p $SshPort -o StrictHostKeyChecking=accept-new "${SshUser}@${SshHost}" $remote
    if ($LASTEXITCODE -ne 0) { throw "SSH fallo (codigo $LASTEXITCODE)" }
}

function Invoke-Scp([string]$LocalFile, [string]$RemoteFile) {
    Write-Host "    scp -> $RemoteFile" -ForegroundColor DarkGray
    & $script:ScpExe -P $SshPort -o StrictHostKeyChecking=accept-new $LocalFile "${SshUser}@${SshHost}:$RemoteFile"
    if ($LASTEXITCODE -ne 0) { throw "SCP fallo (codigo $LASTEXITCODE)" }
}

function Get-RsyncExcludeArgs {
    $list = New-Object System.Collections.Generic.List[string]
    foreach ($name in $ExcludeDirs) {
        $list.Add("--exclude"); $list.Add($name)
    }
    return $list
}

function Sync-DirectoryRsync {
    param([string]$SourceDir, [string]$RemotePath)

    $source = ((Resolve-Path -LiteralPath $SourceDir).Path -replace '\\', '/') + '/'
    $target = "${SshUser}@${SshHost}:${RemotePath}/"
    $sshShell = "ssh -p $SshPort -o StrictHostKeyChecking=accept-new"

    Write-Host "    origen:  $source"
    Write-Host "    destino: $target"
    Write-Host "    (rsync muestra progreso; puede tardar varios minutos)" -ForegroundColor DarkGray

    $args = @("-avz", "--progress", "--delete") + (Get-RsyncExcludeArgs) + @(
        "-e", $sshShell, $source, $target
    )
    & $script:Transport.Rsync @args
    if ($LASTEXITCODE -ne 0) { throw "rsync fallo (codigo $LASTEXITCODE)" }
}

function Sync-DirectoryArchive {
    param(
        [string]$SourceDir,
        [string]$RemotePath,
        [string]$Label
    )

    $source = (Resolve-Path -LiteralPath $SourceDir).Path
    $stamp = Get-Date -Format "yyyyMMddHHmmss"
    $useGzip = $script:Transport.UseGzip -ne $false
    $archiveExt = if ($useGzip) { ".tar.gz" } else { ".tar" }
    $archiveName = "conectate-$Label-$stamp$archiveExt"
    $archiveLocal = Join-Path $env:TEMP $archiveName
    $remoteArchive = "/tmp/$archiveName"
    $tarCreate = if ($useGzip) { "-czf" } else { "-cf" }
    $tarExtract = if ($useGzip) { "-xzf" } else { "-xf" }

    if (Test-Path -LiteralPath $archiveLocal) {
        Remove-Item -LiteralPath $archiveLocal -Force
    }

    Write-Host "    [1/3] Comprimiendo en Windows (sin node_modules)..." -ForegroundColor DarkGray
    Write-Host "    tar: $($script:Transport.Tar)" -ForegroundColor DarkGray

    $tarArgs = @($tarCreate)
    if ($script:Transport.Name -eq "git-tar-scp") {
        if ($script:GitUsrBin) {
            $env:PATH = "$script:GitUsrBin;$env:PATH"
        }
        $tarArgs += ConvertTo-MsysPath $archiveLocal
        foreach ($name in $ExcludeDirs) { $tarArgs += "--exclude=$name" }
        $tarArgs += @("-C", (ConvertTo-MsysPath $source), ".")
    } else {
        $tarArgs += $archiveLocal
        foreach ($name in $ExcludeDirs) { $tarArgs += "--exclude=$name" }
        $tarArgs += @("-C", $source, ".")
    }

    & $script:Transport.Tar @tarArgs
    if ($LASTEXITCODE -ne 0) { throw "tar local fallo (codigo $LASTEXITCODE)" }
    if (-not (Test-Path -LiteralPath $archiveLocal)) {
        throw "No se creo el archivo: $archiveLocal"
    }

    $sizeMb = [math]::Round((Get-Item -LiteralPath $archiveLocal).Length / 1MB, 1)
    Write-Host "    [2/3] Subiendo ${sizeMb} MB con scp (pedira contrasena si no tienes clave SSH)..." -ForegroundColor DarkGray
    Invoke-Scp -LocalFile $archiveLocal -RemoteFile $remoteArchive

    Write-Host "    [3/3] Extrayendo en Ubuntu..." -ForegroundColor DarkGray
    $extract = "mkdir -p $RemotePath && cd $RemotePath && find . -mindepth 1 -maxdepth 1 ! -name '.env' -exec rm -rf {} + 2>/dev/null || true && tar $tarExtract $remoteArchive -C . && rm -f $remoteArchive && echo Extraido OK"
    Invoke-Ssh $extract

    Remove-Item -LiteralPath $archiveLocal -Force -ErrorAction SilentlyContinue
    Write-Host "    Listo: $Label" -ForegroundColor DarkGreen
}

function Sync-Directory {
    param([string]$SourceDir, [string]$RemotePath, [string]$Label)

    if ($script:Transport.Name -eq "git-rsync") {
        Sync-DirectoryRsync -SourceDir $SourceDir -RemotePath $RemotePath
    } else {
        Sync-DirectoryArchive -SourceDir $SourceDir -RemotePath $RemotePath -Label $Label
    }
}

function Install-RemoteNpmInDir([string]$RemoteDir) {
    Write-Host "    npm en $RemoteDir"
    Invoke-Ssh "test -f $RemoteDir/package.json || exit 0; cd $RemoteDir && rm -rf node_modules && if [ -f package-lock.json ]; then npm ci --omit=dev 2>/dev/null || npm ci; else npm install --omit=dev 2>/dev/null || npm install; fi && echo OK"
}

function Install-RemoteNpmDependencies {
    param([string]$FallbackPath, [string]$SignalingPath)
    Install-RemoteNpmInDir "$FallbackPath/api"
    Install-RemoteNpmInDir "$FallbackPath/realtime-gateway"
    Install-RemoteNpmInDir "$SignalingPath"
}

# --- Main ---
Write-Host "Conectate - deploy Ubuntu" -ForegroundColor Green
Write-Host "Repo: $RepoRoot"

$script:GitUsrBin = Resolve-GitUsrBin
$script:SshExe = Resolve-OpenSshTool "ssh"
$script:ScpExe = Resolve-OpenSshTool "scp"
$script:Transport = Resolve-Transport

if (-not $script:SshExe) { throw "Falta ssh.exe (Cliente OpenSSH de Windows)." }
if (-not $script:ScpExe) { throw "Falta scp.exe (viene con Cliente OpenSSH de Windows)." }

Write-Host "SSH:       $script:SshExe"
Write-Host "SCP:       $script:ScpExe"
Write-Host "Destino:   ${SshUser}@${SshHost}:${SshPort}"
Write-Host "Transport: $($script:Transport.Name)"

Show-SshPasswordHint

Write-Step "Comprobando SSH..."
Invoke-Ssh "echo SSH OK"

$remoteFallback = "~/$RemoteFallbackDir"
$remoteSignaling = "~/$RemoteSignalingDir"

Write-Step "Preparando carpetas y limpiando node_modules (un solo SSH)..."
$prep = @"
mkdir -p $remoteFallback $remoteSignaling
echo Limpiando node_modules...
rm -rf $remoteFallback/api/node_modules $remoteFallback/realtime-gateway/node_modules $remoteSignaling/node_modules 2>/dev/null || true
find $remoteFallback $remoteSignaling -type d -name node_modules -prune -exec rm -rf {} + 2>/dev/null || true
echo Limpieza terminada
"@
Invoke-Ssh $prep

Write-Step "Sincronizando local-fallback..."
Sync-Directory -SourceDir $LocalFallbackSource -RemotePath $remoteFallback -Label "fallback"

Write-Step "Sincronizando signaling-server..."
Sync-Directory -SourceDir $SignalingSource -RemotePath $remoteSignaling -Label "signaling"

if (-not $SkipNpmInstall) {
    Write-Step "Instalando npm en Ubuntu..."
    Install-RemoteNpmDependencies -FallbackPath $remoteFallback -SignalingPath $remoteSignaling
}

if (-not $SkipRepair) {
    Write-Step "Reiniciando Docker..."
    if ($PublicBaseUrl) {
        Invoke-Ssh "cd $remoteFallback && PUBLIC_BASE_URL='$PublicBaseUrl' bash scripts/repair-ubuntu-primary.sh"
    } else {
        Invoke-Ssh "cd $remoteFallback && bash scripts/repair-ubuntu-primary.sh"
    }
}

Write-Host ""
Write-Host "Listo." -ForegroundColor Green
Write-Host "  Siguiente: en Ubuntu -> ngrok http 3080"
