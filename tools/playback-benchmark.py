#!/usr/bin/env python3
"""Pixel playback comparison: private, sanitized evidence; no account/cache resets.

All shell arguments are quoted for Android's second shell. CPU is process time
as a percentage of one core. Network counters cover the app UID, including API
and artwork traffic; PSS is the main app process, not isolated codec processes.
Run the same matrix on each APK with media_cache=off to compare network opens.
launch_to_frame_ms includes app startup and host log-receipt delay; ttff_ms is
the app's tap-to-frame interval. Positive t=1s prevents saved-history resume.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import statistics
import subprocess
import sys
import threading
import time


class PlaybackUnavailable(RuntimeError):
    """A failed phase must not turn a matrix into repeated server-denial requests."""


def exact_package_uid(packages, package):
    """pm's filter is a prefix search and can put .test/.benchmark before the actual app."""
    match = re.search(r'^package:' + re.escape(package) + r'\s+uid:(\d+)\s*$', packages, re.M)
    if not match:
        raise RuntimeError('Cannot resolve the exact target package UID')
    return int(match[1])


def playback_milestones(lines):
    """Match frame/visibility evidence within each open, never across recovery episodes.

    Callers supply only the requested video's lines. An error can also lift a
    loading still, so that event alone is not evidence that a picture appeared.
    Missing episode IDs remain supported for old logs, with each open still a
    hard boundary. A new open supersedes an older terminal-recovery verdict.
    """
    result = dict(frames=[], visible_line=None, visible_kind=None, recovery_capped=False)
    opened = frame_seen = failed = False
    current_episode = None
    visible_line = visible_kind = None

    def retain_visibility():
        if result['visible_line'] is None and visible_line is not None:
            result['visible_line'] = visible_line
            result['visible_kind'] = visible_kind

    for line in lines:
        episode_match = re.search(r'\bep=(\d+)\b', line)
        episode = episode_match[1] if episode_match else None
        if ' open +' in line:
            retain_visibility()
            opened, frame_seen, failed = True, False, False
            current_episode = episode
            visible_line = visible_kind = None
            result['recovery_capped'] = False
            continue
        if not opened or episode != current_episode:
            continue
        if re.search(r'\brecovery-capped connectivity=n\b', line):
            # This means a NON-connectivity error exhausted automatic recovery,
            # not that the network is offline. connectivity=y can recover later.
            result['recovery_capped'] = True
        if ' error +' in line:
            failed = True
        if failed:
            continue
        if ' first-frame +' in line:
            frame_seen = True
            result['frames'].append(line)
        if not frame_seen:
            continue
        if ' picture-visible +' in line:
            if visible_kind != 'overlay-gone':
                visible_line, visible_kind = line, 'overlay-gone'
        elif visible_line is None and ' image-hold off why=still-lifted' in line:
            visible_line, visible_kind = line, 'legacy-fade-start'

    retain_visibility()
    return result


