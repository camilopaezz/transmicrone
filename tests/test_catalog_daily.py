from __future__ import annotations

import copy
import io
import json
import re
import sys
import tempfile
import unittest
import urllib.request
from contextlib import redirect_stdout, redirect_stderr
from datetime import datetime, timezone, timedelta
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import catalog_daily as daily


def candidate():
    now = datetime.now(timezone.utc)
    stamp = now.isoformat()
    return {
        'schema_version': 1, 'generated_at': stamp, 'failures': [], 'counts': {'places': 1},
        'places': [{'place_id': 'p1' if i == 0 else f'p{i + 1}', 'name': 'One',
                    'kind': 'paradero', 'query_paradero': f'Q{i}', 'served_painted': ['A1']}
                   for i in range(11)],
        'servicios': [{'route_id': 1, 'painted': 'A1'}],
        'serving': [{'place_id': 'p1' if i == 0 else f'p{i + 1}', 'route_id': 1} for i in range(11)],
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

    def test_pending_removal_does_not_hold_publication(self):
        value = candidate()
        baseline = copy.deepcopy(value)
        baseline['places'].append({'place_id': 'removed', 'name': 'Old'})
        result = self.assess(value, baseline)
        self.assertTrue(result['promotable'])
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
            self.assertIn('old-id', daily.assess(value, baseline, 0, datetime.now(timezone.utc), prior)['pending_removals']['places'])

    def test_removal_evidence_survives_invalid_day_without_counting_it(self):
        value = candidate()
        baseline = copy.deepcopy(value)
        baseline['places'][0]['place_id'] = 'old-id'
        now = datetime.now(timezone.utc)
        value['sources'][0]['name'] = (now - timedelta(days=2)).strftime('GTFS_%Y%m%d.zip')
        first = daily.assess(value, baseline, 0, now - timedelta(days=2))
        self.assertTrue(first['valid'], first)
        first['started_at'] = (now - timedelta(days=2)).isoformat()
        failed = daily.assess(value, baseline, 1, now - timedelta(days=1), first)
        self.assertEqual(failed['removal_evidence'], first['removal_evidence'])
        failed['started_at'] = (now - timedelta(days=1)).isoformat()
        final = daily.assess(value, baseline, 0, now, failed)
        self.assertTrue(final['promotable'], final)
        self.assertEqual(final['removal_evidence']['places']['old-id']['observations'], 2)

    def test_same_day_retries_do_not_add_removal_evidence(self):
        value = candidate()
        baseline = copy.deepcopy(value)
        baseline['places'][0]['place_id'] = 'old-id'
        now = datetime.now(timezone.utc)
        first = daily.assess(value, baseline, 0, now)
        first['started_at'] = now.isoformat()
        again = daily.assess(value, baseline, 0, now, first)
        self.assertIn('old-id', again['pending_removals']['places'])
        self.assertEqual(again['removal_evidence'], first['removal_evidence'])

    def test_reappearance_resets_pending_removal(self):
        value = candidate()
        baseline = copy.deepcopy(value)
        baseline['places'][0]['place_id'] = 'old-id'
        now = datetime.now(timezone.utc)
        value['sources'][0]['name'] = (now - timedelta(days=2)).strftime('GTFS_%Y%m%d.zip')
        first = daily.assess(value, baseline, 0, now - timedelta(days=2))
        self.assertTrue(first['valid'], first)
        first['started_at'] = (now - timedelta(days=2)).isoformat()
        reappeared = copy.deepcopy(baseline)
        reappeared['serving'][0]['place_id'] = 'old-id'
        reappeared['sources'] = copy.deepcopy(value['sources'])
        returned = daily.assess(reappeared, baseline, 0, now - timedelta(days=1), first)
        self.assertTrue(returned['valid'], returned)
        returned['started_at'] = (now - timedelta(days=1)).isoformat()
        self.assertFalse(returned['removal_evidence'].get('places'))
        missing = daily.assess(value, baseline, 0, now, returned)
        self.assertIn('old-id', missing['pending_removals']['places'])
        self.assertEqual(missing['removal_evidence']['places']['old-id']['observations'], 1)

    def test_confirmation_uses_bogota_day_boundaries(self):
        value = candidate()
        baseline = copy.deepcopy(value)
        baseline['places'][0]['place_id'] = 'old-id'
        today = datetime.now(timezone.utc).replace(hour=5, minute=30)
        value['sources'][0]['name'] = (today - timedelta(days=1)).strftime('GTFS_%Y%m%d.zip')
        first = daily.assess(value, baseline, 0, today - timedelta(hours=1))
        self.assertTrue(first['valid'])
        second = daily.assess(value, baseline, 0, today, first)
        self.assertTrue(second['promotable'])
        # Different UTC dates can still be the same calendar day in Bogotá.
        late = today.replace(hour=1)
        first = daily.assess(value, baseline, 0, late - timedelta(hours=2))
        self.assertTrue(first['valid'])
        second = daily.assess(value, baseline, 0, late, first)
        self.assertIn('old-id', second['pending_removals']['places'])


    def test_gross_losses_cannot_be_hidden_by_additions(self):
        baseline = candidate()
        value = copy.deepcopy(baseline)
        for i in range(2):
            old_id = value['places'][i]['place_id']
            value['places'][i]['place_id'] = f'new-{i}'
            value['serving'][i]['place_id'] = f'new-{i}'
        result = self.assess(value, baseline)
        self.assertFalse(result['valid'])
        self.assertIn('places: more than 10% of records disappeared', result['errors'])

    def test_retained_edge_keeps_confirmed_parent_dependencies_and_packed_visibility(self):
        baseline = candidate()
        baseline['servicios'][0].update(catalog_id='1', catalog_ids_sharing_painted=['1'])
        value = copy.deepcopy(baseline)
        value['places'] = value['places'][1:]
        value['serving'] = []
        value['servicios'] = []
        value['service_routes'] = []
        value['service_hours'] = []
        merged, retained = daily.publication_catalog(value, baseline, {'serving': ['p1:1']})
        self.assertEqual(retained['places'], {'p1': 'serving_dependency'})
        self.assertEqual(retained['servicios'], {'1': 'serving_dependency'})
        self.assertEqual(retained['service_routes'], {'1': 'service_dependency'})
        self.assertEqual(merged['service_routes'], baseline['service_routes'])
        packed = daily.pack(merged)
        self.assertEqual([p['place_id'] for p in packed['places']], ['p1'])
        self.assertEqual(packed['servicios'][0]['route_ids'], [1])
        self.assertEqual(merged['counts']['serving_edges'], 1)
        self.assertEqual(value['serving'], [], 'Merging must not mutate the source observation')


    def test_missing_optional_catalog_metadata_does_not_block_publication(self):
        value = candidate()
        value['servicios'][0].update(catalog_id='6430', catalog_ids_sharing_painted=['6430'])
        merged, retained = daily.publication_catalog(value, value, {})
        self.assertEqual(merged['servicios'], value['servicios'])
        self.assertFalse(any(retained.values()))


class AndroidSizeTests(unittest.TestCase):
    def test_limits_match_android(self):
        source = (Path(__file__).resolve().parents[1] / 'android/app/src/main/kotlin/co/camilo/transmicrone/data/catalog/PublishedCatalogRepository.kt').read_text()
        def limit(name):
            match = re.search(rf'const val {name} = (\d+)L \* 1024 \* 1024', source)
            self.assertIsNotNone(match, name)
            return int(match.group(1)) * 1024 * 1024
        self.assertEqual(limit('MAX_DOWNLOAD_BYTES'), daily.ANDROID_MAX_DOWNLOAD_BYTES)
        self.assertEqual(limit('MAX_CATALOG_BYTES'), daily.ANDROID_MAX_CATALOG_BYTES)

    def test_warns_near_limits_and_rejects_above_them(self):
        small = daily.ANDROID_MAX_DOWNLOAD_BYTES // 2
        self.assertEqual(daily.android_size_check(small, small), ([], []))
        errors, warnings = daily.android_size_check(daily.ANDROID_MAX_DOWNLOAD_BYTES - 1, small)
        self.assertEqual((len(errors), len(warnings)), (0, 1))
        errors, warnings = daily.android_size_check(small, daily.ANDROID_MAX_CATALOG_BYTES + 1)
        self.assertEqual((len(errors), len(warnings)), (1, 0))


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
    def run_daily(self, store, value=None, build_error=None):
        value = candidate() if value is None else value

        def build():
            if build_error:
                raise RuntimeError(build_error)
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

    def test_unchanged_candidate_cannot_refresh_a_corrupt_retained_download(self):
        store = FakeR2()
        self.assertEqual(self.run_daily(store)[0], 0)
        original = json.loads(store.objects['current.json'])
        original['catalog'].update(key='retained/catalog.json.gz',
                                   url='https://catalog.test/retained/catalog.json.gz')
        store.objects['retained/catalog.json.gz'] = b'corrupt'
        store.objects['current.json'] = daily.encode(original)
        previous = store.objects['current.json']
        result, report = self.run_daily(store)
        self.assertEqual(result, 1)
        self.assertIn('checksum mismatch', report['publication_error'])
        self.assertEqual(store.objects['current.json'], previous)

    def test_catalog_android_cannot_load_is_not_published(self):
        store = FakeR2()
        self.assertEqual(self.run_daily(store)[0], 0)
        previous = store.objects['current.json']
        value = candidate()
        value['places'][0]['name'] = 'Changed'
        with patch.object(daily, 'ANDROID_MAX_CATALOG_BYTES', 10):
            result, report = self.run_daily(store, value)
        self.assertEqual(result, 1)
        self.assertEqual(report['publication'], 'invalid')
        self.assertTrue(any('exceeds the app limit' in error for error in report['errors']))
        self.assertEqual(store.objects['current.json'], previous)

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

    def test_pending_removal_publishes_updates_and_retains_missing_records(self):
        store = FakeR2()
        self.assertEqual(self.run_daily(store)[0], 0)
        previous = store.objects['current.json']
        value = candidate()
        value['places'][0]['place_id'] = 'replacement'
        value['serving'][0]['place_id'] = 'replacement'
        result, report = self.run_daily(store, value)
        self.assertTrue(report['valid'])
        self.assertTrue(report['promotable'])
        self.assertIn('p1', report['pending_removals']['places'])
        self.assertEqual(report['publication'], 'published')
        self.assertNotEqual(store.objects['current.json'], previous)
        current = json.loads(store.objects['current.json'])
        published = json.loads(daily.gzip.decompress(store.objects[current['source_catalog']['key']]))
        self.assertIn('p1', {p['place_id'] for p in published['places']})
        self.assertIn('replacement', {p['place_id'] for p in published['places']})
        saved = json.loads(store.objects['latest-observation.json'])
        self.assertEqual(saved['publication'], 'published')
        self.assertEqual(saved['holds'], report['holds'])
        self.assertEqual(result, 0)

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

    def test_aborted_build_preserves_evidence_and_later_valid_day_confirms_removal(self):
        store = FakeR2()
        self.assertEqual(self.run_daily(store)[0], 0)
        yesterday = (datetime.now(timezone.utc) - timedelta(days=1)).astimezone(daily.ZoneInfo('America/Bogota')).date().isoformat()
        ledger = {'places': {'p1': {'first_seen': yesterday, 'last_seen': yesterday, 'observations': 1}}}
        ledger['serving'] = {'p1:1': dict(ledger['places']['p1'])}
        store.objects['latest-observation.json'] = daily.encode({'valid': True, 'removal_evidence': ledger})
        result, failed = self.run_daily(store, build_error='source unavailable')
        self.assertEqual(result, 1)
        self.assertEqual(json.loads(store.objects['latest-observation.json'])['removal_evidence'], ledger)
        self.assertNotIn('valid', failed)
        value = candidate()
        value['places'][0]['place_id'] = 'replacement'
        value['serving'][0]['place_id'] = 'replacement'
        result, report = self.run_daily(store, value)
        self.assertEqual(result, 0)
        self.assertEqual(report['publication'], 'published')
        self.assertEqual(report['removal_evidence']['places']['p1']['observations'], 2)

    def test_failure_to_read_observation_cannot_overwrite_its_evidence(self):
        store = FakeR2()
        self.assertEqual(self.run_daily(store)[0], 0)
        saved = store.objects['latest-observation.json']
        read = store.read

        def unavailable(name):
            if name == 'latest-observation.json':
                raise RuntimeError('R2 read failed')
            return read(name)

        with patch.object(store, 'read', side_effect=unavailable):
            self.assertEqual(self.run_daily(store)[0], 1)
        self.assertEqual(store.objects['latest-observation.json'], saved)

    def test_selective_publication_refreshes_published_health(self):
        store = FakeR2()
        self.assertEqual(self.run_daily(store)[0], 0)
        current = json.loads(store.objects['current.json'])
        old = (datetime.now(timezone.utc) - timedelta(days=3)).isoformat()
        current.update(checked_at=old, generated_at=old)
        store.objects['current.json'] = daily.encode(current)
        value = candidate()
        value['places'][0]['place_id'] = 'replacement'
        value['serving'][0]['place_id'] = 'replacement'
        result, report = self.run_daily(store, value)
        self.assertEqual(result, 0)
        self.assertEqual(report['publication'], 'published')
        self.assertEqual(report['publication_health']['status'], 'fresh')
        self.assertNotEqual(json.loads(store.objects['current.json'])['checked_at'], old)

    def test_evidence_failure_during_selective_publication_still_fails(self):
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
                self.assertTrue(report['promotable'])
                self.assertIn('publication_error', report)
                self.assertEqual(store.objects['current.json'], previous)


    def test_next_day_confirms_removal_after_selective_baseline_advances(self):
        store = FakeR2()
        original = candidate()
        self.assertEqual(self.run_daily(store, original)[0], 0)
        value = copy.deepcopy(original)
        value['places'][0]['name'] = 'Updated'
        value['serving'] = value['serving'][1:]
        result, first = self.run_daily(store, value)
        self.assertEqual(result, 0)
        self.assertEqual(first['publication'], 'published')
        self.assertEqual(first['retained_records']['serving'], {'p1:1': 'pending_removal'})
        current = json.loads(store.objects['current.json'])
        published = json.loads(daily.gzip.decompress(store.objects[current['source_catalog']['key']]))
        self.assertEqual(published['places'][0]['name'], 'Updated')
        self.assertEqual(len(published['serving']), 11)
        yesterday = (datetime.now(timezone.utc) - timedelta(days=1)).astimezone(daily.ZoneInfo('America/Bogota')).date().isoformat()
        entry = first['removal_evidence']['serving']['p1:1']
        entry.update(first_seen=yesterday, last_seen=yesterday)
        store.objects['latest-observation.json'] = daily.encode(first)
        result, second = self.run_daily(store, value)
        self.assertEqual(result, 0)
        self.assertEqual(second['publication'], 'published')
        self.assertFalse(second['pending_removals']['serving'])
        current = json.loads(store.objects['current.json'])
        published = json.loads(daily.gzip.decompress(store.objects[current['source_catalog']['key']]))
        self.assertEqual(len(published['serving']), 10)
        self.assertFalse(published['places'][0]['served_painted'])


    def test_pending_removal_only_verifies_unchanged_publication(self):
        store = FakeR2()
        value = candidate()
        self.assertEqual(self.run_daily(store, value)[0], 0)
        original = json.loads(store.objects['current.json'])
        value['serving'] = value['serving'][1:]
        value['places'][0]['served_painted'] = []
        result, report = self.run_daily(store, value)
        self.assertEqual(result, 0)
        self.assertEqual(report['publication'], 'unchanged')
        current = json.loads(store.objects['current.json'])
        self.assertEqual(current['version'], original['version'])
        self.assertIn('p1:1', report['retained_records']['serving'])
        self.assertEqual(report['observed_counts'], value['counts'])
        self.assertEqual(report['counts']['serving_edges'], 11)

    def test_public_preview_uses_baseline_without_creating_r2_client_or_writing(self):
        store = FakeR2()
        value = candidate()
        self.assertEqual(self.run_daily(store, value)[0], 0)
        previous = copy.deepcopy(store.objects)
        value['places'][0]['name'] = 'Updated'
        value['serving'] = value['serving'][1:]

        def build():
            Path(sys.argv[sys.argv.index('--out') + 1]).write_bytes(daily.encode(value))
            return 0

        with tempfile.TemporaryDirectory() as temp, \
             patch.object(sys, 'argv', ['daily', '--out', temp, '--preview']), \
             patch.object(daily, 'PublicBaseline', return_value=store), \
             patch.object(daily, 'R2') as private, \
             patch.object(daily.build_catalog, 'main', side_effect=build), \
             redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            result = daily.main()
            report = json.loads((Path(temp) / 'report.json').read_text())
            published = json.loads((Path(temp) / 'publication-catalog.json').read_text())
            self.assertEqual(report['mode'], 'preview')
            self.assertEqual(report['publication'], 'preview')
            self.assertEqual(len(published['serving']), 11)
            self.assertEqual(published['places'][0]['name'], 'Updated')
            self.assertIn('p1:1', report['retained_records']['serving'])
            self.assertEqual(result, 0)
            private.assert_not_called()
        self.assertEqual(store.objects, previous)


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
