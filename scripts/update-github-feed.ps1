param(
    [string]$OutputPath = '',
    [string]$ExistingFeedPath = '',
    [string]$IndexCacheDirectory = ''
)

<#
Generate the small public update catalog without downloading APKs or firmware.
  ./scripts/update-github-feed.ps1
  ./scripts/update-github-feed.ps1 -OutputPath .tools/tmp/latest-feed.json
Commit/push updates/latest.json on 175c-ota-one-click after publishing assets,
then run verify-github-release.ps1 -Tag ... -ExpectedCommit ... -RequireApp -RequireFeed.
Failures leave the existing catalog untouched; a network failure is never an empty channel.
#>
$ErrorActionPreference = 'Stop'
$repository = 'shuiyeshanchuan/MotoNav'
$api = "https://api.github.com/repos/$repository"
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if (-not $OutputPath) { $OutputPath = Join-Path $projectRoot 'updates/latest.json' }
$OutputPath = [IO.Path]::GetFullPath($OutputPath)
if (-not $ExistingFeedPath) {
    $ExistingFeedPath = if (Test-Path -LiteralPath $OutputPath) { $OutputPath }
        else { Join-Path $projectRoot 'updates/latest.json' }
}
if (-not $IndexCacheDirectory) { $IndexCacheDirectory = Join-Path $projectRoot '.tools/tmp/github-update-index-cache' }

function Read-PublicBytes([string]$Url, [string]$Accept = 'application/vnd.github+json') {
    $request = @{
        Uri = $Url; TimeoutSec = 20; UseBasicParsing = $true; MaximumRedirection = 5
        Headers = @{ Accept = $Accept; 'User-Agent' = 'MotoNav-Update-Feed'; 'Cache-Control' = 'no-cache' }
    }
    $proxyUrl = if ($env:HTTPS_PROXY) { $env:HTTPS_PROXY } else { $env:HTTP_PROXY }
    if ($proxyUrl) { $request.Proxy = $proxyUrl }
    $response = Invoke-WebRequest @request
    $bytes = if ($response.PSObject.Properties['RawContentStream'] -and $response.RawContentStream -is [IO.MemoryStream]) {
        $response.RawContentStream.ToArray()
    } elseif ($response.Content -is [byte[]]) { $response.Content }
    else { [Text.Encoding]::UTF8.GetBytes([string]$response.Content) }
    if ($bytes.Length -gt 2MB) { throw "Response exceeds 2 MiB: $Url" }
    return ,([byte[]]$bytes)
}

function Read-PublicJson([string]$Url, [string]$Accept = 'application/vnd.github+json') {
    return ConvertFrom-Json -InputObject ([Text.Encoding]::UTF8.GetString((Read-PublicBytes $Url $Accept)))
}

function Find-Asset($Release, [string]$Name) {
    $matches = @($Release.assets | Where-Object { $_.name -eq $Name -and $_.state -eq 'uploaded' })
    if ($matches.Count -ne 1) { return $null }
    $asset = $matches[0]
    $prefix = "https://github.com/$repository/releases/download/$($Release.tag_name)/"
    if ($Name -notmatch '^[A-Za-z0-9._-]{1,180}$' -or
        $asset.browser_download_url -cne ($prefix + $Name) -or
        $asset.url -notmatch ('^' + [regex]::Escape($api) + '/releases/assets/[0-9]+$')) { return $null }
    return $asset
}

