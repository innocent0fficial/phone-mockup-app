package com.example.phonemockup

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        statusView = TextView(this).apply { textSize = 18f; text = ExportState.status }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        pickBtn = Button(this).apply { text = "Video chunein"; setOnClickListener { pick() } }
        cancelBtn = Button(this).apply { text = "Cancel"; setOnClickListener { cancelExport() } }
        openBtn = Button(this).apply { text = "Video kholein"; setOnClickListener { openResult() } }
        logView = TextView(this).apply { textSize = 11f; setTextIsSelectable(true) }
        val scroll = ScrollView(this).apply { addView(logView) }

        root.addView(statusView)
        root.addView(bar)
        root.addView(pickBtn)
        root.addView(cancelBtn)
        root.addView(openBtn)
        root.addView(TextView(this).apply { text = "Debug log (copy kar sakte hain):"; textSize = 12f })
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
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

    private fun refresh() {
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
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        pickBtn.isEnabled = false
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
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
            ExportState.log("Open nahi hua: ${e.message}")
        }
    }
}
