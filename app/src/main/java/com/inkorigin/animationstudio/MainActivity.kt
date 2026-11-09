package com.inkorigin.animationstudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.content.Intent
import androidx.documentfile.provider.DocumentFile
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.io.FileOutputStream

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.InputStreamReader

private val Bg = Color(0xFF0B0D12)
private val Panel = Color(0xFF171B24)
private val Accent = Color(0xFFB89AFF)
private val Muted = Color(0xFF9299AA)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StudioApp() }
    }
}

data class Scene(val uri: Uri, val name: String, val duration: Float = 4f, val motion: String = "Cinematic")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("Home") }
    var scenes by remember { mutableStateOf(listOf<Scene>()) }
    var sceneUndo by remember { mutableStateOf(emptyList<List<Scene>>()) }
    var sceneRedo by remember { mutableStateOf(emptyList<List<Scene>>()) }
    fun updateScenes(next: List<Scene>) {
        if (next != scenes) { sceneUndo = (sceneUndo + listOf(scenes)).takeLast(30); sceneRedo = emptyList(); scenes = next }
    }
    fun undoScenes() {
        val previous = sceneUndo.lastOrNull() ?: return
        sceneRedo = (sceneRedo + listOf(scenes)).takeLast(30)
        sceneUndo = sceneUndo.dropLast(1)
        scenes = previous
    }
    fun redoScenes() {
        val next = sceneRedo.lastOrNull() ?: return
        sceneUndo = (sceneUndo + listOf(scenes)).takeLast(30)
        sceneRedo = sceneRedo.dropLast(1)
        scenes = next
    }
    var audio by remember { mutableStateOf<Uri?>(null) }
    var srtText by remember { mutableStateOf("") }
    val timelineSrtCueCount = remember(srtText) { SubtitleEngine.parse(srtText).size }
    var srtName by remember { mutableStateOf("No timeline SRT selected") }
    var wordSrtText by remember { mutableStateOf("") }
    var wordSrtName by remember { mutableStateOf("No word-level SRT selected") }
    var projectName by remember { mutableStateOf("Untitled Project") }
    var status by remember { mutableStateOf("Ready to create") }
    var resolution by remember { mutableStateOf("1080p") }
    var globalMotion by remember { mutableStateOf("Cinematic") }
    var aspectRatio by remember { mutableStateOf("16:9") }
    var frameRate by remember { mutableIntStateOf(30) }
    var transition by remember { mutableStateOf("None") }
    var subtitleFontSize by remember { mutableIntStateOf(64) }
    var subtitleColor by remember { mutableStateOf("White") }
    var subtitlePosition by remember { mutableStateOf("Bottom") }
    var subtitleOutline by remember { mutableStateOf(true) }
    var subtitleBackground by remember { mutableStateOf(false) }
    var projectId by remember { mutableStateOf("project_" + System.currentTimeMillis()) }
    var savedProjects by remember { mutableStateOf(emptyList<ProjectSummary>()) }
    var showProjects by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableIntStateOf(0) }
    var exportStatus by remember { mutableStateOf("Ready to export") }
    var lastExportUri by remember { mutableStateOf<Uri?>(null) }
    var exportHistory by remember { mutableStateOf(emptyList<ExportHistoryEntry>()) }

    LaunchedEffect(Unit) {
        savedProjects = withContext(Dispatchers.IO) { ProjectStore.list(context) }
        exportHistory = withContext(Dispatchers.IO) { ExportHistoryStore.list(context) }
    }
    LaunchedEffect(exporting) {
        while (exporting) {
            VideoExportEngine.progressPercent()?.let { exportProgress = it }
            delay(500)
        }
    }
    LaunchedEffect(projectId, projectName, scenes, audio, srtText, wordSrtText, resolution, aspectRatio, globalMotion, subtitleFontSize, subtitleColor, subtitlePosition, subtitleOutline, subtitleBackground, frameRate, transition) {
        delay(900)
        runCatching {
            withContext(Dispatchers.IO) { ProjectStore.save(context, projectId, projectName, scenes, audio, srtText, wordSrtText, resolution, aspectRatio, globalMotion, subtitleFontSize, subtitleColor, subtitlePosition, subtitleOutline, subtitleBackground, frameRate, transition) }
            savedProjects = withContext(Dispatchers.IO) { ProjectStore.list(context) }
        }.onFailure { status = "Autosave error: " + (it.message ?: "Unknown error") }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folderUri ->
        if (folderUri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(folderUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            scope.launch {
                val imported = withContext(Dispatchers.IO) { DocumentFile.fromTreeUri(context, folderUri)?.let { collectImageDocuments(it) } ?: emptyList() }
                if (imported.isNotEmpty()) {
                    val combined = (scenes + imported.map { file -> Scene(file.uri, file.name ?: "Image", motion = globalMotion) }).sortedWith(compareBy { naturalKey(it.name) })
                    val timed = applyTimelineSrt(combined, srtText)
                    updateScenes(timed)
                    val cueCount = SubtitleEngine.parse(srtText).size
                    status = if (cueCount > 0 && cueCount != combined.size) {
                        "${imported.size} images imported • SRT has $cueCount cues for ${combined.size} images; matched in order"
                    } else "${imported.size} images imported from folder"
                } else status = "No images found in selected folder"
            }
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri -> runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            val importedScenes = uris.map { Scene(it, it.lastPathSegment?.substringAfterLast('/') ?: "Scene", motion = globalMotion) }
            val combined = (scenes + importedScenes).sortedWith(compareBy { naturalKey(it.name) })
            updateScenes(applyTimelineSrt(combined, srtText))
            val cueCount = SubtitleEngine.parse(srtText).size
            status = if (cueCount > 0 && cueCount != combined.size) {
                "${importedScenes.size} images imported • SRT has $cueCount cues for ${combined.size} images; matched in order"
            } else "${importedScenes.size} images imported"
        }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; audio = uri; status = "Voiceover selected" }
    }
    val srtPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            srtName = uri.lastPathSegment ?: "Timeline SRT"
            status = "Reading subtitle file…"
            scope.launch {
                try {
                    val loadedText = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                            reader.readText().removePrefix("\uFEFF")
                        } ?: throw IllegalStateException("Cannot open subtitle file")
                    }
                    srtText = loadedText
                    val parsed = SubtitleEngine.parse(loadedText)
                    if (scenes.isNotEmpty() && parsed.isNotEmpty()) {
                        updateScenes(applyTimelineSrt(scenes, loadedText))
                    }
                    status = if (parsed.isNotEmpty()) {
                        if (scenes.isNotEmpty() && parsed.size != scenes.size) {
                            "Timeline SRT loaded • ${parsed.size} cues for ${scenes.size} images; matched in order"
                        } else "Timeline SRT loaded • ${parsed.size} image timings matched"
                    } else {
                        "SRT loaded • no standard timestamps detected"
                    }
                } catch (e: Exception) {
                    status = "SRT import failed: ${e.message ?: "Unknown error"}"
                }
            }
        }
    }

    val wordSrtPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText().removePrefix("\uFEFF") } ?: error("Cannot read SRT") } }
                    .onSuccess { wordSrtText = it; wordSrtName = uri.lastPathSegment ?: "Word-level SRT"; status = "Word-level SRT loaded" }
                    .onFailure { status = "Subtitle SRT import failed: ${it.message}" }
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Accent, background = Bg, surface = Panel,
        onBackground = Color.White, onSurface = Color.White
    )) {
        Scaffold(
            containerColor = Bg,
            topBar = {
                TopAppBar(
                    title = { Column { Text("ANIMATION STUDIO", fontSize = 17.sp, fontWeight = FontWeight.Bold); Text("Offline video workspace", color = Muted, fontSize = 11.sp) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Panel) {
                    listOf("Home", "Scenes", "Storyboard", "Subtitles", "Export").forEach { item ->
                        NavigationBarItem(
                            selected = tab == item, onClick = { tab = item },
                            icon = { Icon(when(item) { "Scenes" -> Icons.Default.Collections; "Subtitles" -> Icons.Default.Subtitles; "Export" -> Icons.Default.Movie; else -> Icons.Default.Home }, null) },
                            label = { Text(item) }
                        )
                    }
                }
            }
        ) { padding ->
            when(tab) {
                "Home" -> HomePage(Modifier.padding(padding), projectName, { projectName = it }, scenes.size, audio != null, srtName, status, { folderPicker.launch(null) }, { audioPicker.launch(arrayOf("audio/*")) }, { srtPicker.launch(arrayOf("application/x-subrip", "text/plain", "*/*")) }, { tab = "Scenes" }, { showProjects = true })
                "Scenes" -> ScenesPage(Modifier.padding(padding), scenes, ::updateScenes, { imagePicker.launch(arrayOf("image/*")) }, { tab = "Export" }, globalMotion, { selectedMotion -> globalMotion = selectedMotion; updateScenes(scenes.map { scene -> scene.copy(motion = selectedMotion) }) }, sceneUndo.isNotEmpty(), sceneRedo.isNotEmpty(), ::undoScenes, ::redoScenes, wordSrtText, audio, transition, subtitleFontSize, subtitleColor, subtitlePosition, subtitleOutline, subtitleBackground)
                "Storyboard" -> StoryboardPage(Modifier.padding(padding), scenes, ::updateScenes, srtText)
            "Subtitles" -> SubtitlePage(Modifier.padding(padding), wordSrtName, wordSrtText, { wordSrtText = it }, { wordSrtPicker.launch(arrayOf("application/x-subrip", "text/plain", "*/*")) }, subtitleFontSize, { subtitleFontSize = it }, subtitleColor, { subtitleColor = it }, subtitlePosition, { subtitlePosition = it }, subtitleOutline, { subtitleOutline = it }, subtitleBackground, { subtitleBackground = it })
                else -> ExportPage(Modifier.padding(padding), scenes.size, timelineSrtCueCount, audio != null, resolution, { resolution = it }, aspectRatio, { aspectRatio = it }, frameRate, { frameRate = it }, transition, { transition = it }, exporting, exportProgress, { if (VideoExportEngine.cancel()) { exporting = false; exportProgress = 0; exportStatus = "Export cancelled" } else exportStatus = "Finalizing video…" }, exportStatus, { VideoExportEngine.export(context, scenes, audio, wordSrtText, ExportSettings(resolution, aspectRatio, subtitleFontSize, subtitleColor, subtitlePosition, subtitleOutline, subtitleBackground, frameRate, transition), projectName = projectName, onStarted = { exporting = true; exportProgress = 0; exportStatus = "Rendering video…"; lastExportUri = null }, onCompleted = { uri -> exporting = false; exportProgress = 100; lastExportUri = uri; exportStatus = "Video saved to Movies / Animation Studio"; status = "Export complete"; scope.launch { withContext(Dispatchers.IO) { ExportHistoryStore.add(context, uri) }; exportHistory = withContext(Dispatchers.IO) { ExportHistoryStore.list(context) } } }, onError = { message -> exporting = false; exportProgress = 0; exportStatus = if (message == "Export cancelled") message else "Export failed: " + message }) }, { uri -> val share = Intent(Intent.ACTION_SEND).apply { type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }; context.startActivity(Intent.createChooser(share, "Share exported video")) }, exportHistory, status)
            }
        }
    }

    if (showProjects) {
        AlertDialog(
            onDismissRequest = { showProjects = false },
            title = { Text("Saved projects") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (savedProjects.isEmpty()) Text("No saved projects yet.")
                    savedProjects.forEach { project ->
                        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                Text(project.name, fontWeight = FontWeight.Bold)
                                Text(project.sceneCount.toString() + " scenes", color = Muted, fontSize = 12.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            runCatching {
                                                val loaded = withContext(Dispatchers.IO) { ProjectStore.load(context, project.id) }
                                                projectId = loaded.id
                                                projectName = loaded.name
                                                scenes = loaded.scenes
                                                sceneUndo = emptyList()
                                                sceneRedo = emptyList()
                                                audio = loaded.audio
                                                srtText = loaded.srtText
                                                srtName = if (loaded.srtText.isNotBlank()) "Timeline SRT saved" else "No timeline SRT selected"
                                                wordSrtText = loaded.wordSrtText
                                                wordSrtName = if (loaded.wordSrtText.isNotBlank()) "Word-level SRT saved" else "No word-level SRT selected"
                                                resolution = loaded.resolution
                                                aspectRatio = loaded.aspectRatio
                                                globalMotion = loaded.globalMotion
                                                subtitleFontSize = loaded.subtitleFontSize
                                                subtitleColor = loaded.subtitleColor
                                                subtitlePosition = loaded.subtitlePosition
                                                subtitleOutline = loaded.subtitleOutline
                                                subtitleBackground = loaded.subtitleBackground
                                                frameRate = loaded.frameRate
                                                transition = loaded.transition
                                                status = "Project opened"
                                                showProjects = false
                                            }.onFailure { status = "Open failed: " + (it.message ?: "Unknown error") }
                                        }
                                    }) { Text("Open") }
                                    TextButton(onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { ProjectStore.duplicate(context, project.id) }
                                            savedProjects = withContext(Dispatchers.IO) { ProjectStore.list(context) }
                                        }
                                    }) { Text("Duplicate") }
                                    TextButton(onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) { ProjectStore.delete(context, project.id) }
                                            savedProjects = withContext(Dispatchers.IO) { ProjectStore.list(context) }
                                        }
                                    }) { Text("Delete", color = Color(0xFFFF7777)) }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showProjects = false }) { Text("Close") } },
            dismissButton = {
                TextButton(onClick = {
                    projectId = "project_" + System.currentTimeMillis()
                    projectName = "Untitled Project"
                    scenes = emptyList()
                    sceneUndo = emptyList()
                    sceneRedo = emptyList()
                    audio = null
                    srtText = ""
                    srtName = "No timeline SRT selected"
                    wordSrtText = ""
                    wordSrtName = "No word-level SRT selected"
                    status = "New project created"
                    showProjects = false
                }) { Text("New project") }
            }
        )
    }
}

