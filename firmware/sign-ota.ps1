param(
  [Parameter(Mandatory)][string]$ManifestPath,
  [string]$PrivateKeyPath = 'D:\Configrition\MotoNav-ota-private.pem',
  [string]$ExpectedKeyId = '4b5022f6c387bbd0'
)

$ErrorActionPreference = 'Stop'
$manifestFile = Get-Item -LiteralPath $ManifestPath
if (-not (Test-Path -LiteralPath $PrivateKeyPath)) { throw "找不到 OTA 私钥：$PrivateKeyPath" }

$manifest = Get-Content -Raw -LiteralPath $manifestFile.FullName | ConvertFrom-Json
$expectedBoard = 'Waveshare ESP32-S3-Touch-AMOLED-1.75C'
if ($manifest.board -ne $expectedBoard -or $manifest.platform -ne 'universal' -or
    $manifest.partition -ne 'app5M_fat24M_32MB' -or $manifest.verifiedApplicationOffset -ne '0x10000') {
  throw '拒绝给非 1.75C OTA 构建签名'
}
$app = @($manifest.files | Where-Object { $_.file -like '*_app_0x10000.bin' })
if ($app.Count -ne 1) { throw 'manifest 中必须恰好有一个 OTA 应用镜像' }
$appPath = Join-Path $manifestFile.DirectoryName $app[0].file
if (-not (Test-Path -LiteralPath $appPath)) { throw "找不到 OTA 应用镜像：$appPath" }

$image = [System.IO.File]::ReadAllBytes($appPath)
if ($image.Length -ne [int64]$app[0].bytes) { throw 'OTA 应用镜像大小与 manifest 不一致' }
$actualHash = [Convert]::ToHexString([System.Security.Cryptography.SHA256]::HashData($image)).ToLowerInvariant()
if ($actualHash -ne $app[0].sha256.ToLowerInvariant()) { throw 'OTA 应用镜像 SHA-256 与 manifest 不一致' }

$rsa = [System.Security.Cryptography.RSA]::Create()
try {
  $rsa.ImportFromPem([System.IO.File]::ReadAllText($PrivateKeyPath))
  if ($rsa.KeySize -ne 2048) { throw "OTA 私钥必须为 RSA-2048，当前为 $($rsa.KeySize) 位" }
  $signature = $rsa.SignData($image, [System.Security.Cryptography.HashAlgorithmName]::SHA256,
    [System.Security.Cryptography.RSASignaturePadding]::Pkcs1)
  if ($signature.Length -ne 256) { throw "OTA 签名长度错误：$($signature.Length)" }
  if (-not $rsa.VerifyData($image, $signature, [System.Security.Cryptography.HashAlgorithmName]::SHA256,
      [System.Security.Cryptography.RSASignaturePadding]::Pkcs1)) { throw '本地 OTA 签名自检失败' }
  $keyId = [Convert]::ToHexString([System.Security.Cryptography.SHA256]::HashData($rsa.ExportSubjectPublicKeyInfo())).ToLowerInvariant().Substring(0,16)
  if ($keyId -ne $ExpectedKeyId.ToLowerInvariant()) { throw "OTA 私钥与固件公钥不匹配：$keyId" }
} finally {
  $rsa.Dispose()
}

$manifest | Add-Member -Force NoteProperty otaSignature ([ordered]@{
  algorithm = 'RSA-SHA256-PKCS1v1.5'
  keyId = $keyId
  value = [Convert]::ToBase64String($signature)
})
[System.IO.File]::WriteAllText($manifestFile.FullName, ($manifest | ConvertTo-Json -Depth 6), [System.Text.UTF8Encoding]::new($false))
Write-Host "PASS: signed $($app[0].file) with key $keyId" -ForegroundColor Green
