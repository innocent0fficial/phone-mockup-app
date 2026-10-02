package com.example.phonemockup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Brightness
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ExportService : Service() {

    companion object {
        const val ACTION_CANCEL = "com.example.phonemockup.CANCEL"
        const val EXTRA_PRESET = "preset"
        private const val CHANNEL = "export"
        private const val KEEP_OLD_FILES_MS = 7L * 24 * 60 * 60 * 1000
    }

    private class Saved(val uri: Uri, val keepFile: Boolean)

    private val handler = Handler(Looper.getMainLooper())
    private val progressHolder = ProgressHolder()
    private var transformer: Transformer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tempFile: File? = null

    /** Changes every time a job starts or ends, so late callbacks from an old job are ignored. */
    private var jobId = 0

    /** True while the finished video is being copied to the gallery (cannot be cancelled then). */
    @Volatile private var saving = false

    private val poll = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            val state = t.getProgress(progressHolder)
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                ExportState.update("Editing... ${progressHolder.progress}%", progressHolder.progress)
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            when {
                saving -> ExportState.log("Saving to gallery - cancel is not possible now")
                ExportState.running -> {
                    transformer?.cancel()
                    end("Cancelled", null)
                }
                else -> stopSelf()
            }
            return START_NOT_STICKY
        }
        val uri = intent?.data
        startForegroundNow()
        if (uri == null || ExportState.running) {
            if (!ExportState.running) stopSelf()
            return START_NOT_STICKY
        }
        begin(uri, Preset.from(intent.getStringExtra(EXTRA_PRESET)))
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        jobId++
        if (ExportState.running) {
            // The system stopped the service while a job was active: stop cleanly.
            try { transformer?.cancel() } catch (_: Exception) {}
            transformer = null
            if (!saving) { tempFile?.delete(); tempFile = null }
            ExportState.running = false
            ExportState.update("Stopped", ExportState.progress)
        }
        releaseLock()
        super.onDestroy()
    }

    private fun startForegroundNow() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Video export", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)
        val n = builder
            .setContentTitle("313 Edits")
            .setContentText("Editing your video. Please keep the app open.")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, n)
        }
    }

    private fun isCurrent(id: Int) = id == jobId && ExportState.running

    /** Called from the worker thread: report an error on the main thread. */
    private fun failLater(id: Int, message: String) {
        handler.post { if (isCurrent(id)) end(message, null) }
    }

    private fun begin(uri: Uri, preset: Preset) {
        val id = ++jobId
        ExportState.running = true
        ExportState.outputUri = null
        ExportState.clearLog()
        ExportState.update("Checking video...", 0)
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonemockup:export")
                .apply { acquire(4 * 60 * 60 * 1000L) }
        } catch (e: Exception) {
            ExportState.log("Wake lock unavailable: ${e.message}")
        }

        val appContext = applicationContext
        // Reading the video and building the overlay picture can be slow (cloud/slow storage):
        // do it off the main thread so the app never freezes.
        Thread {
            try {
                var w = 0
                var h = 0
                var rot = 0
                var durMs = 0L
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(appContext, uri)
                    w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } finally {
                    try { mmr.release() } catch (_: Exception) {}
                }
                if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                ExportState.log("Format: ${preset.label} (${preset.w}x${preset.h})")
                ExportState.log("Video: ${w}x${h}, rotation $rot, ${durMs / 1000} sec")
                if (w <= 0 || h <= 0) { failLater(id, "Could not read the video"); return@Thread }
                val aspect = w.toFloat() / h
                if (aspect >= 1f) { failLater(id, "Only portrait screen recordings are supported"); return@Thread }

                val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: appContext.filesDir
                cleanOldFiles(dir)
                val estBytes = (durMs / 1000.0 * (preset.bitrate + 160_000) / 8.0).toLong()
                val free = dir.usableSpace
                ExportState.log("Estimated size: ${estBytes / 1_000_000} MB, free: ${free / 1_000_000} MB")
                if (free < estBytes * 2.2) {
                    failLater(id, "Not enough storage. At least ${(estBytes * 2.2 / 1_000_000_000).toInt() + 1} GB of free space is needed.")
                    return@Thread
                }

                val out = File(dir, "mockup_${preset.name.lowercase()}_${System.currentTimeMillis()}.mp4")
                val overlayBitmap = Mockup.buildOverlay(appContext, preset, aspect)
                handler.post {
                    if (isCurrent(id)) startTransformer(id, uri, preset, aspect, out, overlayBitmap)
                    else overlayBitmap.recycle()
                }
            } catch (e: Throwable) {
                ExportState.log(Log.getStackTraceString(e))
                failLater(id, "Error: ${e.message ?: e.javaClass.simpleName}")
            }
        }.start()
    }

    /** Main thread only (Transformer must be created and started on a Looper thread). */
    private fun startTransformer(id: Int, uri: Uri, preset: Preset, aspect: Float, out: File, overlayBitmap: Bitmap) {
        try {
            tempFile = out
            val overlay = BitmapOverlay.createStaticBitmapOverlay(overlayBitmap)
            val videoEffects = mutableListOf<Effect>()
            if (Mockup.BRIGHTNESS != 0f) videoEffects.add(Brightness(Mockup.BRIGHTNESS))
            videoEffects.add(
                Presentation.createForWidthAndHeight(
                    preset.w, preset.h, Presentation.LAYOUT_SCALE_TO_FIT
                )
            )
            videoEffects.add(MatrixTransformation { Mockup.videoMatrix(preset, aspect) })
            videoEffects.add(OverlayEffect(ImmutableList.of(overlay)))
            val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setEffects(Effects(emptyList(), videoEffects))
                .build()

            val encoderFactory = DefaultEncoderFactory.Builder(this)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder().setBitrate(preset.bitrate).build()
                )
                .build()

            val t = Transformer.Builder(this)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        handler.removeCallbacks(poll)
                        transformer = null
                        val f = tempFile
                        if (!isCurrent(id) || f == null) return
                        saving = true
                        ExportState.update("Saving to gallery...", 100)
                        Thread {
                            var saved: Saved? = null
                            try {
                                saved = saveToGallery(f)
                            } catch (e: Throwable) {
                                ExportState.log(Log.getStackTraceString(e))
                            }
                            handler.post {
                                saving = false
                                if (!isCurrent(id)) return@post
                                if (saved != null) {
                                    val where = if (saved.keepFile) "the gallery (app Movies folder)" else "Movies/PhoneMockup"
                                    end("Done! ${preset.label} video saved in $where.", saved.uri, saved.keepFile)
                                } else {
                                    end("Could not save to gallery. File is here: ${f.absolutePath}", null, true)
                                }
                            }
                        }.start()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        ExportState.log(Log.getStackTraceString(exportException))
                        if (isCurrent(id)) end("Error: ${exportException.errorCodeName}", null)
                    }
                })
                .build()
            transformer = t
            ExportState.update("Starting edit...", 0)
            t.start(item, out.absolutePath)
            handler.postDelayed(poll, 1000)
        } catch (e: Throwable) {
            ExportState.log(Log.getStackTraceString(e))
            end("Error: ${e.message ?: e.javaClass.simpleName}", null)
        }
    }

    /** Old temp videos left behind by failed saves / crashes (Android 10+ only, see saveToGallery). */
    private fun cleanOldFiles(dir: File) {
        if (Build.VERSION.SDK_INT < 29) return
        val cutoff = System.currentTimeMillis() - KEEP_OLD_FILES_MS
        try {
            dir.listFiles()?.forEach {
                if (it.isFile && it.name.startsWith("mockup_") && it.name.endsWith(".mp4") && it.lastModified() < cutoff) {
                    it.delete()
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Runs on a worker thread. Throws on failure (the caller logs it). */
    private fun saveToGallery(file: File): Saved? {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PhoneMockup")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                val os = contentResolver.openOutputStream(uri) ?: throw IOException("Cannot open gallery output")
                os.use { o -> file.inputStream().use { it.copyTo(o, 1 shl 20) } }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                return Saved(uri, false)
            } catch (e: Throwable) {
                // never leave a half-written "pending" entry in the gallery
                try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                throw e
            }
        }
        // Android 7-9: the file already sits in the app's Movies folder; register it with the gallery.
        val latch = CountDownLatch(1)
        var scanned: Uri? = null
        MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("video/mp4")) { _, u ->
            scanned = u
            latch.countDown()
        }
        if (!latch.await(15, TimeUnit.SECONDS)) return null
        val u = scanned ?: return null
        return Saved(u, true)
    }

    private fun releaseLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
    }

    private fun end(message: String, output: Uri?, keepFile: Boolean = false) {
        jobId++
        saving = false
        handler.removeCallbacks(poll)
        transformer = null
        if (!keepFile) tempFile?.delete()
        tempFile = null
        releaseLock()
        ExportState.outputUri = output
        ExportState.running = false
        ExportState.update(message, if (output != null) 100 else ExportState.progress)
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
