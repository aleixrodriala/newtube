package com.newtube.mobile.casting.castv2;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertNotNull;

/**
 * Wire-format tests for the hand-rolled CASTV2 protobuf codec + 4-byte length framing.
 * Pure JVM - no Android classes, no Robolectric.
 */
public class CastMessageCodecTest {

    private static CastMessage sample() {
        return CastMessage.utf8("sender-0", "receiver-0",
                "urn:x-cast:com.google.cast.receiver", "{\"type\":\"GET_STATUS\",\"requestId\":1}");
    }

    private static void assertMessagesEqual(CastMessage expected, CastMessage actual) {
        assertEquals(expected.getProtocolVersion(), actual.getProtocolVersion());
        assertEquals(expected.getSourceId(), actual.getSourceId());
        assertEquals(expected.getDestinationId(), actual.getDestinationId());
        assertEquals(expected.getNamespace(), actual.getNamespace());
        assertEquals(expected.getPayloadType(), actual.getPayloadType());
        assertEquals(expected.getPayloadUtf8(), actual.getPayloadUtf8());
    }

    // ---------------------------------------------------------------------------------
    // Round trips
    // ---------------------------------------------------------------------------------

    @Test
    public void roundTripIsExact() throws IOException {
        CastMessage original = sample();
        CastMessage decoded = CastMessageCodec.decode(CastMessageCodec.encode(original));
        assertMessagesEqual(original, decoded);
    }

    @Test
    public void roundTripSurvivesMultiByteVarintLengths() throws IOException {
        // 300-char payload -> 2-byte length varint; 20000-char payload -> 3-byte length varint.
        for (int size : new int[]{300, 20_000}) {
            StringBuilder payload = new StringBuilder(size);
            for (int i = 0; i < size; i++) {
                payload.append((char) ('a' + (i % 26)));
            }
            CastMessage original = CastMessage.utf8("s", "d", "ns", payload.toString());
            CastMessage decoded = CastMessageCodec.decode(CastMessageCodec.encode(original));
            assertMessagesEqual(original, decoded);
        }
    }

    @Test
    public void roundTripSurvivesNonAsciiPayload() throws IOException {
        CastMessage original = CastMessage.utf8("s", "d", "ns", "{\"title\":\"Canción — 日本語 🎵\"}");
        CastMessage decoded = CastMessageCodec.decode(CastMessageCodec.encode(original));
        assertMessagesEqual(original, decoded);
    }

    /** Locks the exact wire bytes so an encoder refactor can't silently change the protocol. */
    @Test
    public void encodeProducesKnownWireBytes() {
        byte[] encoded = CastMessageCodec.encode(CastMessage.utf8("a", "b", "c", "{}"));
        byte[] expected = {
                0x08, 0x00,             // field 1 (protocol_version), varint 0
                0x12, 0x01, 0x61,       // field 2 (source_id), "a"
                0x1A, 0x01, 0x62,       // field 3 (destination_id), "b"
                0x22, 0x01, 0x63,       // field 4 (namespace), "c"
                0x28, 0x00,             // field 5 (payload_type), varint 0 = STRING
                0x32, 0x02, 0x7B, 0x7D, // field 6 (payload_utf8), "{}"
        };
        assertArrayEquals(expected, encoded);
    }

    // ---------------------------------------------------------------------------------
    // Decoder tolerance
    // ---------------------------------------------------------------------------------

    @Test
    public void decoderSkipsUnknownFieldsOfEveryWireType() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Unknown field BEFORE the known ones: field 15, varint, multi-byte value (150).
        out.write(new byte[]{0x78, (byte) 0x96, 0x01});
        out.write(CastMessageCodec.encode(sample()));
        // field 16, fixed64 (wire type 1): tag 129 -> varint 0x81 0x01, then 8 bytes.
        out.write(new byte[]{(byte) 0x81, 0x01, 1, 2, 3, 4, 5, 6, 7, 8});
        // field 17, length-delimited (wire type 2): tag 138 -> 0x8A 0x01, len 3, 3 bytes.
        out.write(new byte[]{(byte) 0x8A, 0x01, 0x03, 9, 8, 7});
        // field 18, fixed32 (wire type 5): tag 149 -> 0x95 0x01, then 4 bytes.
        out.write(new byte[]{(byte) 0x95, 0x01, 4, 3, 2, 1});

