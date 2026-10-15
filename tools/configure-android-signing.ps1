param([Parameter(Mandatory=$true)][string]$KeystorePath)
$ErrorActionPreference = 'Stop'
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw 'GitHub CLI (gh) is required. Login with gh auth login.' }
if (-not (Test-Path -LiteralPath $KeystorePath)) { throw 'Keystore not found.' }
$repo='R3Dzf/Nerqo'
$alias='nerqo-release'
$secure=Read-Host 'Permanent Nerqo Android keystore password' -AsSecureString
$ptr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
  $pw=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
  $encoded=[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $KeystorePath)))
  $encoded | gh secret set NERQO_ANDROID_KEYSTORE_BASE64 --repo $repo
  if ($LASTEXITCODE -ne 0) { throw 'Unable to set keystore secret' }
  $pw | gh secret set NERQO_ANDROID_STORE_PASSWORD --repo $repo
  if ($LASTEXITCODE -ne 0) { throw 'Unable to set store password' }
  $alias | gh secret set NERQO_ANDROID_KEY_ALIAS --repo $repo
  if ($LASTEXITCODE -ne 0) { throw 'Unable to set alias' }
  $pw | gh secret set NERQO_ANDROID_KEY_PASSWORD --repo $repo
  if ($LASTEXITCODE -ne 0) { throw 'Unable to set key password' }
  Write-Host 'Permanent Android signing credentials configured. Keep your original keystore and recovery record safe.'
} finally {
  [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
  Remove-Variable pw,encoded -ErrorAction SilentlyContinue
}
