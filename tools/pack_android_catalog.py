#!/usr/bin/env python3
"""Pack data/catalog.json into a gzipped Android asset.

Hides places without query_paradero or serving. Groups variantes that share
painted code + destino. Does not call Bodega and does not copy feed secrets.

Usage:
  python3 tools/build_catalog.py
  python3 tools/pack_android_catalog.py

Writes android/app/src/main/assets/catalog.json.gz
"""

from __future__ import annotations

import argparse
import gzip
import json
import unicodedata
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_IN = ROOT / "data" / "catalog.json"
DEFAULT_OUT = ROOT / "android" / "app" / "src" / "main" / "assets" / "catalog.json.gz"


def nfc(s: str | None) -> str:
    return unicodedata.normalize("NFC", s or "")


def fold(s: str | None) -> str:
    t = unicodedata.normalize("NFD", nfc(s))
    t = "".join(ch for ch in t if unicodedata.category(ch) != "Mn")
    return t.casefold()


def int_route_id(value: object) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, str) and value.isdigit():
        return int(value)
    return None


def pack(src: dict) -> dict:
    serv_by_rid: dict[int, dict] = {}
    for s in src.get("servicios") or []:
        rid = int_route_id(s.get("route_id"))
        if rid is None:
            continue
        serv_by_rid[rid] = s

    edges: dict[str, list] = defaultdict(list)
    for e in src.get("serving") or []:
        rid = int_route_id(e.get("route_id"))
        if rid is None:
            continue
        edges[e["place_id"]].append(rid)

    places = []
    servicios = []
    servicio_indices = {}
    hidden = 0
    for pl in src.get("places") or []:
        query = (pl.get("query_paradero") or "").strip()
        painted = pl.get("served_painted") or []
        if not query or not painted:
            hidden += 1
            continue
        groups: dict[tuple[str, str], dict] = {}
        for rid in edges.get(pl["place_id"], []):
            s = serv_by_rid.get(rid)
            if not s:
                continue
            key = (nfc(s.get("painted")), nfc(s.get("destino")))
            g = groups.get(key)
            if g is None:
                g = {
                    "painted": key[0],
                    "destino": key[1],
                    "route_ids": [],
                    "color": s.get("color"),
                }
                groups[key] = g
            if rid not in g["route_ids"]:
                g["route_ids"].append(rid)
            if not g.get("color") and s.get("color"):
                g["color"] = s.get("color")
        if not groups:
            hidden += 1
            continue
        dis = nfc(pl.get("disambiguator") or pl.get("troncal") or "") or None
        name = nfc(pl.get("name"))
        fold_src = " ".join(
            x for x in (pl.get("search_name") or name, dis) if x
        )
        rec = {
            "place_id": pl["place_id"],
            "kind": pl["kind"],
            "name": name,
            "fold": fold(fold_src),
            "query_paradero": query,
            "servicios": [
                {
                    "painted": g["painted"],
                    "destino": g["destino"],
                    "route_ids": g["route_ids"],
                    **({"color": g["color"]} if g.get("color") else {}),
                }
                for g in sorted(groups.values(), key=lambda x: (x["painted"], x["destino"]))
            ],
        }
        indices = []
        for servicio in rec.pop("servicios"):
            key = (
                servicio["painted"], servicio["destino"],
                tuple(servicio["route_ids"]), servicio.get("color"),
            )
            if key not in servicio_indices:
                servicio_indices[key] = len(servicios)
                servicios.append(servicio)
            indices.append(servicio_indices[key])
        rec["servicio_indices"] = indices
        if dis:
            rec["disambiguator"] = dis
        places.append(rec)

    estaciones = sum(1 for p in places if p["kind"] == "estacion")
    return {
        "generated_at": src.get("generated_at"),
        "schema_version": 1,
        "hidden_unqueryable": hidden,
        "counts": {
            "places": len(places),
            "estaciones": estaciones,
            "paraderos": len(places) - estaciones,
        },
        "servicios": servicios,
        "places": places,
    }


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--in", dest="src", type=Path, default=DEFAULT_IN)
    p.add_argument("--out", dest="out", type=Path, default=DEFAULT_OUT)
    args = p.parse_args()
    src = json.loads(args.src.read_text(encoding="utf-8"))
    packed = pack(src)
    raw = json.dumps(packed, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_bytes(gzip.compress(raw, compresslevel=9))
    print(
        f"wrote {args.out} gzip={args.out.stat().st_size} "
        f"json={len(raw)} places={packed['counts']['places']} "
        f"hidden={packed['hidden_unqueryable']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
