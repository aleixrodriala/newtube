package com.newtube.sabr.ump;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProtocolException;

/** Adapted from tv-legacy's SABR UMPDecoder; see the module PROVENANCE.md. No transport. */
public final class UMPDecoder {
    private final int maxPartBytes;

    public UMPDecoder(int maxPartBytes) {
        if (maxPartBytes <= 0) throw new IllegalArgumentException("Invalid part limit");
        this.maxPartBytes = maxPartBytes;
    }

    public UMPPart decode(InputStream input) throws IOException {
        long type = readVarInt(input);
        if (type == -1) return null;
        long size = readVarInt(input);
        if (size == -1) throw new EOFException("Missing UMP part size");
        if (type > Integer.MAX_VALUE || size > maxPartBytes) {
            throw new ProtocolException("UMP part exceeds bound");
        }
        return new UMPPart((int) type, (int) size, input);
    }

    /** UMP's 1–5 byte integer encoding, not protobuf's base-128 varint. */
    public long readVarInt(InputStream input) throws IOException {
        int first = input.read();
        if (first == -1) return -1;
        int size = first < 128 ? 1 : first < 192 ? 2 : first < 224 ? 3 : first < 240 ? 4 : 5;
        int shift = size == 5 ? 0 : 8 - size;
        long value = size == 5 ? 0 : first & ((1 << shift) - 1);
        for (int index = 1; index < size; index++) {
            int next = input.read();
            if (next == -1) throw new EOFException("Truncated UMP integer");
            value |= (long) next << shift;
            shift += 8;
        }
        return value;
    }
}
