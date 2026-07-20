package com.newtube.mobile.casting.proxy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SegmentTokenTest {
    private static final String GOOGLEVIDEO_URL =
            "https://rr4---sn-25ge7nsd.googlevideo.com/videoplayback?expire=1721400000&ei=abcDEF"
                    + "&ip=203.0.113.7&id=o-AB_cd-EF&itag=137&source=youtube&mime=video%2Fmp4"
                    + "&sig=AJfQdSswRQIgXyZ%3D%3D&lsparams=met,mh,mm&mt=1721390000";

    @Test
    public void roundTripsGooglevideoUrl() {
        String token = SegmentToken.encode(GOOGLEVIDEO_URL);
        assertEquals(GOOGLEVIDEO_URL, SegmentToken.decode(token));
    }

    @Test
    public void tokenIsUrlSafeAndUnpadded() {
        // A payload length that would normally produce '=' padding and '+'/'/' chars.
        String token = SegmentToken.encode("https://a.googlevideo.com/v?x=ÿþ~~~?");
        assertFalse(token.contains("="));
        assertFalse(token.contains("+"));
        assertFalse(token.contains("/"));
        for (int length = 0; length < 8; length++) {
            String url = "https://g/" + "abcdefgh".substring(0, length);
            assertEquals(url, SegmentToken.decode(SegmentToken.encode(url)));
        }
    }

    @Test
    public void decodeRejectsGarbage() {
        assertNull(SegmentToken.decode(null));
        assertNull(SegmentToken.decode(""));
        assertNull(SegmentToken.decode("not*base64!"));
        assertNull(SegmentToken.decode("aaaaa")); // length % 4 == 1 is never a valid encoding
        assertNull(SegmentToken.decode("abc def"));
    }

    @Test
    public void allowlistAcceptsYoutubeInfrastructureOnly() {
        assertTrue(SegmentToken.isAllowedUpstream(GOOGLEVIDEO_URL));
        assertTrue(SegmentToken.isAllowedUpstream("https://www.youtube.com/api/timedtext?v=abc"));
        assertTrue(SegmentToken.isAllowedUpstream("http://redirector.googlevideo.com/x"));

        assertFalse(SegmentToken.isAllowedUpstream("https://evil.example.com/videoplayback"));
        assertFalse(SegmentToken.isAllowedUpstream("https://googlevideo.com.evil.example/x"));
        assertFalse(SegmentToken.isAllowedUpstream("https://notgooglevideo.com/x"));
        assertFalse(SegmentToken.isAllowedUpstream("ftp://rr4.googlevideo.com/x"));
        assertFalse(SegmentToken.isAllowedUpstream("https://127.0.0.1/videoplayback"));
        assertFalse(SegmentToken.isAllowedUpstream(null));
        assertFalse(SegmentToken.isAllowedUpstream("::::not a url"));
    }
}
