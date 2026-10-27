param(
  [Parameter(Mandatory)][string]$ManifestPath,
  [ValidateSet('stable')][string]$Channel = 'stable',
  [string]$OutPath = ''
)

$ErrorActionPreference = 'Stop'
$manifestFile = Get-Item -LiteralPath $ManifestPath
$manifest = Get-Content -Raw -LiteralPath $manifestFile.FullName | ConvertFrom-Json

if ($manifest.board -ne 'Waveshare ESP32-S3-Touch-AMOLED-1.75C' -or
    $manifest.flashSize -ne '32MB' -or $manifest.partition -ne 'app5M_fat24M_32MB' -or
    $manifest.verifiedApplicationOffset -ne '0x10000') {
  throw 'OTA 索引只接受已验证的 1.75C / 32MB / 0x10000 manifest'
}
if (-not $manifest.otaSignature -or $manifest.otaSignature.algorithm -ne 'RSA-SHA256-PKCS1v1.5' -or
    $manifest.otaSignature.keyId -notmatch '^[0-9a-f]{16}$') {
  throw '先使用 sign-ota.ps1 对 OTA manifest 签名'
}
$app = @($manifest.files | Where-Object { $_.file -match '_app_0x10000\.bin$' })
if ($app.Count -ne 1) { throw 'manifest 中必须恰好有一个 OTA 应用镜像' }
if (-not (Test-Path -LiteralPath (Join-Path $manifestFile.DirectoryName $app[0].file))) { throw 'OTA 应用镜像不在 manifest 同目录' }
if ($manifest.firmware -notmatch '^[A-Za-z0-9._-]{1,47}$') { throw '固件版本格式不安全' }

$index = [ordered]@{
  schema = 1
  channel = $Channel
  firmwares = @(
    [ordered]@{
      board = '175C'
      version = "v$($manifest.firmware.TrimStart('v'))"
      manifestAsset = $manifestFile.Name
      imageAsset = [string]$app[0].file
    }
  )
}
if (-not $OutPath) { $OutPath = Join-Path $manifestFile.DirectoryName "motonav-ota-$Channel.json" }
[IO.File]::WriteAllText($OutPath, ($index | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
Write-Host "PASS: wrote $(Get-Item -LiteralPath $OutPath | Select-Object -ExpandProperty FullName)" -ForegroundColor Green
