package com.example.phonemockup

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * Shows the TEAM 313 loader (assets/loader.html) on top of the app.
 * The app screen below stays hidden until the loader finishes, then the overlay is removed.
 * Safety: if the loader cannot start, fails to load, crashes, or takes longer than
 * MAX_LOADER_MS, the overlay is removed anyway so the app can never get stuck on a white screen.
 * Reusable: in any Activity call  LoaderOverlay.attach(this, contentView)  instead of setContentView.
 */
object LoaderOverlay {

    private const val MAX_LOADER_MS = 10_000L

    fun attach(activity: Activity, content: View, showLoader: Boolean = true) {
        val frame = FrameLayout(activity)
        frame.addView(content, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        if (showLoader) {
            try {
                addLoader(activity, frame)
            } catch (e: Throwable) {
                // No WebView on this device (or it failed to start): skip the loader, show the app.
            }
        }
        activity.setContentView(frame)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun addLoader(activity: Activity, frame: FrameLayout) {
        val handler = Handler(Looper.getMainLooper())
        val cover = FrameLayout(activity).apply { setBackgroundColor(Color.WHITE) }
        val web = WebView(activity)
        var finished = false

        // Always runs on the main thread. Safe to call many times.
        fun finish() {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            frame.removeView(cover)
            cover.removeAllViews()
            try {
                web.removeJavascriptInterface("AndroidLoader")
                web.stopLoading()
                web.destroy()
            } catch (_: Throwable) {
            }
        }

        web.apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            // file:///android_asset/ still works with file access off; the loader only uses its own assets.
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError
                ) {
                    if (request.isForMainFrame) handler.post { finish() }
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    handler.post { finish() }
                    return true
                }
            }
            addJavascriptInterface(object {
                @JavascriptInterface
                fun done() {
                    handler.post { finish() }
                }
            }, "AndroidLoader")
        }

        cover.addView(web, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(cover, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        handler.postDelayed({ finish() }, MAX_LOADER_MS)
        web.loadUrl("file:///android_asset/loader.html")
    }
}
