package com.example.phonemockup

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri

/** Shared state between the service and the screen. */
object ExportState {
    @Volatile var running = false
    @Volatile var progress = 0
    @Volatile var status = "Video chunein"
    @Volatile var outputUri: Uri? = null
    @Volatile var onChange: (() -> Unit)? = null
    private val logBuf = StringBuilder()

    fun logText(): String = synchronized(logBuf) { logBuf.toString() }
    fun clearLog() = synchronized(logBuf) { logBuf.setLength(0) }
    fun log(msg: String) {
        synchronized(logBuf) { logBuf.append(msg).append('\n') }
        onChange?.invoke()
    }
    fun update(newStatus: String, newProgress: Int = progress) {
        status = newStatus
        progress = newProgress
        onChange?.invoke()
    }
}

/** Fixed 9:16 layout (1080x1920) for TikTok / YouTube Shorts / Reels. */
object Mockup {
    const val OUT_W = 1080
    const val OUT_H = 1920
    const val BEZEL = 20f          // black border around the screen
    const val MARGIN_X = 40f       // minimum gap: phone to left/right edge
    const val MARGIN_Y = 36f       // minimum gap: phone to top/bottom edge
    const val SHOW_NOTCH = true
    const val BRIGHTNESS = 0f      // -1..1, 0 = no change (recording colours stay true)
    const val BG_FOCUS = 1.0f      // 0 = left part of the background photo, 1 = right part
    const val BG_BLUR_DIV = 6      // bigger number = blurrier background
    const val BG_VEIL = 0x26FFFFFF // soft white veil so the background stays secondary

    /** Biggest screen height that fits, for this recording's shape. */
    fun screenHeight(aspect: Float): Float {
        val byHeight = OUT_H - 2 * (MARGIN_Y + BEZEL)
        val byWidth = (OUT_W - 2 * (MARGIN_X + BEZEL)) / aspect
        return minOf(byHeight, byWidth)
    }

    /** Shrinks the letterboxed video so it fits exactly into the phone screen (centred). */
    fun videoMatrix(aspect: Float): Matrix {
        val sh = screenHeight(aspect)
        val frameAspect = OUT_W.toFloat() / OUT_H
        val h0 = if (aspect <= frameAspect) OUT_H.toFloat() else OUT_W / aspect
        val s = sh / h0
        return Matrix().apply { postScale(s, s) }
    }

    /** Full-frame picture: soft background + phone body, with a transparent hole for the video. */
    fun buildOverlay(context: Context, aspect: Float): Bitmap {
        val bmp = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val full = Rect(0, 0, OUT_W, OUT_H)

        val bg = try {
            context.assets.open("background.png").use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
        if (bg != null) {
            // crop a 9:16 slice of the landscape photo, blur it by shrinking then enlarging
            val cropW = (bg.height * OUT_W.toFloat() / OUT_H).toInt().coerceAtMost(bg.width)
            val x0 = ((bg.width - cropW) * BG_FOCUS).toInt()
            val src = Rect(x0, 0, x0 + cropW, bg.height)
            val small = Bitmap.createBitmap(OUT_W / BG_BLUR_DIV, OUT_H / BG_BLUR_DIV, Bitmap.Config.ARGB_8888)
            Canvas(small).drawBitmap(bg, src, Rect(0, 0, small.width, small.height), Paint(Paint.FILTER_BITMAP_FLAG))
            c.drawBitmap(small, null, full, Paint(Paint.FILTER_BITMAP_FLAG))
            small.recycle()
            bg.recycle()
        } else {
            val p = Paint()
            p.shader = LinearGradient(
                0f, 0f, 0f, OUT_H.toFloat(),
                0xFFC8E8FF.toInt(), 0xFF9FD2F7.toInt(), Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, OUT_W.toFloat(), OUT_H.toFloat(), p)
        }
        c.drawColor(BG_VEIL)

        val sh = screenHeight(aspect)
        val sw = sh * aspect
        val cx = OUT_W / 2f
        val cy = OUT_H / 2f
        val screen = RectF(cx - sw / 2, cy - sh / 2, cx + sw / 2, cy + sh / 2)
        val body = RectF(
            screen.left - BEZEL, screen.top - BEZEL,
            screen.right + BEZEL, screen.bottom + BEZEL
        )

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF111111.toInt()
            setShadowLayer(60f, 0f, 24f, 0x55000000)
        }
        c.drawRoundRect(body, 100f, 100f, bodyPaint)

        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = 0xFF3C3C3C.toInt()
        }
        c.drawRoundRect(body, 100f, 100f, rim)

        // transparent hole (2px smaller than the video so no edge line shows)
        val hole = RectF(screen)
        hole.inset(2f, 2f)
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        c.drawRoundRect(hole, 80f, 80f, clear)

        if (SHOW_NOTCH) {
            val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111111.toInt() }
            c.drawRoundRect(
                RectF(cx - 75f, screen.top + 22f, cx + 75f, screen.top + 56f),
                17f, 17f, pill
            )
        }
        return bmp
    }
}
