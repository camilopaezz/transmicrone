import hashlib
import json
import sys
from pathlib import Path
from urllib.parse import urlparse, parse_qs

root = Path(sys.argv[1])
plan = json.loads((root / 'plan.json').read_text())
samples = sorted(root.glob('sample-*.json'))
assert len(samples) == len(plan['targets']) == 5
output = {'run': 'https://github.com/camilopaezz/transmicrone/actions/runs/39133571125',
          'plan': plan, 'samples': [], 'comparisons': [], 'verified_bodies': 0}
baseline = {}
baseline_parsed = {}

def canonical(data, url):
    query = parse_qs(urlparse(url).query)
    if '/FeatureServer/' in url:
        data = dict(data)
        data['features'] = sorted(data.get('features', []), key=lambda x: json.dumps(x, sort_keys=True))
    elif query.get('lFuncion') == ['searchRutaByTipo']:
        data = dict(data)
        data['lista_rutas'] = sorted(data['lista_rutas'], key=lambda x: json.dumps(x, sort_keys=True))
    return data

def differences(left, right, path=''):
    if type(left) != type(right):
        return [{'path': path, 'before': left, 'after': right}]
    if isinstance(left, dict):
        changes = []
        for key in sorted(left.keys() | right.keys()):
            if key not in left or key not in right:
                changes.append({'path': path + '/' + key, 'before': left.get(key), 'after': right.get(key)})
            else:
                changes.extend(differences(left[key], right[key], path + '/' + key))
        return changes
    if isinstance(left, list):
        if len(left) != len(right):
            return [{'path': path, 'before_length': len(left), 'after_length': len(right)}]
        return [change for i, (a, b) in enumerate(zip(left, right)) for change in differences(a, b, f'{path}/{i}')]
    return [] if left == right else [{'path': path, 'before': left, 'after': right}]

for sample in samples:
    report = json.loads(sample.read_text())
    assert report['ok'] and len(report['results']) == 8
    directory = root / report['responses']
    metadata_files = sorted(directory.glob('*.json'))
    assert len(metadata_files) == 11
    urls = set()
    changes = []
    statuses = []
    for metadata_file in metadata_files:
        meta = json.loads(metadata_file.read_text())
        assert 'error' not in meta and meta['status'] == 200
        statuses.append(meta['status'])
        body = directory / meta['body']
        raw = body.read_bytes()
        digest = hashlib.sha256(raw).hexdigest()
        assert digest == meta['sha256'] and len(raw) == meta['bytes']
        output['verified_bodies'] += 1
        url = meta['url']
        assert url not in urls
        urls.add(url)
        is_gtfs = url.endswith('.zip')
        parsed = None if is_gtfs else canonical(json.loads(raw), url)
        if sample == samples[0]:
            baseline[url] = digest
            baseline_parsed[url] = parsed
        else:
            assert url in baseline
            equal = digest == baseline[url]
            logical_equal = equal if is_gtfs else parsed == baseline_parsed[url]
            change = {'sample': sample.stem, 'url': url, 'byte_identical': equal, 'parsed_identical': logical_equal}
            if not logical_equal and not is_gtfs:
                change['differences'] = differences(baseline_parsed[url], parsed)
            if not equal:
                changes.append(change)
            output['comparisons'].append(change)
    assert urls == set(baseline)
    output['samples'].append({'sample': sample.stem, 'checked_at': report['checked_at'],
                              'ok': report['ok'], 'verified_response_bodies': len(metadata_files),
                              'changes_from_first': changes,
                              'checks': report['results']})
output['all_response_bodies_identical'] = all(x['byte_identical'] for x in output['comparisons'])
output['all_parsed_data_identical'] = all(x['parsed_identical'] for x in output['comparisons'])
out = Path(sys.argv[2])
out.write_text(json.dumps(output, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({k: v for k, v in output.items() if k not in ('samples', 'comparisons')}, indent=2))
for sample in output['samples']:
    print(sample['sample'], sample['checked_at'], sample['changes_from_first'])
