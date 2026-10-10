from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import threading
import types
import urllib.error
import urllib.request
import unittest
from http.server import HTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import build_catalog  # noqa: E402


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


def load_serve_module():
    fake_config = types.ModuleType("feed_config")
    fake_config.APPID = "test"
    fake_config.BASE_URL = "https://example.invalid"
    fake_config.INSTALL_UUID = "test"
    previous = sys.modules.get("feed_config")
    sys.modules["feed_config"] = fake_config
    try:
        spec = importlib.util.spec_from_file_location(
            "serve_ricaurte_under_test", ROOT / "scripts" / "serve-ricaurte.py"
        )
        assert spec and spec.loader
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module
    finally:
        if previous is None:
            sys.modules.pop("feed_config", None)
        else:
            sys.modules["feed_config"] = previous


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


class RicaurteServerTests(unittest.TestCase):
    def test_missing_html_returns_404(self):
        serve = load_serve_module()
        with tempfile.TemporaryDirectory() as directory:
            serve.HTML = Path(directory) / "missing.html"
            server = HTTPServer(("127.0.0.1", 0), serve.Handler)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                url = f"http://127.0.0.1:{server.server_port}/"
                with self.assertRaises(urllib.error.HTTPError) as error:
                    urllib.request.urlopen(url)
                self.assertEqual(error.exception.code, 404)
                self.assertIn(b"ricaurte.html is not present", error.exception.read())
                error.exception.close()
            finally:
                server.shutdown()
                server.server_close()
                thread.join()


if __name__ == "__main__":
    unittest.main()
