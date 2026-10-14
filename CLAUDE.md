# TransMinuto

Android arrivals boards and local tools for Bogotá's TransMilenio and SITP.

Builds, previews, and device installs: follow [AGENTS.md](AGENTS.md). Build commands and catalog refresh instructions are in [android/README.md](android/README.md).

Read [docs/feed.md](docs/feed.md) before configuring or testing live positions. Read [docs/arrivals.md](docs/arrivals.md) before testing station or stop arrivals. Credentials belong in ignored local configuration.

For live-field joins to GTFS, GIS, or fleet IDs, read [docs/join.md](docs/join.md). Public catalog variant IDs are separate from GTFS and live route IDs.

Domain terms: [CONTEXT.md](CONTEXT.md). App scope: [docs/app-scope.md](docs/app-scope.md). Current UI: [docs/android-design.md](docs/android-design.md). Source inventory and provenance: [docs/sources.md](docs/sources.md), [docs/provenance.md](docs/provenance.md).

Local arrival inspection: `scripts/eta.py S43 TM0069`. For S43 and S48 together: `scripts/ricaurte.sh`. These tools' estimates are separate from the Android app's server-provided arrival phrases.