@Composable
fun HomePage(modifier: Modifier, name: String, setName: (String)->Unit, count: Int, hasAudio: Boolean, srt: String, status: String, addFolder:()->Unit, addAudio:()->Unit, addSrt:()->Unit, goScenes:()->Unit, openProjects:()->Unit) {
    LazyColumn(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Your next story starts here.", fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text("Build a video from scenes, voice and subtitles.", color = Muted)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("PROJECT NAME", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(name, setName, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Text("PROJECT STATUS  •  $status", color = Accent, fontSize = 12.sp)
                }
            }
        }
        item { OutlinedButton(onClick = openProjects, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Open / Manage Saved Projects") } }
        item { SectionTitle("IMPORT YOUR ASSETS") }
        item { ActionCard("Import image folder", "$count images • Select your storyboard images folder", Icons.Default.Folder, addFolder) }
        item { ActionCard("Voiceover", if(hasAudio) "Audio file selected" else "Choose narration audio", Icons.Default.Mic, addAudio) }
        item { ActionCard("Subtitles (SRT)", srt, Icons.Default.Subtitles, addSrt) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("WORKFLOW", fontWeight = FontWeight.Bold)
                    Text("1. Import your image folder\n2. Add voiceover audio\n3. Import timeline SRT\n4. Add word-level SRT in Subtitles\n5. Export MP4", color = Muted, lineHeight = 23.sp)
                    Button(onClick = goScenes, modifier = Modifier.fillMaxWidth()) { Text("Manage scenes") }
                }
            }
        }
    }
}

