#!/usr/bin/env python3
"""Build a searchable place+servicio catalog from official static sources.

Sources (no Bodega live host, no appid):
  - GTFS zip on storage.googleapis.com/gtfs-estaticos/
  - GIS estaciones and paraderos on gis.transmilenio.gov.co
  - GIS Servicios (route_id / painted code)
  - Public catalog API api.buscador-rutas.transmilenio.gov.co
    (documented searchRutaByTipo + infoRuta)

Usage:
  python3 tools/build_catalog.py
  python3 tools/build_catalog.py --gtfs-dir /tmp/gtfs_tm --skip-info-ruta

Does not download GTFS into the repo. Writes data/catalog.json and
data/test-case.json. Does not touch android/ or Bodega constants.
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data"
UA = "transmicrone-catalog/0.1 (local personal tooling)"
GIS = "https://gis.transmilenio.gov.co/arcgis/rest/services"
CATALOG = "https://api.buscador-rutas.transmilenio.gov.co/loader.php"
GTFS_BUCKET = "https://storage.googleapis.com/gtfs-estaticos"
ESTACIONES_LAYER = f"{GIS}/Troncal/consulta_estaciones_troncales/FeatureServer/0"
PARADEROS_LAYER = f"{GIS}/Zonal/consulta_paraderos_zonales/FeatureServer/0"
SERVICIOS_LAYER = (
    f"{GIS}/ConsultaSubgerenciaPlanificacionSITP/"
    "Consulta_Planificacion_SITP/FeatureServer/15"
)

WAGON_RE = re.compile(
    r"\b([A-F]|T\d+)\b",
    re.IGNORECASE,
)


def nfc(s: str | None) -> str:
    return unicodedata.normalize("NFC", s or "")


def fold(s: str | None) -> str:
    t = unicodedata.normalize("NFD", nfc(s))
    t = "".join(ch for ch in t if unicodedata.category(ch) != "Mn")
    return t.casefold()


def http_json(url: str, timeout: float = 30) -> object:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode())


def http_bytes(url: str, timeout: float = 120) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def head_ok(url: str, timeout: float = 20) -> tuple[bool, dict]:
    req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return True, dict(resp.headers)
    except urllib.error.HTTPError as e:
        return False, {"status": e.code}
    except Exception as e:
        return False, {"error": str(e)}


def discover_gtfs_zip() -> tuple[str, dict]:
    now = datetime.now(timezone.utc)
    tried = []
    for delta in range(0, 8):
        day = now.fromordinal(now.toordinal() - delta)
        name = f"GTFS_{day.strftime('%Y%m%d')}.zip"
        url = f"{GTFS_BUCKET}/{name}"
        ok, meta = head_ok(url)
        tried.append({"name": name, "ok": ok})
        if ok:
            return url, {
                "name": name,
                "last_modified": meta.get("Last-Modified") or meta.get("last-modified"),
                "content_length": meta.get("Content-Length") or meta.get("content-length"),
                "tried": tried,
            }
    raise SystemExit(f"no GTFS zip in last 8 utc days: {tried}")


def extract_gtfs(zip_path: Path, dest: Path) -> None:
    dest.mkdir(parents=True, exist_ok=True)
    needed = [
        "agency.txt",
        "routes.txt",
        "stops.txt",
        "trips.txt",
        "stop_times.txt",
        "feed_info.txt",
    ]
    with zipfile.ZipFile(zip_path) as zf:
        names = set(zf.namelist())
        for n in needed:
            if n not in names:
                raise SystemExit(f"GTFS missing {n}")
            zf.extract(n, dest)


def read_csv(path: Path) -> list[dict]:
    with path.open(newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def gis_query_all(layer: str, fields: str, page: int = 2000) -> list[dict]:
    metadata = http_json(f"{layer}?f=json", timeout=60)
    oid = metadata.get("objectIdField") or next(
        (field["name"] for field in metadata.get("fields", [])
         if field.get("type") == "esriFieldTypeOID"), None)
    if not oid:
        raise ValueError(f"GIS layer has no object ID field: {layer}")
    out: list[dict] = []
    seen_ids = set()
    offset = 0
    while True:
        q = urllib.parse.urlencode(
            {
                "where": "1=1",
                "outFields": ",".join(dict.fromkeys([*fields.split(","), oid])),
                "orderByFields": f"{oid} ASC",
                "returnGeometry": "false",
                "outSR": "4326",
                "f": "json",
                "resultRecordCount": str(page),
                "resultOffset": str(offset),
            }
        )
        data = http_json(f"{layer}/query?{q}", timeout=60)
        if not isinstance(data, dict):
            raise SystemExit(f"bad GIS payload {layer}")
        if data.get("error"):
            raise SystemExit(f"GIS error {layer}: {data['error']}")
        feats = data.get("features") or []
        for feat in feats:
            attributes = feat.get("attributes") or {}
            identity = attributes.get(oid)
            if identity is None or identity in seen_ids:
                raise ValueError(f"Missing or repeated GIS object ID in {layer}: {identity}")
            seen_ids.add(identity)
            out.append(attributes)
        if not feats or (len(feats) < page and not data.get("exceededTransferLimit")):
            break
        offset += len(feats)
        if offset > 200_000:
            raise ValueError(f"GIS pagination limit exceeded: {layer}")
    return out


def catalog_search_all() -> list[dict]:
    q = urllib.parse.urlencode(
        {
            "lServicio": "Rutas",
            "lTipo": "api",
            "lFuncion": "searchRutaByTipo",
            "tipo_ruta": "TIPORUTA",
            "search": "",
        }
    )
    data = http_json(f"{CATALOG}?{q}", timeout=60)
    if not isinstance(data, dict):
        raise SystemExit("catalog search: not an object")
    return data.get("lista_rutas") or []


def catalog_info(id_ruta: str, nombre: str, codigo: str) -> dict:
    q = urllib.parse.urlencode(
        {
            "lServicio": "Rutas",
            "lTipo": "api",
            "lFuncion": "infoRuta",
            "idRuta": id_ruta,
            "nombre": nombre,
            "codigo": codigo,
        }
    )
    data = http_json(f"{CATALOG}?{q}", timeout=40)
    if not isinstance(data, dict):
        return {}
    return data


def info_route_stops(info: object) -> list[dict]:
    """Read stop rows from either observed infoRuta recorrido shape."""
    if not isinstance(info, dict):
        raise ValueError("infoRuta response must be an object")
    recorrido = info.get("recorrido")
    if isinstance(recorrido, list):
        stops = recorrido
    elif isinstance(recorrido, dict) and "data" in recorrido:
        stops = recorrido["data"]
    else:
        raise ValueError("infoRuta recorrido must be an array or an object with data")
    if not isinstance(stops, list) or any(not isinstance(stop, dict) for stop in stops):
        raise ValueError("infoRuta recorrido rows must be objects in an array")
    return stops


def service_operating_hours(rows: list[dict], fetched_at: str) -> list[dict]:
    """Keep published hours in the catalog API's own direction/variant namespace."""
    result = []
    for row in rows:
        windows = []
        for window in (row.get("horarios") or {}).get("data") or []:
            values = [str(window.get(key) or "").strip() for key in
                      ("convencion", "hora_inicio", "hora_fin")]
            if not all(values):
                continue
            for index in (1, 2):
                try:
                    values[index] = datetime.strptime(values[index].upper(), "%I:%M %p").strftime("%H:%M")
                except ValueError:
                    pass  # Preserve unfamiliar published formats rather than invent a time.
            value = dict(zip(("day_group", "start_time", "end_time"), values))
            if value not in windows:
                windows.append(value)
        if windows and row.get("id") and row.get("codigo") and row.get("nombre"):
            day_order = {day: index for index, day in enumerate(("L-V", "L-S", "L-D", "S", "D", "D-F"))}
            windows.sort(key=lambda window: (day_order.get(window["day_group"], 99), window["day_group"], window["start_time"], window["end_time"]))
            result.append({
                "catalog_id": str(row["id"]), "painted": str(row["codigo"]),
                "destino": nfc(row["nombre"]), "fetched_at": fetched_at,
                "windows": windows,
            })
    return result


