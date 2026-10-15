param(
  [Parameter(Mandatory=$true)][string]$Existing,
  [Parameter(Mandatory=$true)][string]$Staged,
  [Parameter(Mandatory=$true)][string]$ExpectedSha,
  [Parameter(Mandatory=$true)][int]$ProcessId
)
$ErrorActionPreference = 'Stop'
$Log = Join-Path (Split-Path -Parent $Staged) 'install.log'
try {
    $deadline = (Get-Date).AddMinutes(2)
    while ((Get-Process -Id $ProcessId -ErrorAction SilentlyContinue) -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
    }
    if (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue) { throw 'Application did not exit' }
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $Staged).Hash -ine $ExpectedSha) { throw 'SHA256 mismatch' }
    $backup = "$Existing.nerqo-backup"
    if (Test-Path -LiteralPath $backup) { Remove-Item -LiteralPath $backup -Force }
    Move-Item -LiteralPath $Existing -Destination $backup
    try {
        Move-Item -LiteralPath $Staged -Destination $Existing
        Start-Process -FilePath $Existing
        # Keep the previous install until the next successful update.
    } catch {
        if (Test-Path -LiteralPath $Existing) { Remove-Item -LiteralPath $Existing -Force }
        Move-Item -LiteralPath $backup -Destination $Existing
        throw
    }
} catch {
    ('Update failed: ' + $_.Exception.Message) | Set-Content -Path $Log -Encoding UTF8
    exit 1
}
