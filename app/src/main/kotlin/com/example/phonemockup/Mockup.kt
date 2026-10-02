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

/** Fixed layout of the output video (1920x1080) - change numbers here to move the phone. */
object Mockup {
    const val OUT_W = 1920
    const val OUT_H = 1080
    const val SCREEN_H = 860f      // height of the phone screen in pixels
    const val BEZEL = 22f          // black border around the screen
    const val CENTER_X = 560f      // phone centre, from left
    const val CENTER_Y = 540f      // phone centre, from top
    const val SHOW_NOTCH = true

    /** Moves/shrinks the video (already letterboxed to 1920x1080) into the phone screen. */
    fun videoMatrix(): Matrix {
        val s = SCREEN_H / OUT_H
        val tx = (CENTER_X - OUT_W / 2f) / (OUT_W / 2f)
        val ty = -(CENTER_Y - OUT_H / 2f) / (OUT_H / 2f)
        return Matrix().apply {
            postScale(s, s)
            postTranslate(tx, ty)
        }
    }

    /** Full-frame picture: background + phone body, with a transparent hole where the video shows. */
    fun buildOverlay(context: Context, aspect: Float): Bitmap {
        val bmp = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val bg = try {
            context.assets.open("background.png").use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
        if (bg != null) {
            c.drawBitmap(bg, null, Rect(0, 0, OUT_W, OUT_H), Paint(Paint.FILTER_BITMAP_FLAG))
            bg.recycle()
        } else {
            val p = Paint()
            p.shader = LinearGradient(
                0f, 0f, 0f, OUT_H.toFloat(),
                0xFFC8E8FF.toInt(), 0xFF9FD2F7.toInt(), Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, OUT_W.toFloat(), OUT_H.toFloat(), p)
        }

        val sw = SCREEN_H * aspect
        val screen = RectF(
            CENTER_X - sw / 2, CENTER_Y - SCREEN_H / 2,
            CENTER_X + sw / 2, CENTER_Y + SCREEN_H / 2
        )
        val body = RectF(
            screen.left - BEZEL, screen.top - BEZEL,
            screen.right + BEZEL, screen.bottom + BEZEL
        )

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF111111.toInt()
            setShadowLayer(40f, 0f, 18f, 0x66000000)
        }
        c.drawRoundRect(body, 64f, 64f, bodyPaint)

        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = 0xFF3C3C3C.toInt()
        }
        c.drawRoundRect(body, 64f, 64f, rim)

        // transparent hole (2px smaller than the video so no edge line shows)
        val hole = RectF(screen)
        hole.inset(2f, 2f)
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        c.drawRoundRect(hole, 42f, 42f, clear)

        if (SHOW_NOTCH) {
            val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111111.toInt() }
            c.drawRoundRect(
                RectF(CENTER_X - 55f, screen.top + 14f, CENTER_X + 55f, screen.top + 40f),
                13f, 13f, pill
            )
        }
        return bmp
    }
}
