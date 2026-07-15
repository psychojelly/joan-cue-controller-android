using System.Collections.Generic;
using UnityEngine;
using extOSC;

namespace JoanAudio
{
    public class OscCueReceiver : MonoBehaviour
    {
        [Header("OSC")]
        public OSCReceiver Receiver;

        [Header("Addresses")]
        public string CueAddress = "/audio/cue";
        public string SeekAddress = "/audio/seek";
        public string SeekStemAddress = "/audio/seek/stem";
        public string JumpAddress = "/audio/jump";
        public string JumpStemAddress = "/audio/jump/stem";
        public string VolumeAddress = "/audio/vol";

        [Header("Targets")]
        public AudioSceneController Controller;
        public AudioTransport Transport;

        [Header("Scheduled sync (audio sync Phases 0+1)")]
        [Tooltip("ON: /audio/cue messages that carry a playAt timestamp are started " +
                 "sample-accurately on the shared master clock (and duplicates of the " +
                 "sender's redundant 3x sends are ignored).\n" +
                 "OFF: rollback switch — timestamps are ignored and every cue plays " +
                 "immediately on arrival, exactly like the pre-sync behavior.")]
        public bool UseScheduledSync = true;
        public string ClockMasterAddress = "/clock/master";

        [Header("Debug observability (D2)")]
        [Tooltip("Controller broadcasts /debug/enable 1 to turn on verbose reporting " +
                 "back to the cue server (:9002) and the in-headset HUD; /debug/heartbeat 1 " +
                 "for the lightweight 1 Hz roster beacon. Both default OFF — zero overhead " +
                 "until an operator asks for them.")]
        public string DebugEnableAddress = "/debug/enable";
        public string DebugHeartbeatAddress = "/debug/heartbeat";

        // Dedupe for the sender's redundant 3x sends: (cueId|playAt), recent ~32.
        readonly Queue<string> dedupeOrder = new Queue<string>();
        readonly HashSet<string> dedupeSet = new HashSet<string>();

        void Start()
        {
            if (Receiver == null)
            {
                Debug.LogError("[JoanAudio] OscCueReceiver: no OSCReceiver assigned.");
                return;
            }
            Receiver.Bind(CueAddress, OnCue);
            Receiver.Bind(SeekAddress, OnSeek);
            Receiver.Bind(SeekStemAddress, OnSeekStem);
            Receiver.Bind(JumpAddress, OnJump);
            Receiver.Bind(JumpStemAddress, OnJumpStem);
            Receiver.Bind(VolumeAddress, OnVolume);
            Receiver.Bind(ClockMasterAddress, OnClockMaster);
            Receiver.Bind(DebugEnableAddress, OnDebugEnable);
            Receiver.Bind(DebugHeartbeatAddress, OnDebugHeartbeat);
            DebugReporter.Attach(Controller);
        }

        /// <summary>/debug/enable [0|1] — controller toggles verbose reporting + HUD.</summary>
        void OnDebugEnable(OSCMessage msg)
        {
            if (msg.Values.Count < 1) return;
            DebugReporter.SetEnabled(TryInt(msg.Values[0], 0) != 0);
        }

        /// <summary>/debug/heartbeat [0|1] — controller toggles the 1 Hz roster beacon.</summary>
        void OnDebugHeartbeat(OSCMessage msg)
        {
            if (msg.Values.Count < 1) return;
            DebugReporter.SetHeartbeat(TryInt(msg.Values[0], 0) != 0);
        }

        /// <summary>/clock/master [ip:string] — the cue server announces itself.</summary>
        void OnClockMaster(OSCMessage msg)
        {
            if (msg.Values.Count < 1) return;
            string ip = TryString(msg.Values[0], "");
            if (!string.IsNullOrEmpty(ip)) MasterClock.SetMaster(ip);
        }

