param(
    [Parameter(Mandatory)][string]$Tag,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-fA-F]{40}$')][string]$ExpectedCommit,
    [switch]$RequireApp,
    [string]$AssetDirectory = ''
)

# Read-only checks against the same anonymous first page used by installed apps.
$ErrorActionPreference = 'Stop'
$api = 'https://api.github.com/repos/shuiyeshanchuan/MotoNav'
$headers = @{ Accept = 'application/vnd.github+json'; 'User-Agent' = 'MotoNav-Android'; 'Cache-Control' = 'no-cache' }
function Read-PublicJson([string]$Url) {
    Invoke-RestMethod -Uri $Url -Headers $headers -TimeoutSec 30
}
$encodedTag = [Uri]::EscapeDataString($Tag)
$release = Read-PublicJson "$api/releases/tags/$encodedTag"
if ($release.draft -or $release.prerelease) { throw 'App self-update requires a public, non-prerelease entry.' }
$reference = Read-PublicJson "$api/git/ref/tags/$encodedTag"
$target = $reference.object
while ($target.type -eq 'tag') { $target = (Read-PublicJson "$api/git/tags/$($target.sha)").object }
if ($target.type -ne 'commit' -or $target.sha -ne $ExpectedCommit) {
    throw "Release tag targets $($target.sha), expected build commit $ExpectedCommit."
}
$firstPage = @(Read-PublicJson "$api/releases?per_page=30")
$position = -1
for ($i = 0; $i -lt $firstPage.Count; $i++) {
    if ($firstPage[$i].tag_name -eq $Tag) { $position = $i + 1; break }
}
if ($position -lt 1) { throw 'Release is missing from the anonymous first 30 entries; installed apps cannot discover it.' }
if ($RequireApp -and -not @($release.assets | Where-Object { $_.name -eq 'app-release.apk' -and $_.state -eq 'uploaded' }).Count) {
    throw 'Missing uploaded app-release.apk.'
}
if ($AssetDirectory) {
    $localFiles = @(Get-ChildItem -LiteralPath $AssetDirectory -File)
    if (-not $localFiles.Count) { throw 'Asset directory is empty.' }
    foreach ($file in $localFiles) {
        $remote = @($release.assets | Where-Object { $_.name -eq $file.Name })
        if ($remote.Count -ne 1 -or $remote[0].state -ne 'uploaded' -or $remote[0].size -ne $file.Length) {
            throw "Remote asset missing, incomplete, or wrong size: $($file.Name)"
        }
        $expectedDigest = 'sha256:' + (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($remote[0].digest -ne $expectedDigest) { throw "Remote SHA-256 mismatch: $($file.Name)" }
        Write-Host "PASS: asset size + SHA-256 $($file.Name)"
    }
}
Write-Host "PASS: $Tag -> $ExpectedCommit; anonymous release position $position; $($release.html_url)"
