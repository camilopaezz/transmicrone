# TransMiApp live positions

Unofficial. Same Bodega host TransMiApp uses for "Ubica tu bus". No login. TransMilenio can change or block it without notice. Verified answering over HTTPS on 2026-09-04 from a Colombian egress. On 2026-10-02 arrivals access was restored by adding the now-required `uuid` installation header, as described below.

The default host and request contract live here. Configure `APPID` in the ignored root file `feed.local.properties`, or set `TRANSMICRONE_APP_ID`. The app and PC scripts read that configuration. Do not put credentials in tracked files.

Copy `feed.local.properties.example` to `feed.local.properties` and replace the placeholder. Environment variables `TRANSMICRONE_APP_ID` and `TRANSMICRONE_BASE_URL` override the local file. CI builds without a real app ID and tests with fixtures.

```
BASE_URL=https://tmsa-transmiapp-shvpc.uc.r.appspot.com
```

`/location/ruta` is enough. Replace `B23` with the painted service code.

```bash
curl -sS -X POST \
  "$BASE_URL/location/ruta?ruta=B23" \
  -H "appid: $APPID" \
  -H "uuid: $INSTALL_UUID" \
  -H 'Content-Length: 0' \
  --data ''
```

Two paths return the same vehicle array.

| path | body | selector |
| --- | --- | --- |
| `POST /location/ruta?ruta={CODE}` | empty, `Content-Length: 0` | painted service code (`B23`, `Z8`, `166`, `1`) |
| `POST /buses` | JSON `{"ruta":"{CODE}","Nombre":"{headsign}"}` | same `ruta`; `Nombre` was ignored for B23 on 2026-09-04 (wrong value still returned the Alcalá list) |

The TransMiApp Android client examined in the v2.9.6 teardown uses `/buses` for troncal and `/location/ruta` for zonal. Both accept both systems here: `/location/ruta?ruta=B23` and `/buses` with `ruta=Z8` each returned vehicles.

Stop ETAs are a different call. Read [arrivals.md](arrivals.md) before `/paradero/buses`.

## Contract

- `ruta` is the code painted on buses and maps. Required. Case-sensitive: `b23` → 400.
- Header `appid` is required. Query `?appid=` does not count. Missing/wrong/empty → 401.
- PC tools read credentials from environment variables or the ignored `feed.local.properties` file and save their shared installation ID under `$XDG_STATE_HOME/transmicrone/feed-install-id`, defaulting to `~/.local/state/transmicrone/feed-install-id`.
- Header `uuid` is also required now. Generate one UUID per installation and persist it, then reuse it on every request. Set `INSTALL_UUID` to that saved value for the curl above. Do not use a shared literal or rotate it per request. A public client's [September 21 change](https://github.com/estebantorrg/transmilenio/commit/d5967b8bd4ced636293ee3da888c75ee5407049a) records the requirement. This project verified on October 2 that a canonical arrivals POST without this header returned empty-body 403, while the same request with a stable UUID returned 200 JSON. The Android app stores its UUID in private preferences. Historical status observations below predate this change unless stated otherwise.
- `GET` / `PUT` / `DELETE` / `PATCH` / `HEAD` → 405, `Allow: POST`.
- Send `Content-Length`. Omitting it on HTTP or HTTPS → Google Frontend 411.
- User-Agent is not required. The old iOS MetroBus UA (`MetroBus/1.9.7 (com.nexura.transmilenio; build:276; iOS 16.0.2) Alamofire/1.9.7`) was accepted in the September verification; the curl example omits it.
- Extra query params are ignored.
- JSON body does not supply `ruta` for `/location/ruta`. The query param does.
- Browser `Origin` on POST or OPTIONS → 403 `Invalid CORS request`. Call from curl/native, not from a web page.

Unknown or inactive codes → 400 `"detail":"Service Not Available"`. Omitting `ruta` on `/location/ruta` → 400 `"Required parameter 'ruta' is not present."` An empty `[]` was not observed on 2026-09-04; do not assume idle known routes return one.

Colombia IP geofence observed on 2026-09-04: a Cloudflare Worker in MIA (correct `appid`, health still reachable) got the same 401 as a missing `appid`. Curl from Soacha, CO returned 200. `GET /actuator/health` is not geofenced. See [provenance.md](provenance.md).

## Response

JSON array of vehicles. Exactly these 13 keys on every element (367/367 in the 2026-09-04 sweep). No nulls. No extra keys.

