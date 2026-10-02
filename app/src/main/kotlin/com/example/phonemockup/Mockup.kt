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
    @Volatile var status = "Select a video to start"
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

/** The three export formats. Change size / bitrate / background here. */
enum class Preset(
    val label: String,
    val w: Int,
    val h: Int,
    val bitrate: Int,
    val bgAsset: String,
    val info: String
) {
    TIKTOK("TikTok", 1080, 1920, 8_000_000, "bg_portrait.png", "Vertical 9:16  |  1080 x 1920"),
    YOUTUBE("YouTube", 1920, 1080, 8_000_000, "bg_landscape.jpg", "Horizontal 16:9  |  1920 x 1080"),
    WHATSAPP("WhatsApp", 720, 1280, 3_000_000, "bg_portrait.png", "Vertical 9:16  |  720 x 1280  |  small file");

    /** 1.0 when the short side is 1080 px. Bezel, margins and corners scale with this. */
    val k: Float get() = minOf(w, h) / 1080f

    companion object {
        fun from(name: String?): Preset = values().firstOrNull { it.name == name } ?: TIKTOK
    }
}

/** Phone mockup layout for any Preset (the phone is always centred). */
object Mockup {
    const val BEZEL = 14f          // thin border around the screen (metal frame + black edge)
    const val FRAME = 4f           // width of the metal frame (part of BEZEL)
    const val SCREEN_RADIUS = 0.135f // screen corner radius, as a share of the screen width (iPhone-like)
    const val ISLAND_W = 0.30f     // Dynamic Island width, share of screen width
    const val ISLAND_H = 0.085f    // Dynamic Island height, share of screen width
    const val ISLAND_TOP = 0.026f  // gap above the island, share of screen width
    const val MARGIN_X = 28f       // minimum gap: phone to left/right edge
    const val MARGIN_Y = 24f       // minimum gap: phone to top/bottom edge
    const val SHOW_ISLAND = true   // iPhone Dynamic Island
    const val SHOW_BUTTONS = true  // iPhone side buttons
    const val BRIGHTNESS = 0f      // -1..1, 0 = no change (recording colours stay true)
    const val BG_FOCUS = 0.5f      // which part of the photo is used: 0 = left/top, 0.5 = centre, 1 = right/bottom
    const val BG_BLUR_DIV = 1      // 1 = sharp background, 6 = very blurry
    const val BG_VEIL = 0x00000000 // 0 = no veil. Example: 0x26FFFFFF = soft white veil

    /** Biggest screen height that fits, for this recording's shape. */
    fun screenHeight(p: Preset, aspect: Float): Float {
        val k = p.k
        val byHeight = p.h - 2 * (MARGIN_Y + BEZEL) * k
        val byWidth = (p.w - 2 * (MARGIN_X + BEZEL) * k) / aspect
        return minOf(byHeight, byWidth)
    }

    /** Shrinks the letterboxed video so it fits exactly into the phone screen (centred). */
    fun videoMatrix(p: Preset, aspect: Float): Matrix {
        val sh = screenHeight(p, aspect)
        val frameAspect = p.w.toFloat() / p.h
        val h0 = if (aspect <= frameAspect) p.h.toFloat() else p.w / aspect
        val s = sh / h0
        return Matrix().apply { postScale(s, s) }
    }

