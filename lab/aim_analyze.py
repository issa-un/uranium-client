"""Reads an aim recording from Uranium (Debug > Record my aim) and says how the aim assist
in it works: when it turns on, how fast it turns at each distance from the target, how late
it reacts, whether it goes past, how much it wobbles, where on the player it aims, whether it
turns up and down, how often it turns, and whether its turns are whole mouse counts.

Only what shows in the game is measured: your view each frame, minus what your own mouse
did, is what the assist did.

    python aim_analyze.py recordings/aim-2026-11-19_20-00-00.csv
    (no file: the newest recording in ../recordings)
"""
import csv
import glob
import math
import os
import statistics
import sys

BUCKET_MS = 20.0
ACTIVE_DEG_S = 15.0


def wrap(a):
    a = (a + 180.0) % 360.0
    return a - 180.0


def num(text):
    try:
        return float(text)
    except (TypeError, ValueError):
        return None


def load(path):
    meta = {}
    frames = []
    presses = []
    with open(path, encoding="utf-8") as handle:
        lines = []
        for line in handle:
            if line.startswith("#"):
                if "=" in line:
                    k, v = line[1:].strip().split("=", 1)
                    meta[k.strip()] = v.strip()
                continue
            lines.append(line)
    for row in csv.DictReader(lines):
        if row["kind"] == "press":
            presses.append((num(row["t_ms"]), int(row["key"]), row["action"]))
            continue
        f = {k: row[k] for k in row}
        for k in ("t_ms", "yaw", "pitch", "hand_yaw", "hand_pitch", "dist", "yaw_mid", "pitch_head", "pitch_mid",
                  "pitch_feet", "half_width_deg", "tvx", "tvy", "tvz", "svx", "svy", "svz"):
            f[k] = num(row[k])
        for k in ("lmb", "rmb", "alt", "jump", "sneak", "on_ground"):
            f[k] = row[k] == "1"
        frames.append(f)
    return meta, frames, presses


def pct(part, whole):
    return 0.0 if whole == 0 else 100.0 * part / whole


def median(values, default=float("nan")):
    return statistics.median(values) if values else default


