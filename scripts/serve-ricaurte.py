#!/usr/bin/env python3
"""Serve ricaurte.html and proxy Bodega so the board can refresh in a browser.

Usage: python3 scripts/serve-ricaurte.py
Then open http://<host>:8765/
"""

from __future__ import annotations

import sys
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from feed_config import APPID, BASE_URL, INSTALL_UUID

ROOT = Path(__file__).resolve().parent.parent
HTML = ROOT / "ricaurte.html"
PORT = 8765


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        if self.path.split("?", 1)[0] in ("/", "/ricaurte.html"):
            if not HTML.is_file():
                self.send_error(404, "ricaurte.html is not present in this checkout")
                return
            data = HTML.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)
            return
        self.send_error(404)

    def do_POST(self) -> None:
        if not self.path.startswith("/proxy/"):
            self.send_error(404)
            return
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n)
        upstream = BASE_URL + self.path[len("/proxy") :]
        headers = {"appid": self.headers.get("appid") or APPID, "uuid": INSTALL_UUID}
        ctype = self.headers.get("Content-Type")
        if ctype:
            headers["Content-Type"] = ctype
        if not body:
            headers["Content-Length"] = "0"
        req = urllib.request.Request(upstream, data=body or b"", headers=headers, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=20) as resp:
                raw = resp.read()
                status = resp.status
                out_type = resp.headers.get("Content-Type", "application/json")
        except urllib.error.HTTPError as e:
            raw = e.read()
            status = e.code
            out_type = e.headers.get("Content-Type", "application/json")
        self.send_response(status)
        self.send_header("Content-Type", out_type)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def log_message(self, fmt: str, *args: object) -> None:
        sys.stderr.write("%s\n" % (args[0] if args else fmt))


if __name__ == "__main__":
    print(f"http://0.0.0.0:{PORT}/", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
