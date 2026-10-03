# TransMiApp stop arrivals

Same unofficial Bodega host as live positions. Reuse `BASE_URL` and `appid` from [feed.md](feed.md), and send the persistent per-installation `uuid` header now required by the host. Auth, Content-Length, CORS, and 401/411 behaviour there apply here too. On 2026-10-02 a TM0069 arrivals POST with this UUID returned 200; the earlier empty-body 403 was resolved by this header.

Verified 2026-09-04.

## Live board: `POST /paradero/buses`

JSON body `{"paradero":"{CODE}"}`. Content-Type `application/json`.

`paradero` is the cenefa / stop code the app uses, not the GTFS suffix form:

| input | result |
| --- | --- |
| zonal cenefa `080A01` | 200 JSON array |
| GTFS `stop_code` `080A01_TM` | 200 empty HTML body. Do not send `_TM` |
| troncal estación `TM0002` (Calle 72) | 200 |
| GIS `cenefa` field | same as zonal `paradero` |
| `{"cenefa":"..."}` instead of `paradero` | 400 Service Not Available |
| `{}` | 200 text `paradero no valido` |

`nodo` in the response is GTFS `stop_id` (080A01 → nodo 49841). Join table: [join.md](join.md).

### Response

JSON array of approaching services. Keys observed:

| field | type | meaning |
| --- | --- | --- |
| `ruta_sae` | int | GTFS `route_id` / live `route_id` |
| `ruta_extraida` | string | painted code |
| `destino_limpio` | string | headsign. UTF-8 NFD, same as the position feed |
| `nodo` | int | GTFS `stop_id`. A troncal station request can return several nodos (vagones) |
| `labelparadero` | string | distance label (`773 mts`, `1 kms`, `0 mts`) |
| `labeltiempo` | string | ETA label (`Servicio llegando`, `Entre 2 y 4 min`, `Entre 5 y 7 min`) |
| `color_ruta` | string | hex (`#7AC143`) |
| `color_texto` | string | hex |

No vehicle `id` / `label` on this board. For the buses themselves use [feed.md](feed.md).

Wire JSON is pretty-printed (spaces around `:`), unlike the compact position array. Parse it as JSON anyway.

Example element (cenefa `080A01`, 2026-09-04):

```json
{
  "ruta_sae": 11150,
  "ruta_extraida": "B419",
  "destino_limpio": "Lijaca",
  "nodo": 49841,
  "labelparadero": "773 mts",
  "labeltiempo": "Servicio llegando",
  "color_ruta": "#00618E",
  "color_texto": "#FFFFFF"
}
```

## Nearby vehicles: `POST /getServicios`

JSON body used on 2026-09-04: `{"estacion":"TM0002","ruta":"B23","idRuta":12819,"Nombre":"Alcalá","Distancia":500}`. Returned vehicles on B23 near that station.

Different schema from `/location/ruta`. Keys observed:

| field | notes |
| --- | --- |
| `vehicleid` | number, same value as position `id` |
| `etiqueta` | same as position `label` |
| `latitud`, `longitud` | WGS84. Names are Spanish, unlike `latitude`/`longitude` on the position feed |
| `posicion`, `angulo`, `lasttime`, `ruta_extraida`, `destino_limpio`, `ocupacion_bus` | same meanings as [feed.md](feed.md) |
| `acessiblidad` | misspelled in the API. Same values as `accesibilidad` |
| `ruta_sae` | GTFS `route_id` |
| `distancia` | metres to the station |
| `labeltiempo` | ETA string, sometimes glued (`Servicio llegandoa 444 mts`) |
| `time` | small int (1, 2, …) |
| `estacion_parada` | internal station id, not the `TM…` code you sent |

Empty or poorly shaped bodies can return 200 with `[]`. For vehicle-level ETAs this beat `/paradero/buses` (Ricaurte NQS / S43, 2026-09-04): the board had one coarse S43 row and no label; this path returned etiqueta, distancia, and `labeltiempo`. It still capped at 3 vehicles while more were inbound. `scripts/eta.py` fills the rest from live `posicion` once one hit pins the estación on the shape.

## Theoretical timetable: `POST /consultar_programacion`

Not AVL. JSON body `{"paradero":"080A01","ruta":"B419","idRuta":11150,"nombre":"Lijaca"}` returned scheduled remaining times at that stop.

| field | meaning |
| --- | --- |
| `ruta` | GTFS `route_id` |
| `ruta_extraida` | painted code |
| `nodo` | GTFS `stop_id` |
| `estacion_parada` | cenefa |
| `nombre_parada` | stop name (matches GTFS `stop_name`) |
| `hora_teor_segundos` | scheduled time as seconds from midnight America/Bogotá (`34024` = 09:27:04) |
| `tiempo_estimado` | Spanish phrase (`4 minutos`), not a number |

Use this to calibrate clocks. Do not treat it as a live position.

## Android station source selection, 2026-10-02

At 08:19 Bogota, simultaneous S43 / TM0069 requests disagreed: the service summary said “Servicio llegando”, 563 m, while the nearest identified vehicle was 41405 / T10405 at 2594 m with “Entre 4 min. y 7 min. a: 2Km” and source report 08:18:07 AM. Android station boards now query `/getServicios` for all selected route IDs with `Nombre` set to the destination and `Distancia: 12000`, matching the working PC script. Results are filtered to the selected service/destination/route IDs and deduplicated by vehicle identity. Stop boards retain service summaries.

Fetch time and source report time are separate. `lasttime` is interpreted as a Bogota clock time on the most recent date with five minutes of clock-skew tolerance. Reports older than two minutes are visibly stale even when fetched successfully. Missing source time is displayed as unavailable. Time ranges remain server phrases, without an invented countdown. Any failed variant request preserves the last complete board, with its failure state.

A successful `/getServicios` response can also have an empty body rather than `[]`, observed for S48 / TM0069 on 2026-10-02. Android treats that vehicle-endpoint response as no nearby results, matching the PC script. Blank service-summary bodies remain malformed; non-200 statuses remain failures.
