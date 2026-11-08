package com.inkorigin.animationstudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

data class Scene(val uri: Uri, val name: String, val duration: Float = 4f, val motion: String = "Auto Zoom")

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
    var srtName by remember { mutableStateOf("No subtitle file selected") }
    var projectName by remember { mutableStateOf("Untitled Project") }
    var status by remember { mutableStateOf("Ready to create") }
    var resolution by remember { mutableStateOf("1080p") }
    var globalMotion by remember { mutableStateOf("Cinematic") }
    var aspectRatio by remember { mutableStateOf("16:9") }
    var subtitleFontSize by remember { mutableIntStateOf(32) }
    var subtitleColor by remember { mutableStateOf("White") }
    var subtitlePosition by remember { mutableStateOf("Bottom") }
    var subtitleOutline by remember { mutableStateOf(true) }
    var subtitleBackground by remember { mutableStateOf(false) }
    var projectId by remember { mutableStateOf("project_" + System.currentTimeMillis()) }
    var savedProjects by remember { mutableStateOf(emptyList<ProjectSummary>()) }
    var showProjects by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf("Ready to export") }
    var lastExportUri by remember { mutableStateOf<Uri?>(null) }
    var exportHistory by remember { mutableStateOf(emptyList<ExportHistoryEntry>()) }

    LaunchedEffect(Unit) {
        savedProjects = withContext(Dispatchers.IO) { ProjectStore.list(context) }
        exportHistory = withContext(Dispatchers.IO) { ExportHistoryStore.list(context) }
    }
    LaunchedEffect(projectId, projectName, scenes, audio, srtText, resolution, aspectRatio, globalMotion, subtitleFontSize, subtitleColor, subtitlePosition, subtitleOutline, subtitleBackground) {
        delay(900)
        runCatching {
            withContext(Dispatchers.IO) { ProjectStore.save(context, projectId, projectName, scenes, audio, srtText, resolution, aspectRatio, globalMotion, subtitleFontSize, subtitleColor, subtitlePosition, subtitleOutline, subtitleBackground) }
            savedProjects = withContext(Dispatchers.IO) { ProjectStore.list(context) }
        }.onFailure { status = "Autosave error: " + (it.message ?: "Unknown error") }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { folderUri ->
        if (folderUri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(folderUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            scope.launch {
                val imported = withContext(Dispatchers.IO) { DocumentFile.fromTreeUri(context, folderUri)?.let { collectImageDocuments(it) } ?: emptyList() }
                if (imported.isNotEmpty()) {
                    updateScenes((scenes + imported.map { file -> Scene(file.uri, file.name ?: "Image") }).sortedWith(compareBy { naturalKey(it.name) }))
                    status = imported.size.toString() + " images imported from folder"
                } else status = "No images found in selected folder"
            }
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            updateScenes(uris.map { Scene(it, it.lastPathSegment?.substringAfterLast('/') ?: "Scene") }
                .sortedWith(compareBy { naturalKey(it.name) }))
            status = "${scenes.size} images imported"
        }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { audio = it; if (it != null) status = "Voiceover selected" }
    val srtPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            srtName = uri.lastPathSegment ?: "Subtitle file"
            status = "Reading subtitle file…"
            scope.launch {
                try {
                    val loadedText = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                            reader.readText().removePrefix("\uFEFF")
                        } ?: throw IllegalStateException("Cannot open subtitle file")
                    }
                    srtText = loadedText
                    val cueCount = Regex("""(?m)^\s*\d{2}:\d{2}:\d{2}[,.]\d{3}\s*-->""").findAll(loadedText).count()
                    status = if (cueCount > 0) {
                        "SRT loaded • $cueCount subtitle cues"
                    } else {
                        "Subtitle file loaded • no standard SRT timestamps detected"
                    }
                } catch (e: Exception) {
                    status = "SRT import failed: ${e.message ?: "Unknown error"}"
                }
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
                "Home" -> HomePage(Modifier.padding(padding), projectName, { projectName = it }, scenes.size, audio != null, srtName, status, { imagePicker.launch("image/*") }, { folderPicker.launch(null) }, { audioPicker.launch("audio/*") }, { srtPicker.launch("*/*") }, { tab = "Scenes" }, { showProjects = true })
                "Scenes" -> ScenesPage(Modifier.padding(padding), scenes, ::updateScenes, { imagePicker.launch("image/*") }, globalMotion, { selectedMotion -> globalMotion = selectedMotion; updateScenes(scenes.map { scene -> scene.copy(motion = selectedMotion) }) }, sceneUndo.isNotEmpty(), sceneRedo.isNotEmpty(), ::undoScenes, ::redoScenes)
                "Storyboard" -> StoryboardPage(Modifier.padding(padding), scenes, ::updateScenes)
            "Subtitles" -> SubtitlePage(Modifier.padding(padding), srtName, srtText, { srtText = it }, { srtPicker.launch("*/*") }, subtitleFontSize, { subtitleFontSize = it }, subtitleColor, { subtitleColor = it }, subtitlePosition, { subtitlePosition = it }, subtitleOutline, { subtitleOutline = it }, subtitleBackground, { subtitleBackground = it })
                else -> ExportPage(Modifier.padding(padding), scenes.size, audio != null, resolution, { resolution = it }, aspectRatio, { aspectRatio = it }, exporting, exportStatus, { VideoExportEngine.export(context, scenes, audio, srtText, ExportSettings(resolution, aspectRatio, subtitleFontSize, subtitleColor, subtitlePosition, subtitleOutline, subtitleBackground), onStarted = { exporting = true; exportStatus = "Rendering video…"; lastExportUri = null }, onCompleted = { uri -> exporting = false; lastExportUri = uri; exportStatus = "Video saved to Movies / Animation Studio"; status = "Export complete"; scope.launch { withContext(Dispatchers.IO) { ExportHistoryStore.add(context, uri) }; exportHistory = withContext(Dispatchers.IO) { ExportHistoryStore.list(context) } } }, onError = { message -> exporting = false; exportStatus = "Export failed: " + message }) }, { uri -> val share = Intent(Intent.ACTION_SEND).apply { type = "video/mp4"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }; context.startActivity(Intent.createChooser(share, "Share exported video")) }, exportHistory, status)
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
                                                resolution = loaded.resolution
                                                aspectRatio = loaded.aspectRatio
                                                globalMotion = loaded.globalMotion
                                                subtitleFontSize = loaded.subtitleFontSize
                                                subtitleColor = loaded.subtitleColor
                                                subtitlePosition = loaded.subtitlePosition
                                                subtitleOutline = loaded.subtitleOutline
                                                subtitleBackground = loaded.subtitleBackground
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
                    srtName = "No subtitle file selected"
                    status = "New project created"
                    showProjects = false
                }) { Text("New project") }
            }
        )
    }
}

