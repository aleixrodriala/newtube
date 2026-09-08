package com.newtube.sabr;

import androidx.annotation.Nullable;
import androidx.media3.common.C;

import com.google.protobuf.ByteString;
import com.newtube.sabr.proto.misc.FormatId;
import com.newtube.sabr.proto.videostreaming.BufferedRange;
import com.newtube.sabr.proto.videostreaming.ClientAbrState;
import com.newtube.sabr.proto.videostreaming.FormatInitializationMetadata;
import com.newtube.sabr.proto.videostreaming.MediaHeader;
import com.newtube.sabr.proto.videostreaming.NextRequestPolicy;
import com.newtube.sabr.proto.videostreaming.SabrContextSendingPolicy;
import com.newtube.sabr.proto.videostreaming.SabrContextUpdate;
import com.newtube.sabr.proto.videostreaming.StreamProtectionStatus;
import com.newtube.sabr.proto.videostreaming.StreamerContext;
import com.newtube.sabr.proto.videostreaming.TimeRange;
import com.newtube.sabr.proto.videostreaming.VideoPlaybackAbrRequest;
import com.newtube.sabr.ump.UMPDecoder;
import com.newtube.sabr.ump.UMPPart;
import com.newtube.sabr.ump.UMPPartId;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stateful VOD framing only. No networking, client selection, token acquisition or retries. */
final class SabrProtocol {
    static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
    static final int MAX_CONTROL_BYTES = 64 * 1024;
    private SabrProtocol() {}

    /** Private to one sample-stream/seek generation; a loader commits its copy only at clean EOF. */
    static final class State {
        boolean declared;
        boolean initialized;
        boolean bound;
        long endUs;
        long knownDurationUs;
        int backoffMs;
        ByteString cookie = ByteString.EMPTY;
        final List<BufferedRange> buffered = new ArrayList<>();
        final Map<Integer, SabrContextUpdate> contexts = new HashMap<>();
        final Set<Integer> sendContexts = new HashSet<>();

        State(long positionUs, long durationUs) { endUs = positionUs; knownDurationUs = durationUs; }

        State copy() {
            State result = new State(endUs, knownDurationUs);
            result.declared = declared;
            result.initialized = initialized;
            result.bound = bound;
            result.backoffMs = backoffMs;
            result.cookie = cookie;
            result.buffered.addAll(buffered);
            result.contexts.putAll(contexts);
            result.sendContexts.addAll(sendContexts);
            return result;
        }
    }

    /**
     * Server-side track-type selector. Measured against real YouTube on 2026-09-08, sweeping the
     * field with one video preferred format id: {@code 1} returns audio ALONE, and every other
     * value tried ({@code 0}, {@code 2}, {@code 3}) returns audio AND video. There is no
     * "video only" encoding, so a video request must expect a companion audio track and suppress
     * it by other means. The previous {@code 2} was read as a bitfield and is why a video stream
     * met an unrequested audio format on its first real response.
     */
    private static final int TRACK_TYPES_AUDIO_ONLY = 1;
    private static final int TRACK_TYPES_AUDIO_AND_VIDEO = 0;

