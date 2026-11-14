package com.inkorigin.animationstudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.content.Intent
import android.content.Context
import android.app.DownloadManager
import android.os.Environment
import android.os.Build
import android.provider.Settings
import androidx.documentfile.provider.DocumentFile
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Brush
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
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

private suspend fun checkLatestStudioRelease(context: android.content.Context): Pair<String, String?> {
    return try {
        val connection = (URL("https://api.github.com/repos/spotthediff08-cmd/inkandorigin/releases/latest").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 12000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Animation-Studio-Android")
        }
        try {
            if (connection.responseCode != 200) {
                return Pair("No published update is available yet.", null)
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = json.optString("tag_name").removePrefix("v")
            val installed = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
            val apkUrl = json.optJSONArray("assets")?.let { assets ->
                (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                    ?.optString("browser_download_url")
            }?.takeIf { it.isNotBlank() }
            if (tag.isBlank() || tag == installed) {
                Pair("You are using the latest version ($installed).", null)
            } else if (apkUrl == null) {
                Pair("A newer release ($tag) exists, but its APK is not attached yet.", null)
            } else {
                Pair("Update available: $tag (installed: $installed).", apkUrl)
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        Pair("Update check failed. Check your internet connection and try again.", null)
    }
}

private val Bg = Color(0xFFF7F8FA)
private val Panel = Color(0xFFFFFFFF)
private val PanelElevated = Color(0xFFF1F3F6)
private val Accent = Color(0xFF4169D8)
private val AccentSoft = Color(0xFFEAF0FF)
private val Muted = Color(0xFF697586)
private val Outline = Color(0xFFE4E7EC)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { StudioApp() }
    }
}

data class Scene(val uri: Uri, val name: String, val duration: Float = 4f, val motion: String = "Cinematic", val id: String = UUID.randomUUID().toString())

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
    var projectPendingDelete by remember { mutableStateOf<ProjectSummary?>(null) }
    var showProfile by remember { mutableStateOf(false) }
    var lastProjectId by remember { mutableStateOf<String?>(null) }
    var sceneToReplaceId by remember { mutableStateOf<String?>(null) }
    var sceneToReplaceProjectId by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableIntStateOf(0) }
    var exportStatus by remember { mutableStateOf("Ready to export") }
    var lastExportUri by remember { mutableStateOf<Uri?>(null) }
    var exportHistory by remember { mutableStateOf(emptyList<ExportHistoryEntry>()) }
    var updateMessage by remember { mutableStateOf("Check whether a newer version is available.") }
    var updateApkUrl by remember { mutableStateOf<String?>(null) }
    var downloadedApkUri by remember { mutableStateOf<Uri?>(null) }
    var downloadingUpdate by remember { mutableStateOf(false) }

    suspend fun saveCurrentProjectIfNeeded() {
        val alreadySaved = savedProjects.any { it.id == projectId }
        val hasWork = scenes.isNotEmpty() || audio != null || srtText.isNotBlank() ||
            wordSrtText.isNotBlank() || projectName != "Untitled Project"
        if (!alreadySaved && !hasWork) return

        val id = projectId
        val name = projectName
        val sceneSnapshot = scenes.toList()
        val audioSnapshot = audio
        val timelineSrtSnapshot = srtText
        val wordSrtSnapshot = wordSrtText
        val resolutionSnapshot = resolution
        val aspectRatioSnapshot = aspectRatio
        val motionSnapshot = globalMotion
        val fontSizeSnapshot = subtitleFontSize
        val colorSnapshot = subtitleColor
        val positionSnapshot = subtitlePosition
        val outlineSnapshot = subtitleOutline
        val backgroundSnapshot = subtitleBackground
        val frameRateSnapshot = frameRate
        val transitionSnapshot = transition

        withContext(Dispatchers.IO) {
            ProjectStore.save(context, id, name, sceneSnapshot, audioSnapshot, timelineSrtSnapshot, wordSrtSnapshot,
                resolutionSnapshot, aspectRatioSnapshot, motionSnapshot, fontSizeSnapshot, colorSnapshot,
                positionSnapshot, outlineSnapshot, backgroundSnapshot, frameRateSnapshot, transitionSnapshot)
        }
    }

    fun createNewProject(saveCurrent: Boolean = true) {
        scope.launch {
            if (saveCurrent) {
                runCatching { saveCurrentProjectIfNeeded() }.onFailure {
                    status = "Could not save current project: " + (it.message ?: "Unknown error")
                    return@launch
                }
            }
            projectId = "project_" + System.currentTimeMillis()
            lastProjectId = projectId
            ProjectStore.markOpened(context, projectId)
            projectName = "Untitled Project"
            scenes = emptyList()
            sceneUndo = emptyList()
            sceneRedo = emptyList()
            audio = null
            srtText = ""
            srtName = "No timeline SRT selected"
            wordSrtText = ""
            wordSrtName = "No word-level SRT selected"
            resolution = "1080p"
            globalMotion = "Cinematic"
            aspectRatio = "16:9"
            frameRate = 30
            transition = "None"
            subtitleFontSize = 64
            subtitleColor = "White"
            subtitlePosition = "Bottom"
            subtitleOutline = true
            subtitleBackground = false
            status = "New project created"
            tab = "NewProject"
        }
    }

    LaunchedEffect(Unit) {
        savedProjects = withContext(Dispatchers.IO) { ProjectStore.list(context) }
        lastProjectId = withContext(Dispatchers.IO) { ProjectStore.lastOpenedId(context) }
        exportHistory = withContext(Dispatchers.IO) { ExportHistoryStore.list(context) }
    }

    fun openProject(id: String) {
        scope.launch {
            runCatching {
                if (id != projectId) saveCurrentProjectIfNeeded()
                val loaded = withContext(Dispatchers.IO) { ProjectStore.load(context, id) }
                withContext(Dispatchers.IO) { ProjectStore.markOpened(context, loaded.id) }
                projectId = loaded.id
                lastProjectId = loaded.id
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
                aspectRatio = if (loaded.aspectRatio == "9:16") "9:16" else "16:9"
                globalMotion = loaded.globalMotion
                subtitleFontSize = loaded.subtitleFontSize
                subtitleColor = loaded.subtitleColor
                subtitlePosition = loaded.subtitlePosition
                subtitleOutline = loaded.subtitleOutline
                subtitleBackground = loaded.subtitleBackground
                frameRate = loaded.frameRate
                transition = loaded.transition
                status = "Project opened • ready to continue editing"
                showProjects = false
                tab = "Home"
            }.onFailure { status = "Open failed: " + (it.message ?: "Unknown error") }
        }
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
            withContext(Dispatchers.IO) {
                ProjectStore.save(context, projectId, projectName, scenes, audio, srtText, wordSrtText, resolution, aspectRatio, globalMotion, subtitleFontSize, subtitleColor, subtitlePosition, subtitleOutline, subtitleBackground, frameRate, transition)
                ProjectStore.markOpened(context, projectId)
            }
            lastProjectId = projectId
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

    val replaceImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val targetId = sceneToReplaceId
        val targetProjectId = sceneToReplaceProjectId
        if (uri != null && targetId != null && targetProjectId == projectId) {
            if (scenes.any { it.id == targetId }) {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                val replacementName = uri.lastPathSegment?.substringAfterLast('/') ?: "Scene"
                updateScenes(scenes.map { scene ->
                    if (scene.id == targetId) scene.copy(uri = uri, name = replacementName) else scene
                })
                status = "Scene image replaced"
            } else {
                status = "Replacement cancelled: the selected scene no longer exists"
            }
        }
        sceneToReplaceId = null
        sceneToReplaceProjectId = null
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

    MaterialTheme(colorScheme = lightColorScheme(
        primary = Accent, onPrimary = Color.White,
        secondary = Color(0xFF238A83), onSecondary = Color.White,
        background = Bg, surface = Panel, surfaceVariant = PanelElevated,
        onBackground = Color(0xFF171B24), onSurface = Color(0xFF171B24),
        onSurfaceVariant = Muted, outline = Outline
    ), shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(28.dp)
    )) {
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = Color.White,
                    drawerShape = RoundedCornerShape(topEnd = 22.dp, bottomEnd = 22.dp)
                ) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(48.dp).background(AccentSoft, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Person, null, tint = Accent, modifier = Modifier.size(26.dp))
                            }
                            Column {
                                Text("Animation Studio", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text("Your creative workspace", color = Muted, fontSize = 11.sp)
                            }
                        }
                        HorizontalDivider(color = Outline)
                        NavigationDrawerItem(
                            label = { Text("Check for updates", fontWeight = FontWeight.SemiBold) },
                            selected = false,
                            icon = { Icon(Icons.Default.SystemUpdate, null, tint = Accent) },
                            onClick = {
                                scope.launch {
                                    drawerState.close()
                                    updateMessage = "Checking for updates…"
                                    updateApkUrl = null
                                    downloadedApkUri = null
                                    val result = withContext(Dispatchers.IO) { checkLatestStudioRelease(context) }
                                    updateMessage = result.first
                                    updateApkUrl = result.second
                                }
                            }
                        )
                        Text(updateMessage, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp))
                    }
                }
            }
        ) {
        Scaffold(
            containerColor = Bg,
            topBar = {
                if (tab != "NewProject") TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Open menu", tint = Color(0xFF202124))
                        }
                    },
                    title = { Text("Animation Studio", fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.45).sp) },
                    actions = {
                        IconButton(onClick = { showProfile = true }, modifier = Modifier.padding(end = 6.dp)) {
                            Box(Modifier.size(34.dp).background(AccentSoft, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Person, contentDescription = "Profile", tint = Accent, modifier = Modifier.size(20.dp))
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg)
                )
            },
            bottomBar = {}
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when(tab) {
                "Home" -> HomePage(
                    Modifier.fillMaxSize(),
                    savedProjects,
                    { id -> openProject(id) },
                    { createNewProject() },
                    status
                )
                "NewProject" -> NewProjectPage(
                    modifier = Modifier.fillMaxSize(),
                    projectName = projectName,
                    onNameChange = { projectName = it },
                    imageCount = scenes.size,
                    voiceName = if (audio != null) "Voiceover selected" else "Add Voiceover narration",
                    timelineSrtName = srtName,
                    captionSrtName = wordSrtName,
                    onBack = { tab = "Home" },
                    onSingleImage = { imagePicker.launch(arrayOf("image/*")) },
                    onStoryboard = { tab = "Storyboard" },
                    onVoice = { audioPicker.launch(arrayOf("audio/*")) },
                    onTimelineSrt = { srtPicker.launch(arrayOf("application/x-subrip", "text/plain", "*/*")) },
                    onCaptionSrt = { wordSrtPicker.launch(arrayOf("application/x-subrip", "text/plain", "*/*")) },
                    onNext = { tab = "Scenes" }
                )
                "Scenes" -> ScenesPage(Modifier.fillMaxSize(), scenes, ::updateScenes, { imagePicker.launch(arrayOf("image/*")) }, { _, scene -> sceneToReplaceId = scene.id; sceneToReplaceProjectId = projectId; replaceImagePicker.launch(arrayOf("image/*")) }, { tab = "Export" }, globalMotion, { selectedMotion -> globalMotion = selectedMotion; updateScenes(scenes.map { scene -> scene.copy(motion = selectedMotion) }) }, sceneUndo.isNotEmpty(), sceneRedo.isNotEmpty(), ::undoScenes, ::redoScenes, wordSrtText, audio, transition, subtitleFontSize, subtitleColor, subtitlePosition, subtitleOutline, subtitleBackground, aspectRatio)
                "Storyboard" -> Unit
            "Subtitles" -> SubtitlePage(Modifier.fillMaxSize(), wordSrtName, wordSrtText, { wordSrtText = it }, { wordSrtPicker.launch(arrayOf("application/x-subrip", "text/plain", "*/*")) }, subtitleFontSize, { subtitleFontSize = it }, subtitleColor, { subtitleColor = it }, subtitlePosition, { subtitlePosition = it }, subtitleOutline, { subtitleOutline = it }, subtitleBackground, { subtitleBackground = it })
                else -> ExportPage(Modifier.fillMaxSize(), scenes.size, timelineSrtCueCount, audio != null, resolution, { resolution = it }, aspectRatio, { aspectRatio = it }, frameRate, { frameRate = it }, transition, { transition = it }, exporting, exportProgress, { if (VideoExportEngine.cancel()) { exporting = false; exportProgress = 0; exportStatus = "Export cancelled" } else exportStatus = "Finalizing video…" }, exportStatus, { VideoExportEngine.export(context, scenes, audio, wordSrtText, ExportSettings(resolution, aspectRatio, subtitleFontSize, subtitleColor, subtitlePosition, subtitleOutline, subtitleBackground, frameRate, transition), projectName = projectName, onStarted = { exporting = true; exportProgress = 0; exportStatus = "Rendering video…"; lastExportUri = null }, onCompleted = { uri -> exporting = false; exportProgress = 100; lastExportUri = uri; exportStatus = "Video saved to Movies / Animation Studio"; status = "Export complete"; scope.launch { withContext(Dispatchers.IO) { ExportHistoryStore.add(context, uri) }; exportHistory = withContext(Dispatchers.IO) { ExportHistoryStore.list(context) } } }, onError = { message -> exporting = false; exportProgress = 0; exportStatus = if (message == "Export cancelled") message else "Export failed: " + message }) }, { uri -> val share = Intent(Intent.ACTION_SEND).apply { type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }; context.startActivity(Intent.createChooser(share, "Share exported video")) }, exportHistory, status)
                }
                // Keep this page composed while other tabs are open so the uploaded
                // storyboard, selected folder, and panel choices remain intact.
                StoryboardPage(
                    modifier = if (tab == "Storyboard") Modifier.fillMaxSize() else Modifier.size(0.dp),
                    scenes = scenes,
                    onScenesChange = ::updateScenes,
                    timelineSrtText = srtText,
                    onDone = { tab = "NewProject" }
                )
            }
        }
        }
    }

    if (showProjects) {
        AlertDialog(
            onDismissRequest = { showProjects = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = Color.White,
            titleContentColor = Color(0xFF171B24),
            textContentColor = Muted,
            title = { Text("Your project library", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (savedProjects.isEmpty()) Text("No saved projects yet.")
                    savedProjects.forEach { project ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F8FA)), shape = RoundedCornerShape(17.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Outline.copy(alpha = 0.8f))) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                                Box(Modifier.size(48.dp).background(Color(0xFFEAF0FF), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Movie, null, tint = Accent, modifier = Modifier.size(23.dp))
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(project.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                                    Text(project.sceneCount.toString() + " scenes  •  " + if (project.id == projectId) "Currently open" else "Saved project", color = Muted, fontSize = 10.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                        TextButton(onClick = { openProject(project.id) }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)) { Text("Open", fontSize = 11.sp) }
                                        TextButton(onClick = {
                                            scope.launch {
                                                val result = runCatching { withContext(Dispatchers.IO) { ProjectStore.duplicate(context, project.id) } }
                                                result.onSuccess {
                                                    savedProjects = withContext(Dispatchers.IO) { ProjectStore.list(context) }
                                                    status = "Project duplicated with independent assets"
                                                }.onFailure { status = "Duplicate failed: " + (it.message ?: "Unknown error") }
                                            }
                                        }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)) { Text("Duplicate", fontSize = 11.sp) }
                                        TextButton(onClick = { projectPendingDelete = project }, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)) { Text("Delete", color = Color(0xFFFF7777), fontSize = 11.sp) }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showProjects = false }) { Text("Close") } },
            dismissButton = {
                TextButton(onClick = {
                    createNewProject()
                    showProjects = false
                }) { Text("New project") }
            }
        )
    }

    projectPendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { projectPendingDelete = null },
            shape = RoundedCornerShape(24.dp),
            containerColor = Color.White,
            titleContentColor = Color(0xFF171B24),
            textContentColor = Muted,
            title = { Text("Delete project?", fontWeight = FontWeight.Bold) },
            text = {
                Text(if (pending.id == projectId) {
                    "${pending.name} is currently open. Deleting it will start a new blank project. This cannot be undone."
                } else {
                    "Delete ${pending.name}? This cannot be undone."
                })
            },
            confirmButton = {
                TextButton(onClick = {
                    projectPendingDelete = null
                    scope.launch {
                        withContext(Dispatchers.IO) { ProjectStore.delete(context, pending.id) }
                        if (pending.id == projectId) createNewProject(saveCurrent = false)
                        lastProjectId = withContext(Dispatchers.IO) { ProjectStore.lastOpenedId(context) }
                        savedProjects = withContext(Dispatchers.IO) { ProjectStore.list(context) }
                    }
                }) { Text("Delete", color = Color(0xFFFF7777)) }
            },
            dismissButton = {
                TextButton(onClick = { projectPendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    if (showProfile) {
        AlertDialog(
            onDismissRequest = { showProfile = false },
            title = { Text("Profile", fontWeight = FontWeight.Bold) },
            text = { Text("Profile settings will be added later.") },
            confirmButton = { TextButton(onClick = { showProfile = false }) { Text("Close") } },
            containerColor = Color.White
        )
    }
}

@Composable
private fun NewProjectPage(
    modifier: Modifier,
    projectName: String,
    onNameChange: (String) -> Unit,
    imageCount: Int,
    voiceName: String,
    timelineSrtName: String,
    captionSrtName: String,
    onBack: () -> Unit,
    onSingleImage: () -> Unit,
    onStoryboard: () -> Unit,
    onVoice: () -> Unit,
    onTimelineSrt: () -> Unit,
    onCaptionSrt: () -> Unit,
    onNext: () -> Unit
) {
    var showImageOptions by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color(0xFF202124))
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("New project", fontSize = 31.sp, fontWeight = FontWeight.Bold, color = Color(0xFF171717))
            Text("Create a new project from scratch", fontSize = 15.sp, color = Color(0xFF444444))
        }
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Name new project", fontSize = 14.sp, color = Color(0xFF555555))
            OutlinedTextField(
                value = projectName,
                onValueChange = onNameChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(0.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF303030),
                    unfocusedBorderColor = Color(0xFF777777),
                    focusedTextColor = Color(0xFF222222),
                    unfocusedTextColor = Color(0xFF222222),
                    cursorColor = Accent
                )
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NewProjectFileCard(Icons.Default.Image, "Select Image", if (imageCount > 0) "$imageCount scene images selected" else "Add your scene images") { showImageOptions = true }
            NewProjectFileCard(Icons.Default.Mic, "Add Voiceover narration", if (voiceName == "Add Voiceover narration") voiceName else voiceName, onVoice)
            NewProjectFileCard(Icons.Default.Description, "Add Scene Timing SRT", timelineSrtName, onTimelineSrt)
            NewProjectFileCard(Icons.Default.Subtitles, "Import Caption SRT", captionSrtName, onCaptionSrt)
        }
        Button(
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().height(62.dp),
            shape = RoundedCornerShape(40.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6655D8))
        ) { Text("Next", fontSize = 25.sp, fontWeight = FontWeight.Bold) }
    }
    if (showImageOptions) {
        AlertDialog(
            onDismissRequest = { showImageOptions = false },
            title = { Text("Select Image") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { showImageOptions = false; onStoryboard() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Collections, null); Spacer(Modifier.width(8.dp)); Text("Storyboard")
                    }
                    Button(onClick = { showImageOptions = false; onSingleImage() }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Image, null); Spacer(Modifier.width(8.dp)); Text("Single Image")
                    }
                }
            },
            confirmButton = {},
            containerColor = Color.White
        )
    }
}

