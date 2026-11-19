#!/usr/bin/env python3
"""Check public catalog freshness independently of the expensive collection job."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import urllib.request
from datetime import datetime, timezone


def utc_now():
    return datetime.now(timezone.utc)


def verify_catalog(artifact: dict) -> None:
    request = urllib.request.Request(artifact['url'], headers={'User-Agent': 'transminuto-catalog/1.0'})
    with urllib.request.urlopen(request, timeout=60) as response:
        raw = response.read()
    if len(raw) != artifact['bytes'] or hashlib.sha256(raw).hexdigest() != artifact['sha256']:
        raise ValueError('Published catalog size or checksum mismatch')


def publication_health(manifest: dict | None, now: datetime, max_age_hours: int = 48) -> dict:
    if not manifest:
        return {'status': 'missing', 'error': 'No published catalog manifest'}
    result = {'version': manifest.get('version'), 'max_age_hours': max_age_hours}
    try:
        for field, label in (('generated_at', 'catalog_age_hours'), ('checked_at', 'verified_age_hours')):
            stamp = datetime.fromisoformat(manifest[field])
            if stamp.tzinfo is None or stamp > now:
                raise ValueError(f'{field} must be timezone-aware and not future-dated')
            result[label] = round((now - stamp).total_seconds() / 3600, 2)
        result['status'] = 'stale' if (now - datetime.fromisoformat(manifest['checked_at'])).total_seconds() > max_age_hours * 3600 else 'fresh'
    except (KeyError, TypeError, ValueError) as error:
        result.update(status='invalid', error=str(error))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='https://transminuto-catalog.cpzhmlb.uk/catalog')
    parser.add_argument('--max-age-hours', type=int, default=48)
    args = parser.parse_args()
    if args.max_age_hours <= 0:
        parser.error('--max-age-hours must be positive')
    report = {}
    try:
        def read(name):
            request = urllib.request.Request(f'{args.base_url.rstrip("/")}/{name}',
                                             headers={'User-Agent': 'transminuto-catalog/1.0'})
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.load(response)

        current = read('current.json')
        report = {'publication_health': publication_health(current, utc_now(), args.max_age_hours)}
        verify_catalog(current['catalog'])
        report['delivery'] = {'status': 'verified'}
    except Exception as error:
        if not report:
            report = {'publication_health': {'status': 'unavailable', 'error': str(error)}}
        report['delivery'] = {'status': 'unavailable', 'error': str(error)}
    try:
        observation = read('latest-observation.json')
        report['latest_collection'] = {key: observation.get(key) for key in
                                      ('started_at', 'valid', 'publication', 'run_url')}
    except Exception as error:
        report['latest_collection'] = {'status': 'unavailable', 'error': str(error)}
    summary = json.dumps(report, ensure_ascii=False, indent=2)
    print(summary)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
            stream.write('## Catalog health\n\n```json\n' + summary + '\n```\n')
    if report['publication_health']['status'] != 'fresh' or report.get('delivery', {}).get('status') != 'verified':
        print('::error title=Catalog freshness::Published catalog freshness or download verification failed. See the health summary.')
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
