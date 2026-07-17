using UnityEngine;
using UnityEngine.UI;

namespace JoanAudio
{
    /// <summary>
    /// World-space in-glasses debug HUD. The original DebugHud draws with
    /// OnGUI onto the flat framebuffer, which the XREAL stereo compositor
    /// never displays — so on-device it was invisible in the glasses. This
    /// version builds a small world-space Canvas parented to the XR camera
    /// (head-locked, ~1.4 m ahead, below eye line) so it composites into
    /// the stereo view like any scene object.
    ///
    /// Same control and data as DebugHud: /debug/hud 0 off · 1 feed ·
    /// 2 margin graph, reading DebugReporter.HudLog / MarginHistory.
    /// Runs only on device; in the editor the OnGUI HUD keeps the job.
    /// Everything is created from code — no scene, prefab, or font assets.
    /// </summary>
    public class DebugHudWorld : MonoBehaviour
    {
        const float Distance = 1.4f;      // metres ahead of the camera
        const float Drop = 0.32f;         // metres below camera centre
        const float CanvasWidth = 0.62f;  // metres
        const int PxW = 640, PxH = 340;   // canvas pixels (scaled to metres)
        const float GraphMaxFloorMs = 100f;
        const int TexW = 512, TexH = 160; // graph texture

        GameObject root;
        Text headerText, bodyText, footerText;
        RawImage graphImage;
        Texture2D graphTex;
        Color32[] clearBuf;
        int builtForMode = -1;
        float nextRefresh;

        void Update()
        {
            if (Application.isEditor) return;                 // OnGUI HUD covers the editor
            int mode = DebugReporter.HudMode;

            if (mode == 0)
            {
                if (root != null) { Destroy(root); root = null; builtForMode = -1; }
                return;
            }

            var cam = Camera.main;
            if (cam == null) return;                          // XR rig not up yet

            if (root == null || builtForMode != mode || root.transform.parent != cam.transform)
                Build(cam, mode);

            if (Time.unscaledTime >= nextRefresh)
            {
                nextRefresh = Time.unscaledTime + 0.33f;      // ~3 Hz is plenty
                Refresh(mode);
            }
        }

        void OnDisable()
        {
            if (root != null) { Destroy(root); root = null; builtForMode = -1; }
        }

        // ── construction ────────────────────────────────────────────────

        void Build(Camera cam, int mode)
        {
            if (root != null) Destroy(root);
            builtForMode = mode;

            root = new GameObject("JoanDebugHudWorld");
            root.transform.SetParent(cam.transform, false);
            root.transform.localPosition = new Vector3(0f, -Drop, Distance);
            root.transform.localRotation = Quaternion.identity;

            var canvas = root.AddComponent<Canvas>();
            canvas.renderMode = RenderMode.WorldSpace;
            var rt = canvas.GetComponent<RectTransform>();
            rt.sizeDelta = new Vector2(PxW, PxH);
            root.transform.localScale = Vector3.one * (CanvasWidth / PxW);

            // translucent backdrop
            var bg = MakeRect("bg", rt, Vector2.zero, Vector2.one, Vector2.zero, Vector2.zero);
            bg.gameObject.AddComponent<Image>().color = new Color(0f, 0f, 0f, 0.72f);

            var font = Resources.GetBuiltinResource<Font>("LegacyRuntime.ttf");
            headerText = MakeText("header", rt, font, 26, FontStyle.Bold,
                new Color(0.55f, 1f, 0.65f), TextAnchor.UpperLeft,
                new Vector2(0f, 1f), new Vector2(1f, 1f), new Vector2(14f, -64f), new Vector2(-14f, -6f));

            if (mode == 1)
            {
                bodyText = MakeText("feed", rt, font, 20, FontStyle.Normal,
                    new Color(0.85f, 0.92f, 1f), TextAnchor.UpperLeft,
                    new Vector2(0f, 0f), new Vector2(1f, 1f), new Vector2(14f, 10f), new Vector2(-14f, -66f));
            }
            else
            {
                var g = MakeRect("graph", rt, new Vector2(0f, 0f), new Vector2(1f, 1f),
                    new Vector2(14f, 44f), new Vector2(-64f, -70f));
                graphImage = g.gameObject.AddComponent<RawImage>();
                if (graphTex == null)
                {
                    graphTex = new Texture2D(TexW, TexH, TextureFormat.RGBA32, false);
                    graphTex.filterMode = FilterMode.Bilinear;
                    clearBuf = new Color32[TexW * TexH];
                    var c0 = new Color32(0, 0, 0, 0);
                    for (int i = 0; i < clearBuf.Length; i++) clearBuf[i] = c0;
                }
                graphImage.texture = graphTex;

                footerText = MakeText("footer", rt, font, 18, FontStyle.Normal,
                    new Color(0.85f, 0.92f, 1f), TextAnchor.LowerLeft,
                    new Vector2(0f, 0f), new Vector2(1f, 0f), new Vector2(14f, 8f), new Vector2(-14f, 40f));
            }
            Refresh(mode);
        }

