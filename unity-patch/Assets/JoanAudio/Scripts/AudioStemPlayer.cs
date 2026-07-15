using System.Collections;
using UnityEngine;

namespace JoanAudio
{
    [RequireComponent(typeof(AudioSource))]
    public class AudioStemPlayer : MonoBehaviour
    {
        AudioSource source;
        Coroutine fadeRoutine;
        int fadeGen;

        public string StemName { get; private set; }
        public bool InUse { get; private set; }

        void Awake()
        {
            source = GetComponent<AudioSource>();
            source.playOnAwake = false;
            source.loop = false;
            source.volume = 0f;
        }

        public void Assign(string stemName, AudioClip clip, bool loop)
        {
            StemName = stemName;
            source.clip = clip;
            source.loop = loop;
            source.volume = 0f;
            InUse = true;
        }

        public void PlayFromStart()
        {
            if (source.clip == null) return;
            source.time = 0f;
            source.Play();
        }

        /// <summary>
        /// Sample-accurate scheduled start (audio sync Phase 1): begins playback
        /// at the given AudioSettings.dspTime on the audio thread, so a
        /// main-thread hitch can't delay a start that's already scheduled.
        /// </summary>
        public void PlayScheduled(double dspTime)
        {
            if (source.clip == null) return;
            source.time = 0f;
            source.PlayScheduled(dspTime);
        }

        public void StopAndRelease()
        {
            StopFade();
            source.Stop();
            source.clip = null;
            source.volume = 0f;
            StemName = null;
            InUse = false;
        }

        public void SetVolume(float target, float seconds)
        {
            target = Mathf.Clamp01(target);
            if (seconds <= 0f)
            {
                StopFade();
                source.volume = target;
                return;
            }
            StopFade();
            fadeGen++;
            int myGen = fadeGen;
            fadeRoutine = StartCoroutine(FadeRoutine(target, seconds, myGen));
        }

        public void SetVolumeImmediate(float v)
        {
            StopFade();
            source.volume = Mathf.Clamp01(v);
        }

        /// <summary>Fade volume to 0 over `seconds`, then release the player. seconds&lt;=0 = immediate stop.</summary>
        public void StopWithFade(float seconds)
        {
            if (seconds <= 0f) { StopAndRelease(); return; }
            StopFade();
            fadeGen++;
            int myGen = fadeGen;
            fadeRoutine = StartCoroutine(FadeAndReleaseRoutine(seconds, myGen));
        }

        IEnumerator FadeAndReleaseRoutine(float seconds, int myGen)
        {
            float start = source.volume;
            float elapsed = 0f;
            while (elapsed < seconds)
            {
                if (myGen != fadeGen) yield break;
                elapsed += UnityEngine.Time.deltaTime;
                float t = Mathf.Clamp01(elapsed / seconds);
                source.volume = Mathf.Lerp(start, 0f, t);
                yield return null;
            }
            if (myGen == fadeGen) StopAndRelease();
        }

        public float Volume => source != null ? source.volume : 0f;

        public float Time
        {
            get => source != null ? source.time : 0f;
            set
            {
                if (source == null || source.clip == null) return;
                float t = Mathf.Clamp(value, 0f, Mathf.Max(0f, source.clip.length - 0.01f));
                source.time = t;
            }
        }

        public float Length => (source != null && source.clip != null) ? source.clip.length : 0f;

        public bool IsPlaying => source != null && source.isPlaying;

        IEnumerator FadeRoutine(float target, float seconds, int myGen)
        {
            float start = source.volume;
            float elapsed = 0f;
            while (elapsed < seconds)
            {
                if (myGen != fadeGen) yield break;
                elapsed += UnityEngine.Time.deltaTime;
                float t = Mathf.Clamp01(elapsed / seconds);
                source.volume = Mathf.Lerp(start, target, t);
                yield return null;
            }
            if (myGen == fadeGen) source.volume = target;
        }

        void StopFade()
        {
            if (fadeRoutine != null) { StopCoroutine(fadeRoutine); fadeRoutine = null; }
            fadeGen++;
        }
    }
}
