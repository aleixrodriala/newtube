#!/usr/bin/env python3
"""Run an already-installed non-debuggable benchmark APK without clearing app data.

This tool never builds, installs, uninstalls, changes radios/proxies, or publishes
profiles into production sources. API 34+ avoids Macrobenchmark's older-device
uninstall/reinstall fallback when compilation state is reset.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid


TEST_PACKAGE = 'io.github.aleixrodriala.arc.benchmark'
FIXTURE_PROPERTY = 'debug.arc.benchmark_fixture'
FIXTURE_ACTIVITY = 'com.newtube.mobile.player.BenchmarkPlaybackActivity'
CLASSES = {
    'fixture': 'com.newtube.benchmark.PlaybackStartupBenchmark',
    'profile': 'com.newtube.benchmark.PlaybackBaselineProfileGenerator',
}


def package_uid(packages, package):
    # Prefix queries also return .test/.benchmark; never attribute their sockets to the app.
    match = re.search(r'^package:' + re.escape(package) + r'\s+uid:(\d+)\s*$', packages, re.M)
    return int(match[1]) if match else None


def parse_uid_bytes(netstats, uid):
    _, marker, table = netstats.rpartition('mAppUidStatsMap:')
    section = table.split('mStatsMapA:')[0] if marker else ''
    counters = re.search(r'^\s*' + str(uid) + r'\s+(\d+)\s+\d+\s+(\d+)\s+\d+\s*$',
                         section, re.M)
    return dict(uid=uid, rx_bytes=int(counters[1]), tx_bytes=int(counters[2])) if counters else None


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--mode', choices=CLASSES, default='fixture')
    parser.add_argument('--compilation', choices=('keep', 'none', 'baseline'), default='keep')
    parser.add_argument('--iterations', type=int, default=5)
    parser.add_argument('--package', choices=('io.github.aleixrodriala.arc',
                                            'io.github.aleixrodriala.arc.debug'),
                        default='io.github.aleixrodriala.arc')
    args = parser.parse_args()
    if not 1 <= args.iterations <= 30:
        parser.error('iterations must be between 1 and 30')
    if args.output.exists():
        parser.error('output must be a new private directory')
    adb_command = ['adb', '-s', args.serial]

    def adb(*arguments):
        return subprocess.check_output(adb_command + list(arguments), timeout=60).decode(errors='replace')

    def shell(*arguments):
        return adb('shell', shlex.join(arguments))

    def uid_bytes():
        packages = shell('pm', 'list', 'packages', '-U', args.package)
        uid = package_uid(packages, args.package)
        if uid is None:
            return None
        return parse_uid_bytes(shell('dumpsys', 'netstats'), uid)

    def thermal_state():
        battery = re.search(r'temperature:\s+(\d+)', shell('dumpsys', 'battery'))
        thermal = re.search(r'Thermal Status:\s*(\d+)', shell('dumpsys', 'thermalservice'))
        return dict(battery_c=int(battery[1]) / 10 if battery else None,
                    thermal_status=int(thermal[1]) if thermal else None)

    sdk = int(shell('getprop', 'ro.build.version.sdk').strip())
    if sdk < 34:
        raise RuntimeError('API 34+ required; older compilation resets can reinstall the target app')
    package_info = shell('dumpsys', 'package', args.package)
    if re.search(r'^\s*(?:flags|pkgFlags)=\[[^\]]*\bDEBUGGABLE\b', package_info, re.M):
        raise RuntimeError('Installed target is debuggable; install the benchmark variant first')
    # A shell-only activity has no intent filter and is absent from dumpsys' resolver tables.
    component = args.package + '/' + FIXTURE_ACTIVITY
    resolved = shell('cmd', 'package', 'resolve-activity', '--brief', '-n', component)
    if component not in resolved:
        raise RuntimeError('Installed target does not contain the benchmark-only fixture')
    window = shell('dumpsys', 'window')
    if 'mDreamingLockscreen=true' in window:
        raise RuntimeError('Unlock the selected device before running this tool')
    path_lines = shell('pm', 'path', args.package).splitlines()
    apk_path = next((line.removeprefix('package:') for line in path_lines
                     if line.startswith('package:') and line.endswith('/base.apk')), None)
    if not apk_path:
        raise RuntimeError('Cannot resolve the installed target APK')
    apk_hash = shell('sha256sum', apk_path).split()[0]
    original_fixture = shell('getprop', FIXTURE_PROPERTY).strip()
    args.output.mkdir(mode=0o700, parents=True)
    state = dict(serial=args.serial, package=args.package, sdk=sdk, mode=args.mode,
                 compilation=args.compilation if args.mode == 'fixture' else 'profile-collection',
                 iterations=args.iterations,
                 apk_sha256=apk_hash, original_fixture_property=original_fixture,
                 local_media_only=True, before_uid_bytes=uid_bytes(),
                 before_thermal=thermal_state())
    (args.output / 'run-state.json').write_text(json.dumps(state, indent=2) + '\n')
    remote_output = '/sdcard/Android/media/' + TEST_PACKAGE + '/run-' + uuid.uuid4().hex[:12]
    shell('mkdir', '-p', remote_output)
    instrumentation = ['am', 'instrument', '-w', '-r', '-e', 'class', CLASSES[args.mode],
                       '-e', 'targetPackage', args.package, '-e', 'iterations', str(args.iterations),
                       '-e', 'compilation', args.compilation,
                       '-e', 'additionalTestOutputDir', remote_output,
                       TEST_PACKAGE + '/androidx.test.runner.AndroidJUnitRunner']
    log_output = (args.output / 'fixture.log').open('wb')
    logger = subprocess.Popen(adb_command + ['logcat', '-T', '1', '-v', 'epoch',
                              'BenchmarkFixture:I', 'AndroidRuntime:E', '*:S'], stdout=log_output)
    started = time.monotonic()
    try:
        shell('setprop', FIXTURE_PROPERTY, '1')
        shell('am', 'force-stop', args.package)
        print('Running ' + args.mode + ' on non-debuggable APK ' + apk_hash, flush=True)
        with (args.output / 'instrumentation.txt').open('wb') as output:
            completed = subprocess.run(adb_command + ['shell', shlex.join(instrumentation)],
                                       stdout=output, stderr=subprocess.STDOUT, timeout=900)
        transcript = (args.output / 'instrumentation.txt').read_text(errors='replace')
        if completed.returncode != 0 or not re.search(r'OK \(\d+ tests?\)', transcript):
            raise RuntimeError('Instrumentation did not pass; inspect instrumentation.txt')
        adb('pull', remote_output, str(args.output / 'artifacts'))
        if args.mode == 'profile':
            profiles = sorted((args.output / 'artifacts').rglob('*baseline-prof.txt'))
            if not profiles:
                raise RuntimeError('Profile rule passed but no generated baseline-profile artifact was found')
            verified = []
            for profile in profiles:
                text = profile.read_text()
                rules = [line for line in text.splitlines() if line and not line.startswith('#')]
                if not rules or any('BenchmarkPlaybackActivity' in rule for rule in rules):
                    raise RuntimeError('Generated profile is empty or contains fixture-only classes')
                app_methods = sum('->' in rule and re.search(
                    r'Lcom/(?:newtube/mobile|liskovsoft/(?:smartyoutubetv2/common|googlecommon|youtubeapi))/',
                    rule) is not None for rule in rules)
                if app_methods == 0:
                    raise RuntimeError('Generated profile contains no app-specific methods')
                verified.append(dict(file=str(profile), rules=len(rules), app_methods=app_methods,
                                     sha256=hashlib.sha256(profile.read_bytes()).hexdigest()))
            (args.output / 'verified-profiles.json').write_text(json.dumps(verified, indent=2) + '\n')
        print('Passed; private artifacts: ' + str(args.output), flush=True)
        return 0
    finally:
        cleanup_errors = []
        try:
            # Stop the owned measuring process too, including after an adb timeout.
            # Otherwise it could relaunch the target after fixture mode is restored.
            for package in (TEST_PACKAGE, args.package):
                try:
                    shell('am', 'force-stop', package)
                except (subprocess.SubprocessError, OSError) as error:
                    cleanup_errors.append(type(error).__name__ + ': stop ' + package)
            try:
                state['after_uid_bytes'] = uid_bytes()
                before, after = state.get('before_uid_bytes'), state.get('after_uid_bytes')
                if before and after and before['uid'] == after['uid']:
                    state['uid_byte_delta'] = {key: after[key] - before[key]
                                               for key in ('rx_bytes', 'tx_bytes')}
            except (subprocess.SubprocessError, OSError) as error:
                state['uid_readback_error'] = type(error).__name__
            try:
                state['after_thermal'] = thermal_state()
            except (subprocess.SubprocessError, OSError) as error:
                state['thermal_readback_error'] = type(error).__name__
            try:
                shell('setprop', FIXTURE_PROPERTY, original_fixture)
                state['restored_fixture_property'] = shell('getprop', FIXTURE_PROPERTY).strip()
                if state['restored_fixture_property'] != original_fixture:
                    cleanup_errors.append('Fixture property restoration did not match')
            except (subprocess.SubprocessError, OSError) as error:
                cleanup_errors.append(type(error).__name__ + ': restore fixture property')
        finally:
            logger.terminate()
            try:
                logger.wait(timeout=10)
            except subprocess.TimeoutExpired:
                logger.kill()
                logger.wait(timeout=10)
            log_output.close()
            state['elapsed_s'] = round(time.monotonic() - started, 2)
            state['remote_artifacts'] = remote_output
            state['cleanup_errors'] = cleanup_errors
            (args.output / 'run-state.json').write_text(json.dumps(state, indent=2) + '\n')
        if cleanup_errors:
            raise RuntimeError('Cleanup needs attention; inspect run-state.json')


if __name__ == '__main__':
    raise SystemExit(main())
