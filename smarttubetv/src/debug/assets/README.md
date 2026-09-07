# Local decoder/startup test fixture

`ttff-fixture.mp4` is a generated test pattern and sine tone, not external media.
It is packaged only in debug builds for `TtffFixtureActivity` instrumentation.
It tests the real decoder, load-control readiness, and sustained progress without
requiring a third-party playback response. It does not measure YouTube/API TTFF.

Reproduce with FFmpeg (write to a new path or remove only the existing generated
fixture first; FFmpeg intentionally asks before replacing a file):

```sh
ffmpeg -hide_banner -loglevel error \
  -f lavfi -i testsrc2=size=640x360:rate=30 \
  -f lavfi -i sine=frequency=440:sample_rate=48000 -t 54 \
  -c:v libx264 -preset veryfast -b:v 900k -maxrate 900k -bufsize 1800k \
  -g 30 -pix_fmt yuv420p -c:a aac -b:a 48k -movflags +faststart \
  smarttubetv/src/debug/assets/ttff-fixture.mp4
```
