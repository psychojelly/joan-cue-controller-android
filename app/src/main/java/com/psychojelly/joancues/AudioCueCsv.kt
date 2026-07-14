package com.psychojelly.joancues

/**
 * Kotlin port of the Unity project's JoanAudio/AudioCueConfig.cs.
 * Parses audio_cues.csv: optional metadata rows (BaseUrl / CsvVersion), a
 * header row, then rows of CueId,Notes,StemFileName,Version,Action,Volume,Speed.
 *
 * Semantics preserved:
 *  - Stem key = basename without extension (loader probes .wav/.mp3/.ogg)
 *  - Volume column is 0-100, stored as 0-1
 *  - Action aliases (one shot / play-through / kill all / …)
 *  - Group keys: "B_VQ201" and "B_SQ201" both collapse to "B_201" so one
 *    cue id can fire every type sharing that prefix+number
 */
enum class StemAction { HOLD, LOOP, ONESHOT, STOP, STOPALL }

data class StemCueEntry(
    val fileName: String,
    val version: Int,
    val action: StemAction,
    val volume: Float?,   // 0..1; null = 1.0 if just-started, else no change
    val speed: Float      // seconds for the ramp / stop fade
)

data class Cue(val cueId: String, var notes: String, val stems: MutableList<StemCueEntry> = mutableListOf())

class AudioCueConfig {
    var baseUrl: String = ""
    var csvVersion: Int = 0
    val cues = mutableListOf<Cue>()

    fun getCuesInGroup(idOrGroup: String): List<Cue> {
        if (idOrGroup.isEmpty()) return emptyList()
        return cues.filter { it.cueId == idOrGroup || groupKey(it.cueId) == idOrGroup }
    }

    /** Unique stem basenames + max referenced version, for preloading. */
    fun allUniqueStems(): Map<String, Int> {
        val seen = mutableMapOf<String, Int>()
        for (cue in cues) for (s in cue.stems)
            if (s.fileName.isNotEmpty() && (seen[s.fileName] ?: -1) < s.version) seen[s.fileName] = s.version
        return seen
    }

    companion object {
        private val GROUP_KEY_RX = Regex("^([A-Za-z]+)_[A-Za-z]+(\\d+(?:\\.\\d+)?)$")

        fun groupKey(cueId: String): String {
            val m = GROUP_KEY_RX.find(cueId) ?: return cueId
            return "${m.groupValues[1]}_${m.groupValues[2]}"
        }

        fun parse(csvText: String): AudioCueConfig {
            val cfg = AudioCueConfig()
            if (csvText.isEmpty()) return cfg
            val lines = splitCsvLines(csvText)
            var i = 0

            // Optional metadata rows
            while (i < lines.size) {
                val raw = lines[i].trim()
                if (raw.isEmpty()) { i++; continue }
                val fields = parseCsvRow(raw)
                if (fields.isEmpty()) { i++; continue }
                when (fields[0].trim().lowercase()) {
                    "baseurl" -> if (fields.size > 1) { cfg.baseUrl = fields[1].trim(); i++; continue }
                    "csvversion" -> if (fields.size > 1) { cfg.csvVersion = fields[1].trim().toIntOrNull() ?: 0; i++; continue }
                }
                break
            }
            if (i >= lines.size) return cfg
            i++  // header row

            while (i < lines.size) {
                val line = lines[i]; i++
                if (line.isBlank()) continue
                val row = parseCsvRow(line)
                if (row.size < 5) continue

                val cueId = row[0].trim()
                if (cueId.isEmpty()) continue
                val notes = row[1]
                var stemName = row[2].trim()
                val dot = stemName.lastIndexOf('.')
                if (dot > 0) stemName = stemName.substring(0, dot)
                val version = row[3].trim().toIntOrNull() ?: 0
                val action = parseAction(row.getOrNull(4) ?: "")
                var vol = row.getOrNull(5)?.trim()?.toFloatOrNull()
                if (vol != null) vol = (vol / 100f).coerceIn(0f, 1f)
                val speed = row.getOrNull(6)?.trim()?.toFloatOrNull() ?: 0f

                var cue = cfg.cues.find { it.cueId == cueId }
                if (cue == null) { cue = Cue(cueId, notes); cfg.cues.add(cue) }
                else if (cue.notes.isEmpty() && notes.isNotEmpty()) cue.notes = notes

                if (stemName.isNotEmpty() || action == StemAction.STOPALL)
                    cue.stems.add(StemCueEntry(stemName, version, action, vol, speed))
            }
            return cfg
        }

        private fun parseAction(s: String): StemAction = when (s.trim().lowercase()) {
            "loop" -> StemAction.LOOP
            "oneshot", "one-shot", "one shot", "play-through", "playthrough", "play through" -> StemAction.ONESHOT
            "stop" -> StemAction.STOP
            "stopall", "stop all", "stop-all", "all stop", "killall", "kill all" -> StemAction.STOPALL
            else -> StemAction.HOLD
        }

        /** Line split that respects quoted fields containing newlines. */
        private fun splitCsvLines(text: String): List<String> {
            val list = mutableListOf<String>(); val sb = StringBuilder(); var inQuotes = false; var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '"') { inQuotes = !inQuotes; sb.append(c); i++; continue }
                if (!inQuotes && (c == '\n' || c == '\r')) {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    list.add(sb.toString()); sb.setLength(0); i++; continue
                }
                sb.append(c); i++
            }
            if (sb.isNotEmpty()) list.add(sb.toString())
            return list
        }

        private fun parseCsvRow(line: String): List<String> {
            val fields = mutableListOf<String>(); val sb = StringBuilder(); var inQuotes = false; var i = 0
            while (i < line.length) {
                val c = line[i]
                if (inQuotes) {
                    if (c == '"') {
                        if (i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i++ } else inQuotes = false
                    } else sb.append(c)
                } else when (c) {
                    '"' -> inQuotes = true
                    ',' -> { fields.add(sb.toString()); sb.setLength(0) }
                    else -> sb.append(c)
                }
                i++
            }
            fields.add(sb.toString())
            return fields
        }
    }
}