@Composable
fun HomePage(modifier: Modifier, name: String, setName: (String)->Unit, count: Int, hasAudio: Boolean, srt: String, status: String, addImages:()->Unit, addFolder:()->Unit, addAudio:()->Unit, addSrt:()->Unit, goScenes:()->Unit, openProjects:()->Unit) {
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
        item { ActionCard("Scene images", "$count images selected", Icons.Default.Image, addImages) }
        item { ActionCard("Import image folder", "Add all images from a folder", Icons.Default.Folder, addFolder) }
        item { ActionCard("Voiceover", if(hasAudio) "Audio file selected" else "Choose narration audio", Icons.Default.Mic, addAudio) }
        item { ActionCard("Subtitles (SRT)", srt, Icons.Default.Subtitles, addSrt) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("WORKFLOW", fontWeight = FontWeight.Bold)
                    Text("1. Import scene images\n2. Add voiceover audio\n3. Import or edit subtitles\n4. Set motion and export settings", color = Muted, lineHeight = 23.sp)
                    Button(onClick = goScenes, modifier = Modifier.fillMaxWidth()) { Text("Manage scenes") }
                }
            }
        }
    }
}

@Composable
fun ScenesPage(modifier: Modifier, scenes: List<Scene>, update: (List<Scene>) -> Unit, add: () -> Unit, globalMotion: String, setGlobalMotion: (String) -> Unit, canUndo: Boolean, canRedo: Boolean, undo: () -> Unit, redo: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
        var previewScene = remember { mutableStateOf<Scene?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Scene library", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = undo, enabled = canUndo, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Undo, null); Text("Undo") }
                    OutlinedButton(onClick = redo, enabled = canRedo, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Redo, null); Text("Redo") }
                }
                OutlinedButton(onClick = add, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add images")
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
    val cues = remember(text) { SubtitleEngine.parse(text) }
    var showCueList by remember { mutableStateOf(true) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Subtitles", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(fileName, color = Muted)
        Button(onClick = import, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("Import SRT file") }
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SUBTITLE STYLE", fontWeight = FontWeight.Bold)
                Text("Font size: " + fontSize + " px", color = Muted)
                Slider(value = fontSize.toFloat(), onValueChange = { setFontSize(it.toInt()) }, valueRange = 18f..64f, steps = 22)
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
                    Text(cues.firstOrNull()?.text ?: "Your subtitle preview appears here", color = when(color) { "Yellow" -> Color.Yellow; "Cyan" -> Color.Cyan; else -> Color.White }, fontSize = fontSize.coerceIn(12, 48).sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(12.dp))
                }
                Text("Preview settings are saved with the project. Outline and final burn-in are applied during video rendering.", color = Muted, fontSize = 11.sp)
                Text("Word highlighting is spread evenly across each SRT cue. It is approximate unless your subtitles contain word-level timestamps.", color = Muted, fontSize = 11.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SRT CUES (" + cues.size + ")", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { showCueList = !showCueList }) { Text(if (showCueList) "Hide" else "Show") }
        }
        if (showCueList) {
            if (cues.isEmpty()) Text("No valid timestamped cues found yet.", color = Muted)
            cues.take(200).forEach { cue ->
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text(SubtitleEngine.formatTime(cue.startMs) + " → " + SubtitleEngine.formatTime(cue.endMs), color = Accent, fontSize = 11.sp)
                        Text(cue.text, fontSize = 14.sp)
                    }
                }
            }
            if (cues.size > 200) Text("Showing first 200 of " + cues.size + " cues.", color = Muted, fontSize = 11.sp)
        }
        Text("SRT CONTENT / EDITOR", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = text, onValueChange = setText, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp), placeholder = { Text("Imported subtitle text will appear here…") })
    }
}