@Composable
fun ScenesPage(modifier: Modifier, scenes: List<Scene>, update: (List<Scene>) -> Unit, add: () -> Unit, goExport: () -> Unit, globalMotion: String, setGlobalMotion: (String) -> Unit, canUndo: Boolean, canRedo: Boolean, undo: () -> Unit, redo: () -> Unit, srtText: String, audioUri: Uri?, transition: String, subtitleFontSize: Int, subtitleColor: String, subtitlePosition: String, subtitleOutline: Boolean, subtitleBackground: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    var previewScene = remember { mutableStateOf<Scene?>(null) }
    var showTimelinePreview by remember { mutableStateOf(false) }
    var previewPlaying by remember { mutableStateOf(false) }
    var previewPositionMs by remember { mutableLongStateOf(0L) }
    val previewTotalMs = scenes.sumOf { (it.duration.coerceAtLeast(0.5f) * 1000).toLong() }.coerceAtLeast(1L)
    val previewCues = remember(srtText) { SubtitleEngine.parse(srtText) }
    val activePreviewCue = SubtitleEngine.activeCue(previewCues, previewPositionMs)
    val previewTransitionAlpha = if (transition == "Fade through black") {
        var boundary = 0L
        scenes.dropLast(1).map { scene ->
            boundary += (scene.duration.coerceAtLeast(0.5f) * 1000).toLong()
            (1f - kotlin.math.abs(previewPositionMs - boundary).toFloat() / 250f).coerceIn(0f, 1f)
        }.maxOrNull() ?: 0f
    } else 0f
    val context = LocalContext.current
    val latestPreviewPlaying by rememberUpdatedState(previewPlaying)
    var previewAudioReady by remember(audioUri, showTimelinePreview) { mutableStateOf(false) }
    var previewAudioFailed by remember(audioUri, showTimelinePreview) { mutableStateOf(false) }
    var previewPlayer by remember(audioUri, showTimelinePreview) { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(audioUri, showTimelinePreview) {
        var ownedPlayer: MediaPlayer? = null
        if (showTimelinePreview && audioUri != null) {
            runCatching {
                MediaPlayer().also { player ->
                    ownedPlayer = player
                    player.setOnPreparedListener { prepared ->
                        previewAudioReady = true
                        previewAudioFailed = false
                        if (latestPreviewPlaying) runCatching {
                            if (previewPositionMs < prepared.duration) {
                                prepared.seekTo(previewPositionMs.toInt().coerceAtLeast(0))
                                prepared.start()
                            }
                        }
                    }
                    player.setOnCompletionListener { completed ->
                        previewPositionMs = completed.currentPosition.toLong().coerceAtMost(previewTotalMs)
                    }
                    player.setDataSource(context, audioUri)
                    previewPlayer = player
                    player.prepareAsync()
                }
            }.onFailure {
                previewAudioReady = false
                previewAudioFailed = true
                previewPlayer = null
            }
        }
        onDispose {
            runCatching { ownedPlayer?.let { if (it.isPlaying) it.pause(); it.release() } }
            previewPlayer = null
            previewAudioReady = false
            previewAudioFailed = false
        }
    }

    LaunchedEffect(previewPlaying, previewAudioReady, showTimelinePreview) {
        val player = previewPlayer
        if (previewPlaying && player != null && previewAudioReady) {
            runCatching {
                if (previewPositionMs < player.duration) {
                    player.seekTo(previewPositionMs.toInt().coerceAtLeast(0))
                    if (!player.isPlaying) player.start()
                }
            }
        } else if (!previewPlaying) {
            runCatching { if (player?.isPlaying == true) player.pause() }
        }
        while (previewPlaying && previewPositionMs < previewTotalMs) {
            delay(50)
            val activePlayer = previewPlayer
            if (audioUri != null && activePlayer != null && previewAudioReady) {
                runCatching {
                    if (activePlayer.isPlaying) {
                        previewPositionMs = activePlayer.currentPosition.toLong().coerceAtMost(previewTotalMs)
                    } else if (previewPositionMs >= activePlayer.duration.toLong() || activePlayer.currentPosition >= activePlayer.duration) {
                        previewPositionMs = (previewPositionMs + 50L).coerceAtMost(previewTotalMs)
                    }
                }
            } else {
                previewPositionMs = (previewPositionMs + 50L).coerceAtMost(previewTotalMs)
            }
        }
        if (previewPositionMs >= previewTotalMs) previewPlaying = false
    }

    LaunchedEffect(previewPositionMs, previewAudioReady, previewPlaying) {
        if (!previewPlaying && previewAudioReady) {
            runCatching {
                previewPlayer?.seekTo(previewPositionMs.coerceAtMost(previewPlayer?.duration?.toLong() ?: previewPositionMs).toInt())
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Scene library", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = undo, enabled = canUndo, modifier = Modifier.weight(1f)) { Icon(Icons.AutoMirrored.Filled.Undo, null); Text("Undo") }
                    OutlinedButton(onClick = redo, enabled = canRedo, modifier = Modifier.weight(1f)) { Icon(Icons.AutoMirrored.Filled.Redo, null); Text("Redo") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = add, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add images")
                    }
                    OutlinedButton(onClick = { previewPositionMs = 0L; previewPlaying = false; showTimelinePreview = true }, enabled = scenes.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PlayArrow, null); Text("Preview")
                    }
                }
                Button(onClick = goExport, enabled = scenes.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Movie, null); Spacer(Modifier.width(8.dp)); Text("Go to Export MP4")
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TIMELINE • " + scenes.size + " scenes • " + "%.1f".format(scenes.sumOf { it.duration.toDouble() }) + " seconds", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("GLOBAL CAMERA MOTION", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(globalMotion)
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    listOf("Static", "Auto Zoom", "Zoom In-Out", "Zoom Out-In", "Alternate", "Random", "Pan Left", "Pan Right", "Pan Up", "Pan Down", "Zoom+Pan", "Ken Burns", "Cinematic").forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                setGlobalMotion(option)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }

        if (scenes.isEmpty()) {
            item { EmptyHint("No scenes yet. Add your numbered images to begin.") }
        }

        items(scenes, key = { it.uri.toString() }) { scene ->
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Row(
                    Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(scene.uri, scene.name, modifier = Modifier.size(76.dp).background(Bg, RoundedCornerShape(10.dp)).clickable { previewScene.value = scene })
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(scene.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("${scene.duration} sec • ${scene.motion}", color = Muted, fontSize = 12.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { update(scenes.map { if (it == scene) it.copy(duration = (it.duration - 0.5f).coerceAtLeast(0.5f)) else it }) }) { Icon(Icons.Default.Remove, "Shorter") }
                            IconButton(onClick = { update(scenes.map { if (it == scene) it.copy(duration = (it.duration + 0.5f).coerceAtMost(60f)) else it }) }) { Icon(Icons.Default.Add, "Longer") }
                            var motionExpanded by remember(scene.uri) { mutableStateOf(false) }
                            Box {
                                TextButton(onClick = { motionExpanded = true }) { Text("Motion") }
                                DropdownMenu(expanded = motionExpanded, onDismissRequest = { motionExpanded = false }) {
                                    listOf("Static", "Zoom In", "Zoom Out", "Zoom In-Out", "Zoom Out-In", "Pan Left", "Pan Right", "Pan Up", "Pan Down", "Zoom+Pan", "Ken Burns", "Cinematic", "Alternate", "Random").forEach { motion ->
                                        DropdownMenuItem(text = { Text(motion) }, onClick = {
                                            update(scenes.map { if (it == scene) it.copy(motion = motion) else it })
                                            motionExpanded = false
                                        })
                                    }
                                }
                            }
                        }
                    }
                    Column {
                        val sceneIndex = scenes.indexOf(scene)
                        IconButton(onClick = {
                            if (sceneIndex > 0) update(scenes.toMutableList().also { val moving = it.removeAt(sceneIndex); it.add(sceneIndex - 1, moving) })
                        }, enabled = sceneIndex > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                        IconButton(onClick = {
                            if (sceneIndex < scenes.lastIndex) update(scenes.toMutableList().also { val moving = it.removeAt(sceneIndex); it.add(sceneIndex + 1, moving) })
                        }, enabled = sceneIndex < scenes.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                        IconButton(onClick = { update(scenes - scene) }) { Icon(Icons.Default.Delete, "Remove", tint = Color(0xFFFF7777)) }
                    }
                }
            }
        }

        item {
            Text("Tip: select images in filename order (1, 2, 3..10).", color = Muted, fontSize = 12.sp)
        }
    }

    if (showTimelinePreview && scenes.isNotEmpty()) {
        var remainingMs = previewPositionMs
        var currentIndex = 0
        while (currentIndex < scenes.lastIndex && remainingMs >= (scenes[currentIndex].duration * 1000).toLong()) {
            remainingMs -= (scenes[currentIndex].duration * 1000).toLong()
            currentIndex++
        }
        val currentScene = scenes[currentIndex]
        val sceneDurationMs = (currentScene.duration * 1000).toLong().coerceAtLeast(1L)
        val progress = (remainingMs.toFloat() / sceneDurationMs).coerceIn(0f, 1f)
        val previewMotion = when (currentScene.motion) {
            "Random" -> listOf("Zoom In", "Zoom Out", "Pan Left", "Pan Right", "Ken Burns")[currentIndex % 5]
            "Alternate" -> if (currentIndex % 2 == 0) "Zoom In" else "Zoom Out"
            else -> currentScene.motion
        }
        val scale = when (previewMotion) {
            "Static" -> 1f
            "Zoom In", "Auto Zoom" -> 1f + 0.12f * progress
            "Zoom Out" -> 1.12f - 0.12f * progress
            "Zoom In-Out" -> 1f + 0.10f * kotlin.math.sin(progress * Math.PI).toFloat()
            "Zoom Out-In" -> 1.10f - 0.10f * kotlin.math.sin(progress * Math.PI).toFloat()
            "Pan Left", "Pan Right", "Pan Up", "Pan Down" -> 1.16f
            "Cinematic", "Ken Burns", "Zoom+Pan" -> 1f + 0.09f * progress
            else -> 1.04f
        }
        androidx.compose.ui.window.Dialog(onDismissRequest = { previewPlaying = false; showTimelinePreview = false }) {
            Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Timeline preview", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { previewPlaying = false; showTimelinePreview = false }) { Icon(Icons.Default.Close, "Close") }
                    }
                    Box(Modifier.fillMaxWidth().height(230.dp).background(Color.Black, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        AsyncImage(
                            model = currentScene.uri,
                            contentDescription = currentScene.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = when (previewMotion) {
                                    "Pan Left" -> 14f - 28f * progress
                                    "Pan Right" -> -14f + 28f * progress
                                    "Ken Burns", "Cinematic", "Zoom+Pan" -> -10f + 20f * progress
                                    else -> 0f
                                }
                                translationY = when (previewMotion) {
                                    "Pan Up" -> 14f - 28f * progress
                                    "Pan Down" -> -14f + 28f * progress
                                    else -> 0f
                                }
                            }
                        )
                        if (previewTransitionAlpha > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = previewTransitionAlpha)))
                        Text(currentScene.name, color = Color.White, modifier = Modifier.align(Alignment.TopStart).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(8.dp))
                        activePreviewCue?.let { cue ->
                            val previewTextColor = when (subtitleColor) { "Yellow" -> Color.Yellow; "Cyan" -> Color.Cyan; else -> Color.White }
                            val subtitleAlignment = when (subtitlePosition) { "Top" -> Alignment.TopCenter; "Center" -> Alignment.Center; else -> Alignment.BottomCenter }
                            Text(cue.text, color = previewTextColor, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                fontSize = (subtitleFontSize * 230f / 1080f).coerceAtLeast(8f).sp,
                                style = if (subtitleOutline) androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, androidx.compose.ui.geometry.Offset(1f, 1f), 2f)) else androidx.compose.ui.text.TextStyle.Default,
                                modifier = Modifier.align(subtitleAlignment).padding(10.dp)
                                    .then(if (subtitleBackground) Modifier.background(Color(0xCC000000), RoundedCornerShape(6.dp)) else Modifier)
                                    .padding(8.dp))
                        }
                    }
                    Text("Scene " + (currentIndex + 1) + " of " + scenes.size + " • " + previewMotion, color = Muted, fontSize = 12.sp)
                    Slider(value = previewPositionMs.toFloat(), onValueChange = { previewPositionMs = it.toLong(); previewPlaying = false; runCatching { previewPlayer?.seekTo(previewPositionMs.coerceAtMost(previewPlayer?.duration?.toLong() ?: previewPositionMs).toInt()) } }, valueRange = 0f..previewTotalMs.toFloat())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("%.1f".format(previewPositionMs / 1000f) + "s / " + "%.1f".format(previewTotalMs / 1000f) + "s", color = Muted, modifier = Modifier.weight(1f))
                        IconButton(onClick = { previewPositionMs = 0L; previewPlaying = false; runCatching { previewPlayer?.seekTo(0) } }) { Icon(Icons.Default.Replay, "Restart") }
                        Button(onClick = { if (previewPositionMs >= previewTotalMs) { previewPositionMs = 0L; runCatching { previewPlayer?.seekTo(0) } }; previewPlaying = !previewPlaying }) {
                            Icon(if (previewPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null)
                            Text(if (previewPlaying) "Pause" else "Play")
                        }
                    }
                    Text(
                        when {
                            audioUri == null -> "No voiceover selected. Visual timeline and SRT subtitles are previewed without audio."
                            previewAudioFailed -> "Voiceover preview could not be opened. Visual timeline and subtitles remain available."
                            !previewAudioReady -> "Preparing voiceover audio…"
                            else -> "Voiceover audio plays with the visual timeline. SRT subtitles follow the preview position."
                        },
                        color = Muted,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }

    if (previewScene.value != null) {
        val scene = previewScene.value!!
        androidx.compose.ui.window.Dialog(onDismissRequest = { previewScene.value = null }) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color.Black,
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(Modifier.padding(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = { previewScene.value = null }) {
                            Icon(Icons.Default.Close, "Close preview", tint = Color.White)
                        }
                    }
                    AsyncImage(
                        model = scene.uri,
                        contentDescription = scene.name,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 650.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                    Text(scene.name, color = Color.White, modifier = Modifier.padding(8.dp))
                }
            }
        }
    }

}

