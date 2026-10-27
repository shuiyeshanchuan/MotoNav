package com.example.motonav

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONArray

/** Downloads only signed MotoNav APKs published as GitHub Release assets. */
object AppUpdateClient {
    private const val REPOSITORY = "shuiyeshanchuan/MotoNav"
    private const val APK_ASSET = "app-release.apk"
    private const val MAX_APK_BYTES = 150 * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 60_000

    data class Candidate(val version: String, val apkUrl: String, val bytes: Long)

    fun check(): Candidate {
        val releases = getJsonArray("https://api.github.com/repos/$REPOSITORY/releases?per_page=30")
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
                val candidate = Candidate(version, url, bytes)
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
        val connection = URL(candidate.apkUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/octet-stream")
            connection.setRequestProperty("User-Agent", "MotoNav-Android")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) error("GitHub 请求失败：HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong
            if (total !in 1..MAX_APK_BYTES || total != candidate.bytes) error("安装包大小与发布信息不一致")
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var read = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        read += count
                        if (read > MAX_APK_BYTES) error("安装包超过允许大小")
                        output.write(buffer, 0, count)
                        progress(read, total.toInt())
                    }
                    if (read.toLong() != total) error("安装包下载不完整")
                }
            }
            if (target.exists()) target.delete()
            if (!partial.renameTo(target)) error("无法保存安装包")
            return target
        } finally {
            connection.disconnect()
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

    private fun isReleaseAssetUrl(url: String) =
        url.startsWith("https://github.com/$REPOSITORY/releases/download/")

    private fun getJsonArray(url: String): JSONArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "MotoNav-Android")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) error("GitHub 请求失败：HTTP ${connection.responseCode}")
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                input.copyTo(output)
                output.toByteArray()
            }
            return JSONArray(String(bytes, Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }
}
