#!/usr/bin/env python3
"""Compare real before/after feed clients, interleaved, without printing credentials."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
SOURCE = 'android/app/src/main/kotlin/co/camilo/transmicrone/data/feed/FeedClient.kt'
# The exact pre-change production implementation, not a reimplementation of its scheduler.
DEFAULT_BASELINE = '4d1f20442f4c38c0745ab7e7195f8372041150db'


def publish(raw, output, rounds):
    records = [json.loads(line) for line in raw.splitlines() if line.strip()]
    expected = {(r, mode, count) for r in range(rounds + 1)
                for mode in ('capped', 'uncapped') for count in (4, 8)}
    actual = {(r['round'], r['mode'], r['request_count']) for r in records}
    if len(records) != len(expected) or actual != expected:
        raise RuntimeError('Incomplete or duplicate benchmark observations')
    if any(len(r.get('calls', [])) != r['request_count'] or
           r['warmup'] != (r['round'] == 0) for r in records):
        raise RuntimeError('Incomplete request traces or invalid warm-up labels')
    temporary = output.with_suffix('.collecting')
    temporary.write_text(raw)
    temporary.replace(output)


def collect_device(adb, command, env, output, rounds):
    subprocess.run(adb + ['shell', 'run-as', 'co.camilo.transmicrone.debug',
                         'rm', '-f', 'cache/arrivals-benchmark.jsonl'], check=True)
    result = subprocess.run(command, cwd=ROOT / 'android', env=env, check=True,
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    print(result.stdout, flush=True)
    if 'OK (1 test)' not in result.stdout:
        raise RuntimeError('Android instrumentation did not report a passing benchmark')
    raw = subprocess.check_output(adb + ['exec-out', 'run-as', 'co.camilo.transmicrone.debug',
                    'cat', 'cache/arrivals-benchmark.jsonl'], text=True)
    publish(raw, output, rounds)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', default=DEFAULT_BASELINE)
    parser.add_argument('--rounds', type=int, default=6)
    parser.add_argument('--interval-ms', type=int, default=25000)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--device', help='ADB serial for an Android-runtime benchmark')
    args = parser.parse_args()
    if args.rounds < 0 or args.interval_ms < 0:
        parser.error('Rounds and interval must be nonnegative')
    source = subprocess.check_output(['git', 'show', f'{args.baseline}:{SOURCE}'], cwd=ROOT, text=True)
    if 'val semaphore = Semaphore(2)' not in source:
        parser.error('Baseline must contain the original two-request semaphore')
    source = source.replace(source[source.index('interface FeedClient {'):source.index('class BodegaFeedClient(')], '')
    source = source.replace('class BodegaFeedClient(', 'class BaselineBodegaFeedClient(')
    target = ROOT / 'android/app/src/test/kotlin/co/camilo/transmicrone/data/feed/BaselineBodegaFeedClient.kt'
    if args.device:
        target = Path(str(target).replace('/src/test/', '/src/androidTest/'))
        target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        parser.error(f'Refusing to overwrite {target}')
    target.parent.mkdir(parents=True, exist_ok=True)
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    env = {k: v for k, v in os.environ.items() if not k.startswith('ARRIVALS_BENCHMARK')}
    print(f'Baseline: {args.baseline}; raw observations: {output}', flush=True)
    generated_benchmark = None
    try:
        target.write_text(source)
        if args.device:
            benchmark = ROOT / 'android/app/src/test/kotlin/co/camilo/transmicrone/benchmark/LiveArrivalsBenchmark.kt'
            candidate = Path(str(benchmark).replace('/src/test/', '/src/androidTest/'))
            if candidate.exists():
                raise RuntimeError(f'Refusing to overwrite {candidate}')
            generated_benchmark = candidate
            generated_benchmark.parent.mkdir(parents=True, exist_ok=True)
            content = benchmark.read_text().replace('System.getenv(', 'androidx.test.platform.app.InstrumentationRegistry.getArguments().getString(')
            content = content.replace('val output = File(checkNotNull(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("ARRIVALS_BENCHMARK_OUTPUT")))',
                'val output = File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "arrivals-benchmark.jsonl")')
            content = content.replace('val uuid = checkNotNull(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("ARRIVALS_BENCHMARK_UUID"))',
                'val uuid = co.camilo.transmicrone.data.local.InstallationId.getOrCreate(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext)')
            content = content.replace('java.util.zip.GZIPInputStream(\n            File("src/main/assets/catalog.json.gz").inputStream())',
                'androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.assets.open("catalog.json")')
            generated_benchmark.write_text(content)
            env['ANDROID_SERIAL'] = args.device
            # connectedDebugAndroidTest uninstalls the app, including the raw file.
            # Install updates in place and invoke instrumentation directly instead.
            subprocess.run(['./gradlew', ':app:assembleDebug', ':app:assembleDebugAndroidTest',
                            '-PmockFeed=false', '--console=plain', '--no-daemon'], cwd=ROOT / 'android', env=env, check=True)
            config = (ROOT / 'android/app/build/generated/source/buildConfig/debug/co/camilo/transmicrone/BuildConfig.java').read_text()
            app_id = re.search(r'FEED_APPID = "([^"]*)"', config)
            if 'MOCK_FEED = false;' not in config or not app_id or app_id[1] in ('', 'unconfigured'):
                raise RuntimeError('Expected a configured live-data build')
            adb = ['adb', '-s', args.device]
            for apk in ('debug/app-debug.apk', 'androidTest/debug/app-debug-androidTest.apk'):
                subprocess.run(adb + ['install', '-r', str(ROOT / 'android/app/build/outputs/apk' / apk)], check=True)
            command = adb + ['shell', 'am', 'instrument', '-w', '-r',
                '-e', 'class', 'co.camilo.transmicrone.benchmark.LiveArrivalsBenchmark',
                '-e', 'ARRIVALS_BENCHMARK', '1',
                '-e', 'ARRIVALS_BENCHMARK_ROUNDS', str(args.rounds),
                '-e', 'ARRIVALS_BENCHMARK_INTERVAL_MS', str(args.interval_ms),
                'co.camilo.transmicrone.debug.test/androidx.test.runner.AndroidJUnitRunner']
            collect_device(adb, command, env, output, args.rounds)
            return
        from feed_config import INSTALL_UUID
        with tempfile.TemporaryDirectory(prefix='arrivals-benchmark-') as directory:
            staged = Path(directory) / 'observations.jsonl'
            env.update(ARRIVALS_BENCHMARK='1', ARRIVALS_BENCHMARK_UUID=INSTALL_UUID,
                       ARRIVALS_BENCHMARK_OUTPUT=str(staged), ARRIVALS_BENCHMARK_ROUNDS=str(args.rounds),
                       ARRIVALS_BENCHMARK_INTERVAL_MS=str(args.interval_ms))
            subprocess.run(['./gradlew', ':app:testDebugUnitTest', '--tests',
                            'co.camilo.transmicrone.benchmark.LiveArrivalsBenchmark',
                            '-PmockFeed=false', '--rerun-tasks', '--console=plain', '--no-daemon'],
                           cwd=ROOT / 'android', env=env, check=True)
            # A skipped test cannot reuse an earlier experiment's output.
            publish(staged.read_text(), output, args.rounds)
    finally:
        target.unlink(missing_ok=True)
        if generated_benchmark is not None:
            generated_benchmark.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
