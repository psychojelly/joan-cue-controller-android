using System;
using System.Collections;
using System.Collections.Generic;
using UnityEngine;

namespace JoanAudio
{
    /// <summary>
    /// Cue-driven audio controller. Each cue specifies a list of stem actions
    /// (Loop / OneShot / Stop / Hold) plus target volume + ramp speed.
    /// Stems persist across cues until explicitly Stopped (or until a non-looping
    /// OneShot finishes naturally). No automatic scene boundaries.
    /// </summary>
    public class AudioSceneController : MonoBehaviour
    {
        [Header("CSV")]
        [Tooltip("Optional: drop a TextAsset CSV here. If null, the Loader reads CsvFileName.")]
        public TextAsset CsvAsset;
        public string CsvFileName = "audio_cues.csv";

        [Header("Loader")]
        public AudioStemLoader Loader;

        [Header("Pool")]
        public int PlayerPoolSize = 32;

        [Header("Preload")]
        [Tooltip("If true, all stems referenced in the CSV are loaded on startup. Otherwise stems load on first cue reference.")]
        public bool PreloadAll = true;

        public AudioCueConfig Config { get; private set; }
        public string LastCueId { get; private set; } = null;

        public event Action OnConfigLoaded;
        public event Action<string> OnCueTriggered;
        public event Action<int> OnPreloadComplete;

        readonly List<AudioStemPlayer> pool = new List<AudioStemPlayer>();
        readonly Dictionary<string, AudioStemPlayer> activePlayers = new Dictionary<string, AudioStemPlayer>();
        readonly Dictionary<string, AudioClip> clipCache = new Dictionary<string, AudioClip>();
        Coroutine activeCueRoutine;

        public IReadOnlyDictionary<string, AudioStemPlayer> ActivePlayers => activePlayers;

        void Awake()
        {
            BuildPool();
        }

        IEnumerator Start()
        {
            yield return LoadConfigRoutine();
            if (PreloadAll && Config != null && Loader != null)
            {
                int loaded = 0;
                foreach (var kv in Config.AllUniqueStems())
                {
                    if (clipCache.ContainsKey(kv.Key)) continue;
                    var fileName = kv.Key;
                    var version = kv.Value;
                    yield return Loader.LoadStem(fileName, version,
                        clip => { clipCache[fileName] = clip; loaded++; },
                        err => Debug.LogError($"[JoanAudio] Preload error {fileName}: {err}"));
                }
                Debug.Log($"[JoanAudio] Preloaded {loaded} stems.");
                OnPreloadComplete?.Invoke(loaded);
            }
            else
            {
                OnPreloadComplete?.Invoke(0);
            }
        }

        void BuildPool()
        {
            for (int i = 0; i < PlayerPoolSize; i++)
            {
                var go = new GameObject($"StemPlayer_{i}");
                go.transform.SetParent(transform, false);
                go.AddComponent<AudioSource>();
                pool.Add(go.AddComponent<AudioStemPlayer>());
            }
        }

        IEnumerator LoadConfigRoutine()
        {
            if (CsvAsset != null)
            {
                Config = AudioCueConfig.Parse(CsvAsset.text);
                Debug.Log($"[JoanAudio] Parsed {Config.Cues.Count} cues from CsvAsset.");
                OnConfigLoaded?.Invoke();
                yield break;
            }

            if (Loader == null) { Debug.LogError("[JoanAudio] No CsvAsset and no Loader assigned."); yield break; }

            string text = null;
            yield return Loader.LoadCsv(CsvFileName, 0,
                t => text = t,
                err => Debug.LogError($"[JoanAudio] CSV load error: {err}"));

            if (string.IsNullOrEmpty(text)) { Debug.LogError("[JoanAudio] CSV empty."); yield break; }

            Config = AudioCueConfig.Parse(text);
            Debug.Log($"[JoanAudio] Parsed {Config.Cues.Count} cues from remote/local CSV.");
            OnConfigLoaded?.Invoke();
        }

