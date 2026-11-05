#!/usr/bin/env python3
"""Build, assess, archive, and publish a daily catalog independently of the app."""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import sys
import tarfile
import traceback
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import patch
from zoneinfo import ZoneInfo

import build_catalog
from pack_android_catalog import pack
from probe_catalog_sources import ResponseCapture

SECTIONS = {
    "places": "place_id", "servicios": "route_id", "service_hours": "catalog_id",
    "service_routes": "catalog_id", "serving": None,
}


def encode(value: object) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def verify_public(obj: dict) -> None:
    import urllib.request
    request = urllib.request.Request(obj['url'], headers={'User-Agent': 'transminuto-catalog/1.0'})
    with urllib.request.urlopen(request, timeout=60) as response:
        if sha(response.read()) != obj['sha256']:
            raise RuntimeError('Public catalog download checksum mismatch')


def archive_responses(directory: Path, destination: Path) -> None:
    """Only expose the final filename after the archive is fully closed."""
    destination.unlink(missing_ok=True)
    temporary = destination.with_suffix(destination.suffix + '.partial')
    try:
        with tarfile.open(temporary, 'w:gz') as archive:
            archive.add(directory, arcname='responses')
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)


def clean(value):
    if isinstance(value, dict):
        return {key: clean(item) for key, item in value.items()
                if key not in {"generated_at", "fetched_at", "retrieved_at"}}
    if isinstance(value, list):
        return [clean(item) for item in value]
    return value


def records(catalog: dict, section: str) -> dict:
    key = SECTIONS[section]
    def canonical(row):
        value = clean(row)
        fields = {'places': ('platforms', 'served_route_ids', 'served_painted'),
                  'servicios': ('catalog_ids_sharing_painted',),
                  'service_hours': ('windows',)}
        for field in fields.get(section, ()):
            if field in value:
                value[field] = sorted(value[field], key=encode)
        return value
    return {str(row[key]) if key else f"{row['place_id']}:{row['route_id']}": canonical(row)
            for row in catalog.get(section, [])}


def compare(before: dict, after: dict) -> dict:
    result = {}
    for section in SECTIONS:
        old, new = records(before, section), records(after, section)
        changed = sorted(key for key in old.keys() & new.keys() if old[key] != new[key])
        result[section] = {
            "added": sorted(new.keys() - old.keys()), "removed": sorted(old.keys() - new.keys()),
            "changed": changed,
            "examples": [{"id": key, "before": old[key], "after": new[key]} for key in changed[:3]],
        }
    return result


