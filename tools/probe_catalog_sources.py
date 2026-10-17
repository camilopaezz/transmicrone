#!/usr/bin/env python3
"""Check catalog source access without live-feed credentials or catalog writes."""

from __future__ import annotations

import argparse
import csv
import io
import json
import time
import zipfile
from datetime import datetime, timezone
from pathlib import Path

import build_catalog as catalog


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
    return {"url": url, "bytes": len(raw), "metadata": metadata, "files": rows}


def probe_gis(layer: str, required: str) -> dict:
    rows = catalog.gis_query_all(layer, required)
    if not rows or not any(row.get(required) is not None for row in rows):
        raise ValueError(f"No usable GIS rows for {required}")
    return {"url": layer, "rows": len(rows)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
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
        return {"rows": len(routes), "hours": len(hours)}

    check("catalog-search", search)
    # A small deterministic sample tests the detail endpoint without a network-wide crawl.
    samples = sorted(routes, key=lambda row: str(row.get("id", "")))[:3] if results[-1]["ok"] else []
    for row in samples:
        def detail(row=row):
            info = catalog.catalog_info(str(row["id"]), row["nombre"], row["codigo"])
            stops = catalog.info_route_stops(info)
            if not stops:
                raise ValueError("Route detail returned no stops")
            return {"catalog_id": row["id"], "code": row["codigo"], "stops": len(stops)}
        check(f"catalog-detail-{row['id']}", detail)
        time.sleep(0.2)

    report = {
        "checked_at": datetime.now(timezone.utc).isoformat(),
        "scope": "Source access and basic shape only; not catalog completeness or publication approval",
        "ok": all(result["ok"] for result in results),
        "results": results,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
