#!/usr/bin/env python3
"""Check a short Android interaction from `adb shell dumpsys gfxinfo PACKAGE framestats`."""

import argparse
import csv
import io
import statistics
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("captures", nargs="+", type=Path)
    parser.add_argument("--hz", type=float, default=120)
    parser.add_argument("--duration-ms", type=float, default=280)
    parser.add_argument("--max-missed-percent", type=float, default=15)
    args = parser.parse_args()
    frames, intervals, presentation = [], [], []
    for path in args.captures:
        blocks = path.read_text().split("---PROFILEDATA---")
        if len(blocks) < 3:
            parser.error(f"{path}: no frame stats; use the framestats option")
        sample = [row for row in csv.DictReader(io.StringIO(blocks[1].strip()))
                  if row["Flags"] == "0" and 0 < int(row["FrameCompleted"]) < 10**18]
        if not sample:
            parser.error(f"{path}: no valid frames")
        start = int(sample[0]["IntendedVsync"])
        sample = [row for row in sample
                  if int(row["IntendedVsync"]) - start < args.duration_ms * 1e6]
        frames.extend(sample)
        times = [int(row["IntendedVsync"]) for row in sample]
        intervals.extend((b - a) / 1e6 for a, b in zip(times, times[1:]) if b > a)
        presented = sorted({int(row["DisplayPresentTime"]) for row in sample
                            if 0 < int(row["DisplayPresentTime"]) < 10**18})
        presentation.extend((b - a) / 1e6 for a, b in zip(presented, presented[1:]))
    if not intervals or not presentation:
        parser.error("Too few frames or missing presentation timestamps")
    interval = statistics.median(intervals)
    present_interval = statistics.median(presentation)
    missed = sum(int(row["FrameCompleted"]) > int(row["FrameDeadline"]) for row in frames)
    missed_percent = 100 * missed / len(frames)
    scheduling = statistics.median((int(row["HandleInputStart"]) - int(row["IntendedVsync"])) / 1e6
                                   for row in frames)
    work = statistics.median((int(row["SyncQueued"]) - int(row["HandleInputStart"])) / 1e6
                            for row in frames)
    print(f"{len(frames)} frames; median vsync {interval:.2f} ms; "
          f"median presentation {present_interval:.2f} ms; "
          f"scheduling delay {scheduling:.2f} ms; UI work {work:.2f} ms; "
          f"missed deadlines {missed}/{len(frames)} ({missed_percent:.1f}%)")
    passed = max(interval, present_interval) <= 1000 / args.hz * 1.25 and missed_percent <= args.max_missed_percent
    print("PASS" if passed else "FAIL")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
