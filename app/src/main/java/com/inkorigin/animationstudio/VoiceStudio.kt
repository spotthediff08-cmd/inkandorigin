package com.inkorigin.animationstudio

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceStudioPage(modifier: Modifier = Modifier, onAudioReady: (Uri, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var script by remember { mutableStateOf("") }
    var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var selected by remember { mutableStateOf<Voice?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var rate by remember { mutableFloatStateOf(1f) }
    var pitch by remember { mutableFloatStateOf(1f) }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Preparing Android voices…") }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(context) {
        val engine = TextToSpeech(context) { result ->
            if (result == TextToSpeech.SUCCESS) {
                val found = tts?.voices?.filter { !it.isNetworkConnectionRequired }
                    ?.sortedWith(compareBy({ it.locale.language != "en" }, { it.locale.displayName }, { it.name }))
                    .orEmpty()
                voices = found
                selected = found.firstOrNull()
                ready = true
                status = if (found.isEmpty()) "No offline voices found. Install voices in Android Text-to-Speech settings." else "Ready • §{found.size} installed offline voices"
            } else status = "Android Text-to-Speech initialization failed."
        }
        tts = engine
        onDispose { engine.stop(); engine.shutdown(); tts = null }
    }

    fun splitScript(text: String): List<String> {
        var rest = text.trim()
        val result = mutableListOf<String>()
        while (rest.length > 2200) {
            val part = rest.take(2200)
            val cut = listOf(part.lastIndexOf(". "), part.lastIndexOf("? "), part.lastIndexOf("! "), part.lastIndexOf("; "), part.lastIndexOf(", "), part.lastIndexOf(" "))
                .maxOrNull()?.takeIf { it > 1100 }?.plus(1) ?: 2200
            result += rest.take(cut).trim()
            rest = rest.drop(cut).trim()
        }
        if (rest.isNotBlank()) result += rest
        return result
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("VOICE STUDIO", color = Accent, style = MaterialTheme.typography.labelSmall)
        Text("Create narration", style = MaterialTheme.typography.headlineMedium)
        Text("Use voices installed on your Android device. No API key or subscription.", color = Muted)
        OutlinedTextField(script, { script = it }, Modifier.fillMaxWidth().height(230.dp), label = { Text("Paste your complete script") }, placeholder = { Text("Paste narration here…") }, minLines = 8)
        ExposedDropdownMenuBox(expanded, { expanded = it }) {
            OutlinedTextField(selected?.let { "§{it.name} • §{it.locale.displayName}" } ?: "Choose a voice", {}, Modifier.fillMaxWidth().menuAnchor(), readOnly = true, label = { Text("Android voice") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
            ExposedDropdownMenu(expanded, { expanded = false }) {
                voices.forEach { voice -> DropdownMenuItem(text = { Text("§{voice.name} • §{voice.locale.displayName}") }, onClick = { selected = voice; expanded = false }) }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Speech speed: §{"%.2f".format(rate)}×")
                Slider(rate, { rate = it }, valueRange = 0.7f..1.3f)
                Text("Pitch: §{"%.2f".format(pitch)}×")
                Slider(pitch, { pitch = it }, valueRange = 0.8f..1.2f)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(enabled = ready && !busy && script.isNotBlank() && selected != null, onClick = {
                tts?.apply { setVoice(selected); setSpeechRate(rate); setPitch(pitch); speak(script.take(3000), TextToSpeech.QUEUE_FLUSH, Bundle(), "preview-§{UUID.randomUUID()}") }
                status = "Playing short voice preview"
            }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.PlayArrow, null); Text(" Preview") }
            Button(enabled = ready && !busy && script.isNotBlank() && selected != null, onClick = {
                val engine = tts ?: return@Button
                val voice = selected ?: return@Button
                val chosenRate = rate
                val chosenPitch = pitch
                busy = true
                scope.launch {
                    try {
                        engine.setVoice(voice); engine.setSpeechRate(chosenRate); engine.setPitch(chosenPitch)
                        val parts = splitScript(script)
                        val dir = File(context.cacheDir, "voice-studio").apply { mkdirs() }
                        val outputs = mutableListOf<File>()
                        parts.forEachIndexed { index, part ->
                            status = "Generating chunk §{index + 1} / §{parts.size}…"
                            val file = File(dir, "voice-§{UUID.randomUUID()}.wav")
                            synthesizeToWav(engine, part, file)
                            outputs += file
                        }
                        val finalFile = File(File(context.filesDir, "voiceovers").apply { mkdirs() }, "voiceover-§{System.currentTimeMillis()}.wav")
                        withContext(Dispatchers.IO) { joinPcmWavFiles(outputs, finalFile) }
                        outputs.forEach { it.delete() }
                        onAudioReady(Uri.fromFile(finalFile), finalFile.name)
                        status = "Voiceover generated • §{parts.size} chunks combined"
                    } catch (e: Exception) {
                        status = "Generation failed: §{e.message ?: "Try another installed TTS voice"}"
                    } finally { busy = false }
                }
            }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.RecordVoiceOver, null); Text(if (busy) "Working…" else "Generate") }
        }
        Text(status, color = Muted, style = MaterialTheme.typography.bodySmall)
        Text("Long scripts are split at sentence or phrase boundaries. Voice quality and supported audio formats depend on the TTS engine installed on your phone.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

private suspend fun synthesizeToWav(engine: TextToSpeech, text: String, file: File) {
    suspendCancellableCoroutine<Unit> { cont ->
        val id = UUID.randomUUID().toString()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resumeWithException(IllegalStateException("TTS synthesis failed")) }
            override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id && cont.isActive) cont.resumeWithException(IllegalStateException("TTS error §{errorCode}")) }
        })
        val result = engine.synthesizeToFile(text, Bundle(), file, id)
        if (result != TextToSpeech.SUCCESS && cont.isActive) cont.resumeWithException(IllegalStateException("TTS rejected this text chunk"))
        cont.invokeOnCancellation { engine.stop(); file.delete() }
    }
}

