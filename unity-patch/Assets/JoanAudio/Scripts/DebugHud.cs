using UnityEngine;

namespace JoanAudio
{
    /// <summary>
    /// In-headset debug HUD (D3). Lives on DebugReporter's hidden host and is
    /// controlled independently of the reporting toggle via /debug/hud:
    ///
    ///   0 — off (default; the HUD does not exist during a show)
    ///   1 — message feed: device id, master IP, clock state, heartbeat, and
    ///       the recent cue/log lines with master-clock timestamps
    ///   2 — line graph: recent scheduled-cue margins (ms) — the at-a-glance
    ///       "how much headroom does THIS headset have" view during tech
    ///
    /// Immediate-mode GUI on purpose: no Canvas, no TextMeshPro, no scene or
    /// prefab changes — the whole debug feature stays a scripts-only patch.
    /// </summary>
    public class DebugHud : MonoBehaviour
    {
        const float Pad = 10f;
        const float GraphMaxFloorMs = 100f;   // graph y-scale never below this

        static GUIStyle text;
        static GUIStyle header;
        static Texture2D backdrop;
        static Texture2D solid;               // 1x1 white — line segments + bars

        void OnGUI()
        {
            // On device the XR stereo compositor never shows the OnGUI layer —
            // DebugHudWorld (world-space canvas) covers the glasses; this OnGUI
            // HUD stays for the editor / flat screens.
            if (DebugReporter.HudMode == 0 || !Application.isEditor) return;
            EnsureStyles();

            bool synced = MasterClock.IsSynced;
            string master = MasterClock.MasterIp ?? "—";
            double age = MasterClock.LocalNow - DebugReporter.LastHeartbeatAt;
            string hb = !DebugReporter.HeartbeatOn && !DebugReporter.SafetyHeartbeat ? "off"
                      : age < 7.0 ? "on" : $"stalled {age:F0}s";

            float lineH = text.lineHeight + 2f;
            float w = Mathf.Min(Screen.width - 2 * Pad, Screen.width * 0.55f);
            int bodyLines = DebugReporter.HudMode == 1 ? DebugReporter.HudLog.Count : 0;
            float graphH = DebugReporter.HudMode == 2 ? 140f : 0f;
            float h = (bodyLines + 3) * lineH + graphH + 2 * Pad;

            GUI.DrawTexture(new Rect(Pad, Pad, w, h), backdrop);
            float x = Pad * 2, y = Pad * 1.5f;

            float batt = DebugReporter.BatteryPct;
            string battStr = batt < 0f ? "batt —"
                : $"batt {batt:F0}%{(DebugReporter.BatteryCharging ? "⚡" : "")}";
            GUI.Label(new Rect(x, y, w, lineH),
                $"🐞 {DebugReporter.DeviceId} ({DebugReporter.LocalIp})   master {master}   " +
                $"{DebugReporter.Fps:F0}fps   {battStr}", header);
            y += lineH;
            GUI.Label(new Rect(x, y, w, lineH),
                synced ? $"clock synced   hb {hb}" : $"clock NOT SYNCED   hb {hb}",
                synced ? text : header);
            y += lineH * 1.3f;

            if (DebugReporter.HudMode == 1) DrawFeed(x, y, w, lineH);
            else DrawGraph(x, y, w - Pad * 3, graphH, lineH);
        }

        void DrawFeed(float x, float y, float w, float lineH)
        {
            var lines = DebugReporter.HudLog;
            for (int i = lines.Count - 1; i >= 0; i--)   // newest on top
            {
                GUI.Label(new Rect(x, y, w, lineH), lines[i], text);
                y += lineH;
            }
        }

        /// <summary>Scheduled-cue margins as a connected line, newest at the
        /// right. Above 0 = the cue arrived with headroom; taller = safer.</summary>
        void DrawGraph(float x, float y, float w, float h, float lineH)
        {
            var pts = DebugReporter.MarginHistory;
            if (pts.Count < 2)
            {
                GUI.Label(new Rect(x, y + h / 2, w, lineH),
                    "waiting for scheduled cues… (fire a test tone)", text);
                return;
            }

            float max = GraphMaxFloorMs;
            for (int i = 0; i < pts.Count; i++) if (pts[i] > max) max = pts[i];

            // axis lines + labels at 0 / half / max
            var dim = new Color(1f, 1f, 1f, 0.18f);
            for (int g = 0; g <= 2; g++)
            {
                float gy = y + h - (h * g / 2f);
                DrawRect(new Rect(x, gy, w, 1f), dim);
                GUI.Label(new Rect(x + w + 4, gy - lineH / 2, 60, lineH),
                    $"{max * g / 2f:F0}ms", text);
            }

            // polyline: one thin rotated bar per segment
            var col = new Color(0.55f, 1f, 0.65f);
            int n = pts.Count;
            for (int i = 1; i < n; i++)
            {
                var a = new Vector2(x + w * (i - 1) / (n - 1), y + h - h * Mathf.Clamp01(pts[i - 1] / max));
                var b = new Vector2(x + w * i / (n - 1), y + h - h * Mathf.Clamp01(pts[i] / max));
                DrawLine(a, b, col, 2f);
            }

            GUI.Label(new Rect(x, y + h + 2f, w, lineH),
                $"cue margins — last {n} scheduled cues · latest {pts[n - 1]:F0}ms", text);
        }

        static void DrawLine(Vector2 a, Vector2 b, Color c, float width)
        {
            var d = b - a;
            float len = d.magnitude;
            if (len < 0.001f) return;
            float angle = Mathf.Atan2(d.y, d.x) * Mathf.Rad2Deg;
            var saved = GUI.matrix;
            GUIUtility.RotateAroundPivot(angle, a);
            DrawRect(new Rect(a.x, a.y - width / 2f, len, width), c);
            GUI.matrix = saved;
        }

        static void DrawRect(Rect r, Color c)
        {
            var saved = GUI.color;
            GUI.color = c;
            GUI.DrawTexture(r, solid);
            GUI.color = saved;
        }

        static void EnsureStyles()
        {
            if (backdrop != null) return;
            backdrop = new Texture2D(1, 1);
            backdrop.SetPixel(0, 0, new Color(0f, 0f, 0f, 0.72f));
            backdrop.Apply();
            solid = Texture2D.whiteTexture;

            int size = Mathf.Max(13, Screen.height / 48);
            text = new GUIStyle
            {
                fontSize = size,
                normal = { textColor = new Color(0.85f, 0.92f, 1f) },
                font = Font.CreateDynamicFontFromOSFont("Consolas", size)
            };
            header = new GUIStyle(text)
            {
                fontStyle = FontStyle.Bold,
                normal = { textColor = new Color(0.55f, 1f, 0.65f) }
            };
        }
    }
}
