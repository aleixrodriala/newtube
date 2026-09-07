# Isolated SABR delivery proof — 2026-09-07

**Result: playback not proved.** Existing-session TV metadata was accepted, but
the first raw-URL SABR media POST returned HTTP 403. The probe stopped immediately.

## Scope and interpretation

User approved a small proof before a production Media3 SABR port. This adds only
test sources and test-only protobuf generation/dependencies. It does not enable
SABR in the production player or change its session/recovery policies.

The instrumentation probe uses NewTube's selected account on the Pixel 9
(`4A120DLAQ0049N`) and the single authenticated TV metadata entry point.
It requires raw `OK`, server-confirmed authentication, matching video identity,
ordinary VOD (excluding current and completed live content), and unambiguous
AAC/AVC selections from the same response.

It uses the **untouched server-issued SABR URL**, apart from request numbering.
This deliberately does not exercise the normal URL parameter-processing path;
negative results cannot establish that a complete SABR implementation would fail
or explain an arbitrary HTTP 403.

At most two media POSTs are possible: audio initialization first, then video only
after a clean response with completed matching audio media. Each response has a
20-second whole-call timeout and 16 MiB read bound. Redirects, transport retries,
alternate sessions, protection challenges and response-reload recovery are not
implemented. HTTP/protocol denial stops the probe. Media requests use the app's
existing media transport without API authorization/cookies. Credentials, endpoint
queries, config and media bytes are never exported or included in test metrics.

The UMP inspector reuses the retained `tv-legacy` schemas and a small adapted
parser/request builder; provenance and license are beside the test helper. It
checks video/format identity, completed media records, lengths and clean EOF.
Accepted metadata, HTTP 200 and container initialization alone are not success.

After complete delivery, Android MediaExtractor/MediaCodec can decode retained
bytes entirely in memory, bounded to eight seconds and 30 output buffers per
track. This verifies decoded buffers, **not** rendered first frames, audible
audio, sustained playback, seeking, switching or TTFF. A separate local MP4
fixture verifies this decoder helper without claiming YouTube/SABR success.

## Evidence

Local artifacts: `/tmp/newtube-sabr-proof-20260907-tsuoW9`.

- Final full app unit-test run: **218 tests passed**, including 13 new metadata
  eligibility cases and 16 new protocol tests. Instrumentation APK builds passed.
- Pixel decoder-helper instrumentation: 2 tests passed. Local fixture video:
  34 input samples, 30 decoded outputs, first output 377 ms; audio: 34 samples,
  30 outputs, first output 165 ms. These timings are **not network/playback TTFF**.
- Main installed APK SHA-256 remained
  `5eb8e3dcbbb25c0f975311ed7f6ca5bd65fbaedb22ca13f4e5f374401b07cf41`.
- The Pixel was initially showing a call; device execution was deferred until
  the user confirmed it was free. Only NewTube was force-stopped to clear its
  existing PiP/background playback before instrumentation. Other apps were not
  controlled.

Live probe for `Fo89b8zAIE4` (`pixel-delivery-initialized.log`):

| Gate | Observation |
| --- | --- |
| Existing-account TV metadata | raw `OK`, server-authenticated, no bot-check flag; 1044 ms |
| Selected formats | unique non-DRC AAC 140 and AVC 136 / 720p |
| First audio SABR POST | HTTP **403**, headers after **333 ms** |
| UMP/media/decode | Not reached; response body not inspected as media |
| Video request, retries, alternate routes | None |

This does not establish the HTTP denial's cause. A raw-URL negative is not a
complete-path SABR incompatibility verdict. No real-video TTFF, render, switch,
seek or soak acceptance has been achieved by this proof, and a production port
is not justified as a demonstrated fix yet.

Two earlier instrumentation invocations stopped before video metadata because
the headless harness skipped `SplashPresenter`'s normal preferences setup.
Waiting alone did not resolve it. The harness now initializes GlobalPreferences
with the target app context and waits up to five seconds for its existing
asynchronous account restore. The third invocation above confirmed the existing
account was present and accepted. No account creation, selection, reset, data
export or session rotation was needed.

Another scope limitation: the legacy engine has distinct initialization and
subsequent media requests. Clean initialization-only responses are inconclusive,
not denial; this probe does not implement their continuation. The actual live
capture stopped earlier, at HTTP status, so this distinction did not affect it.

Only the instrumentation companion was installed; the existing main APK and
account data were retained. Cleanup and opt-out verification are recorded below.

- A device invocation without `allow_network_proof=true` skipped the live test
  before setup/requests (`pixel-opt-out.log`, assumption status `-4`).
- Temporary `io.github.aleixrodriala.arc.test` was uninstalled after testing;
  its rebuildable local APK/source remain. The main package is still installed,
  its checksum is unchanged, and no PiP task remains. NewTube was left stopped
  so this failed probe cannot leave background playback/recovery running.
- Root and MediaServiceCore whitespace checks passed. No commits or pushes.