        CastMessage decoded = CastMessageCodec.decode(out.toByteArray());
        assertMessagesEqual(sample(), decoded);
    }

    @Test
    public void decoderToleratesBinaryPayload() throws IOException {
        // We never send BINARY, but must decode it: field 5 = 1, field 7 = bytes.
        byte[] encoded = CastMessageCodec.encode(
                new CastMessage(0, "s", "d", "ns", CastMessage.PAYLOAD_TYPE_BINARY, null, new byte[]{1, 2, 3}));
        CastMessage decoded = CastMessageCodec.decode(encoded);
        assertEquals(CastMessage.PAYLOAD_TYPE_BINARY, decoded.getPayloadType());
        assertNotNull(decoded.getPayloadBinary());
        assertArrayEquals(new byte[]{1, 2, 3}, decoded.getPayloadBinary());
        assertNull(decoded.getPayloadUtf8());
    }

    // ---------------------------------------------------------------------------------
    // Truncation / garbage safety
    // ---------------------------------------------------------------------------------

    @Test
    public void truncatedStringFieldThrows() {
        byte[] full = CastMessageCodec.encode(sample());
        // Cut inside the payload string: must throw, never return a silently-corrupt message.
        byte[] truncated = new byte[full.length - 5];
        System.arraycopy(full, 0, truncated, 0, truncated.length);
        assertThrows(IOException.class, () -> CastMessageCodec.decode(truncated));
    }

    @Test
    public void truncatedVarintThrows() {
        // A lone tag byte with its value missing.
        assertThrows(IOException.class, () -> CastMessageCodec.decode(new byte[]{0x08}));
        // A varint whose continuation bit never clears.
        assertThrows(IOException.class, () -> CastMessageCodec.decode(
                new byte[]{0x08, (byte) 0x80, (byte) 0x80, (byte) 0x80}));
    }

    @Test
    public void lengthFieldLongerThanBufferThrows() {
        // field 2 (string), declared length 100, only 2 bytes present.
        assertThrows(IOException.class, () -> CastMessageCodec.decode(new byte[]{0x12, 0x64, 0x61, 0x62}));
    }

    // ---------------------------------------------------------------------------------
    // Framing (4-byte big-endian length prefix)
    // ---------------------------------------------------------------------------------

    @Test
    public void framedRoundTripOverStreamPair() throws IOException {
        CastMessage first = sample();
        CastMessage second = CastMessage.utf8("sender-0", "transport-1",
                "urn:x-cast:com.google.cast.media", "{\"type\":\"PLAY\",\"mediaSessionId\":7,\"requestId\":2}");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CastMessageCodec.writeFramed(out, first);
        CastMessageCodec.writeFramed(out, second);

        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        assertMessagesEqual(first, CastMessageCodec.readFramed(in));
        assertMessagesEqual(second, CastMessageCodec.readFramed(in));
        assertNull("clean EOF at a frame boundary must read as null", CastMessageCodec.readFramed(in));
    }

    @Test
    public void framePrefixIsFourByteBigEndian() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CastMessage message = CastMessage.utf8("a", "b", "c", "{}");
        CastMessageCodec.writeFramed(out, message);
        byte[] framed = out.toByteArray();
        int bodyLength = CastMessageCodec.encode(message).length;
        assertEquals(bodyLength + 4, framed.length);
        assertEquals(0, framed[0]);
        assertEquals(0, framed[1]);
        assertEquals(0, framed[2]);
        assertEquals(bodyLength, framed[3] & 0xFF);
    }

    @Test
    public void oversizedFrameLengthIsRejectedBeforeAllocation() {
        // Length prefix claims 16 MB - the OOM guard must reject it without reading further.
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[]{0x01, 0x00, 0x00, 0x00});
        assertThrows(IOException.class, () -> CastMessageCodec.readFramed(in));
    }

    @Test
    public void zeroFrameLengthIsRejected() {
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[]{0, 0, 0, 0});
        assertThrows(IOException.class, () -> CastMessageCodec.readFramed(in));
    }

    @Test
    public void midFrameEofThrowsInsteadOfReturningNull() {
        // Header promises 100 bytes, stream dies after 10: that's a desync, not a clean close.
        byte[] bytes = new byte[4 + 10];
        bytes[3] = 100;
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        assertThrows(IOException.class, () -> CastMessageCodec.readFramed(in));
    }

    @Test
    public void truncatedFrameHeaderThrows() {
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[]{0, 0});
        assertThrows(IOException.class, () -> CastMessageCodec.readFramed(in));
    }
}
