package com.example.motonav

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Reads signed OTA candidates from MotoNav GitHub Releases.
 *
 * Stable updates use motonav-ota-stable.json; explicitly requested diagnostic
 * updates also use motonav-ota-diagnostic.json. Each index may
 * name only assets from that same GitHub release; the image remains protected
 * by the RSA signature verified by the round screen before it is installed.
 */
object FirmwareUpdateClient {
    private const val REPOSITORY = "shuiyeshanchuan/MotoNav"
    private const val INDEX_ASSET = "motonav-ota-stable.json"
    private const val DIAGNOSTIC_INDEX_ASSET = "motonav-ota-diagnostic.json"
    private const val MAX_INDEX_REQUESTS = 3
    private const val MAX_MANIFEST_BYTES = 256 * 1024
    private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024

    data class Candidate @JvmOverloads constructor(
        val board: String,
        val version: String,
        val manifestUrl: String,
        val imageUrl: String,
        val releaseName: String,
        val notes: String,
        val diagnostic: Boolean = false,
        val manifestApiUrl: String = "",
        val imageApiUrl: String = ""
    )

    /** Returns the newest allowed firmware candidate across GitHub Releases.
     * GitHub's releases API is not guaranteed to return newly created tags first.
     * Diagnostic images are deliberately excluded from the ordinary stable update
     * path, even when a historical manifest incorrectly labels them stable.
     * Diagnostic candidates require an explicit request and a separate index;
     * ordinary update checks retain the existing stable-only behavior.
     */
    fun check(board: String, includeDiagnostic: Boolean = false): Candidate {
        require(board == "175C" || board == "175") { "请先连接受支持的圆屏" }
        val source = GitHubUpdateSource.session()
        val feedFailure = try {
            selectFeedCandidate(source.feed(), board, includeDiagnostic)?.let { return it }
            "更新目录未列出 $board 的${if (includeDiagnostic) "稳定或测试" else "稳定"}固件"
        } catch (e: Exception) { e.message ?: "更新目录读取失败" }
        // One shared deadline and request budget cover the feed, release list and
        // bounded index fallback; old releases cannot multiply 30s timeouts.
        val releases = try { source.releases() }
        catch (e: Exception) { error("固件检查失败：${e.message}；更新目录：$feedFailure") }
        val indexNames = if (includeDiagnostic) listOf(INDEX_ASSET, DIAGNOSTIC_INDEX_ASSET)
            else listOf(INDEX_ASSET)
        var newest: Candidate? = null
        var requested = 0
        // API order is not tag creation order. Keep a bounded metadata-only sort
        // so incomparable nav/power labels still retain newest publication order.
        val ordered = (0 until releases.length()).mapNotNull { releases.optJSONObject(it) }
            .sortedByDescending { it.optString("published_at").ifBlank { it.optString("created_at") } }
        for (release in ordered) {
            if (release.optBoolean("draft")) continue
            val assets = release.optJSONArray("assets") ?: continue
            for (indexName in indexNames) {
                // A prerelease can publish a diagnostic index, never a stable candidate.
                if (release.optBoolean("prerelease") && indexName == INDEX_ASSET) continue
                val indexUrl = assetUrl(assets, indexName) ?: continue
                if (requested >= MAX_INDEX_REQUESTS) break
                if (source.remainingRequests() <= 0 || source.remainingMs() <= 0) {
                    if (newest != null) return newest
                    error("固件检查时间或请求预算已用尽；更新目录：$feedFailure")
                }
                requested++
                val index = try { source.jsonObject(assetApiUrl(assets, indexName).ifBlank { indexUrl }, MAX_MANIFEST_BYTES) }
                catch (e: Exception) { error("固件索引 $indexName 读取失败：${e.message}；更新目录：$feedFailure") }
                if (!isIndexAllowed(indexName, index.optInt("schema"), index.optString("channel"),
                        includeDiagnostic, release.optBoolean("prerelease"))) continue
                val diagnostic = indexName == DIAGNOSTIC_INDEX_ASSET
                val items = index.optJSONArray("firmwares") ?: continue
                val item = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                    .firstOrNull { it.optString("board") == board } ?: continue
                val version = runCatching { safeVersion(item.optString("version")) }.getOrNull() ?: continue
                val manifestName = runCatching { safeAssetName(item.optString("manifestAsset")) }.getOrNull() ?: continue
                val imageName = runCatching { safeAssetName(item.optString("imageAsset")) }.getOrNull() ?: continue
                if (!imageName.endsWith("_app_0x10000.bin")) continue
                if (!diagnostic && isDiagnosticAsset(version, manifestName, imageName)) continue
                val manifestUrl = assetUrl(assets, manifestName) ?: continue
                val imageUrl = assetUrl(assets, imageName) ?: continue
                val candidate = Candidate(board, version, manifestUrl, imageUrl,
                    release.optString("name").ifBlank { release.optString("tag_name", "MotoNav 固件") },
                    release.optString("body").trim().take(1800), diagnostic,
                    assetApiUrl(assets, manifestName), assetApiUrl(assets, imageName))
                newest = selectNewer(newest, candidate)
            }
        }
        return newest ?: error("固件检查未找到适用于 $board 的${if (includeDiagnostic) "稳定或测试" else "稳定"}候选；" +
            "更新目录：$feedFailure；已读取 $requested 个发布索引（最多 $MAX_INDEX_REQUESTS 个）")
    }

