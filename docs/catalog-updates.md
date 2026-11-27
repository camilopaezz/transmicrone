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

## Daily R2 publication

`catalog-daily.yml` runs the full builder once a day at 10:37 UTC, 05:37 Bogotá
time, after the observed official GTFS publication window. GitHub schedules
run from the default branch and can be delayed by runner availability. Manual
dispatch supports a build-only run for diagnostics. Publication is independent
of Android releases.

The daily job collects every catalog variant's route detail, all GIS pages,
the route list and hours, and a fresh GTFS archive. It makes sequential requests
with a 0.2-second pause between details. Transient network failures and selected
HTTP failures receive up to two retries, with every attempt retained. It builds
in runner scratch space; the checked-in catalog and Android asset are untouched.

Malformed JSON, unexpected route-detail shapes, and empty stop lists also receive
up to two content retries, with one- and two-second backoff. After the first
collection pass, the builder waits 30 seconds and requests only failed variants
once more. Each request still uses the transport retry policy. Successful variants
are not fetched again. Every HTTP response remains captured, including rejected
HTTP 200 bodies. An unresolved failure still invalidates the candidate; a retry
never substitutes yesterday's details as a fresh response.
Stop records must have nonempty string codes, string names, and pass route
conversion before acceptance. Official platform records can have blank names;
the app displays their code instead. Results are processed in original variant order after recovery,
so a transient failure cannot change which variant supplies station metadata.

R2 configuration uses bucket `transminuto-catalog` and custom hostname
`https://transminuto-catalog.cpzhmlb.uk`. The bucket-scoped credential has Object
Read & Write permissions, with no administration access or access to other
buckets. GitHub stores `R2_ACCESS_KEY_ID` and `R2_SECRET_ACCESS_KEY` as encrypted
secrets. Repository variables `R2_ACCOUNT_ID`, `R2_BUCKET`, and `R2_PUBLIC_URL`
select the destination. `CATALOG_R2_READY=true` also permits the experimental
branch push trigger to exercise publication. Scheduled and manual publication
require R2 configuration and fail if it is missing.

Files under the bucket's `catalog/` prefix:

- `versions/<version>/catalog.json.gz`: the exact compact catalog format the
  app currently understands, with size and SHA-256 recorded in its manifest.
- `versions/<version>/source-catalog.json.gz`: complete generated source catalog
  used for comparisons with the last published version.
- `versions/<version>/manifest.json`: immutable candidate manifest, including
  schema versions, artifact URLs, checksums, build time, and content digest.
- `observations/<version>/responses.tar.gz`: original source response bodies,
  request metadata, and errors, including all route-detail variants and GTFS.
- `observations/<version>/report.json`: immutable source validation and added,
  removed, and changed record identities, with before/after examples. It is
  saved before promotion; a `ready` state means the assessment approved it.
- `observations/<version>/publication.json`: the subsequent publication outcome.
- `latest-observation.json`: the latest completed report, including held candidates.
- `current.json`: the current published manifest. An unchanged catalog keeps
  its published version and updates `checked_at` after a successful daily check.

Immutable objects use conditional creation, and catalog/manifest uploads receive
authenticated read-back and public HTTPS checksum verification. Evidence upload
must complete before `current.json` can advance. Public download verification
also checks `current.json` after updating it. Workflow concurrency serializes
publication runs. Previous version objects remain available for recovery.
Before refreshing `checked_at` for an unchanged catalog, the publisher verifies
the existing manifest's catalog download, since it retains that older artifact.

Public verification sends `User-Agent: transminuto-catalog/1.0`. The first
R2 publication checks found that this domain rejected Python's default
`Python-urllib` identity with Cloudflare error 1010, despite successful uploads
and authenticated read-back. The same URL and checksum passed with the explicit
publisher identity. This does not disable Cloudflare security settings or skip
public download verification.

Publication fails closed on missing or failed sources, incomplete or stale route
details, duplicate identities, broken place/route joins, expired GTFS validity,
or a GTFS archive older than two days. Removal of more than 10% of the published
identities in any compared record section blocks publication, even if additions
keep the total count stable. Ordinary removals must appear in two complete observations on different Bogotá calendar days. Each report now carries
`removal_evidence`, keyed by section and record identity, with `first_seen`,
`last_seen`, and an `observations` count. Dates use Bogotá calendar days. Invalid
or aborted collection preserves the ledger without advancing it. Multiple runs
on one day count once. A valid observation in which the record reappears clears
its pending removal. Evidence is also pruned when a removal is no longer relative
to the published baseline. Existing valid reports without a ledger supply the
first observation during migration. If the prior report cannot be read, the job
fails without overwriting `latest-observation.json` and its evidence.

