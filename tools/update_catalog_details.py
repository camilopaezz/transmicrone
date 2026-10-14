#!/usr/bin/env python3
"""Refresh packaged hours and ordered routes without rebuilding place/GTFS joins.

Uses the public catalog API, two concurrent requests, and a resumable local cache.
Run tools/pack_android_catalog.py afterwards to update the Android asset.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
import time

import build_catalog


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=build_catalog.DATA / "catalog.json")
    parser.add_argument("--cache", type=Path, default=Path("/tmp/transmicrone-route-details"))
    args = parser.parse_args()
    args.cache.mkdir(parents=True, exist_ok=True)
    rows = build_catalog.catalog_search_all()
    fetched_at = datetime.now(timezone.utc).isoformat()

    def fetch(row: dict) -> tuple[dict | None, dict | None]:
        cache = args.cache / f"{row['id']}.json"
        if cache.exists():
            saved = json.loads(cache.read_text())
            if saved["painted"] == row["codigo"] and saved["destino"] == build_catalog.nfc(row["nombre"]):
                return saved, None
        for attempt in range(2):
            try:
                info = build_catalog.catalog_info(str(row["id"]), row["nombre"], row["codigo"])
                route = build_catalog.service_route(row, info, datetime.now(timezone.utc).isoformat())
                if not route["stops"]:
                    raise ValueError("No route stops returned")
                cache.write_text(json.dumps(route, ensure_ascii=False, separators=(",", ":")))
                time.sleep(0.12)
                return route, None
            except Exception as error:
                if attempt:
                    return None, {"catalog_id": str(row["id"]), "painted": row["codigo"], "error": str(error)}
                time.sleep(1)
        raise AssertionError("unreachable")

    routes, failures = [], []
    with ThreadPoolExecutor(max_workers=2) as pool:
        for index, (route, failure) in enumerate(pool.map(fetch, rows), 1):
            if route:
                routes.append(route)
            if failure:
                failures.append(failure)
            if index % 25 == 0 or index == len(rows):
                print(f"Route details {index}/{len(rows)}; {len(routes)} saved; {len(failures)} failed", flush=True)
    catalog = json.loads(args.catalog.read_text())
    # Preserve previously downloaded routes if one refresh request fails.
    current = {row["catalog_id"]: row for row in catalog.get("service_routes", [])}
    current.update({row["catalog_id"]: row for row in routes})
    ids = {str(row["id"]) for row in rows}
    catalog["service_routes"] = [row for key, row in current.items() if key in ids]
    catalog["service_hours"] = build_catalog.service_operating_hours(rows, fetched_at)
    catalog["sources"] = [source for source in catalog["sources"]
                          if source["kind"] not in ("catalog-service-hours", "catalog-service-routes")]
    catalog["sources"].extend([
        {"kind": "catalog-service-hours", "url": build_catalog.CATALOG, "count": len(catalog["service_hours"]), "retrieved_at": fetched_at},
        {"kind": "catalog-service-routes", "url": build_catalog.CATALOG, "count": len(catalog["service_routes"]), "retrieved_at": fetched_at, "failures": failures},
    ])
    args.catalog.write_text(json.dumps(catalog, ensure_ascii=False, separators=(",", ":")))
    print(f"Updated {args.catalog}; {len(catalog['service_routes'])} offline routes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