    /** Full-frame picture: background + phone body, with a transparent hole for the video. */
    fun buildOverlay(context: Context, p: Preset, aspect: Float): Bitmap {
        val k = p.k
        val bmp = Bitmap.createBitmap(p.w, p.h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val full = Rect(0, 0, p.w, p.h)

        val bg = try {
            context.assets.open(p.bgAsset).use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
        if (bg != null) {
            // "cover" fit: works with ANY image size, crops to the preset's shape
            val target = p.w.toFloat() / p.h
            val srcAspect = bg.width.toFloat() / bg.height
            val src = if (srcAspect > target) {
                val cw = (bg.height * target).toInt().coerceAtMost(bg.width)
                val x0 = ((bg.width - cw) * BG_FOCUS).toInt()
                Rect(x0, 0, x0 + cw, bg.height)
            } else {
                val ch = (bg.width / target).toInt().coerceAtMost(bg.height)
                val y0 = ((bg.height - ch) * BG_FOCUS).toInt()
                Rect(0, y0, bg.width, y0 + ch)
            }
            val fp = Paint(Paint.FILTER_BITMAP_FLAG)
            if (BG_BLUR_DIV > 1) {
                val small = Bitmap.createBitmap(p.w / BG_BLUR_DIV, p.h / BG_BLUR_DIV, Bitmap.Config.ARGB_8888)
                Canvas(small).drawBitmap(bg, src, Rect(0, 0, small.width, small.height), fp)
                c.drawBitmap(small, null, full, fp)
                small.recycle()
            } else {
                c.drawBitmap(bg, src, full, fp)
            }
            bg.recycle()
        } else {
            val g = Paint()
            g.shader = LinearGradient(
                0f, 0f, 0f, p.h.toFloat(),
                0xFFC8E8FF.toInt(), 0xFF9FD2F7.toInt(), Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, p.w.toFloat(), p.h.toFloat(), g)
        }
        if (BG_VEIL != 0) c.drawColor(BG_VEIL)

        val sh = screenHeight(p, aspect)
        val sw = sh * aspect
        val cx = p.w / 2f
        val cy = p.h / 2f
        val screen = RectF(cx - sw / 2, cy - sh / 2, cx + sw / 2, cy + sh / 2)
        val bezel = BEZEL * k
        val frameW = FRAME * k
        val screenR = minOf(SCREEN_RADIUS * sw, sw / 2f)
        val bodyR = screenR + bezel
        val body = RectF(
            screen.left - bezel, screen.top - bezel,
            screen.right + bezel, screen.bottom + bezel
        )

        // side buttons (drawn first so the phone body sits on top of them)
        if (SHOW_BUTTONS) {
            val btn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF4A4A4F.toInt() }
            fun side(left: Boolean, from: Float, len: Float) {
                val top = body.top + body.height() * from
                val bottom = top + body.height() * len
                val r = if (left) RectF(body.left - 5f * k, top, body.left + 3f * k, bottom)
                else RectF(body.right - 3f * k, top, body.right + 5f * k, bottom)
                c.drawRoundRect(r, 3f * k, 3f * k, btn)
            }
            side(true, 0.145f, 0.035f)   // action button
            side(true, 0.215f, 0.065f)   // volume up
            side(true, 0.295f, 0.065f)   // volume down
            side(false, 0.240f, 0.100f)  // power
        }

        // phone body with a soft shadow
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF0A0A0A.toInt()
            setShadowLayer(60f * k, 0f, 24f * k, 0x55000000)
        }
        c.drawRoundRect(body, bodyR, bodyR, bodyPaint)

        // metal frame (titanium look)
        val metal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = frameW
            shader = LinearGradient(
                body.left, 0f, body.right, 0f,
                intArrayOf(
                    0xFF4A4A4F.toInt(), 0xFFB9B9BE.toInt(), 0xFF5A5A5F.toInt(),
                    0xFFB9B9BE.toInt(), 0xFF4A4A4F.toInt()
                ),
                floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        val ring = RectF(body)
        ring.inset(frameW / 2f, frameW / 2f)
        c.drawRoundRect(ring, bodyR - frameW / 2f, bodyR - frameW / 2f, metal)

        // transparent hole (2px smaller than the video so no edge line shows)
        val hole = RectF(screen)
        hole.inset(2f, 2f)
        val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        c.drawRoundRect(hole, screenR - 2f, screenR - 2f, clear)

        // Dynamic Island
        if (SHOW_ISLAND) {
            val iw = ISLAND_W * sw
            val ih = ISLAND_H * sw
            val top = screen.top + ISLAND_TOP * sw
            val isl = RectF(cx - iw / 2, top, cx + iw / 2, top + ih)
            val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
            c.drawRoundRect(isl, ih / 2, ih / 2, pill)
            val lensRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B2640.toInt() }
            val lens = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF070B14.toInt() }
            c.drawCircle(isl.right - ih * 0.5f, isl.centerY(), ih * 0.24f, lensRing)
            c.drawCircle(isl.right - ih * 0.5f, isl.centerY(), ih * 0.17f, lens)
        }
        return bmp
    }
}
