package com.newtube.mobile.sabrproof.ump;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/** Adapted bounded UMP payload view; closing it never closes the enclosing HTTP response. */
public final class UMPInputStream extends InputStream {
    private final InputStream input;
    private int remaining;

    UMPInputStream(InputStream input, int size) {
        this.input = input;
        remaining = size;
    }

    @Override public int read() throws IOException {
        if (remaining == 0) return -1;
        int value = input.read();
        if (value == -1) throw new EOFException("Truncated UMP payload");
        remaining--;
        return value;
    }

    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (remaining == 0) return -1;
        int count = input.read(bytes, offset, Math.min(length, remaining));
        if (count == -1) throw new EOFException("Truncated UMP payload");
        if (count == 0) {
            bytes[offset] = (byte) read();
            return 1;
        }
        remaining -= count;
        return count;
    }

    @Override public int available() { return remaining; }
}
