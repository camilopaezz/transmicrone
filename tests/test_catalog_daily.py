from __future__ import annotations

import copy
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout, redirect_stderr
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import catalog_daily as daily


def candidate():
    now = datetime.now(timezone.utc)
    stamp = now.isoformat()
    return {
        'schema_version': 1, 'generated_at': stamp, 'failures': [], 'counts': {'places': 1},
        'places': [{'place_id': 'p1', 'name': 'One'}],
        'servicios': [{'route_id': 1, 'painted': 'A1'}],
        'serving': [{'place_id': 'p1', 'route_id': 1}],
        'service_hours': [{'catalog_id': '1', 'fetched_at': stamp, 'windows': []}],
        'service_routes': [{'catalog_id': '1', 'fetched_at': stamp, 'stops': [{'name': 'One'}]}],
        'sources': [
            {'kind': 'gtfs', 'name': now.strftime('GTFS_%Y%m%d.zip'), 'feed_info': [
                {'feed_start_date': '20260101', 'feed_end_date': '20991231'}]},
            *[{'kind': kind} for kind in ('gis-estaciones', 'gis-paraderos', 'gis-servicios')],
            {'kind': 'catalog-searchRutaByTipo', 'count': 1},
            {'kind': 'catalog-infoRuta', 'ok': 1, 'candidates': 1, 'fail': 0},
        ],
    }


class AssessmentTests(unittest.TestCase):
    def assess(self, value, baseline=None):
        return daily.assess(value, baseline or {}, 0, datetime.now(timezone.utc))

    def test_complete_fresh_candidate_can_publish(self):
        self.assertTrue(self.assess(candidate())['promotable'])

    def test_partial_fetch_cannot_publish_even_when_old_details_exist(self):
        value = candidate()
        value['sources'][-1].update(ok=0, fail=1)
        self.assertFalse(self.assess(value)['valid'])

    def test_stale_route_detail_cannot_publish(self):
        value = candidate()
        value['service_routes'][0]['fetched_at'] = '2026-01-01'
        self.assertFalse(self.assess(value)['valid'])

    def test_broken_join_cannot_publish(self):
        value = candidate()
        value['serving'][0]['route_id'] = 99
        self.assertFalse(self.assess(value)['valid'])

    def test_expired_gtfs_cannot_publish(self):
        value = candidate()
        value['sources'][0]['feed_info'][0]['feed_end_date'] = '20250101'
        self.assertFalse(self.assess(value)['valid'])

    def test_removal_holds_publication(self):
        value = candidate()
        baseline = copy.deepcopy(value)
        baseline['places'].append({'place_id': 'removed', 'name': 'Old'})
        result = self.assess(value, baseline)
        self.assertFalse(result['promotable'])
        self.assertEqual(result['changes']['places']['removed'], ['removed'])

    def test_comparison_ignores_fetch_timestamps_but_preserves_stop_order(self):
        old = candidate()
        new = copy.deepcopy(old)
        new['service_routes'][0]['fetched_at'] = 'tomorrow'
        self.assertFalse(daily.compare(old, new)['service_routes']['changed'])
        new['service_routes'][0]['stops'].append({'name': 'Two'})
        self.assertEqual(daily.compare(old, new)['service_routes']['changed'], ['1'])

    def test_complete_removal_on_a_prior_day_is_confirmed(self):
        value = candidate()
        baseline = copy.deepcopy(value)
        baseline['places'][0]['place_id'] = 'old-id'
        for i in range(10):
            baseline['places'].append({'place_id': f'unchanged-{i}'})
            value['places'].append({'place_id': f'unchanged-{i}'})
        prior = {'valid': True, 'started_at': '2026-01-01T12:00:00+00:00',
                 'changes': {'places': {'removed': ['old-id']}}}
        result = daily.assess(value, baseline, 0, datetime.now(timezone.utc), prior)
        self.assertTrue(result['promotable'], result)

    def test_same_day_or_failed_observation_cannot_confirm_removal(self):
        value = candidate()
        baseline = copy.deepcopy(value)
        baseline['places'][0]['place_id'] = 'old-id'
        for valid in (True, False):
            prior = {'valid': valid, 'started_at': datetime.now(timezone.utc).isoformat(),
                     'changes': {'places': {'removed': ['old-id']}}}
            self.assertFalse(daily.assess(value, baseline, 0, datetime.now(timezone.utc), prior)['promotable'])


class FakeR2:
    def __init__(self, archive_fails=False):
        self.objects = {}
        self.events = []
        self.archive_fails = archive_fails

    def read(self, name):
        return self.objects.get(name)

    def put(self, name, raw, content_type='application/json', mutable=False):
        self.events.append(name)
        self.objects[name] = raw
        return {'key': name, 'sha256': daily.sha(raw), 'bytes': len(raw), 'url': 'https://catalog.test/' + name}

    def archive(self, name, path):
        self.events.append('archive')
        if self.archive_fails:
            raise RuntimeError('Archive upload failed')


class PublicationTests(unittest.TestCase):
    def run_daily(self, store):
        value = candidate()

        def build():
            output = Path(sys.argv[sys.argv.index('--out') + 1])
            output.write_text(json.dumps(value))
            return 0

        def download(url, **kwargs):
            return io.BytesIO(store.objects[url.removeprefix('https://catalog.test/')])

        with tempfile.TemporaryDirectory() as temp, \
             patch.object(sys, 'argv', ['daily', '--out', temp]), \
             patch.object(daily, 'R2', return_value=store), \
             patch.object(daily.build_catalog, 'main', side_effect=build), \
             patch.object(daily, 'pack', return_value={'schema_version': 1}), \
             patch('urllib.request.urlopen', side_effect=download), \
             redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            result = daily.main()
            return result, json.loads((Path(temp) / 'report.json').read_text())

    def test_archives_and_verifies_candidate_before_promoting_manifest(self):
        store = FakeR2()
        result, report = self.run_daily(store)
        self.assertEqual(result, 0)
        self.assertEqual(report['publication'], 'published')
        self.assertLess(store.events.index('archive'), store.events.index('current.json'))
        current = json.loads(store.objects['current.json'])
        self.assertIn(current['catalog']['key'], store.objects)

    def test_archive_failure_leaves_current_manifest_untouched(self):
        store = FakeR2(archive_fails=True)
        result, report = self.run_daily(store)
        self.assertEqual(result, 1)
        self.assertNotIn('current.json', store.objects)
        self.assertIn('publication_error', report)

    def test_unchanged_data_keeps_version_and_updates_check_time(self):
        store = FakeR2()
        self.assertEqual(self.run_daily(store)[0], 0)
        original = json.loads(store.objects['current.json'])
        result, report = self.run_daily(store)
        self.assertEqual(result, 0)
        self.assertEqual(report['publication'], 'unchanged')
        current = json.loads(store.objects['current.json'])
        self.assertEqual(current['version'], original['version'])
        self.assertGreaterEqual(current['checked_at'], original['checked_at'])
