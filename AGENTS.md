# Live data and device installs

Read [docs/feed.md](docs/feed.md) before configuring or testing the live feed, and [docs/arrivals.md](docs/arrivals.md) before verifying station or stop arrivals.

- Use live data first for development and previews. Check existing local configuration before declaring credentials missing.
- When the live feed is unavailable or returns no data, use a mock build for emulator previews that need populated cards. Clearly identify it as `Datos de ejemplo`.
- Always install live-data builds on physical devices, including the Xiaomi. Build with `-PmockFeed=false` and configured feed credentials. Mock fallback is for development previews only; a physical device shows the actual empty, stale, or error state when live data is unavailable.
- Before installing on a physical device, verify that the generated build has `MOCK_FEED = false` and a configured app ID. Preserve saved boards and settings when updating.
- After installation, open the app and verify a live fetch on the device. Report whether it returned current arrivals, an empty result, or a failure. A running transport service does not guarantee nearby arrivals for every selected service.

Keep feed credentials in the ignored local configuration or environment described in `docs/feed.md`.
