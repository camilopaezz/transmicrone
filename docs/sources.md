# Official TransMilenio / SITP data

The sources checked on 2026-09-04 were static. No public GTFS-RT Vehicle Positions feed, SIRI/MQTT AVL, or GIS vehicle layer was found. Live positions are the unofficial Bodega host in [feed.md](feed.md).

Checked 2026-09-04: TM open-data page, Bogotá CKAN, ArcGIS Hub keyword search (AVL/GPS/tiempo real → 0), public GIS folders, Mobility Database, Transitland atlas, datos.gov.co.

## GTFS schedule

Working download: `https://storage.googleapis.com/gtfs-estaticos/`

Near-daily zips. Newest on 2026-09-04: `GTFS_20260904.zip` (Last-Modified 09:19 UTC). Naming mixes `GTFS_YYYYMMDD.zip`, `GTFS-YYYY-MM-DD.zip`, and `YYYY-MM-DD 04:00:XX_Static.zip`. No `.pb` / realtime objects in the bucket.

`feed_info.txt` in that snapshot: publisher Transmilenio S.A., `feed_start_date=20260101`, `feed_end_date=20261231`.

Files: agency, routes, trips, stops, stop_times (has `shape_dist_traveled` in metres), shapes (`shape_id` = `route_id`), calendar, calendar_dates, frequencies (cable only), fare_attributes. No `direction_id`. `trip_headsign` is empty; headsigns come from the live feed / GIS, not from GTFS trips.

CKAN dataset [Especificación GTFS SITP](https://datosabiertos.bogota.gov.co/dataset/especificacion-gtfs-general-transport-feed-specification-sitp) is a stale stub (metadata 2023-12-18, data date 2022-01-24). It points at the ArcGIS hub, not at the GCS zip. License there: CC BY-SA 4.0. Pull from GCS.

Transitland atlas [`transmilenio.gov.co.dmfr.json`](https://github.com/transitland/transitland-atlas/blob/master/feeds/transmilenio.gov.co.dmfr.json): spec `gtfs` only, `unstable_url: true`, still listing `GTFS-2024-02-21.zip`. Mobility Database mdb-3358 is the same static feed; zero `gtfs_rt` rows for Bogotá/TransMilenio.

Join keys: [join.md](join.md).

## GIS

- Hub: [datosabiertos-transmilenio.hub.arcgis.com](https://datosabiertos-transmilenio.hub.arcgis.com/)
- REST: `https://gis.transmilenio.gov.co/arcgis/rest/services`

Root `https://gis.transmilenio.gov.co/` is an IIS default page. The REST directories behind it work.

Public layers are infrastructure: estaciones, trazados, paraderos, patios, cable, TransMiBici, Servicios (rutas). Useful join layer:

`ConsultaSubgerenciaPlanificacionSITP/Consulta_Planificacion_SITP/FeatureServer/15` (Servicios). Fields `route_id`, `cod_ruta`, `cod_linea`, `dest_ruta`, `orig_ruta`, `comp_sitp`, `long_ruta` (km; B23 23.032 matches the GTFS shape length). GIS can be behind GTFS.

`GTFS_dinamicos/RH_TRONCAL` is linear-referencing calibration (`Calibration_Point`, `Centerline`, `TRONCALES`). Not AVL.

Empty or token-gated (HTTP 499): `Buses`, `ITS_NOSIRCI`, `SAT`, `Subgerencia_Operaciones`, `conexiones_bigQuery`, `GTFS` on `/server`. Do not treat those folder names as a public vehicle feed.

Catastro also publishes a static `movilidad/transportepublico` MapServer (estaciones, paraderos, rutas). Same limitation.

## Fleet

[Flota Vinculada del SITP](https://datosabiertos.bogota.gov.co/dataset/flota-vinculada-del-sitp) (CC BY 4.0). CKAN CSV lags (dic 2024 on the dataset page). Fresher dumps: `https://storage.googleapis.com/validaciones_tmsa/FlotaVinculada.html`.

`codigo_bus` + `accesible` join to live `label` / `accesibilidad` after zero-padding. The oct 2021 "buses en operación" CSV still has the numeric id that matches live `id` for troncal (`10032` / `D0032`).

`componente` values: TRONCAL, ALIMENTACIÓN, Zonal.

## Other official catalogs (not positions)

TM lists 17 open datasets on [this page](https://www.transmilenio.gov.co/publicaciones/151922/consulta-toda-la-oferta-de-datos-abiertos-de-transmilenio) (URL 301s to a comunicaciones path). All static or historical: horarios, matrices de paradas, validaciones TuLlave, salidas, patios. datos.gov.co mirrors some of them. No extra live feed.

App catalog (not open data): `https://api.buscador-rutas.transmilenio.gov.co/loader.php?lServicio=Rutas&lTipo=api&lFuncion=…`. `searchRutaByTipo` supplies service variants and operating hours; `infoRuta` supplies ordered route stops. `tools/build_catalog.py` and `tools/update_catalog_details.py` use them to populate the offline Android catalog. Public catalog variant IDs are separate from live-feed and GTFS route IDs. The old `www.transmilenio.gov.co/loader.php` AJAX path is 404.

Journey planner: `https://planeador.transmilenio.gov.co/otp/routers/default/plan2` is OpenTripPlanner. It is not vehicle positions.

`mapadigital.transmilenio.gov.co` is an ArcGIS map UI on the same static layers.
