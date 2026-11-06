# Install mika-envd on Windows.
#
# Builds the binary (or copies -Binary), creates a data directory and a token,
# and optionally registers a per-user scheduled task at logon.
# Run with -Help for all options.

[CmdletBinding()]
param(
    [string]$Binary = "",
    [string]$Prefix = "$env:LOCALAPPDATA\Mika\envd",
    [string]$Data = "$env:LOCALAPPDATA\Mika\envd\data",
    [string]$Listen = "127.0.0.1:8787",
    [string[]]$AllowRoot = @(),
    [switch]$ReadOnly,
    [switch]$Service,
    [switch]$NoStart,
    [switch]$Help
)

$ErrorActionPreference = "Stop"

function Show-Help {
    @'
Usage: install.ps1 [options]

Options:
  -Binary PATH      Use an existing mika-envd.exe instead of building.
  -Prefix DIR       Install directory (default: %LOCALAPPDATA%\Mika\envd).
  -Data DIR         Data directory (default: %LOCALAPPDATA%\Mika\envd\data).
  -Listen ADDR      Listen address (default: 127.0.0.1:8787).
  -AllowRoot DIR    Allow env_create to attach directories under DIR.
                    Repeatable: -AllowRoot C:\work -AllowRoot D:\data.
  -ReadOnly         Disable exec, writes, create, clone and delete.
  -Service          Register and start a per-user scheduled task at logon.
  -NoStart          Register the task but do not start it.
  -Help             Show this help.

Examples:
  pwsh -File install.ps1 -AllowRoot C:\work
  pwsh -File install.ps1 -Service
  pwsh -File install.ps1 -Binary .\mika-envd.exe -NoStart
'@
}

function Quote-Arg([string]$Value) {
    if ($Value -match '[\s"]') {
        '"' + ($Value -replace '"', '\"') + '"'
    } else {
        $Value
    }
}

if ($Help) {
    Show-Help
    exit 0
}

$Prefix = [IO.Path]::GetFullPath($Prefix)
$Data = [IO.Path]::GetFullPath($Data)

$envdSrc = Split-Path -Parent $PSScriptRoot
$exe = Join-Path $Prefix "mika-envd.exe"
$builtTemp = $null

if (-not $Binary) {
    $go = Get-Command go -ErrorAction SilentlyContinue
    if (-not $go) {
        throw "Go is required to build mika-envd; pass -Binary or install Go."
    }
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("mika-envd-" + [Guid]::NewGuid().ToString("N") + ".exe")
    Write-Host "Building mika-envd..."
    Push-Location $envdSrc
    try {
        $env:CGO_ENABLED = "0"
        & go build -trimpath -o $tmp .
        if ($LASTEXITCODE -ne 0) { throw "go build failed" }
    } finally {
        Pop-Location
        Remove-Item Env:\CGO_ENABLED -ErrorAction SilentlyContinue
    }
    $Binary = $tmp
    $builtTemp = $tmp
}

if (-not (Test-Path -LiteralPath $Binary -PathType Leaf)) {
    throw "Binary not found: $Binary"
}

New-Item -ItemType Directory -Force -Path $Prefix, $Data | Out-Null
if (-not [string]::Equals((Resolve-Path -LiteralPath $Binary).ProviderPath, $exe, [StringComparison]::OrdinalIgnoreCase)) {
    Copy-Item -LiteralPath $Binary -Destination $exe -Force
}
if ($builtTemp) {
    Remove-Item -LiteralPath $builtTemp -Force -ErrorAction SilentlyContinue
}

$tokenFile = Join-Path $Data "token"
if (-not (Test-Path -LiteralPath $tokenFile) -or (Get-Item -LiteralPath $tokenFile).Length -lt 32) {
    $bytes = New-Object byte[] 48
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    } finally {
        $rng.Dispose()
    }
    [IO.File]::WriteAllText($tokenFile, [Convert]::ToBase64String($bytes))
    try {
        & icacls $tokenFile /inheritance:r /grant:r "$($env:USERNAME):(R,W)" | Out-Null
    } catch {
        Write-Warning "Could not tighten ACLs on $tokenFile; keep the profile directory private."
    }
}

$argsList = @("-listen", $Listen, "-data", $Data, "-token-file", $tokenFile)
if ($ReadOnly) { $argsList += "-read-only" }
foreach ($root in $AllowRoot) {
    $full = (Resolve-Path -LiteralPath $root -ErrorAction Stop).ProviderPath
    if (-not (Test-Path -LiteralPath $full -PathType Container)) {
        throw "AllowRoot is not a directory: $full"
    }
    $argsList += @("-allow-root", $full)
}
$argLine = ($argsList | ForEach-Object { Quote-Arg $_ }) -join " "

if ($Listen -notmatch '^(127\.0\.0\.1|localhost|\[::1\]):') {
    Write-Warning "Listening on $Listen is not loopback; put a TLS reverse proxy in front and keep the token private."
}

if ($Service) {
    $action = New-ScheduledTaskAction -Execute $exe -Argument $argLine
    $trigger = New-ScheduledTaskTrigger -AtLogOn
    $settings = New-ScheduledTaskSettingsSet `
        -AllowStartIfOnBatteries `
        -DontStopIfGoingOnBatteries `
        -RestartCount 3 `
        -RestartInterval (New-TimeSpan -Minutes 1)
    try {
        Register-ScheduledTask -TaskName "Mika envd" -Action $action -Trigger $trigger `
            -Settings $settings -Description "Mika remote execution server" -Force | Out-Null
        if (-not $NoStart) {
            Start-ScheduledTask -TaskName "Mika envd"
            Write-Host "Scheduled task started: Mika envd"
        } else {
            Write-Host "Scheduled task registered: Mika envd"
        }
    } catch {
        Write-Warning "Could not register the scheduled task: $($_.Exception.Message)"
        Write-Host "Run it manually with:"
        Write-Host "  `"$exe`" $argLine"
    }
} else {
    Write-Host "Run it with:"
    Write-Host "  `"$exe`" $argLine"
}

Write-Host ""
Write-Host "Mika envd is ready."
Write-Host "  binary:     $exe"
Write-Host "  data:       $Data"
Write-Host "  token file: $tokenFile"
Write-Host "  MCP URL:    http://$Listen/mcp"
if ($argsList -contains "-allow-root") {
    Write-Host "  allow roots:"
    for ($i = 0; $i -lt $argsList.Count - 1; $i++) {
        if ($argsList[$i] -eq "-allow-root") { Write-Host "    $($argsList[$i + 1])" }
    }
} else {
    Write-Host "  allow roots: none (env_create only creates managed workspaces)"
}
Write-Host ""
Write-Host "Add the URL and the token file contents in Mika: Settings > MCP servers."
Write-Host "Windows support is best-effort: no -exec-user, incomplete Unix permissions."
