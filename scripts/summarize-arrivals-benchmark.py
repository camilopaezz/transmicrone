#!/usr/bin/env python3
"""Summarize whole-board latency without counting failures as faster successes."""
import argparse
from collections import Counter
import json
from pathlib import Path
from statistics import median


def calls(run):
    return run.get('calls', [])


def complete(run):
    return run['result'] in ('success', 'empty') and all(
        c['http_status'] == 200 and not c['transport_failure'] for c in calls(run))


def summarize(paths):
    records = [dict(json.loads(line), experiment=path.stem) for path in paths
               for line in path.read_text().splitlines() if line.strip()]
    for run in records:
        if 'calls' in run and len(run['calls']) != run['request_count']:
            raise ValueError('Incomplete call trace; cannot summarize this experiment')
    measured = [r for r in records if not r['warmup']]
    summaries = []
    for count in sorted({r['request_count'] for r in measured}):
        for mode in ('capped', 'uncapped'):
            runs = [r for r in measured if r['request_count'] == count and r['mode'] == mode]
            if not runs:
                continue
            completed = [r for r in runs if complete(r)]
            network = [c['call_ms'] for r in runs for c in calls(r)]
            start_delays = [max(c['start_offset_ms'] for c in calls(r)) for r in runs if calls(r)]
            peaks = [r['peak_calls'] for r in runs if 'peak_calls' in r]
            summaries.append(dict(requests=count, mode=mode, runs=len(runs),
                outcomes=dict(Counter(r['result'] for r in runs)),
                complete_runs=len(completed), runs_with_call_details=sum(bool(calls(r)) for r in runs),
                transport_failures=sum(c['transport_failure'] for r in runs for c in calls(r)) if network else None,
                complete_median_ms=median(r['board_ms'] for r in completed) if completed else None,
                complete_range_ms=[min(r['board_ms'] for r in completed), max(r['board_ms'] for r in completed)] if completed else None,
                all_outcomes_median_ms=median(r['board_ms'] for r in runs),
                last_request_start_median_ms=median(start_delays) if start_delays else None,
                call_median_ms=median(network) if network else None, peak_calls=max(peaks) if peaks else None,
                http_statuses=dict(Counter(str(c['http_status']) for r in runs for c in calls(r)))))
    pairs = []
    for count in sorted({r['request_count'] for r in measured}):
        for experiment, round_id in sorted({(r['experiment'], r['round']) for r in measured}):
            by_mode = {r['mode']: r for r in measured if r['round'] == round_id and
                       r['experiment'] == experiment and r['request_count'] == count}
            if set(by_mode) != {'capped', 'uncapped'}:
                continue
            a, b = by_mode['capped'], by_mode['uncapped']
            comparable = complete(a) and complete(b)
            pairs.append(dict(experiment=experiment, requests=count, round=round_id,
                capped_ms=a['board_ms'], uncapped_ms=b['board_ms'],
                capped_result=a['result'], uncapped_result=b['result'], both_complete=comparable,
                same_outcome=a['result'] == b['result'],
                reduction_percent=100 * (1 - b['board_ms'] / a['board_ms']) if comparable else None))
    return dict(raw_files=[p.name for p in paths], measured_runs=len(measured),
                warmup_runs=len(records)-len(measured),
                request_count=sum(r['request_count'] for r in records),
                summaries=summaries, pairs=pairs)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', nargs='+', type=Path)
    args = parser.parse_args()
    print(json.dumps(summarize(args.input), indent=2))