def main():
    if len(sys.argv) > 1:
        path = sys.argv[1]
    else:
        here = os.path.dirname(os.path.abspath(__file__))
        found = sorted(glob.glob(os.path.join(here, "..", "recordings", "aim-*.csv")), key=os.path.getmtime)
        if not found:
            print("No recording given and none in ../recordings")
            return
        path = found[-1]
    meta, frames, presses = load(path)
    per_count = float(meta.get("degrees_per_count", "0.15"))
    print(f"Recording: {os.path.basename(path)}")
    if len(frames) < 20:
        print("Too short to say anything.")
        return

    # Per frame: what the view did, minus your mouse = the assist.
    steps = []
    for a, b in zip(frames, frames[1:]):
        dt = b["t_ms"] - a["t_ms"]
        if dt <= 0 or dt > 100:
            continue
        dyaw = wrap(b["yaw"] - a["yaw"])
        dpitch = b["pitch"] - a["pitch"]
        # Looking straight up or down, the mouse keeps pushing but the view stops at 90:
        # only the part of your push the view could follow counts as your hand.
        hand_pitch = max(-90.0, min(90.0, a["pitch"] + b["hand_pitch"])) - a["pitch"]
        b["hand_pitch"] = hand_pitch
        s = {
            "t": b["t_ms"], "dt": dt, "f": b,
            "ayaw": dyaw - b["hand_yaw"], "apitch": dpitch - hand_pitch,
            "hand": abs(b["hand_yaw"]) + abs(b["hand_pitch"]) > 1e-9,
        }
        if b["yaw_mid"] is not None:
            s["off_yaw"] = wrap(b["yaw_mid"] - b["yaw"])
            s["off_pitch"] = b["pitch_mid"] - b["pitch"]
            s["width"] = b["half_width_deg"]
        steps.append(s)
    duration = (frames[-1]["t_ms"] - frames[0]["t_ms"]) / 1000.0
    frame_ms = median([s["dt"] for s in steps])
    print(f"{duration:.1f} s, {len(frames)} frames (about {1000 / frame_ms:.0f} fps), {len(presses)} presses")

    # Buckets of 20 ms, so a mouse report landing a frame late doesn't look like the assist.
    buckets = []
    current = None
    for s in steps:
        key = int(s["t"] // BUCKET_MS)
        if current is None or current["key"] != key:
            current = {"key": key, "t": key * BUCKET_MS, "dt": 0.0, "ayaw": 0.0, "apitch": 0.0, "hand": 0.0,
                       "frames": []}
            buckets.append(current)
        current["dt"] += s["dt"]
        current["ayaw"] += s["ayaw"]
        current["apitch"] += s["apitch"]
        current["hand"] += abs(s["f"]["hand_yaw"]) + abs(s["f"]["hand_pitch"])
        current["frames"].append(s)
    for b in buckets:
        last = b["frames"][-1]
        b["f"] = last["f"]
        b["speed"] = math.hypot(b["ayaw"], b["apitch"]) / max(1e-6, b["dt"] / 1000.0)
        b["active"] = b["speed"] > ACTIVE_DEG_S
        b["off_yaw"] = last.get("off_yaw")
        b["off_pitch"] = last.get("off_pitch")
        b["width"] = last.get("width")
        if b["off_yaw"] is not None:
            first = b["frames"][0]
            start_yaw = first["off_yaw"] if first.get("off_yaw") is not None else b["off_yaw"]
            b["off_start"] = start_yaw
    active = [b for b in buckets if b["active"]]
    print(f"Assist turning in {pct(len(active), len(buckets)):.0f}% of the time")
    if len(active) < 10:
        print("The assist hardly turned: was it on, and was a player near your crosshair?")
        return

    print("\n== When it turns on ==")
    triggers = {
        "Left Alt held": lambda f: f["alt"],
        "left mouse held": lambda f: f["lmb"],
        "right mouse held": lambda f: f["rmb"],
        "in the air": lambda f: not f["on_ground"],
        "moving (WASD)": lambda f: any(c in f["move_keys"] for c in "WASD"),
        "sprinting": lambda f: "R" in f["move_keys"],
        "jumping (space held)": lambda f: f["jump"],
        "player within 3 blocks": lambda f: f["dist"] is not None and f["dist"] <= 3,
        "player 3-4.5 blocks": lambda f: f["dist"] is not None and 3 < f["dist"] <= 4.5,
        "player 4.5-6 blocks": lambda f: f["dist"] is not None and 4.5 < f["dist"] <= 6,
        "player past 6 blocks": lambda f: f["dist"] is not None and f["dist"] > 6,
        "no player near": lambda f: f["dist"] is None,
    }
    # Only moments it had something to do: a player more than 2 degrees off your crosshair
    # (or no player, to see if it turns anyway).
    def needs(b):
        return b["off_yaw"] is None or math.hypot(b["off_yaw"], b["off_pitch"] or 0) > 2
    pool = [b for b in buckets if needs(b)]
    for name, test in triggers.items():
        yes = [b for b in pool if test(b["f"])]
        if len(yes) < 5:
            continue
        on = sum(1 for b in yes if b["active"])
        print(f"  {name:24s} turning {pct(on, len(yes)):5.1f}% of {len(yes)} moments")
    items = {}
    for b in buckets:
        items.setdefault(b["f"]["item"], [0, 0])
        items[b["f"]["item"]][0] += 1
        items[b["f"]["item"]][1] += b["active"]
    print("  by item in hand:")
    for item, (total, on) in sorted(items.items(), key=lambda x: -x[1][0])[:8]:
        print(f"    {item or '(empty)':22s} turning {pct(on, total):5.1f}% of {total}")
    far = [b["f"]["dist"] for b in active if b["f"]["dist"] is not None]
    if far:
        print(f"  farthest it turned toward someone: {max(far):.2f} blocks (most: under {sorted(far)[int(0.95 * len(far)) - 1]:.2f})")
    print("  (a trigger near 100% while the rest are low is what turns it on)")

    # Only moments with a target and no hand on the mouse, for the shape of its turn.
    clean = [b for b in active if b["off_yaw"] is not None and b["hand"] < per_count * 0.5]
    tracking = [b for b in buckets if b["off_yaw"] is not None and b["active"]]

    print("\n== How fast it turns, by how far off the target you are ==")
    bins = [(0, 1), (1, 2), (2, 4), (4, 8), (8, 16), (16, 32), (32, 90), (90, 181)]
    rates = []
    for lo, hi in bins:
        speeds = []
        for b in tracking:
            off = math.hypot(b["off_yaw"], b["off_pitch"] or 0)
            if lo <= off < hi:
                # the part of the turn toward them, deg/s
                if off > 1e-6:
                    toward = (b["ayaw"] * b["off_yaw"] + b["apitch"] * (b["off_pitch"] or 0)) / off
                    speeds.append(toward / (b["dt"] / 1000.0))
        if len(speeds) >= 3:
            m = median(speeds)
            rates.append(((lo + hi) / 2, m))
            print(f"  {lo:>3}-{hi:<3} degrees off: {m:7.0f} deg/s toward them ({len(speeds)} moments)")
    shape = "unknown"
    if len(rates) >= 3:
        small = [r / o for o, r in rates if o < 8 and r > 0]
        big = [r for o, r in rates if o >= 16]
        k = median(small, 0)
        top = max(r for _, r in rates)
        spread = (max(big) - min(big)) / max(1, top) if len(big) >= 2 else 1
        if spread < 0.25 and k * 4 > top * 0.6:
            shape = "a steady speed (Simple-like), about %.0f deg/s" % top
        else:
            shape = "slows down near them: about %.1f x the degrees off per second up close, up to %.0f deg/s" % (k, top)
        print(f"  shape: {shape}")

    print("\n== How late it reacts to them moving ==")
    series_t = [b for b in buckets if b["off_yaw"] is not None]
    target_rate = []
    assist_rate = []
    for a, b in zip(series_t, series_t[1:]):
        if b["key"] != a["key"] + 1:
            continue
        target_rate.append(wrap(b["f"]["yaw_mid"] - a["f"]["yaw_mid"]))
        assist_rate.append(b["ayaw"])
    best_lag = None
    if len(target_rate) > 50:
        scores = []
        for lag in range(0, 21):
            xs = target_rate[: len(target_rate) - lag]
            ys = assist_rate[lag:]
            if len(xs) < 20:
                break
            mx, my = statistics.fmean(xs), statistics.fmean(ys)
            cov = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
            vx = math.sqrt(sum((x - mx) ** 2 for x in xs)) or 1
            vy = math.sqrt(sum((y - my) ** 2 for y in ys)) or 1
            scores.append((cov / (vx * vy), lag))
        best = max(scores)
        best_lag = best[1] * BUCKET_MS
        print(f"  it follows their sideways movement about {best_lag:.0f} ms later (match {best[0]:.2f})")
        print("  (0-20 ms: it reacts at once, maybe aims ahead; 60-150 ms: a human-like reaction delay)")
    else:
        print("  not enough moments with them moving sideways")

    print("\n== Does it go past them? ==")
    passes = []
    turns = 0
    i = 0
    while i < len(series_t):
        b = series_t[i]
        if abs(b["off_yaw"]) > 6 and b["active"]:
            start = b["off_yaw"]
            turns += 1
            j = i + 1
            worst = 0.0
            moved = 0.0
            while j < len(series_t) and j < i + 40 and series_t[j]["key"] - series_t[j - 1]["key"] <= 2:
                off = series_t[j]["off_yaw"]
                moved = max(moved, abs(wrap(series_t[j]["f"]["yaw_mid"] - b["f"]["yaw_mid"])))
                if moved > 0.2 * abs(start):
                    break  # they moved: crossing them now says nothing about overshoot
                if off * start < 0:
                    worst = max(worst, abs(off))
                if abs(off) < 0.5 and worst == 0 and not series_t[j]["active"]:
                    break
                j += 1
            if moved <= 0.2 * abs(start):
                if worst > 0:
                    passes.append(100 * worst / abs(start))
            else:
                turns -= 1
            i = j
        else:
            i += 1
    if turns:
        print(f"  went past on {len(passes)} of {turns} big turns ({pct(len(passes), turns):.0f}%)")
        if passes:
            print(f"  by about {median(passes):.0f}% of the turn (most up to {max(passes):.0f}%)")
    else:
        print("  no big turns (over 6 degrees) at a player standing still enough to judge")

    print("\n== Wobble (does the path bend?) ==")
    along = []
    side = []
    for b in tracking:
        off = math.hypot(b["off_yaw"], b["off_pitch"] or 0)
        if off < 3:
            continue
        ux, uy = b["off_yaw"] / off, (b["off_pitch"] or 0) / off
        along.append(b["ayaw"] * ux + b["apitch"] * uy)
        side.append(-b["ayaw"] * uy + b["apitch"] * ux)
    if along:
        ratio = math.sqrt(statistics.fmean(x * x for x in side)) / max(1e-6, math.sqrt(statistics.fmean(x * x for x in along)))
        print(f"  sideways wobble is {100 * ratio:.0f}% of the turn toward them"
              f" ({'straight' if ratio < 0.1 else 'a little bend' if ratio < 0.3 else 'wanders a lot (Wind-like)'})")

    print("\n== Where on them it aims ==")
    spots = []
    sides = []
    for b in buckets:
        f = b["f"]
        if b["off_yaw"] is None or b["active"] or b["hand"] > 0:
            continue
        if abs(b["off_yaw"]) > (b["width"] or 1) * 1.2:
            continue
        span = f["pitch_feet"] - f["pitch_head"]
        if abs(span) < 1e-6:
            continue
        spots.append((f["pitch"] - f["pitch_head"]) / span)
        sides.append(b["off_yaw"] / max(1e-6, b["width"]))
    # also where turns settle: the moment right after it stops
    for a, b in zip(buckets, buckets[1:]):
        if a["active"] and not b["active"] and b["off_yaw"] is not None:
            f = b["f"]
            span = f["pitch_feet"] - f["pitch_head"]
            if abs(span) > 1e-6:
                spots.append((f["pitch"] - f["pitch_head"]) / span)
    if spots:
        m = median(spots)
        where = "head" if m < 0.2 else "chest" if m < 0.45 else "middle" if m < 0.65 else "legs" if m < 0.9 else "feet"
        print(f"  settles {100 * m:.0f}% of the way from their eyes to their feet: their {where}")
        print(f"  spread: {100 * statistics.pstdev(spots):.0f}% (small = a fixed spot like Center; big = nearest point like Auto)")

    print("\n== Up and down ==")
    yaw_total = sum(abs(b["ayaw"]) for b in active)
    pitch_total = sum(abs(b["apitch"]) for b in active)
    vratio = pitch_total / max(1e-6, yaw_total)
    print(f"  up-down is {100 * vratio:.0f}% of its side-to-side turning"
          f" ({'Vertical aim looks off' if vratio < 0.05 else 'it aims up and down too'})")

    print("\n== How often it turns ==")
    times = [s["t"] for s in steps if not s["hand"] and math.hypot(s["ayaw"], s["apitch"]) > 1e-4]
    gaps = [b - a for a, b in zip(times, times[1:]) if b - a < 200]
    if gaps:
        g = median(gaps)
        how = "every frame" if g < frame_ms * 1.5 else "every game tick (50 ms)" if 40 < g < 60 else f"every {g:.0f} ms"
        print(f"  a turn {how} (median gap {g:.1f} ms, a frame is {frame_ms:.1f} ms)")

    print("\n== Mouse counts ==")
    counts = []
    for s in steps:
        if s["hand"]:
            continue
        for d in (s["ayaw"], s["apitch"]):
            if abs(d) > per_count * 0.5:
                c = d / per_count
                counts.append(abs(c - round(c)))
    if counts:
        whole = pct(sum(1 for c in counts if c < 0.05), len(counts))
        print(f"  {whole:.0f}% of its turns are whole mouse counts (random would be about 10%)")
        print("  " + ("it moves like a real mouse" if whole > 80 else
                      "it turns by fractions a mouse can't make (an anticheat can see that)"))

    print("\n== Closest Uranium Aim Helper settings ==")
    mode = "Simple"
    if along and ratio >= 0.3:
        mode = "Wind"
    elif (best_lag or 0) >= 60 or (turns and len(passes) / turns > 0.3):
        mode = "Human"
    print(f"  Aim mode: {mode}")
    if rates:
        print(f"  Speed: about {max(r for _, r in rates):.0f} deg/s at most")
    if best_lag is not None and mode == "Human":
        print(f"  Reaction: {best_lag:.0f} ms")
    if passes and mode == "Human":
        print(f"  Overshoot: {median(passes):.0f}%")
    if spots:
        print(f"  Aim at: {'Head' if m < 0.2 else 'Center' if m < 0.7 else 'Feet'}"
              f"{' (or Auto: it lands in different spots)' if statistics.pstdev(spots) > 0.2 else ''}")
    print(f"  Vertical aim: {'off' if vratio < 0.05 else 'on'}")
    if far:
        print(f"  Range: about {sorted(far)[int(0.95 * len(far)) - 1]:.1f} blocks")


if __name__ == "__main__":
    main()
