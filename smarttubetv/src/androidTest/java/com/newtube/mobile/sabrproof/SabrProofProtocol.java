package com.newtube.mobile.sabrproof;

import com.google.android.exoplayer2.source.sabr.protos.misc.FormatId;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.ClientAbrState;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.FormatInitializationMetadata;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.MediaHeader;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.SabrError;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.StreamProtectionStatus;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.StreamerContext;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.VideoPlaybackAbrRequest;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.newtube.mobile.sabrproof.ump.UMPDecoder;
import com.newtube.mobile.sabrproof.ump.UMPPart;
import com.newtube.mobile.sabrproof.ump.UMPPartId;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProtocolException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** Offline-capable, test-only initial-request/response proof. See PROVENANCE.md. */
public final class SabrProofProtocol {
    private static final long MAX_RESPONSE_BYTES = 32L * 1024 * 1024;
    private static final int MAX_PARTS = 4096;
    private static final int AUDIO = 1;
    private static final int VIDEO = 2;

    private SabrProofProtocol() {}

    /** Caller supplies the current accepted response's config, formats and existing client. */
    public static byte[] buildInitialRequest(String config, StreamerContext.ClientInfo clientInfo,
            FormatId audio, FormatId video, boolean videoRequest, int height,
            long bandwidthEstimate) {
        if (config == null || config.isEmpty() || config.length() > 1024 * 1024
                || clientInfo == null || !validFormat(videoRequest ? video : audio)) {
            throw new IllegalArgumentException("Missing or oversized initial request metadata");
        }
        if ((audio != null && !validFormat(audio)) || (video != null && !validFormat(video))) {
            throw new IllegalArgumentException("Invalid selected format");
        }
        ClientAbrState.Builder state = ClientAbrState.newBuilder()
                .setSabrForceMaxNetworkInterruptionDurationMs(0)
                .setPlaybackRate(1).setPlayerTimeMs(0)
                .setClientViewportIsFlexible(false).setDrcEnabled(false)
                .setEnabledTrackTypesBitfield(videoRequest ? VIDEO : AUDIO);
        if (bandwidthEstimate >= 0) state.setBandwidthEstimate(bandwidthEstimate);
        if (videoRequest && height > 0) {
            state.setStickyResolution(height).setLastManualSelectedResolution(height);
        }
        VideoPlaybackAbrRequest.Builder request = VideoPlaybackAbrRequest.newBuilder()
                .setClientAbrState(state)
                .setVideoPlaybackUstreamerConfig(ByteString.copyFrom(Base64.getUrlDecoder().decode(config)))
                .setStreamerContext(StreamerContext.newBuilder().setClientInfo(clientInfo));
        if (audio != null) request.addPreferredAudioFormatIds(audio);
        if (video != null) request.addPreferredVideoFormatIds(video);
        // This is an initial request: no invented buffered ranges, cookies or token state.
        return request.build().toByteArray();
    }

