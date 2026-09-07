"""Host-only preflight/cleanup regressions; every subprocess and device command is mocked.

Run: python3 -m unittest discover -s tools -p 'test_benchmark_pixel*.py' -v
"""

import contextlib
import importlib.util
import io
import json
from pathlib import Path
import shlex
import subprocess
import sys
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch


SPEC = importlib.util.spec_from_file_location(
    'benchmark_pixel_lifecycle', Path(__file__).with_name('benchmark-pixel.py'))
BENCHMARK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BENCHMARK)

SERIAL = 'host-only-no-device'
TARGET = 'io.github.aleixrodriala.arc'
APK_HASH = '7' * 64
PROFILE = ('HSPLcom/newtube/mobile/player/Media3PlayerController;->getPositionMs()J\n'
           'Landroidx/media3/exoplayer/ExoPlayer;\n')


class FakeDevice:
    """Fail closed on unknown commands rather than allowing any real adb invocation."""

    def __init__(self, target=TARGET, original=''):
        self.target = target
        self.original = original
        self.fixture_property = original
        self.sdk = 34
        self.package_info = '  flags=[ HAS_CODE ALLOW_BACKUP ]\n'
        self.locked = False
        self.fixture_present = True
        self.calls = []
        self.instrumentation = None
        self.instrumented = False
        self.returncode = 0
        self.transcript = b'INSTRUMENTATION_RESULT: stream=\nOK (1 test)\n'
        self.instrumentation_error = None
        self.stop_failure = None
        self.fail_uid_readback = False
        self.fail_thermal_readback = False
        self.profile = None
        self.logger = Mock(name='owned_logcat')
        self.logger.wait.return_value = 0
        self.logger_output = None

    def command(self, command):
        if command[:3] != ['adb', '-s', SERIAL]:
            raise AssertionError('Only the selected fake serial is allowed: ' + repr(command))
        remaining = command[3:]
        if remaining[:1] == ['shell']:
            if len(remaining) != 2:
                raise AssertionError('Android shell must receive one quoted command')
            parts = tuple(shlex.split(remaining[1]))
        else:
            parts = tuple(remaining)
        self.calls.append(parts)
        return parts

    def check_output(self, command, **kwargs):
        if kwargs != {'timeout': 60}:
            raise AssertionError('Unexpected adb invocation options: ' + repr(kwargs))
        parts = self.command(command)
        component = self.target + '/' + BENCHMARK.FIXTURE_ACTIVITY
        if parts == ('getprop', 'ro.build.version.sdk'):
            return str(self.sdk).encode()
        if parts == ('dumpsys', 'package', self.target):
            return self.package_info.encode()
        if parts == ('cmd', 'package', 'resolve-activity', '--brief', '-n', component):
            return (component if self.fixture_present else 'No activity found').encode()
        if parts == ('dumpsys', 'window'):
            return ('mDreamingLockscreen=' + str(self.locked).lower()).encode()
        if parts == ('pm', 'path', self.target):
            return b'package:/data/app/host-only/base.apk\n'
        if parts == ('sha256sum', '/data/app/host-only/base.apk'):
            return (APK_HASH + '  /data/app/host-only/base.apk\n').encode()
        if parts == ('getprop', BENCHMARK.FIXTURE_PROPERTY):
            return (self.fixture_property + '\n').encode()
        if parts[:2] == ('setprop', BENCHMARK.FIXTURE_PROPERTY) and len(parts) == 3:
            self.fixture_property = parts[2]
            return b''
        if parts == ('pm', 'list', 'packages', '-U', self.target):
            if self.instrumented and self.fail_uid_readback:
                raise subprocess.CalledProcessError(1, command)
            return ('package:' + self.target + ' uid:10707\n').encode()
        if parts == ('dumpsys', 'netstats'):
            return b'mAppUidStatsMap:\n10707 10000 10 1000 5\nmStatsMapA:\n'
        if parts == ('dumpsys', 'battery'):
            if self.instrumented and self.fail_thermal_readback:
                raise subprocess.CalledProcessError(1, command)
            return b'temperature: 312\n'
        if parts == ('dumpsys', 'thermalservice'):
            return b'Thermal Status: 0\n'
        if parts[:2] == ('mkdir', '-p') and len(parts) == 3:
            expected = '/sdcard/Android/media/' + BENCHMARK.TEST_PACKAGE + '/run-'
            if not parts[2].startswith(expected):
                raise AssertionError('Remote artifacts must stay in the owned test package')
            return b''
        if parts[:2] == ('am', 'force-stop') and len(parts) == 3:
            if parts[2] not in (BENCHMARK.TEST_PACKAGE, self.target):
                raise AssertionError('Attempted to stop an unrelated package')
            if parts[2] == self.stop_failure:
                raise subprocess.CalledProcessError(1, command)
            return b''
        if parts[:1] == ('pull',) and len(parts) == 3:
            if self.profile is not None:
                destination = Path(parts[2]) / 'run-fixture'
                destination.mkdir(parents=True)
                (destination / 'generated-baseline-prof.txt').write_text(self.profile)
            return b'host-only pull complete\n'
        raise AssertionError('Unexpected or unsafe fake-device command: ' + repr(parts))

    def popen(self, command, **kwargs):
        parts = self.command(command)
        if parts != ('logcat', '-T', '1', '-v', 'epoch',
                     'BenchmarkFixture:I', 'AndroidRuntime:E', '*:S'):
            raise AssertionError('Only the owned filtered logcat process may be started')
        self.logger_output = kwargs['stdout']
        return self.logger

    def run(self, command, **kwargs):
        self.instrumentation = self.command(command)
        if self.instrumentation[:4] != ('am', 'instrument', '-w', '-r'):
            raise AssertionError('Only instrumentation may be run')
        if kwargs.get('timeout') != 900 or kwargs.get('stderr') != subprocess.STDOUT:
            raise AssertionError('Instrumentation timeout/output contract changed')
        if self.fixture_property != '1':
            raise AssertionError('Offline fixture property must be set before instrumentation')
        self.instrumented = True
        kwargs['stdout'].write(self.transcript)
        if self.instrumentation_error is not None:
            raise self.instrumentation_error
        return SimpleNamespace(returncode=self.returncode)

    def mutation_calls(self):
        return [parts for parts in self.calls
                if parts[:1] in (('setprop',), ('mkdir',), ('pull',), ('logcat',))
                or parts[:2] in (('am', 'force-stop'), ('am', 'instrument'))]


