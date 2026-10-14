# Android app scope

TransMinuto combines arrivals for the services a person would take at one station or stop. The person chooses which bus to take. The Android app uses Kotlin, Jetpack Compose, and direct native requests to the unofficial feed. Build instructions are in [android/README.md](../android/README.md); current interactions are in [android-design.md](android-design.md).

## Saved boards

1. Search for a station or stop by name or code.
2. Select its services and optionally name the board.
3. Save it and repeat for other boarding places.

A board has exactly one station or stop. The app handles catalog and feed identifiers internally. The user's commute supplies test fixtures, rather than a fixed app configuration.

The home screen shows one board at a time and remembers the last selection. Swipe between boards or choose one from the station-title sheet. Editing changes the place, services, or name; deletion offers undo. Settings can reorder boards.

## Arrivals

Selected services share one list, approximately soonest first. Station boards use identified vehicles from `/getServicios`; stop boards use service estimates from `/paradero/buses`. Overlapping ranges do not establish an exact ordering, and service estimates must not appear as several identified buses.

The visible board refreshes every 25 seconds. Manual refresh has an eight-second throttle after successful requests; failures can retry immediately. Cached content remains available during refreshes and failures. The data model distinguishes loading, cache, successful empty results, unsupported selections, and request failures. The current home UI shows refresh progress and empty/failure messages, but omits a persistent fetch timestamp.

Arrivals retain the server's coarse time phrase, with spacing and units cleaned for display. Vehicle reports older than two minutes are marked stale. Fetch time and source report time remain separate. Missing occupancy means unknown. The feed may cap the number of returned vehicles, so the list is not a complete count of inbound buses.

## Offline catalog

Search finds services by code or destination and places by name or code. Place details list serving services and can start a board. Service details show published operating hours, separate directions or variants, and ordered route stops with wagons. These details are packaged and available offline on first launch.

Published operating hours describe service availability, not the time a bus passes a particular stop. Public catalog variant IDs remain separate from GTFS and live-feed route IDs.

## Boundaries

The app has no journey planner, map, bus recommendation, departure-time prediction, account requirement, background tracking, arrival notification, or trip-history recording. It does not use personal location. Crowding is shown only when reported by the feed.

Read [feed.md](feed.md), [arrivals.md](arrivals.md), and [join.md](join.md) before integrating or testing live endpoints. Access depends on the unofficial host, Colombian egress, configured app ID, and persistent installation UUID. A successful fetch can return no nearby vehicles.

The practical test remains whether a rider can notice an approaching alternative without opening separate service views. UI responsiveness and a successful request alone do not establish that the source reports are timely enough.