    /** No transport, retries, redirection, state mutation, or logging; does not close response. */
    public static Inspection inspect(InputStream response, String expectedVideoId, FormatId audio,
            FormatId video, long maxResponseBytes) throws IOException {
        if (response == null || expectedVideoId == null || expectedVideoId.isEmpty()
                || maxResponseBytes <= 0 || maxResponseBytes > MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("Invalid inspection bound or video binding");
        }
        Inspection result = new Inspection();
        BoundedInputStream input = new BoundedInputStream(response, maxResponseBytes);
        UMPDecoder decoder = new UMPDecoder((int) maxResponseBytes);
        Map<Long, Header> headers = new HashMap<>();
        boolean[] boundFormat = new boolean[3];
        byte[] buffer = new byte[8192];
        try {
            while (true) {
                UMPPart part = decoder.decode(input);
                if (part == null) {
                    result.stopReason = headers.isEmpty() ? "eof" : "incomplete_media";
                    return result;
                }
                if (++result.parts > MAX_PARTS) return result.stop("part_limit");
                switch (part.partId) {
                    case UMPPartId.FORMAT_INITIALIZATION_METADATA: {
                        FormatInitializationMetadata metadata = FormatInitializationMetadata.parseFrom(part.toStream());
                        result.formatInitializations++;
                        if (metadata.hasVideoId() && !expectedVideoId.equals(metadata.getVideoId())) {
                            return result.stop("unexpected_video");
                        }
                        int track = track(metadata.getFormatId(), audio, video);
                        if (track != 0 && metadata.hasVideoId()) boundFormat[track] = true;
                        break;
                    }
                    case UMPPartId.MEDIA_HEADER: {
                        MediaHeader metadata = MediaHeader.parseFrom(part.toStream());
                        result.mediaHeaders++;
                        if (metadata.hasVideoId() && !expectedVideoId.equals(metadata.getVideoId())) {
                            return result.stop("unexpected_video");
                        }
                        if (!metadata.hasHeaderId() || !metadata.hasFormatId()) {
                            return result.stop("invalid_media_header");
                        }
                        if (metadata.hasCompressionAlgorithm()) return result.stop("unsupported_compression");
                        if (metadata.hasContentLength() && metadata.getContentLength() < 0) {
                            return result.stop("invalid_media_length");
                        }
                        long id = Integer.toUnsignedLong(metadata.getHeaderId());
                        if (headers.containsKey(id)) return result.stop("duplicate_media_header");
                        int track = track(metadata.getFormatId(), audio, video);
                        if (!metadata.hasVideoId() && !boundFormat[track]) track = 0;
                        headers.put(id, new Header(track, metadata.getIsInitSeg(),
                                metadata.hasContentLength() ? metadata.getContentLength() : -1));
                        break;
                    }
                    case UMPPartId.MEDIA: {
                        InputStream payload = part.toStream();
                        long id = decoder.readVarInt(payload);
                        if (id == -1) return result.stop("missing_media_id");
                        Header header = headers.get(id);
                        int count;
                        while ((count = payload.read(buffer)) != -1) {
                            if (header != null) header.received += count;
                            if (header == null || header.track == 0) result.unmatchedMediaBytes += count;
                            else if (header.track == AUDIO) {
                                result.audioBytes.write(buffer, 0, count);
                                if (header.init) result.audioInitBytes += count;
                                else result.audioMediaBytes += count;
                            } else {
                                result.videoBytes.write(buffer, 0, count);
                                if (header.init) result.videoInitBytes += count;
                                else result.videoMediaBytes += count;
                            }
                        }
                        break;
                    }
                    case UMPPartId.MEDIA_END: {
                        InputStream payload = part.toStream();
                        long id = decoder.readVarInt(payload);
                        if (id == -1 || payload.read() != -1) return result.stop("invalid_media_end");
                        Header header = headers.remove(id);
                        if (header == null) return result.stop("unknown_media_end");
                        if (header.length >= 0 && header.length != header.received) {
                            return result.stop("media_length_mismatch");
                        }
                        if (!header.init && header.received > 0) {
                            if (header.track == AUDIO) result.completedAudioSegments++;
                            else if (header.track == VIDEO) result.completedVideoSegments++;
                        }
                        break;
                    }
                    case UMPPartId.STREAM_PROTECTION_STATUS: {
                        StreamProtectionStatus status = StreamProtectionStatus.parseFrom(part.toStream());
                        result.protectionStatus = status.getStatusValue();
                        if (result.protectionStatus != 1) {
                            return result.stop(result.protectionStatus == 2 ? "attestation_pending"
                                    : result.protectionStatus == 3 ? "attestation_required" : "protection_unknown");
                        }
                        break;
                    }
                    case UMPPartId.SABR_ERROR: {
                        SabrError error = SabrError.parseFrom(part.toStream());
                        if (error.hasError() && error.getError().hasStatusCode()) {
                            result.protocolErrorCode = error.getError().getStatusCode();
                        }
                        return result.stop("sabr_error");
                    }
                    case UMPPartId.SABR_REDIRECT:
                        return result.stop("redirect");
                    case UMPPartId.RELOAD_PLAYER_RESPONSE:
                        return result.stop("reload_player_response");
                    case UMPPartId.NEXT_REQUEST_POLICY:
                    case UMPPartId.PLAYBACK_START_POLICY:
                    case UMPPartId.ALLOWED_CACHED_FORMATS:
                    case UMPPartId.START_BW_SAMPLING_HINT:
                    case UMPPartId.PAUSE_BW_SAMPLING_HINT:
                    case UMPPartId.SELECTABLE_FORMATS:
                    case UMPPartId.REQUEST_IDENTIFIER:
                    case UMPPartId.REQUEST_CANCELLATION_POLICY:
                    case UMPPartId.REQUEST_PIPELINING:
                    case UMPPartId.SABR_CONTEXT_UPDATE:
                    case UMPPartId.SABR_CONTEXT_SENDING_POLICY:
                    case UMPPartId.END_OF_TRACK:
                    case UMPPartId.PREWARM_CONNECTION:
                        // One response only. Policies/context are not replayed or persisted.
                        part.skip();
                        break;
                    default:
                        return result.stop("unsupported_part");
                }
            }
        } catch (ResponseLimitException e) {
            return result.stop("response_limit");
        } catch (InvalidProtocolBufferException e) {
            return result.stop("invalid_protobuf");
        } catch (EOFException e) {
            return result.stop("truncated_response");
        } catch (ProtocolException e) {
            return result.stop("invalid_ump");
        } finally {
            result.responseBytes = input.count;
        }
    }

