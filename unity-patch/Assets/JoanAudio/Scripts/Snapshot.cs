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

                var rt = RenderTexture.GetTemporary(Width, Height, 24);
                var prevTarget = cam.targetTexture;
                cam.targetTexture = rt;
                cam.Render();
                cam.targetTexture = prevTarget;

                var prevActive = RenderTexture.active;
                RenderTexture.active = rt;
                var tex = new Texture2D(Width, Height, TextureFormat.RGB24, false);
                tex.ReadPixels(new Rect(0, 0, Width, Height), 0, 0);
                tex.Apply(false);
                RenderTexture.active = prevActive;
                RenderTexture.ReleaseTemporary(rt);

                byte[] jpg = tex.EncodeToJPG(JpgQuality);
                Object.Destroy(tex);

                string master = MasterClock.MasterIp;
                if (string.IsNullOrEmpty(master))
                {
                    DebugReporter.Hud("snapshot: no master yet");
                    yield break;
                }

                string url = $"http://{master}:{ServerPort}/debug/snapshot" +
                             $"?id={UnityWebRequest.EscapeURL(DebugReporter.DeviceId ?? "device")}";
                using (var req = new UnityWebRequest(url, "POST"))
                {
                    req.uploadHandler = new UploadHandlerRaw(jpg) { contentType = "image/jpeg" };
                    req.downloadHandler = new DownloadHandlerBuffer();
                    req.timeout = 10;
                    yield return req.SendWebRequest();

                    if (req.result == UnityWebRequest.Result.Success)
                    {
                        Debug.Log($"[JoanAudio] snapshot sent ({jpg.Length / 1024} KB)");
                        DebugReporter.Hud("snapshot sent 📸");
                    }
                    else
                    {
                        Debug.LogWarning($"[JoanAudio] snapshot upload failed: {req.error}");
                    }
                }
            }
            finally { busy = false; }
        }
    }
}