        void OnCue(OSCMessage msg)
        {
            if (Controller == null || msg.Values.Count < 1) return;
            string cueId = TryString(msg.Values[0], "");
            if (string.IsNullOrEmpty(cueId)) return;

            // NEW way (optional): a second arg is the master-clock playAt timestamp.
            // No second arg, or UseScheduledSync off -> the original immediate path.
            if (UseScheduledSync && msg.Values.Count >= 2)
            {
                double playAt = TryDouble(msg.Values[1], double.NaN);
                if (!double.IsNaN(playAt))
                {
                    string key = cueId + "|" + playAt.ToString("R");
                    if (dedupeSet.Contains(key)) return;          // duplicate of a 3x send
                    dedupeSet.Add(key); dedupeOrder.Enqueue(key);
                    while (dedupeOrder.Count > 32) dedupeSet.Remove(dedupeOrder.Dequeue());

                    DebugReporter.ReportRx(cueId, MasterClock.Now, playAt);
                    Controller.TriggerCueScheduled(cueId, playAt);
                    return;
                }
            }

            DebugReporter.ReportRx(cueId, MasterClock.Now, 0);
            Controller.TriggerCue(cueId);
        }

        void OnSeek(OSCMessage msg)
        {
            if (Transport == null || msg.Values.Count < 1) return;
            Transport.SeekGlobal(TryFloat(msg.Values[0], 0f));
        }

        void OnSeekStem(OSCMessage msg)
        {
            if (Transport == null || msg.Values.Count < 2) return;
            Transport.SeekStem(TryString(msg.Values[0], ""), TryFloat(msg.Values[1], 0f));
        }

        void OnJump(OSCMessage msg)
        {
            if (Transport == null || msg.Values.Count < 1) return;
            Transport.JumpGlobal(TryFloat(msg.Values[0], 0f));
        }

        void OnJumpStem(OSCMessage msg)
        {
            if (Transport == null || msg.Values.Count < 2) return;
            Transport.JumpStem(TryString(msg.Values[0], ""), TryFloat(msg.Values[1], 0f));
        }

        void OnVolume(OSCMessage msg)
        {
            if (Controller == null || msg.Values.Count < 2) return;
            string stem = TryString(msg.Values[0], "");
            float vol = TryFloat(msg.Values[1], 0f);
            float speed = msg.Values.Count >= 3 ? TryFloat(msg.Values[2], 0f) : 0f;
            if (Controller.ActivePlayers.TryGetValue(stem, out var p)) p.SetVolume(vol, speed);
        }

        static int TryInt(OSCValue v, int fallback)
        {
            switch (v.Type)
            {
                case OSCValueType.Int: return v.IntValue;
                case OSCValueType.Long: return (int)v.LongValue;
                case OSCValueType.Float: return Mathf.RoundToInt(v.FloatValue);
                case OSCValueType.Double: return (int)v.DoubleValue;
                case OSCValueType.String: int.TryParse(v.StringValue, out var r); return r;
                default: return fallback;
            }
        }

        static double TryDouble(OSCValue v, double fallback)
        {
            switch (v.Type)
            {
                case OSCValueType.Double: return v.DoubleValue;
                case OSCValueType.Float: return v.FloatValue;
                case OSCValueType.Int: return v.IntValue;
                case OSCValueType.Long: return v.LongValue;
                case OSCValueType.String:
                    double.TryParse(v.StringValue, System.Globalization.NumberStyles.Float,
                        System.Globalization.CultureInfo.InvariantCulture, out var r); return r;
                default: return fallback;
            }
        }

        static float TryFloat(OSCValue v, float fallback)
        {
            switch (v.Type)
            {
                case OSCValueType.Float: return v.FloatValue;
                case OSCValueType.Double: return (float)v.DoubleValue;
                case OSCValueType.Int: return v.IntValue;
                case OSCValueType.Long: return v.LongValue;
                case OSCValueType.String:
                    float.TryParse(v.StringValue, System.Globalization.NumberStyles.Float,
                        System.Globalization.CultureInfo.InvariantCulture, out var r); return r;
                default: return fallback;
            }
        }

        static string TryString(OSCValue v, string fallback)
        {
            switch (v.Type)
            {
                case OSCValueType.String: return v.StringValue;
                case OSCValueType.Int: return v.IntValue.ToString();
                case OSCValueType.Float: return v.FloatValue.ToString(System.Globalization.CultureInfo.InvariantCulture);
                default: return fallback;
            }
        }
    }
}
