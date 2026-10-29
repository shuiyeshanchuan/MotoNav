package com.example.motonav

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.ceil

/**
 * 版本：跟随 App 版本，见 MainActivity.kt 里的 APP_VERSION（v1.7 新增）
 *
 * 把中文文字渲染成 4 位灰度小图片（每个像素 16 级灰度，两个像素占一个字节），
 * 发给圆屏显示。这样圆屏不需要中文字库，字体也是手机系统的，清晰好看。
 */
object TextImage {
    class Img(val w: Int, val h: Int, val data: ByteArray)

    /**
     * @param maxW  图片最大宽度（像素），文字太长会先缩小字号，再不行就截断加省略号
     * @param sizePx 期望字号（像素）
     * @return 文字为空时返回 null
     */
    fun render(text: String, maxW: Int, sizePx: Float, minSizePx: Float = 16f): Img? {
        if (text.isBlank()) return null
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            color = Color.WHITE
            textSize = sizePx
        }
        var t = text.trim()
        while (p.measureText(t) > maxW && p.textSize > minSizePx) p.textSize = p.textSize - 1f
        if (p.measureText(t) > maxW) {
            while (t.length > 1 && p.measureText("$t…") > maxW) t = t.dropLast(1)
            t = "$t…"
        }
        val fm = p.fontMetrics
        val h = ceil(fm.descent - fm.ascent).toInt().coerceIn(1, 60)
        val w = ceil(p.measureText(t)).toInt().coerceIn(1, maxW)

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(t, 0f, -fm.ascent, p)
        return pack(bmp)
    }

    /** Two short rows for the round-screen WeChat message island. */
    fun renderNotice(title: String, body: String): Img? {
        // 240 x 60 at 4 bpp stays within the firmware's 8 KiB image buffer
        // and its 32 BLE fragments at the common 247-byte MTU.
        val maxW = 240
        val sender = title.trim().ifBlank { "微信消息" }
        val preview = body.replace(Regex("\\s+"), " ").trim().take(200)
        if (preview.isBlank()) return render(sender, maxW, 36f, 20f)
        val heading = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            color = Color.WHITE
            textSize = 24f
        }
        val detail = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
            color = Color.WHITE
            textSize = 21f
        }
        val first = TextUtils.ellipsize(sender, heading, maxW.toFloat(), TextUtils.TruncateAt.END).toString()
        val second = TextUtils.ellipsize(preview, detail, maxW.toFloat(), TextUtils.TruncateAt.END).toString()
        val firstHeight = ceil(heading.fontMetrics.descent - heading.fontMetrics.ascent).toInt()
        val secondHeight = ceil(detail.fontMetrics.descent - detail.fontMetrics.ascent).toInt()
        val w = ceil(maxOf(heading.measureText(first), detail.measureText(second))).toInt().coerceIn(1, maxW)
        val h = (firstHeight + 2 + secondHeight).coerceIn(1, 60)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawText(first, 0f, -heading.fontMetrics.ascent, heading)
            drawText(second, 0f, firstHeight + 2 - detail.fontMetrics.ascent, detail)
        }
        return pack(bmp)
    }

    private fun pack(bmp: Bitmap): Img {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()

        val bpr = (w + 1) / 2
        val out = ByteArray(bpr * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val a = (px[y * w + x] ushr 24) and 0xFF
                val nib = ((a + 8) / 17).coerceIn(0, 15)
                if (nib == 0) continue
                val i = y * bpr + (x shr 1)
                out[i] = if (x and 1 == 0) (out[i].toInt() or (nib shl 4)).toByte()
                         else (out[i].toInt() or nib).toByte()
            }
        }
        return Img(w, h, out)
    }
}