@Composable
private fun NewProjectFileCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    detail: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().heightIn(min = 78.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF9F9FB)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF8C8C92))
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color(0xFF333333), modifier = Modifier.size(29.dp))
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF252525))
                Text(detail, fontSize = 11.sp, color = Color(0xFF66666B), maxLines = 1)
            }
            Icon(Icons.Default.Add, null, tint = Color(0xFF333333), modifier = Modifier.size(25.dp))
        }
    }
}

@Composable
fun HomePage(
    modifier: Modifier,
    projects: List<ProjectSummary>,
    openProject: (String) -> Unit,
    createProject: () -> Unit,
    status: String
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var folders by remember {
        mutableStateOf<List<String>>(
            context.getSharedPreferences("studio_home", Context.MODE_PRIVATE)
                .getStringSet("project_folders", emptySet<String>())?.toList()?.sorted() ?: emptyList()
        )
    }
    var showCreateFolder by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var selectedFolder by remember { mutableStateOf<String?>(null) }

    val filteredProjects = projects.filter {
        it.name.contains(searchQuery, ignoreCase = true)
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
            contentPadding = PaddingValues(top = 10.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search your projects") },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Outline,
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White
                    )
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Folders", fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showCreateFolder = true }) {
                        Icon(Icons.Default.CreateNewFolder, null, tint = Accent)
                        Spacer(Modifier.width(5.dp))
                        Text("New folder", color = Accent)
                    }
                }
                if (folders.isEmpty()) {
                    Text("Create folders to organise your projects.", color = Muted, fontSize = 12.sp)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        folders.forEach { folder ->
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { selectedFolder = folder },
                                colors = CardDefaults.cardColors(containerColor = Color.White),
                                shape = RoundedCornerShape(15.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Outline)
                            ) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Folder, null, tint = Accent, modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.width(11.dp))
                                    Text(folder, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Icon(Icons.Default.ChevronRight, null, tint = Muted)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("All", fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text("${filteredProjects.size} projects", color = Muted, fontSize = 11.sp)
                }
            }
            if (filteredProjects.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Icon(Icons.Default.Movie, null, tint = Muted, modifier = Modifier.size(38.dp))
                        Text(if (searchQuery.isBlank()) "No projects yet" else "No matching projects", color = Muted)
                        Text("Tap + to create your first project.", color = Muted, fontSize = 12.sp)
                    }
                }
            } else {
                items(filteredProjects, key = { it.id }) { project ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { openProject(project.id) },
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(17.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Outline)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(48.dp).background(AccentSoft, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Movie, null, tint = Accent, modifier = Modifier.size(24.dp))
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(project.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                                Text("${project.sceneCount} scenes", color = Muted, fontSize = 11.sp)
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = Muted)
                        }
                    }
                }
            }
            item {
                Text(status, color = Muted, fontSize = 10.sp, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
            }
        }
        FloatingActionButton(
            onClick = createProject,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = Accent,
            contentColor = Color.White,
            shape = RoundedCornerShape(18.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Create project", modifier = Modifier.size(28.dp))
        }
    }

    if (showCreateFolder) {
        AlertDialog(
            onDismissRequest = { showCreateFolder = false; folderName = "" },
            title = { Text("Create folder", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Folder name") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val cleanName = folderName.trim()
                        if (cleanName.isNotEmpty() && folders.none { it.equals(cleanName, true) }) {
                            folders = (folders + cleanName).sorted()
                            context.getSharedPreferences("studio_home", Context.MODE_PRIVATE)
                                .edit().putStringSet("project_folders", folders.toSet()).apply()
                        }
                        folderName = ""
                        showCreateFolder = false
                    },
                    enabled = folderName.isNotBlank()
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateFolder = false; folderName = "" }) { Text("Cancel") }
            },
            containerColor = Color.White
        )
    }

    selectedFolder?.let { folder ->
        AlertDialog(
            onDismissRequest = { selectedFolder = null },
            title = { Text(folder, fontWeight = FontWeight.Bold) },
            text = { Text("Folder created. Project organisation and moving projects into folders will be added in a later update.") },
            confirmButton = { TextButton(onClick = { selectedFolder = null }) { Text("Okay") } },
            containerColor = Color.White
        )
    }

}

