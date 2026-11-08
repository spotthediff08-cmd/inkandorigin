package com.inkorigin.animationstudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    var tab by remember { mutableStateOf("Home") }
    var scenes by remember { mutableStateOf(listOf<Scene>()) }
    var audio by remember { mutableStateOf<Uri?>(null) }
    var srtText by remember { mutableStateOf("") }
    var srtName by remember { mutableStateOf("No subtitle file selected") }
    var projectName by remember { mutableStateOf("Untitled Project") }
    var status by remember { mutableStateOf("Ready to create") }
    var resolution by remember { mutableStateOf("1080p") }
    var globalMotion by remember { mutableStateOf("Cinematic") }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            scenes = uris.map { Scene(it, it.lastPathSegment?.substringAfterLast('/') ?: "Scene") }
                .sortedWith(compareBy { naturalKey(it.name) })
            status = "${scenes.size} images imported"
        }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { audio = it; if (it != null) status = "Voiceover selected" }
    val srtPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            srtName = uri.lastPathSegment ?: "Subtitle file"
            status = "Reading subtitle file…"
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
                "Home" -> HomePage(Modifier.padding(padding), projectName, { projectName = it }, scenes.size, audio != null, srtName, status, { imagePicker.launch("image/*") }, { audioPicker.launch("audio/*") }, { srtPicker.launch("*/*") }, { tab = "Scenes" })
                "Scenes" -> ScenesPage(Modifier.padding(padding), scenes, { scenes = it }, { imagePicker.launch("image/*") }, globalMotion, { globalMotion = it })
                "Storyboard" -> StoryboardPage(Modifier.padding(padding), scenes) { scenes = it }
            "Subtitles" -> SubtitlePage(Modifier.padding(padding), srtName, srtText, { srtText = it }, { srtPicker.launch("*/*") })
                else -> ExportPage(Modifier.padding(padding), scenes.size, audio != null, resolution, { resolution = it }, status)
            }
        }
    }
}

@Composable
fun HomePage(modifier: Modifier, name: String, setName: (String)->Unit, count: Int, hasAudio: Boolean, srt: String, status: String, addImages:()->Unit, addAudio:()->Unit, addSrt:()->Unit, goScenes:()->Unit) {
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
        item { SectionTitle("IMPORT YOUR ASSETS") }
        item { ActionCard("Scene images", "$count images selected", Icons.Default.Image, addImages) }
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
fun ScenesPage(modifier: Modifier, scenes: List<Scene>, update: (List<Scene>) -> Unit, add: () -> Unit, globalMotion: String, setGlobalMotion: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
        var previewScene = remember { mutableStateOf<Scene?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Scene library", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = add, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add images")
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("GLOBAL CAMERA MOTION", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(globalMotion)
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    listOf("Static", "Auto Zoom", "Zoom In-Out", "Zoom Out-In", "Alternate", "Random", "Pan", "Ken Burns", "Cinematic").forEach { option ->
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
                    Column(Modifier.weight(1f)) {
                        Text(scene.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("${scene.duration} sec • $globalMotion", color = Muted, fontSize = 12.sp)
                    }
                    IconButton(onClick = { update(scenes - scene) }) {
                        Icon(Icons.Default.Delete, "Remove", tint = Color(0xFFFF7777))
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
fun SubtitlePage(modifier: Modifier, fileName: String, text: String, setText:(String)->Unit, import:()->Unit) {
    Column(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Subtitles", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(fileName, color = Muted)
        Button(onClick = import, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("Import SRT file") }
        Text("SRT CONTENT / EDITOR", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = text, onValueChange = setText, modifier = Modifier.fillMaxWidth().weight(1f), placeholder = { Text("Imported subtitle text will appear here…") })
        Text("Subtitle styling controls will be added in the next build.", color = Muted, fontSize = 12.sp)
    }
}

@Composable
fun ExportPage(modifier: Modifier, count: Int, audio: Boolean, resolution: String, setResolution:(String)->Unit, status:String) {
    Column(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Export video", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PROJECT CHECK", fontWeight = FontWeight.Bold)
                Text("Scene images: $count\nVoiceover: ${if(audio) "Added" else "Missing"}\nResolution: $resolution", color = Muted, lineHeight = 24.sp)
            }
        }
        Text("OUTPUT RESOLUTION", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("720p", "1080p", "4K").forEach { value ->
                FilterChip(selected = resolution == value, onClick = { setResolution(value) }, label = { Text(value) })
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
            Column(Modifier.padding(16.dp)) {
                Text("Export engine", fontWeight = FontWeight.Bold)
                Text("This fresh project includes the interface and asset workflow foundation. Actual MP4 rendering, subtitle burn-in, and final encoding are not yet connected; they require the video-rendering engine in a later tested build.", color = Muted, fontSize = 13.sp)
            }
        }
        Text(status, color = Accent)
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
                        selectedPanels.addAll((0 until gridSize * gridSize).filterNot { it in blankPanels })
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
