# Catalog updates

## Hosted source access probe

`.github/workflows/catalog-probe.yml` runs `tools/probe_catalog_sources.py`
on a GitHub-hosted Ubuntu runner. It needs no secrets and does not call the
live arrivals host, modify the packaged catalog, or publish updates.

The probe downloads the discovered GTFS archive and checks headers and a first
row in each file consumed by the builder. It reads all pages of the three GIS
layers, checks the catalog list and operating hours, and requests three route
details. Each check reports independently; any failure fails the job. The JSON
report and response bodies are retained as a workflow artifact for 30 days,
with a job summary. Each probe writes a sibling `.responses` directory containing
the original GET response bytes and a metadata file per request with URL,
timestamp, status, headers, and checksum. Bodies are saved before JSON or ZIP
validation, including HTTP error responses. Connection failures have metadata
but no response body. Discovery HEAD requests are not captured.

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

## One-night variation experiment

`catalog-overnight.yml` collects six samples, one hour apart, on a GitHub-hosted
runner. It can be started via manual dispatch once available on the default
branch. The initial experiment used a branch push trigger, which has now been
removed to avoid restarting it while fixing the workflow. There is no daily
schedule. The job stops after six samples and has a 330-minute overall limit.
Each probe has a ten-minute limit; failures are recorded and later samples
continue. The Actions job summary compares each source with its first successful
sample. Check both the collected sample count and individual results; a completed
experiment does not imply that every source succeeded.

Reports and their sibling response directories are uploaded together as
`catalog-overnight-reports` and retained for
30 days. They include timestamps, durations, counts, errors, and SHA-256 content
fingerprints. GIS fingerprints include all returned attributes. GIS and catalog
search fingerprints ignore row ordering; route detail fingerprints preserve
array order. GTFS fingerprints cover the downloaded archive bytes, so a changed
ZIP alone does not prove the underlying transit records changed. Route details
sample only three variants, not the whole network.

The first overnight experiment retained only reports and fingerprints. It did
not retain response bodies, so the two changed route-detail responses cannot be
compared retrospectively. Response capture was added after that experiment.

This temporary experiment keeps one hosted runner waiting between samples so
it can run from the branch without merging a recurring workflow into main.
It does not depend on the local PC. A production schedule should use separate
scheduled jobs instead of retaining a runner between samples.

## Next steps

Run the full builder on hosted infrastructure, then add completeness and change
validation before enabling unattended publication. The intended distribution
is versioned catalog files and a compatibility manifest in object storage.
Storage publishing, scheduled generation, Android downloads, and saved-board
reconciliation are not implemented by this probe.
