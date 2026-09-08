package com.newtube.sabr.ump;

import java.io.IOException;
import java.io.InputStream;

/** Adapted from tv-legacy: a single bounded payload view prevents crossing part boundaries. */
public final class UMPPart {
    public final int partId;
    public final int size;
    private final UMPInputStream data;

    UMPPart(int partId, int size, InputStream input) {
        this.partId = partId;
        this.size = size;
        data = new UMPInputStream(input, size);
    }

    public UMPInputStream toStream() { return data; }

    public void skip() throws IOException {
        byte[] discard = new byte[4096];
        while (data.read(discard) != -1) { }
    }
}
