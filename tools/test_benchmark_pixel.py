#!/usr/bin/env python3
"""Host-only parser checks; no adb/device actions."""

import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location('benchmark_pixel', Path(__file__).with_name('benchmark-pixel.py'))
BENCHMARK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BENCHMARK)


class BenchmarkParsersTest(unittest.TestCase):
    def test_target_uid_is_exact_not_first_prefix_match(self):
        packages = ('package:io.github.aleixrodriala.arc.benchmark uid:10790\r\n'
                    'package:io.github.aleixrodriala.arc.test uid:10789\r\n'
                    'package:io.github.aleixrodriala.arc uid:10707\r\n')
        self.assertEqual(10707, BENCHMARK.package_uid(packages, 'io.github.aleixrodriala.arc'))

    def test_missing_target_does_not_use_test_or_other_variant(self):
        packages = ('package:io.github.aleixrodriala.arc.test uid:10789\n'
                    'package:io.github.aleixrodriala.arc.debug uid:10790\n')
        self.assertIsNone(BENCHMARK.package_uid(packages, 'io.github.aleixrodriala.arc'))

    def test_uid_bytes_use_final_map_not_health_summary(self):
        netstats = ('mAppUidStatsMap: OK\nother health output\n'
                    'mAppUidStatsMap:\n  uid rxBytes rxPackets txBytes txPackets\n'
                    '  10790 22097 500 16666298 3000\n'
                    '  10707 5210000000 90000 48000000 12000\n'
                    'mStatsMapA:\n  10707 999 999 999 999\n')
        self.assertEqual(dict(uid=10707, rx_bytes=5210000000, tx_bytes=48000000),
                         BENCHMARK.parse_uid_bytes(netstats, 10707))

    def test_missing_map_or_uid_is_unknown_not_zero(self):
        self.assertIsNone(BENCHMARK.parse_uid_bytes('10707 1 2 3 4\n', 10707))
        self.assertIsNone(BENCHMARK.parse_uid_bytes('mAppUidStatsMap:\n10790 1 2 3 4\n', 10707))


if __name__ == '__main__':
    unittest.main()
