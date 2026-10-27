package com.example.motonav

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONObject

/** Reads the manifest and app_0x10000.bin pair produced by firmware/build.ps1. */
data class OtaPackage(
    val board: String,
    val boardName: String,
    val version: String,
    val image: ByteArray,
    val sha256: String,
    val signatureAlgorithm: String,
    val signatureKeyId: String,
    val signatureBase64: String
) {
    companion object {
        fun read(context: Context, manifestUri: Uri, appUri: Uri): OtaPackage {
            val resolver = context.contentResolver
            val manifest = resolver.openInputStream(manifestUri)?.use { it.readBytes() }
                ?: error("无法读取 OTA manifest")
            val image = resolver.openInputStream(appUri)?.use { it.readBytes() }
                ?: error("无法读取 OTA 应用镜像")
            return read(manifest, image)
        }

        /** Online updater uses the exact same manifest and image validation as the offline picker. */
        fun read(manifest: ByteArray, image: ByteArray): OtaPackage {
            val json = JSONObject(String(manifest, Charsets.UTF_8))
            val boardName = json.optString("board")
            val board = when {
                boardName.contains("1.75C", ignoreCase = true) -> "175C"
                boardName.contains("1.75", ignoreCase = true) -> "175"
                else -> error("OTA manifest 板型不是 MotoNav 1.75/1.75C")
            }
            val expectedPartition = if (board == "175C") "app5M_fat24M_32MB" else "app3M_fat9M_16MB"
            val expectedFlash = if (board == "175C") "32MB" else "16MB"
            if (json.optString("platform") != "universal") error("OTA manifest 平台不受支持")
            if (json.optString("esp32Core") != "3.3.10") error("OTA manifest 的 ESP32 Core 版本不匹配")
            if (json.optString("partition") != expectedPartition || json.optString("flashSize") != expectedFlash) {
                error("OTA manifest 的 Flash/分区与板型不匹配")
            }
            if (json.optString("verifiedApplicationOffset") != "0x10000") {
                error("OTA 应用镜像偏移不是 0x10000")
            }
            val rawVersion = json.optString("firmware").takeIf { it.isNotBlank() }
                ?: error("OTA manifest 缺少固件版本")
            val version = if (rawVersion.startsWith("v")) rawVersion else "v$rawVersion"
            val files = json.optJSONArray("files") ?: error("OTA manifest 缺少文件清单")
            var expectedSize = -1L
            var expectedSha = ""
            for (i in 0 until files.length()) {
                val item = files.optJSONObject(i) ?: continue
                if (item.optString("file").contains("_app_0x10000.bin")) {
                    expectedSize = item.optLong("bytes", -1L)
                    expectedSha = item.optString("sha256").lowercase()
                    break
                }
            }
            if (expectedSize <= 0L || expectedSha.length != 64 || !expectedSha.matches(Regex("[0-9a-f]{64}"))) {
                error("manifest 缺少有效的应用镜像 SHA-256")
            }
            val signature = json.optJSONObject("otaSignature") ?: error("OTA manifest 缺少正式签名")
            val algorithm = signature.optString("algorithm")
            val keyId = signature.optString("keyId").lowercase()
            val signatureBase64 = signature.optString("value")
            if (algorithm != "RSA-SHA256-PKCS1v1.5" || !keyId.matches(Regex("[0-9a-f]{16}"))) {
                error("OTA manifest 的签名元数据无效")
            }
            val signatureBytes = try { Base64.decode(signatureBase64, Base64.DEFAULT) }
            catch (_: IllegalArgumentException) { error("OTA manifest 的签名不是 Base64") }
            if (signatureBytes.size != 256) error("OTA manifest 的 RSA-2048 签名长度错误")
            if (image.size.toLong() != expectedSize) error("应用镜像大小与 manifest 不一致")
            return OtaPackage(board, boardName, version, image, expectedSha, algorithm, keyId, signatureBase64)
        }
    }
}