@Composable
fun SubtitlePage(
    modifier: Modifier, fileName: String, text: String, setText: (String) -> Unit, import: () -> Unit,
    fontSize: Int, setFontSize: (Int) -> Unit, color: String, setColor: (String) -> Unit,
    position: String, setPosition: (String) -> Unit, outline: Boolean, setOutline: (Boolean) -> Unit,
    background: Boolean, setBackground: (Boolean) -> Unit
) {
    var cues by remember(text) { mutableStateOf(emptyList<SubtitleCue>()) }
    var showCueList by remember { mutableStateOf(true) }
    var showEditor by remember { mutableStateOf(false) }
    var parsing by remember(text) { mutableStateOf(text.isNotBlank()) }
    LaunchedEffect(text) {
        parsing = text.isNotBlank()
        cues = withContext(Dispatchers.IO) { SubtitleEngine.parse(text) }
        parsing = false
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Subtitles", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(fileName, color = Muted)
        Button(onClick = import, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("Import SRT file") }
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SUBTITLE STYLE", fontWeight = FontWeight.Bold)
                Text("Font size: " + fontSize + " px at 1080p", color = Muted)
                Slider(value = fontSize.toFloat(), onValueChange = { setFontSize(it.toInt()) }, valueRange = 28f..112f, steps = 20)
                Text("Text color", color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("White", "Yellow", "Cyan").forEach { option -> FilterChip(selected = color == option, onClick = { setColor(option) }, label = { Text(option) }) }
                }
                Text("Position", color = Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Top", "Center", "Bottom").forEach { option -> FilterChip(selected = position == option, onClick = { setPosition(option) }, label = { Text(option) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = outline, onCheckedChange = setOutline)
                    Text("Text outline")
                    Spacer(Modifier.width(12.dp))
                    Checkbox(checked = background, onCheckedChange = setBackground)
                    Text("Background box")
                }
                Surface(color = if (background) Color(0xCC000000) else Color.Transparent, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(cues.firstOrNull()?.text ?: "Your subtitle preview appears here", color = when(color) { "Yellow" -> Color.Yellow; "Cyan" -> Color.Cyan; else -> Color.White }, fontSize = (fontSize * 230f / 1080f).coerceAtLeast(8f).sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp))
                }
                Text("Preview settings are saved with the project. Outline and final burn-in are applied during video rendering.", color = Muted, fontSize = 11.sp)
                Text("Word highlighting is spread evenly across each SRT cue. It is approximate unless your subtitles contain word-level timestamps.", color = Muted, fontSize = 11.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (parsing) "READING SRT…" else "SRT CUES (" + cues.size + ")", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { showCueList = !showCueList }) { Text(if (showCueList) "Hide" else "Show") }
        }
        if (showCueList && !parsing) {
            if (cues.isEmpty()) Text("No valid timestamped cues found yet.", color = Muted)
            cues.take(100).forEach { cue ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text(SubtitleEngine.formatTime(cue.startMs) + " → " + SubtitleEngine.formatTime(cue.endMs), color = Accent, fontSize = 11.sp)
                        Text(cue.text, fontSize = 14.sp)
                    }
                }
            }
            if (cues.size > 100) Text("Showing first 100 of " + cues.size + " cues to keep the screen responsive.", color = Muted, fontSize = 11.sp)
        }
        TextButton(onClick = { showEditor = !showEditor }) {
            Text(if (showEditor) "Hide SRT text editor" else "Show SRT text editor")
        }
        if (showEditor) {
            OutlinedTextField(value = text, onValueChange = setText, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp), placeholder = { Text("Imported subtitle text will appear here…") })
        }
    }
}