        static RectTransform MakeRect(string name, RectTransform parent,
            Vector2 anchorMin, Vector2 anchorMax, Vector2 offsetMin, Vector2 offsetMax)
        {
            var go = new GameObject(name);
            var rt = go.AddComponent<RectTransform>();
            rt.SetParent(parent, false);
            rt.anchorMin = anchorMin; rt.anchorMax = anchorMax;
            rt.offsetMin = offsetMin; rt.offsetMax = offsetMax;
            return rt;
        }

        static Text MakeText(string name, RectTransform parent, Font font, int size,
            FontStyle style, Color color, TextAnchor anchor,
            Vector2 anchorMin, Vector2 anchorMax, Vector2 offsetMin, Vector2 offsetMax)
        {
            var rt = MakeRect(name, parent, anchorMin, anchorMax, offsetMin, offsetMax);
            var t = rt.gameObject.AddComponent<Text>();
            t.font = font; t.fontSize = size; t.fontStyle = style;
            t.color = color; t.alignment = anchor;
            t.horizontalOverflow = HorizontalWrapMode.Overflow;
            t.verticalOverflow = VerticalWrapMode.Truncate;
            return t;
        }

        // ── content ─────────────────────────────────────────────────────

        void Refresh(int mode)
        {
            bool synced = MasterClock.IsSynced;
            string master = MasterClock.MasterIp ?? "—";
            double age = MasterClock.LocalNow - DebugReporter.LastHeartbeatAt;
            string hb = !DebugReporter.HeartbeatOn && !DebugReporter.SafetyHeartbeat ? "off"
                      : age < 7.0 ? "on" : $"stalled {age:F0}s";
            string wifi = WifiLockManager.Locked ? "wifi-lock" : "NO WIFI-LOCK";
            float batt = DebugReporter.BatteryPct;
            string battStr = batt < 0f ? "batt —"
                : $"batt {batt:F0}%{(DebugReporter.BatteryCharging ? "⚡" : "")}";

            headerText.text =
                $"{DebugReporter.DeviceId}   master {master}   " +
                $"{DebugReporter.Fps:F0}fps   {battStr}\n" +
                $"{(synced ? "clock synced" : "clock NOT SYNCED")}   hb {hb}   {wifi}";

            if (mode == 1) RefreshFeed();
            else RefreshGraph();
        }

        void RefreshFeed()
        {
            var lines = DebugReporter.HudLog;
            var sb = new System.Text.StringBuilder(1024);
            for (int i = lines.Count - 1; i >= 0; i--) sb.AppendLine(lines[i]);   // newest on top
            bodyText.text = sb.ToString();
        }

        void RefreshGraph()
        {
            var pts = DebugReporter.MarginHistory;
            graphTex.SetPixels32(clearBuf);

            if (pts.Count < 2)
            {
                footerText.text = "waiting for scheduled cues… (fire a test tone)";
                graphTex.Apply(false);
                return;
            }

            float max = GraphMaxFloorMs;
            for (int i = 0; i < pts.Count; i++) if (pts[i] > max) max = pts[i];

            var grid = new Color32(255, 255, 255, 46);
            for (int g = 0; g <= 2; g++)
            {
                int gy = Mathf.Clamp(Mathf.RoundToInt((TexH - 1) * g / 2f), 0, TexH - 1);
                for (int x = 0; x < TexW; x++) graphTex.SetPixel(x, gy, grid);
            }

            var col = new Color32(140, 255, 166, 255);
            int n = pts.Count;
            for (int i = 1; i < n; i++)
            {
                int x0 = (i - 1) * (TexW - 1) / (n - 1);
                int x1 = i * (TexW - 1) / (n - 1);
                int y0 = Mathf.RoundToInt((TexH - 1) * Mathf.Clamp01(pts[i - 1] / max));
                int y1 = Mathf.RoundToInt((TexH - 1) * Mathf.Clamp01(pts[i] / max));
                PlotLine(x0, y0, x1, y1, col);
            }
            graphTex.Apply(false);

            footerText.text =
                $"cue margins · last {n} scheduled cues · latest {pts[n - 1]:F0}ms · scale 0–{max:F0}ms";
        }

        /// <summary>Bresenham with 2px thickness into graphTex.</summary>
        void PlotLine(int x0, int y0, int x1, int y1, Color32 c)
        {
            int dx = Mathf.Abs(x1 - x0), sx = x0 < x1 ? 1 : -1;
            int dy = -Mathf.Abs(y1 - y0), sy = y0 < y1 ? 1 : -1;
            int err = dx + dy;
            while (true)
            {
                graphTex.SetPixel(x0, y0, c);
                if (y0 + 1 < TexH) graphTex.SetPixel(x0, y0 + 1, c);
                if (x0 == x1 && y0 == y1) break;
                int e2 = 2 * err;
                if (e2 >= dy) { err += dy; x0 += sx; }
                if (e2 <= dx) { err += dx; y0 += sy; }
            }
        }
    }
}