    /**
     * @param companionAudio the audio track this period is loading on its own stream, or null. A
     *     video request cannot ask for video alone, so the companion is named as the preferred
     *     audio and declared fully buffered; otherwise the server picks its own default audio and
     *     ships it alongside every video response.
     */
    static byte[] request(SabrStreamInfo info, SabrStreamInfo.Track track,
            @Nullable SabrStreamInfo.Track companionAudio, State state,
            long playbackPositionUs, long bandwidth, float speed) {
        boolean isVideo = track.type == C.TRACK_TYPE_VIDEO;
        ClientAbrState.Builder abr = ClientAbrState.newBuilder()
                .setPlayerTimeMs(Math.max(0, playbackPositionUs) / 1000)
                .setPlaybackRate(Float.isFinite(speed) && speed > 0 ? speed : 1)
                .setBandwidthEstimate(Math.max(0, bandwidth))
                .setEnabledTrackTypesBitfield(isVideo
                        ? TRACK_TYPES_AUDIO_AND_VIDEO : TRACK_TYPES_AUDIO_ONLY)
                .setDrcEnabled(track.drc);
        // Media3 owns the chosen quality. Do not pretend to enable server-side ABR by supplying
        // invented bandwidth or fake buffered ranges for tracks that were never downloaded.
        if (track.type == C.TRACK_TYPE_VIDEO && track.format.height > 0) {
            abr.setStickyResolution(track.format.height).setClientViewportIsFlexible(false);
        }
        if (track.audioTrackId != null && !track.audioTrackId.isEmpty()) {
            abr.setAudioTrackId(track.audioTrackId);
        }
        StreamerContext.Builder context = StreamerContext.newBuilder().setClientInfo(info.client);
        if (!state.cookie.isEmpty()) context.setPlaybackCookie(state.cookie);
        for (int type : state.sendContexts) {
            SabrContextUpdate value = state.contexts.get(type);
            if (value != null) {
                context.addSabrContexts(StreamerContext.SabrContext.newBuilder()
                        .setType(type).setValue(value.getValue()));
            } else context.addUnsentSabrContexts(type);
        }
        VideoPlaybackAbrRequest.Builder request = VideoPlaybackAbrRequest.newBuilder()
                .setVideoPlaybackUstreamerConfig(info.config).setClientAbrState(abr)
                .setStreamerContext(context);
        if (!isVideo) request.addPreferredAudioFormatIds(track.identity);
        else {
            request.addPreferredVideoFormatIds(track.identity);
            if (companionAudio != null) suppressCompanionAudio(request, info, companionAudio, state);
        }
        if (state.declared) request.addSelectedFormatIds(track.identity);
        // Keep only actual completed ranges still in the player's retained buffer.
        for (BufferedRange range : state.buffered) {
            if (range.getStartTimeMs() + range.getDurationMs() > playbackPositionUs / 1000) {
                request.addBufferedRanges(range);
            }
        }
        return request.build().toByteArray();
    }

    /**
     * Names the period's own audio as preferred and claims the whole of it, so the server has
     * nothing to add to a video response. Measured on one video, 2026-09-08: without this a video
     * request carries 188,265 bytes of the server's default Opus track; with it the companion
     * costs a single 2,180-byte initialization segment on the first request, and once the format
     * is declared, nothing at all. The claim is scoped to THIS request - the audio stream sends
     * its own true buffered ranges - and it only suppresses delivery, never selects a quality.
     */
    private static void suppressCompanionAudio(VideoPlaybackAbrRequest.Builder request,
            SabrStreamInfo info, SabrStreamInfo.Track companionAudio, State state) {
        long durationMs = info.durationUs / 1000;
        request.addPreferredAudioFormatIds(companionAudio.identity);
        request.addBufferedRanges(BufferedRange.newBuilder()
                .setFormatId(companionAudio.identity).setStartTimeMs(0).setDurationMs(durationMs)
                .setTimeRange(TimeRange.newBuilder().setStartTicks(0)
                        .setDurationTicks(durationMs).setTimescale(1000)));
        if (state.declared) request.addSelectedFormatIds(companionAudio.identity);
    }

    /**
     * Presents only selected MP4/WebM container bytes to a stock Media3 extractor. DefaultExtractorInput
     * supplies normal peek/seek-buffer semantics above this stream; no modified extractor subclass.
     */
    static final class ResponseInput extends InputStream {
        final State result;
        long wireBytes;
        long mediaBytes;
        int completedSegments;
        int completedInitializations;
        boolean cleanEof;
        private final InputStream raw;
        private final SabrStreamInfo info;
        private final SabrStreamInfo.Track track;
        private final UMPDecoder decoder = new UMPDecoder(MAX_RESPONSE_BYTES);
        private final Map<Long, Header> headers = new HashMap<>();
        private final byte[] singleByte = new byte[1];
        private int parts;
        private InputStream payload;
        private Header current;

