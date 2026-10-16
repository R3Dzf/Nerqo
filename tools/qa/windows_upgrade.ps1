param(
    [Parameter(Mandatory=$true)][string]$OldInstaller,
    [Parameter(Mandatory=$true)][string]$NewInstaller
)
$ErrorActionPreference = 'Stop'
$installPath = Join-Path $env:LOCALAPPDATA 'Programs\Nerqo-Upgrade-Smoke'
$dataRoot = Join-Path $env:LOCALAPPDATA 'Nerqo'
$appPath = Join-Path $installPath 'Nerqo.exe'
$appProcess = $null
function Install-Nerqo([string]$installer, [switch]$ReusePrevious) {
    $resolved = (Resolve-Path -LiteralPath $installer).Path
    $installerArguments = "/SP- /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /NORESTARTAPPLICATIONS"
    if (-not $ReusePrevious) { $installerArguments += " /DIR=`"$installPath`"" }
    Write-Host "Installing: $resolved"
    $setup = Start-Process -FilePath $resolved -ArgumentList $installerArguments -PassThru
    if (-not $setup.WaitForExit(120000)) {
        $setup.Kill()
        throw 'Native setup did not finish within two minutes'
    }
    if ($setup.ExitCode -ne 0) { throw "Installer failed: $($setup.ExitCode)" }
}
function Start-Nerqo {
    Write-Host "Starting installed app: $appPath"
    $script:appProcess = Start-Process -FilePath $appPath -ArgumentList '--upgrade-smoke' -PassThru
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        if ($script:appProcess.HasExited) { throw 'Installed native app exited unexpectedly' }
        try {
            $status = Invoke-RestMethod 'https://127.0.0.1:44888/api/status' -SkipCertificateCheck -TimeoutSec 2
            return $status
        } catch { Start-Sleep -Milliseconds 500 }
    }
    throw 'Installed native receiver did not become ready'
}
function Stop-Nerqo {
    if ($null -ne $script:appProcess -and -not $script:appProcess.HasExited) {
        $script:appProcess.Kill()
        $script:appProcess.WaitForExit()
    }
}
try {
    Install-Nerqo $OldInstaller
    $old = Start-Nerqo
    if ($old.version -ne '1.0.8') { throw 'Wrong old application installed' }
    Stop-Nerqo
    # Isolated GitHub Windows runner: synthetic state, no real user's data.
    $token = 'upgrade-smoke-only-token'
    $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($token))).ToLowerInvariant()
    $id = [Guid]::NewGuid().ToString('N')
    $state = @{
        Peers = @{ $hash = @{ Name = 'Upgrade smoke phone'; Expires = [DateTimeOffset]::UtcNow.AddDays(30).ToString('O') } }
        Uploads = @{}; Folders = @{}; OutgoingFolders = @{}
        Items = @(@{ Id = $id; Kind = 'text'; Sender = 'Android'; Content = 'Preserved across the native upgrade'; Size = 0; Sha256 = ''; Created = [DateTimeOffset]::UtcNow.ToString('O') })
    }
    $state | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $dataRoot 'state.json') -Encoding utf8NoBOM
    $sentinel = Join-Path $dataRoot 'Received\upgrade-sentinel.txt'
    'Nerqo upgrade preservation fixture' | Set-Content -LiteralPath $sentinel
    $identity = Get-FileHash (Join-Path $dataRoot 'lan-identity.dpapi')
    $saved = Get-FileHash (Join-Path $dataRoot 'state.json')
    $received = Get-FileHash $sentinel
    $headers = @{ Authorization = "Bearer $token" }
    $null = Start-Nerqo
    $before = Invoke-RestMethod 'https://127.0.0.1:44888/api/items' -Headers $headers -SkipCertificateCheck
    if ($before.items[0].id -ne $id) { throw 'Old app did not load the fixture' }
    # Upgrade with the actual old receiver still running; Restart Manager must close it.
    $oldProcess = $script:appProcess
    $oldProgram = (Get-FileHash $appPath).Hash
    Install-Nerqo $NewInstaller -ReusePrevious
    if (-not $oldProcess.WaitForExit(10000)) { throw 'Upgrade left the old receiver running' }
    if ((Get-FileHash $appPath).Hash -eq $oldProgram) { throw 'Old executable was not replaced' }
    $installedEntry = @(Get-ChildItem 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall' | Where-Object {
        $_.PSChildName -eq '{B36DB0B6-9931-4E92-8EDA-60C5E75F91C1}_is1'
    })
    if ($installedEntry.Count -ne 1) { throw 'Expected exactly one Nerqo installation entry' }
    $installedDetails = Get-ItemProperty $installedEntry[0].PSPath
    if ($installedDetails.DisplayVersion -ne '1.0.9') { throw 'Old installed-app version remains registered' }
    $current = Start-Nerqo
    if ($current.version -ne '1.0.9') { throw 'Installed app did not upgrade to 1.0.9' }
    $cachedInstaller = Join-Path $dataRoot 'Updates\Nerqo-Windows-Setup-1.0.9.exe'
    if (-not (Test-Path $cachedInstaller) -or (Get-FileHash $cachedInstaller).Hash -ne (Get-FileHash $NewInstaller).Hash) {
        throw 'Current installer was not cached byte-for-byte for the next delta update'
    }
    $after = Invoke-RestMethod 'https://127.0.0.1:44888/api/items' -Headers $headers -SkipCertificateCheck
    if ($after.items[0].id -ne $id) { throw 'Shared note or paired token lost during upgrade' }
    foreach ($fixture in @($identity, $saved, $received)) {
        if ((Get-FileHash -LiteralPath $fixture.Path).Hash -ne $fixture.Hash) { throw "Upgrade changed preserved data: $($fixture.Path)" }
    }
    New-Item -ItemType Directory -Force dist/qa | Out-Null
    'PASS: per-user installer 1.0.8 -> 1.0.9; running old process closed; executable replaced; one installed-app entry; installed native receiver; persistent TLS identity; paired token; shared note; received file preserved.' | Set-Content dist/qa/windows-upgrade.txt
    Get-Content dist/qa/windows-upgrade.txt
} finally { Stop-Nerqo }
