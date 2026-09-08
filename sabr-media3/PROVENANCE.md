# Optional Media3 SABR VOD source

This is an experimental production library, not the older debug delivery proof.
NewTube's preference defaults off. It uses stock Media3 1.10.1; it does not restore
the vendored ExoPlayer fork or introduce a different decoder/client.

## Retained protocol definitions

The schemas in `src/main/proto/sabr` derive from this repository's `tv-legacy`
tag, commit `ccb01fc4ad6e573eec7c23bb0b590fa51947727d`, subtree
`exoplayer-amzn-2.10.6/library/sabr/src/main/proto/sabr`. Their Java package is
changed to `com.newtube.sabr.proto.*`; wire fields are preserved. The two context
policy schemas were checked against upstream and the same retained tag. Generated
Java is a build artifact, using pinned protoc/protobuf-javalite 3.22.3.

The four `ump` Java classes adapt that tag's
`library/sabr/src/main/java/com/google/android/exoplayer2/source/sabr/parser/ump`.
They use bounded ordinary InputStreams instead of the legacy ExtractorInput;
truncated integers/parts, excessive controls and unknown framing fail closed.
The upstream ExoPlayer Apache 2.0 license is preserved as `UPSTREAM-LICENSE` and
included in the APK's `assets/newtube-sabr-legal/` directory.

`SabrProtocol` develops the initial request field mapping from the retained
`manifest/SabrManifest.java:createVideoPlaybackAbrRequest` into an independent,
bounded per-track/per-seek state machine. It sends actual selected identities,
playhead, measured bandwidth and completed buffer ranges. Opaque request policy
state is process-local. No account/token acquisition, client selection, challenge
recovery, server redirect following or alternate-source recovery is included.

`SabrMediaSource` is a new Media3 implementation using its Loader, SampleQueue,
BundledChunkExtractor and unmodified MP4/WebM extractors. The production transport
is the app's existing media OkHttp pool, with retries/redirects disabled, a bounded
call timeout and no byte-cache reuse for stateful POST responses.

## Small version-pinned Media3 bridge

`androidx.media3.exoplayer.source.SabrSubtitleSourceFactory` adapts the subtitle
branch of AndroidX Media3's Apache-2.0-licensed `DefaultMediaSourceFactory` at
**1.10.1**. It is in Media3's package solely to access the package-private
`ProgressiveMediaSource.Factory.enableLazyLoadingWithSingleTrack` method. It
does not replace an AndroidX class. Caption loading uses the stock subtitle
extractor/parser and is lazy until selected. Recompile and run the real-device
caption test on every Media3 upgrade.

Primary API/source references:

- https://developer.android.com/media/media3/exoplayer/media-sources
- https://github.com/androidx/media/blob/1.10.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/DefaultMediaSourceFactory.java

## Tests

`src/testSupport` is included only in unit/instrumentation test source sets.
The fixture MP4/WebM files are synthetic FFmpeg output, reproducible with
`tools/generate-sabr-fixtures.sh`; no downloaded media or user data is included.
Network instrumentation requires explicit opt-in, uses normal accepted app
metadata, stops on failure, and never treats failed delivery as a timing result.
