package com.inkorigin.animationstudio

data class SubtitleCue(val index: Int, val startMs: Long, val endMs: Long, val text: String)

object SubtitleEngine {
    private val timestamp = Regex("""(\d{2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})[,.](\d{3})""")
    fun parse(source: String): List<SubtitleCue> {
        // Stream through lines instead of splitting a large word-level SRT into thousands of blocks.
        val normalized = source.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val cues = mutableListOf<SubtitleCue>()
        var start = -1L
        var end = -1L
        val cueText = StringBuilder()

        fun flushCue() {
            val value = cueText.toString().trim()
            if (start >= 0L && end > start && value.isNotBlank()) {
                cues += SubtitleCue(cues.size + 1, start, end, value)
            }
            start = -1L
            end = -1L
            cueText.setLength(0)
        }

        normalized.lineSequence().forEach { line ->
            val match = timestamp.find(line)
            if (match != null) {
                flushCue()
                val values = (1..8).map { match.groupValues[it].toLong() }
                start = toMs(values[0], values[1], values[2], values[3])
                end = toMs(values[4], values[5], values[6], values[7])
            } else if (line.isBlank()) {
                flushCue()
            } else if (start >= 0L) {
                if (cueText.isNotEmpty()) cueText.append('\n')
                cueText.append(line.trim())
            }
        }
        flushCue()
        return cues
    }
    private fun toMs(h: Long, m: Long, s: Long, ms: Long): Long = h * 3_600_000L + m * 60_000L + s * 1_000L + ms
    fun activeCue(cues: List<SubtitleCue>, positionMs: Long): SubtitleCue? = cues.lastOrNull { positionMs >= it.startMs && positionMs < it.endMs }
    fun formatTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0)
        return "%02d:%02d:%02d,%03d".format(safe / 3_600_000, (safe % 3_600_000) / 60_000, (safe % 60_000) / 1_000, safe % 1_000)
    }
}