private fun joinPcmWavFiles(files: List<File>, destination: File) {
    require(files.isNotEmpty()) { "No audio chunks created" }
    data class Part(val fmt: ByteArray, val pcm: ByteArray)
    fun read(file: File): Part {
        val bytes = file.readBytes()
        require(bytes.size > 44 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") {
            "TTS engine did not return WAV audio. Try a different Android TTS engine."
        }
        var pos = 12
        var fmt: ByteArray? = null
        var pcm: ByteArray? = null
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = (bytes[pos + 4].toInt() and 255) or ((bytes[pos + 5].toInt() and 255) shl 8) or ((bytes[pos + 6].toInt() and 255) shl 16) or ((bytes[pos + 7].toInt() and 255) shl 24)
            val start = pos + 8
            if (size < 0 || start + size > bytes.size) break
            if (id == "fmt " && size >= 16) fmt = bytes.copyOfRange(start, start + 16)
            if (id == "data") pcm = bytes.copyOfRange(start, start + size)
            pos = start + size + (size and 1)
        }
        val format = fmt ?: error("WAV format chunk missing")
        require(((format[0].toInt() and 255) or ((format[1].toInt() and 255) shl 8)) == 1) { "Only PCM WAV can be joined by this version" }
        return Part(format, pcm ?: error("WAV audio data missing"))
    }
    val parts = files.map(::read)
    require(parts.all { it.fmt.contentEquals(parts.first().fmt) }) { "TTS audio formats differ between chunks" }
    val audioBytes = parts.sumOf { it.pcm.size.toLong() }
    require(audioBytes <= Int.MAX_VALUE - 44) { "Voiceover file is too large" }
    val fmt = parts.first().fmt
    destination.outputStream().buffered().use { out ->
        val h = ByteArray(44)
        fun ascii(at: Int, s: String) { s.toByteArray(Charsets.US_ASCII).copyInto(h, at) }
        fun int(at: Int, v: Int) { repeat(4) { h[at + it] = (v ushr (it * 8)).toByte() } }
        ascii(0, "RIFF"); int(4, (36L + audioBytes).toInt()); ascii(8, "WAVE"); ascii(12, "fmt "); int(16, 16)
        fmt.copyInto(h, 20, 0, 16); ascii(36, "data"); int(40, audioBytes.toInt())
        out.write(h); parts.forEach { out.write(it.pcm) }
    }
}
