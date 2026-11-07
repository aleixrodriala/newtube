#!/usr/bin/env python3
"""In-app source benchmark (netbench phase 1).

Opens each (source, video) cell in the side-by-side benchmark build of NewTube
(io.github.aleixrodriala.arc.check: its own data, no account) with the source forced through
debug.arc.player_client, lets it play like a user would for --play-s seconds (with the app's own
auto-seek from debug.arc.bench_seek), then reads the app's NetPath lines and writes one JSON line
per open: what /player answered, what the loader chose, first frame, how far playback got
(bench-tick), the seek, and any error.

Pixel hard rules: every intent is preceded by guard.sh start (focus is the check app, the launcher
or the owner's idle NewTube; no shade; awake; no call). While a cell plays, the focus must stay on
the check app; if anything else takes it (the owner picked the phone up), the cell is aborted and
the run stops. Intents always name the check package. No logcat -c. Media volume is set to 0 for
the run and restored after.

One walk at a time: every open holds a host-wide lock (--sender-lock) from its guard check until
its /player walk is decided (a first frame, a refusal, an error, or 25 s), so phones on one network
never walk at the same moment; playback then overlaps, as in a home with several screens. The first
explicit bot check (YouTube's "confirm you're not a bot"), a trip or an established wall on any
phone writes the host STOP file next to the lock, which stops every run until a person removes it.
A phone under --min-battery percent stops its run.

Usage:
  NETBENCH_SERIAL=<adb serial> appbench.py --network lte --sources TV_TIZEN,WEB_EMBED \
      --videos _WB5hh7WOb4,dQw4w9WgXcQ [--support-xhr none|true|false|absent] [--repeat 1] \
      [--play-s 150] [--seek 90:0.7] [--data DIR] --run-id X
The phone comes from --serial or NETBENCH_SERIAL (no default: it refuses to run without one), the
guard is ../device/guard.sh, and results go to <data>/appbench/results (--data or NETBENCH_DATA,
default: the tools/netbench directory).
"""
import argparse
import contextlib
import fcntl
import json
import os
import re
import signal
import subprocess
import sys
import time

SERIAL = None  # --serial or NETBENCH_SERIAL; main() refuses to run without one
PKG = "io.github.aleixrodriala.arc.check"
HERE = os.path.dirname(os.path.abspath(__file__))
GUARD = os.path.join(os.path.dirname(HERE), "device", "guard.sh")
DEFAULT_DATA = os.path.dirname(HERE)  # tools/netbench
RESULTS = os.path.join(DEFAULT_DATA, "appbench", "results")  # main(): <data>/appbench/results


def adb(*args, check=False, timeout=60):
    r = subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.decode(errors='replace')}")
    return r.stdout.decode(errors="replace")