@Composable
private fun HomeQuickAction(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(modifier = modifier.height(108.dp).clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Outline)) {
        Column(Modifier.fillMaxSize().padding(13.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(34.dp).background(Color(0xFFEAF0FF), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Accent, modifier = Modifier.size(19.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = Color(0xFF171B24), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Muted, fontSize = 9.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun StudioStatCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    val statusColor = when {
        label == "VOICEOVER" && value == "READY" -> Color(0xFF65D6A4)
        label == "TIMELINE SRT" && value == "READY" -> Color(0xFF65C8E8)
        else -> Accent
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(17.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Outline.copy(alpha = 0.72f))
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(Modifier.size(29.dp).background(statusColor.copy(alpha = 0.13f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = statusColor, modifier = Modifier.size(16.dp))
            }
            Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, color = Color.White)
            Text(label, color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.45.sp, maxLines = 1)
        }
    }
}

@Composable
fun ScenesPage(modifier: Modifier, scenes: List<Scene>, update: (List<Scene>) -> Unit, add: () -> Unit, replace: (Int, Scene) -> Unit, goExport: () -> Unit, globalMotion: String, setGlobalMotion: (String) -> Unit, canUndo: Boolean, canRedo: Boolean, undo: () -> Unit, redo: () -> Unit, srtText: String, audioUri: Uri?, transition: String, subtitleFontSize: Int, subtitleColor: String, subtitlePosition: String, subtitleOutline: Boolean, subtitleBackground: Boolean, aspectRatio: String) {
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

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 15.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        // Keep scene controls and preview tools fixed; only the scene list below scrolls.
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            StudioPageHeader("Scene library", "CREATE YOUR VIDEO", "Arrange visuals, motion and timing.", "${scenes.size} scenes  •  ${"%.1f".format(scenes.sumOf { it.duration.toDouble() })} sec")
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
            Text("TIMELINE • " + scenes.size + " scenes • " + "%.1f".format(scenes.sumOf { it.duration.toDouble() }) + " seconds", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("GLOBAL CAMERA MOTION", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(globalMotion) }
            androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                listOf("Static", "Auto Zoom", "Zoom In-Out", "Zoom Out-In", "Alternate", "Random", "Pan Left", "Pan Right", "Pan Up", "Pan Down", "Zoom+Pan", "Ken Burns", "Cinematic").forEach { option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = { setGlobalMotion(option); expanded = false })
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (scenes.isEmpty()) {
                item { EmptyHint("No scenes yet. Add your numbered images to begin.") }
            }
            itemsIndexed(scenes) { sceneIndex, scene ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(19.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Outline.copy(alpha = 0.8f))) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(scene.uri, displaySceneLabel(scene.name), modifier = Modifier.size(82.dp).background(Bg, RoundedCornerShape(12.dp)).clickable { previewScene.value = scene }, contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(displaySceneLabel(scene.name), fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text("${scene.duration} sec • ${scene.motion}", color = Muted, fontSize = 12.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (previewCues.isNotEmpty()) "SRT auto timing" else "Default timing • import SRT for auto timing",
                                    color = Muted,
                                    fontSize = 10.sp
                                )
                                var motionExpanded by remember(scene.id) { mutableStateOf(false) }
                                Box {
                                    TextButton(onClick = { motionExpanded = true }) { Text("Motion") }
                                    DropdownMenu(expanded = motionExpanded, onDismissRequest = { motionExpanded = false }) {
                                        listOf("Static", "Auto Zoom", "Zoom In", "Zoom Out", "Zoom In-Out", "Zoom Out-In", "Pan Left", "Pan Right", "Pan Up", "Pan Down", "Zoom+Pan", "Ken Burns", "Cinematic", "Alternate", "Random").forEach { motion ->
                                            DropdownMenuItem(text = { Text(motion) }, onClick = {
                                                update(scenes.mapIndexed { index, item -> if (index == sceneIndex) item.copy(motion = motion) else item })
                                                motionExpanded = false
                                            })
                                        }
                                    }
                                }
                            }
                            TextButton(onClick = { replace(sceneIndex, scene) }) { Text("Replace image") }
                        }
                        Column {
                            IconButton(onClick = { if (sceneIndex > 0) update(scenes.toMutableList().also { val moving = it.removeAt(sceneIndex); it.add(sceneIndex - 1, moving) }) }, enabled = sceneIndex > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                            IconButton(onClick = { if (sceneIndex < scenes.lastIndex) update(scenes.toMutableList().also { val moving = it.removeAt(sceneIndex); it.add(sceneIndex + 1, moving) }) }, enabled = sceneIndex < scenes.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                            IconButton(onClick = { update(scenes.filterIndexed { index, _ -> index != sceneIndex }) }) { Icon(Icons.Default.Delete, "Remove", tint = Color(0xFFFF7777)) }
                        }
                    }
                }
            }
            item { Text("Tip: select images in filename order (1, 2, 3..10).", color = Muted, fontSize = 12.sp) }
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
        // Subtle Ken Burns movement: visible motion with restrained zoom.
        val scale = when (previewMotion) {
            "Static" -> 1.04f
            "Zoom In", "Auto Zoom" -> 1.04f + 0.12f * progress
            "Zoom Out" -> 1.16f - 0.12f * progress
            "Zoom In-Out" -> 1.04f + 0.12f * kotlin.math.sin(progress * Math.PI).toFloat()
            "Zoom Out-In" -> 1.16f - 0.12f * kotlin.math.sin(progress * Math.PI).toFloat()
            "Pan Left", "Pan Right", "Pan Up", "Pan Down" -> 1.16f
            else -> 1.06f + 0.10f * progress
        }
        val panX = when (previewMotion) {
            "Pan Left" -> 0.04f - 0.08f * progress
            "Pan Right" -> -0.04f + 0.08f * progress
            "Cinematic", "Ken Burns", "Zoom+Pan" -> -0.025f + 0.05f * progress
            else -> 0f
        }
        val panY = when (previewMotion) {
            "Pan Up" -> 0.04f - 0.08f * progress
            "Pan Down" -> -0.04f + 0.08f * progress
            "Cinematic", "Ken Burns", "Zoom+Pan" -> 0.012f - 0.024f * progress
            else -> 0f
        }
        androidx.compose.ui.window.Dialog(onDismissRequest = { previewPlaying = false; showTimelinePreview = false }) {
            Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Timeline preview", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { previewPlaying = false; showTimelinePreview = false }) { Icon(Icons.Default.Close, "Close") }
                    }
                    Box(Modifier.fillMaxWidth().then(if (aspectRatio == "9:16") Modifier.height(420.dp) else Modifier.aspectRatio(16f / 9f)).background(Color.Black, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                        Box(Modifier.then(if (aspectRatio == "9:16") Modifier.fillMaxHeight().aspectRatio(9f / 16f) else Modifier.fillMaxSize()), contentAlignment = Alignment.Center) {
                        AsyncImage(
                            model = currentScene.uri,
                            contentDescription = displaySceneLabel(currentScene.name),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = size.width * panX
                                translationY = size.height * panY
                            }
                        )
                        if (previewTransitionAlpha > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = previewTransitionAlpha)))
                        Text(displaySceneLabel(currentScene.name), color = Color.White, modifier = Modifier.align(Alignment.TopStart).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(8.dp))
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
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        StudioPageHeader("Subtitle studio", "CAPTIONS & STYLE", fileName, "${cues.size} timed cues")
        Button(onClick = import, modifier = Modifier.fillMaxWidth().height(49.dp), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("Import subtitle SRT", fontWeight = FontWeight.Bold) }
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Outline.copy(alpha = 0.65f))) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("SUBTITLE STYLE", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp); Spacer(Modifier.weight(1f)); Surface(color = AccentSoft, shape = RoundedCornerShape(8.dp)) { Text("PREVIEW", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = Accent, fontSize = 8.sp, fontWeight = FontWeight.Bold) } }
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
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        StudioPageHeader("Export studio", "FINAL OUTPUT", "Configure quality and render your finished film.", "MP4  •  H.264  •  AAC")
        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Outline.copy(alpha = 0.8f))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("PROJECT CHECK", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp); Spacer(Modifier.weight(1f)); Icon(Icons.Default.FactCheck, null, tint = Accent, modifier = Modifier.size(17.dp)) }
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("16:9", "9:16").forEach { value -> FilterChip(selected = aspectRatio == value, onClick = { setAspectRatio(value) }, label = { Text(value) }) } }
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
        Button(onClick = onExport, enabled = count > 0 && srtCount == count && !exporting, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Default.Movie, null); Spacer(Modifier.width(9.dp)); Text(if (exporting) "Rendering your video…" else "Render & Export MP4", fontWeight = FontWeight.Bold)
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
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(15.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Outline.copy(alpha = 0.7f))) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
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
private fun StudioPageHeader(title: String, kicker: String, subtitle: String, meta: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(21.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF5E67B7).copy(alpha = 0.28f))
    ) {
        Box(
            Modifier.fillMaxWidth().background(
                Brush.linearGradient(listOf(Color(0xFF273E79), Color(0xFF40346F), Color(0xFF25233D)))
            ).padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(kicker, color = Color(0xFFC5CFFF), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.25.sp)
                Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.45).sp)
                Text(subtitle, color = Color.White.copy(alpha = 0.78f), fontSize = 11.sp, maxLines = 2)
                Surface(color = Color.White.copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp)) {
                    Text(meta, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun ActionCard(title:String, detail:String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick:()->Unit) {
    Card(
        Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Outline.copy(alpha = 0.72f))
    ) {
        Row(Modifier.padding(horizontal = 15.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(AccentSoft, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Accent, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(detail, color = Muted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2)
            }
            Icon(Icons.Default.ChevronRight, null, tint = Muted, modifier = Modifier.size(20.dp))
        }
    }
}
@Composable fun SectionTitle(text:String) { Text(text.uppercase(Locale.ROOT), color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
@Composable fun EmptyHint(text:String) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Outline.copy(alpha = 0.75f))) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(42.dp).background(AccentSoft, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.AutoAwesome, null, tint = Accent) }
            Text("Nothing here yet", color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(text, color = Muted, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 17.sp)
        }
    }
}


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
    timelineSrtText: String,
    onDone: () -> Unit
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
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
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
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        StudioPageHeader("Storyboard Pro", "IMAGE WORKFLOW", "Turn contact sheets into individual scene images.", "BATCH IMPORT  •  PANEL SELECTION  •  AUTO CHECK")

        Button(
            onClick = { storyboardFolderPicker.launch(null) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !processing
        ) {
            Icon(Icons.Default.Folder, null); Spacer(Modifier.width(8.dp)); Text("Import Image Folder")
        }
        OutlinedButton(
            onClick = { picker.launch(arrayOf("image/*")) },
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
                    modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
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
                            onDone()
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
                    onDone()
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
                                    if (selected) Accent else PanelElevated,
                                    RoundedCornerShape(10.dp)
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
                            onDone()
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


private fun displaySceneLabel(fileName: String): String {
    val base = fileName.substringAfterLast('/').substringBeforeLast('.', fileName)
    val match = Regex("^\\d+[-_ ]*([A-Za-z])\\1?[-_ ]*(\\d+)$", RegexOption.IGNORE_CASE).find(base)
    if (match != null) {
        val group = match.groupValues[1].uppercase(Locale.ROOT)
        val number = match.groupValues[2].toIntOrNull()?.toString()?.padStart(2, '0') ?: match.groupValues[2]
        return "$group-$number"
    }
    return base
}
