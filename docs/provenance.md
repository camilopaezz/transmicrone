# Provenance

Historical source notes from 2026-09-04, with later observations dated explicitly. Field contract: [feed.md](feed.md).

## Chain

Fleet GNSS on the buses → cellular → TMSA backend → TransMiApp "Ubica tu bus". ENTER.CO (2024-07-09) quotes David Monroy (TMSA spatial systems): the GNSS fix reaches the app after a short delay, typically about a minute. Esri GIS + GTFS are the static stack around that, not the live socket.

| layer | who | evidence |
| --- | --- | --- |
| Publisher | TRANSMILENIO S.A. (TMSA) | Play/App Store seller, `transmiapp@transmilenio.gov.co` |
| Package | `com.nexura.transmilenio` (Android + iOS) | Nexura Internacional built the original web/mobile work (case study 2010+, page 2017). The package name stuck |
| 2022 app contract | ITO SOFTWARE SAS | TMSA Resolución 491 / SAM-07-2022 |
| 2026 planner | Maas (LPG Colombia) + Siemens Mobility / Hacon | bogota.gov.co 2026-04-29; Hacon press 2026-05-27. Journey planning, not a replacement for the Bodega AVL host |
| Live host | Google App Engine, project name `tmsa-transmiapp-shvpc`, region `uc` (us-central) | hostname + Spring Boot actuator + `server: Google Frontend` |

The 2026 renovation added a trip planner. The September verification found that the Bodega position API accepted the 2022-era iOS UA and `appid`. Treat Bodega as a surviving Nexura-era service, not as Hacon.

## Official app

- Display name: TransMi App
- iOS: [id 1195720094](https://apps.apple.com/app/transmi-app/id1195720094), first release 2017-06-02, version 2.9.9 (2026-08-20)
- Play: [com.nexura.transmilenio](https://play.google.com/store/apps/details?id=com.nexura.transmilenio), 5M+ installs, updated 2026-08-12
- Old client product name in the UA: `MetroBus/1.9.7` (build 276, Alamofire). Accepted in the September verification. The curl in [feed.md](feed.md) omits it.
- APK v2.9.6 teardown (jadx `APIServiceInterface.java`): [estebantorrg/transmilenio spec §5.8](https://github.com/estebantorrg/transmilenio/blob/main/spec.md)

Play copy advertises real-time SITP location with distance, approximate time, and occupancy. Occupancy is the live `ocupacion_bus` field; it is empty on most vehicles.

## Public reverse-engineers

The hostname is not in TMSA's open-data docs. Independent consumers:

| source | date | what they documented |
| --- | --- | --- |
| [cansadadeserfeliz/public_transport](https://github.com/cansadadeserfeliz/public_transport) `transmiapp/services.py` | commit 2023-09-04, Vera Mazhuga | first public `POST /location/ruta`, HTTP (use HTTPS), same `appid` + MetroBus UA. Sample JSON is missing later fields (`destino_limpio`, `angulo`, `nombre_sistema`, `accesibilidad`, `ocupacion_bus`). Their `Bus.route_id=item['id']` mapping is wrong |
| [estebantorrg/transmilenio](https://github.com/estebantorrg/transmilenio) spec.md | APK v2.9.6 | full Bodega surface (`/buses`, `/location/ruta`, `/paradero/buses`, `/getServicios`, `/consultar_programacion`, TuLlave POIs, card). Catalog `loader.php` wire `lFuncion`s. CO-IP geofence confirmed 2026-09-04: Cloudflare Worker in MIA with a valid `appid` got 401 on `/location/ruta`; `GET /actuator/health` from the same worker still answered |
| [NicolasR2/transmiapp-buses-api](https://github.com/NicolasR2/transmiapp-buses-api) | 2026 | `/buses` only |
| [andresayac/transmi-app](https://github.com/andresayac/transmi-app) | 2026 | web proxy onto the same host |

No second appspot AVL host turned up. Sibling names 404. GIS operational folders that sound like ITS/SAE are token-gated, not a substitute.

estebantorrg also lists catalog, OTP, Firebase Storage, a Cloud Run `ms-transmiapp-…`, and `app.sitp.gov.co` Basic-auth geocoder (credential in the APK; hunt found that geocoder 404). Those are not the position feed.

## ToS (factual)

Page: [Política de privacidad y condiciones de uso TransMiApp](https://www.transmilenio.gov.co/publicaciones/153173/politica-de-privacidad-y-condiciones-de-uso-transmiapp/) (última actualización 2025-12-02).

The page authorizes personal, private, non-commercial use of contents (view / print / download with attribution). It forbids copying or altering TMSA internet contents to associate them with other sites, and forbids use that risks correct operation of the app. TMSA may suspend or block access. There is no sentence that says "no scraping" or "no reverse engineering" by those words. IP stays with Distrito / TRANSMILENIO.

Feed credentials belong in ignored local configuration. Do not republish the `appid` as a public API key; it is already in the APK and in the GitHub consumers above.
