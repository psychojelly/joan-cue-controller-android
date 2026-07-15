using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using UnityEngine;
using Debug = UnityEngine.Debug;

namespace JoanAudio
{
    /// <summary>
    /// NTP-style clock sync client (audio sync Phase 0).
    ///
    /// Learns the cue master's IP from the /clock/master OSC announce (see
    /// OscCueReceiver), then pings the master's :9001 responder every few
    /// seconds:   /clock/ping [seq:int]  ->  /clock/pong [seq:int, masterTime:double]
    ///
    /// One sample: offset = masterTime - (t0 + t1)/2, quality = round trip.
    /// Keeps the last 16 samples and applies the median offset of the 4
    /// lowest-RTT samples (rejects samples taken while the Wi-Fi was noisy),
    /// slewing toward it rather than jumping.
    ///
    /// Everything is self-contained: no scene objects, no inspector wiring —
    /// a hidden host GameObject is created on demand so play-mode teardown
    /// cleanly stops the thread.
    /// </summary>
    public static class MasterClock
    {
        const int ClockPort = 9001;
        const float PingIntervalSeconds = 5f;
        const int SampleWindow = 16;
        const int BestSamples = 4;

        // Local monotonic base — thread-safe, unaffected by wall-clock changes.
        static readonly Stopwatch stopwatch = Stopwatch.StartNew();
        public static double LocalNow => stopwatch.Elapsed.TotalSeconds;

        static readonly object gate = new object();
        static readonly List<(double offset, double rtt)> samples = new List<(double, double)>();
        static double appliedOffset;
        static bool hasOffset;
        static string masterIp;
        static Thread pingThread;
        static volatile bool running;
        static UdpClient udp;

        /// <summary>True once at least one good sample has been applied.</summary>
        public static bool IsSynced { get { lock (gate) return hasOffset; } }

        /// <summary>Master time right now (only meaningful when IsSynced).</summary>
        public static double Now { get { lock (gate) return LocalNow + appliedOffset; } }

        /// <summary>The current master IP (null until a /clock/master announce arrives).</summary>
        public static string MasterIp { get { lock (gate) return masterIp; } }

        /// <summary>Point the clock at the master. Safe to call repeatedly.</summary>
        public static void SetMaster(string ip)
        {
            if (string.IsNullOrEmpty(ip)) return;
            lock (gate)
            {
                if (ip == masterIp && running) return;
                masterIp = ip;
            }
            Debug.Log($"[JoanAudio] MasterClock: master -> {ip}");
            EnsureRunning();
        }

        // ---- lifecycle -------------------------------------------------------

        static void EnsureRunning()
        {
            if (running) return;
            running = true;
            ClockHost.Ensure();   // hidden GO whose destruction stops us on play-exit
            pingThread = new Thread(PingLoop) { IsBackground = true, Name = "JoanMasterClock" };
            pingThread.Start();
        }

        internal static void Stop()
        {
            running = false;
            try { udp?.Close(); } catch { }
            udp = null;
        }

        // ---- ping loop -------------------------------------------------------

        static void PingLoop()
        {
            int seq = 0;
            while (running)
            {
                string ip;
                lock (gate) ip = masterIp;
                if (!string.IsNullOrEmpty(ip))
                {
                    try { PingOnce(ip, ++seq); }
                    catch (Exception e) { if (running) Debug.LogWarning($"[JoanAudio] MasterClock ping: {e.Message}"); }
                }
                for (int i = 0; i < PingIntervalSeconds * 10 && running; i++) Thread.Sleep(100);
            }
        }

        static void PingOnce(string ip, int seq)
        {
            if (udp == null) { udp = new UdpClient(); udp.Client.ReceiveTimeout = 1500; }

            byte[] ping = EncodePing(seq);
            double t0 = LocalNow;
            udp.Send(ping, ping.Length, ip, ClockPort);

            var remote = new IPEndPoint(IPAddress.Any, 0);
            byte[] reply = udp.Receive(ref remote);
            double t1 = LocalNow;

            if (!DecodePong(reply, out int gotSeq, out double masterTime) || gotSeq != seq) return;

            double rtt = t1 - t0;
            double offset = masterTime - (t0 + t1) / 2.0;

            lock (gate)
            {
                samples.Add((offset, rtt));
                while (samples.Count > SampleWindow) samples.RemoveAt(0);

                // Median offset of the lowest-RTT samples — noise-resistant.
                var best = samples.OrderBy(s => s.rtt).Take(Math.Min(BestSamples, samples.Count))
                                  .Select(s => s.offset).OrderBy(o => o).ToList();
                double target = best[best.Count / 2];

                if (!hasOffset) { appliedOffset = target; hasOffset = true;
                    Debug.Log($"[JoanAudio] MasterClock synced to {ip} (offset {target * 1000.0:F1} ms, rtt {rtt * 1000.0:F1} ms)"); }
                else appliedOffset += (target - appliedOffset) * 0.2;   // slew, don't jump
            }
        }

