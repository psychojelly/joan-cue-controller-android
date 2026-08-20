#!/usr/bin/env python3
"""
Fire every cue in show order and rank them by what they cost the frame.

The build already measures this: PerfProbe waits SettleSeconds (2.5s) after a
cue so the content actually spawns, then logs one line per cue:

    cost B_VQ601: 16.7 -> 44.0 ms (+27.3ms, 31 rend, 22 mat, 2 light, ...)

So the sweep does not need to measure anything itself. It fires, paces slowly
enough that the measurement is of a settled scene rather than of the spawn,
and reads the device's own numbers back. Pacing below ~4s measures loading.

Usage:
  python cue-sweep.py --ip 192.168.2.254 --csv audio_cues.csv [--dwell 6] [--only B_VQ]
"""
import argparse, socket, subprocess, sys, time, csv, io, re, os

ADB = os.environ.get('ADB', 'adb')

def osc(addr, *args):
    pad = lambda b: b + b'\0' * (4 - len(b) % 4)
    tags = ','
    body = b''
    for a in args:
        if isinstance(a, str):
            tags += 's'; body += pad(a.encode())
    return pad(addr.encode()) + pad(tags.encode()) + body

def cue_order(csv_path, only):
    seen, out = set(), []
    for r in csv.reader(io.open(csv_path, encoding='utf-8')):
        if not r or not r[0].strip(): continue
        c = r[0].strip()
        if c in ('CueId', 'BaseUrl', 'CsvVersion', 'STOP_ALL'): continue
        if only and not c.startswith(only): continue
        if c not in seen:
            seen.add(c); out.append(c)
    return out

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--ip', required=True)
    ap.add_argument('--port', type=int, default=7000)
    ap.add_argument('--serial', default=None, help='adb serial, e.g. 192.168.2.254:5555')
    ap.add_argument('--csv', required=True)
    ap.add_argument('--dwell', type=float, default=6.0)
    ap.add_argument('--only', default='B_')
    ap.add_argument('--out', default='cue-sweep-results.csv')
    a = ap.parse_args()

    serial = a.serial or (a.ip + ':5555')
    adb = [ADB, '-s', serial]

    cues = cue_order(a.csv, a.only)
    print("  sweeping %d cues at %.1fs dwell  (~%.0f min)" % (len(cues), a.dwell, len(cues)*a.dwell/60))

    subprocess.run(adb + ['logcat', '-c'], capture_output=True)
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    for i, c in enumerate(cues, 1):
        s.sendto(osc('/audio/cue', c), (a.ip, a.port))
        print("    [%3d/%d] %s" % (i, len(cues), c), flush=True)
        time.sleep(a.dwell)
    s.close()

    log = subprocess.run(adb + ['logcat', '-d'], capture_output=True, text=True,
                         errors='replace').stdout
    # cost B_VQ601: 16.7 -> 44.0 ms (+27.3ms, 31 rend, 22 mat, 2 light, 9021 particles, 7 vfx)
    pat = re.compile(r'cost (\S+): ([\d.]+) -> ([\d.]+) ms \(([+\-][\d.]+)ms,'
                     r'\s*(\d+) rend,\s*(\d+) mat,\s*(\d+) light,\s*(\d+) particles,\s*(\d+) vfx')
    rows = []
    for m in pat.finditer(log):
        rows.append(dict(cue=m.group(1), before=float(m.group(2)), after=float(m.group(3)),
                         delta=float(m.group(4)), rend=int(m.group(5)), mat=int(m.group(6)),
                         light=int(m.group(7)), particles=int(m.group(8)), vfx=int(m.group(9))))
    if not rows:
        print("  NO cost lines captured. Is the app running and receiving cues?")
        sys.exit(1)

    rows.sort(key=lambda r: -r['after'])
    with io.open(a.out, 'w', encoding='utf-8', newline='') as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0].keys())); w.writeheader(); w.writerows(rows)

    print("\n  === most expensive cues (settled frame time) ===")
    print("  %-12s %8s %8s %8s %7s %6s %10s %5s" %
          ('CUE','BEFORE','AFTER','DELTA','FPS','REND','PARTICLES','VFX'))
    for r in rows[:20]:
        fps = 1000.0 / r['after'] if r['after'] else 0
        print("  %-12s %7.1f %8.1f %8.1f %7.0f %6d %10d %5d" %
              (r['cue'], r['before'], r['after'], r['delta'], fps, r['rend'], r['particles'], r['vfx']))
    print("\n  full table -> %s   (%d cues measured)" % (a.out, len(rows)))

main()
