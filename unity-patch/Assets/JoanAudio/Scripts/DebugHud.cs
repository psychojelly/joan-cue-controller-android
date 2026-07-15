using UnityEngine;

namespace JoanAudio
{
    /// <summary>
    /// In-headset debug HUD (D3). Lives on DebugReporter's hidden host and
    /// draws only while debug mode is enabled (/debug/enable 1), so during a
    /// show it doesn't exist. Shows what an operator standing next to a
    /// performer needs during tech:
    ///
    ///   - device id + master IP + clock state (offset ms)
    ///   - last cue + active stem count + heartbeat state
    ///   - the recent debug feed (cue rx with scheduling margin, warnings)
    ///
    /// Immediate-mode GUI on purpose: no Canvas, no TextMeshPro, no scene or
    /// prefab changes — the whole debug feature stays a scripts-only patch.
    /// </summary>
    public class DebugHud : MonoBehaviour
    {
        const float Pad = 10f;

        static GUIStyle text;
        static GUIStyle header;
        static Texture2D backdrop;

        void OnGUI()
        {
            if (!DebugReporter.Enabled) return;

            if (backdrop == null)
            {
                backdrop = new Texture2D(1, 1);
                backdrop.SetPixel(0, 0, new Color(0f, 0f, 0f, 0.72f));
                backdrop.Apply();

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

            bool synced = MasterClock.IsSynced;
            string master = MasterClock.MasterIp ?? "—";
            double age = MasterClock.LocalNow - DebugReporter.LastHeartbeatAt;
            string hb = !DebugReporter.HeartbeatOn ? "off"
                      : age < 2.0 ? "on" : $"stalled {age:F0}s";

            var lines = DebugReporter.HudLog;
            float lineH = text.lineHeight + 2f;
            float w = Mathf.Min(Screen.width - 2 * Pad, Screen.width * 0.55f);
            float h = (lines.Count + 3) * lineH + 2 * Pad;

            GUI.DrawTexture(new Rect(Pad, Pad, w, h), backdrop);
            float x = Pad * 2, y = Pad * 1.5f;

            GUI.Label(new Rect(x, y, w, lineH),
                $"🐞 {DebugReporter.DeviceId}   master {master}", header);
            y += lineH;
            GUI.Label(new Rect(x, y, w, lineH),
                synced ? $"clock synced  offset {MasterClock.OffsetMs:F1} ms   hb {hb}"
                       : $"clock NOT SYNCED   hb {hb}", synced ? text : header);
            y += lineH * 1.3f;

            for (int i = lines.Count - 1; i >= 0; i--)   // newest on top
            {
                GUI.Label(new Rect(x, y, w, lineH), lines[i], text);
                y += lineH;
            }
        }
    }
}
