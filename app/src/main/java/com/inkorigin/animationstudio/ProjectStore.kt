package com.inkorigin.animationstudio

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class ProjectSummary(val id: String, val name: String, val updatedAt: Long, val sceneCount: Int)
data class LoadedProject(
    val id: String, val name: String, val scenes: List<Scene>, val audio: Uri?, val srtText: String, val wordSrtText: String,
    val resolution: String, val aspectRatio: String, val globalMotion: String,
    val subtitleFontSize: Int, val subtitleColor: String, val subtitlePosition: String,
    val subtitleOutline: Boolean, val subtitleBackground: Boolean, val frameRate: Int, val transition: String
)

object ProjectStore {
    private fun root(context: Context) = File(context.filesDir, "projects").apply { mkdirs() }

    private const val STATE_PREFS = "animation_studio_project_state"

    private fun replaceFile(temporary: File, target: File) {
        try {
            Files.move(
                temporary.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun safeProjectId(id: String): String {
        require(id.matches(Regex("project_[0-9]+"))) { "Invalid project ID" }
        return id
    }

    fun lastOpenedId(context: Context): String? {
        val id = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            .getString("last_opened_project_id", null) ?: return null
        if (!id.startsWith("project_") || id.contains('/') || id.contains('\\')) return null
        return id.takeIf { File(root(context), "$it/project.json").isFile }
    }

    fun markOpened(context: Context, id: String) {
        if (id.startsWith("project_") && !id.contains('/') && !id.contains('\\')) {
            context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .edit().putString("last_opened_project_id", id).apply()
        }
    }

    @Synchronized
    fun list(context: Context): List<ProjectSummary> =
        root(context).listFiles()?.filter { File(it, "project.json").isFile }?.mapNotNull { folder ->
            runCatching {
                val json = JSONObject(File(folder, "project.json").readText())
                ProjectSummary(folder.name, json.optString("name", "Untitled Project"), json.optLong("updatedAt", folder.lastModified()), json.optJSONArray("scenes")?.length() ?: 0)
            }.getOrNull()
        }?.sortedByDescending { it.updatedAt } ?: emptyList()

    @Synchronized
    fun save(
        context: Context, id: String, name: String, scenes: List<Scene>, audio: Uri?, srtText: String, wordSrtText: String,
        resolution: String, aspectRatio: String, globalMotion: String, subtitleFontSize: Int,
        subtitleColor: String, subtitlePosition: String, subtitleOutline: Boolean, subtitleBackground: Boolean, frameRate: Int, transition: String
    ) {
        val safeId = safeProjectId(id)
        val folder = File(root(context), safeId).apply { mkdirs() }
        val assets = File(folder, "assets").apply { mkdirs() }
        val sceneArray = JSONArray()
        scenes.forEachIndexed { index, scene ->
            val namedExtension = scene.name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }
            val mimeExtension = MimeTypeMap.getSingleton().getExtensionFromMimeType(context.contentResolver.getType(scene.uri))
            val extension = namedExtension.takeIf { it.length in 2..5 } ?: mimeExtension ?: "jpg"
            val target = File(assets, "scene_%04d.%s".format(index + 1, extension))
            copyUri(context, scene.uri, target)
            sceneArray.put(JSONObject().put("path", target.absolutePath).put("name", scene.name).put("duration", scene.duration.toDouble()).put("motion", scene.motion).put("sceneId", scene.id))
        }
        val audioTarget = audio?.let { uri ->
            val namedExtension = (uri.lastPathSegment ?: "").substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }
            val mimeExtension = MimeTypeMap.getSingleton().getExtensionFromMimeType(context.contentResolver.getType(uri))
            val extension = namedExtension.takeIf { it.length in 2..5 } ?: mimeExtension ?: "audio"
            File(assets, "voiceover_audio." + extension).also { target -> copyUri(context, uri, target) }
        }
        val json = JSONObject().put("id", id).put("name", name.ifBlank { "Untitled Project" }).put("updatedAt", System.currentTimeMillis())
            .put("scenes", sceneArray).put("audioPath", audioTarget?.absolutePath ?: JSONObject.NULL)
            .put("srtText", srtText).put("wordSrtText", wordSrtText).put("resolution", resolution).put("aspectRatio", aspectRatio).put("globalMotion", globalMotion)
            .put("subtitleFontSize", subtitleFontSize).put("subtitleColor", subtitleColor).put("subtitlePosition", subtitlePosition)
             .put("subtitleOutline", subtitleOutline).put("subtitleBackground", subtitleBackground).put("frameRate", frameRate).put("transition", transition)
        val projectFile = File(folder, "project.json")
        val temporaryProjectFile = File(folder, "project.json.tmp")
        temporaryProjectFile.writeText(json.toString())
        try {
            replaceFile(temporaryProjectFile, projectFile)
        } catch (error: Exception) {
            temporaryProjectFile.delete()
            throw error
        }
    }

    @Synchronized
    fun load(context: Context, id: String): LoadedProject {
        val folder = File(root(context), safeProjectId(id))
        val json = JSONObject(File(folder, "project.json").readText())
        val array = json.optJSONArray("scenes") ?: JSONArray()
        val scenes = (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            val file = File(item.optString("path"))
            if (!file.isFile) return@mapNotNull null
            Scene(Uri.fromFile(file), item.optString("name", file.name), item.optDouble("duration", 4.0).toFloat(), item.optString("motion", "Cinematic"), item.optString("sceneId").takeIf { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString())
        }
        val audioPath = json.optString("audioPath", "")
        val savedSubtitleFontSize = json.optInt("subtitleFontSize", 64)
        val migratedSubtitleFontSize = if (savedSubtitleFontSize == 32) 64 else savedSubtitleFontSize
        return LoadedProject(id, json.optString("name", "Untitled Project"), scenes,
            if (audioPath.isNotBlank() && File(audioPath).isFile) Uri.fromFile(File(audioPath)) else null,
            json.optString("srtText", ""), json.optString("wordSrtText", ""), json.optString("resolution", "1080p"), json.optString("aspectRatio", "16:9"), json.optString("globalMotion", "Cinematic"),
            migratedSubtitleFontSize, json.optString("subtitleColor", "White"), json.optString("subtitlePosition", "Bottom"),
            json.optBoolean("subtitleOutline", true), json.optBoolean("subtitleBackground", false), json.optInt("frameRate", 30), json.optString("transition", "Fade through black"))
    }

    @Synchronized
    fun duplicate(context: Context, id: String): String {
        val source = File(root(context), safeProjectId(id))
        require(File(source, "project.json").isFile) { "Project not found" }

        var newId: String
        var destination: File
        do {
            newId = "project_" + System.currentTimeMillis()
            destination = File(root(context), newId)
        } while (destination.exists())

        try {
            source.copyRecursively(destination, overwrite = false)
            val sourceJson = JSONObject(File(source, "project.json").readText())
            val json = JSONObject(File(destination, "project.json").readText())
                .put("id", newId)
                .put("name", sourceJson.optString("name", "Project") + " Copy")
                .put("updatedAt", System.currentTimeMillis())

            // A duplicated project owns its copied assets. Never leave absolute paths
            // pointing back into the original project, otherwise deleting the original
            // would silently break the duplicate's images and voiceover.
            val sourceRootPath = source.canonicalFile.toPath()
            fun copiedAssetPath(oldPath: String, label: String): File {
                val originalAssetPath = File(oldPath).canonicalFile.toPath()
                require(originalAssetPath.startsWith(sourceRootPath)) { "$label is outside the source project" }
                val relativePath = sourceRootPath.relativize(originalAssetPath)
                return File(destination, relativePath.toString())
            }

            val copiedScenes = json.optJSONArray("scenes") ?: JSONArray()
            for (index in 0 until copiedScenes.length()) {
                val scene = copiedScenes.optJSONObject(index) ?: continue
                val oldPath = scene.optString("path")
                if (oldPath.isBlank()) continue
                val copiedAsset = copiedAssetPath(oldPath, "A scene asset")
                require(copiedAsset.isFile) { "A copied scene image is missing" }
                scene.put("path", copiedAsset.absolutePath)
            }

            val oldAudioPath = json.optString("audioPath", "")
            if (oldAudioPath.isNotBlank() && oldAudioPath != "null") {
                val copiedAudio = copiedAssetPath(oldAudioPath, "The voiceover asset")
                require(copiedAudio.isFile) { "The copied voiceover is missing" }
                json.put("audioPath", copiedAudio.absolutePath)
            }

            val projectFile = File(destination, "project.json")
            val temporaryProjectFile = File(destination, "project.json.tmp")
            temporaryProjectFile.writeText(json.toString())
            replaceFile(temporaryProjectFile, projectFile)
            return newId
        } catch (error: Exception) {
            destination.deleteRecursively()
            throw error
        }
    }

    @Synchronized
    fun rename(context: Context, id: String, name: String) {
        val file = File(root(context), "${safeProjectId(id)}/project.json")
        val json = JSONObject(file.readText()).put("name", name.ifBlank { "Untitled Project" }).put("updatedAt", System.currentTimeMillis())
        file.writeText(json.toString())
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        val safeId = safeProjectId(id)
        File(root(context), safeId).deleteRecursively()
        val prefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("last_opened_project_id", null) == safeId) {
            prefs.edit().remove("last_opened_project_id").apply()
        }
    }

    private fun copyUri(context: Context, uri: Uri, target: File) {
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.delete()
        try {
            if (uri.scheme == "file") {
                val source = File(uri.path ?: error("Missing file path"))
                source.inputStream().use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
            } else {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Cannot read selected file")
            }
            replaceFile(temporary, target)
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }
}
