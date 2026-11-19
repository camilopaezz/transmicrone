from __future__ import annotations

import io
import json
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from probe_catalog_sources import ResponseCapture
import build_catalog


class CaptureTests(unittest.TestCase):
    def test_content_retries_retain_every_original_response(self):
        bodies = [b'<html>upstream failure</html>', b'{"recorrido":null}',
                  b'{"recorrido":[{"id":"1","codigo":"A","nombre":"Stop"}]}']
        responses = []
        for body in bodies:
            response = io.BytesIO(body)
            response.status = 200
            response.headers = {'Content-Type': 'application/json'}
            responses.append(response)
        with tempfile.TemporaryDirectory() as temp, \
             patch('urllib.request.urlopen', side_effect=responses), \
             patch.object(build_catalog.time, 'sleep'):
            capture = ResponseCapture(Path(temp), retries=2)
            with patch.object(build_catalog, 'http_json', capture.http_json):
                result = list(build_catalog.route_detail_results(
                    [{'id': '1', 'nombre': 'One', 'codigo': 'A1'}], pause=0))
            self.assertIsNone(result[0][2])
            self.assertEqual([path.read_bytes() for path in sorted(Path(temp).glob('*.body'))], bodies)

    def test_preserves_invalid_json_before_parse_failure(self):
        response = io.BytesIO(b"<html>upstream failure</html>")
        response.status = 200
        response.headers = {"Content-Type": "text/html"}
        with tempfile.TemporaryDirectory() as temp, patch("urllib.request.urlopen", return_value=response):
            directory = Path(temp)
            with self.assertRaises(json.JSONDecodeError):
                ResponseCapture(directory).http_json("https://example.test/catalog")
            self.assertEqual((directory / "response-0001.body").read_bytes(), b"<html>upstream failure</html>")
            metadata = json.loads((directory / "response-0001.json").read_text())
            self.assertEqual(metadata["status"], 200)
            self.assertEqual(metadata["url"], "https://example.test/catalog")

    def test_preserves_http_error_body_and_still_fails(self):
        error = urllib.error.HTTPError("https://example.test/catalog", 403, "Forbidden", {}, io.BytesIO(b"denied"))
        with tempfile.TemporaryDirectory() as temp, patch("urllib.request.urlopen", side_effect=error):
            directory = Path(temp)
            with self.assertRaises(urllib.error.HTTPError):
                ResponseCapture(directory).http_bytes("https://example.test/catalog")
            self.assertEqual((directory / "response-0001.body").read_bytes(), b"denied")
            metadata = json.loads((directory / "response-0001.json").read_text())
            self.assertEqual(metadata["status"], 403)
            self.assertIn("error", metadata)

    def test_records_connection_failure(self):
        with tempfile.TemporaryDirectory() as temp, patch("urllib.request.urlopen", side_effect=TimeoutError("timed out")):
            directory = Path(temp)
            with self.assertRaises(TimeoutError):
                ResponseCapture(directory).http_bytes("https://example.test/catalog")
            metadata = json.loads((directory / "response-0001.json").read_text())
            self.assertEqual(metadata["error"], "timed out")
            self.assertNotIn("body", metadata)
