package com.example.phonemockup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
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

class ExportService : Service() {

    companion object {
        const val ACTION_CANCEL = "com.example.phonemockup.CANCEL"
        const val VIDEO_BITRATE = 8_000_000   // 8 Mbps -> about 3.6 GB per hour
        private const val CHANNEL = "export"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val progressHolder = ProgressHolder()
    private var transformer: Transformer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tempFile: File? = null

    private val poll = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            val state = t.getProgress(progressHolder)
            if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                ExportState.update("Edit ho rahi hai... ${progressHolder.progress}%", progressHolder.progress)
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            transformer?.cancel()
            if (ExportState.running) end("Cancel ho gaya", null) else stopSelf()
            return START_NOT_STICKY
        }
        val uri = intent?.data
        startForegroundNow()
        if (uri == null || ExportState.running) {
            if (!ExportState.running) stopSelf()
            return START_NOT_STICKY
        }
        begin(uri)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
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
            .setContentTitle("Phone Mockup")
            .setContentText("Video edit ho rahi hai. App band na karein.")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, n)
        }
    }

    private fun begin(uri: Uri) {
        ExportState.running = true
        ExportState.outputUri = null
        ExportState.clearLog()
        ExportState.update("Video check ho rahi hai...", 0)
        try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(this, uri)
            var w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            mmr.release()
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            ExportState.log("Video: ${w}x${h}, rotation $rot, ${durMs / 1000} sec")
            if (w <= 0 || h <= 0) { end("Video padhi nahi ja saki", null); return }
            val aspect = w.toFloat() / h
            if (aspect >= 1f) { end("Sirf portrait (khari) screen recording chalti hai", null); return }

            val dir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
            val estBytes = (durMs / 1000.0 * (VIDEO_BITRATE + 160_000) / 8.0).toLong()
            val free = dir.usableSpace
            ExportState.log("Andaza size: ${estBytes / 1_000_000} MB, free: ${free / 1_000_000} MB")
            if (free < estBytes * 2.2) {
                end("Storage kam hai. Kam az kam ${(estBytes * 2.2 / 1_000_000_000).toInt() + 1} GB khali chahiye.", null)
                return
            }

            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonemockup:export")
                .apply { acquire(4 * 60 * 60 * 1000L) }

            val out = File(dir, "mockup_${System.currentTimeMillis()}.mp4")
            tempFile = out

            val overlay = BitmapOverlay.createStaticBitmapOverlay(Mockup.buildOverlay(this, aspect))
            val videoEffects = mutableListOf<Effect>()
            if (Mockup.BRIGHTNESS != 0f) videoEffects.add(Brightness(Mockup.BRIGHTNESS))
            videoEffects.add(
                Presentation.createForWidthAndHeight(
                    Mockup.OUT_W, Mockup.OUT_H, Presentation.LAYOUT_SCALE_TO_FIT
                )
            )
            videoEffects.add(MatrixTransformation { Mockup.videoMatrix(aspect) })
            videoEffects.add(OverlayEffect(ImmutableList.of(overlay)))
            val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setEffects(Effects(emptyList(), videoEffects))
                .build()

            val encoderFactory = DefaultEncoderFactory.Builder(this)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder().setBitrate(VIDEO_BITRATE).build()
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
                        ExportState.update("Gallery mein save ho raha hai...", 100)
                        val f = tempFile
                        if (f == null) { end("File nahi mili", null); return }
                        Thread {
                            val saved = try {
                                saveToGallery(f)
                            } catch (e: Exception) {
                                ExportState.log(Log.getStackTraceString(e))
                                null
                            }
                            handler.post {
                                if (saved != null) {
                                    end("Ho gaya! Movies/PhoneMockup folder mein hai.", saved)
                                } else {
                                    end("Gallery mein save nahi hua. File yahan hai: ${f.absolutePath}", null, true)
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
                        end("Error: ${exportException.errorCodeName}", null)
                    }
                })
                .build()
            transformer = t
            ExportState.update("Edit shuru...", 0)
            t.start(item, out.absolutePath)
            handler.postDelayed(poll, 1000)
        } catch (e: Exception) {
            ExportState.log(Log.getStackTraceString(e))
            end("Error: ${e.message}", null)
        }
    }

    private fun saveToGallery(file: File): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PhoneMockup")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val os = contentResolver.openOutputStream(uri) ?: return null
        os.use { o -> file.inputStream().use { it.copyTo(o, 1 shl 20) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    private fun releaseLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
    }

    private fun end(message: String, output: Uri?, keepFile: Boolean = false) {
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