First publication
bootstraps from a complete fresh build and records a comparison with the bundled
catalog. These checks are initial conservative rules to evaluate over daily runs.

Valid observations publish additions and edits while deferring unconfirmed
removals. Each pending removed identity is copied from the published baseline;
confirmed removals are applied. Retained serving links keep their place and
service dependencies, even if those parents' removals are confirmed. Referenced
route details and hours are retained when available in the baseline. Fresh
records always take precedence. Place service lists and publication counts are
rebuilt from the resulting catalog so retained links remain selectable in Android.

The source observation remains distinct from the effective publication. Reports
keep raw `changes`, `observed_counts`, and `removal_evidence`; `published_changes`,
`counts`, and `retained_records` describe the effective publication. Retention
reasons distinguish pending removals from required dependencies. Retained route
details preserve their original fetch timestamps. The uploaded source catalog,
compact catalog, and manifest digest all describe the same effective publication;
that source catalog becomes the next run's baseline. Raw candidates remain in
Actions artifacts alongside original response evidence. Confirmation still uses
only complete fresh observations, never the retained records.

This policy protects top-level identities in places, services, hours, route
details, and serving links. Changes within an existing record, including stop
sequence, platforms, and query metadata, remain edits and publish after validation.
It does not infer field-level removal confirmation.

A candidate containing only pending removals may leave the effective catalog
unchanged. The publisher verifies the retained download and refreshes `checked_at`
in that case. A changed effective catalog advances `current.json` only after
archiving and verification. Actions emits `Catalog removals deferred` when records
are retained. Invalid data, collection errors, verification errors, and evidence
or publication upload failures still fail the job and leave publication blocked.
Archive creation writes a temporary file and only renames it after a successful
close. A partial archive cannot hide the intact loose response files from the
artifact fallback. Both the response archive and assessment report must reach
R2 before the published pointer can change.

Workflow artifacts retain reports, the candidate, and full responses for 30 days;
R2 objects currently have no automatic deletion policy. GitHub artifacts also
include loose response files so a timeout before archiving does not discard them.

## Android downloads

Android checks the public `catalog/current.json` on a background thread when the
app process starts, at most once every six hours after a successful check. A
failed check retries on the next process start. The build property
`catalogBaseUrl` overrides the publisher URL. Catalog search, operating hours, and route stops load offline
from the newest usable local snapshot, either the packaged catalog or a previous
download. The app uses the catalog's `generated_at` to compare versions.
`checked_at` tracks publisher health, so an older check date does not prevent
Android from using the last published catalog.

The app accepts a supported manifest and an immutable catalog URL on the
publisher's host. It checks the compressed size and SHA-256 and loads the
candidate through the catalog parser before atomically replacing its private
copy. A failed check or download leaves the previous copy available. Parsing
streams the decompressed JSON. A cold start compares the packaged and downloaded
timestamps without building either catalog, then loads only the newer one.
Validating or switching to a download briefly holds a second catalog in the
background; running out of memory there keeps the current one.

The running app switches to a verified download when it returns to the
foreground on the home screen, or when a new catalog search starts. The new
catalog loads in the background and takes over only if that screen is still
showing, so a search, its details, and a board edit always use one snapshot.

The daily job rejects an artifact above Android's limits of 5 MiB compressed or
32 MiB decompressed, because every installed app would refuse it. Above 75% of
either limit, the job adds a `size_warnings` entry and a workflow warning so an
app release can raise the limits first.

Saved boards continue using their stored station or stop code and selected
service IDs for arrivals. Downloading a new catalog does not rewrite those
records. Automatic reconciliation of saved identifiers is a separate change.

## Freshness monitoring

`catalog-health.yml` checks the public `current.json` and
`latest-observation.json` three times daily, independently of collection, and
supports manual dispatch. It needs no R2 credentials. As with daily collection,
GitHub may delay scheduled starts; these are monitoring opportunities rather
than an exact delivery guarantee. The schedule takes effect on the default branch.