    private static boolean validFormat(FormatId format) {
        return format != null && format.hasItag() && format.getItag() > 0;
    }

    private static int track(FormatId actual, FormatId audio, FormatId video) {
        if (matches(actual, audio)) return AUDIO;
        return matches(actual, video) ? VIDEO : 0;
    }

    private static boolean matches(FormatId actual, FormatId expected) {
        return validFormat(expected) && actual.hasItag() && actual.getItag() == expected.getItag()
                && actual.getLastModified() == expected.getLastModified()
                && actual.getXtags().equals(expected.getXtags());
    }

    /** Counts are observations, not proof by themselves: require eof and completed media. */
    public static final class Inspection {
        public long responseBytes;
        public int parts;
        public int formatInitializations;
        public int mediaHeaders;
        public long audioInitBytes;
        public long videoInitBytes;
        public long audioMediaBytes;
        public long videoMediaBytes;
        public long unmatchedMediaBytes;
        public int completedAudioSegments;
        public int completedVideoSegments;
        public int protectionStatus = -1;
        public int protocolErrorCode = -1;
        public String stopReason;
        private final ByteArrayOutputStream audioBytes = new ByteArrayOutputStream();
        private final ByteArrayOutputStream videoBytes = new ByteArrayOutputStream();

        public byte[] getAudioBytes() { return audioBytes.toByteArray(); }
        public byte[] getVideoBytes() { return videoBytes.toByteArray(); }

        public boolean hasCompleteMedia() {
            return "eof".equals(stopReason)
                    && (completedAudioSegments > 0 || completedVideoSegments > 0);
        }

        private Inspection stop(String reason) {
            stopReason = reason;
            return this;
        }
    }

    private static final class Header {
        final int track;
        final boolean init;
        final long length;
        long received;

        Header(int track, boolean init, long length) {
            this.track = track;
            this.init = init;
            this.length = length;
        }
    }

    private static final class ResponseLimitException extends IOException {}

    private static final class BoundedInputStream extends InputStream {
        final InputStream input;
        final long limit;
        long count;

        BoundedInputStream(InputStream input, long limit) {
            this.input = input;
            this.limit = limit;
        }

        @Override public int read() throws IOException {
            if (count >= limit) throw new ResponseLimitException();
            int value = input.read();
            if (value != -1) count++;
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (count >= limit) throw new ResponseLimitException();
            int read = input.read(bytes, offset, (int) Math.min(length, limit - count));
            if (read > 0) count += read;
            return read;
        }
    }
}
