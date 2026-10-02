package com.example.phonemockup

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var bar: ProgressBar
    private lateinit var logView: TextView
    private lateinit var pickBtn: Button
    private lateinit var cancelBtn: Button
    private lateinit var openBtn: Button
    private lateinit var presetInfo: TextView
    private val presetButtons = LinkedHashMap<Preset, Button>()
    private var selected = Preset.TIKTOK
    private var tab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = savedInstanceState?.getInt("tab") ?: 0
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        statusView = TextView(this).apply { textSize = 18f; text = ExportState.status }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        selected = Preset.from(getSharedPreferences("prefs", Context.MODE_PRIVATE).getString("preset", null))
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Preset.values().forEach { p ->
            val b = Button(this).apply {
                text = p.label
                isAllCaps = false
                setOnClickListener { choose(p) }
            }
            presetButtons[p] = b
            presetRow.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        presetInfo = TextView(this).apply { textSize = 12f; setPadding(0, 0, 0, (8 * d).toInt()) }
        pickBtn = Button(this).apply { text = "Select Video"; setOnClickListener { pick() } }
        cancelBtn = Button(this).apply { text = "Cancel"; setOnClickListener { cancelExport() } }
        openBtn = Button(this).apply { text = "Open Video"; setOnClickListener { openResult() } }
        logView = TextView(this).apply {
            textSize = 11f
            setTextIsSelectable(true)
            minHeight = (120 * d).toInt()
        }

        root.addView(statusView)
        root.addView(bar)
        root.addView(TextView(this).apply { text = "Choose format:"; textSize = 12f })
        root.addView(presetRow)
        root.addView(presetInfo)
        root.addView(pickBtn)
        root.addView(cancelBtn)
        root.addView(openBtn)
        root.addView(TextView(this).apply { text = "Export details (long-press to copy):"; textSize = 12f })
        root.addView(logView)
        val home = ScrollView(this).apply {
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // TEAM 313 loader first, then the real screen (only on a fresh start, not on rotation)
        val devScreen = DeveloperScreen.build(this)
        devScreen.visibility = View.GONE
        val content = FrameLayout(this).apply {
            addView(home, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(devScreen, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val nav = AppNav.build(this, tab) { t ->
            tab = t
            home.visibility = if (t == 0) View.VISIBLE else View.GONE
            devScreen.visibility = if (t == 1) View.VISIBLE else View.GONE
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        LoaderOverlay.attach(this, page, savedInstanceState == null)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }

    override fun onResume() {
        super.onResume()
        ExportState.onChange = { runOnUiThread { refresh() } }
        refresh()
    }

    override fun onPause() {
        ExportState.onChange = null
        super.onPause()
    }

    private fun choose(p: Preset) {
        if (ExportState.running) return
        selected = p
        getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putString("preset", p.name).apply()
        refresh()
    }

    private fun refresh() {
        presetButtons.forEach { (p, b) ->
            val on = p == selected
            b.setBackgroundColor(Color.parseColor(if (on) "#FACC15" else "#E6E6E6"))
            b.setTextColor(Color.parseColor(if (on) "#111111" else "#666666"))
            b.isEnabled = !ExportState.running
        }
        presetInfo.text = selected.info
        statusView.text = ExportState.status
        bar.progress = ExportState.progress
        logView.text = ExportState.logText()
        pickBtn.isEnabled = !ExportState.running
        cancelBtn.isEnabled = ExportState.running
        openBtn.isEnabled = !ExportState.running && ExportState.outputUri != null
    }

    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        startActivityForResult(i, 100)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 100 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
        val i = Intent(this, ExportService::class.java)
            .setData(uri)
            .putExtra(ExportService.EXTRA_PRESET, selected.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        pickBtn.isEnabled = false
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (e: Exception) {
            ExportState.log("Could not start export: ${e.message}")
            ExportState.update("Could not start export")
            refresh()
        }
    }

    private fun cancelExport() {
        startService(Intent(this, ExportService::class.java).setAction(ExportService.ACTION_CANCEL))
    }

    private fun openResult() {
        val uri = ExportState.outputUri ?: return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            ExportState.log("Could not open: ${e.message}")
        }
    }
}