        ResponseInput(InputStream input, SabrStreamInfo info, SabrStreamInfo.Track track, State state) {
            this.info = info;
            this.track = track;
            result = state.copy();
            result.backoffMs = 0;
            // REQUEST-scoped opaque context must not escape into a later response generation.
            result.contexts.entrySet().removeIf(entry -> entry.getValue().getScope()
                    == SabrContextUpdate.SabrContextScope.SABR_CONTEXT_SCOPE_REQUEST);
            raw = new InputStream() {
                @Override public int read() throws IOException {
                    int value = input.read();
                    if (value != -1) count(1);
                    return value;
                }
                @Override public int read(byte[] data, int offset, int length) throws IOException {
                    if (length == 0) return 0;
                    int count = input.read(data, offset, (int) Math.min(length,
                            MAX_RESPONSE_BYTES - wireBytes + 1));
                    if (count > 0) count(count);
                    return count;
                }
                private void count(int length) throws SabrException {
                    wireBytes += length;
                    if (wireBytes > MAX_RESPONSE_BYTES) throw new SabrException("response_limit");
                }
            };
        }

        @Override public int read() throws IOException {
            return read(singleByte, 0, 1) == -1 ? -1 : singleByte[0] & 255;
        }

        @Override public int read(byte[] data, int offset, int length) throws IOException {
            if (data == null) throw new NullPointerException();
            if (offset < 0 || length < 0 || offset > data.length - length) {
                throw new IndexOutOfBoundsException();
            }
            if (length == 0) return 0;
            while (!cleanEof) {
                if (payload != null) {
                    int count = payload.read(data, offset, length);
                    if (count != -1) {
                        current.received += count;
                        mediaBytes += count;
                        if (current.received > MAX_RESPONSE_BYTES || current.length >= 0
                                && current.received > current.length) throw new SabrException("media_length");
                        return count;
                    }
                    payload = null;
                    current = null;
                }
                nextPart();
            }
            return -1;
        }