| field | type | meaning |
| --- | --- | --- |
| `id` | string of digits | vehicle record id |
| `route_id` | int | GTFS `route_id` for this variant. Not always unique for one `ruta` (directions/variants split). Join: [join.md](join.md) |
| `latitude`, `longitude` | float | WGS84 |
| `label` | string | fleet id. Troncal `D0032`, `N0008`, `A10013`. Zonal `Z10-2258` |
| `lasttime` | string | last report as `%I:%M:%S %p` (`09:20:07 AM`). No date. Wall clock is America/Bogotá (UTC-5) |
| `ruta_extraida` | string | the requested `ruta` echoed back |
| `destino_limpio` | string | headsign. UTF-8 NFD (`Alcala` + combining acute). NFC `Alcalá` fails a naive `==` |
| `posicion` | int | metres along the route from the origin. 0 sits on the first GTFS shape point. Observed 0..73685 |
| `angulo` | int | heading in degrees. Observed 1..359 |
| `nombre_sistema` | string | `TransMilenio` (troncal + dual) or `TransMiZonal` (zonal / alimentador) |
| `accesibilidad` | string | `PLATAFORMA ALTA`, `CON ELEVADOR`, `NINGUNA`, `ENTRADA BAJA` |
| `ocupacion_bus` | string | `""` (most), `VACIO`, `MEDIO`, `LLENO` |

`id` vs `label`: zonal `id` is the digits of `label` with the hyphen removed (`Z10-2258` → `102258`). Troncal is related but fleet-coded (`D0032` → `10032`, `N0008` → `14008`), not raw digits.

No duplicate `id` inside one `ruta` response.

Lag vs the response `Date` header: about 30-56 s on 2026-09-04, matching the app's ~1 min GNSS to cellular delay. A second poll ~20 s later moved `lasttime` and usually lat/lon/`posicion` for the same ids.

Example (B23, 2026-09-04). `destino_limpio` is shown NFC here; the wire is NFD.

```json
{
  "id": "14008",
  "route_id": 12819,
  "latitude": 4.682396510112432,
  "longitude": -74.12176834370104,
  "label": "N0008",
  "lasttime": "09:05:31 AM",
  "ruta_extraida": "B23",
  "destino_limpio": "Alcalá",
  "posicion": 0,
  "angulo": 324,
  "nombre_sistema": "TransMilenio",
  "accesibilidad": "PLATAFORMA ALTA",
  "ocupacion_bus": ""
}
```

Zonal example (Z8):

```json
{
  "id": "102258",
  "route_id": 12497,
  "latitude": 4.616602883220169,
  "longitude": -74.15527666345278,
  "label": "Z10-2258",
  "lasttime": "09:06:51 AM",
  "ruta_extraida": "Z8",
  "destino_limpio": "Toberin",
  "posicion": 8051,
  "angulo": 84,
  "nombre_sistema": "TransMiZonal",
  "accesibilidad": "NINGUNA",
  "ocupacion_bus": ""
}
```

## Failures

Error bodies from the host are `application/problem+json`. Google edge errors are HTML.

| case | status | body |
| --- | --- | --- |
| missing / wrong / empty `appid`, or `appid` as query only | 401 | `"title":"Unauthorized"`, detail still `"Service Not Available"` |
| GET / PUT / DELETE / PATCH / HEAD | 405 | `"Method 'GET' is not supported."` (method name varies), `Allow: POST` |
| OPTIONS, no Origin | 200 | `Allow: POST,OPTIONS`, empty |
| OPTIONS or POST with browser `Origin` | 403 | `Invalid CORS request` |
| unknown `ruta`, or `b23` / spaced code | 400 | `"detail":"Service Not Available"` |
| omit `ruta` on `/location/ruta` | 400 | `"Required parameter 'ruta' is not present."` |
| no `Content-Length` | 411 | Google HTML |
| no User-Agent | 200 | still works |
| burst of a few sequential calls | 200 | no 429 seen. Heavier probing produced hangs / 0-byte timeouts, not a JSON rate-limit |

## Host surface

Spring Boot on Google App Engine `tmsa-transmiapp-shvpc.uc.r.appspot.com` (`uc` = us-central). `server: Google Frontend`. TLS cert `*.appspot.com`.

| path | result |
| --- | --- |
| `POST /location/ruta`, `POST /buses` | vehicle arrays (this doc) |
| `POST /paradero/buses` | stop board. [arrivals.md](arrivals.md) |
| `POST /getServicios` | per-(station, route) vehicles + ETA. [arrivals.md](arrivals.md) |
| `POST /consultar_programacion` | theoretical per-stop timetable, not AVL. [arrivals.md](arrivals.md) |
| `GET /puntos_recarga`, `GET /puntos_personalizacion` | TuLlave POIs, not AVL |
| `POST /lectura_tarjeta` | card ledger. Needs `numero_tarjeta`. Out of scope here |
| `GET /actuator` | 200. Links to health only |
| `GET /actuator/health` | 503 `{"status":"DOWN"}` while the feed still returns 200. Liveness and readiness subpaths can be UP. Do not treat aggregate health as feed liveness |
| `GET /actuator/info\|env\|mappings\|beans` | 404 |
| guessed `/location/all`, `/rutas`, swagger, `/v3/api-docs` | 404 |

No bbox or vehicle-id query. `ruta` is the selector on the position paths.

Sibling `*.appspot.com` names 404. Catalog, OTP, and GIS live on other hosts: [sources.md](sources.md).
