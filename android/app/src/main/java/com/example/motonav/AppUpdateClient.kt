package com.example.motonav

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Downloads only signed MotoNav APKs published as GitHub Release assets. */
object AppUpdateClient {
    private const val REPOSITORY = "shuiyeshanchuan/MotoNav"
    private const val APK_ASSET = "app-release.apk"
    private const val MAX_APK_BYTES = 150 * 1024 * 1024

    data class Candidate @JvmOverloads constructor(val version: String, val apkUrl: String, val bytes: Long, val apkApiUrl: String = "")

    fun check(): Candidate {
        val session = GitHubUpdateSource.session()
        val feedError = try {
            val app = session.feed().optJSONObject("app")
            if (app != null) return parseFeedCandidate(app)
            "更新目录没有 App 安装包"
        } catch (e: Exception) { e.message ?: "更新目录不可用" }
        val releases = try { session.releases() }
        catch (e: Exception) { error("$feedError；${e.message ?: "GitHub API 不可用"}") }
        var newest: Candidate? = null
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            val version = releaseVersion(release.optString("tag_name")) ?: continue
            val assets = release.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val asset = assets.optJSONObject(j) ?: continue
                if (asset.optString("name") != APK_ASSET) continue
                val bytes = asset.optLong("size", -1)
                val url = asset.optString("browser_download_url")
                if (bytes !in 1..MAX_APK_BYTES || !isReleaseAssetUrl(url)) continue
                val candidate = Candidate(version, url, bytes, asset.optLong("id").takeIf { it > 0 }
                    ?.let { "https://api.github.com/repos/$REPOSITORY/releases/assets/$it" }.orEmpty())
                if (newest == null || compareVersions(candidate.version, newest.version) > 0) newest = candidate
            }
        }
        return newest ?: error("GitHub 暂未发布可更新的 MotoNav 安装包")
    }

    fun isNewer(candidate: String, current: String): Boolean = compareVersions(candidate, current) > 0

    fun download(context: Context, candidate: Candidate, progress: (Int, Int) -> Unit): File {
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(folder, "MotoNav-${candidate.version.removePrefix("v")}.apk")
        val partial = File(folder, "${target.name}.part")
        if (partial.exists()) partial.delete()
        try {
            GitHubUpdateSource.downloadFile(candidate.apkUrl, candidate.apkApiUrl, MAX_APK_BYTES,
                candidate.bytes, partial, progress)
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) error("无法保存安装包")
            return target
        } finally {
            if (partial.exists()) partial.delete()
        }
    }

    /** The APK must be a newer build of this exact application with the same signing key. */
    fun verify(context: Context, apk: File, expectedVersion: String): String {
        val pm = context.packageManager
        val installed = packageInfo(pm, context.packageName, false) ?: error("无法读取当前 App 信息")
        val archive = packageInfo(pm, apk.absolutePath, true) ?: error("下载的文件不是有效 APK")
        require(archive.packageName == context.packageName) { "安装包不是当前 MotoNav" }
        require(archive.versionName?.removePrefix("v") == expectedVersion.removePrefix("v")) { "安装包版本与发布信息不一致" }
        require(versionCode(archive) > versionCode(installed)) { "安装包不是比当前版本更新的版本" }
        val installedSigners = signingDigests(installed)
        val archiveSigners = signingDigests(archive)
        require(installedSigners.isNotEmpty() && installedSigners == archiveSigners) { "安装包签名与当前 MotoNav 不一致" }
        return archive.versionName ?: "新版本"
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(pm: PackageManager, source: String, archive: Boolean): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return if (archive) pm.getPackageArchiveInfo(source, flags) else pm.getPackageInfo(source, flags)
    }

    @Suppress("DEPRECATION")
    private fun signingDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun releaseVersion(tag: String): String? {
        val version = tag.removePrefix("motonav-")
        return version.takeIf { Regex("v\\d+\\.\\d+\\.\\d+").matches(it) }
    }

    private fun compareVersions(left: String, right: String): Int {
        val a = left.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val b = right.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0..2) if (a.getOrElse(i) { 0 } != b.getOrElse(i) { 0 })
            return a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
        return 0
    }

    private fun isReleaseAssetUrl(url: String) = GitHubUpdateSource.isReleaseAssetUrl(url)

    fun parseFeedCandidate(app: JSONObject): Candidate {
        val version = app.optString("version")
        require(releaseVersion(version) != null) { "更新目录 App 版本无效" }
        val url = app.optString("apkUrl")
        val apiUrl = app.optString("apkApiUrl")
        val bytes = app.optLong("bytes", -1)
        require(bytes in 1..MAX_APK_BYTES && isReleaseAssetUrl(url) &&
            url.endsWith("/$APK_ASSET") && url.substringBeforeLast('/').substringAfterLast('/') == app.optString("releaseTag") &&
            releaseVersion(app.optString("releaseTag")) == version &&
            (apiUrl.isBlank() || GitHubUpdateSource.isAssetApiUrl(apiUrl))) { "更新目录 App 资产无效" }
        return Candidate(version, url, bytes, apiUrl)
    }
}
