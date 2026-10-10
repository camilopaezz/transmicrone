# Transmicrone

Local tooling against Bogotá TransMilenio / SITP live bus positions.

Builds, previews, and device installs: follow [AGENTS.md](AGENTS.md) for live data and mock fallback rules.

Live positions come from TransMiApp's Bodega host (unofficial). Contract, fields, failure modes: [docs/feed.md](docs/feed.md). Read it before calling the feed, verifying it still answers, or mapping a response into types.

How to call (base URL, `appid`, curl): [docs/feed.md](docs/feed.md).

Stop arrivals board: [docs/arrivals.md](docs/arrivals.md) before `/paradero/buses`. Next N buses at an estación: `scripts/eta.py S43 TM0069`. S43+S48 at Ricaurte NQS: `scripts/ricaurte.sh`. Browser board: `python3 scripts/serve-ricaurte.py` then [ricaurte.html](ricaurte.html) (Bodega has no CORS; do not open the file directly).

Join live fields to GTFS, GIS, or fleet IDs: [docs/join.md](docs/join.md).

Official catalogs are static. No GTFS-RT. Inventory: [docs/sources.md](docs/sources.md).

Who hosts this, ToS, other reverse-engineers: [docs/provenance.md](docs/provenance.md).

Domain language: [CONTEXT.md](CONTEXT.md).
