package com.newtube.mobile.sabrproof;

import static org.junit.Assert.*;

import com.google.android.exoplayer2.source.sabr.protos.misc.FormatId;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.FormatInitializationMetadata;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.MediaHeader;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.SabrError;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.StreamProtectionStatus;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.StreamerContext;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.VideoPlaybackAbrRequest;
import com.newtube.mobile.sabrproof.SabrProofProtocol.Inspection;
import com.newtube.mobile.sabrproof.ump.UMPDecoder;
import com.newtube.mobile.sabrproof.ump.UMPPart;
import com.newtube.mobile.sabrproof.ump.UMPPartId;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Synthetic protocol fixtures only: no Android, account state or network calls. */
public class SabrProofProtocolTest {
    private static final String VIDEO_ID = "fixture-video";
    private static final FormatId AUDIO = FormatId.newBuilder().setItag(140).setLastModified(123).build();
    private static final FormatId VIDEO = FormatId.newBuilder().setItag(137).setLastModified(456).build();

    @Test public void initialAudioRequestPreservesSuppliedMetadataAndHasNoInventedState() throws Exception {
        byte[] config = new byte[] {0, 1, (byte) 254, (byte) 255};
        StreamerContext.ClientInfo client = StreamerContext.ClientInfo.newBuilder()
                .setClientName(StreamerContext.ClientName.TVHTML5).setClientVersion("fixture-version")
                .setHl("es").setGl("ES").setDeviceMake("fixture-make").build();
        VideoPlaybackAbrRequest request = VideoPlaybackAbrRequest.parseFrom(SabrProofProtocol.buildInitialRequest(
                Base64.getUrlEncoder().withoutPadding().encodeToString(config), client,
                AUDIO, VIDEO, false, 1080, 128000));
        assertEquals(client, request.getStreamerContext().getClientInfo());
        assertEquals(AUDIO, request.getPreferredAudioFormatIds(0));
        assertEquals(VIDEO, request.getPreferredVideoFormatIds(0));
        assertArrayEquals(config, request.getVideoPlaybackUstreamerConfig().toByteArray());
        assertEquals(1, request.getClientAbrState().getEnabledTrackTypesBitfield());
        assertEquals(128000, request.getClientAbrState().getBandwidthEstimate());
        assertEquals(0, request.getClientAbrState().getPlayerTimeMs());
        assertEquals(1f, request.getClientAbrState().getPlaybackRate(), 0);
        assertFalse(request.getClientAbrState().hasStickyResolution());
        assertEquals(0, request.getBufferedRangesCount());
        assertEquals(0, request.getSelectedFormatIdsCount());
        assertFalse(request.getStreamerContext().hasPoToken());
        assertFalse(request.getStreamerContext().hasPlaybackCookie());
        assertEquals(0, request.getStreamerContext().getSabrContextsCount());
        assertEquals(0, request.getStreamerContext().getUnsentSabrContextsCount());
    }

    @Test public void initialVideoUsesActualHeightAndOptionalBandwidth() throws Exception {
        VideoPlaybackAbrRequest request = VideoPlaybackAbrRequest.parseFrom(SabrProofProtocol.buildInitialRequest(
                "AQ", StreamerContext.ClientInfo.getDefaultInstance(), AUDIO, VIDEO, true, 720, -1));
        assertEquals(2, request.getClientAbrState().getEnabledTrackTypesBitfield());
        assertEquals(720, request.getClientAbrState().getStickyResolution());
        assertEquals(720, request.getClientAbrState().getLastManualSelectedResolution());
        assertFalse(request.getClientAbrState().hasBandwidthEstimate());
    }

    @Test public void missingMetadataIsRejectedWithoutChoosingAnIdentity() {
        assertThrows(IllegalArgumentException.class, () -> SabrProofProtocol.buildInitialRequest(
                "AQ", null, AUDIO, VIDEO, false, -1, -1));
        assertThrows(IllegalArgumentException.class, () -> SabrProofProtocol.buildInitialRequest(
                "AQ", StreamerContext.ClientInfo.getDefaultInstance(), AUDIO, null, true, -1, -1));
        assertThrows(IllegalArgumentException.class, () -> SabrProofProtocol.buildInitialRequest(
                "invalid!", StreamerContext.ClientInfo.getDefaultInstance(), AUDIO, VIDEO, false, -1, -1));
    }

