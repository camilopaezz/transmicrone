#!/usr/bin/env python3
"""Check catalog source access without live-feed credentials or catalog writes."""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import time
import zipfile
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch

import build_catalog as catalog


class ResponseCapture:
    """Persist response bytes before parsing, including HTTP error bodies."""

    def __init__(self, directory: Path):
        self.directory = directory
        self.directory.mkdir(parents=True, exist_ok=True)
        self.sequence = 0

    def http_bytes(self, url: str, timeout: float = 120) -> bytes:
        self.sequence += 1
        stem = f"response-{self.sequence:04d}"
        metadata = {"url": url, "requested_at": datetime.now(timezone.utc).isoformat()}
        try:
            request = urllib.request.Request(url, headers={"User-Agent": catalog.UA})
            http_error = None
            try:
                response = urllib.request.urlopen(request, timeout=timeout)
            except urllib.error.HTTPError as error:
                response = error
                http_error = error
            with response:
                metadata.update(status=response.status, headers=dict(response.headers))
                raw = response.read()
            (self.directory / f"{stem}.body").write_bytes(raw)
            metadata.update(body=f"{stem}.body", bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())
            if http_error is not None:
                raise http_error
            return raw
        except Exception as error:
            metadata["error"] = str(error)
            raise
        finally:
            (self.directory / f"{stem}.json").write_text(
                json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
            )

    def http_json(self, url: str, timeout: float = 30) -> object:
        return json.loads(self.http_bytes(url, timeout).decode())


def fingerprint(value: object, unordered: bool = False) -> str:
    """Ignore object key order, and optionally row order, when comparing data."""
    if unordered:
        value = sorted(value, key=lambda row: json.dumps(row, sort_keys=True, ensure_ascii=False))
    raw = json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()
    return hashlib.sha256(raw).hexdigest()


def probe_gtfs() -> dict:
    url, metadata = catalog.discover_gtfs_zip()
    raw = catalog.http_bytes(url, timeout=180)
    rows = {}
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        for name, required in {
            "agency.txt": "agency_name",
            "routes.txt": "route_id",
            "stops.txt": "stop_id",
            "trips.txt": "trip_id",
            "stop_times.txt": "stop_id",
            "feed_info.txt": "feed_publisher_name",
        }.items():
            with archive.open(name) as source:
                reader = csv.DictReader(io.TextIOWrapper(source, encoding="utf-8-sig"))
                if required not in (reader.fieldnames or []):
                    raise ValueError(f"{name} missing {required}")
                first = next(reader, None)
                if not first or not first.get(required):
                    raise ValueError(f"{name} has no usable first row")
                rows[name] = first if name == "feed_info.txt" else "first row valid"
    return {"url": url, "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest(), "metadata": metadata, "files": rows}


def probe_gis(layer: str, required: str) -> dict:
    rows = catalog.gis_query_all(layer, "*")
    if not rows or not any(row.get(required) is not None for row in rows):
        raise ValueError(f"No usable GIS rows for {required}")
    return {"url": layer, "rows": len(rows), "sha256": fingerprint(rows, unordered=True)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    capture = ResponseCapture(args.out.with_suffix(".responses"))
    with patch.object(catalog, "http_bytes", capture.http_bytes), patch.object(catalog, "http_json", capture.http_json):
        return run_probe(args.out)


def run_probe(output: Path) -> int:
    results = []

    def check(name, operation):
        started = time.monotonic()
        try:
            details = operation()
            result = {"name": name, "ok": True, "details": details}
        except (Exception, SystemExit) as error:
            result = {"name": name, "ok": False, "error": str(error)}
        result["seconds"] = round(time.monotonic() - started, 2)
        results.append(result)
        print(json.dumps(result, ensure_ascii=False), flush=True)

    check("gtfs", probe_gtfs)
    for name, layer, field in [
        ("gis-estaciones", catalog.ESTACIONES_LAYER, "numero_estacion"),
        ("gis-paraderos", catalog.PARADEROS_LAYER, "cenefa"),
        ("gis-servicios", catalog.SERVICIOS_LAYER, "route_id"),
    ]:
        check(name, lambda layer=layer, field=field: probe_gis(layer, field))

    routes = []

    def search():
        routes.extend(catalog.catalog_search_all())
        if not routes or any(not all(row.get(key) for key in ("id", "codigo", "nombre")) for row in routes):
            raise ValueError("Catalog search empty or missing route identity fields")
        hours = catalog.service_operating_hours(routes, datetime.now(timezone.utc).isoformat())
        if not hours:
            raise ValueError("Catalog search has no operating hours")
        return {"rows": len(routes), "hours": len(hours), "sha256": fingerprint(routes, unordered=True)}

    check("catalog-search", search)
    # A small deterministic sample tests the detail endpoint without a network-wide crawl.
    samples = sorted(routes, key=lambda row: str(row.get("id", "")))[:3] if results[-1]["ok"] else []
    for row in samples:
        def detail(row=row):
            info = catalog.catalog_info(str(row["id"]), row["nombre"], row["codigo"])
            stops = catalog.info_route_stops(info)
            if not stops:
                raise ValueError("Route detail returned no stops")
            return {"catalog_id": row["id"], "code": row["codigo"], "stops": len(stops), "sha256": fingerprint(info)}
        check(f"catalog-detail-{row['id']}", detail)
        time.sleep(0.2)

    report = {
        "checked_at": datetime.now(timezone.utc).isoformat(),
        "scope": "Source access and basic shape only; not catalog completeness or publication approval",
        "ok": all(result["ok"] for result in results),
        "results": results,
    }
    report["responses"] = output.with_suffix(".responses").name
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