        // ---- minimal OSC for ping/pong (kept local; extOSC's transmitter is
        //      scene-bound and this must run on a background thread) ----------

        static byte[] EncodePing(int seq)
        {
            byte[] addr = PadOsc("/clock/ping");
            byte[] tags = PadOsc(",i");
            byte[] arg = BitConverter.GetBytes(seq);
            if (BitConverter.IsLittleEndian) Array.Reverse(arg);
            var outBytes = new byte[addr.Length + tags.Length + 4];
            Buffer.BlockCopy(addr, 0, outBytes, 0, addr.Length);
            Buffer.BlockCopy(tags, 0, outBytes, addr.Length, tags.Length);
            Buffer.BlockCopy(arg, 0, outBytes, addr.Length + tags.Length, 4);
            return outBytes;
        }

        static bool DecodePong(byte[] data, out int seq, out double masterTime)
        {
            seq = 0; masterTime = 0;
            try
            {
                int pos = 0;
                string address = ReadOscString(data, ref pos);
                if (address != "/clock/pong") return false;
                string tags = ReadOscString(data, ref pos);
                if (!tags.StartsWith(",")) return false;
                foreach (char t in tags.Substring(1))
                {
                    if (t == 'i') { seq = ReadBigEndianInt(data, ref pos); }
                    else if (t == 'd') { masterTime = ReadBigEndianDouble(data, ref pos); }
                    else if (t == 'f') { masterTime = ReadBigEndianFloat(data, ref pos); }
                    else return false;
                }
                return true;
            }
            catch { return false; }
        }

        static byte[] PadOsc(string s)
        {
            byte[] raw = System.Text.Encoding.UTF8.GetBytes(s);
            int padded = (raw.Length / 4 + 1) * 4;
            var b = new byte[padded];
            Buffer.BlockCopy(raw, 0, b, 0, raw.Length);
            return b;
        }

        static string ReadOscString(byte[] d, ref int pos)
        {
            int start = pos;
            while (pos < d.Length && d[pos] != 0) pos++;
            string s = System.Text.Encoding.UTF8.GetString(d, start, pos - start);
            pos = start + ((pos - start) / 4 + 1) * 4;
            return s;
        }

        static int ReadBigEndianInt(byte[] d, ref int pos)
        {
            var b = new byte[4]; Buffer.BlockCopy(d, pos, b, 0, 4); pos += 4;
            if (BitConverter.IsLittleEndian) Array.Reverse(b);
            return BitConverter.ToInt32(b, 0);
        }

        static double ReadBigEndianDouble(byte[] d, ref int pos)
        {
            var b = new byte[8]; Buffer.BlockCopy(d, pos, b, 0, 8); pos += 8;
            if (BitConverter.IsLittleEndian) Array.Reverse(b);
            return BitConverter.ToDouble(b, 0);
        }

        static float ReadBigEndianFloat(byte[] d, ref int pos)
        {
            var b = new byte[4]; Buffer.BlockCopy(d, pos, b, 0, 4); pos += 4;
            if (BitConverter.IsLittleEndian) Array.Reverse(b);
            return BitConverter.ToSingle(b, 0);
        }

        /// <summary>Hidden host: exists only so play-mode teardown stops the thread.</summary>
        class ClockHost : MonoBehaviour
        {
            static ClockHost instance;
            public static void Ensure()
            {
                if (instance != null) return;
                var go = new GameObject("~JoanMasterClock") { hideFlags = HideFlags.HideAndDontSave };
                DontDestroyOnLoad(go);
                instance = go.AddComponent<ClockHost>();
            }
            void OnDestroy() { MasterClock.Stop(); instance = null; }
        }
    }
}
