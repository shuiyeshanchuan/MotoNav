param(
  [string]$ApkPath = (Join-Path $PSScriptRoot 'app/build/outputs/apk/release/app-release.apk'),
  [string]$ExpectedSha1 = ''
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $ApkPath)) {
  throw "APK 不存在：$ApkPath"
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$buildToolsRoot = Join-Path $projectRoot '.tools/android-sdk/build-tools'
$buildTools = Get-ChildItem -LiteralPath $buildToolsRoot -Directory |
  Sort-Object Name -Descending | Select-Object -First 1
if (-not $buildTools) { throw '找不到 Android SDK Build Tools' }

$aapt2 = Join-Path $buildTools.FullName 'aapt2.exe'
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
if (-not (Test-Path -LiteralPath $aapt2) -or -not (Test-Path -LiteralPath $apksigner)) {
  throw "Build Tools 不完整：$($buildTools.FullName)"
}

if (-not $env:JAVA_HOME) {
  $java = Get-ChildItem -LiteralPath (Join-Path $projectRoot '.tools/jdk') -Filter 'java.exe' -Recurse -ErrorAction SilentlyContinue |
    Select-Object -First 1
  if ($java) { $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $java.FullName) }
}
if (-not $env:JAVA_HOME) { throw 'JAVA_HOME 未设置，无法运行 apksigner' }
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + ";$env:Path"

$badging = (& $aapt2 dump badging $ApkPath | Out-String)
$package = [regex]::Match($badging, "package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'")
if (-not $package.Success) { throw '无法读取 APK 包名和版本' }

$personalPath = Join-Path $PSScriptRoot 'personal.properties'
if (Test-Path -LiteralPath $personalPath) {
  $configured = Select-String -LiteralPath $personalPath -Pattern '^\s*personalApplicationId\s*=\s*(\S+)\s*$' |
    Select-Object -First 1
  if ($configured -and $package.Groups[1].Value -ne $configured.Matches[0].Groups[1].Value) {
    throw "APK 包名与 personal.properties 不一致：$($package.Groups[1].Value)"
  }
}

$certText = (& $apksigner verify --verbose --print-certs $ApkPath 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) { throw "APK 签名校验失败：$certText" }
$sha = [regex]::Match($certText, 'Signer #1 certificate SHA-1 digest:\s*([0-9A-Fa-f:]+)')
if (-not $sha.Success) { throw '无法读取 APK 证书 SHA1' }

$actualSha1 = $sha.Groups[1].Value.ToUpperInvariant()
$normalizedExpected = ($ExpectedSha1 -replace '[^0-9A-Fa-f]', '').ToUpperInvariant()
$normalizedActual = $actualSha1.Replace(':', '')
if ($normalizedExpected -and $normalizedExpected -ne $normalizedActual) {
  throw "APK SHA1 不匹配：实际 $actualSha1，期望 $ExpectedSha1"
}

Write-Host "PASS: package=$($package.Groups[1].Value) version=$($package.Groups[3].Value)" -ForegroundColor Green
Write-Host "Certificate SHA1: $actualSha1"
if ($normalizedExpected) { Write-Host 'Expected SHA1: MATCH' -ForegroundColor Green }