function Read-Index($Asset) {
    # Avoid the github.com/releases/download gateway, which may return 504 even
    # while the official API asset -> release-assets route succeeds.
    if ([long]$Asset.size -le 0 -or [long]$Asset.size -gt 256KB) { throw "Invalid index size: $($Asset.name)" }
    $expected = if ($Asset.digest -match '^sha256:([0-9a-fA-F]{64})$') { $Matches[1].ToLowerInvariant() } else { '' }
    $cacheFile = if ($expected) { Join-Path $IndexCacheDirectory ("sha256-$expected.json") } else { '' }
    if ($cacheFile -and (Test-Path -LiteralPath $cacheFile) -and
        (Get-Item -LiteralPath $cacheFile).Length -eq [long]$Asset.size -and
        (Get-FileHash -LiteralPath $cacheFile -Algorithm SHA256).Hash.ToLowerInvariant() -eq $expected) {
        return ConvertFrom-Json -InputObject ([IO.File]::ReadAllText($cacheFile))
    }
    $bytes = Read-PublicBytes $Asset.url 'application/octet-stream'
    if ($bytes.Length -ne [long]$Asset.size) { throw "Index size does not match public metadata: $($Asset.name)" }
    if ($expected) {
        $hasher = [Security.Cryptography.SHA256]::Create()
        try { $actual = ([BitConverter]::ToString($hasher.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant() }
        finally { $hasher.Dispose() }
        if ($actual -cne $expected) { throw "Index SHA-256 does not match public metadata: $($Asset.name)" }
    }
    $index = ConvertFrom-Json -InputObject ([Text.Encoding]::UTF8.GetString($bytes))
    if ($cacheFile) {
        try {
            New-Item -ItemType Directory -Path $IndexCacheDirectory -Force | Out-Null
            [IO.File]::WriteAllBytes($cacheFile, $bytes)
        } catch { Write-Warning "Cannot cache index; catalog generation continues: $($_.Exception.Message)" }
    }
    return $index
}

function Is-Diagnostic([string]$Version, [string]$Manifest, [string]$Image) {
    return "$Version $Manifest $Image" -match '(?i)imu-test|imu-diagnostic|ride-info'
}

function Published-At($Release) {
    # New PowerShell versions deserialize ISO dates as DateTime; string casts
    # would emit culture-dependent MM/dd/yyyy and break chronological ordering.
    if ($Release.published_at -is [DateTime]) { return $Release.published_at.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ') }
    return ([DateTimeOffset]::Parse([string]$Release.published_at, [Globalization.CultureInfo]::InvariantCulture)).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
}

function Make-Firmware($Release, [string]$Board, [string]$Version, [string]$Channel, $Manifest, $Image) {
    return [ordered]@{
        board = $Board; version = $Version; channel = $Channel
        manifestUrl = $Manifest.browser_download_url; imageUrl = $Image.browser_download_url
        manifestApiUrl = $Manifest.url; imageApiUrl = $Image.url
        releaseName = if ($Release.name) { [string]$Release.name } else { [string]$Release.tag_name }
        notes = ([string]$Release.body).Trim().Substring(0, [Math]::Min(1800, ([string]$Release.body).Trim().Length))
        prerelease = [bool]$Release.prerelease; publishedAt = Published-At $Release
    }
}

$releases = @(Read-PublicJson "$api/releases?per_page=30" | Where-Object { -not $_.draft } |
    Sort-Object -Property @{ Expression = { Published-At $_ }; Descending = $true })
if (-not $releases.Count) { throw 'No public releases returned; refusing to replace the update feed.' }
$firmwares = @{}
$application = $null
$indexRequests = 0
foreach ($release in $releases) {
    if ($release.tag_name -notmatch '^[A-Za-z0-9._-]+$') { continue }
    if (-not $release.prerelease -and $release.tag_name -match '^motonav-(v[0-9]+\.[0-9]+\.[0-9]+)$') {
        $appVersion = $Matches[1]
        $apk = Find-Asset $release 'app-release.apk'
        if ($apk -and [long]$apk.size -gt 0 -and [long]$apk.size -le 150MB -and
            (-not $application -or [Version]$appVersion.Substring(1) -gt [Version]$application.version.Substring(1))) {
            if ($apk.digest -notmatch '^sha256:([0-9a-fA-F]{64})$') { throw "APK SHA-256 is missing: $($release.tag_name)" }
            $application = [ordered]@{
                version = $appVersion; apkUrl = $apk.browser_download_url; apkApiUrl = $apk.url
                bytes = [long]$apk.size; sha256 = $Matches[1].ToLowerInvariant(); releaseTag = $release.tag_name
            }
        }
    }
    foreach ($channel in @('stable', 'diagnostic')) {
        if ($channel -eq 'stable' -and $release.prerelease) { continue }
        # Both supported boards are resolved: older indexes cannot change them.
        if ($firmwares.ContainsKey("175:$channel") -and $firmwares.ContainsKey("175C:$channel")) { continue }
        $asset = Find-Asset $release "motonav-ota-$channel.json"
        if (-not $asset) { continue }
        $indexRequests++
        try { $index = Read-Index $asset }
        catch { throw "Cannot read $($release.tag_name)/$($asset.name); previous feed preserved. $($_.Exception.Message)" }
        if ($index.schema -ne 1 -or $index.channel -cne $channel) { continue }
        foreach ($item in @($index.firmwares)) {
            $board = [string]$item.board
            if ($board -notin @('175', '175C') -or $firmwares.ContainsKey("${board}:$channel")) { continue }
            $version = [string]$item.version
            if (-not $version.StartsWith('v')) { $version = 'v' + $version }
            if ($version -notmatch '^v[A-Za-z0-9._-]{1,47}$') { continue }
            $manifest = Find-Asset $release ([string]$item.manifestAsset)
            $image = Find-Asset $release ([string]$item.imageAsset)
            if (-not $manifest -or -not $image -or $image.name -notlike '*_app_0x10000.bin') { continue }
            if ($channel -eq 'stable' -and (Is-Diagnostic $version $manifest.name $image.name)) { continue }
            $firmwares["${board}:$channel"] = Make-Firmware $release $board $version $channel $manifest $image
        }
    }
}
if (-not $application) { throw 'No public signed-APK metadata found in first 30 releases; previous feed preserved.' }

# A long run of App/diagnostic releases may push the stable release off page 1.
# Carry only a previously validated stable entry, and recheck the public release
# and both uploaded asset identities. API errors abort instead of deleting it.
if (Test-Path -LiteralPath $ExistingFeedPath) {
    $previous = Get-Content -LiteralPath $ExistingFeedPath -Raw | ConvertFrom-Json
    if ($previous.schema -ne 1) { throw 'Existing feed schema is invalid; refusing to discard it.' }
    foreach ($item in @($previous.firmwares)) {
        $board = [string]$item.board
        if ($item.channel -ne 'stable' -or $board -notin @('175', '175C') -or $firmwares.ContainsKey("${board}:stable")) { continue }
        if ([string]$item.version -notmatch '^v[A-Za-z0-9._-]{1,47}$') { throw 'Invalid previous stable version.' }
        $pattern = '^https://github\.com/' + [regex]::Escape($repository) + '/releases/download/([A-Za-z0-9._-]+)/([A-Za-z0-9._-]+)$'
        if ([string]$item.manifestUrl -notmatch $pattern) { throw 'Invalid previous stable manifest URL.' }
        $tag = $Matches[1]; $manifestName = $Matches[2]
        if ([string]$item.imageUrl -notmatch $pattern -or $Matches[1] -cne $tag) { throw 'Previous stable assets must share one release.' }
        $imageName = $Matches[2]
        if ($imageName -notlike '*_app_0x10000.bin' -or (Is-Diagnostic $item.version $manifestName $imageName)) { throw 'Previous stable entry is diagnostic or invalid.' }
        $savedRelease = Read-PublicJson "$api/releases/tags/$([Uri]::EscapeDataString($tag))"
        if ($savedRelease.draft -or $savedRelease.prerelease) { throw "Previous stable release is no longer publicly stable: $tag" }
        $manifest = Find-Asset $savedRelease $manifestName
        $image = Find-Asset $savedRelease $imageName
        if (-not $manifest -or -not $image) { throw "Previous stable assets are missing: $tag" }
        $firmwares["${board}:stable"] = Make-Firmware $savedRelease $board $item.version 'stable' $manifest $image
    }
}

$feed = [ordered]@{
    schema = 1; generatedAt = [DateTime]::UtcNow.ToString('o'); app = $application
    firmwares = @($firmwares.Values | Sort-Object -Property @{ Expression = { $_.publishedAt }; Descending = $true })
}
$json = $feed | ConvertTo-Json -Depth 8
if ([Text.Encoding]::UTF8.GetByteCount($json) -ge 128KB) { throw 'Generated feed exceeds the App 128 KiB limit; previous feed preserved.' }
$folder = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Path $folder -Force | Out-Null
$temporary = Join-Path $folder ('.latest-' + [Guid]::NewGuid().ToString('N') + '.tmp')
try {
    [IO.File]::WriteAllText($temporary, $json + [Environment]::NewLine, (New-Object Text.UTF8Encoding($false)))
    if (Test-Path -LiteralPath $OutputPath) { [IO.File]::Replace($temporary, $OutputPath, [System.Management.Automation.Language.NullString]::Value) }
    else { [IO.File]::Move($temporary, $OutputPath) }
} finally {
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary }
}
Write-Host "PASS: $OutputPath; app $($application.version); $($firmwares.Count) firmware entries; $indexRequests small indexes; no APK/bin downloads."