def assess(catalog: dict, baseline: dict, build_exit: int, now: datetime, prior_observation: dict | None = None) -> dict:
    errors, holds = [], []
    if build_exit:
        errors.append(f"Builder exited {build_exit}")
    if catalog.get("schema_version") != 1:
        errors.append("Unsupported source catalog schema")
    if catalog.get("failures"):
        errors.append(f"{len(catalog['failures'])} source or join failures")
    sources = {source['kind']: source for source in catalog.get('sources', [])}
    for kind in ('gtfs', 'gis-estaciones', 'gis-paraderos', 'gis-servicios', 'catalog-searchRutaByTipo', 'catalog-infoRuta'):
        if kind not in sources:
            errors.append(f"Missing source: {kind}")
    for section, key in SECTIONS.items():
        rows = catalog.get(section, [])
        if not rows:
            errors.append(f"Empty {section}")
        if len(records(catalog, section)) != len(rows):
            errors.append(f"Duplicate identities in {section}")
    places = records(catalog, 'places')
    routes = records(catalog, 'servicios')
    if any(edge['place_id'] not in places or str(edge['route_id']) not in routes for edge in catalog.get('serving', [])):
        errors.append('Serving edge references an unknown place or route')
    expected = sources.get('catalog-searchRutaByTipo', {}).get('count', 0)
    detail_source = sources.get('catalog-infoRuta', {})
    if not expected or detail_source.get('ok') != expected or detail_source.get('candidates') != expected or detail_source.get('fail'):
        errors.append('Not every catalog variant has a fresh successful route-detail fetch')
    for section in ('service_routes', 'service_hours'):
        if len(catalog.get(section, [])) != expected:
            errors.append(f'Incomplete {section}: expected {expected}')
    for row in catalog.get('service_routes', []):
        if not row.get('stops') or row.get('fetched_at') != catalog.get('generated_at'):
            errors.append(f"Missing or stale route detail: {row.get('catalog_id')}")
    gtfs = sources.get('gtfs', {})
    try:
        modified = datetime.strptime(gtfs['name'][5:13], '%Y%m%d').replace(tzinfo=timezone.utc)
        if not 0 <= (now.date() - modified.date()).days <= 2:
            errors.append('GTFS snapshot is older than two days or future-dated')
        info = gtfs['feed_info'][0]
        today = now.strftime('%Y%m%d')
        if not info['feed_start_date'] <= today <= info['feed_end_date']:
            errors.append('GTFS validity does not cover today')
    except (KeyError, IndexError, ValueError):
        errors.append('Missing or invalid GTFS dates')
    changes = compare(baseline, catalog)
    for section, change in changes.items():
        if baseline and change['removed']:
            confirmed = set()
            if prior_observation and prior_observation.get('valid'):
                # Different Bogotá calendar days, not repeated manual runs of one snapshot.
                prior_day = datetime.fromisoformat(prior_observation['started_at']).astimezone(ZoneInfo('America/Bogota')).date()
                today = now.astimezone(ZoneInfo('America/Bogota')).date()
                if prior_day < today:
                    confirmed = set(prior_observation.get('changes', {}).get(section, {}).get('removed', []))
            unconfirmed = set(change['removed']) - confirmed
            if unconfirmed:
                holds.append(f"{section}: {len(unconfirmed)} removed identities need a second complete daily observation")
        old_count = len(baseline.get(section, []))
        if old_count and len(catalog.get(section, [])) < old_count * 0.9:
            errors.append(f'{section}: more than 10% of records disappeared')
    return {'valid': not errors, 'promotable': not errors and not holds,
            'errors': errors, 'holds': holds, 'changes': changes}