class Benchmark:
    def __init__(self, args):
        self.args = args
        self.pkg = args.package
        self.cmd = ['adb', '-s', args.serial]
        self.out = Path(args.output)
        self.out.mkdir(mode=0o700, parents=True, exist_ok=False)
        self.lines = []
        self.received_times = []
        self.results = []
        self.hz = int(self.shell('getconf', 'CLK_TCK').strip())
        packages = self.shell('pm', 'list', 'packages', '-U', self.pkg)
        self.uid = exact_package_uid(packages, self.pkg)
        self.logfile = (self.out / 'playback.log').open('w')
        self.logger = subprocess.Popen(self.cmd + [
            'logcat', '-T', '1', '-v', 'epoch', 'NetPath:D', 'EventLogger:D',
            'SessionWarmup:D', 'AndroidRuntime:E', '*:S'], stdout=subprocess.PIPE)
        self.reader = threading.Thread(target=self.read_logs, daemon=True)
        self.reader.start()

    def adb(self, *args):
        return subprocess.check_output(self.cmd + list(args), timeout=30).decode(errors='replace')

    def shell(self, *args):
        return self.adb('shell', shlex.join(args))

    def read_logs(self):
        for raw in self.logger.stdout:
            received = time.monotonic()
            line = raw.decode(errors='replace').replace('\x00', '').rstrip()
            line = re.sub(r'https?://\S+', '<url>', line)
            line = re.sub(r'[A-Za-z0-9_+/=%-]{65,}', '<opaque>', line)
            self.received_times.append(received)
            self.lines.append(line)
            self.logfile.write(line + '\n')
            self.logfile.flush()

    def require_focus(self):
        focus = re.search(r'mCurrentFocus=.*', self.shell('dumpsys', 'window'))
        if not focus or self.pkg not in focus.group():
            raise RuntimeError('App lost foreground; stopping device input')

    def reject_pip(self):
        activities = self.shell('dumpsys', 'activity', 'activities')
        if re.search(r'\b(?:mode=pinned|windowingMode=2)\b', activities, re.I):
            raise RuntimeError('A pinned picture-in-picture task is visible; stopping benchmark')

    def stats(self):
        result = {'host_monotonic': time.monotonic()}
        try:
            pid = self.shell('pidof', self.pkg).strip()
        except subprocess.CalledProcessError as error:
            if error.returncode != 1:
                raise
            pid = ''
        if pid and pid.isdigit():
            stat = self.shell('cat', '/proc/' + pid + '/stat').rsplit(')', 1)[1].split()
            result.update(pid=int(pid), cpu_ticks=int(stat[11]) + int(stat[12]),
                          threads=int(stat[17]))
            mem = self.shell('dumpsys', 'meminfo', self.pkg)
            for key, pattern in {
                'pss_kb': r'TOTAL PSS:\s+(\d+)',
                'java_kb': r'Java Heap:\s+(\d+)',
                'native_kb': r'Native Heap:\s+(\d+)',
                'graphics_kb': r'Graphics:\s+(\d+)',
                'views': r'Views:\s+(\d+)',
                'activities': r'Activities:\s+(\d+)',
            }.items():
                found = re.search(pattern, mem)
                if found:
                    result[key] = int(found.group(1))
        # Pixel's cumulative eBPF app-UID counters do not wait for history bucket polling.
        net = self.shell('dumpsys', 'netstats')
        # Android also prints an earlier "mAppUidStatsMap: OK" health summary;
        # the final marker owns the actual counters. An absent marker stays empty.
        _, marker, table = net.rpartition('mAppUidStatsMap:')
        section = table.split('mStatsMapA:')[0] if marker else ''
        found = re.search(r'^\s*' + str(self.uid) + r'\s+(\d+)\s+\d+\s+(\d+)\s+\d+\s*$', section, re.M)
        if found:
            result.update(rx_bytes=int(found.group(1)), tx_bytes=int(found.group(2)))
        battery = self.shell('dumpsys', 'battery')
        found = re.search(r'temperature:\s+(\d+)', battery)
        if found:
            result['battery_c'] = int(found.group(1)) / 10
        return result

    def open_video(self, video):
        return self.shell('am', 'start', '-W', '-a', 'android.intent.action.VIEW',
                          '-d', 'https://www.youtube.com/watch?v=' + video
                          + '&t=' + str(self.args.start_seconds) + 's',
                          '-p', self.pkg)

    def phase(self, name, video, duration, cold=False, browse=False):
        self.reject_pip()
        if cold:
            self.shell('am', 'force-stop', self.pkg)
        else:
            self.require_focus()
        before = self.stats()
        start_line = len(self.lines)
        started = time.monotonic()
        browse_launch = None
        if browse:
            browse_launch = self.shell('am', 'start', '-W', '-n', self.pkg +
                                      '/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity')
            self.require_focus()
        launch = self.open_video(video)
        activity = re.search(r'^Activity:\s+(\S+)', launch, re.M)
        if 'Error:' in launch or not activity or activity[1].split('/')[0] != self.pkg:
            raise RuntimeError('Expected package did not handle playback intent')
        samples = []
        next_sample_at = 0
        while True:
            elapsed = time.monotonic() - started
            phase_lines = self.lines[start_line:]
            current = [line for line in phase_lines if 'video=' + video + ' ' in line]
            milestones = playback_milestones(current)
            has_frame = bool(milestones['frames'])
            # This is the application's final pre-media denial, not an intermediate client
            # response that might still produce a playable result through its normal flow.
            if any('bot-check autoplay=n suggestions=cancelled' in line for line in phase_lines):
                break
            if milestones['recovery_capped']:
                break
            if elapsed >= duration and (has_frame or elapsed >= 30):
                break
            # Full dumpsys probes can take seconds: keep them off the first-frame
            # path, except when playback is unresolved after eight seconds.
            if elapsed >= next_sample_at and (has_frame or elapsed >= 8):
                sample = self.stats()
                sample['elapsed_s'] = time.monotonic() - started
                samples.append(sample)
                next_sample_at = time.monotonic() - started + self.args.sample_interval
            time.sleep(0.1)
        self.require_focus()
        after = self.stats()
        captured = self.lines[start_line:]
        received = self.received_times[start_line:start_line + len(captured)]
        requested = [line for line in captured if 'video=' + video + ' ' in line]
        first_open = next((line for line in requested if ' open +' in line), None)
        if first_open:
            requested = requested[requested.index(first_open):]
        else:
            requested = []
        milestones = playback_milestones(requested)
        frame_lines = milestones['frames']
        frames = [int(v) for line in frame_lines for v in re.findall(r'first-frame \+(\d+)', line)]
        total_ttff = None
        initial_t0 = None
        for line in requested:
            stamp = re.match(r'\s*(\d+\.\d+)', line)
            if not stamp:
                continue
            offset = re.search(r' (?:open|first-frame) \+(\d+)', line)
            if initial_t0 is None and (' tap' in line or ' open +' in line):
                initial_t0 = float(stamp.group(1)) - (int(offset.group(1)) / 1000 if offset else 0)
            if initial_t0 is not None and line in frame_lines:
                total_ttff = round(1000 * (float(stamp.group(1)) - initial_t0))
                break
        result = dict(name=name, video=video, elapsed_s=round(time.monotonic() - started, 2),
                      ttff_ms=total_ttff, frame_episode_ms=frames, samples=samples,
                      launch_host_monotonic=started, am_start_output=launch,
                      browse_am_start_output=browse_launch, requested_start_seconds=self.args.start_seconds,
                      ttff_status='ok' if total_ttff is not None else ('no_first_frame' if first_open else 'no_new_open'),
                      bot_check_denied=any('bot-check autoplay=n suggestions=cancelled' in line
                                           for line in captured),
                      recovery_capped=milestones['recovery_capped'],
                      errors=sum(' error +' in line for line in requested),
                      load_errors=sum(' load[E]' in line for line in requested),
                      tracks=[line.split('track-selected ')[1] for line in requested if 'track-selected ' in line],
                      states=[line for line in captured if 'state [' in line or 'droppedFrames [' in line],
                      before=before, after=after)
        frame = frame_lines[0] if frame_lines else None
        result['launch_to_frame_ms'] = round(1000 * (received[captured.index(frame)] - started)) if frame else None
        # A decoded frame may still be covered by the loading thumbnail until READY.
        # New builds report the overlay actually GONE; older builds only logged fade start.
        # Both require a first frame in that same open, before any intervening error.
        lifted = milestones['visible_line']
        result['visible_frame_kind'] = milestones['visible_kind']
        lift_stamp = re.match(r'\s*(\d+\.\d+)', lifted) if lifted else None
        result['visible_frame_ms'] = round(1000 * (float(lift_stamp[1]) - initial_t0)) if lift_stamp and initial_t0 else None
        render = next((line for line in captured if 'renderedFirstFrame [' in line), None)
        position = re.search(r'mediaPos=([\d.]+)', render) if render else None
        result['first_frame_position_s'] = float(position[1]) if position else None
        result['first_frame_event'] = render
        result['initial_tracks'] = result['tracks'][:2]
        result['first_frame_state'] = next((line for line in result['states'] if 'READY]' in line), None)
        for key in ('rx_bytes', 'tx_bytes'):
            if key in before and key in after:
                result[key] = after[key] - before[key]
        if 'cpu_ticks' in after:
            ticks_before = before.get('cpu_ticks', 0) if before.get('pid') == after.get('pid') else 0
            result['cpu_one_core_pct'] = round((after['cpu_ticks'] - ticks_before) / self.hz /
                                                (after['host_monotonic'] - before['host_monotonic']) * 100, 2)
        stable = [sample for sample in samples if sample['elapsed_s'] >= 30 and 'cpu_ticks' in sample]
        if len(stable) >= 2 and stable[0].get('pid') == stable[-1].get('pid'):
            result['steady_cpu_one_core_pct'] = round((stable[-1]['cpu_ticks'] - stable[0]['cpu_ticks']) /
                self.hz / (stable[-1]['host_monotonic'] - stable[0]['host_monotonic']) * 100, 2)
        pss_samples = [s['pss_kb'] for s in samples if 'pss_kb' in s]
        result['median_pss_mb'] = round(statistics.median(pss_samples) / 1024, 1) if pss_samples else None
        self.results.append(result)
        (self.out / 'results.json').write_text(json.dumps(self.results, indent=2) + '\n')
        print(json.dumps({key: value for key, value in result.items()
                          if key not in ('samples', 'before', 'after', 'states', 'tracks',
                                         'am_start_output', 'browse_am_start_output',
                                         'first_frame_event', 'first_frame_state')}), flush=True)
        if result['bot_check_denied'] or result['recovery_capped'] or result['ttff_status'] != 'ok':
            # Force-stopping for the next cold phase would discard process-local cooldowns.
            # Preserve this failure's evidence and stop before making another video request.
            raise PlaybackUnavailable('phase ' + name + ': '
                                      + ('server bot check' if result['bot_check_denied']
                                         else 'non-connectivity recovery exhausted'
                                         if result['recovery_capped'] else result['ttff_status']))

    def run(self):
        videos = self.args.videos.split(',')
        if any(not re.fullmatch(r'[A-Za-z0-9_-]{11}', video) for video in videos):
            raise ValueError('Expected 11-character video IDs')
        if self.args.mode in ('matrix', 'cold'):
            for i, video in enumerate(videos):
                self.phase('cold-direct-' + str(i + 1), video, 14, cold=True)
            for i, video in enumerate(videos):
                self.phase('cold-browse-immediate-' + str(i + 1), video, 14, cold=True, browse=True)
            if self.args.mode == 'matrix':
                for i, video in enumerate(videos * 2):
                    self.phase('switch-' + str(i + 1), video, 14)
                self.phase('sustained', videos[0], self.args.duration)
        elif self.args.mode == 'switches':
            self.phase('switch-warmup', videos[-1], 14, cold=True)
            for i in range(6):
                self.phase('switch-' + str(i + 1), videos[i % len(videos)], 14)
        else:
            self.phase(self.args.mode, videos[0], self.args.duration, cold=True,
                       browse=self.args.mode == 'browse')

    def close(self):
        self.logger.terminate()
        self.logger.wait(timeout=10)
        self.reader.join(timeout=5)
        self.logfile.close()


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--package', default='io.github.aleixrodriala.arc')
    parser.add_argument('--output', required=True)
    parser.add_argument('--videos', default='Fo89b8zAIE4,u_vnA6nlDvs,yi0PiY1i3XU')
    parser.add_argument('--mode', choices=['matrix', 'cold', 'switches', 'soak', 'browse'], default='matrix')
    parser.add_argument('--duration', type=int, default=180)
    parser.add_argument('--start-seconds', type=int, default=1,
                        help='Positive timestamp; zero resumes saved history in this app')
    parser.add_argument('--sample-interval', type=float, default=12)
    args = parser.parse_args()
    if args.duration <= 0 or args.sample_interval <= 0 or args.start_seconds <= 0:
        parser.error('duration, sample-interval and start-seconds must be positive')
    benchmark = Benchmark(args)
    try:
        benchmark.run()
    except PlaybackUnavailable as error:
        print('Benchmark stopped; no further playback requests: ' + str(error), file=sys.stderr)
        return 2
    finally:
        benchmark.close()


if __name__ == '__main__':
    raise SystemExit(main())
