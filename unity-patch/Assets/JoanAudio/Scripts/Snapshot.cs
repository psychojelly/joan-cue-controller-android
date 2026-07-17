using System.Collections;
using UnityEngine;
using UnityEngine.Networking;

namespace JoanAudio
{
    /// <summary>
    /// "What is this headset actually showing?" — on /debug/snap the device
    /// renders its scene camera to a texture, JPEG-encodes it, and HTTP-POSTs
    /// it to the cue server (:8765/debug/snapshot?id=...), which announces it
    /// on the debug feed for the operator panel to display.
    ///
    /// HTTP because a ~200-500 KB image doesn't fit OSC/UDP. The capture is a
    /// manual Camera.Render into a RenderTexture: mono (one eye's viewpoint),
    /// includes everything the camera draws — scene, VFX, and the world-space
    /// HUD if it's on. It is the content of the view, not the exact stereo
    /// composite after XREAL reprojection.
    ///
    /// Cost: one extra scene render + ReadPixels + JPEG encode — a visible
    /// one-frame hitch on a loaded device. A tech tool; avoid mid-performance.
    /// </summary>
    public static class Snapshot
    {
        const int Width = 1280, Height = 720, JpgQuality = 75;
        const int ServerPort = 8765;
        static bool busy;

        public static IEnumerator CaptureAndSend()
        {
            if (busy) yield break;
            busy = true;
            try
            {
                yield return new WaitForEndOfFrame();

                var cam = Camera.main;
                if (cam == null)
                {
                    DebugReporter.Hud("snapshot: no camera");
                    yield break;
                }

                byte[] jpg = null;
                try
                {
                    var rt = RenderTexture.GetTemporary(Width, Height, 24);

                    // URP does not support manual Camera.Render() — the SRP way
                    // is a render request. Fall back to Camera.Render() only on
                    // the built-in pipeline (e.g. if the project ever changes).
                    var request = new UnityEngine.Rendering.RenderPipeline.StandardRequest();
                    if (UnityEngine.Rendering.RenderPipeline.SupportsRenderRequest(cam, request))
                    {
                        request.destination = rt;
                        UnityEngine.Rendering.RenderPipeline.SubmitRenderRequest(cam, request);
                    }
                    else
                    {
                        var prevTarget = cam.targetTexture;
                        cam.targetTexture = rt;
                        cam.Render();
                        cam.targetTexture = prevTarget;
                    }

                    var prevActive = RenderTexture.active;
                    RenderTexture.active = rt;
                    var tex = new Texture2D(Width, Height, TextureFormat.RGB24, false);
                    tex.ReadPixels(new Rect(0, 0, Width, Height), 0, 0);
                    tex.Apply(false);
                    RenderTexture.active = prevActive;
                    RenderTexture.ReleaseTemporary(rt);

                    jpg = tex.EncodeToJPG(JpgQuality);
                    Object.Destroy(tex);
                }
                catch (System.Exception e)
                {
                    Debug.LogWarning($"[JoanAudio] snapshot capture failed: {e.Message}");
                    DebugReporter.Hud("snapshot capture failed");
                    jpg = null;
                }
                if (jpg == null) yield break;

                string master = MasterClock.MasterIp;
                if (string.IsNullOrEmpty(master))
                {
                    DebugReporter.Hud("snapshot: no master yet");
                    yield break;
                }

                // Upload with System.Net on a worker thread — NOT UnityWebRequest,
                // which rejects plain http:// ("Insecure connection not allowed")
                // in non-development builds unless the project-wide player setting
                // is changed. The cue server is LAN http by design; .NET sockets
                // aren't subject to Unity's policy, and this stays scripts-only.
                string url = $"http://{master}:{ServerPort}/debug/snapshot" +
                             $"?id={UnityWebRequest.EscapeURL(DebugReporter.DeviceId ?? "device")}";
                bool done = false, ok = false;
                string error = null;
                var worker = new System.Threading.Thread(() =>
                {
                    try
                    {
                        var req = (System.Net.HttpWebRequest)System.Net.WebRequest.Create(url);
                        req.Method = "POST";
                        req.ContentType = "image/jpeg";
                        req.ContentLength = jpg.Length;
                        req.Timeout = 10000;
                        req.ReadWriteTimeout = 10000;
                        using (var s = req.GetRequestStream()) s.Write(jpg, 0, jpg.Length);
                        using (var resp = (System.Net.HttpWebResponse)req.GetResponse())
                            ok = (int)resp.StatusCode == 200;
                        if (!ok) error = "server refused";
                    }
                    catch (System.Exception e) { error = e.Message; }
                    finally { done = true; }
                }) { IsBackground = true };
                worker.Start();
                float deadline = Time.realtimeSinceStartup + 15f;
                while (!done && Time.realtimeSinceStartup < deadline) yield return null;

                if (ok)
                {
                    Debug.Log($"[JoanAudio] snapshot sent ({jpg.Length / 1024} KB)");
                    DebugReporter.Hud("snapshot sent 📸");
                }
                else
                {
                    Debug.LogWarning($"[JoanAudio] snapshot upload failed: {error ?? "timeout"}");
                    DebugReporter.Hud("snapshot upload FAILED");
                }
            }
            finally { busy = false; }
        }
    }
}
