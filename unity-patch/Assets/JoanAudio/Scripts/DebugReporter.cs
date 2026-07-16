using System;
using System.Collections.Generic;
using System.Net.Sockets;
using System.Text;
using UnityEngine;

namespace JoanAudio
{
    /// <summary>
    /// Debug observability (D2). When the controller broadcasts
    /// /debug/enable 1, this reports device activity back to the cue server's
    /// :9002 debug listener as OSC:
    ///
    ///   /debug/hello [id, kind]                          once, on enable
    ///   /debug/rx    [id, cueId, recvMaster:d, playAt:d] every accepted cue
    ///   /debug/hb    [id, masterTime:d, lastCue, stems:i, offsetMs:d]  1 Hz
    ///   /debug/log   [id, masterTime:d, level, msg]      warnings/errors +
    ///                                                    [JoanAudio] logs
    ///
    /// Timestamps are master-clock seconds (MasterClock.Now) so the controller
    /// can compare devices against its own send times. Reports go to
    /// MasterClock.MasterIp:9002 (the IP learned from the /clock/master
    /// announce) — no extra configuration.
    ///
    /// Heartbeat is a separate toggle (/debug/heartbeat 1) so the operator can
    /// run a lightweight "who's alive" roster without verbose log traffic.
    ///
    /// Everything is OFF by default: until the controller enables it there are
    /// no sockets, no log hooks, no per-frame work — show behavior is
    /// byte-identical to a build without debug mode turned on.
    ///
    /// Self-contained like MasterClock: no scene objects or inspector wiring;
    /// a hidden host GameObject is created on demand and play-mode teardown
    /// cleans everything up.
    /// </summary>
    public static class DebugReporter
    {
        const int DebugPort = 9002;
        const float HeartbeatSeconds = 1f;        // active rate (roster dot: green < 3 s)
        const float SafetyHeartbeatSeconds = 5f;  // always-on safety-net rate
        const int MaxLogsPerSecond = 10;          // forwarded-log rate limit
        const int MaxLogChars = 200;
        const int HudLines = 12;

        public static bool Enabled { get; private set; }
        public static bool HeartbeatOn { get; private set; }

        /// <summary>In-headset HUD mode — controlled independently of the
        /// reporting toggle via /debug/hud: 0 = off, 1 = message feed,
        /// 2 = margin line graph.</summary>
        public static int HudMode { get; private set; }

        /// <summary>Show safety net: once a master is known, send a low-rate
        /// (5 s) heartbeat even with all debug toggles off, so the operator
        /// roster always answers "is headset 3 alive?". One small packet per
        /// 5 s per device; set false (OscCueReceiver inspector) to return to
        /// strictly-zero traffic when debug is off.</summary>
        public static bool SafetyHeartbeat = true;

        /// <summary>Roster name for this device. Defaults to the device name;
        /// set before enabling if you want something friendlier.</summary>
        public static string DeviceId;

        static AudioSceneController controller;
        static UdpClient udp;
        static bool logHooked;
        static bool inSend;                  // reentrancy guard for the log hook
        static double logWindowStart;
        static int logWindowCount;

        // Recent activity for the in-headset HUD (D3). Main thread only.
        static readonly List<string> hudLog = new List<string>();
        internal static IReadOnlyList<string> HudLog => hudLog;
        internal static double LastHeartbeatAt { get; private set; }

        // Scheduling-margin history for the HUD's graph mode. Main thread only.
        const int MarginHistoryMax = 48;
        static readonly List<float> marginHistory = new List<float>();
        internal static IReadOnlyList<float> MarginHistory => marginHistory;

        /// <summary>Called by OscCueReceiver.Start() so heartbeats can report
        /// the last cue and active stem count.</summary>
        public static void Attach(AudioSceneController sceneController)
        {
            controller = sceneController;
            if (string.IsNullOrEmpty(DeviceId))
                DeviceId = SystemInfo.deviceName.Replace(' ', '-');
            Host.Ensure();   // host always runs so the safety heartbeat can tick
        }

