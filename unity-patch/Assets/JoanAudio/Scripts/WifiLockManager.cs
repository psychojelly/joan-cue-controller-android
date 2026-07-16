using UnityEngine;

namespace JoanAudio
{
    /// <summary>
    /// Holds an Android Wi-Fi lock in low-latency mode so the device's Wi-Fi
    /// radio stays at full power instead of dropping into power-save between
    /// packets. On XREAL/Android that power-save cycle is the usual cause of
    /// the periodic ~200 ms transit spikes seen on cue delivery (roughly every
    /// 30-40 s) — the same lock the cue-controller tablet app already holds.
    ///
    /// Controlled from the operator over OSC (/debug/wifilock 0|1) so it can be
    /// toggled during tech to A/B its effect on the delay graph. An inspector
    /// default lets it come up locked on app start for the show.
    ///
    /// No-ops cleanly in the Editor and on non-Android platforms.
    /// </summary>
    public static class WifiLockManager
    {
        public static bool Locked { get; private set; }

#if UNITY_ANDROID && !UNITY_EDITOR
        static AndroidJavaObject wifiLock;
        // WifiManager.WIFI_MODE_FULL_LOW_LATENCY (API 29+). Falls back to
        // WIFI_MODE_FULL_HIGH_PERF on older devices.
        const int WIFI_MODE_FULL_LOW_LATENCY = 4;
        const int WIFI_MODE_FULL_HIGH_PERF = 3;
#endif

        /// <summary>Acquire (on=true) or release (on=false) the Wi-Fi lock.</summary>
        public static void SetLocked(bool on)
        {
            if (on == Locked) return;
#if UNITY_ANDROID && !UNITY_EDITOR
            try
            {
                if (on)
                {
                    if (wifiLock == null) wifiLock = CreateLock();
                    if (wifiLock != null && !wifiLock.Call<bool>("isHeld"))
                        wifiLock.Call("acquire");
                }
                else if (wifiLock != null && wifiLock.Call<bool>("isHeld"))
                {
                    wifiLock.Call("release");
                }
                Locked = on;
                Debug.Log($"[JoanAudio] Wi-Fi low-latency lock {(on ? "ACQUIRED" : "released")}");
            }
            catch (System.Exception e)
            {
                Debug.LogWarning($"[JoanAudio] Wi-Fi lock {(on ? "acquire" : "release")} failed: {e.Message}");
            }
#else
            Locked = on;
            Debug.Log($"[JoanAudio] Wi-Fi lock {(on ? "on" : "off")} (no-op off-device)");
#endif
        }

#if UNITY_ANDROID && !UNITY_EDITOR
        static AndroidJavaObject CreateLock()
        {
            using (var player = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
            using (var activity = player.GetStatic<AndroidJavaObject>("currentActivity"))
            {
                // getApplicationContext().getSystemService(Context.WIFI_SERVICE)
                using (var context = activity.Call<AndroidJavaObject>("getApplicationContext"))
                {
                    string WIFI_SERVICE;
                    using (var ctxClass = new AndroidJavaClass("android.content.Context"))
                        WIFI_SERVICE = ctxClass.GetStatic<string>("WIFI_SERVICE");
                    var wifiMgr = context.Call<AndroidJavaObject>("getSystemService", WIFI_SERVICE);
                    if (wifiMgr == null) return null;

                    int mode = GetSdkInt() >= 29 ? WIFI_MODE_FULL_LOW_LATENCY : WIFI_MODE_FULL_HIGH_PERF;
                    var lk = wifiMgr.Call<AndroidJavaObject>("createWifiLock", mode, "joanaudio:lowlatency");
                    lk.Call("setReferenceCounted", false);
                    return lk;
                }
            }
        }

        static int GetSdkInt()
        {
            using (var version = new AndroidJavaClass("android.os.Build$VERSION"))
                return version.GetStatic<int>("SDK_INT");
        }
#endif
    }
}
