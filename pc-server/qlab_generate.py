#!/usr/bin/env python3
"""Build a QLab cue list for Joan of the City from the show's own cue CSV.

Run this ON THE MAC, with the QLab workspace you want to fill already open.

    python3 qlab_generate.py --probe          # create ONE cue and report
    python3 qlab_generate.py --track B        # then create the whole track

WHY A GENERATOR AND NOT A .qlab5 FILE
    QLab's document is a package format Figure 53 do not document for outside
    authoring. Hand-writing one would be fragile and would break on any QLab
    update. Asking QLab to build its own cues over OSC is the supported route,
    and it means re-running this after a CSV change is cheap — the cue list
    stays derived from the show's single source of truth rather than becoming a
    second copy that drifts.

WHAT EACH CUE DOES
    One QLab network cue per cue group, sending

        /joan/cue B_201

    to the cue server, which then schedules it, sends it three times, and fans
    it out to every headset. QLab is NOT pointed at the headsets directly: a
    network cue sends one message, once, with no timestamp, which would lose
    both the redundancy and the scheduled playAt that keeps several headsets in
    sync with each other.

ABOUT --probe
    The OSC property name for a network cue's message text is not stated in the
    published dictionary, so this script tries the plausible names and reports
    which one QLab accepted. Run --probe first: it creates a single cue, tells
    you what worked, and you can look at that cue in QLab to confirm it is
    right before generating 175 of them.
"""
import argparse, csv, io, re, sys, time

try:
    from pythonosc import udp_client
except ImportError:
    sys.exit("pythonosc is missing.  pip3 install python-osc")

# Candidate property names for the message text, most likely first. QLab
# ignores addresses it does not recognise, so trying several is harmless — and
# far better than guessing one and silently producing 175 empty cues.
MESSAGE_PROPS = [
    "messageText",        # QLab 5 custom-OSC patch: the single large text field
    "customMessage",
    "message",
    "oscMessage",
    "network/messageText",
]


def load_groups(csv_path, track):
    rows = list(csv.reader(io.open(csv_path, encoding="utf-8-sig")))
    hdr, data = None, []
    for r in rows:
        if r and r[0] == "CueId":
            hdr = r
            continue
        if hdr and r and r[0].strip():
            data.append(r)

    order, notes = [], {}
    for r in data:
        cid = r[0].strip()
        m = re.match(r"^([ABC])_(?:SQ|VQ)(.+)$", cid)
        group = f"{m.group(1)}_{m.group(2)}" if m else cid
        if track and not group.startswith(f"{track}_") and group != "STOP_ALL":
            continue
        if group not in notes:
            order.append(group)
            notes[group] = []
        n = (r[1] or "").strip()
        if n and n not in notes[group]:
            notes[group].append(n)
    return [(g, " / ".join(notes[g])) for g in order]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--qlab", default="127.0.0.1",
                    help="IP of the Mac running QLab (default: this machine)")
    ap.add_argument("--qlab-port", type=int, default=53000)
    ap.add_argument("--server", required=False,
                    help="IP of the cue server, for the note on each cue")
    ap.add_argument("--csv", default="audio_cues.csv")
    ap.add_argument("--track", default="B", help="A, B, C, or blank for all")
    ap.add_argument("--probe", action="store_true",
                    help="create ONE cue and report which property worked")
    ap.add_argument("--gap", type=float, default=0.12)
    args = ap.parse_args()

    groups = load_groups(args.csv, args.track.strip().upper())
    if not groups:
        sys.exit(f"No cues found for track {args.track!r} in {args.csv}")

    if args.probe:
        groups = groups[:1]
        print("PROBE: creating one cue only.\n")

    q = udp_client.SimpleUDPClient(args.qlab, args.qlab_port)
    print(f"QLab at {args.qlab}:{args.qlab_port} — {len(groups)} cue(s) to create")
    print("Make sure the workspace you want is open and frontmost.\n")

    for i, (group, note) in enumerate(groups, 1):
        # New cue; it becomes selected, so subsequent messages target it.
        q.send_message("/new", ["network"])
        time.sleep(args.gap)
        q.send_message("/cue/selected/number", [group])
        time.sleep(args.gap * 0.5)
        q.send_message("/cue/selected/name", [note[:60] if note else group])
        time.sleep(args.gap * 0.5)
        if note:
            q.send_message("/cue/selected/notes", [note])
            time.sleep(args.gap * 0.5)

        payload = f"/joan/cue {group}"
        for prop in MESSAGE_PROPS:
            q.send_message(f"/cue/selected/{prop}", [payload])
            time.sleep(0.04)

        print(f"  {i:>3}/{len(groups)}  {group:<12} {payload}")
        time.sleep(args.gap)

    print("\nDone.")
    if args.probe:
        print(
            "\nNow look at that cue in QLab:\n"
            "  - Does its message field read exactly:  /joan/cue " + groups[0][0] + "\n"
            "  - Is its patch pointing at the cue server (see below)?\n"
            "\nIf the message field is EMPTY, none of the property names worked —\n"
            "tell me what QLab calls that field and I will correct the script.\n")
    print(
        "Remaining manual step (once per workspace, not per cue):\n"
        "  QLab -> Workspace Settings -> Network -> add an OSC destination\n"
        f"  pointing at the cue server{' at ' + args.server if args.server else ''}, port 53000,\n"
        "  then set it as the patch for these cues.\n")


if __name__ == "__main__":
    main()
