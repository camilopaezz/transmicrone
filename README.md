# TransMinuto

[![Android CI](https://github.com/camilopaezz/transmicrone/actions/workflows/android.yml/badge.svg)](https://github.com/camilopaezz/transmicrone/actions/workflows/android.yml)

Personal arrivals boards for Bogotá's TransMilenio and SITP. A native Android app built with Kotlin, Jetpack Compose, and Material 3, with a Spanish UI.

Save a station or stop, choose its services, and see arrivals together. Swipe between boards or tap the station title to choose one from the board sheet. Pull down to refresh. Use the app-bar plus to add a board and the pencil to edit it; deletion is available in the editor. Station arrivals show individual vehicle reports; old reports stay marked as old. The app polls the visible board every 25 seconds.

Use the top-right search icon to find services by code or destination, or stations and stops by name or code. Place details list their services and can start a new arrivals board. Service details show published operating hours and the official route from origin to destination, with ordered stops and wagons. Hours and routes are packaged in the catalog and work offline from the first launch. Separate directions and route variants remain separate. Published service hours do not specify when a bus passes a particular station or stop.

## Build and run

Install JDK 21 and an Android SDK with platform 36 and build tools 36.1.0. Set `JAVA_HOME` and `ANDROID_HOME`, or configure the SDK path in `android/local.properties`.

For live arrivals, copy `feed.local.properties.example` to `feed.local.properties` and configure the app ID locally. `TRANSMICRONE_APP_ID` and `TRANSMICRONE_BASE_URL` override this file. Credentials are excluded from Git. The default build uses the live feed.

```sh
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n co.camilo.transmicrone.debug/co.camilo.transmicrone.MainActivity
```

Use live data for development and previews first. If the feed is unavailable or empty, emulator previews can use `./gradlew :app:assembleDebug -PmockFeed=true`. The app labels this mode `Datos de ejemplo` and uses a separate arrivals cache. Physical devices always use `-PmockFeed=false` with configured credentials; follow [AGENTS.md](AGENTS.md) for installation checks.

For physical-device builds, use `./gradlew :app:assembleDevice -PmockFeed=false`
and `app/build/outputs/apk/device/app-device.apk`. This build disables debugging and uses
the development package and signing key, so installing it with `-r` preserves saved
boards and settings. It always uses the live feed. Stop screen mirroring before
measuring frame timing; the capture process can add display-buffer stalls on the Xiaomi.

## Continuous integration

GitHub Actions runs unit tests, Android lint, debug assembly, and instrumented tests on an Android 15 emulator for pushes and pull requests. It also checks Python syntax and publishes the debug APK and test reports as build artifacts. CI uses no live-feed credentials; its APK needs a locally configured build for live access.

## Project

- `android/`: app, packaged catalog, and tests. [Android notes](android/README.md)
- `scripts/`: local tools for querying the live feed.
- `tools/`: catalog generation and Android asset packing.
- `data/`: generated catalog and small source fixtures.
- `docs/`: feed contracts, app scope, current design, source provenance, and release notes.

The live host is unofficial and can reject requests or stop responding. See the [feed contract](docs/feed.md) and [data sources](docs/sources.md). This project is not affiliated with TransMilenio.
