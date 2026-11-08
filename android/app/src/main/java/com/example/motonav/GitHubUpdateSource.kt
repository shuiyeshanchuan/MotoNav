package com.example.motonav

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** Small update metadata has a deadline independent of firmware/APK transfers. */
object GitHubUpdateSource {
    private const val REPOSITORY = "shuiyeshanchuan/MotoNav"
    private const val RAW_FEED = "https://raw.githubusercontent.com/$REPOSITORY/175c-ota-one-click/updates/latest.json"
    private const val API_FEED = "https://api.github.com/repos/$REPOSITORY/contents/updates/latest.json?ref=175c-ota-one-click"
    private const val RELEASES = "https://api.github.com/repos/$REPOSITORY/releases?per_page=30"
    private const val MAX_FEED_BYTES = 128 * 1024
    private const val CHECK_MS = 15_000
    private const val REQUEST_MS = 4_000
    private const val MAX_REQUESTS = 6
    private val workers = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(2), { task -> Thread(task, "MotoNav-update-metadata").apply { isDaemon = true } })
    // Cancellation never waits for a blocked socket or DNS lookup on the caller.
    private val cancellation = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(4), { task -> Thread(task, "MotoNav-update-cancel").apply { isDaemon = true } })
    private val feedLock = ReentrantLock()
    @Volatile private var cachedFeed: JSONObject? = null
    @Volatile private var cachedAt = 0L

    fun session(): Session = Session()

    class Session {
        private val deadline = nowMs() + CHECK_MS
        private var requests = 0
        fun remainingMs(): Int = (deadline - nowMs()).coerceIn(0, CHECK_MS.toLong()).toInt()
        fun remainingRequests(): Int = MAX_REQUESTS - requests

        fun feed(): JSONObject {
            check(remainingMs() > 0) { "检查更新超时（最多15秒），请重试" }
            check(feedLock.tryLock(remainingMs().toLong(), TimeUnit.MILLISECONDS)) { "检查更新超时（最多15秒），请重试" }
            try {
                cachedFeed?.takeIf { nowMs() - cachedAt < 15_000 }?.let { return JSONObject(it.toString()) }
                val failures = ArrayList<String>()
                for (url in listOf(RAW_FEED, API_FEED)) {
                    try {
                        val feed = jsonObject(url, MAX_FEED_BYTES)
                        require(feed.optInt("schema") == 1 && feed.optJSONArray("firmwares") != null) { "更新目录格式无效" }
                        cachedFeed = JSONObject(feed.toString())
                        cachedAt = nowMs()
                        return feed
                    } catch (e: Exception) { failures.add(e.message ?: "更新目录不可用") }
                }
                error(failures.distinct().joinToString("；"))
            } finally { feedLock.unlock() }
        }

        fun releases(): JSONArray = JSONArray(String(bytes(RELEASES, 2 * 1024 * 1024), Charsets.UTF_8))
        fun jsonObject(url: String, maxBytes: Int): JSONObject = JSONObject(String(bytes(url, maxBytes), Charsets.UTF_8))

        private fun bytes(url: String, maxBytes: Int): ByteArray {
            requireMetadataUrl(url)
            check(remainingMs() > 0) { "检查更新超时（最多15秒），请重试" }
            check(requests++ < MAX_REQUESTS) { "更新来源多次失败，请稍后重试" }
            val budget = minOf(REQUEST_MS, remainingMs())
            val connection = AtomicReference<HttpURLConnection?>()
            val future = try { workers.submit<ByteArray> { readMetadata(url, maxBytes, budget, connection) } }
            catch (_: java.util.concurrent.RejectedExecutionException) { error("更新网络请求尚未结束，请稍后重试") }
            try {
                return future.get(budget.toLong(), TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                error("${sourceName(url)} 响应超时（单个来源最多4秒，检查最多15秒）")
            } catch (e: ExecutionException) {
                throw (e.cause as? Exception ?: e)
            } finally {
                future.cancel(true)
                workers.remove(future as Runnable)
                connection.getAndSet(null)?.let { socket ->
                    try { cancellation.execute { socket.disconnect() } }
                    catch (_: java.util.concurrent.RejectedExecutionException) { /* Socket read timeout also bounds worker lifetime. */ }
                }
            }
        }
    }

    private fun readMetadata(url: String, maxBytes: Int, budget: Int, active: AtomicReference<HttpURLConnection?>): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        active.set(connection)
        val deadline = nowMs() + budget
        try {
            connection.connectTimeout = budget.coerceAtLeast(1)
            connection.readTimeout = minOf(2_000, budget).coerceAtLeast(1)
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "MotoNav-Android")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("Accept", when {
                isAssetApiUrl(url) -> "application/octet-stream"
                "/contents/" in url -> "application/vnd.github.raw+json"
                else -> "application/vnd.github+json"
            })
            val code = connection.responseCode
            check(code == HttpURLConnection.HTTP_OK) { "${sourceName(url)} 请求失败：HTTP $code" }
            val total = connection.contentLengthLong
            check(total <= maxBytes) { "更新信息超过允许大小（上限 $maxBytes 字节）" }
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    check(!Thread.currentThread().isInterrupted && nowMs() < deadline) { "更新信息读取超时" }
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= maxBytes) { "更新信息超过允许大小（上限 $maxBytes 字节）" }
                    output.write(buffer, 0, count)
                }
                if (total >= 0) check(output.size().toLong() == total) { "更新信息下载不完整" }
                output.toByteArray()
            }
        } finally { active.compareAndSet(connection, null); connection.disconnect() }
    }

    /** GitHub's asset API avoids an unhealthy github.com download gateway. */
    fun downloadAsset(primaryUrl: String, apiUrl: String, maxBytes: Int,
                      progress: ((Int, Int) -> Unit)? = null): ByteArray {
        val output = ByteArrayOutputStream()
        transferAsset(primaryUrl, apiUrl, maxBytes, { output.reset() },
            { buffer, count -> output.write(buffer, 0, count) }, progress)
        return output.toByteArray()
    }

    /** APKs stream to disk; retry truncates the partial file before using the other route. */
    fun downloadFile(primaryUrl: String, apiUrl: String, maxBytes: Int, expectedBytes: Long,
                     partial: File, progress: (Int, Int) -> Unit) {
        var output: FileOutputStream? = null
        try {
            transferAsset(primaryUrl, apiUrl, maxBytes, {
                output?.close()
                output = FileOutputStream(partial)
            }, { buffer, count -> output!!.write(buffer, 0, count) }, { read, total ->
                require(total < 0 || total.toLong() == expectedBytes) { "安装包大小与发布信息不一致" }
                progress(read, expectedBytes.toInt())
            }, expectedBytes)
        } finally { output?.close() }
    }

    private fun transferAsset(primaryUrl: String, apiUrl: String, maxBytes: Int, reset: () -> Unit,
                              write: (ByteArray, Int) -> Unit, progress: ((Int, Int) -> Unit)?, expectedBytes: Long? = null) {
        require(isReleaseAssetUrl(primaryUrl)) { "下载地址不是官方发布资产" }
        require(apiUrl.isBlank() || isAssetApiUrl(apiUrl)) { "备用下载地址不是官方发布资产" }
        val urls = if (apiUrl.isNotBlank()) listOf(apiUrl, primaryUrl) else listOf(primaryUrl)
        val errors = ArrayList<String>()
        for (url in urls) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "MotoNav-Android")
                connection.setRequestProperty("Accept", "application/octet-stream")
                val code = connection.responseCode
                check(code == 200) { "${sourceName(url)} 下载失败：HTTP $code" }
                connection.readTimeout = 60_000
                val total = connection.contentLengthLong
                require(total <= maxBytes) { "下载文件超过允许大小（上限 $maxBytes 字节）" }
                if (expectedBytes != null) require(total < 0 || total == expectedBytes) { "安装包大小与发布信息不一致" }
                reset()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    var read = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        read += count
                        require(read <= maxBytes) { "下载文件超过允许大小（上限 $maxBytes 字节）" }
                        write(buffer, count)
                        progress?.invoke(read, if (total in 1..maxBytes) total.toInt() else -1)
                    }
                    require((total < 0 || read.toLong() == total) && (expectedBytes == null || read.toLong() == expectedBytes)) { "下载文件不完整" }
                }
                return
            } catch (e: Exception) { errors.add(e.message ?: "下载失败") }
            finally { connection.disconnect() }
        }
        error(errors.distinct().joinToString("；"))
    }

    fun isReleaseAssetUrl(url: String): Boolean =
        Regex("https://github\\.com/$REPOSITORY/releases/download/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+").matches(url)
    fun isAssetApiUrl(url: String): Boolean =
        Regex("https://api\\.github\\.com/repos/$REPOSITORY/releases/assets/[1-9][0-9]*").matches(url)
    private fun requireMetadataUrl(url: String) {
        require(url == RAW_FEED || url == API_FEED || url == RELEASES || isReleaseAssetUrl(url) || isAssetApiUrl(url)) { "更新信息地址不属于官方来源" }
    }
    private fun sourceName(url: String) = when {
        url.startsWith("https://raw.githubusercontent.com/") -> "GitHub 更新目录"
        url.startsWith("https://api.github.com/") -> "GitHub API"
        else -> "GitHub 发布文件"
    }
    private fun nowMs(): Long = System.nanoTime() / 1_000_000
}