        public void TriggerCue(string cueId)
        {
            if (Config == null) { Debug.LogWarning("[JoanAudio] Config not loaded."); return; }

            // cueId may be an exact cue id or a group key (prefix+number, e.g. "A_201").
            // Every member cue's stems are merged into one routine so they run together and
            // don't cancel each other (a group typically pairs one SQ audio cue with a VQ video cue).
            var cues = Config.GetCuesInGroup(cueId);
            if (cues.Count == 0) { Debug.LogWarning($"[JoanAudio] Cue {cueId} not found."); return; }

            var merged = new List<StemCueEntry>();
            string notes = null;
            foreach (var c in cues)
            {
                if (string.IsNullOrEmpty(notes) && !string.IsNullOrEmpty(c.Notes)) notes = c.Notes;
                merged.AddRange(c.Stems);
            }

            if (activeCueRoutine != null) StopCoroutine(activeCueRoutine);
            activeCueRoutine = StartCoroutine(ApplyCue(merged));
            LastCueId = cueId;
            OnCueTriggered?.Invoke(cueId);
            Debug.Log($"[JoanAudio] Cue {cueId}: {notes}");
        }

        /// <summary>
        /// Scheduled variant (audio sync Phase 1): start this cue's stems at the
        /// shared master-clock time `playAtMaster` so every device begins the
        /// same audio at the same instant. Falls back to TriggerCue when the
        /// clock isn't synced. If the message arrived late (stall / missed
        /// window), stems start immediately but seek in by the overshoot so the
        /// device lands mid-phrase in sync instead of permanently behind.
        /// </summary>
        public void TriggerCueScheduled(string cueId, double playAtMaster)
        {
            if (!MasterClock.IsSynced)
            {
                Debug.LogWarning($"[JoanAudio] Cue {cueId} carried a schedule but the clock isn't synced yet — playing now.");
                TriggerCue(cueId);
                return;
            }

            if (Config == null) { Debug.LogWarning("[JoanAudio] Config not loaded."); return; }
            var cues = Config.GetCuesInGroup(cueId);
            if (cues.Count == 0) { Debug.LogWarning($"[JoanAudio] Cue {cueId} not found."); return; }

            var merged = new List<StemCueEntry>();
            string notes = null;
            foreach (var c in cues)
            {
                if (string.IsNullOrEmpty(notes) && !string.IsNullOrEmpty(c.Notes)) notes = c.Notes;
                merged.AddRange(c.Stems);
            }

            double delay = playAtMaster - MasterClock.Now;
            double dspAt = AudioSettings.dspTime + delay;
            float lateSeek = delay < 0 ? (float)(-delay) : 0f;

            if (activeCueRoutine != null) StopCoroutine(activeCueRoutine);
            activeCueRoutine = StartCoroutine(ApplyCue(merged, dspAt, lateSeek));
            LastCueId = cueId;
            OnCueTriggered?.Invoke(cueId);
            Debug.Log(lateSeek > 0f
                ? $"[JoanAudio] Cue {cueId} (LATE by {lateSeek * 1000f:F0} ms — seeking in): {notes}"
                : $"[JoanAudio] Cue {cueId} scheduled in {delay * 1000.0:F0} ms: {notes}");
        }

        void FadeAndReleaseAll(float fade)
        {
            foreach (var kv in new List<KeyValuePair<string, AudioStemPlayer>>(activePlayers))
                kv.Value.StopWithFade(fade);
            activePlayers.Clear();
        }