class R2:
    def __init__(self):
        import boto3
        from botocore.config import Config
        self.bucket = os.environ['R2_BUCKET']
        self.prefix = os.environ.get('R2_PREFIX', 'catalog').strip('/')
        self.public = os.environ['R2_PUBLIC_URL'].rstrip('/')
        if not self.public.startswith('https://'):
            raise ValueError('R2_PUBLIC_URL must use HTTPS')
        self.client = boto3.client('s3', region_name='auto',
            endpoint_url=f"https://{os.environ['R2_ACCOUNT_ID']}.r2.cloudflarestorage.com",
            aws_access_key_id=os.environ['R2_ACCESS_KEY_ID'],
            aws_secret_access_key=os.environ['R2_SECRET_ACCESS_KEY'],
            config=Config(retries={'max_attempts': 3, 'mode': 'standard'},
                          request_checksum_calculation='when_required', response_checksum_validation='when_required'))

    def key(self, name):
        return f'{self.prefix}/{name}'

    def read(self, name):
        from botocore.exceptions import ClientError
        try:
            response = self.client.get_object(Bucket=self.bucket, Key=self.key(name))
            with response['Body'] as body:
                return body.read()
        except ClientError as error:
            if error.response['Error']['Code'] in ('NoSuchKey', '404'):
                return None
            raise

    def put(self, name, raw, content_type='application/json', mutable=False):
        args = dict(Bucket=self.bucket, Key=self.key(name), Body=raw, ContentType=content_type,
                    CacheControl='no-cache' if mutable else 'public, max-age=31536000, immutable',
                    Metadata={'sha256': sha(raw)})
        if not mutable:
            args['IfNoneMatch'] = '*'
        self.client.put_object(**args)
        if self.read(name) != raw:
            raise RuntimeError(f'R2 read-back mismatch: {name}')
        return {'key': name, 'url': f'{self.public}/{self.key(name)}', 'sha256': sha(raw), 'bytes': len(raw)}

    def archive(self, name, path):
        self.client.upload_file(str(path), self.bucket, self.key(name),
            ExtraArgs={'ContentType': 'application/gzip', 'Metadata': {'sha256': sha(path.read_bytes())}})
        result = self.client.head_object(Bucket=self.bucket, Key=self.key(name))
        if result['ContentLength'] != path.stat().st_size:
            raise RuntimeError('R2 evidence archive length mismatch')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--build-only', action='store_true', help='Exercise collection without configured R2')
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    now = datetime.now(timezone.utc)
    version = now.strftime('%Y%m%dT%H%M%SZ') + '-' + os.environ.get('GITHUB_RUN_ID', 'local') + '-' + os.environ.get('GITHUB_RUN_ATTEMPT', '1')
    store, previous_manifest, prior_observation, baseline = None, None, None, {}
    report = {'version': version, 'started_at': now.isoformat(), 'mode': 'build-only' if args.build_only else 'r2',
              'run_url': f"https://github.com/{os.environ.get('GITHUB_REPOSITORY', 'camilopaezz/transmicrone')}/actions/runs/{os.environ.get('GITHUB_RUN_ID', '')}"}
    code = 1
    try:
        if not args.build_only:
            store = R2()
            previous = store.read('current.json')
            previous_manifest = json.loads(previous) if previous else None
            observation = store.read('latest-observation.json')
            prior_observation = json.loads(observation) if observation else None
            if previous_manifest:
                raw = store.read(previous_manifest['source_catalog']['key'])
                if raw is None or sha(raw) != previous_manifest['source_catalog']['sha256']:
                    raise RuntimeError('Previous R2 catalog missing or checksum mismatch')
                baseline = json.loads(gzip.decompress(raw))
        if not baseline:
            baseline = json.loads((build_catalog.DATA / 'catalog.json').read_text())
            report['baseline'] = 'bundled catalog; first R2 publication'
        else:
            report['baseline'] = previous_manifest['version']
        capture = ResponseCapture(out / 'responses', retries=2)
        identity_baseline = out / 'identity-baseline.json'
        identity_baseline.write_bytes(encode(baseline))
        argv = ['build_catalog.py', '--out', str(out / 'catalog.json'), '--test-out', str(out / 'test-case.json'),
                '--work-dir', str(out / 'gtfs'), '--sleep', '0.2', '--identity-baseline', str(identity_baseline)]
        try:
            with patch.object(sys, 'argv', argv), patch.object(build_catalog, 'http_json', capture.http_json), patch.object(build_catalog, 'http_bytes', capture.http_bytes):
                build_exit = build_catalog.main()
        except (Exception, SystemExit) as error:
            report['build_error'] = str(error)
            build_exit = 1
        if not (out / 'catalog.json').exists():
            raise RuntimeError('Builder did not produce a candidate catalog')
        candidate = json.loads((out / 'catalog.json').read_text())
        assessment = assess(candidate, baseline if previous_manifest else {}, build_exit, now, prior_observation)
        # The bundled catalog is useful for first-run comparison, but is not a prior publication.
        assessment['changes'] = compare(baseline, candidate)
        report.update(assessment)
        report['counts'] = candidate['counts']
        report['source_failures'] = candidate['failures']
        report['source_warnings'] = candidate.get('warnings', [])
        payload = {section: records(candidate, section) for section in SECTIONS}
        report['content_sha256'] = sha(encode(payload))
        packed = gzip.compress(encode(pack(candidate)), mtime=0)
        (out / 'catalog.json.gz').write_bytes(packed)
        source = gzip.compress(encode(candidate), mtime=0)
        (out / 'source-catalog.json.gz').write_bytes(source)
        (out / 'changes.json').write_bytes(encode(assessment['changes']))
        if store:
            base = f'versions/{version}'
            source_object = store.put(f'{base}/source-catalog.json.gz', source, 'application/gzip')
            artifact = store.put(f'{base}/catalog.json.gz', packed, 'application/gzip')
            manifest = {'schema_version': 1, 'catalog_schema_version': 1, 'version': version,
                        'generated_at': candidate['generated_at'], 'content_sha256': report['content_sha256'],
                        'checked_at': now.isoformat(),
                        'catalog': artifact, 'source_catalog': source_object, 'run_url': report['run_url']}
            report['candidate_manifest'] = store.put(f'{base}/manifest.json', encode(manifest))
            # Public read-back exercises the same path a future app will use.
            for obj in (artifact, report['candidate_manifest']):
                verify_public(obj)
            # Recompute using today's canonical comparison so an older digest format
            # cannot force publication when only irrelevant ordering changed.
            baseline_payload = {section: records(baseline, section) for section in SECTIONS}
            unchanged = previous_manifest and sha(encode(baseline_payload)) == report['content_sha256']
            report['publication'] = 'invalid' if not assessment['valid'] else ('unchanged' if unchanged else 'held')
            # Archive evidence before promoting the pointer, even when validation holds it.
            report['manifest'] = manifest
        else:
            report['publication'] = 'build-only'
        # A valid observation can succeed while removal confirmation prevents promotion.
        code = 0 if assessment['valid'] else 1
    except Exception as error:
        report['error'] = str(error)
        traceback.print_exc()
    finally:
        try:
            if (out / 'responses').exists():
                archive_responses(out / 'responses', out / 'responses.tar.gz')
            if store:
                if (out / 'responses.tar.gz').exists():
                    store.archive(f'observations/{version}/responses.tar.gz', out / 'responses.tar.gz')
                promote = code == 0 and report.get('promotable', False)
                if promote and report.get('publication') != 'unchanged':
                    report['publication'] = 'ready'
                # Persist the assessment and change evidence before changing the published pointer.
                store.put(f'observations/{version}/report.json', encode(report))
                store.put('latest-observation.json', encode(report), mutable=True)
                if promote and report.get('publication') != 'unchanged':
                    current = store.put('current.json', encode(report['manifest']), mutable=True)
                    report['publication'] = 'published'
                    verify_public(current)
                elif promote:
                    refreshed = {**previous_manifest, 'checked_at': now.isoformat(), 'last_checked_run_url': report['run_url']}
                    current = store.put('current.json', encode(refreshed), mutable=True)
                    verify_public(current)
                store.put(f'observations/{version}/publication.json', encode({
                    'publication': report.get('publication', 'failed'),
                    'finished_at': datetime.now(timezone.utc).isoformat(),
                }))
                store.put('latest-observation.json', encode(report), mutable=True)
        except Exception as error:
            report['publication_error'] = str(error)
            code = 1
            traceback.print_exc()
        report['finished_at'] = datetime.now(timezone.utc).isoformat()
        (out / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        summary = f"Catalog {version}: {report.get('publication', 'failed')}\n\n" + json.dumps({key: report.get(key) for key in ('counts', 'errors', 'holds', 'source_warnings', 'error', 'publication_error')}, ensure_ascii=False, indent=2)
        print(summary, flush=True)
        if code == 0 and report.get('valid') and not report.get('promotable'):
            print('::notice title=Catalog publication held::Valid candidate and evidence saved; current catalog retained pending removal confirmation.', flush=True)
        if os.environ.get('GITHUB_STEP_SUMMARY'):
            with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
                stream.write('## Daily catalog\n\n```\n' + summary + '\n```\n')
    return code


if __name__ == '__main__':
    raise SystemExit(main())