@Composable
fun ExportPage(modifier: Modifier, count: Int, audio: Boolean, resolution: String, setResolution:(String)->Unit, aspectRatio: String, setAspectRatio:(String)->Unit, exporting: Boolean, exportStatus: String, onExport: () -> Unit, onShare: (Uri) -> Unit, history: List<ExportHistoryEntry>, status:String) {
    Column(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Export video", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PROJECT CHECK", fontWeight = FontWeight.Bold)
                Text("Scene images: $count\nVoiceover: ${if(audio) "Added" else "Missing"}\nAspect ratio: $aspectRatio\nResolution: $resolution", color = Muted, lineHeight = 24.sp)
            }
        }
        Text("ASPECT RATIO", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("16:9", "9:16", "1:1").forEach { value -> FilterChip(selected = aspectRatio == value, onClick = { setAspectRatio(value) }, label = { Text(value) }) } }
        Text("OUTPUT RESOLUTION", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("720p", "1080p", "4K").forEach { value ->
                FilterChip(selected = resolution == value, onClick = { setResolution(value) }, label = { Text(value) })
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(16.dp)) {
                Text("MP4 rendering", fontWeight = FontWeight.Bold)
                Text("H.264 video • AAC voiceover • SRT subtitle burn-in • 30 fps", color = Muted, fontSize = 13.sp)
            }
        }
        Button(onClick = onExport, enabled = count > 0 && !exporting, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Movie, null); Spacer(Modifier.width(8.dp)); Text(if (exporting) "Rendering…" else "Export MP4")
        }
        if (exporting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(exportStatus, color = Accent)
        if (exportStatus.startsWith("Video saved")) OutlinedButton(onClick = { /* current export appears at the top of history */ history.firstOrNull()?.let(onShare) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(8.dp)); Text("Share video") }
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

fun naturalKey(value: String): String = value.lowercase().replace(Regex("\\d+")) { match ->
    match.value.padStart(12, '0')
}



private fun detectBlankPanels(bitmap: Bitmap, grid: Int): Set<Int> {
    val blanks = mutableSetOf<Int>()

    for (index in 0 until grid * grid) {
        val row = index / grid
        val col = index % grid
        val left = col * bitmap.width / grid
        val top = row * bitmap.height / grid
        val right = (col + 1) * bitmap.width / grid
        val bottom = (row + 1) * bitmap.height / grid

        var sum = 0.0
        var sumSq = 0.0
        var count = 0

        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val px = (left + (x + 0.5) * (right - left) / 8)
                    .toInt().coerceIn(left, right - 1)
                val py = (top + (y + 0.5) * (bottom - top) / 8)
                    .toInt().coerceIn(top, bottom - 1)
                val color = bitmap.getPixel(px, py)
                val gray = (
                    0.299 * android.graphics.Color.red(color) +
                    0.587 * android.graphics.Color.green(color) +
                    0.114 * android.graphics.Color.blue(color)
                )
                sum += gray
                sumSq += gray * gray
                count++
            }
        }

        val mean = sum / count
        val variance = sumSq / count - mean * mean

        if ((mean > 225 || mean < 35) && variance < 180) {
            blanks.add(index)
        }
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

@Composable
fun StoryboardPage(
    modifier: Modifier,
    scenes: List<Scene>,
    onScenesChange: (List<Scene>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var storyboardUri by remember { mutableStateOf<Uri?>(null) }
    var gridSize by remember { mutableIntStateOf(3) }
    var blankPanels by remember { mutableStateOf(setOf<Int>()) }
    var status by remember { mutableStateOf("Choose a storyboard image") }
    var processing by remember { mutableStateOf(false) }

    val selectedPanels = remember { mutableStateListOf<Int>() }

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
            onClick = { picker.launch("image/*") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !processing
        ) {
            Text("Select Storyboard Image")
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

                                    for (index in selectedPanels.filterNot { it in blankPanels }.sorted()) {
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
                            onScenesChange(
                                (scenes + addedScenes).sortedWith(
                                    compareBy<Scene> { naturalKey(it.name) }
                                )
                            )
                            status =
                                "${addedScenes.size} panels added. ${result.second} duplicates skipped."
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