        /// <summary>/debug/enable [0|1] — master switch for all reporting.</summary>
        public static void SetEnabled(bool on)
        {
            if (on == Enabled) return;
            Enabled = on;
            if (on)
            {
                Host.Ensure();
                HookLogs(true);
                string kind = Application.isEditor ? "editor" : "headset";
                Send("/debug/hello", DeviceId, kind);
                Hud($"debug ON ({kind})");
                Debug.Log($"[JoanAudio] DebugReporter: enabled (id {DeviceId}, master {MasterClock.MasterIp ?? "unknown"})");
            }
            else
            {
                HookLogs(false);
                Hud("debug OFF");
                Debug.Log("[JoanAudio] DebugReporter: disabled");
            }
        }

        /// <summary>/debug/heartbeat [0|1] — independent 1 Hz roster beacon.</summary>
        public static void SetHeartbeat(bool on)
        {
            if (on == HeartbeatOn) return;
            HeartbeatOn = on;
            if (on) Host.Ensure();
            Debug.Log($"[JoanAudio] DebugReporter: heartbeat {(on ? "on" : "off")}");
        }

        /// <summary>/debug/hud [0|1|2] — off / feed / graph. Independent of the
        /// reporting toggle so a stagehand can see the glasses HUD without
        /// turning on network debug traffic (and vice versa).</summary>
        public static void SetHudMode(int mode)
        {
            mode = Mathf.Clamp(mode, 0, 2);
            if (mode == HudMode) return;
            HudMode = mode;
            Host.Ensure();
            Debug.Log($"[JoanAudio] DebugReporter: HUD {(mode == 0 ? "off" : mode == 1 ? "feed" : "graph")}");
        }

        /// <summary>Cue received (called by OscCueReceiver for every accepted
        /// cue). playAt = 0 means the immediate, unscheduled path. The HUD and
        /// margin history update regardless of the reporting toggle; only the
        /// network report is gated on Enabled.</summary>
        public static void ReportRx(string cueId, double recvMaster, double playAt)
        {
            float marginMs = playAt > 0 ? (float)((playAt - recvMaster) * 1000.0) : 0f;
            // Record for the HUD graph only when the margin is meaningful: the
            // clock must be synced (an unsynced "margin" is epoch garbage that
            // would wreck the graph's scale).
            if (playAt > 0 && MasterClock.IsSynced && Mathf.Abs(marginMs) < 60000f)
            {
                marginHistory.Add(marginMs);
                while (marginHistory.Count > MarginHistoryMax) marginHistory.RemoveAt(0);
            }
            Hud(playAt > 0 ? $"rx {cueId}  margin {marginMs:F0}ms" : $"rx {cueId}  (immediate)");

            if (!Enabled) return;
            Send("/debug/rx", DeviceId, cueId, recvMaster, playAt);
        }

        // ---- heartbeat (driven by the host's Update, main thread) ------------

        internal static void HeartbeatTick()
        {
            // Active mode: 1 Hz. Safety net: 5 s once a master is known, even
            // with every toggle off — the roster keeps answering during a show.
            float interval;
            if (HeartbeatOn) interval = HeartbeatSeconds;
            else if (SafetyHeartbeat && !string.IsNullOrEmpty(MasterClock.MasterIp)) interval = SafetyHeartbeatSeconds;
            else return;
            if (MasterClock.LocalNow - LastHeartbeatAt < interval) return;
            LastHeartbeatAt = MasterClock.LocalNow;
            string lastCue = controller != null && controller.LastCueId != null ? controller.LastCueId : "";
            int stems = controller != null ? controller.ActivePlayers.Count : 0;
            // offsetMs: raw applied offset, -1 when unsynced — same convention as
            // the performer tablets (PerformerService.kt) so the roster reads
            // uniformly. Note the raw value is epoch-relative and can be huge;
            // it's "synced + stable?" information, not a latency number.
            Send("/debug/hb", DeviceId, MasterClock.Now, lastCue, stems,
                 MasterClock.IsSynced ? MasterClock.OffsetMs : -1.0);
        }

        // ---- Unity log forwarding --------------------------------------------

        static void HookLogs(bool on)
        {
            if (on == logHooked) return;
            logHooked = on;
            if (on) Application.logMessageReceived += OnUnityLog;
            else Application.logMessageReceived -= OnUnityLog;
        }

