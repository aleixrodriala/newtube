# Debug-only SABR delivery proof

The schemas under `src/androidTest/proto/sabr` are copied unchanged (apart from
line endings) from this repository's `tv-legacy` tag, commit
`ccb01fc4ad6e573eec7c23bb0b590fa51947727d`, subtree
`exoplayer-amzn-2.10.6/library/sabr/src/main/proto/sabr`.
The preserved upstream ExoPlayer license is `UPSTREAM-LICENSE` beside this file.

The four `ump` Java classes adapt the same tag's
`library/sabr/src/main/java/com/google/android/exoplayer2/source/sabr/parser/ump`.
Changes: a plain Java bounded InputStream replaces legacy ExtractorInput;
truncated integers/payloads and excessive part sizes fail closed; only relevant
part identifiers are retained. No legacy player, decoder, recovery or transport
is included.

`SabrProofProtocol.buildInitialRequest` adapts the initial request fields from
`manifest/SabrManifest.java:createVideoPlaybackAbrRequest`. It uses the caller's
existing response config, client metadata, selected format IDs, bitrate and height.
It reports an initial position and empty actual buffers; it does not fabricate
buffered ranges, generate tokens, alter sessions or perform requests.

The inspector retains only bounded, selected-format media in process for optional
local decoding. Redirect, reload, error, unknown control and non-OK protection
statuses terminate inspection. It has no retry or follow-up request path.
