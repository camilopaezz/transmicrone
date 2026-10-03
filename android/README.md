# Transmicrone Android

Personal arrivals boards for TransMilenio / SITP. Unofficial Bodega host. Spanish UI. No accounts, maps, notifications, or location.

## Build

JDK **21** (do not use the system Java 27):

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME="$HOME/Android/Sdk"
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Debug APK: `android/app/build/outputs/apk/debug/app-debug.apk`

For UI review without the live feed, build with `./gradlew :app:assembleDebug -PmockFeed=true`.
This debug-only mode shows “Datos de ejemplo” in the app bar and generates arrivals for the
selected services, including varied occupancy and an old vehicle report. Refresh takes 900 ms
so loading feedback can be reviewed. Saved boards are shared, but sample arrivals use a separate
cache. Rebuild without `-PmockFeed=true` to return to the live feed. Release always uses the live feed.

Install on the running emulator:

```bash
$ANDROID_HOME/platform-tools/adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
$ANDROID_HOME/platform-tools/adb -s emulator-5554 shell am start -n co.camilo.transmicrone.debug/co.camilo.transmicrone.MainActivity
```

Use `JAVA_HOME` to select JDK 21. `local.properties` can set `sdk.dir`, or use `ANDROID_HOME`. Feed settings are read from environment variables or the ignored root `feed.local.properties` at compile time. The default host is documented in `docs/feed.md`; the app ID is never committed. Without an app ID the build still succeeds, but live requests are unauthorized. Mock builds and automated tests need no credential.

Material 3 **1.4.0**, the current stable Material library, with Compose BOM **2025.08.01**. This compatible Compose baseline runs on AGP 8.13.2 and compileSdk 36. The UI follows the Material 3 Expressive direction without depending on alpha-only components. Design details: `docs/android-design.md`.

## Catalog

Search uses the packed official catalog, not a hard-coded commute.

```bash
python3 tools/build_catalog.py
python3 tools/pack_android_catalog.py
```

Asset: `android/app/src/main/assets/catalog.json.gz`. Places without a live `query_paradero` or serving (including GIS Ricaurte CL 13 / nodo 12003) are omitted.

Android's asset merger expands this gzip source into `assets/catalog.json` in the APK. The runtime loader reads the packaged JSON without a second gzip decode. `PackagedCatalogTest` checks the actual installed asset and the commute servicios.

## Use

1. Agregar tablero.
2. Search an estación or paradero by name.
3. Multi-select painted servicios that serve that place.
4. Swipe, tabs, or previous/next to change boards.

The list is one combined phrase list, approximately soonest first. `labeltiempo` is shown as returned. Empty occupancy is “Ocupación desconocida”, never “Vacío”. A failed refresh keeps the last cache and names the failure (rejected host, network, timeout, …), distinct from an in-progress refresh. Disk cache after process restart is shown as old until this process gets a successful fetch. HTTP 403 is a host failure, not “no buses”. Station boards query `/getServicios` for every selected GTFS route variant, combine identified buses, and show distance, fleet label, and source report age. Stop boards retain `/paradero/buses` service estimates.

## Limits

- Unofficial feed; Colombian IP, `appid`, and a persistent per-installation `uuid` header. May 403/401 or vanish.
- No GTFS-RT. No background work.
- Polling: 25 s on the visible board while started; 8 s manual throttle. Cadence is still pending a live sample.
- Do not treat `scripts/eta.py` speeds as in-app predictions.

## Checks (2026-10-02)

JDK 21. No new SDK licenses accepted. No commit.

- `:app:testDebugUnitTest` SUCCESS, 25 tests / 0 failures, including light/dark text contrast
- `:app:lintDebug` SUCCESS, 0 errors, 17 warnings (dependency/targetSdk/unused resources)
- `:app:assembleDebug` SUCCESS — `android/app/build/outputs/apk/debug/app-debug.apk`
- `:app:connectedDebugAndroidTest` SUCCESS on emulator-5554 Daily_API35, 11 tests, including stale vehicle reports, packaged catalog and installation-ID persistence regressions
- Screenshots: `android/screenshots/empty-home.png`, `android/screenshots/setup.png`
- Initial live Bodega POSTs and health checks returned 403. Adding the newly required installation `uuid` restored arrivals access on 2026-10-02. Host POST returned 200 with 24 service rows; agent-device verified S43 to San Mateo at Ricaurte NQS, "Entre 5 y 7 min", "Ocupación desconocida", and an updated timestamp.
- Agent-device verification completed search → multi-select → save for León XIII with E48/K43 and Ricaurte NQS with S43/S48. Swiping, tabs, previous/next arrows, opening edit with preserved selections, saving edits, and reopening the last-used board passed.
- Search initially crashed with `FileNotFoundException: catalog.json.gz`, revealing the previous app's Settings screen. The asset loader now reads the JSON name produced by Android's asset merger. The regression test failed before this fix and passed afterward.
- JVM tests, lint, debug assembly, and all seven device tests passed after the catalog-loader, theme, and UUID-header fixes.
- Before the UUID fix, live refresh showed "el servidor rechazó la consulta". After the fix, live refresh succeeded. The UUID remained identical across app close/reopen, and arrivals refreshed successfully after reopening. Failure with a real populated cache remains unverified.
- Screenshots from this walkthrough: `screenshots/agent-device-ricaurte.png`, `screenshots/agent-device-edit-leon.png`.
- The UI refresh adds a dedicated saved-board loading state, so reopening no longer presents an empty-board prompt before persistence finishes loading.
- Live screenshot: `screenshots/agent-device-live-ricaurte.png`.
- TalkBack on a device and large text were not run

The PC tools were updated for the same installation-header requirement. `python3 scripts/eta.py S43 TM0069 -n 1` succeeded with seven live vehicles and three nearby results. See `docs/android-data-validation.md` for the correction to the earlier access-policy hypothesis.

## Material 3 UI refresh

Home, arrivals, setup, and board management were redesigned with dynamic light/dark color, rounded tonal surfaces, larger station/ETA typography, a pill search field, service-selection rows, an extended add-board FAB, and pull-to-refresh. Setup uses a bottom save action. Board options use a menu with deletion confirmation, and system Back returns to home. Polling remains every 25 seconds. Station cards now use individual bus reports; the server’s time range remains an estimate.


## Arrival source correction, 2026-10-02

A paired live S43 check at 08:19 Bogota showed `/paradero/buses` claiming “Servicio llegando” at 563 m while `/getServicios` reported the nearest bus at 2594 m, “Entre 4 min. y 7 min.”. The Android station path now uses the vehicle endpoint, not that conflicting summary. Every route variant is queried with at most two concurrent requests, and a vehicle is shown once using its latest returned report. A request failure preserves the previous complete board instead of substituting a coarse summary.

Each vehicle card shows its reported distance and `lasttime` separately from the board’s fetch time. Reports older than two minutes replace the main ETA heading with “Reporte antiguo”; the last estimate remains labelled below it. Report age updates every five seconds. Since `lasttime` contains only a local clock time, the parser infers the most recent Bogota date with five minutes of clock-skew tolerance. Missing or invalid source times are marked unavailable. No precise countdown is calculated. The endpoint can cap the returned vehicles, so this is not a full inbound bus count.
