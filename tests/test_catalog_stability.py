"""Catalog identities and content must not depend on source row order."""
from __future__ import annotations

import copy
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import build_catalog as builder
import catalog_daily as daily
from pack_android_catalog import pack


class CatalogStabilityTests(unittest.TestCase):
    def build(self, reverse=False, baseline=None, tm_code='TM0001', gis=None):
        tables = {
            'feed_info': [],
            'routes': [{'route_id': str(i), 'route_short_name': 'A1',
                        'route_long_name': 'North', 'agency_id': '1'} for i in (101, 102)],
            'stops': [
                {'stop_id': '10', 'location_type': '1', 'stop_name': 'Station',
                 'stop_lat': '4', 'stop_lon': '-74'},
                *[{'stop_id': str(i), 'parent_station': '10', 'location_type': '0',
                   'stop_name': f'Platform {i}'} for i in (11, 12)],
            ],
            'trips': [{'trip_id': 't1', 'route_id': '101'}, {'trip_id': 't2', 'route_id': '102'}],
        }
        rows = [{'id': str(i), 'codigo': 'A1', 'nombre': 'North', 'horarios': {'data': [
            {'convencion': 'S', 'hora_inicio': '4:00 AM', 'hora_fin': '11:00 PM'}]}}
                for i in (201, 202)]
        gis = gis if gis is not None else [
            {'route_id': 101, 'cod_ruta': 'OTHER', 'cod_linea': 'WRONG'},
            {'route_id': 101, 'cod_ruta': 'A1', 'cod_linea': 'RIGHT'},
        ]
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            (work / 'stop_times.txt').write_text('trip_id,stop_id\nt1,11\nt2,11\n')
            argv = ['builder', '--gtfs-dir', str(work), '--out', str(work / 'catalog.json'),
                    '--test-out', str(work / 'test.json'), '--sleep', '0']
            if baseline is not None:
                (work / 'baseline.json').write_text(json.dumps(baseline))
                argv += ['--identity-baseline', str(work / 'baseline.json')]
            def read(path):
                values = tables[path.stem]
                return list(reversed(values)) if reverse else values
            def query(layer, *args, **kwargs):
                values = gis if layer == builder.SERVICIOS_LAYER else []
                return list(reversed(values)) if reverse else values
            stop = {'id': '10', 'codigo': tm_code or 'unmapped', 'nombre': 'Station',
                    'posicion': 0, 'parada': '1'}
            with patch.object(sys, 'argv', argv), patch.object(builder, 'read_csv', side_effect=read), \
                 patch.object(builder, 'gis_query_all', side_effect=query), \
                 patch.object(builder, 'catalog_search_all', return_value=list(reversed(rows)) if reverse else rows), \
                 patch.object(builder, 'catalog_info', return_value={'recorrido': [stop]}), \
                 redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                self.assertEqual(builder.main(), 0)
            return json.loads((work / 'catalog.json').read_text())

    def test_permuting_unique_source_rows_preserves_content_and_packed_catalog(self):
        first, second = self.build(), self.build(reverse=True)
        payload = lambda c: {s: daily.records(c, s) for s in daily.SECTIONS}
        self.assertEqual(payload(first), payload(second))
        self.assertEqual(daily.clean(pack(first)), daily.clean(pack(second)))

    def test_gis_match_uses_painted_direction_instead_of_last_row(self):
        for reverse in (False, True):
            result = self.build(reverse=reverse)
            service = next(s for s in result['servicios'] if s['route_id'] == 101)
            self.assertEqual(service['gis_cod_ruta'], 'A1')
            self.assertEqual(service['gis_cod_linea'], 'RIGHT')

    def test_conflicting_gis_matches_are_reported_without_selecting_a_row(self):
        gis = [{'route_id': 101, 'cod_ruta': 'A1', 'cod_linea': line} for line in ('X', 'Y')]
        result = self.build(gis=gis)
        service = next(s for s in result['servicios'] if s['route_id'] == 101)
        self.assertIsNone(service['gis_cod_linea'])
        self.assertTrue(any(w['source'] == 'gis-service-ambiguity' for w in result['warnings']))

    def test_station_identity_survives_lost_or_changed_tm_mapping(self):
        baseline = {'places': [{'place_id': 'estacion:TM0001', 'kind': 'estacion', 'nodo': 10}]}
        for tm_code in (None, 'TM0002'):
            result = self.build(baseline=baseline, tm_code=tm_code)
            self.assertEqual(result['places'][0]['place_id'], 'estacion:TM0001')
            self.assertEqual(result['places'][0]['query_paradero'], tm_code)
        self.assertEqual(self.build()['places'][0]['place_id'], 'estacion-nodo:10')

    def test_comparison_canonicalizes_sets_but_preserves_route_stop_order(self):
        value = self.build()
        shuffled = copy.deepcopy(value)
        for place in shuffled['places']:
            for key in ('platforms', 'served_route_ids', 'served_painted'):
                place[key].reverse()
        for service in shuffled['servicios']:
            service['catalog_ids_sharing_painted'].reverse()
        self.assertEqual({s: daily.records(value, s) for s in daily.SECTIONS},
                         {s: daily.records(shuffled, s) for s in daily.SECTIONS})
        shuffled['service_routes'][0]['stops'].append({'code': 'OTHER'})
        self.assertTrue(daily.compare(value, shuffled)['service_routes']['changed'])

    def test_gis_offset_pagination_uses_object_id_order(self):
        calls = []
        def fetch(url, **kwargs):
            calls.append(url)
            if '/query?' not in url:
                return {'objectIdField': 'FID'}
            return {'features': []}
        with patch.object(builder, 'http_json', side_effect=fetch):
            builder.gis_query_all('https://gis.example/layer', 'name')
        query = parse_qs(urlparse(calls[-1]).query)
        self.assertEqual(query.get('orderByFields'), ['FID ASC'])

    def test_gis_pagination_rejects_overlapping_pages(self):
        responses = [
            {'objectIdField': 'FID'},
            {'features': [{'attributes': {'FID': 1}}], 'exceededTransferLimit': True},
            {'features': [{'attributes': {'FID': 1}}]},
        ]
        with patch.object(builder, 'http_json', side_effect=responses):
            with self.assertRaisesRegex(ValueError, 'repeated GIS object ID'):
                builder.gis_query_all('https://gis.example/layer', 'name', page=1)

    def test_duplicate_baseline_station_nodes_fail_instead_of_picking_an_identity(self):
        baseline = {'places': [
            {'place_id': f'estacion:{code}', 'kind': 'estacion', 'nodo': 10}
            for code in ('TM0001', 'TM0002')
        ]}
        with self.assertRaisesRegex(ValueError, 'Ambiguous station identities'):
            self.build(baseline=baseline)


if __name__ == '__main__':
    unittest.main()