    /** The official small feed is ordered newest-publication-first, just like the fallback. */
    fun selectFeedCandidate(feed: JSONObject, board: String, includeDiagnostic: Boolean): Candidate? {
        require(feed.optInt("schema") == 1) { "更新目录版本不受支持" }
        val items = feed.optJSONArray("firmwares") ?: error("更新目录缺少固件列表")
        var newest: Candidate? = null
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optString("board") != board) continue
            val channel = item.optString("channel")
            val diagnostic = channel == "diagnostic"
            if (channel != "stable" && !diagnostic || diagnostic && !includeDiagnostic ||
                !diagnostic && item.optBoolean("prerelease")) continue
            val version = safeVersion(item.optString("version"))
            val manifestUrl = item.optString("manifestUrl")
            val imageUrl = item.optString("imageUrl")
            val manifest = releaseAsset(manifestUrl) ?: error("更新目录中的 $board 固件清单地址无效")
            val image = releaseAsset(imageUrl) ?: error("更新目录中的 $board 固件镜像地址无效")
            val manifestApi = item.optString("manifestApiUrl")
            val imageApi = item.optString("imageApiUrl")
            require(isApiAssetUrl(manifestApi) && isApiAssetUrl(imageApi)) { "更新目录中的 $board API 附件地址无效" }
            if (manifest.first != image.first || !manifest.second.endsWith("_manifest.json") ||
                !image.second.endsWith("_app_0x10000.bin")) error("更新目录中的 $board 固件文件不属于同一发布或类型错误")
            if (!diagnostic && isDiagnosticAsset(version, manifest.second, image.second)) error("稳定更新目录混入测试固件")
            newest = selectNewer(newest, Candidate(board, version, manifestUrl, imageUrl,
                item.optString("releaseName").ifBlank { "MotoNav 固件" }, item.optString("notes").trim().take(1800), diagnostic,
                manifestApi, imageApi))
        }
        return newest
    }

    /** Do not let a feed redirect either half of a signed package to a different repo/release. */
    private fun releaseAsset(value: String): Pair<String, String>? = runCatching {
        val uri = URI(value)
        if (uri.scheme != "https" || uri.host != "github.com" || uri.port != -1 || uri.userInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null) return null
        val prefix = "/$REPOSITORY/releases/download/"
        if (!uri.rawPath.startsWith(prefix)) return null
        val tail = uri.rawPath.removePrefix(prefix).split('/')
        if (tail.size != 2 || tail[0] == "." || tail[0] == ".." || !Regex("[A-Za-z0-9._-]{1,120}").matches(tail[0])) return null
        tail[0] to safeAssetName(tail[1])
    }.getOrNull()

    private fun isApiAssetUrl(value: String): Boolean = value.isBlank() ||
        Regex("https://api\\.github\\.com/repos/$REPOSITORY/releases/assets/[1-9][0-9]*").matches(value)

    /** Downloads the selected release and applies the same package checks as the offline picker. */
    fun downloadPackage(candidate: Candidate, progress: (String) -> Unit): OtaPackage {
        progress("正在下载固件清单…")
        val manifest = GitHubUpdateSource.downloadAsset(candidate.manifestUrl, candidate.manifestApiUrl, MAX_MANIFEST_BYTES)
        progress("正在下载圆屏固件…")
        val image = GitHubUpdateSource.downloadAsset(candidate.imageUrl, candidate.imageApiUrl, MAX_IMAGE_BYTES) { read, total ->
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

    /** Incomparable build channels retain release order; only exact versions prefer stable. */
    fun selectNewer(current: Candidate?, candidate: Candidate): Candidate {
        if (current == null || compareVersions(candidate.version, current.version) > 0 ||
            candidate.version == current.version && current.diagnostic && !candidate.diagnostic)
            return candidate
        return current
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

    /** Shared policy for an index's name, declared channel and explicit user opt-in. */
    fun isIndexAllowed(indexName: String, schema: Int, channel: String,
                       includeDiagnostic: Boolean, prerelease: Boolean): Boolean {
        if (schema != 1) return false
        return when (indexName) {
            INDEX_ASSET -> channel == "stable" && !prerelease
            DIAGNOSTIC_INDEX_ASSET -> includeDiagnostic && channel == "diagnostic"
            else -> false
        }
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

    private fun assetApiUrl(assets: JSONArray, name: String): String {
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") == name && asset.optLong("id") > 0 && assetUrl(assets, name) != null)
                return "https://api.github.com/repos/$REPOSITORY/releases/assets/${asset.optLong("id")}"
        }
        return ""
    }

}
