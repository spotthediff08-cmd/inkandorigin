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
                    AsyncImage(scene.uri, null, modifier = Modifier.size(76.dp).background(Bg, RoundedCornerShape(10.dp)))
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


@Composable
fun StoryboardPage(
    modifier: Modifier,
    scenes: List<Scene>,
    onScenesChange: (List<Scene>) -> Unit
) {
    val context = LocalContext.current
    var storyboardUri by remember { mutableStateOf<Uri?>(null) }
    val selectedPanels = remember {
        mutableStateListOf<Int>().apply { addAll(0..8) }
    }
    var status by remember { mutableStateOf("Select a 3×3 storyboard image") }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        storyboardUri = uri
        selectedPanels.clear()
        selectedPanels.addAll(0..8)
        status = if (uri != null) "Image ready to split" else "No image selected"
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Storyboard Splitter", fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text("Select one image containing 9 equal panels.", color = Muted)

        Button(
            onClick = { picker.launch("image/*") },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Select Storyboard Image")
        }

        storyboardUri?.let { uri ->
            AsyncImage(
                model = uri,
                contentDescription = "Storyboard preview",
                modifier = Modifier.fillMaxWidth().height(180.dp),
                contentScale = ContentScale.Fit
            )

            Text("Tap panels to include or exclude them.", color = Muted)

            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                repeat(3) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        repeat(3) { col ->
                            val index = row * 3 + col
                            val selected = index in selectedPanels

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .background(
                                        if (selected) Accent else Panel,
                                        RoundedCornerShape(9.dp)
                                    )
                                    .clickable {
                                        if (selected) selectedPanels.remove(index)
                                        else selectedPanels.add(index)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "AA_${(index + 1).toString().padStart(2, '0')}",
                                    color = if (selected) Color.Black else Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            Text("${selectedPanels.size} of 9 panels selected", color = Muted)

            Button(
                onClick = {
                    try {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: throw Exception("Cannot open selected image")

                        val bitmap = input.use { BitmapFactory.decodeStream(it) }
                            ?: throw Exception("Invalid image")

                        if (bitmap.width < 3 || bitmap.height < 3) {
                            bitmap.recycle()
                            throw Exception("Image is too small")
                        }

                        val folder = File(
                            context.filesDir,
                            "storyboard/${System.currentTimeMillis()}"
                        )
                        if (!folder.mkdirs() && !folder.isDirectory) {
                            bitmap.recycle()
                            throw Exception("Cannot create output folder")
                        }

                        val addedScenes = selectedPanels.sorted().map { index ->
                            val row = index / 3
                            val col = index % 3
                            val left = col * bitmap.width / 3
                            val top = row * bitmap.height / 3
                            val right = (col + 1) * bitmap.width / 3
                            val bottom = (row + 1) * bitmap.height / 3

                            val crop = Bitmap.createBitmap(
                                bitmap, left, top, right - left, bottom - top
                            )
                            val filename =
                                "AA_${(index + 1).toString().padStart(2, '0')}.jpg"
                            val file = File(folder, filename)

                            FileOutputStream(file).use {
                                crop.compress(Bitmap.CompressFormat.JPEG, 95, it)
                            }
                            crop.recycle()
                            Scene(Uri.fromFile(file), filename)
                        }

                        bitmap.recycle()
                        onScenesChange(
                            (scenes + addedScenes).sortedWith(compareBy { naturalKey(it.name) })
                        )
                        status = "${addedScenes.size} panels added to Scenes"
                    } catch (e: Exception) {
                        status = "Error: ${e.message ?: "Could not split image"}"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedPanels.isNotEmpty()
            ) {
                Text("Split & Add to Scenes")
            }
        }

        Text(status, color = Accent)
    }
}