    @Test public void matchingInitAndCompletedMediaAreCountedAndRetainedSeparately() throws Exception {
        byte[] fixture = concat(initialization(AUDIO), initialization(VIDEO),
                segment(7, AUDIO, true, "ai"), segment(8, AUDIO, false, "audio"),
                segment(130, VIDEO, true, "vi"), segment(131, VIDEO, false, "video"));
        Inspection result = inspect(fixture);
        assertEquals("eof", result.stopReason);
        assertEquals(fixture.length, result.responseBytes);
        assertEquals(2, result.formatInitializations);
        assertEquals(4, result.mediaHeaders);
        assertEquals(2, result.audioInitBytes);
        assertEquals(5, result.audioMediaBytes);
        assertEquals(2, result.videoInitBytes);
        assertEquals(5, result.videoMediaBytes);
        assertEquals(1, result.completedAudioSegments);
        assertEquals(1, result.completedVideoSegments);
        assertEquals(0, result.unmatchedMediaBytes);
        assertTrue(result.hasCompleteMedia());
        assertArrayEquals(bytes("aiaudio"), result.getAudioBytes());
        assertArrayEquals(bytes("vivideo"), result.getVideoBytes());
    }

    @Test public void protectionDenialStopsBeforeTrailingMediaEvenWhenServerSuggestsRetries() throws Exception {
        for (int status : new int[] {0, 2, 3, 17}) {
            byte[] denied = part(UMPPartId.STREAM_PROTECTION_STATUS,
                    StreamProtectionStatus.newBuilder().setStatusValue(status).setMaxRetries(99).build().toByteArray());
            Inspection result = inspect(concat(denied, segment(1, AUDIO, false, "must-not-read")));
            assertEquals(status, result.protectionStatus);
            assertEquals(denied.length, result.responseBytes);
            assertEquals(0, result.audioMediaBytes);
            assertEquals(0, result.getAudioBytes().length);
            assertFalse(result.hasCompleteMedia());
            assertNotEquals("eof", result.stopReason);
        }
    }

    @Test public void protectionOkAloneIsNotMediaProof() throws Exception {
        Inspection result = inspect(part(UMPPartId.STREAM_PROTECTION_STATUS,
                StreamProtectionStatus.newBuilder().setStatusValue(1).build().toByteArray()));
        assertEquals("eof", result.stopReason);
        assertEquals(1, result.protectionStatus);
        assertFalse(result.hasCompleteMedia());
    }

    @Test public void errorReloadRedirectAndUnknownControlsNeverTriggerRecovery() throws Exception {
        SabrError error = SabrError.newBuilder().setError(
                com.google.android.exoplayer2.source.sabr.protos.videostreaming.Error.newBuilder()
                        .setStatusCode(403)).build();
        Inspection denied = inspect(concat(part(UMPPartId.SABR_ERROR, error.toByteArray()),
                segment(1, AUDIO, false, "unread")));
        assertEquals("sabr_error", denied.stopReason);
        assertEquals(403, denied.protocolErrorCode);
        assertFalse(denied.hasCompleteMedia());
        int[] controls = {UMPPartId.RELOAD_PLAYER_RESPONSE, UMPPartId.SABR_REDIRECT, 999};
        String[] reasons = {"reload_player_response", "redirect", "unsupported_part"};
        for (int i = 0; i < controls.length; i++) {
            Inspection result = inspect(concat(part(controls[i], new byte[0]), segment(1, AUDIO, false, "unread")));
            assertEquals(reasons[i], result.stopReason);
            assertEquals(0, result.audioMediaBytes);
            assertFalse(result.hasCompleteMedia());
        }
    }

    @Test public void denialAfterCompletedMediaInvalidatesProof() throws Exception {
        Inspection result = inspect(concat(segment(1, AUDIO, false, "received"),
                part(UMPPartId.STREAM_PROTECTION_STATUS,
                        StreamProtectionStatus.newBuilder().setStatusValue(3).build().toByteArray())));
        assertEquals(8, result.audioMediaBytes);
        assertEquals(1, result.completedAudioSegments);
        assertFalse(result.hasCompleteMedia());
        assertEquals("attestation_required", result.stopReason);
    }