def shell(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


CURRENT_LOGCAT = None
KEPT_STARTED = [False]
NO_GUARD = False  # only for my own emulator (--serial emulator-*): nobody else uses it
SENDER_LOCK = None  # main(): --sender-lock, None = off
DEFAULT_SENDER_LOCK = os.path.expanduser("~/.cache/netbench/sender.lock")


WALK_TURN_MAX_S = 25  # a walk that decided nothing by then releases the turn anyway


class Turn:
    """A held sender turn; release() once the open's walk is decided (idempotent)."""

    def __init__(self, fh):
        self.fh = fh

    @property
    def held(self):
        return self.fh is not None

    def release(self):
        if self.fh is not None:
            fcntl.flock(self.fh, fcntl.LOCK_UN)
            self.fh.close()
            self.fh = None


@contextlib.contextmanager
def sender_turn():
    """This host's turn to walk: the lock is flock(2), so it dies with the process."""
    if not SENDER_LOCK:
        yield Turn(None)
        return
    os.makedirs(os.path.dirname(SENDER_LOCK), exist_ok=True)
    fh = open(SENDER_LOCK, "a")
    t = time.time()
    try:
        fcntl.flock(fh, fcntl.LOCK_EX)
    except BaseException:
        fh.close()
        raise
    if time.time() - t > 1:
        print(f"  (waited {time.time() - t:.0f}s for the sender turn)", flush=True)
    turn = Turn(fh)
    try:
        yield turn
    finally:
        turn.release()
    time.sleep(1.5)  # flock is not FIFO: give a waiting run the next turn


def host_stop_path():
    return os.path.join(os.path.dirname(SENDER_LOCK), "STOP") if SENDER_LOCK else None


def host_stopped():
    """The reason in the host STOP file, or None."""
    path = host_stop_path()
    if path and os.path.exists(path):
        with open(path, errors="replace") as fh:
            return fh.read().strip() or "STOP file present"
    return None


def stop_host(reason):
    path = host_stop_path()
    if path:
        with open(path, "a") as fh:
            fh.write(f"{time.strftime('%Y-%m-%dT%H:%M:%S')} {SERIAL} {reason}\n")


def walk_decided(lines, video):
    """The open's /player walk is over: a first frame, a media-less answer, an error, a trip."""
    res = parse(lines, video)
    info = res["info"]
    return (res["first_frame_ms"] is not None or bool(res["errors"]) or res.get("bot_trip", False)
            or bool(info and "dash=0 hls=n" in info["detail"]))


def bot_signal(lines):
    """The first line of this open that says YouTube challenged the IP, or None. A private video's
    repeated sign-in text is signal=repeated-login and does not count."""
    for ln in lines:
        if ("bot-check" in ln and "signal=explicit" in ln) or "bot-check trip" in ln \
                or "botwall established" in ln:
            return ln.split("NetPath", 1)[-1].strip(" ():0123456789")[:160]
    return None


def battery_level():
    m = re.search(r"^\s*level: (\d+)", shell("dumpsys battery"), re.M)
    return int(m.group(1)) if m else None


def guard(mode="start"):
    if NO_GUARD:
        return True, "no guard (own emulator)"
    # The guard checks the same phone this run drives.
    r = subprocess.run([GUARD, mode], capture_output=True, timeout=60,
                       env={**os.environ, "NETBENCH_SERIAL": SERIAL,
                            "GUARD_ALLOW_CALL": "1" if ALLOW_CALL else "0"})
    return r.returncode == 0, r.stdout.decode(errors="replace").strip()


def focus():
    out = shell("dumpsys window | grep -m1 mCurrentFocus")
    return out.strip()


def setprop(key, value):
    # Android can't reliably clear a property; the app treats "none" as unset.
    shell(f"setprop {key} {value if value else 'none'}")


def device_time():
    # logcat -T wants the device's own clock: 'MM-DD hh:mm:ss.mmm'.
    return shell("date +'%m-%d %H:%M:%S.000'").strip()


def media_volume():
    out = shell("cmd media_session volume --stream 3 --get")
    m = re.search(r"volume is (\d+)", out)
    return int(m.group(1)) if m else None


def set_media_volume(v):
    # cmd audio, not cmd media_session: on the Pixel's Android 17 a 0 from media_session mutes the
    # stream, and media_session's later --set is then ignored (2026-10-19: a run left the owner's
    # media volume muted at 0); AudioManager.setStreamVolume through cmd audio unmutes it.
    shell(f"cmd audio set-volume 3 {v}")


# The owner's in-call rule (2026-09-29): with --allow-call (or NETBENCH_ALLOW_CALL=1) an active call
# no longer fails the guard for opens, but before any open during a call the media stream on the
# ACTIVE output (his earbuds when connected) must read 0, or the open waits. Network toggles never
# relax (pixel-lte-wrap.sh). Cells that saw a call are tagged incall=true (out of timing stats).
ALLOW_CALL = False
ZEROED = {}  # output device -> the media index this run found there before it set 0


def audio_device():
    """The media stream's active output (dumpsys audio, STREAM_MUSIC 'Devices:'), or None."""
    m = re.search(r"- STREAM_MUSIC:.*?\n\s*Devices: (\S+)", shell("dumpsys audio"), re.S)
    return m.group(1) if m else None


def call_active():
    """True/False from the telephony registry; None when the read came back empty."""
    states = re.findall(r"mCallState=(\d)", shell("dumpsys telephony.registry | grep mCallState"))
    return any(st != "0" for st in states) if states else None


def zero_media_volume():
    """The media stream on the active output to 0, remembering that output's prior index for the
    restore; True once it reads 0 (a muted group reads 0 and ignores the set: that is silent too)."""
    v = media_volume()
    if v is None:
        return False
    if v != 0:
        ZEROED.setdefault(audio_device(), v)
        for _ in range(3):
            set_media_volume(0)
            time.sleep(0.5)
            if media_volume() == 0:
                break
    return media_volume() == 0



def extra_props(args):
    """--prop KEY=VALUE switches, only debug.arc.* keys."""
    props = {}
    for item in getattr(args, "prop", None) or []:
        key, sep, value = item.partition("=")
        if not sep or not key.startswith("debug.arc.") or not re.fullmatch(r"[A-Za-z0-9_.]+", key) \
                or not re.fullmatch(r"[A-Za-z0-9_.:-]*", value):
            raise SystemExit(f"bad --prop {item!r}")
        props[key] = value
    return props

def parse(lines, video, window_s=None):
    """Turn the NetPath lines of one open into a verdict. window_s: a --settle cell's play window,
    judged on its own length instead of the 60/140 s marks of a full cell."""
    res = {"results": [], "winner": None, "info": None, "prepare": None, "first_frame_ms": None,
           "ticks": [], "seek": None, "errors": [], "http403": 0, "auto_reload_cap": False,
           "loads_403": 0, "readiness": [], "autoplay_stop": None}
    for ln in lines:
        if "NetPath" not in ln:
            continue
        m = re.search(r"player-result video=(\S+) client=(\S+) attempt=(\d+) status=(\S+) playable=(\S) .*?"
                      r"formats=(\S+) usableAdaptive=(\d+) dash=(\S) hls=(\S) sabr=(\S) reason=\"(.*?)\"", ln)
        if m and m.group(1) == video:
            res["results"].append({"client": m.group(2), "attempt": int(m.group(3)), "status": m.group(4),
                                   "playable": m.group(5), "formats": m.group(6),
                                   "usable": int(m.group(7)), "hls": m.group(9), "sabr": m.group(10),
                                   "reason": m.group(11)[:80]})
            continue
        m = re.search(r"player-transform video=(\S+) client=(\S+)", ln)
        if m and m.group(1) == video:
            res["winner"] = m.group(2)
        m = re.search(r"video=" + re.escape(video) + r" info \+(\d+) (.*)", ln)
        if m:
            res["info"] = {"ms": int(m.group(1)), "detail": m.group(2)}
        m = re.search(r"video=" + re.escape(video) + r" prepare \+(\d+) type=(\S+)", ln)
        if m:
            res["prepare"] = {"ms": int(m.group(1)), "type": m.group(2)}
        m = re.search(r"video=" + re.escape(video) + r" first-frame \+(\d+)", ln)
        if m and res["first_frame_ms"] is None:
            res["first_frame_ms"] = int(m.group(1))
        m = re.search(r"video=" + re.escape(video) + r" error \+(\d+) (.*)", ln)
        if m:
            res["errors"].append({"ms": int(m.group(1)), "error": m.group(2)[:160],
                                  "ticks_before": len(res["ticks"])})
        m = re.search(r"bench-tick video=(\S+) pos=(-?\d+) dur=(-?\d+) buf=(-?\d+) state=(\S+) playing=(\S) t=(\d+)", ln)
        if m and m.group(1) == video:
            res["ticks"].append({"pos": int(m.group(2)), "dur": int(m.group(3)), "buf": int(m.group(4)),
                                 "state": m.group(5), "playing": m.group(6), "t": int(m.group(7))})
        m = re.search(r"bench-seek video=(\S+) from=(\d+) to=(\d+)", ln)
        if m and m.group(1) == video:
            res["seek"] = {"from": int(m.group(2)), "to": int(m.group(3))}
        # The readiness gate (pre-roll wait) and the autoplay budget; NetPath context carries no
        # video id on the loader thread, so these are per open, not per video.
        m = re.search(r" (readiness-(?:wait|retry|served)|recovery-deferred reason=readiness) ?(.*)", ln)
        if m:
            res["readiness"].append(m.group(1).split()[0] + " " + m.group(2)[:80])
        if "bot-check cooldown video=" + video in ln:
            res["bot_cooldown"] = True
        if "bot-check trip" in ln:
            res["bot_trip"] = True
        if "player-ring anon-tizen next" in ln:
            res["anon_tizen_next"] = True
        m = re.search(r"autoplay-stop (.*)", ln)
        if m:
            res["autoplay_stop"] = m.group(1)[:80]
        if "http403=y" in ln:
            res["http403"] += 1
        if "auto-reload cap hit" in ln and video in ln:
            res["auto_reload_cap"] = True
    ticks = res["ticks"]
    max_pos = max((t["pos"] for t in ticks), default=-1)
    dur = max((t["dur"] for t in ticks), default=-1)
    ended = any(t["state"] == "ENDED" for t in ticks)
    after_seek = [t for t in ticks if res["seek"] and t["t"] > 0 and t["pos"] > res["seek"]["to"] + 5000]
    res["max_pos_ms"] = max_pos
    res["dur_ms"] = dur
    res["ended"] = ended
    res["played_after_seek"] = bool(after_seek)
    # An error the app recovered from (recovery walk, re-mint): playing ticks advanced after it.
    after_error = ticks[res["errors"][-1]["ticks_before"]:] if res["errors"] else []
    playing_after = [t for t in after_error if t["playing"] == "y" and t["state"] == "READY"]
    res["recovered"] = bool(res["errors"]) and (ended or (
        len(playing_after) >= 2 and playing_after[-1]["pos"] > playing_after[0]["pos"]))
    if res["first_frame_ms"] is None:
        verdict = "NO-START"
    elif res["errors"] and not res["recovered"]:
        verdict = f"FAIL@{max(0, max_pos) // 1000}s"
    elif res["errors"]:
        verdict = f"RECOVERED@{max(0, max_pos) // 1000}s"
    elif ended or (res["seek"] and after_seek) or max_pos >= 140_000:
        verdict = "PLAY-OK"
    elif window_s and max_pos >= window_s * 1000:
        verdict = "PLAY-OK"
    elif max_pos >= 60_000:
        verdict = f"PARTIAL@{max_pos // 1000}s"
    else:
        verdict = f"STALL@{max(0, max_pos) // 1000}s"
    res["verdict"] = verdict
    res.update(phases(lines, video))
    res.update(soak(lines, video))
    return res


# Long-play fields (r11 soak, --play-s 300 --seek none). Ticks come every 10 s.
RE_RESUME = re.compile(r"video=(\S+) resume-seek target=(\d+)")
RE_RECOVERY = re.compile(r"video=(\S+) recovery-(error|source|action|capped)\b(.*)")
RE_TICK = re.compile(r"bench-tick video=(\S+) pos=(-?\d+) dur=(-?\d+) buf=(-?\d+) state=(\S+) playing=(\S) t=(\d+)")
STALL_ADVANCE_MS = 5000  # a 10 s tick that moved less than this is a stall (unless the video ended)


def soak(lines, video):
    """Where playback started and how far it got without a break.

    start_pos_ms: the resume position (the first resume-seek before the first frame; 0 without one).
    continuous_ms: seconds of playback from the start to the first break (an error, or a tick that
    moved under 5 s while not ENDED), or to the end of the cell when nothing broke: the lead before
    the first tick, plus each tick's forward advance (an in-app jump such as a SponsorBlock skip is
    not played time), plus the stretch from the last tick to the error. A live stream counts from its
    first tick. first_stop_pos_ms: the position of that break. played_ms: all forward tick-to-tick
    advance, breaks included (seeks and reload rewinds excluded). jumps: forward jumps over 15 s. stall_ticks /
    buffering_ticks: ticks that moved under 5 s / ticks in BUFFERING. paused_ticks: READY but not
    playing (a pause, e.g. audio focus lost to a call: not a network stall). routes: the video's answers in
    order (player-transform clients). recoveries: one entry per media error: position, http403,
    action, capped, and the client before and after. pot_*: web PO token activity in this open.
    visitor: the anonymous visitor's fingerprint on the video's first /player request (the
    `visitor=` hash NetPath logs; a pm clear makes a new one). wall_media_ms: the furthest media
    position buffered (tick pos + buf) before the first error: the 403 surfaces after ExoPlayer's
    retries, so the error's own pos understates where the served media ended (a lower bound, ticks
    are 10 s apart).
    """
    out = {"start_pos_ms": 0, "continuous_ms": None, "first_stop_pos_ms": None, "played_ms": 0,
           "stall_ticks": 0, "buffering_ticks": 0, "paused_ticks": 0, "jumps": 0, "routes": [], "recoveries": [],
           "pot_mints": 0, "pot_generators": [], "pot_challenges": [], "player_pot_requests": 0,
           "visitor": None, "wall_media_ms": None}
    buffered_end = None
    ff = False
    resumed = False
    prev = None
    first = None
    stop = None
    rec = None
    live = False
    before_stop = 0  # played advance until the first break
    for ln in lines:
        if "NetPath" not in ln:
            continue
        m = RE_RESUME.search(ln)
        if m and m.group(1) == video and not ff and not resumed:
            out["start_pos_ms"] = int(m.group(2))
            resumed = True
        if re.search(r"video=" + re.escape(video) + r" first-frame \+", ln):
            ff = True
        m = re.search(r"player-transform video=(\S+) client=(\S+)", ln)
        if m and m.group(1) == video:
            if not out["routes"] or out["routes"][-1] != m.group(2):
                out["routes"].append(m.group(2))
            if rec is not None and rec["after"] is None:
                rec["after"] = m.group(2)
        if re.search(r"video=" + re.escape(video) + r" live=y", ln) or \
                re.search(r"video=" + re.escape(video) + r" info \+\d+ .*live=y", ln):
            live = True
        m = RE_RECOVERY.search(ln)
        if m and m.group(1) == video:
            kind, rest = m.group(2), m.group(3)
            if kind == "error":
                if not out["recoveries"]:
                    out["wall_media_ms"] = buffered_end
                p = re.search(r" pos=(-?\d+)", rest)
                rec = {"pos_ms": int(p.group(1)) if p else None, "http403": None, "action": None,
                       "capped": False, "before": out["routes"][-1] if out["routes"] else None,
                       "after": None}
                out["recoveries"].append(rec)
                if stop is None:
                    stop = rec["pos_ms"] if rec["pos_ms"] is not None else (prev["pos"] if prev else 0)
                    if prev is not None and 0 < stop - prev["pos"] <= 15_000:
                        before_stop += stop - prev["pos"]
            elif rec is not None and kind == "source":
                rec["http403"] = "http403=y" in rest
            elif rec is not None and kind == "action":
                a = re.search(r"action=(\S+)", rest)
                rec["action"] = a.group(1) if a else None
            elif rec is not None and kind == "capped":
                rec["capped"] = True
        if "auto-reload cap hit" in ln and video in ln and rec is not None:
            rec["capped"] = True
        if "web-pot-mint" in ln:
            out["pot_mints"] += 1
        if "web-pot-session" in ln:
            for key, field in (("generator", "pot_generators"), ("challenge", "pot_challenges")):
                g = re.search(key + r"=(\S+)", ln)
                if g and g.group(1) not in out[field]:
                    out[field].append(g.group(1))
        if "player-http[S]" in ln and ("video=" + video + " ") in ln:
            if " pot=y" in ln:
                out["player_pot_requests"] += 1
            vis = re.search(r" visitor=(\w+)", ln)
            if vis and out["visitor"] is None:
                out["visitor"] = vis.group(1)
        m = RE_TICK.search(ln)
        if m and m.group(1) == video:
            t = {"pos": int(m.group(2)), "dur": int(m.group(3)), "state": m.group(5), "t": int(m.group(7))}
            if t["state"] == "READY" and m.group(6) == "n":
                out["paused_ticks"] += 1  # READY but not playing: paused (e.g. audio focus lost to a call)
            if not out["recoveries"] and int(m.group(4)) >= 0:
                buffered_end = max(buffered_end or 0, t["pos"] + int(m.group(4)))
            if t["state"] == "BUFFERING":
                out["buffering_ticks"] += 1
            if first is None:
                first = t
            if prev is not None:
                d = t["pos"] - prev["pos"]
                dt = t["t"] - prev["t"] if t["t"] > prev["t"] else 10_000
                if 0 < d <= dt + 5000:
                    out["played_ms"] += d
                    if stop is None:
                        before_stop += d
                elif d > dt + 5000:
                    out["jumps"] += 1
                if t["state"] != "ENDED" and prev["state"] != "ENDED" and d < STALL_ADVANCE_MS:
                    out["stall_ticks"] += 1
                    if stop is None:
                        stop = prev["pos"]
            prev = t
    if first is not None:
        # A live stream's position is inside its DVR window: count from the first tick.
        base = first["pos"] if (live or (not resumed and first["pos"] > 60_000)) else out["start_pos_ms"]
        lead = first["pos"] - base if 0 <= first["pos"] - base <= 30_000 else 0
        if stop is not None and stop < first["pos"]:
            lead = max(0, stop - base)  # broke before the first tick
        out["continuous_ms"] = lead + before_stop
        out["first_stop_pos_ms"] = stop
    return out


# Open phases (ttff-analysis 2026-09-29, section 6). "+X" values are the app's own ms since the tap
# (the NetPath open context); *_done / answer values come from logcat timestamps minus the tap line's,
# which is the same origin. Everything after the video's first frame is ignored except the two
# milestones that follow it (ready, picture-visible).
LOGCAT_TS = re.compile(r"^(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d{3}) ")
V8_RUN = re.compile(r"v8-run reused=(\S) initMs=(\d+) solveMs=(\d+)")
WARM = re.compile(r"NetPath[^:]*: warm(-failed)? (\S+) \+(\d+)ms(.*)")
WARM_METRICS = re.compile(r" dns=(-?\d+) connect=(-?\d+) ssl=(-?\d+) wait=(-?\d+) reused=(\S) proto=(\S+)")


def logcat_ms(line):
    """Milliseconds of a `logcat -v time` line (month/day/time; no year in the format)."""
    m = LOGCAT_TS.match(line)
    if not m:
        return None
    mo, d, h, mi, s, ms = (int(g) for g in m.groups())
    return ((((mo * 31 + d) * 24 + h) * 60 + mi) * 60 + s) * 1000 + ms


def phases(lines, video):
    """Per-phase fields of one open (None when the log has no such line).

    picture_visible_ms / ready_ms / mli_ms / init_done_ms: the milestones' +X. picture_lift: the
    still-lift path (ready|texture; None before it was logged). sig_ms / v8_solve_ms: the n/sig
    solve of the answer that played (the last player-sig before the first frame, and the V8 run
    right before it; None when that answer needed no V8). answer_ms / answer_client: the winning
    player-result (the client of the last player-transform before the first frame) minus the tap;
    None when that answer came before the tap (a prefetch). dec_video_ms / dec_audio_ms: codec initMs
    of the first decoder line per type (0 = a reused codec). warm_*: the first googlevideo warm
    after the tap: warm_ms is its own duration, warm_done_ms when it finished, then the Cronet
    breakdown when the build logs one. embed_identity: restored|fetched (+ embed_fetch_ms).
    """
    vid = re.escape(video)
    ms_line = lambda name: re.compile(r"video=" + vid + r" " + name + r" \+(\d+)")
    re_tap = re.compile(r"ep=\d+ video=" + vid + r" tap\b")
    re_ff = ms_line("first-frame")
    re_pv = re.compile(r"video=" + vid + r" picture-visible \+(\d+)(?:.*? lift=(\S+))?")
    re_ready = ms_line("ready")
    re_mli = ms_line("media-load init")
    re_init = ms_line("media-init done")
    re_dec = re.compile(r"video=" + vid + r" decoder (init|reuse) \+\d+ type=(\S+)(?: .*?initMs=(\d+))?")
    re_sig = re.compile(r"player-sig video=" + vid + r" .*\bms=(\d+)")
    re_result = re.compile(r"player-result video=" + vid + r" client=(\S+) attempt=\d+ status=\S+ playable=(\S)")
    re_transform = re.compile(r"player-transform video=" + vid + r" client=(\S+)")
    re_embed = re.compile(r"embed-identity source=(\S+)(?: ms=(\d+))?")
    out = {"picture_visible_ms": None, "picture_lift": None, "ready_ms": None, "sig_ms": None,
           "v8_solve_ms": None, "answer_ms": None, "answer_client": None, "mli_ms": None,
           "init_done_ms": None, "dec_video_ms": None, "dec_audio_ms": None, "warm_ms": None,
           "warm_host": None, "warm_ok": None, "warm_done_ms": None, "warm_dns_ms": None,
           "warm_connect_ms": None, "warm_ssl_ms": None, "warm_wait_ms": None, "warm_reused": None,
           "warm_proto": None, "embed_identity": None, "embed_fetch_ms": None}
    tap = None
    ff = False
    pending_v8 = None
    playable_at = {}
    answer_at = None
    for ln in lines:
        if "NetPath" not in ln:
            continue
        ln = ln.replace("\x00", "")  # logcat dumps carry NUL bytes
        if tap is None:
            if re_tap.search(ln):
                tap = logcat_ms(ln)
            continue  # nothing before this video's tap belongs to its open
        m = re_pv.search(ln)
        if m and out["picture_visible_ms"] is None:
            out["picture_visible_ms"] = int(m.group(1))
            out["picture_lift"] = m.group(2)
        m = re_ready.search(ln)
        if m and out["ready_ms"] is None:
            out["ready_ms"] = int(m.group(1))
        if ff:
            continue
        if re_ff.search(ln):
            ff = True
            continue
        m = V8_RUN.search(ln)
        if m:
            pending_v8 = int(m.group(3))
        m = re_sig.search(ln)
        if m:
            out["sig_ms"] = int(m.group(1))
            out["v8_solve_ms"] = pending_v8
            pending_v8 = None
        m = re_result.search(ln)
        if m and m.group(2) == "y":
            playable_at[m.group(1)] = logcat_ms(ln)
        m = re_transform.search(ln)
        if m and m.group(1) in playable_at:
            out["answer_client"] = m.group(1)
            answer_at = playable_at[m.group(1)]
        m = re_mli.search(ln)
        if m and out["mli_ms"] is None:
            out["mli_ms"] = int(m.group(1))
        m = re_init.search(ln)
        if m:
            out["init_done_ms"] = max(out["init_done_ms"] or 0, int(m.group(1)))
        m = re_dec.search(ln)
        if m:
            key = "dec_" + m.group(2) + "_ms"
            if key in out and out[key] is None:
                out[key] = int(m.group(3)) if m.group(1) == "init" and m.group(3) else 0
        m = WARM.search(ln)
        if m and out["warm_ms"] is None:
            out["warm_ok"] = m.group(1) is None
            out["warm_host"] = m.group(2)
            out["warm_ms"] = int(m.group(3))
            at = logcat_ms(ln)
            out["warm_done_ms"] = at - tap if at is not None and tap is not None else None
            mm = WARM_METRICS.search(m.group(4))
            if mm:
                out.update({"warm_dns_ms": int(mm.group(1)), "warm_connect_ms": int(mm.group(2)),
                            "warm_ssl_ms": int(mm.group(3)), "warm_wait_ms": int(mm.group(4)),
                            "warm_reused": mm.group(5) == "y", "warm_proto": mm.group(6)})
        m = re_embed.search(ln)
        if m and out["embed_identity"] is None:
            out["embed_identity"] = m.group(1)
            out["embed_fetch_ms"] = int(m.group(2)) if m.group(2) else None
    if answer_at is not None and tap is not None:
        out["answer_ms"] = answer_at - tap
    return out


def settled(lines, video, window_s):
    """A --settle cell is over once the watched video played window_s seconds, or once the walk
    ended without media (a refusal) and the app moved on or 5 s passed: the decision
    is in the log, and the rest of a long cell is either more of the same playback or autoplay."""
    res = parse(lines, video, window_s)
    if res["max_pos_ms"] >= window_s * 1000 or res["ended"]:
        return True
    if res["errors"] and not res["recovered"]:
        return False  # a recovery may still be running: wait for it or the deadline
    info = res["info"]
    if info and "dash=0 hls=n" in info["detail"] and res["first_frame_ms"] is None:
        # Another open after the refusal (autoplay), or 5 s since the refusal was first seen.
        other = re.compile(r"video=(?!" + re.escape(video) + r")\S+ open \+")
        after = False
        for ln in lines:
            if re.search(r"video=" + re.escape(video) + r" info \+", ln):
                after = True
            elif after and other.search(ln):
                return True
        return time.time() - SETTLE_INFO_SEEN.setdefault(video, time.time()) >= 5
    return False


SETTLE_INFO_SEEN = {}


def reparse(paths):
    """Offline: print parse() for saved per-open logs (<run>.<SOURCE>.<video>.t<N>.log). No adb."""
    for path in paths:
        m = re.match(r"(.+?)\.([A-Z_]+)\.([\w-]{11})\.t(\d+)\.log$", os.path.basename(path))
        if not m:
            print(f"skip {path}: not a per-open log name", file=sys.stderr)
            continue
        with open(path, errors="replace") as fh:
            lines = fh.read().replace("\x00", "").splitlines()
        row = {"file": os.path.basename(path), "run_id": m.group(1), "source": m.group(2),
               "video": m.group(3), "trial": int(m.group(4)), **parse(lines, m.group(3))}
        print(json.dumps(row))


def wait_for_guard(max_wait_s=120):
    """A heads-up notification or a glance at the phone fails the guard for a few seconds: wait it
    out (never act meanwhile) and stop only if it keeps failing."""
    deadline = time.time() + max_wait_s
    ok, why = guard("start")
    while not ok and time.time() < deadline:
        time.sleep(5)
        ok, why = guard("start")
    return ok, why


def run_cell(args, source, video, trial, out):
    stopped = host_stopped()
    if stopped:
        return {"stop": f"host STOP ({host_stop_path()}): {stopped}"}
    level = battery_level()
    if level is not None and level < args.min_battery:
        return {"stop": f"battery {level}% < {args.min_battery}%"}
    loud = 0
    while True:
        ok, why = wait_for_guard()
        if not ok:
            return {"stop": f"guard before open: {why}"}
        with sender_turn() as turn:
            # The turn may have taken a while: the other phone may have met a wall, and this phone
            # may have been picked up. Check both again right before the intent.
            stopped = host_stopped()
            if stopped:
                return {"stop": f"host STOP ({host_stop_path()}): {stopped}"}
            ok, why = guard("start")
            if ok and ALLOW_CALL and call_active() is not False and not zero_media_volume():
                ok, why = False, "in a call and the media stream would not read 0 on the active output"
                loud += 1
                if loud >= 30:
                    return {"stop": why}
            if ok:
                return open_cell(args, source, video, trial, out, turn)
        time.sleep(5)


def open_cell(args, source, video, trial, out, turn):
    setprop("debug.arc.player_client", None if source == "RING" else source)
    setprop("debug.arc.support_xhr", None if args.support_xhr == "none" else args.support_xhr)
    setprop("debug.arc.anon_tizen", "1" if args.anon_tizen else None)
    for key, value in extra_props(args).items():
        setprop(key, value)
    setprop("debug.arc.bench", "1")
    setprop("debug.arc.bench_seek", args.seek)
    # --keep-process: one clean start, then every open lands in the running app like a user's next
    # tap (in-process state such as the bot-check circuit carries over; props apply at start only).
    if not args.keep_process or not KEPT_STARTED[0]:
        shell(f"am force-stop {PKG}")
        KEPT_STARTED[0] = True
    if args.pm_clear:
        # A fresh install's first open: no cached player, visitor, embed identity or format cache.
        shell(f"pm clear {PKG}")
        shell(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
    mark = device_time()
    raw = os.path.join(RESULTS, f"{args.run_id}.{source}.{video}.t{trial}.log")
    # Stream the log while the cell runs: the phone's buffer rolls over within minutes (an
    # autoplay storm fills it fastest), so a dump at the end lost the start of the open.
    raw_fh = open(raw, "w")
    logcat = subprocess.Popen(["adb", "-s", SERIAL, "logcat", "-v", "time", "-T", mark, "-s", "NetPath"],
                              stdout=raw_fh, stderr=subprocess.DEVNULL)
    global CURRENT_LOGCAT
    CURRENT_LOGCAT = logcat
    t0 = time.time()
    shell(f"am start -p {PKG} -a android.intent.action.VIEW -d https://youtu.be/{video}")
    aborted = None
    # Settle cells end early when they can, so their deadline can afford a slow walk or an ad.
    deadline = t0 + args.play_s + (40 if args.settle else 20)
    SETTLE_INFO_SEEN.pop(video, None)
    # The focus is checked from 6 s after the intent (a cold start on a slow phone shows the launcher
    # for a few seconds); the log is read from the first second, to hand the turn on early.
    incall = call_active() is not False
    polls = 0
    time.sleep(1)
    while time.time() < deadline:
        polls += 1
        if not incall and polls % 5 == 0 and call_active():
            incall = True
        if time.time() - t0 >= 6:
            f = focus()
            if PKG + "/" not in f:
                aborted = f"focus left the check app: {f}"
                break
        with open(raw, errors="replace") as fh:
            lines = fh.read().replace("\x00", "").splitlines()
        if turn.held and (walk_decided(lines, video) or time.time() - t0 >= WALK_TURN_MAX_S):
            turn.release()
        if args.settle and settled(lines, video, args.play_s):
            break
        time.sleep(1 if turn.held else 2 if args.settle else 10)
    if not args.keep_process:
        shell(f"am force-stop {PKG}")
    time.sleep(1)
    logcat.terminate()
    try:
        logcat.wait(timeout=10)
    except subprocess.TimeoutExpired:
        logcat.kill()
    raw_fh.close()
    with open(raw, errors="replace") as fh:
        lines = fh.read().splitlines()
    turn.release()
    incall = incall or call_active() is not False
    res = parse(lines, video, args.play_s if args.settle else None)
    wall = bot_signal(lines)
    if wall:
        stop_host(f"{args.run_id} {video}: {wall}")
        aborted = aborted or f"bot signal: {wall}"
    if args.settle:
        res["window_s"] = args.play_s
        res["cell_s"] = round(time.time() - t0, 1)
    row = {"ts": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "run_id": args.run_id, "network": args.network,
           "source": source, "support_xhr": args.support_xhr, "anon_tizen": bool(args.anon_tizen), "props": extra_props(args), "video": video, "trial": trial,
           "aborted": aborted, "incall": incall, **res}
    out.write(json.dumps(row) + "\n")
    out.flush()
    tried = ",".join(r["client"] + ":" + (("OK" if r["playable"] == "y" else r["status"])) for r in res["results"])
    print(f"[{source:14}] {video} t{trial} {row['verdict']:14} winner={res['winner']} "
          f"prepare={res['prepare'] and res['prepare']['type']} ff={res['first_frame_ms']} "
          f"maxPos={res['max_pos_ms']//1000 if res['max_pos_ms']>=0 else -1}s "
          f"seek={'y' if res['seek'] else 'n'} errors={len(res['errors'])} tried={tried}"
          + (f" readiness=[{'; '.join(res['readiness'][:4])}]" if res['readiness'] else "")
          + (f" autoplay-stop={res['autoplay_stop']}" if res['autoplay_stop'] else "")
          + (" BOT-TRIP" if res.get('bot_trip') else "") + (" BOT-COOLDOWN" if res.get('bot_cooldown') else "")
          + (" INCALL" if incall else "") + (f" ABORTED({aborted})" if aborted else ""), flush=True)
    return {"stop": aborted}


def _stop(signum, frame):
    # Background jobs start with SIGINT ignored; install handlers so a stop always reaches the
    # finally block below (force-stop, clear properties, restore volume).
    raise KeyboardInterrupt(f"signal {signum}")


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "--reparse":
        # Saved logs only: appbench.py --reparse results/<run>.*.log > rows.jsonl
        reparse(sys.argv[2:])
        return
    signal.signal(signal.SIGINT, _stop)
    signal.signal(signal.SIGTERM, _stop)
    ap = argparse.ArgumentParser()
    ap.add_argument("--network", required=True)
    ap.add_argument("--sources", required=True, help="AppClient names, or RING for the natural walk")
    ap.add_argument("--videos", required=True)
    ap.add_argument("--support-xhr", default="none")
    ap.add_argument("--keep-process", action="store_true",
                    help="do not restart the app between cells (state carries over, like a user)")
    ap.add_argument("--pm-clear", action="store_true",
                    help="clear the app's data before every open (a fresh install's first open: the "
                         "player-JS gate A/B). Refused for the signed-in .auth build")
    ap.add_argument("--anon-tizen", action="store_true",
                    help="planner switch: TV_TIZEN without the account right after a refusal")
    ap.add_argument("--prop", action="append", default=[],
                    help="extra debug switch for the app, KEY=VALUE (e.g. debug.arc.hls_vod=1); "
                         "repeatable, cleared at the end")
    ap.add_argument("--repeat", type=int, default=1)
    ap.add_argument("--play-s", type=int, default=150)
    ap.add_argument("--seek", default="90:0.7")
    ap.add_argument("--settle", action="store_true",
                    help="decision cells: end each open once the video played --play-s seconds or "
                         "the walk settled a refusal (--play-s 15: ticks come every 10 s)")
    ap.add_argument("--run-id", required=True)
    ap.add_argument("--sender-lock", default=os.environ.get("NETBENCH_SENDER_LOCK") or DEFAULT_SENDER_LOCK,
                    help="host-wide lock held per open, so runs on one network take turns "
                         f"(default: {DEFAULT_SENDER_LOCK}; 'none' = off)")
    ap.add_argument("--allow-call", action="store_true",
                    default=os.environ.get("NETBENCH_ALLOW_CALL") == "1",
                    help="the owner's in-call rule: an active call does not stop opens (the guard's "
                         "GUARD_ALLOW_CALL), the media stream on the active output must read 0 first; "
                         "default: $NETBENCH_ALLOW_CALL=1. Never for LTE runs")
    ap.add_argument("--min-battery", type=int, default=8,
                    help="stop the run when the phone's battery is under this percent")
    ap.add_argument("--serial", default=os.environ.get("NETBENCH_SERIAL"),
                    help="adb serial of the phone (default: $NETBENCH_SERIAL; required; guarded "
                         "unless it is an emulator-* serial)")
    ap.add_argument("--package", default=PKG,
                    help="the app to drive (default: the signed-out .check build; .auth is the "
                         "signed-in benchmark build, -PsideBySide=auth)")
    ap.add_argument("--data", default=os.environ.get("NETBENCH_DATA") or DEFAULT_DATA,
                    help="data directory; results go to <data>/appbench/results "
                         "(default: $NETBENCH_DATA, else the tools/netbench directory)")
    args = ap.parse_args()
    global SERIAL, NO_GUARD, RESULTS, SENDER_LOCK, ALLOW_CALL
    globals()["PKG"] = args.package  # read as a default above, so not in the global list
    if not args.serial:
        ap.error("no device: pass --serial or set NETBENCH_SERIAL (the phone's adb serial)")
    SERIAL = args.serial
    NO_GUARD = SERIAL.startswith("emulator-")
    ALLOW_CALL = bool(args.allow_call)
    if ALLOW_CALL and args.network != "wifi":
        ap.error("--allow-call is for Wi-Fi runs only: LTE cells wait for the call to end")
    SENDER_LOCK = None if args.sender_lock == "none" else args.sender_lock
    RESULTS = os.path.join(args.data, "appbench", "results")
    os.makedirs(RESULTS, exist_ok=True)
    if args.pm_clear and (args.keep_process or PKG.endswith(".auth")):
        ap.error("--pm-clear clears the app: never the signed-in .auth build, never with --keep-process")
    if PKG not in shell(f"pm list packages {PKG}"):
        sys.exit(f"{PKG} is not installed")
    vol = media_volume()
    print(f"appbench {args.run_id}: network={args.network} media volume was {vol} on {audio_device()}"
          + (" (in-call rule on)" if ALLOW_CALL else ""), flush=True)
    zero_media_volume()
    stopped = None
    try:
        with open(os.path.join(RESULTS, args.run_id + ".jsonl"), "a") as out:
            for trial in range(1, args.repeat + 1):
                for video in args.videos.split(","):
                    for source in args.sources.split(","):
                        r = run_cell(args, source.strip(), video.strip(), trial, out)
                        if r.get("stop"):
                            stopped = r["stop"]
                            break
                    if stopped:
                        break
                if stopped:
                    break
    finally:
        # A second TERM (a queue stopping right after this run's last cell) must not cut the cleanup:
        # it once left bench, bench_seek and a --prop set on the phone (2026-09-29 18:26).
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        if CURRENT_LOGCAT is not None and CURRENT_LOGCAT.poll() is None:
            CURRENT_LOGCAT.kill()
        shell(f"am force-stop {PKG}")
        for key in ("debug.arc.player_client", "debug.arc.support_xhr", "debug.arc.anon_tizen",
                    "debug.arc.bench", "debug.arc.bench_seek", *extra_props(args)):
            setprop(key, None)
        # Each output this run set to 0 gets its own prior index back, if it is the active output
        # now (the shell sets only the active one; an output that went away keeps 0 and is named).
        # Re-read and retried: a muted volume group ignores setStreamVolume from the shell
        # (Android 15). Correction: on the Mi 8 (2026-09-29) a restore did take; the 0 the next
        # run read was the owner holding volume-down (dumpsys audio: adjustSuggestedStreamVolume
        # ADJUST_LOWER from the key). A run keeps whatever the owner set: never "fix" a 0.
        notes = []
        active = audio_device() if ZEROED else None
        for dev, want in ZEROED.items():
            if dev != active:
                notes.append(f"{dev} NOT restored to {want} (not the active output now: {active})")
                continue
            now = None
            for _ in range(3):
                set_media_volume(want)
                time.sleep(0.5)
                now = media_volume()
                if now == want:
                    break
            notes.append(f"{dev} restored to {now}" + (f" (WANTED {want})" if now != want else ""))
        print(f"appbench {args.run_id}: done" + (f", STOPPED: {stopped}" if stopped else "")
              + "; media volume " + ("; ".join(notes) if notes else f"untouched (was {vol})"),
              flush=True)
    if stopped:
        sys.exit(2)  # a stop ends the whole sequence (the wrapper then restores Wi-Fi)


if __name__ == "__main__":
    main()
