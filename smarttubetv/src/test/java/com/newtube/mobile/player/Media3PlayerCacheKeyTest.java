package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import android.app.Application;
import android.net.Uri;

import androidx.media3.datasource.DataSpec;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/** Protects disk-cache identity while verifying bounded key reuse across media ranges. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class Media3PlayerCacheKeyTest {
    private final Media3PlayerCache.StableCacheKeyFactory keys =
            new Media3PlayerCache.StableCacheKeyFactory(2);

    @Test
    public void repeatedRangesReuseKeyWithoutRebuildingSignedQuery() {
        String url = media("video", "248", "17", "");
        String first = keys.buildCacheKey(new DataSpec.Builder().setUri(url)
                .setPosition(0).setLength(1_000).build());
        String later = keys.buildCacheKey(new DataSpec.Builder().setUri(Uri.parse(url))
                .setPosition(500_000).setLength(20_000).build());

        assertEquals("yt.video.248.17.", first); // Existing on-disk key remains byte-identical.
        assertSame(first, later);
    }

    @Test
    public void refreshedSignatureAndHostStillReachExistingMediaBytes() {
        String first = keys.buildCacheKey(spec(media("video", "248", "17", "&expire=100&sig=old")));
        String refresh = keys.buildCacheKey(spec(media("video", "248", "17", "&expire=200&sig=new")
                .replace("r1.googlevideo.com", "r2.googlevideo.com")));

        assertEquals(first, refresh);
    }

    @Test
    public void uploadRevisionFormatAndAudioVariantRemainSeparate() {
        String base = keys.buildCacheKey(spec(media("video", "251", "17", "&xtags=en%20original")));
        assertEquals("yt.video.251.17.en original", base);
        assertNotEquals(base, keys.buildCacheKey(spec(media("video", "251", "18", "&xtags=en%20original"))));
        assertNotEquals(base, keys.buildCacheKey(spec(media("video", "140", "17", "&xtags=en%20original"))));
        assertNotEquals(base, keys.buildCacheKey(spec(media("video", "251", "17", "&xtags=es"))));
    }

    @Test
    public void liveSequencesKeepTheirOwnCacheResourcesAcrossQueryAndPathForms() {
        String first = keys.buildCacheKey(spec(media("live", "248", "17", "&sq=70")));
        String next = keys.buildCacheKey(spec(media("live", "248", "17", "&sq=71")));
        String path = keys.buildCacheKey(spec(media("live", "248", "17", "")
                .replace("/videoplayback?", "/videoplayback/sq/70/?")));

        assertEquals("yt.live.248.17..sq70", first);
        assertNotEquals(first, next);
        assertEquals(first, path);
    }

    @Test
    public void genericExplicitKeysDoNotContaminateAnotherRequestForTheSameUri() {
        String url = "https://media.invalid/file";
        assertEquals("first", keys.buildCacheKey(new DataSpec.Builder().setUri(url).setKey("first").build()));
        assertEquals("second", keys.buildCacheKey(new DataSpec.Builder().setUri(url).setKey("second").build()));
        assertEquals(url, keys.buildCacheKey(spec(url)));
    }

    @Test
    public void missingOptionalFieldsKeepOriginalKeyShape() {
        assertEquals("yt.video.248..", keys.buildCacheKey(
                spec("https://r1.googlevideo.com/videoplayback?id=video&itag=248")));
    }

    @Test
    public void boundedMemoEvictsOldUrisAndKeepsRecentlyUsedRanges() {
        DataSpec first = spec(media("first", "248", "17", ""));
        DataSpec second = spec(media("second", "248", "17", ""));
        String firstKey = keys.buildCacheKey(first);
        String secondKey = keys.buildCacheKey(second);
        assertSame(firstKey, keys.buildCacheKey(first)); // Refresh LRU order.
        keys.buildCacheKey(spec(media("third", "248", "17", "")));
        assertSame(firstKey, keys.buildCacheKey(first));

        String rebuiltSecond = keys.buildCacheKey(second);
        assertEquals(secondKey, rebuiltSecond);
        assertNotSame(secondKey, rebuiltSecond);
    }

    private static DataSpec spec(String url) {
        return new DataSpec.Builder().setUri(url).build();
    }

    private static String media(String id, String itag, String lmt, String extra) {
        return "https://r1.googlevideo.com/videoplayback?id=" + id
                + "&itag=" + itag + "&lmt=" + lmt + extra;
    }
}
