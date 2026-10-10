#!/usr/bin/env python3
"""Next buses of a servicio arriving at a troncal estación.

Usage:
  scripts/eta.py S43 TM0069
  scripts/eta.py S43 TM0069 -n 4

Ricaurte NQS is TM0069. Calle 72 is TM0002.

Prefers POST /getServicios (distancia + labeltiempo + etiqueta).
Fills remaining slots from POST /location/ruta using station posición
inferred as bus.posicion + distancia from the nearest getServicios hit.
Troncal clock ETA uses 27 km/h. Board phrases stay as the host sent them.
"""

from __future__ import annotations

import argparse
import json
import sys
import unicodedata
import urllib.error
import urllib.request
from collections import Counter
from datetime import datetime, timedelta, timezone

# Contract: docs/feed.md
from feed_config import APPID, BASE_URL, INSTALL_UUID

TRONCAL_M_PER_S = 27_000 / 3_600  # 27 km/h
BOGOTA = timezone(timedelta(hours=-5))


def nfc(s: str) -> str:
    return unicodedata.normalize("NFC", s)


def bodega(path: str, body: dict | None = None) -> object:
    url = BASE_URL + path
    data = None
    headers = {"appid": APPID, "uuid": INSTALL_UUID}
    if body is None:
        headers["Content-Length"] = "0"
        data = b""
    else:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            raw = resp.read()
    except urllib.error.HTTPError as e:
        raw = e.read()
        raise SystemExit(f"{e.code} {path}: {raw[:300]!r}") from e
    if not raw:
        return []
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        raise SystemExit(f"non-JSON from {path}: {raw[:200]!r}") from None


def location(ruta: str) -> list[dict]:
    buses = bodega(f"/location/ruta?ruta={ruta}")
    if not isinstance(buses, list):
        raise SystemExit(f"/location/ruta: {buses!r}")
    return buses


def get_servicios(estacion: str, ruta: str, route_id: int, nombre: str) -> list[dict]:
    body = {
        "estacion": estacion,
        "ruta": ruta,
        "idRuta": route_id,
        "Nombre": nombre,
        "Distancia": 12000,
    }
    out = bodega("/getServicios", body)
    if not isinstance(out, list) or any(not isinstance(row, dict) for row in out):
        raise SystemExit("/getServicios: expected an array of objects")
    return out


def matching_services(
    servicios: list[dict], ruta: str, route_id: int, nombre: str
) -> list[dict]:
    """Keep only rows for the selected painted service and GTFS variant."""
    return [
        row
        for row in servicios
        if str(row.get("ruta_sae")) == str(route_id)
        and row.get("ruta_extraida") == ruta
        and nfc(row.get("destino_limpio") or "") == nombre
    ]


def pick_variant(buses: list[dict]) -> tuple[int, str]:
    dests = Counter(nfc(b["destino_limpio"]) for b in buses)
    nombre = dests.most_common(1)[0][0]
    ids = Counter(b["route_id"] for b in buses if nfc(b["destino_limpio"]) == nombre)
    return ids.most_common(1)[0][0], nombre


def minutes_for(metres: float) -> float:
    return metres / TRONCAL_M_PER_S / 60


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("ruta", help="painted servicio, e.g. S43")
    p.add_argument("estacion", help="app estación code, e.g. TM0069")
    p.add_argument("-n", type=int, default=4, help="how many buses (default 4)")
    args = p.parse_args()

    buses = location(args.ruta)
    if not buses:
        print(f"no live vehicles for {args.ruta}", file=sys.stderr)
        return 1

    route_id, nombre = pick_variant(buses)
    inbound = [
        b
        for b in buses
        if b["route_id"] == route_id and nfc(b["destino_limpio"]) == nombre
    ]
    servicios = matching_services(
        get_servicios(args.estacion, args.ruta, route_id, nombre),
        args.ruta,
        route_id,
        nombre,
    )

    occ_by_id = {str(b["id"]): b.get("ocupacion_bus") or "" for b in inbound}
    for s in servicios:
        vid = str(s.get("vehicleid"))
        if s.get("ocupacion_bus"):
            occ_by_id[vid] = s["ocupacion_bus"]

    station_pos = None
    for s in sorted(servicios, key=lambda x: x.get("distancia") or 10**9):
        pos = s.get("posicion")
        dist = s.get("distancia")
        if pos is not None and dist is not None:
            station_pos = pos + dist
            break

    now = datetime.now(BOGOTA)
    rows: list[dict] = []
    seen: set[str] = set()

    def add(live_id: str, label: str, remaining: float | None, phrase: str | None, source: str) -> None:
        if live_id in seen:
            return
        seen.add(live_id)
        mins = minutes_for(remaining) if remaining is not None else None
        eta = now + timedelta(minutes=mins) if mins is not None else None
        rows.append(
            {
                "id": live_id,
                "label": label,
                "remaining": remaining,
                "phrase": phrase,
                "mins": mins,
                "eta": eta,
                "source": source,
                "occ": occ_by_id.get(live_id, ""),
            }
        )

    for s in sorted(servicios, key=lambda x: x.get("distancia") if x.get("distancia") is not None else 10**9):
        add(
            str(s["vehicleid"]),
            s.get("etiqueta") or "",
            float(s["distancia"]) if s.get("distancia") is not None else None,
            s.get("labeltiempo"),
            "getServicios",
        )

    if station_pos is not None:
        rest = sorted(
            (b for b in inbound if str(b["id"]) not in seen and b["posicion"] < station_pos),
            key=lambda b: station_pos - b["posicion"],
        )
        for b in rest:
            add(
                str(b["id"]),
                b["label"],
                float(station_pos - b["posicion"]),
                None,
                "live+pin",
            )

    rows = rows[: args.n]
    print(
        f"{args.ruta} → {nombre} at {args.estacion}  "
        f"route_id={route_id}  live={len(inbound)}  "
        f"getServicios={len(servicios)}  {now.strftime('%I:%M:%S %p')}"
    )
    if station_pos is not None:
        print(f"estación pin {station_pos:.0f} m along shape (27 km/h clock)")
    if len(rows) < args.n:
        print(f"only {len(rows)} inbound, asked for {args.n}")
    print()
    print(f"{'#':>2}  {'label':<10} {'id':<8} {'occ':<8} {'rem m':>7}  {'min':>6}  {'clock':<8}  source")
    for i, r in enumerate(rows, 1):
        rem = f"{r['remaining']:.0f}" if r["remaining"] is not None else "?"
        mins = f"{r['mins']:.1f}" if r["mins"] is not None else "?"
        clock = r["eta"].strftime("%I:%M %p") if r["eta"] else "?"
        occ = r["occ"] or "-"
        extra = f"  {r['phrase']}" if r["phrase"] else ""
        print(f"{i:2}  {r['label']:<10} {r['id']:<8} {occ:<8} {rem:>7}  {mins:>6}  {clock:<8}  {r['source']}{extra}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