def service_route(row: dict, info: dict, fetched_at: str) -> dict:
    stops = info_route_stops(info)
    try:
        positions = [float(stop["posicion"]) for stop in stops]
    except (KeyError, TypeError, ValueError):
        positions = []
    if positions:
        stops = [stop for _, stop in sorted(zip(positions, stops), key=lambda pair: pair[0])]
    return {
        "catalog_id": str(row["id"]), "painted": str(row["codigo"]),
        "destino": nfc(row["nombre"]), "fetched_at": fetched_at,
        "stops": [{
            "code": str(stop.get("codigo") or ""), "name": nfc(stop.get("nombre")),
            "wagon": str(stop.get("vagon") or "").strip() or None,
            "address": nfc(stop.get("direccion")) or None,
            "stops_here": str(stop.get("parada")) != "0",
        } for stop in stops],
    }


def wagon_from_platform_name(name: str) -> str | None:
    m = WAGON_RE.search(nfc(name))
    return m.group(1).upper() if m else None


def cenefa_from_gtfs_code(stop_code: str) -> str | None:
    code = (stop_code or "").strip()
    if code.endswith("_TM"):
        return code[: -3]
    if re.fullmatch(r"\d{3}[A-Z]\d{2}", code):
        return code
    return None


def agency_sistema(agency_id: str) -> str:
    if agency_id in {"1", "6"}:
        return "TransMilenio"
    if agency_id == "7":
        return "TransMiCable"
    return "TransMiZonal"