@Composable
fun ExportPage(modifier: Modifier, count: Int, srtCount: Int, audio: Boolean, resolution: String, setResolution:(String)->Unit, aspectRatio: String, setAspectRatio:(String)->Unit, frameRate: Int, setFrameRate:(Int)->Unit, transition: String, setTransition:(String)->Unit, exporting: Boolean, progress: Int, onCancel: () -> Unit, exportStatus: String, onExport: () -> Unit, onShare: (Uri) -> Unit, history: List<ExportHistoryEntry>, status:String) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Export video", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PROJECT CHECK", fontWeight = FontWeight.Bold)
                Text("Scene images: $count\nSRT scene titles: $srtCount\nVoiceover: ${if(audio) "Added" else "Missing"}\nAspect ratio: $aspectRatio\nResolution: $resolution", color = Muted, lineHeight = 24.sp)
                if (srtCount == count && count > 0) {
                    Text("SRT and scene counts match. Export is ready.", color = Color(0xFF75D69A), fontSize = 12.sp)
                } else {
                    Text(
                        if (srtCount == 0) "Export locked: import a valid scene-title SRT first."
                        else "Export locked: $srtCount SRT titles but $count scene images. Make both counts equal.",
                        color = Color(0xFFFFA45C), fontSize = 12.sp
                    )
                }
            }
        }
        Text("ASPECT RATIO", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("16:9", "9:16", "1:1").forEach { value -> FilterChip(selected = aspectRatio == value, onClick = { setAspectRatio(value) }, label = { Text(value) }) } }
        Text("SCENE TRANSITION", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("None", "Fade through black").forEach { value ->
                FilterChip(selected = transition == value, onClick = { setTransition(value) }, label = { Text(value) })
            }
        }
        Text("FRAME RATE", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(24, 30, 60).forEach { value -> FilterChip(selected = frameRate == value, onClick = { setFrameRate(value) }, label = { Text(value.toString() + " fps") }) } }
        Text("VIDEO QUALITY / RESOLUTION", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("720p", "1080p", "4K").forEach { value ->
                FilterChip(selected = resolution == value, onClick = { setResolution(value) }, label = { Text(value) })
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(16.dp)) {
                Text("MP4 rendering", fontWeight = FontWeight.Bold)
                Text("H.264 video • AAC voiceover • SRT subtitle burn-in • " + frameRate + " fps", color = Muted, fontSize = 13.sp)
            }
        }
        Button(onClick = onExport, enabled = count > 0 && srtCount == count && !exporting, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Movie, null); Spacer(Modifier.width(8.dp)); Text(if (exporting) "Rendering…" else "Export MP4")
        }
        if (exporting) {
            if (progress > 0) LinearProgressIndicator(progress = { progress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (progress > 0) "Rendering: $progress%" else "Preparing export…", color = Muted, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
        Text(exportStatus, color = Accent)
        if (exportStatus.startsWith("Video saved")) OutlinedButton(onClick = { /* current export appears at the top of history */ history.firstOrNull()?.let { onShare(it.uri) } }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("Share video") }
        if (history.isNotEmpty()) {
            Text("EXPORT HISTORY", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            history.take(10).forEach { entry ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.fileName, maxLines = 1, fontWeight = FontWeight.SemiBold)
                            Text(java.text.SimpleDateFormat("dd MMM yyyy • HH:mm", java.util.Locale.getDefault()).format(java.util.Date(entry.createdAt)), color = Muted, fontSize = 11.sp)
                        }
                        IconButton(onClick = { onShare(entry.uri) }) { Icon(Icons.Default.Share, "Share export") }
                    }
                }
            }
        }
        Text(status, color = Muted, fontSize = 11.sp)
    }
}

