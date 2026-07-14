package com.psychojelly.joancues

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Kotlin port of JoanAudio's AudioSceneController + AudioStemPlayer +
 * AudioStemLoader, on Android MediaPlayer.
 *
 * Behavior preserved:
 *  - Cue id or group key fires the merged stems of every matching cue
 *  - LOOP/ONESHOT: if the stem is already active, only adjust volume (no restart);
 *    else start from 0 and ramp to target over Speed (or set immediately)
 *  - ONESHOT auto-releases when playback completes
 *  - STOP fades one stem out over Speed; STOPALL fades everything
 *  - HOLD adjusts volume only if the stem is already active
 *  - Stems download from BaseUrl (probing .wav/.mp3/.ogg) and cache to disk
 */
class StemEngine(private val context: Context) {

    companion object {
        private const val TAG = "StemEngine"
        const val DEFAULT_CSV_URL = "https://raw.githubusercontent.com/dlobser/opera-audio/main/audio_cues.csv"
        const val DEFAULT_STEM_BASE = "https://raw.githubusercontent.com/dlobser/opera-audio/main/Stems"
        private val EXTENSIONS = listOf(".wav", ".mp3", ".ogg")
        private const val FADE_TICK_MS = 33L
    }

    var config: AudioCueConfig? = null; private set
    var lastCueId: String? = null; private set

    private val main = Handler(Looper.getMainLooper())
    private val cacheDir: File by lazy { File(context.cacheDir, "stems").apply { mkdirs() } }

    /** stem basename -> live player */
    private val active = LinkedHashMap<String, Player>()
    private val fades = mutableMapOf<String, Fade>()
    private var fadeLoopRunning = false

    // ---- public status for the UI ------------------------------------------

    @Volatile var status: String = "idle"
    @Volatile var preloadDone = 0
    @Volatile var preloadTotal = 0
    fun activeStems(): List<Pair<String, Float>> = synchronized(active) {
        active.map { it.key to it.value.volume }
    }

    // ---- load config + preload ----------------------------------------------

    /** Blocking — call from a background thread. */
    fun loadConfigAndPreload(csvUrl: String = DEFAULT_CSV_URL) {
        status = "downloading cue CSV…"
        val csvFile = File(cacheDir, "audio_cues.csv")
        val text = try {
            downloadText(csvUrl).also { csvFile.writeText(it) }
        } catch (e: Exception) {
            Log.w(TAG, "CSV download failed (${e.message}); trying cache")
            if (csvFile.exists()) csvFile.readText() else { status = "CSV unavailable: ${e.message}"; return }
        }
        val cfg = AudioCueConfig.parse(text)
        if (cfg.baseUrl.isEmpty()) cfg.baseUrl = DEFAULT_STEM_BASE
        config = cfg
        Log.i(TAG, "Parsed ${cfg.cues.size} cues; BaseUrl=${cfg.baseUrl}")

        val stems = cfg.allUniqueStems()
        preloadTotal = stems.size; preloadDone = 0
        status = "preloading 0/${stems.size} stems…"
        for ((name, version) in stems) {
            try {
                ensureStemFile(name, version)
            } catch (e: Exception) {
                Log.e(TAG, "Preload failed for $name: ${e.message}")
            }
            preloadDone++
            status = "preloading $preloadDone/${stems.size} stems…"
        }
        status = "ready — ${cfg.cues.size} cues, ${stems.size} stems cached"
    }

    // ---- cue triggering ------------------------------------------------------

    /** Thread-safe: marshals to the main thread (MediaPlayer likes one thread). */
    fun triggerCue(cueId: String) {
        main.post { triggerCueInternal(cueId) }
    }

    private fun triggerCueInternal(cueId: String) {
        val cfg = config ?: run { Log.w(TAG, "Config not loaded"); return }
        val cues = cfg.getCuesInGroup(cueId)
        if (cues.isEmpty()) { Log.w(TAG, "Cue $cueId not found"); status = "cue $cueId not found"; return }

        val merged = cues.flatMap { it.stems }
        lastCueId = cueId
        val notes = cues.firstOrNull { it.notes.isNotEmpty() }?.notes ?: ""
        status = "cue $cueId  $notes"
        Log.i(TAG, "Cue $cueId: $notes")

        for (entry in merged) {
            when (entry.action) {
                StemAction.HOLD -> {
                    if (entry.volume != null) synchronized(active) { active[entry.fileName] }
                        ?.let { fadeTo(entry.fileName, it, entry.volume, entry.speed) }
                }
                StemAction.STOP -> stopStem(entry.fileName, entry.speed)
                StemAction.STOPALL -> stopAll(entry.speed)
                StemAction.LOOP, StemAction.ONESHOT -> ensurePlaying(entry)
            }
        }
    }

    fun stopAll(fade: Float = 0f) {
        main.post {
            val names = synchronized(active) { active.keys.toList() }
            names.forEach { stopStem(it, fade) }
        }
    }

    // ---- transport (best-effort /audio/seek + /audio/jump) ------------------

    fun seekGlobal(seconds: Float) = main.post {
        synchronized(active) { active.values.toList() }.forEach { it.seekTo(seconds) }
    }
    fun jumpGlobal(delta: Float) = main.post {
        synchronized(active) { active.values.toList() }.forEach { it.seekTo(it.position() + delta) }
    }
    fun seekStem(stem: String, seconds: Float) = main.post {
        synchronized(active) { active[stem] }?.seekTo(seconds)
    }
    fun jumpStem(stem: String, delta: Float) = main.post {
        synchronized(active) { active[stem] }?.let { it.seekTo(it.position() + delta) }
    }
    fun setStemVolume(stem: String, vol: Float, speed: Float) = main.post {
        synchronized(active) { active[stem] }?.let { fadeTo(stem, it, vol.coerceIn(0f, 1f), speed) }
    }

