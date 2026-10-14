# TransMinuto Android

Native Spanish-language arrivals boards built with Kotlin, Jetpack Compose, and Material 3 Expressive. App scope is in [app-scope.md](../docs/app-scope.md); current UI behavior is in [android-design.md](../docs/android-design.md).

## Build and verify

Use JDK 21 and Android SDK platform 36 with build tools 36.1.0. Set `JAVA_HOME` and `ANDROID_HOME`, or set `sdk.dir` in `android/local.properties`.

From `android/`:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug -PmockFeed=false
./gradlew :app:connectedDebugAndroidTest -PmockFeed=false
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Instrumented tests need a running emulator. GitHub Actions runs these checks and the Python tests; release notes record validation for each prerelease.

Feed settings are compiled from environment variables or the ignored root `feed.local.properties`. Configuration and request contracts are in [feed.md](../docs/feed.md) and [arrivals.md](../docs/arrivals.md). A build can succeed without an app ID, but it cannot fetch live arrivals.

## Emulator previews and physical devices

Follow [AGENTS.md](../AGENTS.md) for live-data and installation checks. Use live data first. If the feed is unavailable or empty, an emulator preview can use:

```sh
./gradlew :app:assembleDebug -PmockFeed=true
```

This debug mode shows `Datos de ejemplo`, includes varied occupancy and an old report, and uses a separate arrivals cache. Rebuild with `-PmockFeed=false` to return to live data.

Install on the running emulator, preserving saved boards and settings:

```sh
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n co.camilo.transmicrone.debug/co.camilo.transmicrone.MainActivity
```

Physical devices always use live builds with configured credentials. For a build without debugger overhead:

```sh
./gradlew :app:assembleDevice -PmockFeed=false
adb -s SERIAL install -r app/build/outputs/apk/device/app-device.apk
adb -s SERIAL shell am start -n co.camilo.transmicrone.debug/co.camilo.transmicrone.MainActivity
```

The `device` build uses the development package and debug signing key. Verify the generated `MOCK_FEED = false` and configured app ID before installing, then open the app and check a live fetch as required by AGENTS.md. A successful empty result is different from a failed request.

## Catalog

Run catalog tooling from the repository root. To rebuild place and service joins from official static sources:

```sh
python3 tools/build_catalog.py
python3 tools/pack_android_catalog.py
```

To refresh operating hours and ordered routes while preserving existing place and GTFS joins:

```sh
python3 tools/update_catalog_details.py
python3 tools/pack_android_catalog.py
```

The refresh uses two concurrent public-catalog requests and a resumable cache under `/tmp/transmicrone-route-details`, recreated when absent. Remove that cache for a fresh download. Public catalog variant IDs are separate from GTFS and live-feed route IDs.

A full rebuild retains previously downloaded routes for unchanged variants when `infoRuta` calls are skipped, limited, empty, or fail. Retained rows keep their original download date. A failed service-list request aborts before overwriting the catalog. Distinct catalog variants remain selectable even when their stop lists are identical, preserving their destinations and schedules.

Search, published hours, and ordered route stops work offline from first launch. Packing preserves directions, repeated visits, wagons, and pass-through stops. It omits places without a live query code or serving services.

Source asset: `app/src/main/assets/catalog.json.gz`. Android's asset merger expands it to `assets/catalog.json`; the runtime reads JSON directly. `PackagedCatalogTest` verifies the installed asset. Small source fixtures remain under `data/fixtures/` and Android test resources.

## Animation performance

Stop continuous mirroring and recording before measuring phone performance. The Xiaomi's Device panel stream was observed to add frame-pacing and display-buffer stalls. Panel video cannot establish native 120 Hz performance. Measure after the launch boost expires and compare taps with swipes.

Build the live `device` variant first, then collect a capture after each interaction:

```sh
adb -s SERIAL shell dumpsys gfxinfo co.camilo.transmicrone.debug reset
# Tap a title side and wait for the slide to finish.
adb -s SERIAL shell dumpsys gfxinfo co.camilo.transmicrone.debug framestats > /tmp/title-tap.txt
python3 scripts/android-frame-stats.py /tmp/title-tap.txt
```

Run the checker from the repository root. It examines the first 280 ms and excludes flagged frames. Pass multiple captures to aggregate a batch; compare mirroring on and off within the same app process.

## Prerelease signing

Tags such as `v0.1.0-alpha.1` trigger `.github/workflows/prerelease.yml`. The workflow builds, tests, lints, verifies the permanent signature, and publishes a live release APK with SHA-256 checksums. The tag must match the Gradle version and have notes under `docs/releases/`.

Repository secrets are `ANDROID_SIGNING_KEYSTORE`, containing the base64-encoded keystore, `ANDROID_SIGNING_STORE_PASSWORD`, `ANDROID_SIGNING_KEY_ALIAS`, `ANDROID_SIGNING_KEY_PASSWORD`, and `TRANSMICRONE_APP_ID`. Keep an offline recovery copy of the permanent key and passwords. Local signing reads the ignored `signing.local.properties` with `STORE_FILE`, `STORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`.

Release uses `co.camilo.transmicrone`; development builds use `co.camilo.transmicrone.debug`. Saved boards belong to their installation and do not transfer automatically between these packages.
