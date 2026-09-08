package com.newtube.sabr;

import static com.newtube.sabr.SabrTestData.*;
import static org.junit.Assert.*;

import android.app.Application;
import com.google.protobuf.ByteString;
import com.newtube.sabr.proto.misc.FormatId;
import com.newtube.sabr.proto.videostreaming.*;
import com.newtube.sabr.ump.UMPPartId;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SabrProtocolTest {
    private final SabrStreamInfo.Track track = video();
    private final SabrStreamInfo info = info(track);

    @Test public void initialRequestUsesTheSuppliedClientAndRealPlaybackState() throws Exception {
        VideoPlaybackAbrRequest request = VideoPlaybackAbrRequest.parseFrom(SabrProtocol.request(info, track, null,
                new SabrProtocol.State(2_000_000, info.durationUs), 2_000_000, 734000, 1.5f));
        assertEquals(StreamerContext.ClientName.TVHTML5, request.getStreamerContext().getClientInfo().getClientName());
        assertEquals(2000, request.getClientAbrState().getPlayerTimeMs());
        assertEquals(734000, request.getClientAbrState().getBandwidthEstimate());
        assertEquals(1.5f, request.getClientAbrState().getPlaybackRate(), 0);
        assertEquals(0, request.getSelectedFormatIdsCount());
        assertEquals(0, request.getBufferedRangesCount());
        assertFalse(request.getStreamerContext().hasPoToken());
        assertEquals(track.identity, request.getPreferredVideoFormatIds(0));
    }

    @Test public void initializationOnlyIsProgressButNotMedia() throws Exception {
        SabrProtocol.State original = new SabrProtocol.State(0, info.durationUs);
        SabrProtocol.ResponseInput response = read(concat(declaration(track),
                segment(track, 1, true, 0, 0, new byte[]{1, 2})), original);
        assertTrue(response.cleanEof);
        assertTrue(response.result.initialized);
        assertEquals(1, response.completedInitializations);
        assertEquals(0, response.completedSegments);
        assertFalse(original.initialized);
        assertEquals(0, original.endUs);
    }

    @Test public void aSeparateContinuationRetainsInitializationAndOnlyRealBufferedRanges() throws Exception {
        SabrProtocol.ResponseInput init = read(segment(track, 1, true, 0, 0, new byte[]{1}), state());
        SabrProtocol.ResponseInput media = read(segment(track, 2, false, 0, 2000, new byte[]{2, 3}), init.result);
        assertEquals(1, media.completedSegments);
        assertEquals(2_000_000, media.result.endUs);
        VideoPlaybackAbrRequest continuation = VideoPlaybackAbrRequest.parseFrom(
                SabrProtocol.request(info, track, null, media.result, 500_000, 1000000, 1));
        assertEquals(track.identity, continuation.getSelectedFormatIds(0));
        assertEquals(1, continuation.getBufferedRangesCount());
        assertEquals(0, continuation.getBufferedRanges(0).getStartTimeMs());
        assertEquals(2000, continuation.getBufferedRanges(0).getDurationMs());
        assertEquals(0, VideoPlaybackAbrRequest.parseFrom(SabrProtocol.request(info, track, null, media.result,
                2_000_000, 1, 1)).getBufferedRangesCount());
    }

    @Test public void fragmentsAcrossSingleByteReadsYieldOnlyContainerBytes() throws Exception {
        byte[] wire = concat(declaration(track), segment(track, 1, true, 0, 0, new byte[]{10, 20}),
                segment(track, 2, false, 0, 2000, new byte[]{30, 40, 50}));
        ByteArrayInputStream fragmented = new ByteArrayInputStream(wire) {
            @Override public synchronized int read(byte[] data, int offset, int length) {
                return super.read(data, offset, Math.min(1, length));
            }
        };
        SabrProtocol.ResponseInput response = new SabrProtocol.ResponseInput(fragmented, info, track, state());
        assertArrayEquals(new byte[]{10, 20, 30, 40, 50}, drain(response));
        assertEquals(wire.length, response.wireBytes);
        assertEquals(5, response.mediaBytes);
    }

    @Test public void truncatedEndDoesNotCommitTheInputState() throws Exception {
        byte[] complete = segment(track, 1, true, 0, 0, new byte[]{1, 2});
        SabrProtocol.State before = state();
        assertThrows(IOException.class, () -> read(Arrays.copyOf(complete, complete.length - 1), before));
        assertFalse(before.initialized);
        assertTrue(before.buffered.isEmpty());
    }

    @Test public void mediaWithoutInitializationIsRejected() {
        fails("media_before_initialization", segment(track, 1, false, 0, 2000, new byte[]{1}));
    }

    @Test public void aDifferentVideoIsRejected() {
        fails("unexpected_video", part(UMPPartId.FORMAT_INITIALIZATION_METADATA,
                FormatInitializationMetadata.newBuilder().setVideoId("abcdefghijk")
                        .setFormatId(track.identity).build().toByteArray()));
    }

    @Test public void sameItagWithDifferentModificationTimeDeclaresNothing() throws Exception {
        FormatId different = track.identity.toBuilder().setLastModified(99999).build();
        SabrProtocol.ResponseInput response = read(part(UMPPartId.FORMAT_INITIALIZATION_METADATA,
                FormatInitializationMetadata.newBuilder().setVideoId(VIDEO).setFormatId(different)
                        .build().toByteArray()), state());
        assertFalse(response.result.declared);
        assertFalse(response.result.initialized);
        assertEquals(0, response.completedInitializations);
    }

    @Test public void sameItagWithDifferentAudioVariantDeclaresNothing() throws Exception {
        FormatId different = track.identity.toBuilder().setXtags("lang=es").build();
        SabrProtocol.ResponseInput response = read(part(UMPPartId.FORMAT_INITIALIZATION_METADATA,
                FormatInitializationMetadata.newBuilder().setVideoId(VIDEO).setFormatId(different)
                        .build().toByteArray()), state());
        assertFalse(response.result.declared);
        assertEquals(0, response.completedInitializations);
    }

    @Test public void aVideoRequestNamesTheCompanionAudioAndClaimsAllOfIt() throws Exception {
        SabrStreamInfo.Track companion = audio();
        SabrStreamInfo both = info(track, companion);
        VideoPlaybackAbrRequest request = VideoPlaybackAbrRequest.parseFrom(SabrProtocol.request(
                both, track, companion, new SabrProtocol.State(0, both.durationUs), 0, 1, 1));
        // 0, not a "video" bit: the server has no video-only mode, so the companion is claimed
        // instead. See TRACK_TYPES_* in SabrProtocol.
        assertEquals(0, request.getClientAbrState().getEnabledTrackTypesBitfield());
        assertEquals(track.identity, request.getPreferredVideoFormatIds(0));
        assertEquals(companion.identity, request.getPreferredAudioFormatIds(0));
        assertEquals(1, request.getBufferedRangesCount());
        assertEquals(companion.identity, request.getBufferedRanges(0).getFormatId());
        assertEquals(0, request.getBufferedRanges(0).getStartTimeMs());
        assertEquals(both.durationUs / 1000, request.getBufferedRanges(0).getDurationMs());
        assertEquals(0, request.getSelectedFormatIdsCount());
    }

    @Test public void anAudioRequestAsksForAudioAloneAndClaimsNothingElse() throws Exception {
        SabrStreamInfo.Track companion = audio();
        SabrStreamInfo both = info(track, companion);
        VideoPlaybackAbrRequest request = VideoPlaybackAbrRequest.parseFrom(SabrProtocol.request(
                both, companion, null, new SabrProtocol.State(0, both.durationUs), 0, 1, 1));
        assertEquals(1, request.getClientAbrState().getEnabledTrackTypesBitfield());
        assertEquals(companion.identity, request.getPreferredAudioFormatIds(0));
        assertEquals(0, request.getPreferredVideoFormatIdsCount());
        assertEquals(0, request.getBufferedRangesCount());
    }

    @Test public void aDeclaredVideoStreamAlsoStopsTheCompanionInitialization() throws Exception {
        SabrStreamInfo.Track companion = audio();
        SabrStreamInfo both = info(track, companion);
        SabrProtocol.State declared = new SabrProtocol.State(0, both.durationUs);
        declared.declared = true;
        VideoPlaybackAbrRequest request = VideoPlaybackAbrRequest.parseFrom(
                SabrProtocol.request(both, track, companion, declared, 0, 1, 1));
        assertTrue(request.getSelectedFormatIdsList().contains(track.identity));
        assertTrue(request.getSelectedFormatIdsList().contains(companion.identity));
        assertEquals(2, request.getSelectedFormatIdsCount());
    }

    @Test public void companionMediaIsDiscardedInsteadOfPlayedOrCounted() throws Exception {
        SabrStreamInfo.Track companion = audio();
        SabrStreamInfo both = info(track, companion);
        byte[] wire = concat(declaration(track), segment(track, 1, true, 0, 0, new byte[]{10, 20}),
                declaration(companion), segment(companion, 2, true, 0, 0, new byte[]{99, 98, 97}),
                segment(track, 3, false, 0, 2000, new byte[]{30}),
                segment(companion, 4, false, 0, 2000, new byte[]{96, 95}));
        SabrProtocol.State before = new SabrProtocol.State(0, both.durationUs);
        SabrProtocol.ResponseInput response =
                new SabrProtocol.ResponseInput(new ByteArrayInputStream(wire), both, track, before);
        // Only this stream's own container bytes reach the extractor.
        assertArrayEquals(new byte[]{10, 20, 30}, drain(response));
        assertEquals(1, response.completedSegments);
        assertEquals(1, response.completedInitializations);
        assertEquals(3, response.mediaBytes);
        assertEquals(1, response.result.buffered.size());
        assertEquals(track.identity, response.result.buffered.get(0).getFormatId());
    }

    @Test public void aResponseCarryingOnlyCompanionMediaMakesNoProgress() throws Exception {
        SabrStreamInfo.Track companion = audio();
        SabrStreamInfo both = info(track, companion);
        byte[] wire = concat(declaration(companion), segment(companion, 1, true, 0, 0, new byte[]{9, 8}));
        SabrProtocol.ResponseInput response = new SabrProtocol.ResponseInput(
                new ByteArrayInputStream(wire), both, track, new SabrProtocol.State(0, both.durationUs));
        assertEquals(0, drain(response).length);
        assertTrue(response.cleanEof);
        assertFalse(response.result.declared);
        assertFalse(response.result.initialized);
        assertEquals(0, response.completedSegments);
    }

    @Test public void aTruncatedCompanionSegmentIsStillRejected() {
        SabrStreamInfo.Track companion = audio();
        SabrStreamInfo both = info(track, companion);
        byte[] header = part(UMPPartId.MEDIA_HEADER, MediaHeader.newBuilder().setHeaderId(1)
                .setVideoId(VIDEO).setFormatId(companion.identity).setIsInitSeg(true)
                .setContentLength(3).build().toByteArray());
        byte[] wire = concat(header, part(UMPPartId.MEDIA, concat(varint(1), new byte[]{1, 2})),
                part(UMPPartId.MEDIA_END, varint(1)));
        IOException failure = assertThrows(IOException.class, () -> drain(new SabrProtocol.ResponseInput(
                new ByteArrayInputStream(wire), both, track, new SabrProtocol.State(0, both.durationUs))));
        assertTrue(failure.getMessage(), failure.getMessage().endsWith("incomplete_media"));
    }

    @Test public void formatDeclarationMustBeVideoBound() {
        fails("unbound_initialization", part(UMPPartId.FORMAT_INITIALIZATION_METADATA,
                FormatInitializationMetadata.newBuilder().setFormatId(track.identity).build().toByteArray()));
    }

    @Test public void aMismatchedDeclaredLengthIsRejected() {
        byte[] header = part(UMPPartId.MEDIA_HEADER, MediaHeader.newBuilder().setHeaderId(1)
                .setVideoId(VIDEO).setFormatId(track.identity).setIsInitSeg(true).setContentLength(3).build().toByteArray());
        fails("incomplete_media", concat(header, part(UMPPartId.MEDIA, new byte[]{1, 2}), part(UMPPartId.MEDIA_END, new byte[]{1})));
    }

    @Test public void anUnknownMediaHeaderIsRejected() {
        fails("unknown_media_header", part(UMPPartId.MEDIA, new byte[]{1, 2}));
    }

    @Test public void anUnfinishedHeaderIsNotCleanEof() {
        byte[] header = part(UMPPartId.MEDIA_HEADER, MediaHeader.newBuilder().setHeaderId(1)
                .setVideoId(VIDEO).setFormatId(track.identity).setIsInitSeg(true).build().toByteArray());
        assertThrows(IOException.class, () -> read(header, state()));
    }

    @Test public void anAttestationDemandIsTerminal() {
        fails("stream_protection", part(UMPPartId.STREAM_PROTECTION_STATUS, StreamProtectionStatus
                .newBuilder().setStatus(StreamProtectionStatus.Status.ATTESTATION_REQUIRED)
                .build().toByteArray()));
    }

    /**
     * ATTESTATION_PENDING arrives alongside media that is being served normally - measured on the
     * iOS client, 2026-09-08, which returned status 2 with 133,605 bytes of media in the same
     * response that VISIONOS answered with status 1.
     */
    @Test public void aPendingOrUnknownProtectionVerdictDoesNotStopServedMedia() throws Exception {
        for (int status : new int[]{0, 1, 2, 4}) {
            SabrProtocol.ResponseInput response = read(concat(
                    part(UMPPartId.STREAM_PROTECTION_STATUS, StreamProtectionStatus.newBuilder()
                            .setStatusValue(status).build().toByteArray()),
                    segment(track, 1, true, 0, 0, new byte[]{1, 2})), state());
            assertTrue("status " + status, response.cleanEof);
            assertTrue("status " + status, response.result.initialized);
        }
    }

    @Test public void redirectsReloadsAndServerErrorsAreTerminal() {
        fails("server_redirect", part(UMPPartId.SABR_REDIRECT, new byte[0]));
        fails("response_reload_required", part(UMPPartId.RELOAD_PLAYER_RESPONSE, new byte[0]));
        fails("server_error", part(UMPPartId.SABR_ERROR, new byte[0]));
    }

    @Test public void oversizedControlsAreRejectedBeforeProtobufAllocation() {
        fails("control_limit", concat(varint(UMPPartId.NEXT_REQUEST_POLICY), varint(SabrProtocol.MAX_CONTROL_BYTES + 1)));
    }

    @Test public void duplicateHeadersAreRejected() {
        byte[] header = part(UMPPartId.MEDIA_HEADER, MediaHeader.newBuilder().setHeaderId(1)
                .setVideoId(VIDEO).setFormatId(track.identity).setIsInitSeg(true).build().toByteArray());
        fails("duplicate_or_excessive_headers", concat(header, header));
    }

    @Test public void policyBackoffIsBoundedAndNoVideoMismatchIsAccepted() {
        fails("unsupported_backoff", part(UMPPartId.NEXT_REQUEST_POLICY,
                NextRequestPolicy.newBuilder().setBackoffTimeMs(20001).build().toByteArray()));
        fails("unexpected_policy_video", part(UMPPartId.NEXT_REQUEST_POLICY,
                NextRequestPolicy.newBuilder().setVideoId("abcdefghijk").build().toByteArray()));
    }

    @Test public void contextPolicyHonorsFalseStopAndDiscard() throws Exception {
        SabrContextUpdate update = SabrContextUpdate.newBuilder().setType(8)
                .setValue(ByteString.copyFromUtf8("synthetic"))
                .setWritePolicy(SabrContextUpdate.SabrContextWritePolicy.SABR_CONTEXT_WRITE_POLICY_OVERWRITE)
                .setSendByDefault(false).build();
        SabrProtocol.ResponseInput first = read(part(UMPPartId.SABR_CONTEXT_UPDATE, update.toByteArray()), state());
        assertFalse(first.result.sendContexts.contains(8));
        first.result.sendContexts.add(8);
        SabrProtocol.ResponseInput stop = read(part(UMPPartId.SABR_CONTEXT_SENDING_POLICY,
                SabrContextSendingPolicy.newBuilder().addStopPolicy(8).build().toByteArray()), first.result);
        assertFalse(stop.result.sendContexts.contains(8));
        assertTrue(stop.result.contexts.containsKey(8));
        SabrProtocol.ResponseInput discard = read(part(UMPPartId.SABR_CONTEXT_SENDING_POLICY,
                SabrContextSendingPolicy.newBuilder().addDiscardPolicy(8).build().toByteArray()), stop.result);
        assertFalse(discard.result.contexts.containsKey(8));
    }

    @Test public void unknownPartIsNotSilentlyConvertedToEndOfStream() { fails("unsupported_part", part(119, new byte[0])); }

    @Test public void requestNumberingPreservesAllOtherEncodedQueryBytes() {
        android.net.Uri endpoint = android.net.Uri.parse("https://fixture.googlevideo.com/videoplayback?"
                + "signed=a+b%2Fc%2fd&rn=99&empty=&same=one&same=two&encoded=%25%3D");
        assertEquals("signed=a+b%2Fc%2fd&empty=&same=one&same=two&encoded=%25%3D&rn=2",
                SabrMediaSource.numberedUri(endpoint, 2).getEncodedQuery());
    }

    private SabrProtocol.State state() { return new SabrProtocol.State(0, info.durationUs); }
    private SabrProtocol.ResponseInput read(byte[] wire, SabrProtocol.State before) throws IOException {
        SabrProtocol.ResponseInput response = new SabrProtocol.ResponseInput(new ByteArrayInputStream(wire), info, track, before);
        drain(response);
        return response;
    }
    private static byte[] drain(SabrProtocol.ResponseInput response) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[13];
        int read;
        while ((read = response.read(buffer)) != -1) bytes.write(buffer, 0, read);
        return bytes.toByteArray();
    }
    private void fails(String reason, byte[] wire) {
        SabrException error = assertThrows(SabrException.class, () -> read(wire, state()));
        assertEquals(reason, error.reason);
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("private-fixture"));
    }
}
