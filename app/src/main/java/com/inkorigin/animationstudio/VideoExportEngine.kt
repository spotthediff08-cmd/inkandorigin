package com.inkorigin.animationstudio

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import androidx.media3.transformer.Effects
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class ExportSettings(
    val resolution: String = "1080p",
    val aspectRatio: String = "16:9",
    val subtitleFontSize: Int = 32,
    val subtitleColor: String = "White",
    val subtitlePosition: String = "Bottom",
    val subtitleOutline: Boolean = true,
    val subtitleBackground: Boolean = false
)

@UnstableApi
object VideoExportEngine {
    fun export(
        context: Context,
        scenes: List<Scene>,
        audioUri: Uri?,
        srtText: String,
        settings: ExportSettings,
        onStarted: () -> Unit,
        onCompleted: (Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        if (scenes.isEmpty()) {
            onError("Add at least one scene image before exporting.")
            return
        }
        val widthHeight = outputSize(settings.resolution, settings.aspectRatio)
        val cues = SubtitleEngine.parse(srtText)
        val videoItems = scenes.map { scene ->
            val durationMs = (scene.duration.coerceIn(0.5f, 300f) * 1000).toLong()
            val mediaItem = MediaItem.Builder().setUri(scene.uri).setImageDurationMs(durationMs).build()
            val motion = motionEffect(scene.motion, durationMs)
            EditedMediaItem.Builder(mediaItem)
                .setFrameRate(30)
                .setEffects(Effects(emptyList(), listOf(motion)))
                .build()
        }
        val videoSequence = EditedMediaItemSequence.withVideoFrom(videoItems)
        val sequences = mutableListOf(videoSequence)
        if (audioUri != null) {
            val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(audioUri)).build()
            sequences += EditedMediaItemSequence.withAudioFrom(listOf(audioItem))
        }
        val videoEffects = mutableListOf<androidx.media3.common.Effect>()
        videoEffects += Presentation.createForWidthAndHeight(
            widthHeight.first, widthHeight.second, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
        )
        if (cues.isNotEmpty()) {
            videoEffects += OverlayEffect(listOf(SubtitleCanvasOverlay(cues, settings)))
        }
        val composition = Composition.Builder(sequences)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        val output = File(context.cacheDir, "animation_studio_${System.currentTimeMillis()}.mp4")
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    Thread {
                        runCatching { publishToGallery(context, output) }
                            .onSuccess { uri -> Handler(Looper.getMainLooper()).post { onCompleted(uri) } }
                            .onFailure { error -> Handler(Looper.getMainLooper()).post { onError(error.message ?: "Could not save exported video") } }
                    }.start()
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    Handler(Looper.getMainLooper()).post {
                        onError(exportException.message ?: "Video export failed on this device.")
                    }
                }
            })
            .build()
        runCatching {
            onStarted()
            transformer.start(composition, output.absolutePath)
        }.onFailure { onError(it.message ?: "Could not start video export.") }
    }

    private fun outputSize(resolution: String, aspectRatio: String): Pair<Int, Int> {
        val shortSide = when (resolution) {
            "720p" -> 720
            "4K" -> 2160
            else -> 1080
        }
        return when (aspectRatio) {
            "9:16" -> Pair(shortSide, shortSide * 16 / 9)
            "1:1" -> Pair(shortSide, shortSide)
            else -> Pair(shortSide * 16 / 9, shortSide)
        }
    }

    private fun motionEffect(motion: String, durationMs: Long): MatrixTransformation =
        MatrixTransformation { presentationTimeUs ->
            val progress = (presentationTimeUs / (durationMs * 1000f)).coerceIn(0f, 1f)
            val matrix = android.graphics.Matrix()
            val scale = when (motion) {
                "Zoom In", "Auto Zoom" -> 1f + 0.12f * progress
                "Zoom Out" -> 1.12f - 0.12f * progress
                "Zoom In-Out" -> 1f + 0.10f * sin(progress * Math.PI).toFloat()
                "Zoom Out-In" -> 1.10f - 0.10f * sin(progress * Math.PI).toFloat()
                "Cinematic", "Ken Burns" -> 1f + 0.09f * progress
                else -> 1.04f
            }
            matrix.setScale(scale, scale, 0f, 0f)
            when (motion) {
                "Pan Left" -> matrix.postTranslate(0.16f - 0.32f * progress, 0f)
                "Pan Right" -> matrix.postTranslate(-0.16f + 0.32f * progress, 0f)
                "Ken Burns", "Cinematic" -> matrix.postTranslate(-0.08f + 0.16f * progress, 0.04f - 0.08f * progress)
                else -> Unit
            }
            matrix
        }

    private fun publishToGallery(context: Context, source: File): Uri {
        val name = "AnimationStudio_${System.currentTimeMillis()}.mp4"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Animation Studio")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            } else {
                val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Animation Studio")
                if (!folder.exists()) folder.mkdirs()
                put(MediaStore.Video.Media.DATA, File(folder, name).absolutePath)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Gallery could not create the output video.")
        try {
            resolver.openOutputStream(uri)?.use { output -> source.inputStream().use { it.copyTo(output) } }
                ?: error("Gallery output stream is unavailable.")
            if (Build.VERSION.SDK_INT >= 29) {
                val ready = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                resolver.update(uri, ready, null, null)
            }
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}

@UnstableApi
private class SubtitleCanvasOverlay(
    private val cues: List<SubtitleCue>,
    private val settings: ExportSettings
) : CanvasOverlay(true) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB8000000.toInt() }

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        val cue = cues.firstOrNull {
            presentationTimeUs / 1000 >= it.startMs && presentationTimeUs / 1000 < it.endMs
        } ?: return
        val h = canvas.height.toFloat()
        val w = canvas.width.toFloat()
        val size = settings.subtitleFontSize * (h / 1080f)
        fill.textSize = size
        fill.color = when (settings.subtitleColor) {
            "Yellow" -> android.graphics.Color.YELLOW
            "Cyan" -> android.graphics.Color.CYAN
            else -> android.graphics.Color.WHITE
        }
        stroke.textSize = size
        stroke.color = android.graphics.Color.BLACK
        stroke.strokeWidth = max(2f, size * 0.10f)
        val maxWidth = w * 0.88f
        val lines = wrap(cue.text, fill, maxWidth)
        val lineHeight = size * 1.22f
        val blockHeight = lineHeight * lines.size
        val firstBaseline = when (settings.subtitlePosition) {
            "Top" -> h * 0.13f + size
            "Center" -> (h - blockHeight) / 2f + size
            else -> h * 0.86f - blockHeight + size
        }
        if (settings.subtitleBackground) {
            val widest = lines.maxOfOrNull { fill.measureText(it) } ?: 0f
            val rect = RectF(w / 2f - widest / 2f - size * 0.35f, firstBaseline - size,
                w / 2f + widest / 2f + size * 0.35f, firstBaseline + blockHeight - lineHeight + size * 0.35f)
            canvas.drawRoundRect(rect, size * 0.18f, size * 0.18f, boxPaint)
        }
        lines.forEachIndexed { index, line ->
            val y = firstBaseline + index * lineHeight
            if (settings.subtitleOutline) canvas.drawText(line, w / 2f, y, stroke)
            canvas.drawText(line, w / 2f, y, fill)
        }
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val output = mutableListOf<String>()
        text.split("\n").forEach { paragraph ->
            var line = ""
            paragraph.split(Regex("\\s+")).forEach { word ->
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && paint.measureText(candidate) > maxWidth) {
                    output += line
                    line = word
                } else line = candidate
            }
            if (line.isNotEmpty()) output += line
        }
        return output.ifEmpty { listOf("") }
    }
}
