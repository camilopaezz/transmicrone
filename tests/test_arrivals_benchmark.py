"""Checks that failed/partial requests cannot create a misleading speedup."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location(
    'arrivals_summary', Path(__file__).resolve().parents[1] / 'scripts/summarize-arrivals-benchmark.py')
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)


class ArrivalsMeasurementTests(unittest.TestCase):
    def write(self, directory, name, rows):
        path = Path(directory) / name
        path.write_text(''.join(json.dumps(row) + '\n' for row in rows))
        return path

    def observation(self, ms, result='success', warmup=False, mode='uncapped', round_id=1):
        return dict(board_ms=ms, result=result, warmup=warmup, mode=mode,
                    round=round_id, request_count=4)

    def test_fast_failure_and_warmup_do_not_reduce_success_latency(self):
        rows = [self.observation(2000), self.observation(4000),
                self.observation(10, result='failure:TIMEOUT'),
                self.observation(1, warmup=True)]
        with tempfile.TemporaryDirectory() as directory:
            result = summary.summarize([self.write(directory, 'run.jsonl', rows)])
        measured = result['summaries'][0]
        self.assertEqual(measured['complete_median_ms'], 3000)
        self.assertEqual(measured['complete_runs'], 2)
        self.assertEqual(measured['outcomes']['failure:TIMEOUT'], 1)
        self.assertEqual(measured['runs_with_call_details'], 0)
        self.assertIsNone(measured['call_median_ms'])

    def test_transport_failure_is_excluded_even_if_client_reports_empty(self):
        row = self.observation(10, result='empty')
        row['calls'] = [dict(http_status=200, transport_failure=True)]
        self.assertFalse(summary.complete(row))

    def test_repeated_round_numbers_are_paired_within_their_own_window(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = [self.write(directory, f'{i}.jsonl', [
                self.observation(2000, mode='capped'), self.observation(ms),
            ]) for i, ms in enumerate((1000, 4000))]
            result = summary.summarize(paths)
        self.assertEqual(len(result['pairs']), 2)
        self.assertEqual([r['reduction_percent'] for r in result['pairs']], [50, -100])


class BenchmarkCollectionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location(
            'benchmark_launcher', Path(__file__).resolve().parents[1] / 'scripts/benchmark-arrivals.py')
        cls.launcher = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.launcher)

    def records(self):
        return [dict(round=0, warmup=True, mode=mode, request_count=count,
                     calls=[{} for _ in range(count)])
                for mode in ('capped', 'uncapped') for count in (4, 8)]

    def test_failed_instrumentation_never_reads_or_publishes_previous_cache(self):
        from unittest.mock import patch
        import subprocess
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'observations.jsonl'
            output.write_text('previous experiment')
            with patch.object(self.launcher.subprocess, 'run', return_value=
                              subprocess.CompletedProcess([], 0, stdout='FAILURES!!!')), \
                    patch.object(self.launcher.subprocess, 'check_output') as read:
                with self.assertRaises(RuntimeError):
                    self.launcher.collect_device(['adb'], ['instrument'], {}, output, 0)
                read.assert_not_called()
            self.assertEqual(output.read_text(), 'previous experiment')

    def test_partial_or_duplicate_records_cannot_replace_existing_output(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'observations.jsonl'
            output.write_text('previous experiment')
            short = self.records()
            short[0]['calls'].pop()
            duplicate = self.records()
            duplicate[-1] = duplicate[0]
            for rows in (short, duplicate, self.records()[:-1]):
                with self.subTest(rows=rows), self.assertRaises(RuntimeError):
                    self.launcher.publish(''.join(json.dumps(r)+'\n' for r in rows), output, 0)
                self.assertEqual(output.read_text(), 'previous experiment')
            self.launcher.publish(''.join(json.dumps(r)+'\n' for r in self.records()), output, 0)
            self.assertEqual(len(output.read_text().splitlines()), 4)

    def test_summary_rejects_truncated_call_details(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'truncated.jsonl'
            row = dict(round=1, warmup=False, mode='uncapped', request_count=4,
                       board_ms=10, result='success', calls=[])
            path.write_text(json.dumps(row))
            with self.assertRaises(ValueError):
                summary.summarize([path])
