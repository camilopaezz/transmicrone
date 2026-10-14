from __future__ import annotations

import importlib.util
import io
import json
import sys
import tempfile
import types
import unittest
from unittest.mock import patch
from contextlib import redirect_stdout, redirect_stderr
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import build_catalog  # noqa: E402
import pack_android_catalog  # noqa: E402


def load_eta_module():
    """Load eta without reading local feed configuration or creating an ID."""
    fake_config = types.ModuleType("feed_config")
    fake_config.APPID = "test"
    fake_config.BASE_URL = "https://example.invalid"
    fake_config.INSTALL_UUID = "test"
    previous = sys.modules.get("feed_config")
    sys.modules["feed_config"] = fake_config
    try:
        spec = importlib.util.spec_from_file_location("eta_under_test", ROOT / "scripts" / "eta.py")
        assert spec and spec.loader
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    finally:
        if previous is None:
            sys.modules.pop("feed_config", None)
        else:
            sys.modules["feed_config"] = previous


eta = load_eta_module()


class InfoRutaShapeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads((ROOT / "data/fixtures/infoRuta-E48.json").read_text())

    def test_bare_array_fixture_preserves_stop_order(self):
        stops = build_catalog.info_route_stops(self.fixture)
        self.assertEqual(stops, self.fixture["recorrido"])
        self.assertEqual([stop["codigo"] for stop in stops], [
            "TM0103", "TM0102", "TM0101", "TM0100", "TM0149", "TM0096",
            "TM0095", "TM0093", "TM0089", "TM0069", "TM0067",
        ])

    def test_object_data_shape_preserves_stop_order(self):
        stops = build_catalog.info_route_stops(
            {**self.fixture, "recorrido": {"data": self.fixture["recorrido"]}}
        )
        self.assertEqual(stops, self.fixture["recorrido"])

    def test_unrecognized_shape_is_rejected(self):
        for info in ({"recorrido": {"items": []}}, {"recorrido": None}, []):
            with self.subTest(info=info), self.assertRaises(ValueError):
                build_catalog.info_route_stops(info)


class OperatingHoursTests(unittest.TestCase):
    def test_published_windows_survive_packing_with_source_identity_and_date(self):
        rows = [{"id": "12444", "codigo": "E48", "nombre": "CA\u0301D", "horarios": {"data": [
            {"convencion": "L-V", "hora_inicio": "4:00 AM ", "hora_fin": "10:00 AM"},
            {"convencion": "L-V", "hora_inicio": "4:00 PM", "hora_fin": "12:30 am"},
            {"convencion": "D-F", "hora_inicio": "", "hora_fin": "10:00 AM"},
        ]}}]
        hours = build_catalog.service_operating_hours(rows, "2026-10-13T01:00:00Z")
        self.assertEqual(hours[0]["catalog_id"], "12444")
        self.assertEqual(hours[0]["destino"], "CÁD")
        self.assertEqual(hours[0]["fetched_at"], "2026-10-13T01:00:00Z")
        self.assertEqual(hours[0]["windows"], [
            {"day_group": "L-V", "start_time": "04:00", "end_time": "10:00"},
            {"day_group": "L-V", "start_time": "16:00", "end_time": "00:30"},
        ])
        self.assertEqual(pack_android_catalog.pack({"service_hours": hours})["service_hours"], hours)

    def test_missing_hours_do_not_invent_a_schedule(self):
        self.assertEqual(build_catalog.service_operating_hours([
            {"id": "1", "codigo": "E48", "nombre": "CAD"},
        ], "2026-10-13"), [])


class OfflineRouteTests(unittest.TestCase):
    def test_packing_preserves_order_repeated_visits_and_pass_through_stops(self):
        info = {"recorrido": {"data": [
            {"codigo": "A", "nombre": "Alpha", "posicion": 2, "parada": "1"},
            {"codigo": "A", "nombre": "Alpha", "posicion": 0, "parada": "1", "vagon": "B"},
            {"codigo": "C", "nombre": "Charlie", "posicion": 1, "parada": "0"},
        ]}}
        route = build_catalog.service_route({"id": "api-id", "codigo": "E48", "nombre": "CAD"}, info, "2026-10-13")
        self.assertEqual([stop["code"] for stop in route["stops"]], ["A", "C", "A"])
        self.assertEqual(route["stops"][0]["wagon"], "B")
        self.assertFalse(route["stops"][1]["stops_here"])
        self.assertEqual(pack_android_catalog.pack({"service_routes": [route]})["service_routes"], [route])


