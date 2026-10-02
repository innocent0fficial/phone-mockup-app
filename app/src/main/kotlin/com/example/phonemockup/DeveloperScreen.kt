package com.example.phonemockup

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Change the developer details here - the screen updates automatically. */
object DeveloperInfo {
    const val TEAM = "𝐓𝐄𝐀𝐌 𝟑𝟏𝟑 𝐋𝐄𝐆𝐀𝐂𝐘 💻"
    const val NAME = "313"
    const val MOBILE = "03491622803"
    const val EMAIL = "innocent.wolrd110@gmail.com"
    const val CHANNEL_NAME = "𝐓𝐄𝐀𝐌 𝟑𝟏𝟑 𝐋𝐄𝐆𝐀𝐂𝐘 🔷💻"
    const val CHANNEL_LINK = "https://whatsapp.com/channel/0029VbCSe059xVJmelC2nM44"
}

/** Bottom navigation bar: Home | Developer Info. */
object AppNav {
    fun build(a: Activity, initialTab: Int = 0, onSelect: (Int) -> Unit): View {
        val d = a.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val bar = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(8).toFloat()
        }
        val tabs = mutableListOf<TextView>()
        fun select(i: Int) {
            tabs.forEachIndexed { idx, t ->
                val on = idx == i
                t.setTextColor(Color.parseColor(if (on) "#222222" else "#888888"))
                t.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                t.setBackgroundColor(Color.parseColor(if (on) "#FFF6C7" else "#FFFFFF"))
            }
            onSelect(i)
        }
        val items = listOf("🏠" to "Home", "👨‍💻" to "Developer Info")
        items.forEachIndexed { i, item ->
            val t = TextView(a).apply {
                text = item.first + "\n" + item.second
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { select(i) }
            }
            tabs.add(t)
            bar.addView(t, LinearLayout.LayoutParams(0, dp(60), 1f))
        }
        select(initialTab.coerceIn(0, items.size - 1))
        return bar
    }
}

/** The Developer Info page (opens from the bottom bar, never shown on the main screen). */
object DeveloperScreen {
    fun build(a: Activity): View {
        val d = a.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val dark = Color.parseColor("#222222")
        val gray = Color.parseColor("#888888")

        fun launch(i: Intent) {
            try {
                a.startActivity(i)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(a, "No app found to open this", Toast.LENGTH_SHORT).show()
            }
        }

        fun copy(text: String) {
            val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("313", text))
            Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show()
        }

        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(28), dp(16), dp(24))
        }

        val logo = try {
            a.assets.open("team313.png").use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
        if (logo != null) {
            col.addView(
                ImageView(a).apply {
                    setImageBitmap(logo)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                LinearLayout.LayoutParams(dp(120), dp(120))
            )
        }
        col.addView(TextView(a).apply {
            text = DeveloperInfo.TEAM
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(dark)
            setPadding(0, dp(12), 0, 0)
        })
        col.addView(TextView(a).apply {
            text = "DEVELOPER INFO"
            textSize = 13f
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
            setTextColor(gray)
            setPadding(0, dp(4), 0, dp(20))
        })

        fun card(icon: String, label: String, value: String, action: String?, onTap: (() -> Unit)?) {
            val box = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), Color.parseColor("#EEEEEE"))
                }
                elevation = dp(2).toFloat()
                setOnLongClickListener { copy(value); true }
            }
            box.addView(TextView(a).apply {
                text = "$icon  $label"
                textSize = 12f
                setTextColor(gray)
            })
            box.addView(TextView(a).apply {
                text = value
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(dark)
                setPadding(0, dp(4), 0, 0)
            })
            if (action != null && onTap != null) {
                box.addView(TextView(a).apply {
                    text = action
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Color.parseColor("#B38F00"))
                    setPadding(0, dp(8), 0, 0)
                })
                box.setOnClickListener { onTap() }
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(12)) }
            col.addView(box, lp)
        }

        card("👨‍💻", "Developer Name", DeveloperInfo.NAME, null, null)
        card("📱", "Mobile", DeveloperInfo.MOBILE, "Tap to call") {
            launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + DeveloperInfo.MOBILE)))
        }
        card("📧", "Gmail", DeveloperInfo.EMAIL, "Tap to send an email") {
            launch(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + DeveloperInfo.EMAIL)))
        }
        card("📢", "WhatsApp Channel", DeveloperInfo.CHANNEL_NAME, "Tap to open the channel") {
            launch(Intent(Intent.ACTION_VIEW, Uri.parse(DeveloperInfo.CHANNEL_LINK)))
        }
        card("🔗", "Channel Link", DeveloperInfo.CHANNEL_LINK, "Tap to open") {
            launch(Intent(Intent.ACTION_VIEW, Uri.parse(DeveloperInfo.CHANNEL_LINK)))
        }

        col.addView(TextView(a).apply {
            text = "Long-press any card to copy"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(gray)
            setPadding(0, dp(8), 0, 0)
        })

        return ScrollView(a).apply {
            setBackgroundColor(Color.parseColor("#F5F5F7"))
            isFillViewport = true
            addView(col)
        }
    }
}
