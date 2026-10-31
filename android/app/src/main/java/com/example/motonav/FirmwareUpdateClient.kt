package com.example.motonav

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads signed OTA candidates from MotoNav GitHub Releases.
 *
 * Each stable firmware release contains motonav-ota-stable.json. The index may
 * name only assets from that same GitHub release; the image remains protected
 * by the RSA signature verified by the round screen before it is installed.
 */
object FirmwareUpdateClient {
    private const val REPOSITORY = "shuiyeshanchuan/MotoNav"
    private const val INDEX_ASSET = "motonav-ota-stable.json"
    // The GitHub releases API includes metadata for every asset in every
    // release. It is larger than an individual OTA manifest and grows as
    // releases accumulate, so it needs its own bounded limit.
    private const val MAX_RELEASES_BYTES = 2 * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 256 * 1024
    private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000

    data class Candidate(
        val board: String,
        val version: String,
        val manifestUrl: String,
        val imageUrl: String,
        val releaseName: String,
        val notes: String
    )

    /** Returns the newest formal firmware candidate across all non-prerelease Releases.
     * GitHub's releases API is not guaranteed to return newly created tags first.
     * Diagnostic images are deliberately excluded from the ordinary stable update
     * path, even when a historical manifest incorrectly labels them stable. This
     * keeps a nav10 device from being offered the unverified nav11 test image.
     */
    fun check(board: String): Candidate {
        require(board == "175C" || board == "175") { "请先连接受支持的圆屏" }
        val releases = getJsonArray(
            "https://api.github.com/repos/$REPOSITORY/releases?per_page=30",
            MAX_RELEASES_BYTES
        )
        var newest: Candidate? = null
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            val assets = release.optJSONArray("assets") ?: continue
            val indexUrl = assetUrl(assets, INDEX_ASSET) ?: continue
            val index = try { JSONObject(String(download(indexUrl, MAX_MANIFEST_BYTES), Charsets.UTF_8)) }
            catch (_: Exception) { continue }
            if (index.optInt("schema") != 1 || index.optString("channel") != "stable") continue
            val items = index.optJSONArray("firmwares") ?: continue
            val item = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                .firstOrNull { it.optString("board") == board } ?: continue
            val version = safeVersion(item.optString("version"))
            val manifestName = safeAssetName(item.optString("manifestAsset"))
            val imageName = safeAssetName(item.optString("imageAsset"))
            if (!imageName.endsWith("_app_0x10000.bin")) continue
            if (isDiagnosticAsset(version, manifestName, imageName)) continue
            val manifestUrl = assetUrl(assets, manifestName) ?: continue
            val imageUrl = assetUrl(assets, imageName) ?: continue
            val candidate = Candidate(board, version, manifestUrl, imageUrl,
                release.optString("name").ifBlank { release.optString("tag_name", "MotoNav 固件") },
                release.optString("body").trim().take(1800))
            if (newest == null || compareVersions(candidate.version, newest.version) > 0) newest = candidate
        }
        return newest ?: error("GitHub 暂未发布适用于 $board 的稳定 OTA 固件")
    }

    /** Downloads the selected release and applies the same package checks as the offline picker. */
    fun downloadPackage(candidate: Candidate, progress: (String) -> Unit): OtaPackage {
        progress("正在下载固件清单…")
        val manifest = download(candidate.manifestUrl, MAX_MANIFEST_BYTES)
        progress("正在下载圆屏固件…")
        val image = download(candidate.imageUrl, MAX_IMAGE_BYTES) { read, total ->
            progress(if (total > 0) "正在下载圆屏固件：$read / $total 字节" else "正在下载圆屏固件：$read 字节")
        }
        val pkg = OtaPackage.read(manifest, image)
        require(pkg.board == candidate.board) { "下载的固件板型与更新信息不一致" }
        require(pkg.version == candidate.version) { "下载的固件版本与更新信息不一致" }
        return pkg
    }

    /**
     * Candidate may share a numeric release prefix but have a newer build label,
     * such as power.13 → power.14. The release channel is a build label rather
     * than a semver pre-release, so power/junction/nav builds with the same
     * numeric prefix are mutually upgradeable.
     */
    fun isNotOlder(candidate: String, current: String): Boolean {
        return current.isBlank() || compareVersions(candidate, current) >= 0
    }

    private fun compareVersions(left: String, right: String): Int {
        val l = versionParts(left); val r = versionParts(right)
        for (i in 0..2) if (l.base[i] != r.base[i]) return l.base[i].compareTo(r.base[i])
        if (l.tail == r.tail) return 0
        if (l.channel == r.channel && l.build != null && r.build != null)
            return l.build.compareTo(r.build)
        if (l.channel.isNotBlank() && r.channel.isNotBlank() && l.channel != r.channel)
            return 0
        return l.tail.compareTo(r.tail)
    }

    private data class VersionParts(val base: List<Int>, val channel: String, val build: Int?, val tail: String)

    private fun versionParts(value: String): VersionParts {
        val raw = value.removePrefix("v")
        val match = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(.*)$").matchEntire(raw)
            ?: return VersionParts(listOf(0, 0, 0), raw, null, raw)
        val tail = match.groupValues[4]
        // Build labels may have a suffix, for example nav.10-ride-info-imu-test.
        // Extract the numeric channel build before comparing; lexical ordering
        // would otherwise incorrectly place nav.10 before nav.9.
        val channelBuild = Regex("^[-._]([A-Za-z][A-Za-z0-9]*)\\.(\\d+)(.*)$").matchEntire(tail)
        val channel = channelBuild?.groupValues?.get(1)
            ?: tail.trimStart('.', '-', '_').substringBeforeLast('.', missingDelimiterValue = "")
        val build = channelBuild?.groupValues?.get(2)?.toIntOrNull()
        return VersionParts(listOf(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt()), channel, build, tail)
    }

    private fun safeVersion(value: String): String {
        val version = if (value.startsWith("v")) value else "v$value"
        require(Regex("v[A-Za-z0-9._-]{1,47}").matches(version)) { "更新索引中的固件版本无效" }
        return version
    }

    private fun safeAssetName(value: String): String {
        require(Regex("[A-Za-z0-9._-]{1,180}").matches(value)) { "更新索引中的文件名无效" }
        return value
    }

    /** Exposed for the release-policy regression test; this is not an OTA bypass. */
    fun isDiagnosticAssetName(version: String, manifestName: String, imageName: String): Boolean {
        val text = "$version $manifestName $imageName".lowercase()
        return "imu-test" in text || "imu-diagnostic" in text || "ride-info" in text
    }

    private fun isDiagnosticAsset(version: String, manifestName: String, imageName: String): Boolean =
        isDiagnosticAssetName(version, manifestName, imageName)

    private fun assetUrl(assets: JSONArray, name: String): String? {
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") != name) continue
            val url = asset.optString("browser_download_url")
            if (url.startsWith("https://github.com/$REPOSITORY/releases/download/")) return url
        }
        return null
    }

    private fun getJsonArray(url: String, maxBytes: Int): JSONArray =
        JSONArray(String(download(url, maxBytes), Charsets.UTF_8))

    private fun download(url: String, maxBytes: Int, progress: ((Int, Int) -> Unit)? = null): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "MotoNav-Android")
            connection.setRequestProperty("Cache-Control", "no-cache")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) error("GitHub 请求失败：HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(if (total in 1..maxBytes) total.toInt() else 16 * 1024)
                val buffer = ByteArray(16 * 1024)
                var count: Int
                var read = 0
                while (input.read(buffer).also { count = it } >= 0) {
                    read += count
                    if (read > maxBytes) {
                        error("下载文件超过允许大小（已读取 ${read} 字节，上限 ${maxBytes} 字节）")
                    }
                    output.write(buffer, 0, count)
                    progress?.invoke(read, if (total in 1..maxBytes) total.toInt() else -1)
                }
                return output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }
}
