param(
  [Parameter(Mandatory)][string]$ManifestPath,
  [string]$PublicKeyPath = 'D:\Configrition\MotoNav-ota-public.pem',
  [string]$ExpectedKeyId = '4b5022f6c387bbd0'
)

$ErrorActionPreference = 'Stop'
$manifestFile = Get-Item -LiteralPath $ManifestPath
if (-not (Test-Path -LiteralPath $PublicKeyPath)) { throw "找不到 OTA 公钥：$PublicKeyPath" }
$manifest = Get-Content -Raw -LiteralPath $manifestFile.FullName | ConvertFrom-Json
if ($manifest.board -ne 'Waveshare ESP32-S3-Touch-AMOLED-1.75C' -or $manifest.platform -ne 'universal' -or
    $manifest.flashSize -ne '32MB' -or $manifest.partition -ne 'app5M_fat24M_32MB' -or
    $manifest.verifiedApplicationOffset -ne '0x10000') {
  throw 'OTA manifest 不是受支持的 1.75C 应用构建'
}

$app = @($manifest.files | Where-Object { $_.file -like '*_app_0x10000.bin' })
if ($app.Count -ne 1) { throw 'manifest 中必须恰好有一个 OTA 应用镜像' }
$imagePath = Join-Path $manifestFile.DirectoryName $app[0].file
$image = [System.IO.File]::ReadAllBytes($imagePath)
if ($image.Length -ne [int64]$app[0].bytes) { throw 'OTA 应用镜像大小与 manifest 不一致' }
$sha = [Convert]::ToHexString([System.Security.Cryptography.SHA256]::HashData($image)).ToLowerInvariant()
if ($sha -ne $app[0].sha256.ToLowerInvariant()) { throw 'OTA 应用镜像 SHA-256 与 manifest 不一致' }

$signature = $manifest.otaSignature
if (-not $signature -or $signature.algorithm -ne 'RSA-SHA256-PKCS1v1.5' -or
    $signature.keyId -ne $ExpectedKeyId.ToLowerInvariant()) {
  throw 'OTA manifest 签名元数据不正确'
}
$signatureBytes = try { [Convert]::FromBase64String($signature.value) }
catch { throw 'OTA manifest 签名不是有效 Base64' }
if ($signatureBytes.Length -ne 256) { throw "OTA 签名长度错误：$($signatureBytes.Length)" }

$rsa = [System.Security.Cryptography.RSA]::Create()
try {
  $rsa.ImportFromPem([System.IO.File]::ReadAllText($PublicKeyPath))
  $actualKeyId = [Convert]::ToHexString([System.Security.Cryptography.SHA256]::HashData($rsa.ExportSubjectPublicKeyInfo())).ToLowerInvariant().Substring(0,16)
  if ($actualKeyId -ne $ExpectedKeyId.ToLowerInvariant()) { throw "OTA 公钥 ID 不匹配：$actualKeyId" }
  if (-not $rsa.VerifyData($image, $signatureBytes, [System.Security.Cryptography.HashAlgorithmName]::SHA256,
      [System.Security.Cryptography.RSASignaturePadding]::Pkcs1)) { throw 'OTA RSA 签名验签失败' }
} finally {
  $rsa.Dispose()
}

Write-Host "PASS: $($app[0].file) SHA-256 + RSA signature verified ($actualKeyId)" -ForegroundColor Green
