from __future__ import annotations

import io
import hashlib
import json
import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch
from contextlib import redirect_stdout

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import catalog_health as health


class HealthTests(unittest.TestCase):
    now = datetime(2026, 11, 18, 20, tzinfo=timezone.utc)
    raw = b'catalog contents'

    def manifest(self, verified_hours=0):
        return {'version': 'published-version',
                'generated_at': (self.now - timedelta(days=7)).isoformat(),
                'checked_at': (self.now - timedelta(hours=verified_hours)).isoformat(),
                'catalog': {'url': 'https://catalog.test/catalog.json.gz', 'bytes': len(self.raw),
                            'sha256': hashlib.sha256(self.raw).hexdigest()}}

    def test_unchanged_but_recently_verified_catalog_is_fresh(self):
        result = health.publication_health(self.manifest(), self.now)
        self.assertEqual(result['status'], 'fresh')
        self.assertEqual(result['catalog_age_hours'], 168)
        self.assertEqual(result['verified_age_hours'], 0)

    def test_hold_does_not_make_old_catalog_fresh(self):
        result = health.publication_health(self.manifest(49), self.now)
        self.assertEqual(result['status'], 'stale')

    def test_missing_malformed_naive_or_future_dates_are_unhealthy(self):
        for manifest in (None, {}, {**self.manifest(), 'checked_at': 'yesterday'},
                         {**self.manifest(), 'checked_at': '2026-11-18T20:00:00'},
                         self.manifest(-1)):
            with self.subTest(manifest=manifest):
                self.assertNotEqual(health.publication_health(manifest, self.now)['status'], 'fresh')

    def test_monitor_fails_for_stale_catalog_despite_successful_collection(self):
        responses = [io.BytesIO(json.dumps(self.manifest(49)).encode()),
                     io.BytesIO(self.raw),
                     io.BytesIO(json.dumps({'valid': True, 'publication': 'held'}).encode())]
        with patch.object(sys, 'argv', ['health']), \
             patch.object(health, 'utc_now', return_value=self.now), \
             patch('urllib.request.urlopen', side_effect=responses), redirect_stdout(io.StringIO()) as output:
            self.assertEqual(health.main(), 1)
        self.assertIn('::error title=Catalog freshness', output.getvalue())

    def test_monitor_failure_to_fetch_is_actionable(self):
        with patch.object(sys, 'argv', ['health']), \
             patch('urllib.request.urlopen', side_effect=TimeoutError('timed out')), \
             redirect_stdout(io.StringIO()) as output:
            self.assertEqual(health.main(), 1)
        self.assertIn('timed out', output.getvalue())

    def test_monitor_passes_for_verified_unchanged_catalog(self):
        responses = [io.BytesIO(json.dumps(self.manifest()).encode()),
                     io.BytesIO(self.raw),
                     io.BytesIO(json.dumps({'valid': True, 'publication': 'unchanged'}).encode())]
        with patch.object(sys, 'argv', ['health']), \
             patch.object(health, 'utc_now', return_value=self.now), \
             patch('urllib.request.urlopen', side_effect=responses), redirect_stdout(io.StringIO()):
            self.assertEqual(health.main(), 0)

    def test_collection_metadata_failure_preserves_healthy_publication_result(self):
        for error in (TimeoutError('timed out'), json.JSONDecodeError('invalid', '', 0)):
            responses = [io.BytesIO(json.dumps(self.manifest()).encode()), io.BytesIO(self.raw), error]
            with self.subTest(error=error), patch.object(sys, 'argv', ['health']), \
                 patch.object(health, 'utc_now', return_value=self.now), \
                 patch('urllib.request.urlopen', side_effect=responses), redirect_stdout(io.StringIO()) as output:
                self.assertEqual(health.main(), 0)
                report = json.loads(output.getvalue())
                self.assertEqual(report['publication_health']['status'], 'fresh')
                self.assertEqual(report['latest_collection']['status'], 'unavailable')

    def test_recent_manifest_cannot_hide_broken_catalog_download(self):
        for artifact in (io.BytesIO(b'corrupt'), TimeoutError('artifact unavailable')):
            responses = [io.BytesIO(json.dumps(self.manifest()).encode()), artifact,
                         io.BytesIO(b'{"valid":true,"publication":"unchanged"}')]
            with self.subTest(artifact=artifact), patch.object(sys, 'argv', ['health']), \
                 patch.object(health, 'utc_now', return_value=self.now), \
                 patch('urllib.request.urlopen', side_effect=responses), redirect_stdout(io.StringIO()) as output:
                self.assertEqual(health.main(), 1)
                self.assertIn('"status": "fresh"', output.getvalue())
                self.assertIn('"status": "unavailable"', output.getvalue())