    // ---- internals -----------------------------------------------------------

    private fun ensurePlaying(entry: StemCueEntry) {
        val existing = synchronized(active) { active[entry.fileName] }
        if (existing != null) {
            // Already playing — just adjust volume. Don't restart. (Parity with Unity.)
            if (entry.volume != null) fadeTo(entry.fileName, existing, entry.volume, entry.speed)
            return
        }
        // Load off the main thread, then start on it.
        Thread {
            val file = try { ensureStemFile(entry.fileName, entry.version) } catch (e: Exception) {
                Log.e(TAG, "Load failed ${entry.fileName}: ${e.message}"); return@Thread
            }
            main.post {
                if (synchronized(active) { active.containsKey(entry.fileName) }) return@post
                val loop = entry.action == StemAction.LOOP
                val p = Player(entry.fileName, file, loop) ?: return@post
                synchronized(active) { active[entry.fileName] = p }
                val target = entry.volume ?: 1f
                if (entry.speed > 0f) { p.setVolumeNow(0f); fadeTo(entry.fileName, p, target, entry.speed) }
                else p.setVolumeNow(target)
                p.start {
                    // one-shot completion -> release (loop players never complete)
                    synchronized(active) { if (active[entry.fileName] == it) active.remove(entry.fileName) }
                    it.release()
                }
            }
        }.start()
    }

    private fun stopStem(name: String, fade: Float) {
        val p = synchronized(active) { active.remove(name) } ?: return
        if (fade <= 0f) { p.release(); return }
        fades.remove(name)
        val steps = (fade * 1000 / FADE_TICK_MS).toInt().coerceAtLeast(1)
        val startVol = p.volume
        var step = 0
        lateinit var r: Runnable
        r = Runnable {
            step++
            val v = startVol * (1f - step.toFloat() / steps)
            if (step >= steps || v <= 0f) p.release()
            else { p.setVolumeNow(v); main.postDelayed(r, FADE_TICK_MS) }
        }
        main.post(r)
    }

    private data class Fade(var target: Float, var perTick: Float)

    private fun fadeTo(name: String, p: Player, target: Float, speed: Float) {
        if (speed <= 0f) { fades.remove(name); p.setVolumeNow(target); return }
        val ticks = (speed * 1000 / FADE_TICK_MS).coerceAtLeast(1f)
        fades[name] = Fade(target, (target - p.volume) / ticks)
        if (!fadeLoopRunning) { fadeLoopRunning = true; main.postDelayed(::fadeTick, FADE_TICK_MS) }
    }

    private fun fadeTick() {
        val done = mutableListOf<String>()
        for ((name, fade) in fades) {
            val p = synchronized(active) { active[name] } ?: run { done.add(name); null } ?: continue
            val next = p.volume + fade.perTick
            val arrived = (fade.perTick >= 0 && next >= fade.target) || (fade.perTick < 0 && next <= fade.target)
            p.setVolumeNow(if (arrived) fade.target else next)
            if (arrived) done.add(name)
        }
        done.forEach { fades.remove(it) }
        if (fades.isNotEmpty()) main.postDelayed(::fadeTick, FADE_TICK_MS) else fadeLoopRunning = false
    }

    /** Download (if not cached) and return the local file for a stem. Blocking. */
    private fun ensureStemFile(name: String, version: Int): File {
        for (ext in EXTENSIONS) {
            val f = File(cacheDir, "$name.v$version$ext")
            if (f.exists() && f.length() > 0) return f
        }
        val base = config?.baseUrl?.ifEmpty { DEFAULT_STEM_BASE } ?: DEFAULT_STEM_BASE
        var lastErr: Exception? = null
        for (ext in EXTENSIONS) {
            try {
                val bytes = downloadBytes("$base/$name$ext")
                val f = File(cacheDir, "$name.v$version$ext")
                f.writeBytes(bytes)
                return f
            } catch (e: Exception) { lastErr = e }
        }
        throw lastErr ?: RuntimeException("no extension worked for $name")
    }

    private fun downloadText(url: String): String = downloadBytes(url).toString(Charsets.UTF_8)

    private fun downloadBytes(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 60000
        try {
            if (conn.responseCode != 200) throw RuntimeException("HTTP ${conn.responseCode} for $url")
            return conn.inputStream.readBytes()
        } finally { conn.disconnect() }
    }

    fun release() {
        main.post {
            synchronized(active) { active.values.forEach { it.release() }; active.clear() }
            fades.clear()
        }
    }

    // ---- one stem = one MediaPlayer -----------------------------------------

    private class Player(val stemName: String, file: File, loop: Boolean) {
        private val mp = MediaPlayer()
        var volume = 1f; private set
        private var released = false

        init {
            mp.setDataSource(file.absolutePath)
            mp.isLooping = loop
            mp.prepare()
        }

        fun start(onComplete: (Player) -> Unit) {
            if (!mp.isLooping) mp.setOnCompletionListener { onComplete(this) }
            mp.start()
        }
        fun setVolumeNow(v: Float) {
            if (released) return
            volume = v.coerceIn(0f, 1f)
            try { mp.setVolume(volume, volume) } catch (_: Exception) {}
        }
        fun seekTo(seconds: Float) {
            if (released) return
            try { mp.seekTo((seconds.coerceAtLeast(0f) * 1000).toInt()) } catch (_: Exception) {}
        }
        fun position(): Float = if (released) 0f else try { mp.currentPosition / 1000f } catch (_: Exception) { 0f }
        fun release() {
            if (released) return
            released = true
            try { mp.stop() } catch (_: Exception) {}
            try { mp.release() } catch (_: Exception) {}
        }
    }
}