    @Test public void unknownHeaderOrFormatNeverCountsAsSelectedMedia() throws Exception {
        Inspection unknownHeader = inspect(part(UMPPartId.MEDIA, concat(integer(55), bytes("unknown"))));
        assertEquals(7, unknownHeader.unmatchedMediaBytes);
        assertEquals(0, unknownHeader.audioMediaBytes);
        assertFalse(unknownHeader.hasCompleteMedia());
        Inspection unknownFormat = inspect(segment(1, FormatId.newBuilder().setItag(999).build(), false, "other"));
        assertEquals(5, unknownFormat.unmatchedMediaBytes);
        assertEquals(0, unknownFormat.getAudioBytes().length);
        assertFalse(unknownFormat.hasCompleteMedia());
    }

    @Test public void formatIdentityIncludesTimestampAndNormalizedXtags() throws Exception {
        FormatId[] mismatches = { AUDIO.toBuilder().setLastModified(124).build(),
                AUDIO.toBuilder().setXtags("different-variant").build(), AUDIO.toBuilder().clearLastModified().build() };
        for (FormatId mismatch : mismatches) {
            Inspection result = inspect(segment(1, mismatch, false, "wrong"));
            assertEquals(5, result.unmatchedMediaBytes);
            assertFalse(result.hasCompleteMedia());
        }
        assertTrue(inspect(segment(1, AUDIO.toBuilder().setXtags("").build(), false, "ok")).hasCompleteMedia());
    }

    @Test public void mismatchedVideoStopsAndMissingBindingNeverCounts() throws Exception {
        MediaHeader wrong = header(1, AUDIO, false, 5).toBuilder().setVideoId("other-video").build();
        assertEquals("unexpected_video", inspect(part(UMPPartId.MEDIA_HEADER, wrong.toByteArray())).stopReason);
        FormatInitializationMetadata wrongInit = FormatInitializationMetadata.newBuilder()
                .setVideoId("other-video").setFormatId(AUDIO).build();
        assertEquals("unexpected_video", inspect(part(UMPPartId.FORMAT_INITIALIZATION_METADATA,
                wrongInit.toByteArray())).stopReason);
        byte[] missingBinding = concat(part(UMPPartId.MEDIA_HEADER,
                header(1, AUDIO, false, 5).toBuilder().clearVideoId().build().toByteArray()),
                part(UMPPartId.MEDIA, concat(integer(1), bytes("audio"))), part(UMPPartId.MEDIA_END, integer(1)));
        assertFalse(inspect(missingBinding).hasCompleteMedia());
        assertEquals(5, inspect(missingBinding).unmatchedMediaBytes);
        assertTrue(inspect(concat(initialization(AUDIO), missingBinding)).hasCompleteMedia());
    }

    @Test public void initOnlyAndIncompleteMediaAreNotPlaybackProof() throws Exception {
        Inspection initOnly = inspect(segment(1, AUDIO, true, "init"));
        assertEquals("eof", initOnly.stopReason);
        assertEquals(4, initOnly.audioInitBytes);
        assertFalse(initOnly.hasCompleteMedia());
        Inspection partial = inspect(concat(part(UMPPartId.MEDIA_HEADER, header(1, AUDIO, false, 5).toByteArray()),
                part(UMPPartId.MEDIA, concat(integer(1), bytes("audio")))));
        assertEquals("incomplete_media", partial.stopReason);
        assertEquals(5, partial.audioMediaBytes);
        assertFalse(partial.hasCompleteMedia());
    }

    @Test public void declaredLengthMismatchAndDuplicateHeadersInvalidateProof() throws Exception {
        Inspection wrongLength = inspect(concat(part(UMPPartId.MEDIA_HEADER, header(1, AUDIO, false, 9).toByteArray()),
                part(UMPPartId.MEDIA, concat(integer(1), bytes("short"))), part(UMPPartId.MEDIA_END, integer(1))));
        assertEquals("media_length_mismatch", wrongLength.stopReason);
        assertFalse(wrongLength.hasCompleteMedia());
        byte[] header = part(UMPPartId.MEDIA_HEADER, header(1, AUDIO, false, 3).toByteArray());
        assertEquals("duplicate_media_header", inspect(concat(header, header)).stopReason);
    }

    @Test public void truncationAndMalformedProtobufAreInconclusive() throws Exception {
        assertEquals("truncated_response", inspect(new byte[] {(byte) 0x80}).stopReason);
        assertEquals("truncated_response", inspect(new byte[] {21}).stopReason);
        assertEquals("truncated_response", inspect(concat(integer(21), integer(8), new byte[] {1, 2})).stopReason);
        assertEquals("invalid_protobuf", inspect(part(UMPPartId.MEDIA_HEADER, new byte[] {0})).stopReason);
    }