class CatalogRebuildTests(unittest.TestCase):
    def test_failed_catalog_search_leaves_existing_output_untouched(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            gtfs = work / "gtfs"
            gtfs.mkdir()
            for name in ["feed_info", "routes", "stops", "trips", "stop_times"]:
                (gtfs / f"{name}.txt").write_text("unused\n")
            output = work / "catalog.json"
            original = '{"service_routes":[{"catalog_id":"old"}]}'
            output.write_text(original)
            with patch.object(build_catalog, "DATA", work), \
                    patch.object(sys, "argv", ["build_catalog", "--gtfs-dir", str(gtfs), "--out", str(output)]), \
                    patch.object(build_catalog, "gis_query_all", return_value=[]), \
                    patch.object(build_catalog, "catalog_search_all", side_effect=OSError("unavailable")), \
                    redirect_stderr(io.StringIO()):
                with self.assertRaisesRegex(RuntimeError, "existing catalog was not overwritten"):
                    build_catalog.main()
            self.assertEqual(output.read_text(), original)

    def rebuild(self, flags, response, changed_destination=False):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            gtfs = work / "gtfs"
            gtfs.mkdir()
            for name in ["feed_info", "routes", "stops", "trips", "stop_times"]:
                (gtfs / f"{name}.txt").write_text("unused\n")
            rows = [{"id": str(i), "codigo": "2-5", "nombre": name, "horarios": {"data": [
                {"convencion": "S", "hora_inicio": start, "hora_fin": "12:30 AM"},
            ]}} for i, name, start in [(1, "North", "4:30 AM"), (2, "South", "5:00 AM")]]
            old = [{"catalog_id": str(i), "painted": "2-5", "destino": name,
                    "fetched_at": "old", "stops": [{"code": "OLD", "name": "Old stop"}]}
                   for i, name in [(1, "North"), (2, "South"), (999, "Removed")]]
            if changed_destination:
                old[1]["destino"] = "Obsolete destination"
            output = work / "catalog.json"
            output.write_text(json.dumps({"service_routes": old}))
            argv = ["build_catalog", "--gtfs-dir", str(gtfs), "--out", str(output), "--sleep", "0", *flags]
            with patch.object(build_catalog, "DATA", work), patch.object(sys, "argv", argv), \
                    patch.object(build_catalog, "gis_query_all", return_value=[]), \
                    patch.object(build_catalog, "catalog_search_all", return_value=rows), \
                    patch.object(build_catalog, "catalog_info", side_effect=response) as fetch, \
                    redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                status = build_catalog.main()
            catalog = json.loads(output.read_text())
            self.assertEqual(status, 1 if any(f["source"] == "infoRuta" for f in catalog["failures"]) else 0)
            return catalog, old, fetch.call_count

    @staticmethod
    def good_response(*args):
        return {"recorrido": [{"codigo": "NEW", "nombre": "New stop", "parada": "1"}]}

    def test_skip_preserves_routes_and_original_dates(self):
        catalog, old, calls = self.rebuild(["--skip-info-ruta"], self.good_response)
        self.assertEqual(catalog["service_routes"], old[:2])
        self.assertEqual(calls, 0)
        self.assertEqual(len(catalog["service_hours"]), 2)

    def test_limit_refreshes_one_route_and_preserves_the_other(self):
        catalog, old, calls = self.rebuild(["--info-limit", "1"], self.good_response)
        routes = {r["catalog_id"]: r for r in catalog["service_routes"]}
        self.assertEqual(routes["1"]["stops"][0]["code"], "NEW")
        self.assertEqual(routes["2"], old[1])
        self.assertEqual(set(routes), {"1", "2"})
        self.assertEqual(calls, 1)

    def test_reused_catalog_id_does_not_keep_an_obsolete_destination(self):
        catalog, old, _ = self.rebuild(["--skip-info-ruta"], self.good_response, changed_destination=True)
        self.assertEqual(catalog["service_routes"], old[:1])

    def test_failed_or_empty_response_preserves_previous_route(self):
        for empty in [False, True]:
            with self.subTest(empty=empty):
                def response(catalog_id, *args):
                    if catalog_id == "2":
                        if empty:
                            return {"recorrido": []}
                        raise OSError("catalog unavailable")
                    return self.good_response()
                catalog, old, calls = self.rebuild([], response)
                routes = {r["catalog_id"]: r for r in catalog["service_routes"]}
                self.assertEqual(routes["1"]["stops"][0]["code"], "NEW")
                self.assertEqual(routes["2"], old[1])
                self.assertEqual(set(routes), {"1", "2"})
                self.assertEqual(len(catalog["service_hours"]), 2)
                self.assertEqual(calls, 2)
                self.assertEqual(catalog["failures"][0]["source"], "infoRuta")


class EtaSelectionTests(unittest.TestCase):
    def test_filters_get_servicios_to_selected_route_variant(self):
        rows = [
            {"ruta_sae": 12819, "ruta_extraida": "S43", "destino_limpio": "Ricaurte"},
            {"ruta_sae": "12820", "ruta_extraida": "S43", "destino_limpio": "Ricaurte"},
            {"ruta_sae": 12819, "ruta_extraida": "S43", "destino_limpio": "Usme"},
            {"ruta_sae": 12819, "ruta_extraida": "S48", "destino_limpio": "Ricaurte"},
        ]
        self.assertEqual(
            eta.matching_services(rows, "S43", 12819, "Ricaurte"),
            [rows[0]],
        )


if __name__ == "__main__":
    unittest.main()
