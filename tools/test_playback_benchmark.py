"""Offline regressions for playback-benchmark.py; no adb, devices, or real waits.

Run: python3 -m unittest discover -s tools -p 'test_playback_benchmark.py' -v
"""
import contextlib
import importlib.util
import io
from pathlib import Path
import shlex
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch


SPEC = importlib.util.spec_from_file_location(
    'playback_benchmark', Path(__file__).with_name('playback-benchmark.py'))
benchmark = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(benchmark)

PACKAGE = 'io.github.example.player'
VIDEO = 'ABCDEFGHIJK'


class FakeClock:
    def __init__(self):
        self.now = 100.0

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class PlaybackBenchmarkTest(unittest.TestCase):
    def test_package_uid_ignores_instrumentation_and_debug_prefix_matches(self):
        packages = ('package:io.github.example.player.benchmark uid:12001\r\n'
                    'package:io.github.example.player.test uid:12002\r\n'
                    'package:io.github.example.player.debug uid:12003\r\n'
                    'package:io.github.example.player uid:10233\r\n')
        self.assertEqual(10233, benchmark.exact_package_uid(packages, PACKAGE))

    def test_missing_exact_package_uid_fails_instead_of_reporting_other_app_traffic(self):
        with self.assertRaisesRegex(RuntimeError, 'exact target package UID'):
            benchmark.exact_package_uid('package:' + PACKAGE + '.test uid:12002\n', PACKAGE)

    def test_package_uid_escapes_dots_in_the_package_name(self):
        with self.assertRaises(RuntimeError):
            benchmark.exact_package_uid('package:ioXgithubXexampleXplayer uid:12002\n', PACKAGE)

    def setUp(self):
        # A missed mock must fail locally rather than connect to a developer's phone.
        self.addCleanup(patch.stopall)
        patch.object(benchmark.subprocess, 'check_output',
                     side_effect=AssertionError('External commands are forbidden in this test')).start()
        patch.object(benchmark.subprocess, 'Popen',
                     side_effect=AssertionError('External commands are forbidden in this test')).start()

    def make_benchmark(self):
        instance = benchmark.Benchmark.__new__(benchmark.Benchmark)
        instance.pkg = PACKAGE
        instance.uid = 10233
        instance.hz = 100
        instance.args = SimpleNamespace(start_seconds=1, sample_interval=12)
        return instance

    def test_nested_android_shell_keeps_url_and_package_in_same_command(self):
        instance = self.make_benchmark()
        instance.adb = Mock(return_value='')

        instance.open_video(VIDEO)

        adb_args = instance.adb.call_args.args
        self.assertEqual('shell', adb_args[0])
        self.assertEqual(2, len(adb_args))
        android_args = shlex.split(adb_args[1])
        self.assertEqual([
            'am', 'start', '-W', '-a', 'android.intent.action.VIEW',
            '-d', 'https://www.youtube.com/watch?v=ABCDEFGHIJK&t=1s',
            '-p', PACKAGE,
        ], android_args)
        # Splitting on shell punctuation reproduces the bug that detached '-p'
        # when an unquoted '&t=' let Android resolve the URL in another app.
        lexer = shlex.shlex(adb_args[1], posix=True, punctuation_chars=';&|')
        lexer.whitespace_split = True
        self.assertNotIn('&', list(lexer))

    def stats_for(self, netstats='', proc_stat=None):
        instance = self.make_benchmark()
        outputs = {
            ('pidof', PACKAGE): '777' if proc_stat else '',
            ('cat', '/proc/777/stat'): proc_stat,
            ('dumpsys', 'meminfo', PACKAGE): 'TOTAL PSS: 102400\nNative Heap: 14000',
            ('dumpsys', 'netstats'): netstats,
            ('dumpsys', 'battery'): 'temperature: 321',
        }
        instance.shell = Mock(side_effect=lambda *args: outputs[args])
        return instance.stats()

    def test_uid_counters_use_final_data_table_after_earlier_health_marker(self):
        stats = self.stats_for(netstats='''
mAppUidStatsMap: OK
mStatsMapA: OK
mStatsMapB: OK
mAppUidStatsMap:
  uid rxBytes rxPackets txBytes txPackets
  110233 333 22 444 11
  10233 9876543 61 123456 42
mStatsMapA:
  10233 111 1 222 2
''')

        self.assertEqual(9876543, stats['rx_bytes'])
        self.assertEqual(123456, stats['tx_bytes'])

    def test_missing_uid_table_does_not_read_unrelated_counters(self):
        stats = self.stats_for(netstats='mStatsMapA:\n  10233 111 1 222 2\n')

        self.assertNotIn('rx_bytes', stats)
        self.assertNotIn('tx_bytes', stats)

    def test_missing_exact_uid_does_not_match_uid_suffix(self):
        stats = self.stats_for(netstats='mAppUidStatsMap:\n  110233 111 1 222 2\nmStatsMapA:\n')

        self.assertNotIn('rx_bytes', stats)

    def test_proc_stat_offsets_handle_parentheses_and_exclude_child_cpu(self):
        # Fields are numbered as in proc_pid_stat(5), starting at 3 after comm.
        fields = [str(number * 11) for number in range(3, 53)]
        fields[0] = 'S'
        fields[14 - 3] = '123'  # utime
        fields[15 - 3] = '45'   # stime
        fields[16 - 3] = '9000' # cutime: never counted as this process's CPU
        fields[17 - 3] = '8000' # cstime
        fields[20 - 3] = '19'   # num_threads
        raw = '777 (main ) worker) ' + ' '.join(fields)

        stats = self.stats_for(proc_stat=raw)

        self.assertEqual(777, stats['pid'])
        self.assertEqual(168, stats['cpu_ticks'])
        self.assertEqual(19, stats['threads'])

    def phase_for(self, events, duration=0):
        instance = self.make_benchmark()
        clock = FakeClock()
        instance.lines, instance.received_times, instance.results = [], [], []
        instance.reject_pip = Mock()
        instance.require_focus = Mock()
        instance.shell = Mock(return_value='')
        instance.stats = Mock(side_effect=lambda: {
            'host_monotonic': clock.now, 'pss_kb': 102400,
        })

        def open_video(_):
            # Epoch 1000 maps to host monotonic 100, with 50ms simulated delivery
            # latency. Injection happens during launch, as a live logcat reader would.
            for epoch, tag, message in events:
                instance.lines.append(f'{epoch:.3f} 777 777 D {tag}: {message}')
                instance.received_times.append(epoch - 900 + 0.05)
            clock.now = max(instance.received_times, default=100.1)
            return f'Status: ok\nActivity: {PACKAGE}/com.example.Player\n'

        instance.open_video = open_video
        with TemporaryDirectory(prefix='newtube-benchmark-test-') as output:
            instance.out = Path(output)
            with patch.object(benchmark.time, 'monotonic', clock.monotonic), \
                    patch.object(benchmark.time, 'sleep', clock.sleep), \
                    contextlib.redirect_stdout(io.StringIO()):
                # Most cases need no soak; explicit durations check whether terminal
                # outcomes stop early. All waits advance only this fake clock.
                failure = None
                try:
                    instance.phase('unit-test', VIDEO, duration=duration, cold=True)
                except benchmark.PlaybackUnavailable as error:
                    failure = error
                result = instance.results[0]
                self.assertEqual(bool(result['bot_check_denied'] or result['recovery_capped']
                                      or result['ttff_status'] != 'ok'), failure is not None)
            self.assertTrue((instance.out / 'results.json').is_file())
        return instance.results[0]

    def test_recovery_ttff_keeps_first_open_origin_and_separates_visible_frame(self):
        result = self.phase_for([
            (1000.0, 'NetPath', f'ep=1 video={VIDEO} tap'),
            (1000.1, 'NetPath', f'ep=1 video={VIDEO} open +100 "Test"'),
            (1000.8, 'NetPath', f'ep=1 video={VIDEO} error +800 TestError'),
            (1001.2, 'NetPath', f'ep=2 video={VIDEO} open +0 "Retry"'),
            (1001.7, 'EventLogger', 'renderedFirstFrame [eventTime=1.70, mediaPos=1.00]'),
            (1001.7, 'NetPath', f'ep=2 video={VIDEO} first-frame +500'),
            (1001.9, 'NetPath', f'ep=2 video={VIDEO} image-hold off why=still-lifted'),
        ])

        self.assertEqual('ok', result['ttff_status'])
        self.assertEqual(1700, result['ttff_ms'])
        self.assertEqual([500], result['frame_episode_ms'])
        self.assertEqual(1750, result['launch_to_frame_ms'])
        self.assertEqual(1900, result['visible_frame_ms'])
        self.assertEqual('legacy-fade-start', result['visible_frame_kind'])
        self.assertEqual(1.0, result['first_frame_position_s'])
        self.assertEqual(1, result['errors'])

    def test_old_frame_before_new_open_is_not_credited_to_the_request(self):
        result = self.phase_for([
            (1000.0, 'NetPath', f'ep=1 video={VIDEO} first-frame +999'),
            (1000.1, 'NetPath', f'ep=2 video={VIDEO} open +100 "New"'),
            (1000.6, 'NetPath', f'ep=2 video={VIDEO} first-frame +600'),
        ])

        self.assertEqual(600, result['ttff_ms'])
        self.assertEqual([600], result['frame_episode_ms'])

    def test_actual_overlay_removal_is_distinguished_from_legacy_fade_start(self):
        result = self.phase_for([
            (1000.1, 'NetPath', f'ep=1 video={VIDEO} open +100 "Test"'),
            (1000.6, 'NetPath', f'ep=1 video={VIDEO} first-frame +600'),
            (1000.7, 'NetPath', f'ep=1 video={VIDEO} image-hold off why=still-lifted'),
            (1000.82, 'NetPath', f'ep=1 video={VIDEO} picture-visible +820 state=ready-texture-overlay-gone'),
        ])
        self.assertEqual(820, result['visible_frame_ms'])
        self.assertEqual('overlay-gone', result['visible_frame_kind'])

    def test_error_recovery_still_lift_without_frame_is_not_visible_playback(self):
        # Shape and timings of the saved Pixel availability failure: the error
        # clears the thumbnail, then a new recovery open starts without a frame.
        result = self.phase_for([
            (1000.386, 'NetPath', f'ep=1 video={VIDEO} open +386 "Test"'),
            (1002.072, 'NetPath', f'ep=1 video={VIDEO} error +2072 SourceError'),
            (1002.079, 'NetPath', f'ep=1 video={VIDEO} image-hold off why=still-lifted'),
            (1002.179, 'NetPath', f'ep=2 video={VIDEO} open +0 "Retry"'),
        ])

        self.assertEqual('no_first_frame', result['ttff_status'])
        self.assertIsNone(result['visible_frame_ms'])
        self.assertIsNone(result['visible_frame_kind'])

    def test_overlay_gone_without_matching_first_frame_is_not_credited(self):
        result = self.phase_for([
            (1000.1, 'NetPath', f'ep=1 video={VIDEO} open +100 "Test"'),
            (1000.6, 'NetPath', f'ep=1 video={VIDEO} picture-visible +600'),
        ])

        self.assertIsNone(result['visible_frame_ms'])
        self.assertIsNone(result['visible_frame_kind'])

    def test_recovery_frame_cannot_validate_earlier_failed_open_visibility(self):
        result = self.phase_for([
            (1000.1, 'NetPath', f'ep=1 video={VIDEO} open +100 "Test"'),
            (1000.5, 'NetPath', f'ep=1 video={VIDEO} error +500 SourceError'),
            (1000.6, 'NetPath', f'ep=1 video={VIDEO} image-hold off why=still-lifted'),
            (1001.0, 'NetPath', f'ep=2 video={VIDEO} open +0 "Retry"'),
            (1001.5, 'NetPath', f'ep=2 video={VIDEO} first-frame +500'),
        ])

        self.assertEqual(1500, result['ttff_ms'])
        self.assertIsNone(result['visible_frame_ms'])
        self.assertIsNone(result['visible_frame_kind'])

    def test_late_old_episode_frame_after_new_open_is_not_credited(self):
        result = self.phase_for([
            (1000.1, 'NetPath', f'ep=2 video={VIDEO} open +100 "Test"'),
            (1000.5, 'NetPath', f'ep=1 video={VIDEO} first-frame +500'),
            (1000.6, 'NetPath', f'ep=2 video={VIDEO} picture-visible +600'),
        ])

        self.assertEqual('no_first_frame', result['ttff_status'])
        self.assertEqual([], result['frame_episode_ms'])
        self.assertIsNone(result['visible_frame_ms'])

    def test_late_old_episode_visibility_cannot_pair_with_new_frame(self):
        result = self.phase_for([
            (1000.1, 'NetPath', f'ep=2 video={VIDEO} open +100 "Test"'),
            (1000.5, 'NetPath', f'ep=2 video={VIDEO} first-frame +500'),
            (1000.6, 'NetPath', f'ep=1 video={VIDEO} picture-visible +600'),
        ])

        self.assertEqual(500, result['ttff_ms'])
        self.assertIsNone(result['visible_frame_ms'])

    def test_new_open_is_a_boundary_even_when_episode_identifier_is_reused(self):
        result = self.phase_for([
            (1000.1, 'NetPath', f'ep=1 video={VIDEO} open +100 "Test"'),
            (1000.5, 'NetPath', f'ep=1 video={VIDEO} first-frame +500'),
            (1001.0, 'NetPath', f'ep=1 video={VIDEO} open +0 "New open"'),
            (1001.1, 'NetPath', f'ep=1 video={VIDEO} image-hold off why=still-lifted'),
        ])

        self.assertIsNone(result['visible_frame_ms'])

    def test_error_between_decoded_frame_and_still_lift_prevents_visibility_credit(self):
        result = self.phase_for([
            (1000.1, 'NetPath', f'ep=1 video={VIDEO} open +100 "Test"'),
            (1000.5, 'NetPath', f'ep=1 video={VIDEO} first-frame +500'),
            (1000.6, 'NetPath', f'ep=1 video={VIDEO} error +600 SourceError'),
            (1000.7, 'NetPath', f'ep=1 video={VIDEO} image-hold off why=still-lifted'),
        ])

        self.assertEqual(500, result['ttff_ms'])
        self.assertIsNone(result['visible_frame_ms'])

    def test_first_visible_open_is_not_replaced_by_a_later_recovery_marker(self):
        result = self.phase_for([
            (1000.1, 'NetPath', f'ep=1 video={VIDEO} open +100 "Test"'),
            (1000.5, 'NetPath', f'ep=1 video={VIDEO} first-frame +500'),
            (1000.6, 'NetPath', f'ep=1 video={VIDEO} image-hold off why=still-lifted'),
            (1000.7, 'NetPath', f'ep=1 video={VIDEO} error +700 SourceError'),
            (1001.0, 'NetPath', f'ep=2 video={VIDEO} open +0 "Retry"'),
            (1001.5, 'NetPath', f'ep=2 video={VIDEO} first-frame +500'),
            (1001.6, 'NetPath', f'ep=2 video={VIDEO} picture-visible +600'),
        ])

        self.assertEqual(600, result['visible_frame_ms'])
        self.assertEqual('legacy-fade-start', result['visible_frame_kind'])

    def test_tap_without_new_open_is_annotated_even_if_old_frame_arrives(self):
        result = self.phase_for([
            (1000.0, 'NetPath', f'ep=1 video={VIDEO} tap'),
            (1000.1, 'NetPath', f'ep=1 video={VIDEO} first-frame +100'),
        ])

        self.assertEqual('no_new_open', result['ttff_status'])
        self.assertIsNone(result['ttff_ms'])
        self.assertIsNone(result['launch_to_frame_ms'])
        self.assertEqual([], result['frame_episode_ms'])

    def test_new_open_without_frame_is_not_confused_with_missing_open(self):
        result = self.phase_for([
            (1000.1, 'NetPath', f'ep=1 video={VIDEO} open +100 "Test"'),
        ])

        self.assertEqual('no_first_frame', result['ttff_status'])
        self.assertIsNone(result['ttff_ms'])

    def test_server_denial_without_player_errors_still_records_failed_playback(self):
        # A playability denial occurs before media preparation, so the absence of
        # engine/load errors cannot establish that a soak actually played anything.
        result = self.phase_for([
            (1000.1, 'NetPath', f'ep=1 video={VIDEO} open +100 "Test"'),
            (1000.8, 'NetPath', f'player-result video={VIDEO} status=LOGIN_REQUIRED'),
        ])

        self.assertEqual(0, result['errors'])
        self.assertEqual(0, result['load_errors'])
        self.assertEqual('no_first_frame', result['ttff_status'])
        self.assertNotEqual('ok', result['ttff_status'])
        self.assertIsNone(result['ttff_ms'])
        self.assertIsNone(result['launch_to_frame_ms'])
        self.assertEqual([], result['frame_episode_ms'])

    def test_final_bot_denial_stops_without_waiting_for_soak_duration(self):
        result = self.phase_for([
            (1000.1, 'NetPath', f'ep=1 video={VIDEO} open +100 "Test"'),
            (1000.8, 'NetPath', 'bot-check autoplay=n suggestions=cancelled'),
        ])
        self.assertTrue(result['bot_check_denied'])
        self.assertLess(result['elapsed_s'], 2)
        self.assertEqual('no_first_frame', result['ttff_status'])

    def test_non_connectivity_recovery_cap_stops_long_soak_without_frame(self):
        result = self.phase_for([
            (1000.1, 'NetPath', f'ep=1 video={VIDEO} open +100 "Test"'),
            (1009.0, 'NetPath', f'ep=1 video={VIDEO} error +9000 SourceError'),
            (1009.1, 'NetPath', f'ep=1 video={VIDEO} recovery-capped connectivity=n validated=y'),
        ], duration=180)

        self.assertTrue(result['recovery_capped'])
        self.assertLess(result['elapsed_s'], 10)
        self.assertEqual('no_first_frame', result['ttff_status'])

    def test_terminal_recovery_after_initial_playback_still_fails_the_soak(self):
        result = self.phase_for([
            (1000.1, 'NetPath', f'ep=1 video={VIDEO} open +100 "Test"'),
            (1000.5, 'NetPath', f'ep=1 video={VIDEO} first-frame +500'),
            (1000.6, 'NetPath', f'ep=1 video={VIDEO} picture-visible +600'),
            (1009.0, 'NetPath', f'ep=1 video={VIDEO} error +9000 SourceError'),
            (1009.1, 'NetPath', f'ep=1 video={VIDEO} recovery-capped connectivity=n'),
        ], duration=180)

        self.assertEqual('ok', result['ttff_status'])
        self.assertEqual(600, result['visible_frame_ms'])
        self.assertTrue(result['recovery_capped'])
        self.assertLess(result['elapsed_s'], 10)

    def test_connectivity_recovery_cap_does_not_abort_waiting(self):
        result = self.phase_for([
            (1000.1, 'NetPath', f'ep=1 video={VIDEO} open +100 "Test"'),
            (1000.5, 'NetPath', f'ep=1 video={VIDEO} first-frame +500'),
            (1001.0, 'NetPath', f'ep=1 video={VIDEO} error +1000 NetworkError'),
            (1001.1, 'NetPath', f'ep=1 video={VIDEO} recovery-capped connectivity=y'),
        ], duration=5)

        self.assertFalse(result['recovery_capped'])
        self.assertGreaterEqual(result['elapsed_s'], 5)

    def test_intermediate_server_denial_does_not_abort_playable_attempt(self):
        result = self.phase_for([
            (1000.1, 'NetPath', f'ep=1 video={VIDEO} open +100 "Test"'),
            (1000.3, 'NetPath', f'player-result video={VIDEO} status=LOGIN_REQUIRED'),
            (1000.5, 'NetPath', f'ep=1 video={VIDEO} first-frame +500'),
        ], duration=5)

        self.assertFalse(result['bot_check_denied'])
        self.assertFalse(result['recovery_capped'])
        self.assertEqual('ok', result['ttff_status'])
        self.assertGreaterEqual(result['elapsed_s'], 5)

    def test_recovery_cap_for_old_episode_or_other_video_does_not_abort(self):
        result = self.phase_for([
            (1000.1, 'NetPath', f'ep=2 video={VIDEO} open +100 "Test"'),
            (1000.5, 'NetPath', f'ep=2 video={VIDEO} first-frame +500'),
            (1001.1, 'NetPath', f'ep=1 video={VIDEO} recovery-capped connectivity=n'),
            (1001.2, 'NetPath', 'ep=2 video=LMNOPQRSTUV recovery-capped connectivity=n'),
        ], duration=5)

        self.assertFalse(result['recovery_capped'])
        self.assertGreaterEqual(result['elapsed_s'], 5)

    def test_new_open_supersedes_already_captured_old_terminal_verdict(self):
        result = self.phase_for([
            (1000.1, 'NetPath', f'ep=1 video={VIDEO} open +100 "Test"'),
            (1000.3, 'NetPath', f'ep=1 video={VIDEO} recovery-capped connectivity=n'),
            (1001.0, 'NetPath', f'ep=2 video={VIDEO} open +0 "New request"'),
            (1001.5, 'NetPath', f'ep=2 video={VIDEO} first-frame +500'),
        ], duration=5)

        self.assertFalse(result['recovery_capped'])
        self.assertGreaterEqual(result['elapsed_s'], 5)

    def test_matrix_cannot_force_stop_and_retry_after_first_failed_phase(self):
        instance = self.make_benchmark()
        instance.args.mode = 'matrix'
        instance.args.videos = VIDEO + ',LMNOPQRSTUV'
        instance.args.duration = 180
        instance.phase = Mock(side_effect=benchmark.PlaybackUnavailable('server bot check'))
        with self.assertRaises(benchmark.PlaybackUnavailable):
            instance.run()
        self.assertEqual(1, instance.phase.call_count)


if __name__ == '__main__':
    unittest.main()
