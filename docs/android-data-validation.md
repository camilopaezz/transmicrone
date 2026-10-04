# Android data validation (2026-10-01)

Data-discovery only. No Android module, no Bodega constants copied here. Live contract remains [feed.md](feed.md) and [arrivals.md](arrivals.md). Join rules: [join.md](join.md). Official static inventory: [sources.md](sources.md). App intent: [app-scope.md](app-scope.md).

Checked from Soacha, CO (`191.107.38.209`, America/Bogotá) on 2026-10-01 23:44 −05 / 2026-10-02 04:44 UTC.

## Live Bodega: initial rejection, resolved with installation header

`GET /actuator`, `GET /actuator/health`, `POST /location/ruta`, and `POST /paradero/buses` all returned **HTTP 403**, empty HTML body, `server: Google Frontend`. Tried HTTP/2 and HTTP/1.1, IPv4 (`172.217.162.116`) and IPv6, with and without the documented iOS / okhttp User-Agents, with and without `appid` read from [feed.md](feed.md) (secret not logged).

This is not the documented 401-missing-appid or 503-health-DOWN. On 2026-09-04 the same host answered 200 from Colombian egress. These initial checks did not verify live vehicles, occupancy, ETA phrases, or multi-bus counts. The later UUID-header check below verified a service-level arrivals response. Native Android still does not need a CORS proxy; it still needs a host that answers.

Integration until the host answers: documented-contract client + fixtures. Do not invent arrivals.

### Rejection investigation, 2026-10-02 12:14 UTC

Three subagents reviewed Android request construction, reproduced host requests, and compared the rejection with prior observations. A temporary native Android instrumentation probe then checked the emulator using the app's OkHttp configuration. The probe was removed afterward; production networking was unchanged.

| request | client | result |
| --- | --- | --- |
| `GET /actuator/health` | host Python | 403, empty HTML, Google Frontend |
| `POST /paradero/buses`, TM0069, canonical appid | host Python | same |
| Same arrivals POST without appid | host Python | same |
| Health GET with documented MetroBus User-Agent | host curl, HTTP/2 | same, Content-Length 0 |
| Health GET | Android emulator, app's OkHttp configuration | 403, text/html, Google Frontend, HTTP/2, 0 body bytes |
| Arrivals POST, TM0069, canonical appid | Android emulator, app's OkHttp configuration | same |

The host had no conventional HTTP proxy configured. Cloudflare's connection trace reported `loc=CO`, Bogotá edge, and WARP off. This identifies the host's egress country; it does not prove how Bodega classifies that IP.

The Android request matches the documented method, path, JSON body, appid header, and content type, with no Origin. Generated BuildConfig values match the canonical feed document. Existing MockWebServer coverage checks method, path, body, appid, and absent Origin. The current UI's rejection message alone cannot establish a status code, because the client maps several non-200 statuses to the same failure kind; the native probe establishes 403 for these requests.

