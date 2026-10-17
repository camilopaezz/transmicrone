# Catalog updates

## Hosted source access probe

`.github/workflows/catalog-probe.yml` runs `tools/probe_catalog_sources.py`
on a GitHub-hosted Ubuntu runner. It needs no secrets and does not call the
live arrivals host, modify the packaged catalog, or publish updates.

The probe downloads the discovered GTFS archive and checks headers and a first
row in each file consumed by the builder. It reads all pages of the three GIS
layers, checks the catalog list and operating hours, and requests three route
details. Each check reports independently; any failure fails the job. The JSON
report is retained as a workflow artifact for 14 days, with a job summary.

Run it from Actions using **Catalog source probe → Run workflow** once the
workflow is on the default branch. During the initial experiment, pushes that
change the probe or its workflow on `t3/catalog-over-the-air-updates` also run it.
To run locally without writing generated catalog files:

```sh
python3 tools/probe_catalog_sources.py --out /tmp/catalog-source-report.json
```

Initial hosted run on October 16, 2026, Bogotá time:
[successful run and report](https://github.com/camilopaezz/transmicrone/actions/runs/39000368086).
All checks passed:

- GTFS snapshot `GTFS_20261016.zip`, 120,460,459 bytes.
- GIS stations: 152 rows; stops: 7,610 rows; services: 693 rows.
- Catalog variants: 1,247; operating-hours records: 1,247.
- Route detail samples `1`, `10`, and `1003`: nonempty stop lists.

This demonstrates catalog source access from that hosted runner at that time.
It does not establish access from every location, full catalog correctness,
future availability, or access to the separately hosted live arrivals feed.
No Colombian collection host is needed based on this initial result.

## Next steps

Run the full builder on hosted infrastructure, then add completeness and change
validation before enabling unattended publication. The intended distribution
is versioned catalog files and a compatibility manifest in object storage.
Storage publishing, scheduled generation, Android downloads, and saved-board
reconciliation are not implemented by this probe.