        private void nextPart() throws IOException {
            UMPPart part = decoder.decode(raw);
            if (part == null) {
                if (!headers.isEmpty()) throw new EOFException("Incomplete SABR media segment");
                cleanEof = true;
                return;
            }
            if (++parts > 8192) throw new SabrException("part_limit");
            if (part.partId != UMPPartId.MEDIA && part.size > MAX_CONTROL_BYTES) {
                throw new SabrException("control_limit");
            }
            switch (part.partId) {
                case UMPPartId.FORMAT_INITIALIZATION_METADATA: {
                    FormatInitializationMetadata metadata =
                            FormatInitializationMetadata.parseFrom(part.toStream());
                    checkVideo(metadata.hasVideoId() ? metadata.getVideoId() : null);
                    if (metadata.hasVideoId()) result.bound = true;
                    if (!result.bound) throw new SabrException("unbound_initialization");
                    // The companion audio a video request has to name is announced here too.
                    // Its metadata declares nothing about this stream's own format.
                    if (!isOwn(metadata.getFormatId())) break;
                    result.declared = true;
                    if (metadata.hasEndTimeMs() && metadata.getEndTimeMs() > 0
                            && metadata.getEndTimeMs() <= info.durationUs / 1000 + 2000) {
                        result.knownDurationUs = Math.min(info.durationUs, metadata.getEndTimeMs() * 1000);
                    }
                    break;
                }
                case UMPPartId.MEDIA_HEADER: {
                    MediaHeader header = MediaHeader.parseFrom(part.toStream());
                    checkVideo(header.hasVideoId() ? header.getVideoId() : null);
                    if (header.hasVideoId()) result.bound = true;
                    if (!result.bound || !header.hasHeaderId() || header.hasCompressionAlgorithm()
                            || header.hasContentLength() && (header.getContentLength() < 0
                                || header.getContentLength() > MAX_RESPONSE_BYTES)) {
                        throw new SabrException("invalid_media_header");
                    }
                    long id = Integer.toUnsignedLong(header.getHeaderId());
                    if (headers.size() >= 16 || headers.containsKey(id)) {
                        throw new SabrException("duplicate_or_excessive_headers");
                    }
                    headers.put(id, new Header(header, isOwn(header.getFormatId())));
                    break;
                }
                case UMPPartId.MEDIA: {
                    long id = decoder.readVarInt(part.toStream());
                    Header header = headers.get(id);
                    if (header == null) throw new SabrException("unknown_media_header");
                    if (!header.own) {
                        // Companion-track bytes are still counted against the declared length, so
                        // a truncated or overlong foreign segment is caught, but they are never
                        // handed to this stream's extractor.
                        header.received += part.toStream().available();
                        if (header.length >= 0 && header.received > header.length) {
                            throw new SabrException("media_length");
                        }
                        part.skip();
                        break;
                    }
                    current = header;
                    payload = part.toStream();
                    break;
                }
                case UMPPartId.MEDIA_END: {
                    long id = decoder.readVarInt(part.toStream());
                    Header header = headers.remove(id);
                    if (header == null || part.toStream().read() != -1 || header.received == 0
                            || header.length >= 0 && header.length != header.received) {
                        throw new SabrException("incomplete_media");
                    }
                    if (!header.own) break;
                    if (header.metadata.getIsInitSeg()) {
                        result.initialized = true;
                        result.declared = true;
                        completedInitializations++;
                    } else {
                        if (!result.initialized) throw new SabrException("media_before_initialization");
                        addCompletedRange(header.metadata);
                        completedSegments++;
                    }
                    break;
                }
                case UMPPartId.STREAM_PROTECTION_STATUS: {
                    StreamProtectionStatus status = StreamProtectionStatus.parseFrom(part.toStream());
                    // Only an outright attestation DEMAND is terminal. ATTESTATION_PENDING rides
                    // along with media that is being served normally - measured on the iOS client,
                    // 2026-09-08, which returned status 2 together with 133,605 bytes of media
                    // while VISIONOS returned status 1 for the identical request.
                    if (status.getStatusValue() == StreamProtectionStatus.Status
                            .ATTESTATION_REQUIRED_VALUE) throw new SabrException("stream_protection");
                    break;
                }
                case UMPPartId.SABR_ERROR:
                    throw new SabrException("server_error");
                case UMPPartId.SABR_REDIRECT:
                    throw new SabrException("server_redirect");
                case UMPPartId.RELOAD_PLAYER_RESPONSE:
                    throw new SabrException("response_reload_required");
                case UMPPartId.NEXT_REQUEST_POLICY: {
                    NextRequestPolicy policy = NextRequestPolicy.parseFrom(part.toStream());
                    if (policy.hasVideoId() && !info.videoId.equals(policy.getVideoId())) {
                        throw new SabrException("unexpected_policy_video");
                    }
                    if (policy.getBackoffTimeMs() < 0 || policy.getBackoffTimeMs() > 20_000) {
                        throw new SabrException("unsupported_backoff");
                    }
                    result.backoffMs = policy.getBackoffTimeMs();
                    if (policy.hasPlaybackCookie()) result.cookie = policy.getPlaybackCookie().toByteString();
                    break;
                }
                case UMPPartId.SABR_CONTEXT_UPDATE: {
                    SabrContextUpdate update = SabrContextUpdate.parseFrom(part.toStream());
                    if (!update.hasType() || !update.hasValue() || !update.hasWritePolicy()
                            || update.getValue().size() > 16 * 1024) throw new SabrException("invalid_context");
                    if (update.getWritePolicy() != SabrContextUpdate.SabrContextWritePolicy
                            .SABR_CONTEXT_WRITE_POLICY_KEEP_EXISTING || !result.contexts.containsKey(update.getType())) {
                        result.contexts.put(update.getType(), update);
                    }
                    if (update.getSendByDefault()) result.sendContexts.add(update.getType());
                    checkContextBounds();
                    break;
                }
                case UMPPartId.SABR_CONTEXT_SENDING_POLICY: {
                    SabrContextSendingPolicy policy = SabrContextSendingPolicy.parseFrom(part.toStream());
                    result.sendContexts.addAll(policy.getStartPolicyList());
                    result.sendContexts.removeAll(policy.getStopPolicyList());
                    for (int type : policy.getDiscardPolicyList()) {
                        result.contexts.remove(type);
                        result.sendContexts.remove(type);
                    }
                    checkContextBounds();
                    break;
                }
                case UMPPartId.END_OF_TRACK:
                    // Not sufficient by itself: completion must be backed by actual media timing.
                case UMPPartId.PLAYBACK_START_POLICY:
                case UMPPartId.ALLOWED_CACHED_FORMATS:
                case UMPPartId.START_BW_SAMPLING_HINT:
                case UMPPartId.PAUSE_BW_SAMPLING_HINT:
                case UMPPartId.SELECTABLE_FORMATS:
                case UMPPartId.REQUEST_IDENTIFIER:
                case UMPPartId.REQUEST_CANCELLATION_POLICY:
                case UMPPartId.REQUEST_PIPELINING:
                case UMPPartId.PREWARM_CONNECTION:
                    part.skip();
                    break;
                default:
                    throw new SabrException("unsupported_part");
            }
        }

