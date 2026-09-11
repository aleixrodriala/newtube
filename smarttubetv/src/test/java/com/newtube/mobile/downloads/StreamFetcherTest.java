package com.newtube.mobile.downloads;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StreamFetcherTest {
    @Test
    public void readsTheTotalOutOfContentRange() {
        assertEquals(12345, StreamFetcher.totalFromContentRange("bytes 0-999/12345"));
        assertEquals(-1, StreamFetcher.totalFromContentRange("bytes 0-999/*"));
        assertEquals(-1, StreamFetcher.totalFromContentRange(null));
        assertEquals(-1, StreamFetcher.totalFromContentRange("garbage"));
    }
}