    @Test public void byteAndPartLimitsBoundRetainedDataAndReads() throws Exception {
        byte[] fixture = concat(part(UMPPartId.MEDIA_HEADER, header(1, AUDIO, false, 500).toByteArray()),
                part(UMPPartId.MEDIA, concat(integer(1), new byte[500])));
        Inspection limited = SabrProofProtocol.inspect(new ByteArrayInputStream(fixture), VIDEO_ID, AUDIO, VIDEO, 520);
        assertEquals("response_limit", limited.stopReason);
        assertEquals(520, limited.responseBytes);
        assertTrue(limited.getAudioBytes().length <= 520);
        assertFalse(limited.hasCompleteMedia());
        ByteArrayOutputStream many = new ByteArrayOutputStream();
        for (int i = 0; i < 4097; i++) many.write(part(UMPPartId.REQUEST_IDENTIFIER, new byte[0]));
        assertEquals("part_limit", inspect(many.toByteArray()).stopReason);
    }

    @Test public void umpIntegersAndPayloadViewsPreserveBoundaries() throws Exception {
        UMPDecoder decoder = new UMPDecoder(1024);
        long[] values = {0, 127, 128, 16383, 16384, 2097151, 2097152, 268435455, 268435456, 0xffffffffL};
        for (long value : values) assertEquals(value, decoder.readVarInt(new ByteArrayInputStream(integer(value))));
        ByteArrayInputStream input = new ByteArrayInputStream(concat(part(21, bytes("abc")), part(22, integer(7))));
        UMPPart first = decoder.decode(input);
        InputStream payload = first.toStream();
        assertSame(payload, first.toStream());
        assertEquals('a', payload.read());
        first.skip();
        assertEquals(-1, payload.read());
        UMPPart second = decoder.decode(input);
        assertEquals(22, second.partId);
        assertEquals(7, decoder.readVarInt(second.toStream()));
        assertNull(decoder.decode(input));
        assertThrows(EOFException.class, () -> decoder.readVarInt(new ByteArrayInputStream(new byte[] {(byte) 0xf0, 1})));
    }

    private static Inspection inspect(byte[] fixture) throws IOException {
        return SabrProofProtocol.inspect(new ByteArrayInputStream(fixture), VIDEO_ID, AUDIO, VIDEO, 1024 * 1024);
    }

    private static byte[] initialization(FormatId format) throws IOException {
        return part(UMPPartId.FORMAT_INITIALIZATION_METADATA, FormatInitializationMetadata.newBuilder()
                .setVideoId(VIDEO_ID).setFormatId(format).build().toByteArray());
    }

    private static MediaHeader header(int id, FormatId format, boolean init, int length) {
        return MediaHeader.newBuilder().setHeaderId(id).setVideoId(VIDEO_ID).setFormatId(format)
                .setIsInitSeg(init).setContentLength(length).setSequenceNumber(init ? 0 : 1).build();
    }

    private static byte[] segment(int id, FormatId format, boolean init, String content) throws IOException {
        byte[] bytes = bytes(content);
        return concat(part(UMPPartId.MEDIA_HEADER, header(id, format, init, bytes.length).toByteArray()),
                part(UMPPartId.MEDIA, concat(integer(id), bytes)), part(UMPPartId.MEDIA_END, integer(id)));
    }

    private static byte[] part(int type, byte[] payload) throws IOException {
        return concat(integer(type), integer(payload.length), payload);
    }

    /** Synthetic fixture encoder for UMP's integer encoding (not protobuf varints). */
    private static byte[] integer(long value) {
        int size = value < 128 ? 1 : value < 16384 ? 2 : value < 2097152 ? 3 : value < 268435456 ? 4 : 5;
        byte[] result = new byte[size];
        int shift = size == 5 ? 0 : 8 - size;
        int prefix = size == 1 ? 0 : size == 2 ? 0x80 : size == 3 ? 0xc0 : size == 4 ? 0xe0 : 0xf0;
        result[0] = (byte) (prefix | (size == 5 ? 0 : value & ((1 << shift) - 1)));
        for (int i = 1; i < size; i++, shift += 8) result[i] = (byte) (value >>> shift);
        return result;
    }

    private static byte[] concat(byte[]... inputs) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] input : inputs) output.write(input);
        return output.toByteArray();
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