class BenchmarkPixelLifecycleTest(unittest.TestCase):
    def setUp(self):
        self.temporary = TemporaryDirectory(prefix='benchmark-lifecycle-test-')
        self.addCleanup(self.temporary.cleanup)
        self.output = Path(self.temporary.name) / 'private-run'
        self.device = FakeDevice()
        # All reachable subprocess entrypoints are fail-closed mocks, including Popen fallback.
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        self.stack.enter_context(patch.object(BENCHMARK.subprocess, 'check_output',
                                              side_effect=lambda *a, **kw: self.device.check_output(*a, **kw)))
        self.stack.enter_context(patch.object(BENCHMARK.subprocess, 'run',
                                              side_effect=lambda *a, **kw: self.device.run(*a, **kw)))
        self.stack.enter_context(patch.object(BENCHMARK.subprocess, 'Popen',
                                              side_effect=lambda *a, **kw: self.device.popen(*a, **kw)))
        self.stack.enter_context(patch.object(BENCHMARK.os, 'umask', return_value=0o022))
        self.stack.enter_context(patch.object(BENCHMARK.time, 'monotonic', side_effect=(100.0, 102.25)))
        self.stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
        self.stack.enter_context(contextlib.redirect_stderr(io.StringIO()))

    def invoke(self, *extra):
        arguments = ['benchmark-pixel.py', '--serial', SERIAL, '--output', str(self.output), *extra]
        with patch.object(sys, 'argv', arguments):
            return BENCHMARK.main()

    def state(self):
        return json.loads((self.output / 'run-state.json').read_text())

    def assert_no_mutation(self):
        self.assertEqual([], self.device.mutation_calls())
        self.assertFalse(self.output.exists())
        self.assertEqual(self.device.original, self.device.fixture_property)

    def assert_safe_cleanup(self, expected_errors=False):
        state = self.state()
        self.assertEqual(self.device.original, self.device.fixture_property)
        self.assertEqual(self.device.original, state['original_fixture_property'])
        self.assertEqual(self.device.original, state['restored_fixture_property'])
        self.assertEqual(expected_errors, bool(state['cleanup_errors']))
        stops = [parts for parts in self.device.calls if parts[:2] == ('am', 'force-stop')]
        self.assertEqual([('am', 'force-stop', self.device.target),
                          ('am', 'force-stop', BENCHMARK.TEST_PACKAGE),
                          ('am', 'force-stop', self.device.target)], stops)
        restore = ('setprop', BENCHMARK.FIXTURE_PROPERTY, self.device.original)
        # The measuring process must stop before it can relaunch an app after fixture restoration.
        self.assertLess(self.device.calls.index(stops[1]),
                        max(i for i, parts in enumerate(self.device.calls) if parts == restore))
        self.device.logger.terminate.assert_called_once_with()
        self.assertTrue(self.device.logger_output.closed)
        for parts in self.device.calls:
            self.assertNotIn(parts[:1], [('install',), ('uninstall',), ('install-multiple',), ('reboot',)])
            self.assertNotIn(parts[:2], [('pm', 'clear'), ('pm', 'install'), ('pm', 'uninstall'),
                                        ('svc', 'wifi'), ('svc', 'data'), ('settings', 'put')])

    def test_api_below_34_rejects_before_any_mutation(self):
        self.device.sdk = 33
        with self.assertRaisesRegex(RuntimeError, 'API 34'):
            self.invoke()
        self.assert_no_mutation()

    def test_debuggable_target_rejects_before_any_mutation(self):
        self.device.package_info = '  pkgFlags=[ HAS_CODE DEBUGGABLE ALLOW_BACKUP ]\n'
        with self.assertRaisesRegex(RuntimeError, 'debuggable'):
            self.invoke()
        self.assert_no_mutation()

    def test_locked_phone_rejects_before_any_mutation(self):
        self.device.locked = True
        with self.assertRaisesRegex(RuntimeError, 'Unlock'):
            self.invoke()
        self.assert_no_mutation()

    def test_missing_benchmark_fixture_rejects_before_any_mutation(self):
        self.device.fixture_present = False
        with self.assertRaisesRegex(RuntimeError, 'benchmark-only fixture'):
            self.invoke()
        self.assert_no_mutation()

    def test_successful_fixture_defaults_to_keep_and_restores_empty_property(self):
        self.assertEqual(0, self.invoke())
        self.assert_safe_cleanup()
        state = self.state()
        self.assertEqual('fixture', state['mode'])
        self.assertEqual('keep', state['compilation'])
        self.assertEqual(5, state['iterations'])
        self.assertEqual(APK_HASH, state['apk_sha256'])
        self.assertTrue(state['local_media_only'])
        self.assertIn(('-e', 'compilation', 'keep'),
                      [self.device.instrumentation[i:i + 3]
                       for i in range(len(self.device.instrumentation) - 2)])
        self.device.logger.kill.assert_not_called()

    def test_nonempty_property_and_selected_variant_are_restored_exactly(self):
        self.device = FakeDevice(target=TARGET + '.debug', original='previous fixture value')
        self.assertEqual(0, self.invoke('--package', self.device.target))
        self.assert_safe_cleanup()
        self.assertEqual(self.device.target, self.state()['package'])

    def test_failed_instrumentation_transcript_still_restores_and_stops_owned_packages(self):
        self.device.original = self.device.fixture_property = 'prior'
        self.device.transcript = b'FAILURES!!! Tests run: 1, Failures: 1\n'
        with self.assertRaisesRegex(RuntimeError, 'Instrumentation did not pass'):
            self.invoke()
        self.assert_safe_cleanup()
        self.assertFalse(any(parts[:1] == ('pull',) for parts in self.device.calls))

    def test_nonzero_instrumentation_exit_fails_even_with_ok_text(self):
        self.device.returncode = 1
        with self.assertRaisesRegex(RuntimeError, 'Instrumentation did not pass'):
            self.invoke()
        self.assert_safe_cleanup()

    def test_instrumentation_timeout_restores_property_and_stops_measuring_package(self):
        self.device.instrumentation_error = subprocess.TimeoutExpired('fake instrumentation', 900)
        with self.assertRaises(subprocess.TimeoutExpired):
            self.invoke()
        self.assert_safe_cleanup()

    def test_slow_owned_logger_is_killed_after_property_restoration(self):
        self.device.logger.wait.side_effect = [subprocess.TimeoutExpired('owned logcat', 10), 0]
        self.assertEqual(0, self.invoke())
        self.assert_safe_cleanup()
        self.device.logger.kill.assert_called_once_with()
        self.assertEqual(2, self.device.logger.wait.call_count)

    def test_stop_failure_is_reported_without_skipping_remaining_cleanup(self):
        self.device.stop_failure = BENCHMARK.TEST_PACKAGE
        with self.assertRaisesRegex(RuntimeError, 'Cleanup needs attention'):
            self.invoke()
        self.assert_safe_cleanup(expected_errors=True)
        self.assertEqual(['CalledProcessError: stop ' + BENCHMARK.TEST_PACKAGE],
                         self.state()['cleanup_errors'])

    def test_readback_failures_do_not_prevent_property_restoration(self):
        self.device.fail_uid_readback = True
        self.device.fail_thermal_readback = True
        self.assertEqual(0, self.invoke())
        self.assert_safe_cleanup()
        state = self.state()
        self.assertEqual('CalledProcessError', state['uid_readback_error'])
        self.assertEqual('CalledProcessError', state['thermal_readback_error'])

    def test_profile_generation_metadata_is_explicit_and_artifacts_are_verified(self):
        self.device.profile = PROFILE
        self.assertEqual(0, self.invoke('--mode', 'profile', '--compilation', 'baseline'))
        self.assert_safe_cleanup()
        self.assertEqual('profile', self.state()['mode'])
        self.assertEqual('profile-collection', self.state()['compilation'])
        self.assertIn(BENCHMARK.CLASSES['profile'], self.device.instrumentation)
        verified = json.loads((self.output / 'verified-profiles.json').read_text())
        self.assertEqual(1, len(verified))
        self.assertEqual(1, verified[0]['app_methods'])
        self.assertEqual(2, verified[0]['rules'])
        self.assertEqual(64, len(verified[0]['sha256']))

    def test_missing_profile_artifact_still_restores_and_fails_explicitly(self):
        with self.assertRaisesRegex(RuntimeError, 'no generated baseline-profile artifact'):
            self.invoke('--mode', 'profile')
        self.assert_safe_cleanup()

    def test_existing_output_directory_is_not_overwritten_and_no_device_is_touched(self):
        self.output.mkdir()
        sentinel = self.output / 'existing.txt'
        sentinel.write_text('preserve this prior run\n')
        with self.assertRaises(SystemExit):
            self.invoke()
        self.assertEqual([], self.device.calls)
        self.assertEqual('preserve this prior run\n', sentinel.read_text())


if __name__ == '__main__':
    unittest.main()
