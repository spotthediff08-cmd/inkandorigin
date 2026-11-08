package com.inkorigin.animationstudio

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
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
import androidx.media3.transformer.ProgressHolder
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

data class ExportSettings(
    val resolution: String = "1080p",
    val aspectRatio: String = "16:9",
    val subtitleFontSize: Int = 32,
    val subtitleColor: String = "White",
    val subtitlePosition: String = "Bottom",
    val subtitleOutline: Boolean = true,
    val subtitleBackground: Boolean = false,
    val frameRate: Int = 30,
    val transition: String = "Fade through black"
)

@UnstableApi
object VideoExportEngine {
    @Volatile private var activeTransformer: Transformer? = null
    @Volatile private var activeOutput: File? = null

    fun progressPercent(): Int? {
        val current = activeTransformer ?: return null
        val holder = ProgressHolder()
        return runCatching {
            if (current.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else null
        }.getOrNull()
    }

    fun cancel(): Boolean {
        val current = activeTransformer ?: return false
        return runCatching {
            current.cancel()
            activeTransformer = null
            activeOutput?.delete()
            activeOutput = null
            true
        }.getOrDefault(false)
    }

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
        val videoItems = scenes.mapIndexed { sceneIndex, scene ->
            val durationMs = (scene.duration.coerceIn(0.5f, 300f) * 1000).toLong()
            val mediaItem = MediaItem.Builder().setUri(scene.uri).setImageDurationMs(durationMs).build()
            val motion = motionEffect(scene.motion, durationMs, sceneIndex)
            EditedMediaItem.Builder(mediaItem)
                 .setFrameRate(settings.frameRate)
                .setEffects(Effects(emptyList(), listOf(motion)))
                .build()
        }
        val videoSequence = EditedMediaItemSequence.withVideoFrom(videoItems)
        val sceneBoundariesMs = mutableListOf<Long>()
        var sceneBoundaryMs = 0L
        scenes.dropLast(1).forEach { scene ->
            sceneBoundaryMs += (scene.duration.coerceIn(0.5f, 300f) * 1000).toLong()
            sceneBoundariesMs += sceneBoundaryMs
        }
        val sequences = mutableListOf(videoSequence)
        if (audioUri != null) {
            val totalVideoDurationMs = scenes.sumOf { (it.duration.coerceIn(0.5f, 300f) * 1000).toLong() }
            val audioDurationMs = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, audioUri)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
                } finally {
                    retriever.release()
                }
            }.getOrNull()
            val audioItem = if (audioDurationMs != null && audioDurationMs > 0) {
                val clippedDuration = min(audioDurationMs, totalVideoDurationMs)
                val audioMediaItem = MediaItem.Builder().setUri(audioUri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(0)
                            .setEndPositionMs(clippedDuration)
                            .build()
                    ).build()
                EditedMediaItem.Builder(audioMediaItem).setDurationUs(audioDurationMs * 1000L).build()
            } else {
                EditedMediaItem.Builder(MediaItem.fromUri(audioUri)).build()
            }
            sequences += EditedMediaItemSequence.withAudioFrom(listOf(audioItem))
        }
        val videoEffects = mutableListOf<androidx.media3.common.Effect>()
        videoEffects += Presentation.createForWidthAndHeight(
            widthHeight.first, widthHeight.second, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
        )
        val overlays = mutableListOf<androidx.media3.effect.TextureOverlay>()
        if (settings.transition == "Fade through black" && sceneBoundariesMs.isNotEmpty()) {
            overlays += SceneFadeOverlay(sceneBoundariesMs)
        }
        if (cues.isNotEmpty()) overlays += SubtitleCanvasOverlay(cues, settings)
        if (overlays.isNotEmpty()) videoEffects += OverlayEffect(overlays)
        val composition = Composition.Builder(sequences)
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        val output = File(context.cacheDir, "animation_studio_${System.currentTimeMillis()}.mp4")
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    activeTransformer = null
                    activeOutput = null
                    Thread {
                        runCatching { publishToGallery(context, output) }
                            .onSuccess { uri -> output.delete(); Handler(Looper.getMainLooper()).post { onCompleted(uri) } }
                            .onFailure { error -> output.delete(); Handler(Looper.getMainLooper()).post { onError(error.message ?: "Could not save exported video") } }
                    }.start()
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    activeTransformer = null
                    activeOutput = null
                    output.delete()
                    Handler(Looper.getMainLooper()).post {
                        onError(exportException.message ?: "Video export failed on this device.")
                    }
                }
            })
            .build()
        activeTransformer = transformer
        activeOutput = output
        runCatching {
            onStarted()
            transformer.start(composition, output.absolutePath)
        }.onFailure {
            activeTransformer = null
            activeOutput = null
            output.delete()
            onError(it.message ?: "Could not start video export.")
        }
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

    private fun motionEffect(motion: String, durationMs: Long, sceneIndex: Int): MatrixTransformation {
        val actualMotion = when (motion) {
            "Random" -> listOf("Zoom In", "Zoom Out", "Pan Left", "Pan Right", "Ken Burns")[sceneIndex % 5]
            "Alternate" -> if (sceneIndex % 2 == 0) "Zoom In" else "Zoom Out"
            else -> motion
        }
        return MatrixTransformation { presentationTimeUs ->
            val progress = (presentationTimeUs / (durationMs * 1000f)).coerceIn(0f, 1f)
            val matrix = android.graphics.Matrix()
            val scale = when (actualMotion) {
                "Static" -> 1f
                "Zoom In", "Auto Zoom" -> 1f + 0.12f * progress
                "Zoom Out" -> 1.12f - 0.12f * progress
                "Zoom In-Out" -> 1f + 0.10f * sin(progress * Math.PI).toFloat()
                "Zoom Out-In" -> 1.10f - 0.10f * sin(progress * Math.PI).toFloat()
                "Cinematic", "Ken Burns", "Zoom+Pan" -> 1f + 0.09f * progress
                else -> 1.04f
            }
            matrix.setScale(scale, scale, 0f, 0f)
            when (actualMotion) {
                "Pan Left" -> matrix.postTranslate(0.16f - 0.32f * progress, 0f)
                "Pan Right" -> matrix.postTranslate(-0.16f + 0.32f * progress, 0f)
                "Pan Up" -> matrix.postTranslate(0f, 0.16f - 0.32f * progress)
                "Pan Down" -> matrix.postTranslate(0f, -0.16f + 0.32f * progress)
                "Ken Burns", "Cinematic", "Zoom+Pan" -> matrix.postTranslate(-0.08f + 0.16f * progress, 0.04f - 0.08f * progress)
                else -> Unit
            }
            matrix
        }
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
private class SceneFadeOverlay(
    private val sceneBoundariesMs: List<Long>,
    private val halfFadeDurationMs: Long = 250L
) : CanvasOverlay(true) {
    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        val timeMs = presentationTimeUs / 1000L
        val alpha = sceneBoundariesMs.maxOfOrNull { boundary ->
            (1f - abs(timeMs - boundary).toFloat() / halfFadeDurationMs).coerceIn(0f, 1f)
        } ?: 0f
        if (alpha > 0f) {
            canvas.drawColor(android.graphics.Color.argb((alpha * 255).toInt(), 0, 0, 0))
        }
    }
}

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
        val words = cue.text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val lines = wrapWords(words, fill, maxWidth)
        val lineHeight = size * 1.22f
        val blockHeight = lineHeight * lines.size
        val firstBaseline = when (settings.subtitlePosition) {
            "Top" -> h * 0.13f + size
            "Center" -> (h - blockHeight) / 2f + size
            else -> h * 0.86f - blockHeight + size
        }
        val lineWidths = lines.map { line -> line.sumOf { fill.measureText(it).toDouble() }.toFloat() + max(0, line.size - 1) * fill.measureText(" ") }
        if (settings.subtitleBackground) {
            val widest = lineWidths.maxOrNull() ?: 0f
            val rect = RectF(w / 2f - widest / 2f - size * 0.35f, firstBaseline - size,
                w / 2f + widest / 2f + size * 0.35f, firstBaseline + blockHeight - lineHeight + size * 0.35f)
            canvas.drawRoundRect(rect, size * 0.18f, size * 0.18f, boxPaint)
        }
        val elapsed = (presentationTimeUs / 1000 - cue.startMs).coerceAtLeast(0)
        val cueDuration = (cue.endMs - cue.startMs).coerceAtLeast(1)
        val activeWord = ((elapsed.toDouble() / cueDuration) * words.size).toInt().coerceIn(0, max(0, words.lastIndex))
        var wordIndex = 0
        lines.forEachIndexed { lineIndex, line ->
            val y = firstBaseline + lineIndex * lineHeight
            var x = w / 2f - lineWidths[lineIndex] / 2f
            line.forEachIndexed { itemIndex, word ->
                val wordWidth = fill.measureText(word)
                val centerX = x + wordWidth / 2f
                if (settings.subtitleOutline) canvas.drawText(word, centerX, y, stroke)
                fill.color = if (wordIndex == activeWord) android.graphics.Color.YELLOW else when (settings.subtitleColor) {
                    "Yellow" -> android.graphics.Color.YELLOW
                    "Cyan" -> android.graphics.Color.CYAN
                    else -> android.graphics.Color.WHITE
                }
                canvas.drawText(word, centerX, y, fill)
                x += wordWidth + fill.measureText(" ")
                wordIndex++
            }
        }
    }

    private fun wrapWords(words: List<String>, paint: Paint, maxWidth: Float): List<List<String>> {
        val output = mutableListOf<MutableList<String>>()
        var line = mutableListOf<String>()
        var width = 0f
        words.forEach { word ->
            val wordWidth = paint.measureText(word)
            val extra = if (line.isEmpty()) wordWidth else paint.measureText(" ") + wordWidth
            if (line.isNotEmpty() && width + extra > maxWidth) {
                output += line
                line = mutableListOf(word)
                width = wordWidth
            } else {
                line += word
                width += extra
            }
        }
        if (line.isNotEmpty()) output += line
        return output.ifEmpty { listOf(mutableListOf("")) }
    }
}