The health summary distinguishes the latest collection outcome from published
catalog freshness. It reports age since `generated_at` and age since `checked_at`.
An unchanged catalog that passed a complete daily check remains fresh through
its refreshed `checked_at`; a held or invalid candidate does not refresh that
timestamp. More than 48 hours without a successful verification of the published
catalog fails the health workflow and emits an error annotation. Missing,
unreadable, malformed, or future-dated timestamps also fail the check. The monitor
downloads the referenced compact catalog and verifies its size and SHA-256;
an unavailable or corrupt artifact fails even when manifest timestamps are fresh.
Collection metadata is evaluated independently: an unavailable or malformed
`latest-observation.json` is reported without masking a healthy published catalog.
Workflow
failure notifications follow the repository's existing GitHub Actions notification
settings; this does not create issues or send messages through another service.

Daily reports include `publication_health` and emit a warning when stale, without
turning an otherwise valid archived hold into a failed collection. Operators can
run the same read-only check locally:

```sh
python3 tools/catalog_health.py --max-age-hours 48
```

Ambiguous joins still require deterministic resolution separately. Pending removal
confirmation no longer blocks unrelated validated updates.

Pull requests changing the daily publication code, tests, or workflow run a full
live collection with `--preview`. This reads the public manifest, checksum-verified
source catalog, and removal ledger, then produces the effective publication
locally. It never creates an R2 client or receives R2 credentials. The artifact
contains the raw `catalog.json`, effective `publication-catalog.json`, compressed
publication files, report, and response evidence. `preview_result` reports whether
the run would publish a changed catalog, verify an unchanged one, or reject invalid
data. PR previews do not advance the production confirmation ledger.

Run this same read-only preview locally:

```sh
python3 tools/catalog_daily.py --out /tmp/catalog-preview --preview
```

## Catalog identity and ordering

Daily builds pass the published source catalog to the builder as
`--identity-baseline`. Existing station IDs are preserved by GTFS nodo even
when their TM query mapping disappears or changes. New stations use
`estacion-nodo:<nodo>` independently of their optional query key. The baseline
supplies identities only; it does not substitute old route-detail responses.

Platforms, route memberships, catalog-ID memberships, and top-level records
have deterministic ordering. Comparison and content digests also canonicalize
set-like fields in older catalogs, while preserving route-stop sequence.
An older digest format alone does not trigger publication. GIS pagination
requests object-ID order and rejects missing or repeated object IDs. GIS
service joins prefer the painted direction within each route ID; conflicting
matches leave optional GIS metadata unset and emit a warning rather than
choosing a response row arbitrarily.

On October 29, the full builder was replayed against the October 28 archived
responses after verifying all 1,251 body checksums. Reversing unordered inputs
previously changed 149 places and 476 services. After these fixes, it changes
only six places associated with ambiguous cenefa-to-node joins, with no service
metadata changes. The remaining 44 added and 72 removed serving links are all
at those six cenefas. Excluding those places gives identical source records
and packed catalogs; published station IDs remain intact. Ordered collection
also passed against all three live GIS layers. The
[verification record](experiments/2026-10-29/catalog-stability.json) retains the
counts and source provenance.

Cenefa ambiguity resolution is deferred to
[issue #9](https://github.com/camilopaezz/transmicrone/issues/9). Builds retain
the existing selection behavior and now report each ambiguity explicitly.
Both GIS and cenefa warnings appear in the daily report and Actions summary.
Warnings do not bypass validation or removal confirmation.

## October 24 hold alert investigation

The [October 24 run](https://github.com/camilopaezz/transmicrone/actions/runs/40100984277)
fetched all 1,244 catalog variants successfully, with no source or validation
errors. It retained the October 23 published catalog because 2 place identities,
6 route identities, and 205 serving links needed a second complete daily
observation before removal. All candidate files and response evidence were saved
in R2 under version `20261024T140612Z-40100984277-1`.

The [October 22 run](https://github.com/camilopaezz/transmicrone/actions/runs/39805797138)
was likewise a valid observation held for 126 serving-link removals. The
[October 23 run](https://github.com/camilopaezz/transmicrone/actions/runs/39978029836)
subsequently published successfully. These alerts did not indicate a broken R2
upload or a continuous catalog outage. The script had conflated a valid hold
with a failed daily observation by returning exit code 1 for both.

The regression test exercises the complete publication flow with a valid
candidate requiring removal confirmation. It verifies exit code 0, retained
candidate evidence and hold reasons, and an unchanged published manifest.
Additional tests ensure invalid data and evidence-upload failures during a hold
continue to return failure. Publication eligibility remains separate from the
observation's process exit status.
