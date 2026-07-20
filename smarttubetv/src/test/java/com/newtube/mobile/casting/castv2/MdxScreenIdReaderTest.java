package com.newtube.mobile.casting.castv2;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * mdxSessionStatus parsing - the one payload the mdx shim exists for.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34) // newest SDK that runs on Java 17
// conscrypt-android on the app classpath breaks Robolectric's crypto setup; not needed here.
@ConscryptMode(ConscryptMode.Mode.OFF)
public class MdxScreenIdReaderTest {

    @Test
    public void parsesScreenIdFromMdxSessionStatus() throws Exception {
        // Real-world shape (ytcast/pychromecast): data carries screenId + deviceId and friends.
        JSONObject payload = new JSONObject("{"
                + "\"type\":\"mdxSessionStatus\","
                + "\"data\":{"
                + "  \"screenId\":\"very-long-opaque-screen-id-123\","
                + "  \"deviceId\":\"ff-ee-dd\","
                + "  \"loungeToken\":null"
                + "}}");
        assertEquals("very-long-opaque-screen-id-123", MdxScreenIdReader.parseScreenId(payload));
    }

    @Test
    public void wrongMessageTypeReturnsNull() throws Exception {
        JSONObject payload = new JSONObject(
                "{\"type\":\"somethingElse\",\"data\":{\"screenId\":\"x\"}}");
        assertNull(MdxScreenIdReader.parseScreenId(payload));
    }

    @Test
    public void missingDataReturnsNull() throws Exception {
        assertNull(MdxScreenIdReader.parseScreenId(new JSONObject("{\"type\":\"mdxSessionStatus\"}")));
    }

    @Test
    public void emptyScreenIdReturnsNull() throws Exception {
        JSONObject payload = new JSONObject(
                "{\"type\":\"mdxSessionStatus\",\"data\":{\"screenId\":\"\"}}");
        assertNull(MdxScreenIdReader.parseScreenId(payload));
    }
}
