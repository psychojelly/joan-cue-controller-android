using UnityEngine;

namespace JoanAudio
{
    /// <summary>
    /// Audible sync test (/audio/test): a procedurally generated triple beep
    /// that plays OUTSIDE the cue system — no cue CSV, no stems, no transport
    /// state touched. When the message carries a playAt master-clock timestamp
    /// (sync mode on the controller) the beeps start via PlayScheduled exactly
    /// like real cues, so firing it at every device at once turns your ears
    /// into the sync test: one clean "beep-beep-beep" means in sync, a flam
    /// means trouble.
    ///
    /// The clip is generated in code (three 60 ms 1760 Hz beeps, 150 ms apart)
    /// so the whole feature stays a scripts-only patch — no AudioClip asset,
    /// no scene changes. Respects the /audio/mute master volume.
    /// </summary>
    public static class TestTone
    {
        const float BeepHz = 1760f;      // A6 — cuts through show audio
        const float BeepSeconds = 0.06f;
        const float GapSeconds = 0.15f;
        const int Beeps = 3;
        const float Volume = 0.8f;

        static AudioSource source;

        /// <summary>Play the test beeps. playAt &gt; 0 = master-clock seconds to
        /// start at (falls back to "now" when late or unsynced); 0 = now.</summary>
        public static void Play(double playAt)
        {
            Ensure();

            double delay = 0;
            if (playAt > 0 && MasterClock.IsSynced)
                delay = playAt - MasterClock.Now;

            if (delay > 0)
            {
                double dspAt = AudioSettings.dspTime + delay;
                source.Stop();
                source.PlayScheduled(dspAt);
                Debug.Log($"[JoanAudio] Test tone scheduled in {delay * 1000.0:F0} ms");
            }
            else
            {
                source.Stop();
                source.Play();
                Debug.Log(playAt > 0
                    ? "[JoanAudio] Test tone (late/unsynced — playing now)"
                    : "[JoanAudio] Test tone (immediate)");
            }
        }

        static void Ensure()
        {
            if (source != null) return;
            var go = new GameObject("~JoanTestTone") { hideFlags = HideFlags.HideAndDontSave };
            Object.DontDestroyOnLoad(go);
            source = go.AddComponent<AudioSource>();
            source.clip = BuildClip();
            source.playOnAwake = false;
            source.loop = false;
            source.spatialBlend = 0f;     // 2D — same loudness on every device
            source.volume = Volume;
        }

        static AudioClip BuildClip()
        {
            int rate = AudioSettings.outputSampleRate;
            int beepSamples = (int)(BeepSeconds * rate);
            int gapSamples = (int)(GapSeconds * rate);
            int total = Beeps * beepSamples + (Beeps - 1) * gapSamples;
            var data = new float[total];

            int fade = rate / 200;        // 5 ms fade in/out — no clicks
            for (int b = 0; b < Beeps; b++)
            {
                int start = b * (beepSamples + gapSamples);
                for (int i = 0; i < beepSamples; i++)
                {
                    float env = 1f;
                    if (i < fade) env = i / (float)fade;
                    else if (i > beepSamples - fade) env = (beepSamples - i) / (float)fade;
                    data[start + i] = Mathf.Sin(2f * Mathf.PI * BeepHz * i / rate) * env;
                }
            }

            var clip = AudioClip.Create("JoanTestTone", total, 1, rate, false);
            clip.SetData(data, 0);
            return clip;
        }
    }
}
