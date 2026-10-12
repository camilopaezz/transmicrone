# Title-side tap performance

Verified on the physical Xiaomi 21081111RG, Android 14, on 2026-10-11.
No emulator was used. The device build used the configured live feed,
`MOCK_FEED = false`, and a non-debuggable build. Saved boards and settings
were retained during updates.

The 60 fps Device panel mirror contributes to actual application jank on this
phone. Turning off its scrcpy stream improved taps in the same running process.
Reopening the stream brought deadline misses back without rebuilding or
restarting the app. A panel capped at 60 fps also cannot show a 120 fps animation.

Initial captures measured a median presentation interval of 16.58 ms for title
side taps, compared with 8.31 ms for swipes. In the first 280 ms of each tap,
91 of 144 frames missed their deadline. The median UI work was only 2.35 ms;
the median delay before that work started was 8.90 ms.

A Perfetto capture showed render-thread buffer waits. `dequeueBuffer` averaged
7.76 ms, and the phone's `target_fps` counter fell from 120 to roughly 64 during
short tap animations. These observations support buffer backpressure and device
frame pacing as the cause in this mirroring setup.

With the original title implementation restored and the mirror stopped, 30 taps
across three consecutive batches measured a median presentation interval of
8.29 ms. Of 707 frames in the 280 ms capture windows, 30 missed their deadline,
4.2%. Reopening the 60 fps mirror in the same process raised deadline misses to
32.6% in the 280 ms capture windows. These are sample measurements, not
a guarantee that every frame meets its deadline.

Removing the side-button ripple and requesting the fastest window refresh rate
or display mode did not eliminate jank with mirroring active. Those experimental
application changes were removed. The app should not hold the display at its
highest refresh rate to compensate for measurement overhead.

## Repeating the check

Read [feed.md](feed.md) and [arrivals.md](arrivals.md) before installing a live
build or checking arrivals. Use the physical Xiaomi and the `device` build.
Stop continuous mirroring and screen recording before measuring native phone
performance. Reinstalling or relaunching alone can temporarily improve results;
repeat batches after the launch boost has expired.

For each interaction, reset graphics stats immediately before tapping a title
side, wait for the slide to finish, and save the frame stats. Specify the
connected phone's serial with `adb -s` when other devices are connected.

```sh
adb shell dumpsys gfxinfo co.camilo.transmicrone.debug reset
# Tap the previous or next title region on the Xiaomi, then wait for settling.
adb shell dumpsys gfxinfo co.camilo.transmicrone.debug framestats > /tmp/title-tap.txt
python scripts/android-frame-stats.py /tmp/title-tap.txt
```

The checker examines the first 280 ms, excluding flagged nonstandard frames.
It reports median vsync and presentation intervals, scheduling delay, actual UI
work, and missed frame deadlines. Its default pass criterion is a median interval
within 25% of 120 Hz and no more than 15% missed deadlines. Pass multiple capture
files to aggregate a batch. Check taps and swipes separately, and compare stream
on and off without restarting the app. Do not infer performance from the panel's
video frame rate or the screen's refresh-rate overlay alone.
