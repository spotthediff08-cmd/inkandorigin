package com.inkorigin.animationstudio

data class SubtitleCue(val index: Int, val startMs: Long, val endMs: Long, val text: String)

object SubtitleEngine {
    private val timestamp = Regex("""(\d{2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})[,.](\d{3})""")
    fun parse(source: String): List<SubtitleCue> {
        val normalized = source.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val blocks = normalized.trim().split(Regex("\n\s*\n+"))
        val cues = mutableListOf<SubtitleCue>()
        blocks.forEach { block ->
            val lines = block.lines()
            val timeLineIndex = lines.indexOfFirst { timestamp.containsMatchIn(it) }
            if (timeLineIndex >= 0) {
                val match = timestamp.find(lines[timeLineIndex]) ?: return@forEach
                val values = (1..8).map { match.groupValues[it].toLong() }
                val start = toMs(values[0], values[1], values[2], values[3])
                val end = toMs(values[4], values[5], values[6], values[7])
                val text = lines.drop(timeLineIndex + 1).joinToString("\n").trim()
                if (text.isNotBlank() && end > start) cues += SubtitleCue(cues.size + 1, start, end, text)
            }
        }
        return cues
    }
    private fun toMs(h: Long, m: Long, s: Long, ms: Long): Long = h * 3_600_000L + m * 60_000L + s * 1_000L + ms
    fun activeCue(cues: List<SubtitleCue>, positionMs: Long): SubtitleCue? = cues.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs }
    fun formatTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        return "%02d:%02d:%02d,%03d".format(safe / 3_600_000, (safe % 3_600_000) / 60_000, (safe % 60_000) / 1_000, safe % 1_000)
    }
}
