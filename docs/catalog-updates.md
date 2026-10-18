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

`catalog-overnight.yml` initially collected six hourly overnight samples on a
GitHub-hosted runner. The follow-up experiment on October 18 collects a sample
immediately, then at each whole hour through noon Bogotá time, including noon.
It uses a fixed deadline of `2026-10-18T12:00:00-05:00` for its branch push
trigger. Manual dispatch accepts an explicit deadline within 3.5 hours.
There is no daily schedule. The job stops after its last sample and has a
240-minute overall limit. `plan.json` records all intended sample times.
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
The [capture verification run](https://github.com/camilopaezz/transmicrone/actions/runs/39133334129)
successfully uploaded 23 files including response bodies and request metadata.
Both dated GTFS archives were subsequently
[recovered and retained](https://github.com/camilopaezz/transmicrone/actions/runs/39133408272)
with SHA-256 checksums exactly matching the overnight reports. These recovered
archives are available in `recovered-overnight-gtfs` for 30 days. The dedicated
`catalog-recover-gtfs.yml` workflow verifies those recorded checksums when rerun.

This temporary experiment keeps one hosted runner waiting between samples so
it can run from the branch without merging a recurring workflow into main.
It does not depend on the local PC. A production schedule should use separate
scheduled jobs instead of retaining a runner between samples.

## October 18 morning-to-noon results

The [collection run](https://github.com/camilopaezz/transmicrone/actions/runs/39133571125)
completed successfully. All five planned samples were present, collected at
08:54, 09:00, 10:00, 11:00, and 12:00 Bogotá time. All 40 source checks passed.
The one-time follow-up schedule was disabled after triggering.

The [comparison run](https://github.com/camilopaezz/transmicrone/actions/runs/39150416099)
downloaded the full artifact and verified all 55 response bodies against their
recorded sizes and SHA-256 checksums. Every captured request returned HTTP 200.
Each sample contains the GTFS archive, six GIS page responses, the catalog
search response, and three route-detail responses. No planned samples or
captured response bodies were missing.

Every response body was byte-for-byte identical to the corresponding first
sample. Parsed JSON comparison also found no changes, including no changes
attributable only to object-key or GIS/search row ordering:

- GIS station attributes: 152 records, unchanged.
- GIS stop attributes: 7,610 records, unchanged across all four pages.
- GIS service attributes: 693 records, unchanged.
- Catalog variants and published hours: 1,247 records each, unchanged.
- Complete sampled route-detail responses for `1` / `17-3`, `10` / `D237`,
  and `1003` / `K627`: unchanged, including ordered stops of 24, 61, and 60.
- `GTFS_20261018.zip`: 185,617,644 bytes in every sample, SHA-256
  `a6eb71b50dce1f31fe7f1a6368d4ff6041358a4bdf55f70be9d65a6d9f169c53`.
  Since the archive bytes did not change, there is no inter-sample archive
  member or transit-record change to investigate in this collection window.

The [machine-readable comparison](experiments/2026-10-18/noon-comparison.json)
is checked in alongside its [verification script](experiments/2026-10-18/compare_noon.py).
Run the script with the extracted collection artifact directory and an output
JSON path as its two arguments. The full original responses remain in the
collection artifact for its configured 30-day retention period. The comparison
artifact is retained for 30 days as `catalog-noon-comparison`.

This supports using hosted runners for collection and skipping publication when
the catalog's meaningful content is unchanged. It does not establish long-term
availability, full-network route-detail stability, or agreement between sources.
Only three route details were sampled, and the window contains no observed
network changes. The earlier overnight route-detail changes remain unexplained
because their earlier bodies were not retained. Publication validation still
needs source freshness, identity and join checks, completeness checks, and
confirmation of removals. Fetch success alone must not approve publication.

## Next steps

Run the full builder on hosted infrastructure, then add completeness and change
validation before enabling unattended publication. The intended distribution
is versioned catalog files and a compatibility manifest in object storage.
Storage publishing, scheduled generation, Android downloads, and saved-board
reconciliation are not implemented by this probe.
