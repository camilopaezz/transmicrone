# Join keys: live feed ↔ official data

Read this before matching `route_id`, `label`, `ruta`, `nodo`, or `paradero` to GTFS, GIS, or fleet files.

Live field meanings: [feed.md](feed.md), [arrivals.md](arrivals.md). Where to download official files: [sources.md](sources.md).

Verified against GTFS `GTFS_20260904.zip`, GIS Servicios, Flota Vinculada (dic 2024 + 2021 snapshot), and live Bodega responses on 2026-09-04.

## Vehicles

| live | official | quality |
| --- | --- | --- |
| `ruta` / `ruta_extraida` | GTFS `route_short_name` | best human key. Painted code. Case-sensitive on the live host |
| `route_id` | GTFS `route_id` (integer string) | strong on current GTFS. B23 → 12819 in the live feed, today's `routes.txt`, and GIS Servicios |
| `route_id` | GIS `Consulta_Planificacion_SITP/FeatureServer/15` `route_id` | same integer when GIS is current. GIS can lag GTFS (C15 live/GTFS 13311 vs GIS 12467) |
| `destino_limpio` | GIS `dest_ruta`, GTFS `route_long_name` | cleaned / truncated. B23 GIS dest `Alcalá - Colegio S. To` → live `Alcalá`. Wire is NFD |
| `label` | Flota Vinculada `codigo_bus` | match after normalizing zeros. Live `D0032` vs flota `D032`. Zonal `Z10-2258` is exact |
| `id` | historical fleet numeric id | zonal: digits of `label` minus hyphen. Troncal: old `buses-en-operacion` id (`10032` with `D0032` in the oct 2021 CSV). Current flota CSV dropped that column |
| `accesibilidad` | flota `accesible` | same strings except live `NINGUNA` vs flota `NO ACCESIBLE` |
| `nombre_sistema` | GTFS `agency_id` | collapsed. `TransMilenio` ≈ troncal (1) + dual (6). `TransMiZonal` ≈ alimentador (2) + zonal (3/4/5). Not a 1:1 |
| `posicion` | GTFS `shapes.txt` along `shape_id` | metres from origin. `shape_id` equals `route_id` for every route in today's GTFS. B23 `posicion` 0 sat on shape point 1 |

Do not join current live `route_id` to old GTFS snapshots that used `Z_6970`-style ids (e.g. `GTFS-2024-02-21.zip`).

## Stops (arrivals)

| live | official | quality |
| --- | --- | --- |
| `paradero` body field | GIS zonal `cenefa`; GTFS `stop_code` without `_TM` | `080A01` works. `080A01_TM` does not |
| `paradero` `TM0002` | troncal estación code used by the app, not GTFS `stop_id` | GTFS stations have empty `stop_code` |
| `nodo` | GTFS `stop_id` | exact for zonal (080A01 → 49841) |
| `ruta_sae` | GTFS `route_id` / live `route_id` | exact on samples (B23 → 12819) |
| `nombre_parada` (programacion) | GTFS `stop_name` | exact on the Liberia sample |

## `ruta` is not unique

GTFS `route_short_name` repeats. Today's feed: 667 unique short names, 243 duplicates. Live `route_id` picks one variant; some `ruta` values still return two `route_id`s at once (E44, K86, ruta `1`).

The same painted code can exist in two systems. Live `C15` on 2026-09-04 was TransMiZonal (16 vehicles). GTFS also has a troncal C15 (`route_id` 13311, Portal Suba). Querying `C15` does not fetch that troncal service.

GIS Servicios pairs directions under `cod_linea` / `nom_ruta` (`B23` + `K23` → `BK23`). The live host is queried by the painted one-way code, not the pair.

GIS `cod_ruta` is not always the painted code:

| painted / live `ruta` | GIS `cod_ruta` | GTFS `route_short_name` |
| --- | --- | --- |
| `B23` | `B23` | `B23` |
| `Z8` | `Z8-3` | `Z8` |
| `K309` (live accepts it) | `KB309-3` | `KB309` |

Prefer GTFS `route_short_name` + current `route_id` over GIS `cod_ruta` when they disagree.

## Agency taxonomy (current GTFS)

`agency.txt` in `GTFS_20260904.zip`. Transitland's older agency mapping is stale; do not use it.

| agency_id | agency_name | live `nombre_sistema` (observed) |
| --- | --- | --- |
| 1 | Transmilenio-Troncal | TransMilenio |
| 2 | Transmilenio-Alimentadores | TransMiZonal |
| 3 | Zonal-Urbano | TransMiZonal |
| 4 | Zonal-Complementario | TransMiZonal |
| 5 | Zonal-Especial | TransMiZonal |
| 6 | Transmilenio-Dual | TransMilenio (M86) |
| 7 | TransMiCable | not seen on the bus feed |

`route_type` is 3 (bus) except cable = 6. Timezone `America/Bogota`.