        private void checkVideo(@Nullable String video) throws SabrException {
            if (video != null && !info.videoId.equals(video)) throw new SabrException("unexpected_video");
        }

        /** False for the companion audio a video request must name; see {@link #request}. */
        private boolean isOwn(FormatId identity) {
            return identity.equals(track.identity);
        }

        private void checkContextBounds() throws SabrException {
            if (result.contexts.size() > 64 || result.sendContexts.size() > 64) {
                throw new SabrException("context_limit");
            }
        }

        private void addCompletedRange(MediaHeader header) throws SabrException {
            long startMs = header.getStartMs();
            long durationMs = header.getDurationMs();
            if (header.hasTimeRange()) {
                TimeRange time = header.getTimeRange();
                if (time.getTimescale() <= 0 || time.getStartTicks() < 0 || time.getDurationTicks() <= 0) {
                    throw new SabrException("invalid_media_time");
                }
                startMs = scaleToMs(time.getStartTicks(), time.getTimescale());
                durationMs = scaleToMs(time.getDurationTicks(), time.getTimescale());
            }
            if (startMs < 0 || durationMs <= 0 || startMs > info.durationUs / 1000 + 2000
                    || durationMs > 120_000 || startMs + durationMs > info.durationUs / 1000 + 2000) {
                throw new SabrException("invalid_media_time");
            }
            result.endUs = Math.max(result.endUs, Math.min(info.durationUs, (startMs + durationMs) * 1000));
            BufferedRange.Builder range = BufferedRange.newBuilder().setFormatId(track.identity)
                    .setStartTimeMs(startMs).setDurationMs(durationMs)
                    .setTimeRange(TimeRange.newBuilder().setStartTicks(startMs)
                            .setDurationTicks(durationMs).setTimescale(1000));
            if (header.hasSequenceNumber()) {
                range.setStartSegmentIndex(header.getSequenceNumber()).setEndSegmentIndex(header.getSequenceNumber());
            }
            result.buffered.add(range.build());
            // A bounded history, pruned again against the actual playhead before every request.
            if (result.buffered.size() > 64) result.buffered.remove(0);
        }

        private long scaleToMs(long ticks, int scale) throws SabrException {
            if (ticks > Long.MAX_VALUE / 1000) throw new SabrException("media_time_overflow");
            return ticks * 1000 / scale;
        }
    }

    private static final class Header {
        final MediaHeader metadata;
        final long length;
        final boolean own;
        long received;
        Header(MediaHeader metadata, boolean own) {
            this.metadata = metadata;
            this.own = own;
            length = metadata.hasContentLength() ? metadata.getContentLength() : -1;
        }
    }
}
