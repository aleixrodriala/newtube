package com.newtube.mobile.downloads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import android.app.Application;

import java.io.File;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class StreamFetcherTest {
    /**
     * Pixel 09-26: re-downloading a deleted download passed its local file:// card image as the
     * thumbnail URL; OkHttp's request builder threw outside the guard and failed the whole job.
     */
    @Test
    public void aNonHttpThumbnailUrlIsSkippedNotThrown() throws Exception {
        File target = File.createTempFile("thumb", ".jpg");
        //noinspection ResultOfMethodCallIgnored
        target.delete();
        StreamFetcher fetcher = new StreamFetcher("test-agent");

        assertNull(fetcher.fetchSmall("file:///data/user/0/io.github.aleixrodriala.arc/files/downloads/x.jpg", target));
        assertFalse(target.exists());
    }

    @Test
    public void readsTheTotalOutOfContentRange() {
        assertEquals(12345, StreamFetcher.totalFromContentRange("bytes 0-999/12345"));
        assertEquals(-1, StreamFetcher.totalFromContentRange("bytes 0-999/*"));
        assertEquals(-1, StreamFetcher.totalFromContentRange(null));
        assertEquals(-1, StreamFetcher.totalFromContentRange("garbage"));
    }
}