        IEnumerator ApplyCue(List<StemCueEntry> entries, double? dspAt = null, float lateSeek = 0f)
        {
            foreach (var entry in entries)
            {
                switch (entry.Action)
                {
                    case StemAction.Hold:
                        // Adjust volume on an already-playing stem. If it's not active, do nothing.
                        if (entry.Volume.HasValue && activePlayers.TryGetValue(entry.FileName, out var holdP))
                            holdP.SetVolume(entry.Volume.Value, entry.Speed);
                        break;

                    case StemAction.Stop:
                        if (activePlayers.TryGetValue(entry.FileName, out var stopP))
                        {
                            activePlayers.Remove(entry.FileName);
                            stopP.StopWithFade(entry.Speed);
                        }
                        break;

                    case StemAction.StopAll:
                        FadeAndReleaseAll(entry.Speed);
                        break;

                    case StemAction.Loop:
                    case StemAction.OneShot:
                        yield return EnsurePlayingAndApply(entry, dspAt, lateSeek);
                        break;
                }
            }
        }

        IEnumerator EnsurePlayingAndApply(StemCueEntry entry, double? dspAt = null, float lateSeek = 0f)
        {
            bool loop = entry.Action == StemAction.Loop;

            if (activePlayers.TryGetValue(entry.FileName, out var existing))
            {
                // Already playing — just adjust volume. Don't restart.
                if (entry.Volume.HasValue) existing.SetVolume(entry.Volume.Value, entry.Speed);
                yield break;
            }

            // Need to start playing. Make sure clip is loaded.
            if (!clipCache.TryGetValue(entry.FileName, out var clip) || clip == null)
            {
                AudioClip loadedClip = null;
                yield return Loader.LoadStem(entry.FileName, entry.Version,
                    c => { loadedClip = c; clipCache[entry.FileName] = c; },
                    err => Debug.LogError($"[JoanAudio] Load failed {entry.FileName}: {err}"));
                clip = loadedClip;
                if (clip == null) yield break;
            }

            var player = AcquirePlayer();
            if (player == null) { Debug.LogError("[JoanAudio] Pool exhausted."); yield break; }
            player.Assign(entry.FileName, clip, loop);

            bool scheduledInFuture = dspAt.HasValue && lateSeek <= 0f && dspAt.Value > AudioSettings.dspTime;
            if (scheduledInFuture)
            {
                // Sample-accurate start on the audio thread — immune to main-thread hitches.
                player.PlayScheduled(dspAt.Value);
            }
            else
            {
                player.PlayFromStart();
                // Arrived late for its schedule: land mid-phrase in sync, not behind.
                if (lateSeek > 0f) player.Time = lateSeek;
            }
            activePlayers[entry.FileName] = player;

            float target = entry.Volume ?? 1f;
            if (entry.Speed > 0f)
            {
                player.SetVolumeImmediate(0f);
                if (scheduledInFuture)
                    StartCoroutine(RampAtDsp(player, dspAt.Value, target, entry.Speed));
                else
                    player.SetVolume(target, entry.Speed);
            }
            else
            {
                player.SetVolumeImmediate(target);
            }

            if (!loop) StartCoroutine(WatchOneShot(player, entry.FileName));
        }

        /// <summary>Hold volume at 0 until the scheduled dsp start, then run the cue's ramp.</summary>
        IEnumerator RampAtDsp(AudioStemPlayer player, double dspAt, float target, float seconds)
        {
            while (AudioSettings.dspTime < dspAt)
            {
                if (player == null || !player.InUse) yield break;
                yield return null;
            }
            if (player != null && player.InUse) player.SetVolume(target, seconds);
        }

        IEnumerator WatchOneShot(AudioStemPlayer player, string stemName)
        {
            // Wait one frame so AudioSource.isPlaying reflects Play().
            yield return null;
            while (player != null && player.InUse && player.StemName == stemName && player.IsPlaying)
                yield return null;
            if (player != null && player.StemName == stemName)
            {
                player.StopAndRelease();
                if (activePlayers.TryGetValue(stemName, out var current) && current == player)
                    activePlayers.Remove(stemName);
            }
        }

        AudioStemPlayer AcquirePlayer()
        {
            foreach (var p in pool) if (!p.InUse) return p;
            return null;
        }
    }
}
