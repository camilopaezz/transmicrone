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


class CaptureTests(unittest.TestCase):
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