def match_gis_service(candidates: list[dict], route_id: str, painted: str, warnings: list[dict]) -> dict:
    """A repeated GIS route ID can describe multiple painted directions."""
    matches = [row for row in candidates if row.get("cod_ruta") == painted]
    choices = matches or candidates
    signatures = {(row.get("cod_ruta"), row.get("cod_linea")) for row in choices}
    if len(signatures) <= 1:
        return choices[0] if choices else {}
    warnings.append({
        "source": "gis-service-ambiguity", "route_id": route_id, "painted": painted,
        "candidates": sorted(
            [{"cod_ruta": code, "cod_linea": line} for code, line in signatures],
            key=lambda row: json.dumps(row, sort_keys=True)),
    })
    return {}


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--gtfs-dir", type=Path, help="existing extracted GTFS directory")
    p.add_argument("--gtfs-zip", type=Path, help="existing GTFS zip")
    p.add_argument("--skip-info-ruta", action="store_true", help="do not call infoRuta")
    p.add_argument("--info-limit", type=int, default=0, help="max infoRuta calls (0 = all catalog variants)")
    p.add_argument("--sleep", type=float, default=0.12, help="pause between infoRuta calls")
    p.add_argument("--out", type=Path, default=DATA / "catalog.json")
    p.add_argument("--test-out", type=Path, default=DATA / "test-case.json")
    p.add_argument("--work-dir", type=Path, default=Path("/tmp/transmicrone-gtfs"))
    p.add_argument("--identity-baseline", type=Path, help="published source catalog whose station IDs must be preserved")
    args = p.parse_args()

    previous = json.loads(args.out.read_text(encoding="utf-8")) if args.out.exists() else {}
    identity_baseline = json.loads(args.identity_baseline.read_text()) if args.identity_baseline else previous
    station_ids = {}
    for place in identity_baseline.get("places", []):
        if place.get("kind") == "estacion" and place.get("nodo") is not None:
            node = str(place["nodo"])
            if node in station_ids and station_ids[node] != place["place_id"]:
                raise ValueError(f"Ambiguous station identities in baseline for nodo {node}")
            station_ids[node] = place["place_id"]

    DATA.mkdir(parents=True, exist_ok=True)
    generated_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    failures: list[dict] = []
    warnings: list[dict] = []
    sources: list[dict] = []

    gtfs_dir = args.gtfs_dir
    gtfs_meta: dict = {}
    if gtfs_dir is None:
        work = args.work_dir
        work.mkdir(parents=True, exist_ok=True)
        if args.gtfs_zip:
            zpath = args.gtfs_zip
            gtfs_meta = {"name": zpath.name, "source": "local-zip"}
        else:
            url, gtfs_meta = discover_gtfs_zip()
            zpath = work / gtfs_meta["name"]
            if not zpath.exists():
                print(f"downloading {url}", file=sys.stderr)
                zpath.write_bytes(http_bytes(url, timeout=180))
            gtfs_meta["url"] = url
        gtfs_dir = work / "extracted"
        print(f"extracting {zpath} -> {gtfs_dir}", file=sys.stderr)
        extract_gtfs(zpath, gtfs_dir)
    else:
        gtfs_meta = {"source": "local-dir", "path": str(gtfs_dir)}
    sources.append({"kind": "gtfs", **gtfs_meta})

    feed_info = read_csv(gtfs_dir / "feed_info.txt")
    sources[0]["feed_info"] = feed_info
    routes = read_csv(gtfs_dir / "routes.txt")
    stops = read_csv(gtfs_dir / "stops.txt")
    trips = read_csv(gtfs_dir / "trips.txt")
    print(f"GTFS routes={len(routes)} stops={len(stops)} trips={len(trips)}", file=sys.stderr)

    trip_to_route = {t["trip_id"]: t["route_id"] for t in trips}
    stop_routes: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))
    st_path = gtfs_dir / "stop_times.txt"
    print(f"scanning {st_path}", file=sys.stderr)
    n_st = 0
    with st_path.open(newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            n_st += 1
            rid = trip_to_route.get(row["trip_id"])
            if rid:
                stop_routes[row["stop_id"]][rid] += 1
            if n_st % 2_000_000 == 0:
                print(f"  stop_times {n_st}", file=sys.stderr)
    print(f"stop_times rows={n_st} stops_with_routes={len(stop_routes)}", file=sys.stderr)

    # GIS
    try:
        estaciones_gis = gis_query_all(
            ESTACIONES_LAYER,
            "numero_estacion,nombre_estacion,codigo_nodo_estacion,troncal_estacion,"
            "numero_vagones_estacion,tipo_estacion,latitud_estacion,longitud_estacion,"
            "ubicacion_estacion",
            page=500,
        )
        sources.append(
            {
                "kind": "gis-estaciones",
                "url": ESTACIONES_LAYER,
                "count": len(estaciones_gis),
                "retrieved_at": generated_at,
            }
        )
        print(f"GIS estaciones={len(estaciones_gis)}", file=sys.stderr)
    except Exception as e:
        failures.append({"source": "gis-estaciones", "error": str(e)})
        estaciones_gis = []

    try:
        paraderos_gis = gis_query_all(
            PARADEROS_LAYER,
            "cenefa,nombre,zona_sitp,direccion_bandera,localidad,latitud,longitud",
            page=5000,
        )
        sources.append(
            {
                "kind": "gis-paraderos",
                "url": PARADEROS_LAYER,
                "count": len(paraderos_gis),
                "retrieved_at": generated_at,
            }
        )
        print(f"GIS paraderos={len(paraderos_gis)}", file=sys.stderr)
    except Exception as e:
        failures.append({"source": "gis-paraderos", "error": str(e)})
        paraderos_gis = []

    try:
        servicios_gis = gis_query_all(
            SERVICIOS_LAYER,
            "route_id,cod_ruta,cod_linea,nom_ruta,orig_ruta,dest_ruta,comp_sitp,est_ruta,long_ruta",
            page=2000,
        )
        sources.append(
            {
                "kind": "gis-servicios",
                "url": SERVICIOS_LAYER,
                "count": len(servicios_gis),
                "retrieved_at": generated_at,
            }
        )
        print(f"GIS servicios={len(servicios_gis)}", file=sys.stderr)
    except Exception as e:
        failures.append({"source": "gis-servicios", "error": str(e)})
        servicios_gis = []

    try:
        lista_rutas = catalog_search_all()
        sources.append(
            {
                "kind": "catalog-searchRutaByTipo",
                "url": CATALOG,
                "count": len(lista_rutas),
                "note": "empty search seed returns the full list",
                "retrieved_at": generated_at,
            }
        )
        print(f"catalog lista_rutas={len(lista_rutas)}", file=sys.stderr)
    except Exception as e:
        raise RuntimeError("Catalog search failed; existing catalog was not overwritten") from e

    # Package ordered routes for every system; troncal/dual TM codes also live here.
    tm_by_nodo: dict[int, dict] = {}
    info_ok = 0
    info_fail = 0
    identities = {str(row["id"]): (str(row["codigo"]), nfc(row["nombre"])) for row in lista_rutas}
    # Keep usable details for unchanged variants when a request is skipped or fails.
    service_routes_by_id = {
        row["catalog_id"]: row for row in previous.get("service_routes", [])
        if row.get("stops") and identities.get(row["catalog_id"]) == (row["painted"], nfc(row["destino"]))
    }
    if not args.skip_info_ruta:
        candidates = lista_rutas
        if args.info_limit:
            candidates = candidates[: args.info_limit]
        print(f"infoRuta candidates={len(candidates)}", file=sys.stderr)
        for i, r in enumerate(candidates, 1):
            try:
                info = catalog_info(str(r.get("id")), nfc(r.get("nombre")), str(r.get("codigo")))
                rec = info_route_stops(info)
                if not rec:
                    raise ValueError("No route stops returned")
                route = service_route(r, info, generated_at)
                service_routes_by_id[route["catalog_id"]] = route
                info_ok += 1
                for stop in rec:
                    try:
                        nodo = int(stop.get("id"))
                    except (TypeError, ValueError):
                        continue
                    codigo = str(stop.get("codigo") or "")
                    if codigo.startswith("TM"):
                        prev = tm_by_nodo.get(nodo)
                        if prev is None:
                            tm_by_nodo[nodo] = {
                                "tm_code": codigo,
                                "name": nfc(stop.get("nombre")),
                                "direccion": nfc(stop.get("direccion")),
                            }
                        elif prev["tm_code"] != codigo:
                            failures.append(
                                {
                                    "source": "infoRuta-tm-conflict",
                                    "nodo": nodo,
                                    "a": prev["tm_code"],
                                    "b": codigo,
                                }
                            )
            except Exception as e:
                info_fail += 1
                failures.append(
                    {
                        "source": "infoRuta",
                        "id": r.get("id"),
                        "codigo": r.get("codigo"),
                        "error": str(e),
                    }
                )
            if i % 25 == 0:
                print(f"  infoRuta {i}/{len(candidates)} tm_nodos={len(tm_by_nodo)}", file=sys.stderr)
            time.sleep(args.sleep)
        sources.append(
            {
                "kind": "catalog-infoRuta",
                "url": CATALOG,
                "candidates": len(candidates),
                "ok": info_ok,
                "fail": info_fail,
                "tm_nodos": len(tm_by_nodo),
                "retrieved_at": generated_at,
            }
        )

    stops_by_id = {s["stop_id"]: s for s in stops}
    children: dict[str, list[dict]] = defaultdict(list)
    for s in stops:
        parent = (s.get("parent_station") or "").strip()
        if parent:
            children[parent].append(s)

    gis_by_nodo = {}
    for e in estaciones_gis:
        nodo = e.get("codigo_nodo_estacion")
        if nodo is not None:
            gis_by_nodo[int(nodo)] = e

    places: list[dict] = []
    seen_place = set()

    # Estaciones: GTFS location_type=1 joined to GIS + TM
    for s in stops:
        if s.get("location_type") != "1":
            continue
        try:
            nodo = int(s["stop_id"])
        except ValueError:
            continue
        gis = gis_by_nodo.get(nodo) or {}
        tm = tm_by_nodo.get(nodo) or {}
        tm_code = tm.get("tm_code")
        name = nfc(gis.get("nombre_estacion") or s.get("stop_name"))
        troncal = nfc(gis.get("troncal_estacion") or "")
        place_id = station_ids.get(str(nodo), f"estacion-nodo:{nodo}")
        if place_id in seen_place:
            continue
        seen_place.add(place_id)
        plats = []
        route_ids: dict[str, int] = defaultdict(int)
        for ch in children.get(s["stop_id"], []):
            wagon = wagon_from_platform_name(ch.get("stop_name") or "")
            plats.append(
                {
                    "stop_id": ch["stop_id"],
                    "stop_code": ch.get("stop_code") or None,
                    "name": nfc(ch.get("stop_name")),
                    "wagon": wagon,
                    "lat": float(ch["stop_lat"]) if ch.get("stop_lat") else None,
                    "lon": float(ch["stop_lon"]) if ch.get("stop_lon") else None,
                }
            )
            for rid, c in stop_routes.get(ch["stop_id"], {}).items():
                route_ids[rid] += c
        places.append(
            {
                "place_id": place_id,
                "kind": "estacion",
                "tm_code": tm_code,
                "nodo": nodo,
                "name": name,
                "search_name": name,
                "disambiguator": troncal or None,
                "troncal": troncal or None,
                "address": nfc(gis.get("ubicacion_estacion") or tm.get("direccion") or "") or None,
                "lat": float(gis["latitud_estacion"])
                if gis.get("latitud_estacion") is not None
                else float(s["stop_lat"]),
                "lon": float(gis["longitud_estacion"])
                if gis.get("longitud_estacion") is not None
                else float(s["stop_lon"]),
                "wagon_count": gis.get("numero_vagones_estacion"),
                "gis_numero_estacion": gis.get("numero_estacion"),
                "platforms": sorted(plats, key=lambda platform: platform["stop_id"]),
                "served_route_ids": sorted(route_ids),
                "query_paradero": tm_code,  # Bodega /paradero/buses body
            }
        )

    # Paraderos: GIS cenefa is the live query key; GTFS stop_id is nodo
    gtfs_by_cenefa: dict[str, dict] = {}
    nodes_by_cenefa: dict[str, list[str]] = defaultdict(list)
    for s in stops:
        cen = cenefa_from_gtfs_code(s.get("stop_code") or "")
        if cen and s.get("location_type") == "0" and not (s.get("parent_station") or "").strip():
            gtfs_by_cenefa.setdefault(cen, s)
            nodes_by_cenefa[cen].append(s["stop_id"])
    for cen, nodes in sorted(nodes_by_cenefa.items()):
        if len(nodes) > 1:
            warnings.append({"source": "gtfs-cenefa-ambiguity", "cenefa": cen,
                             "nodes": sorted(nodes), "issue": "https://github.com/camilopaezz/transmicrone/issues/9"})

    for g in paraderos_gis:
        cen = (g.get("cenefa") or "").strip()
        if not cen:
            continue
        place_id = f"paradero:{cen}"
        if place_id in seen_place:
            continue
        seen_place.add(place_id)
        gs = gtfs_by_cenefa.get(cen)
        nodo = int(gs["stop_id"]) if gs else None
        rids = sorted(stop_routes.get(str(nodo), {})) if nodo is not None else []
        places.append(
            {
                "place_id": place_id,
                "kind": "paradero",
                "cenefa": cen,
                "nodo": nodo,
                "name": nfc(g.get("nombre") or (gs or {}).get("stop_name")),
                "search_name": nfc(g.get("nombre") or ""),
                "disambiguator": nfc(g.get("direccion_bandera") or g.get("localidad") or "") or None,
                "localidad": nfc(g.get("localidad") or "") or None,
                "address": nfc(g.get("direccion_bandera") or "") or None,
                "lat": g.get("latitud"),
                "lon": g.get("longitud"),
                "served_route_ids": rids,
                "query_paradero": cen,
            }
        )

    route_by_id = {r["route_id"]: r for r in routes}
    catalog_by_painted: dict[str, list[dict]] = defaultdict(list)
    for r in lista_rutas:
        catalog_by_painted[str(r.get("codigo"))].append(r)
    gis_by_rid: dict[str, list[dict]] = defaultdict(list)
    for row in servicios_gis:
        if row.get("route_id") is not None:
            gis_by_rid[str(row["route_id"])].append(row)

    servicios = []
    for r in routes:
        painted = r["route_short_name"]
        rid = r["route_id"]
        cat_matches = catalog_by_painted.get(painted) or []
        catalog_id = None
        catalog_color = None
        if len(cat_matches) == 1:
            catalog_id = str(cat_matches[0].get("id"))
            catalog_color = cat_matches[0].get("color")
        else:
            # prefer catalog id that equals current GTFS route_id when present
            for c in cat_matches:
                if str(c.get("id")) == str(rid):
                    catalog_id = str(c.get("id"))
                    catalog_color = c.get("color")
                    break
        gis = match_gis_service(gis_by_rid.get(str(rid), []), str(rid), painted, warnings)
        servicios.append(
            {
                "route_id": int(rid) if str(rid).isdigit() else rid,
                "painted": painted,
                "destino": nfc(r.get("route_long_name")),
                "agency_id": int(r["agency_id"]) if str(r["agency_id"]).isdigit() else r["agency_id"],
                "nombre_sistema": agency_sistema(str(r["agency_id"])),
                "catalog_id": catalog_id,
                "catalog_ids_sharing_painted": sorted({str(c.get("id")) for c in cat_matches}),
                "gis_cod_ruta": gis.get("cod_ruta"),
                "gis_cod_linea": gis.get("cod_linea"),
                "gis_route_id": gis.get("route_id"),
                "color": catalog_color,
            }
        )

    serving = []
    route_id_to_painted = {r["route_id"]: r["route_short_name"] for r in routes}
    for place in places:
        for rid in place.get("served_route_ids") or []:
            serving.append(
                {
                    "place_id": place["place_id"],
                    "route_id": int(rid) if str(rid).isdigit() else rid,
                    "painted": route_id_to_painted.get(rid),
                }
            )
        # drop bulky list from place after copying; keep compact painted set
        painted_set = []
        seen_p = set()
        for rid in place.get("served_route_ids") or []:
            pnt = route_id_to_painted.get(rid)
            if pnt and pnt not in seen_p:
                seen_p.add(pnt)
                painted_set.append(pnt)
        place["served_painted"] = sorted(painted_set)

    places.sort(key=lambda row: row["place_id"])
    servicios.sort(key=lambda row: str(row["route_id"]))
    serving.sort(key=lambda row: (row["place_id"], str(row["route_id"])))
    lista_rutas = sorted(lista_rutas, key=lambda row: str(row["id"]))

    def find_place(pred):
        return [x for x in places if pred(x)]

    leon = find_place(lambda x: x["kind"] == "estacion" and fold(x["name"]) == fold("León XIII"))
    rica = find_place(lambda x: x["kind"] == "estacion" and fold(x["name"]).startswith("ricaurte"))

    def servicio(painted: str) -> list[dict]:
        return [s for s in servicios if s["painted"] == painted]

    test_case = {
        "boards": [
            {
                "label": "León XIII",
                "selected_painted": ["E48", "K43"],
                "direction_note": "Toward Ricaurte",
                "resolved_places": leon,
                "servicios": {k: servicio(k) for k in ["E48", "K43"]},
            },
            {
                "label": "Ricaurte",
                "selected_painted": ["S48", "S43"],
                "direction_note": "Toward León XIII",
                "resolved_places": rica,
                "servicios": {k: servicio(k) for k in ["S48", "S43"]},
            },
        ],
        "notes": [
            "León XIII is a unique estación name; query_paradero is the TM code from infoRuta.",
            "Ricaurte is two estaciones in GIS/GTFS (NQS vs CL 13 / Americas). The commute board is NQS.",
            "Catalog API id is not GTFS route_id (E48 catalog 12444 vs GTFS 13266).",
            "S43 has multiple GTFS route_id variantes sharing the painted code and destino.",
        ],
    }

    catalog = {
        "generated_at": generated_at,
        "schema_version": 1,
        "service_hours": service_operating_hours(lista_rutas, generated_at),
        "service_routes": [service_routes_by_id[str(row["id"])] for row in lista_rutas
                           if str(row["id"]) in service_routes_by_id],
        "sources": sources,
        "failures": failures,
        "warnings": sorted(warnings, key=lambda row: json.dumps(row, sort_keys=True)),
        "counts": {
            "places": len(places),
            "estaciones": sum(1 for x in places if x["kind"] == "estacion"),
            "paraderos": sum(1 for x in places if x["kind"] == "paradero"),
            "estaciones_with_tm": sum(1 for x in places if x["kind"] == "estacion" and x.get("tm_code")),
            "servicios": len(servicios),
            "serving_edges": len(serving),
            "gtfs_stop_times": n_st,
        },
        "places": places,
        "servicios": servicios,
        "serving": serving,
        "search": {
            "normalize": "NFC display; search against casefold+diacritic-folded name and disambiguator",
            "estacion_before_paradero": True,
            "do_not_index": "GTFS stop_name of schools/parks that merely contain estación words",
        },
        "live_query": {
            "estacion": "POST /paradero/buses JSON {paradero: tm_code}",
            "paradero": "POST /paradero/buses JSON {paradero: cenefa without _TM}",
            "variant": "match ruta_sae to GTFS route_id; painted ruta_extraida is not unique",
            "bodega_host": "see docs/feed.md; this file does not duplicate hostname or appid",
        },
    }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(catalog, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    test_path = args.test_out
    test_path.parent.mkdir(parents=True, exist_ok=True)
    # shrink resolved places for the sidecar
    slim_boards = []
    for b in test_case["boards"]:
        slim_places = []
        for pl in b["resolved_places"]:
            slim_places.append(
                {
                    k: pl[k]
                    for k in [
                        "place_id",
                        "kind",
                        "tm_code",
                        "nodo",
                        "name",
                        "disambiguator",
                        "troncal",
                        "address",
                        "lat",
                        "lon",
                        "wagon_count",
                        "gis_numero_estacion",
                        "query_paradero",
                        "served_painted",
                    ]
                    if k in pl
                }
                | {"platforms": pl.get("platforms"), "served_route_ids": pl.get("served_route_ids")}
            )
        slim_boards.append({**b, "resolved_places": slim_places})
    test_path.write_text(
        json.dumps({**test_case, "boards": slim_boards, "generated_at": generated_at}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(
        json.dumps(
            {
                "out": str(args.out),
                "bytes": args.out.stat().st_size,
                "test_case": str(test_path),
                "counts": catalog["counts"],
                "failures": len(failures),
                "info_ok": info_ok,
                "info_fail": info_fail,
            },
            indent=2,
        )
    )
    return 0 if info_fail == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