@Composable
fun ActionCard(title:String, detail:String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick:()->Unit) {
    Card(Modifier.fillMaxWidth().clickable { onClick() }, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Accent, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(detail, color = Muted, fontSize = 12.sp) }
            Icon(Icons.Default.ChevronRight, null, tint = Muted)
        }
    }
}
@Composable fun SectionTitle(text:String) { Text(text, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
@Composable fun EmptyHint(text:String) { Card(colors = CardDefaults.cardColors(containerColor = Panel)) { Text(text, Modifier.padding(18.dp), color = Muted) } }


fun collectImageDocuments(folder: DocumentFile): List<DocumentFile> {
    return folder.listFiles().flatMap { child ->
        when {
            child.isDirectory -> collectImageDocuments(child)
            child.isFile && child.type?.startsWith("image/") == true -> listOf(child)
            else -> emptyList()
        }
    }
}

fun applyTimelineSrt(scenes: List<Scene>, srtText: String): List<Scene> {
    val cues = SubtitleEngine.parse(srtText)
    if (scenes.isEmpty() || cues.isEmpty()) return scenes
    return scenes.mapIndexed { index, scene ->
        val cue = cues.getOrNull(index) ?: return@mapIndexed scene
        val nextStart = cues.getOrNull(index + 1)?.startMs
        val end = nextStart?.takeIf { it > cue.startMs } ?: cue.endMs
        val durationMs = (end - cue.startMs).coerceAtLeast(100L)
        scene.copy(duration = durationMs / 1000f)
    }
}

fun naturalKey(value: String): String = value.lowercase().replace(Regex("\\d+")) { match ->
    match.value.padStart(12, '0')
}



private fun detectBlankPanels(bitmap: Bitmap, grid: Int): Set<Int> {
    val blanks = mutableSetOf<Int>()
    // Conservative detection: only flag panels that are almost completely solid
    // black/white. This avoids classifying pale drawings or dark scenes as blank.
    val sampleGrid = 16
    for (index in 0 until grid * grid) {
        val row = index / grid
        val col = index % grid
        val left = col * bitmap.width / grid
        val top = row * bitmap.height / grid
        val right = (col + 1) * bitmap.width / grid
        val bottom = (row + 1) * bitmap.height / grid
        if (right <= left || bottom <= top) continue

        var sum = 0.0
        var sumSq = 0.0
        var nearExtreme = 0
        var count = 0
        for (y in 0 until sampleGrid) {
            for (x in 0 until sampleGrid) {
                val px = (left + (x + 0.5) * (right - left) / sampleGrid)
                    .toInt().coerceIn(left, right - 1)
                val py = (top + (y + 0.5) * (bottom - top) / sampleGrid)
                    .toInt().coerceIn(top, bottom - 1)
                val color = bitmap.getPixel(px, py)
                val gray = 0.299 * android.graphics.Color.red(color) +
                    0.587 * android.graphics.Color.green(color) +
                    0.114 * android.graphics.Color.blue(color)
                sum += gray
                sumSq += gray * gray
                if (gray >= 248 || gray <= 7) nearExtreme++
                count++
            }
        }
        val mean = sum / count
        val variance = (sumSq / count - mean * mean).coerceAtLeast(0.0)
        val almostSolid = nearExtreme.toFloat() / count >= 0.985f && variance < 20.0
        if (almostSolid && (mean >= 248 || mean <= 7)) blanks.add(index)
    }
    return blanks
}

private fun panelSignature(bitmap: Bitmap): String {
    val result = StringBuilder()
    for (y in 0 until 8) {
        for (x in 0 until 8) {
            val px = (x * bitmap.width / 8).coerceIn(0, bitmap.width - 1)
            val py = (y * bitmap.height / 8).coerceIn(0, bitmap.height - 1)
            val c = bitmap.getPixel(px, py)
            result.append(android.graphics.Color.red(c) / 8).append(',')
            result.append(android.graphics.Color.green(c) / 8).append(',')
            result.append(android.graphics.Color.blue(c) / 8).append(';')
        }
    }
    return result.toString()
}

private data class BatchStoryboardResult(
    val added: List<Scene>,
    val blankSkipped: Int,
    val duplicateSkipped: Int,
    val failed: Int
)

@Composable
fun StoryboardPage(
    modifier: Modifier,
    scenes: List<Scene>,
    onScenesChange: (List<Scene>) -> Unit,
    timelineSrtText: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var storyboardUri by remember { mutableStateOf<Uri?>(null) }
    var folderImages by remember { mutableStateOf(emptyList<DocumentFile>()) }
    var selectedFolderUris by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var gridSize by remember { mutableIntStateOf(3) }
    var blankPanels by remember { mutableStateOf(setOf<Int>()) }
    var status by remember { mutableStateOf("Choose a storyboard image") }
    var processing by remember { mutableStateOf(false) }

    val selectedPanels = remember { mutableStateListOf<Int>() }

    val storyboardFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folderUri ->
        if (folderUri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(folderUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            scope.launch {
                val imported = withContext(Dispatchers.IO) {
                    DocumentFile.fromTreeUri(context, folderUri)?.let { collectImageDocuments(it) } ?: emptyList()
                }.sortedBy { naturalKey(it.name ?: "Image") }
                folderImages = imported
                selectedFolderUris = imported.map { it.uri }.toSet()
                blankPanels = emptySet()
                selectedPanels.clear()
                if (imported.isNotEmpty()) {
                    // Open the first image immediately so the grid selector and Split button appear.
                    storyboardUri = imported.first().uri
                    status = "${imported.size} images found. Choose a contact sheet below, then split it."
                } else {
                    storyboardUri = null
                    status = "No images found in selected folder"
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        storyboardUri = uri
        blankPanels = emptySet()
        selectedPanels.clear()
        status = if (uri != null) "Checking storyboard panels..." else "No image selected"
    }

    LaunchedEffect(storyboardUri, gridSize) {
        val uri = storyboardUri ?: return@LaunchedEffect
        try {
            val blanks = withContext(Dispatchers.IO) {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw Exception("Cannot open image")
                val bitmap = input.use { BitmapFactory.decodeStream(it) }
                    ?: throw Exception("Invalid image")
                try {
                    if (bitmap.width < gridSize || bitmap.height < gridSize) {
                        throw Exception("Image resolution is too small")
                    }
                    detectBlankPanels(bitmap, gridSize)
                } finally {
                    bitmap.recycle()
                }
            }

            blankPanels = blanks
            selectedPanels.clear()
            selectedPanels.addAll(
                (0 until gridSize * gridSize).filterNot { it in blanks }
            )
            status = if (blanks.isEmpty()) {
                "Panel check complete. Select panels to split."
            } else {
                "${blanks.size} possible blank panels detected. Check selection."
            }
        } catch (e: Exception) {
            status = "Detection error: ${e.message}"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Storyboard Pro", fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text(
            "Split contact sheets into individual scene images.",
            color = Muted
        )

        Button(
            onClick = { storyboardFolderPicker.launch(null) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !processing
        ) {
            Icon(Icons.Default.Folder, null); Spacer(Modifier.width(8.dp)); Text("Import Image Folder")
        }
        OutlinedButton(
            onClick = { picker.launch("image/*") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !processing
        ) { Text("Choose One Contact Sheet") }

        if (folderImages.isNotEmpty()) {
            Text("IMAGES IN SELECTED FOLDER (${folderImages.size})", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { selectedFolderUris = folderImages.map { it.uri }.toSet() },
                    modifier = Modifier.weight(1f),
                    enabled = !processing
                ) { Text("Select All") }
                OutlinedButton(
                    onClick = { selectedFolderUris = emptySet() },
                    modifier = Modifier.weight(1f),
                    enabled = !processing
                ) { Text("Deselect All") }
            }
            Text("${selectedFolderUris.size} of ${folderImages.size} storyboards selected", color = Muted, fontSize = 11.sp)
            folderImages.take(100).forEachIndexed { index, item ->
                Row(
                    modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = item.uri in selectedFolderUris,
                        onCheckedChange = { checked ->
                            selectedFolderUris = if (checked) selectedFolderUris + item.uri else selectedFolderUris - item.uri
                        },
                        enabled = !processing
                    )
                    Text(item.name ?: "Image ${index + 1}", modifier = Modifier.weight(1f), maxLines = 2, color = Color.White, fontSize = 12.sp)
                    TextButton(onClick = {
                        storyboardUri = item.uri
                        blankPanels = emptySet()
                        selectedPanels.clear()
                        status = "Checking ${item.name ?: "image"}..."
                    }, enabled = !processing) { Text(if (storyboardUri == item.uri) "Preview" else "Preview") }
                }
            }
            if (folderImages.size > 100) Text("Showing first 100 files in the list. Select All includes every image.", color = Muted, fontSize = 11.sp)
            Button(
                onClick = {
                    val targets = folderImages.filter { it.uri in selectedFolderUris }
                    scope.launch {
                        processing = true
                        status = "Preparing ${targets.size} storyboards for batch split..."
                        try {
                            val result = withContext(Dispatchers.IO) {
                                val outputFolder = File(context.filesDir, "storyboard/batch_${System.currentTimeMillis()}")
                                if (!outputFolder.mkdirs() && !outputFolder.isDirectory) {
                                    throw Exception("Cannot create output folder")
                                }
                                val added = mutableListOf<Scene>()
                                var blankSkipped = 0
                                var duplicateSkipped = 0
                                var failed = 0
                                targets.forEachIndexed { imageIndex, document ->
                                    try {
                                        val input = context.contentResolver.openInputStream(document.uri)
                                            ?: throw Exception("Cannot open ${document.name}")
                                        val bitmap = input.use { BitmapFactory.decodeStream(it) }
                                            ?: throw Exception("Invalid image: ${document.name}")
                                        try {
                                            if (bitmap.width < gridSize || bitmap.height < gridSize) {
                                                throw Exception("Image too small: ${document.name}")
                                            }
                                            val blanks = detectBlankPanels(bitmap, gridSize)
                                            blankSkipped += blanks.size
                                            val signatures = mutableSetOf<String>()
                                            val baseName = (document.name ?: "Storyboard_${imageIndex + 1}")
                                                .substringBeforeLast('.')
                                                .replace(Regex("[^A-Za-z0-9_-]"), "_")
                                                .take(40)
                                            for (panelIndex in 0 until gridSize * gridSize) {
                                                if (panelIndex in blanks) continue
                                                val row = panelIndex / gridSize
                                                val col = panelIndex % gridSize
                                                val left = col * bitmap.width / gridSize
                                                val top = row * bitmap.height / gridSize
                                                val right = (col + 1) * bitmap.width / gridSize
                                                val bottom = (row + 1) * bitmap.height / gridSize
                                                val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                                                try {
                                                    if (!signatures.add(panelSignature(crop))) {
                                                        duplicateSkipped++
                                                        continue
                                                    }
                                                    val filename = "${baseName}_AA_${(panelIndex + 1).toString().padStart(2, '0')}.jpg"
                                                    val file = File(outputFolder, filename)
                                                    FileOutputStream(file).use { output ->
                                                        if (!crop.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                                                            throw Exception("Could not save $filename")
                                                        }
                                                    }
                                                    added.add(Scene(Uri.fromFile(file), filename))
                                                } finally {
                                                    crop.recycle()
                                                }
                                            }
                                        } finally {
                                            bitmap.recycle()
                                        }
                                    } catch (_: Exception) {
                                        failed++
                                    }
                                }
                                BatchStoryboardResult(added, blankSkipped, duplicateSkipped, failed)
                            }
                            val combined = (scenes + result.added).sortedWith(compareBy<Scene> { naturalKey(it.name) })
                            onScenesChange(applyTimelineSrt(combined, timelineSrtText))
                            status = "Batch split complete: ${result.added.size} scenes from ${targets.size} storyboards • ${result.blankSkipped} blank panels skipped • ${result.duplicateSkipped} duplicates skipped • ${result.failed} images failed"
                        } catch (e: Exception) {
                            status = "Batch split error: ${e.message ?: "Unknown error"}"
                        } finally {
                            processing = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedFolderUris.isNotEmpty() && !processing
            ) { Text(if (processing) "Splitting all selected..." else "Split All Selected Storyboards") }
            OutlinedButton(
                onClick = {
                    val targets = folderImages.filter { it.uri in selectedFolderUris }
                    val additions = targets.map { Scene(it.uri, it.name ?: "Image") }
                    val combined = (scenes + additions).sortedWith(compareBy { naturalKey(it.name) })
                    onScenesChange(applyTimelineSrt(combined, timelineSrtText))
                    status = "${additions.size} selected folder images added directly as scenes (no splitting)."
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedFolderUris.isNotEmpty() && !processing
            ) { Text("Add Selected Images as Scenes (No Split)") }
        }

        Text("Choose Grid Size", fontWeight = FontWeight.Bold)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(2, 3, 4).forEach { size ->
                if (gridSize == size) {
                    Button(
                        onClick = { gridSize = size },
                        modifier = Modifier.weight(1f),
                        enabled = !processing
                    ) {
                        Text("${size}×${size}")
                    }
                } else {
                    OutlinedButton(
                        onClick = { gridSize = size },
                        modifier = Modifier.weight(1f),
                        enabled = !processing
                    ) {
                        Text("${size}×${size}")
                    }
                }
            }
        }

        storyboardUri?.let { uri ->
            AsyncImage(
                model = uri,
                contentDescription = "Storyboard preview",
                modifier = Modifier.fillMaxWidth().height(190.dp),
                contentScale = ContentScale.Fit
            )

            Text("Tap a panel to select or deselect it.", color = Muted)

            repeat(gridSize) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    repeat(gridSize) { col ->
                        val index = row * gridSize + col
                        val selected = index in selectedPanels
                        val blank = index in blankPanels

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .background(
                                    if (selected) Accent else Panel,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable(enabled = !processing) {
                                    if (selected) selectedPanels.remove(index)
                                    else selectedPanels.add(index)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "AA_${(index + 1).toString().padStart(2, '0')}",
                                    color = if (selected) Color.Black else Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                                if (blank) {
                                    Text(
                                        "BLANK?",
                                        color = if (selected) Color.Black else Muted,
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Text(
                "${selectedPanels.size} selected • ${blankPanels.size} possible blank",
                color = Muted
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        selectedPanels.clear()
                        selectedPanels.addAll(
                            (0 until gridSize * gridSize).filterNot { it in blankPanels }
                        )
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !processing
                ) {
                    Text("Select All Nonblank")
                }

                OutlinedButton(
                    onClick = {
                        selectedPanels.clear()
                        selectedPanels.addAll(0 until gridSize * gridSize)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !processing
                ) {
                    Text("Select All")
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        processing = true
                        status = "Splitting panels..."
                        try {
                            val result = withContext(Dispatchers.IO) {
                                val input = context.contentResolver.openInputStream(uri)
                                    ?: throw Exception("Cannot open image")
                                val bitmap = input.use {
                                    BitmapFactory.decodeStream(it)
                                } ?: throw Exception("Invalid image")

                                try {
                                    val folder = File(
                                        context.filesDir,
                                        "storyboard/${System.currentTimeMillis()}"
                                    )
                                    if (!folder.mkdirs() && !folder.isDirectory) {
                                        throw Exception("Cannot create output folder")
                                    }

                                    val signatures = mutableSetOf<String>()
                                    val added = mutableListOf<Scene>()
                                    var duplicates = 0

                                    for (index in selectedPanels.sorted()) { // Manual selection overrides the auto-detection suggestion.
                                        val row = index / gridSize
                                        val col = index % gridSize
                                        val left = col * bitmap.width / gridSize
                                        val top = row * bitmap.height / gridSize
                                        val right = (col + 1) * bitmap.width / gridSize
                                        val bottom = (row + 1) * bitmap.height / gridSize

                                        val crop = Bitmap.createBitmap(
                                            bitmap, left, top,
                                            right - left, bottom - top
                                        )

                                        try {
                                            val signature = panelSignature(crop)
                                            if (!signatures.add(signature)) {
                                                duplicates++
                                                continue
                                            }

                                            val filename =
                                                "AA_${(index + 1).toString().padStart(2, '0')}.jpg"
                                            val file = File(folder, filename)

                                            FileOutputStream(file).use { output ->
                                                if (!crop.compress(
                                                        Bitmap.CompressFormat.JPEG,
                                                        95,
                                                        output
                                                    )
                                                ) {
                                                    throw Exception("Could not save $filename")
                                                }
                                            }
                                            added.add(Scene(Uri.fromFile(file), filename))
                                        } finally {
                                            crop.recycle()
                                        }
                                    }

                                    Pair(added, duplicates)
                                } finally {
                                    bitmap.recycle()
                                }
                            }

                            val addedScenes = result.first
                            val combined = (scenes + addedScenes).sortedWith(
                                compareBy<Scene> { naturalKey(it.name) }
                            )
                            onScenesChange(applyTimelineSrt(combined, timelineSrtText))
                            val cueCount = SubtitleEngine.parse(timelineSrtText).size
                            status = if (cueCount > 0 && cueCount != combined.size) {
                                "${addedScenes.size} panels added • SRT has $cueCount cues for ${combined.size} images; matched in order • ${result.second} duplicates skipped"
                            } else "${addedScenes.size} panels added. ${result.second} duplicates skipped."
                        } catch (e: Exception) {
                            status = "Split error: ${e.message ?: "Unknown error"}"
                        } finally {
                            processing = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedPanels.isNotEmpty() && !processing
            ) {
                Text(if (processing) "Processing..." else "Split & Add to Scenes")
            }
        }

        Text(status, color = Accent)
    }
}