        static void OnUnityLog(string message, string stackTrace, LogType type)
        {
            if (inSend) return;                                   // our own noise
            bool interesting = type != LogType.Log || message.StartsWith("[JoanAudio]");
            if (!interesting) return;

            // Simple 1-second rate window so a log storm can't flood the wifi.
            double now = MasterClock.LocalNow;
            if (now - logWindowStart >= 1.0) { logWindowStart = now; logWindowCount = 0; }
            if (++logWindowCount > MaxLogsPerSecond) return;

            string level = type == LogType.Warning ? "warn"
                         : type == LogType.Log ? "info" : "error";
            string msg = message.Length > MaxLogChars ? message.Substring(0, MaxLogChars) : message;
            Send("/debug/log", DeviceId, MasterClock.Now, level, msg);
            if (type != LogType.Log) Hud($"{level}: {msg}");
        }

        // ---- transport --------------------------------------------------------

        static void Send(string address, params object[] args)
        {
            string ip = MasterClock.MasterIp;
            if (string.IsNullOrEmpty(ip)) return;                 // no master yet
            try
            {
                inSend = true;
                if (udp == null) udp = new UdpClient();
                byte[] packet = Encode(address, args);
                udp.Send(packet, packet.Length, ip, DebugPort);
            }
            catch { /* debug must never break the show */ }
            finally { inSend = false; }
        }

        internal static void Stop()
        {
            HookLogs(false);
            try { udp?.Close(); } catch { }
            udp = null;
            Enabled = false;
            HeartbeatOn = false;
            HudMode = 0;
        }

        // ---- minimal OSC encoder (strings, int32, float64) --------------------
        // Kept local for the same reason as MasterClock's: extOSC's transmitter
        // is scene-bound, and this must stay dependency-free.

        static byte[] Encode(string address, object[] args)
        {
            var tags = new StringBuilder(",");
            var body = new List<byte[]>();
            foreach (var a in args)
            {
                switch (a)
                {
                    case string s: tags.Append('s'); body.Add(PadOsc(s)); break;
                    case int i: tags.Append('i'); body.Add(BigEndian(BitConverter.GetBytes(i))); break;
                    case double d: tags.Append('d'); body.Add(BigEndian(BitConverter.GetBytes(d))); break;
                    case float f: tags.Append('f'); body.Add(BigEndian(BitConverter.GetBytes(f))); break;
                    case bool b: tags.Append('i'); body.Add(BigEndian(BitConverter.GetBytes(b ? 1 : 0))); break;
                    default: tags.Append('s'); body.Add(PadOsc(a?.ToString() ?? "")); break;
                }
            }

            byte[] addr = PadOsc(address);
            byte[] tagBytes = PadOsc(tags.ToString());
            int len = addr.Length + tagBytes.Length;
            foreach (var b in body) len += b.Length;

            var packet = new byte[len];
            int pos = 0;
            Buffer.BlockCopy(addr, 0, packet, pos, addr.Length); pos += addr.Length;
            Buffer.BlockCopy(tagBytes, 0, packet, pos, tagBytes.Length); pos += tagBytes.Length;
            foreach (var b in body) { Buffer.BlockCopy(b, 0, packet, pos, b.Length); pos += b.Length; }
            return packet;
        }

        static byte[] PadOsc(string s)
        {
            byte[] raw = Encoding.UTF8.GetBytes(s);
            int padded = (raw.Length / 4 + 1) * 4;
            var b = new byte[padded];
            Buffer.BlockCopy(raw, 0, b, 0, raw.Length);
            return b;
        }

        static byte[] BigEndian(byte[] b)
        {
            if (BitConverter.IsLittleEndian) Array.Reverse(b);
            return b;
        }

        // ---- HUD feed ----------------------------------------------------------

        internal static void Hud(string line)
        {
            hudLog.Add($"{MasterClock.Now,10:F3}  {line}");
            while (hudLog.Count > HudLines) hudLog.RemoveAt(0);
        }

        // ---- hidden host: heartbeat driver + HUD + teardown --------------------

        class Host : MonoBehaviour
        {
            static Host instance;
            public static void Ensure()
            {
                if (instance != null) return;
                var go = new GameObject("~JoanDebug") { hideFlags = HideFlags.HideAndDontSave };
                DontDestroyOnLoad(go);
                instance = go.AddComponent<Host>();
                go.AddComponent<DebugHud>();
            }
            void Update() { DebugReporter.HeartbeatTick(); }
            void OnDestroy() { DebugReporter.Stop(); instance = null; }
        }
    }
}
