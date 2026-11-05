from __future__ import annotations

import copy
import io
import json
import sys
import tempfile
import unittest
import urllib.request
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
    def __init__(self, archive_fails=False, report_fails=False):
        self.objects = {}
        self.events = []
        self.archive_fails = archive_fails
        self.report_fails = report_fails

    def read(self, name):
        return self.objects.get(name)

    def put(self, name, raw, content_type='application/json', mutable=False):
        self.events.append(name)
        if self.report_fails and name.endswith('/report.json'):
            raise RuntimeError('Report upload failed')
        self.objects[name] = raw
        return {'key': name, 'sha256': daily.sha(raw), 'bytes': len(raw), 'url': 'https://catalog.test/' + name}

    def archive(self, name, path):
        self.events.append('archive')
        if self.archive_fails:
            raise RuntimeError('Archive upload failed')


class PublicationTests(unittest.TestCase):
    def run_daily(self, store, value=None):
        value = candidate() if value is None else value

        def build():
            output = Path(sys.argv[sys.argv.index('--out') + 1])
            identity_baseline = Path(sys.argv[sys.argv.index('--identity-baseline') + 1])
            identities = json.loads(identity_baseline.read_text())
            if 'current.json' in store.objects:
                current = json.loads(store.objects['current.json'])
                expected = json.loads(daily.gzip.decompress(store.objects[current['source_catalog']['key']]))
                self.assertEqual(identities, expected)
            output.write_text(json.dumps(value))
            return 0

        def download(url, **kwargs):
            if isinstance(url, urllib.request.Request):
                url = url.full_url
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
        report_event = next(name for name in store.events if name.endswith('/report.json'))
        self.assertLess(store.events.index(report_event), store.events.index('current.json'))
        current = json.loads(store.objects['current.json'])
        self.assertIn(current['catalog']['key'], store.objects)

    def test_archive_failure_leaves_current_manifest_untouched(self):
        store = FakeR2(archive_fails=True)
        result, report = self.run_daily(store)
        self.assertEqual(result, 1)
        self.assertNotIn('current.json', store.objects)
        self.assertIn('publication_error', report)

    def test_report_failure_leaves_previous_manifest_untouched(self):
        store = FakeR2()
        self.assertEqual(self.run_daily(store)[0], 0)
        previous = store.objects['current.json']
        store.report_fails = True
        result, report = self.run_daily(store)
        self.assertEqual(result, 1)
        self.assertEqual(store.objects['current.json'], previous)
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

    def test_unchanged_legacy_digest_does_not_force_publication_after_canonicalization(self):
        store = FakeR2()
        self.assertEqual(self.run_daily(store)[0], 0)
        original = json.loads(store.objects['current.json'])
        original['content_sha256'] = 'digest-from-previous-comparison-format'
        store.objects['current.json'] = daily.encode(original)
        result, report = self.run_daily(store)
        self.assertEqual(result, 0)
        self.assertEqual(report['publication'], 'unchanged')
        self.assertEqual(json.loads(store.objects['current.json'])['version'], original['version'])

    def test_valid_removal_hold_succeeds_without_changing_published_manifest(self):
        store = FakeR2()
        self.assertEqual(self.run_daily(store)[0], 0)
        previous = store.objects['current.json']
        value = candidate()
        value['places'][0]['place_id'] = 'replacement'
        value['serving'][0]['place_id'] = 'replacement'
        result, report = self.run_daily(store, value)
        self.assertTrue(report['valid'])
        self.assertFalse(report['promotable'])
        self.assertTrue(report['holds'])
        self.assertEqual(report['publication'], 'held')
        self.assertEqual(store.objects['current.json'], previous)
        saved = json.loads(store.objects['latest-observation.json'])
        self.assertEqual(saved['publication'], 'held')
        self.assertEqual(saved['holds'], report['holds'])
        self.assertEqual(result, 0, 'A fully saved, valid observation is successful even when promotion is held')

    def test_invalid_candidate_still_fails_without_changing_published_manifest(self):
        store = FakeR2()
        self.assertEqual(self.run_daily(store)[0], 0)
        previous = store.objects['current.json']
        value = candidate()
        value['sources'][-1].update(ok=0, fail=1)
        result, report = self.run_daily(store, value)
        self.assertEqual(result, 1)
        self.assertFalse(report['valid'])
        self.assertEqual(report['publication'], 'invalid')
        self.assertEqual(store.objects['current.json'], previous)

    def test_evidence_failure_during_hold_still_fails(self):
        for failure in ('archive_fails', 'report_fails'):
            with self.subTest(failure=failure):
                store = FakeR2()
                self.assertEqual(self.run_daily(store)[0], 0)
                previous = store.objects['current.json']
                setattr(store, failure, True)
                value = candidate()
                value['places'][0]['place_id'] = 'replacement'
                value['serving'][0]['place_id'] = 'replacement'
                result, report = self.run_daily(store, value)
                self.assertEqual(result, 1)
                self.assertTrue(report['valid'])
                self.assertFalse(report['promotable'])
                self.assertIn('publication_error', report)
                self.assertEqual(store.objects['current.json'], previous)


class ArchiveTests(unittest.TestCase):
    def test_interrupted_archive_leaves_loose_responses_available(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp) / 'responses'
            directory.mkdir()
            body = directory / 'response-0001.body'
            body.write_bytes(b'complete source response')
            destination = Path(temp) / 'responses.tar.gz'
            with patch.object(daily.tarfile, 'open') as opener:
                opener.return_value.__enter__.return_value.add.side_effect = OSError('disk full')
                with self.assertRaises(OSError):
                    daily.archive_responses(directory, destination)
            self.assertFalse(destination.exists())
            self.assertEqual(body.read_bytes(), b'complete source response')

    def test_successful_archive_contains_original_response(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp) / 'responses'
            directory.mkdir()
            (directory / 'response-0001.body').write_bytes(b'complete source response')
            destination = Path(temp) / 'responses.tar.gz'
            daily.archive_responses(directory, destination)
            with daily.tarfile.open(destination) as archive:
                self.assertEqual(archive.extractfile('responses/response-0001.body').read(), b'complete source response')


class PublicDownloadTests(unittest.TestCase):
    def test_identifies_publisher_to_public_download_host(self):
        raw = b'catalog bytes'

        def download(request, **kwargs):
            self.assertIsInstance(request, urllib.request.Request)
            self.assertEqual(request.get_header('User-agent'), 'transminuto-catalog/1.0')
            return io.BytesIO(raw)

        with patch('urllib.request.urlopen', side_effect=download):
            daily.verify_public({'url': 'https://catalog.test/catalog.json.gz', 'sha256': daily.sha(raw)})

    def test_rejects_changed_public_download(self):
        with patch('urllib.request.urlopen', return_value=io.BytesIO(b'wrong bytes')):
            with self.assertRaisesRegex(RuntimeError, 'checksum mismatch'):
                daily.verify_public({'url': 'https://catalog.test/catalog.json.gz', 'sha256': daily.sha(b'catalog bytes')})