Initial inference, superseded by the UUID test below: a host-wide access restriction or source-IP/range block before the normal endpoint/authentication handlers. Ordinary missing-appid or the historically documented country restriction returned 401 problem JSON; historical CORS rejection included `Invalid CORS request`; missing Content-Length returned 411. The identical health and authenticated/unauthenticated responses do not match those signatures. An [App Engine firewall deny can return 403 before an application receives a request](https://cloud.google.com/blog/products/gcp/app-engine-firewall-now-generally-available), but these responses do not prove that a firewall is the specific component involved.

Remaining distinction: this connection may be blocked, or the host may now restrict all callers. Comparing the current official app and this endpoint from an independent Colombian connection, such as mobile data, would narrow that down. Operator access logs or policy configuration would be needed to identify the exact denial rule. At this stage no header or request-body fix had been established. The follow-up below identified the missing installation header.

### UUID header resolves arrivals access, 2026-10-02

A fresh review of the public client's [September 21 fix](https://github.com/estebantorrg/transmilenio/commit/d5967b8bd4ced636293ee3da888c75ee5407049a) found a new mandatory `uuid` installation header. Our canonical contract and Android client predated it and omitted it. The change fits the user's observation that access worked roughly two weeks earlier.

One controlled host POST to `/paradero/buses` for TM0069, with the same canonical appid/body plus one saved installation UUID, returned **HTTP 200, application/json, 5108 bytes, 24 service rows**. It included S43 to San Mateo, `Servicio llegando`, route_id 13278, nodo 61601. No UUID rotation, proxy, hostname change, or credential replacement was needed. The same request without uuid had returned 403 during the preceding investigation.

The result disproves the earlier claim that these observations established an edge/access restriction requiring another connection. `Server: Google Frontend` and an empty body did not identify the rejection layer. Health remained a poor diagnostic for whether a correctly identified arrivals client would work.

Android now generates one UUID, saves it in private installation preferences, and sends it on every arrivals request. A MockWebServer regression checks the header; an Android test checks persistence and reuse across contexts. Agent-device verified the installed app showing S43 to San Mateo, "Entre 5 y 7 min", "Ocupación desconocida", and an updated timestamp. The private installation ID stayed identical across app close/reopen, and the reopened app refreshed successfully. This verifies the Android service-level arrival display, not distinct vehicle identity in that board or occupancy reliability. The PC script check below separately exercised the vehicle endpoints.

The user also confirmed that the official TransMiApp still tracks buses on their phone, while the PC script had worked about two weeks earlier. Both `scripts/eta.py` and `scripts/serve-ricaurte.py` now send a shared, persistent PC installation ID from `scripts/feed_config.py`, which also reads feed constants from the canonical document. The saved ID is under `$XDG_STATE_HOME/transmicrone/feed-install-id`, defaulting to `~/.local/state/transmicrone/feed-install-id`, with private file permissions. It is reused across processes.

`python3 scripts/eta.py S43 TM0069 -n 1` completed successfully after the header fix: seven live S43 vehicles from `/location/ruta` and three nearby results from `/getServicios`. One printed result carried the host's ETA phrase and reported occupancy. The script's 27 km/h clock remains a heuristic, independent of the host phrase. Python syntax checks and ID reuse across separate processes passed. The browser proxy's header construction was updated; its missing `ricaurte.html` UI was not verified.

## Official static sources (working)

| source | call | result |
| --- | --- | --- |
| GTFS | `HEAD/GET https://storage.googleapis.com/gtfs-estaticos/GTFS_20261001.zip` | 200, Last-Modified 2026-10-01 09:19 UTC, 119 989 721 bytes. `feed_info.txt`: Transmilenio S.A., 20260101–20261231 |
| GIS estaciones | `Troncal/consulta_estaciones_troncales/FeatureServer/0` | 152 estaciones |
| GIS paraderos | `Zonal/consulta_paraderos_zonales/FeatureServer/0` | 7358 paraderos with `cenefa` |
| GIS Servicios | `Consulta_Planificacion_SITP/FeatureServer/15` | 693 rows; `route_id` matches current GTFS for the test servicios |
| Catalog API | `GET .../loader.php?lServicio=Rutas&lTipo=api&lFuncion=searchRutaByTipo&tipo_ruta=TIPORUTA&search=` | 1234 rutas. Empty seed is the full list. `STATUS` is `false` on success; ignore it |
| Catalog API | `lFuncion=infoRuta&idRuta&nombre&codigo` | 303 troncal/dual calls, 0 failures. This is the only official source of `TM####` codes |

Public catalog documented by the TransMiApp teardown (`searchRutaByTipo`, `infoRuta`). No Bodega probing, no personal data.

## Test case identifiers (not assumed from names)

Search by name is unsafe: GTFS `stop_name` contains schools and parks with “León”, and **two** type-1 stops named Ricaurte.

### León XIII → Ricaurte (E48, K43)

| field | value |
| --- | --- |
| GIS name | León XIII |
| troncal | Soacha |
| GIS `numero_estacion` | `07505` |
| nodo / GTFS `stop_id` (estación, `location_type=1`) | `7505` |
| Bodega / app `paradero` | **`TM0101`** |
| catalog place_id | `estacion:TM0101` |
| lat, lon | 4.59217919, −74.19313846 |

E48 (GTFS `route_id` **13266**, destino CAD, catalog API id `12444`, GIS `cod_linea` ES48) stops at **vagón A** (GTFS `62282` / `S005`). K43 (`route_id` **13276**, destino P. ElDorado, catalog id `5663`, línea KS43) stops at **vagón B** (`62283` / `S006`). Platform spacing is tens of metres (the nearby boarding points). Setup still selects the estación, not a vagón.

### Ricaurte → León XIII (S48, S43)

| field | value |
| --- | --- |
| GIS name | Ricaurte - NQS |
| troncal | NQS |
| GIS `numero_estacion` | `07111` |
| nodo / GTFS estación | `7111` |
| Bodega / app `paradero` | **`TM0069`** |
| catalog place_id | `estacion:TM0069` |
| lat, lon | 4.6116862, −74.09386888 |

S48: GTFS `route_id` **13267**, destino San Mateo, catalog id `5655`. S43: **three** GTFS variantes **13277, 13278, 13279**, all destino San Mateo; GIS only has 13278; catalog API only has id `5665`. Both painted codes board **vagón B** (`61601` / `E069`) in GTFS stop_times.

**Do not use** GIS/GTFS `Ricaurte - CL 13` (nodo **12003**, Americas). In this snapshot it has no `TM` code, no GTFS platforms, no stop_times. It is ~400 m from the NQS face, not the 20 m alternative.

TransMiApp `infoRuta` and GTFS parent `7111` attach NQS wagons A–C **and** Calle 13 wagons D–F to the same `TM0069`. GIS splits those faces into two estaciones. For this commute, query **`TM0069`** and filter to S43/S48 (NQS B). A board that showed every servicio at TM0069 would mix corridors.

infoRuta samples (wagon at each place):

| painted | catalog id | León XIII | Ricaurte NQS |
| --- | --- | --- | --- |
| E48 | 12444 | TM0101 vagón A | TM0069 vagón C |
| K43 | 5663 | TM0101 vagón B | TM0069 vagón C |
| S43 | 5665 | TM0101 vagón B | TM0069 vagón B |
| S48 | 5655 | TM0101 vagón A | TM0069 vagón B |

## Searchable catalog (generated)

`tools/build_catalog.py` writes `data/catalog.json` (~7.6 MB) and `data/test-case.json`. Rebuild; do not hand-edit two stations into the app.

2026-10-02T04:51:04Z snapshot:

- 7513 places: 155 GTFS estaciones + 7358 GIS paraderos
- 143 estaciones with a `TM` code (12 without: including 12003 and several GIS rows with empty GTFS serving — hide those from setup)
- 1045 GTFS servicios, 247 painted codes with more than one `route_id`
- 61 279 place↔`route_id` edges from GTFS `stop_times` (9 410 882 rows)
- 302 troncal rows where catalog API `id` ≠ GTFS `route_id` (E48: `12444` vs `13266`). **Live `ruta_sae` / `route_id` is GTFS.** Catalog `id` is only for `infoRuta`

Place kinds:

- **estación**: `query_paradero` = `TM####`. User-visible name + troncal disambiguator. Platforms/vagones are internal.
- **paradero**: `query_paradero` = GIS `cenefa` (no `_TM`). `nodo` = GTFS `stop_id` when `stop_code` is `{cenefa}_TM`.

Search: NFC display; casefold + strip diacritics; estaciones before paraderos; never index incidental GTFS names (schools named León, “Estación Ricaurte” street stops).

## Recommended app data contract

Board configuration (saved, not live):

```json
{
  "board_id": "uuid",
  "place_id": "estacion:TM0101",
  "kind": "estacion",
  "query_paradero": "TM0101",
  "display_name": "León XIII",
  "disambiguator": "Soacha",
  "servicios": [
    {"painted": "E48", "destino": "CAD", "route_ids": [13266]},
    {"painted": "K43", "destino": "P. ElDorado", "route_ids": [13276]}
  ]
}
```

For S43 store `route_ids: [13277, 13278, 13279]` under one row (same painted + destino). Do not make the user pick a variante id.

Live row (from `/paradero/buses` when the host answers):

```json
{
  "painted": "E48",
  "destino": "CAD",
  "route_id": 13266,
  "nodo": 62282,
  "labeltiempo": "Entre 2 y 4 min",
  "labelparadero": "773 mts",
  "ocupacion": null
}
```

Rules:

- One board = one `query_paradero`. Combine selected servicios into one list. Sort by the host’s coarse phrase order, not by a fabricated countdown.
- `labeltiempo` is a phrase. Do not parse it into a timer.
- Empty `ocupacion_bus` is unknown, not empty bus.
- `destino_limpio` is NFD on the wire; compare NFC.
- Distinguish fetch time, source `lasttime` (when using vehicle endpoints), empty-but-200, unsupported query, and transport failure.
- Never duplicate hostname/`appid`; generate BuildConfig from [feed.md](feed.md) at build time.

## Distinct multi-bus arrivals: not verified today

Documented 2026-09-04, **not re-checked**:

- `/paradero/buses` is servicio-level: no vehicle `id` / `label`. One coarse row is not several buses.
- `/getServicios` returns vehicle identity (`vehicleid`, `etiqueta`) and capped at 3 in the Ricaurte S43 case while more vehicles were inbound.
- Overlapping ETA ranges do not order buses.

Until Bodega answers, the board must not present multiple identified buses per servicio. If `/paradero/buses` later returns several rows for the same `ruta_sae`, treat them as separate only when the payload actually differs (different nodo/phrase/distance), still without inventing labels. Vehicle-level lists need `/getServicios` (and/or `/location/ruta` as in `scripts/eta.py`) and a new live check.

`scripts/eta.py` remaining-metres via 27 km/h is CLI estimation, not a phrase the UI should show as host data.

## Integration strategy

1. Ship `data/catalog.json` (or a stripped `places` + `servicios` + `serving`) as an asset. Rebuild when GTFS/GIS/catalog move. Do not call Bodega to search.
2. Setup: search places → pick one with `query_paradero` → pick painted+destino from `served_painted` / serving edges.
3. Runtime: `POST /paradero/buses` with `{paradero: query_paradero}`; filter to the board’s painted codes; if `ruta_sae` is present, accept it when it is in that servicio’s `route_ids`.
4. Cache last JSON + fetch timestamp; show it as old while refreshing.
5. Unit-test parsers against `data/fixtures/` and the samples in [arrivals.md](arrivals.md). Do not hit Bodega in CI.
6. When Bodega 200s again from this network, re-run the four test servicios at `TM0101` and `TM0069` (arrivals + `/getServicios` + occupancy + lag). Until then, keep the 403 in the UI as a host failure, not as “no buses”.

Polling cadence and stale thresholds still need a live sample.

## Implementation checks (2026-10-02, defect pass)

Commands (JDK 21, no new SDK licenses accepted):

```
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
```

| Check | Result |
| --- | --- |
| `:app:testDebugUnitTest` | SUCCESS. **18** tests, **0** failures (`DomainTest` 11, `AppViewModelTest` 7) |
| `:app:lintDebug` | SUCCESS. **0** errors. **17** warnings (OldTargetApi, UnusedResources, GradleDependency, NewerVersionAvailable, AndroidGradlePluginVersion, DataExtractionRules, UseKtx) |
| `:app:assembleDebug` | SUCCESS. APK `android/app/build/outputs/apk/debug/app-debug.apk` |
| `:app:connectedDebugAndroidTest` | SUCCESS on `Daily_API35` (API 35). **5** Compose tests |
| Live Bodega | `GET /actuator/health` **403** this pass. No live arrivals. Do not treat 403 as empty |
| Emulator search typing | `adb input text` still opens an unrelated Settings activity; catalog search covered by JVM tests |
| TalkBack / large text / physical device | Not run |

Packed gzip catalog unit-tested: León XIII `estacion:TM0101`, Ricaurte NQS `estacion:TM0069` with S43 `route_ids` [13277, 13278, 13279]. Feed host/`APPID` still only from [feed.md](feed.md) via Gradle `feedField`.

## Files

| path | role |
| --- | --- |
| `tools/build_catalog.py` | generator (GTFS + GIS + catalog API) |
| `data/catalog.json` | searchable catalog |
| `data/test-case.json` | resolved commute boards |
| `data/fixtures/searchRutaByTipo-E48.json` | catalog search sample |
| `data/fixtures/infoRuta-E48.json` | recorrido with TM codes, trazado stripped |
| `data/fixtures/gis-estaciones-leon-ricaurte.json` | GIS rows 7505 / 7111 / 12003 |

Existing `scripts/` and `docs/` were not modified.


## Station arrival correction, 2026-10-02

The earlier service-only UI above has been superseded for station boards. A paired S43 / TM0069 live request at 08:19 Bogota reproduced the phone symptom: `/paradero/buses` returned “Servicio llegando”, 563 m, while `/getServicios` returned nearest vehicle 41405 / T10405 at 2594 m, “Entre 4 min. y 7 min. a: 2Km”, reported at 08:18:07 AM. The previous app refreshed that conflicting service phrase every 25 seconds; it did not follow an individual bus or distinguish its observation time from the fetch time.

The station client now queries `/getServicios` for all selected route variants with two concurrent requests maximum, filters by selected code/destination/route IDs, and deduplicates vehicle IDs using the newest returned report. The cache preserves vehicle ID, fleet label, distance, and source timestamp. Stop boards continue using service estimates. A failed variant query retains the previous complete board and exposes the failure instead of substituting a fresh service summary.

Cards display source clock time and elapsed report age separately from fetch time. Reports older than two minutes show “Reporte antiguo” with the last estimate below it. Missing/invalid source clocks are marked unavailable. The date is inferred from the most recent Bogota day because the feed supplies clock time only. Time ranges remain server estimates; no exact countdown or full inbound count is claimed.

A captured-response regression first failed against the service-summary path, then passed using `/getServicios`. The JVM suite now has 25 passing tests, including route variants, deduplication, cache persistence, source-clock midnight handling, and rejected-query behavior. Android UI verification adds an old-report case where “Servicio llegando” is replaced by the stale heading.

Final verification: 25 JVM tests passed, Android UI/instrumentation suite passed 11 tests, lint completed without errors, and the APK built. The final emulator live screen fetched S43 vehicle reports successfully at 08:38 Bogota, including T10506 at 601 m and T10418 at 5 m with separate source clocks and ages. No blanket “arriving” replacement or fabricated countdown is applied; any arriving phrase still visible comes from that vehicle’s report. The Xiaomi was unreachable, so delivery changed to a downloadable APK at the user’s request.
