package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class NotificationArtworkTest {
    @Test
    public void notificationRebuildsKeepTheDownloadAndDeliverToLatestCallback() {
        NotificationArtwork<String> art = new NotificationArtwork<>();
        List<String> callbacks = new ArrayList<>();
        long request = art.request("video-a", image -> callbacks.add("old:" + image));
        for (int i = 0; i < 10; i++) {
            assertEquals(0, art.request("video-a", image -> callbacks.add("latest:" + image)));
        }

        assertTrue(art.complete(request, "video-a", "bitmap"));
        assertEquals(List.of("latest:bitmap"), callbacks);
        assertEquals("bitmap", art.get("video-a"));
        assertNull(art.get("video-b"));
    }

    @Test
    public void switchingVideosCannotPublishOldArtworkOrOldCallbacks() {
        NotificationArtwork<String> art = new NotificationArtwork<>();
        List<String> callbacks = new ArrayList<>();
        long oldRequest = art.request("video-a", callbacks::add);
        long newRequest = art.request("video-b", callbacks::add);

        assertFalse(art.complete(oldRequest, "video-b", "old-bitmap"));
        assertNull(art.get("video-b"));
        assertTrue(art.complete(newRequest, "video-b", "new-bitmap"));
        assertEquals(List.of("new-bitmap"), callbacks);
    }

    @Test
    public void sameUrlAfterReleaseDoesNotReviveCancelledRequest() {
        NotificationArtwork<String> art = new NotificationArtwork<>();
        List<String> callbacks = new ArrayList<>();
        long oldRequest = art.request("video-a", callbacks::add);
        art.clear();
        long newRequest = art.request("video-a", callbacks::add);

        assertFalse(art.complete(oldRequest, "video-a", "obsolete"));
        assertTrue(art.complete(newRequest, "video-a", "current"));
        assertEquals(List.of("current"), callbacks);
    }

    @Test
    public void failedRequestCanRetryAndLateFailureCannotCancelNewRequest() {
        NotificationArtwork<String> art = new NotificationArtwork<>();
        long failed = art.request("video-a", image -> {});
        art.fail(failed);
        long retry = art.request("video-a", image -> {});

        assertTrue(retry > failed);
        art.fail(failed);
        assertTrue(art.complete(retry, "video-a", "retried"));
        assertEquals("retried", art.get("video-a"));
    }

    @Test
    public void switchingOrReleasingDropsThePreviousBitmap() {
        NotificationArtwork<String> art = new NotificationArtwork<>();
        art.complete(art.request("video-a", image -> {}), "video-a", "old");
        art.request("video-b", image -> {});
        assertNull(art.get("video-a"));
        assertNull(art.get("video-b"));
        art.clear();
        assertNull(art.get("video-b"));
    }

    @Test
    public void presenterSwitchBeforeNextArtworkRequestSuppressesPreviousCallback() {
        NotificationArtwork<String> art = new NotificationArtwork<>();
        List<String> callbacks = new ArrayList<>();
        long previous = art.request("video-a", callbacks::add);

        // Selection is already B, but its reset/notification events have not called request(B) yet.
        assertFalse(art.complete(previous, "video-b", "old-bitmap"));
        assertTrue(callbacks.isEmpty());
        assertNull(art.get("video-a"));

        long current = art.request("video-b", callbacks::add);
        assertTrue(art.complete(current, "video-b", "current-bitmap"));
        assertEquals(List.of("current-bitmap"), callbacks);
    }

    @Test
    public void clearingSelectedVideoBeforeNextNotificationCannotPublishArtwork() {
        NotificationArtwork<String> art = new NotificationArtwork<>();
        List<String> callbacks = new ArrayList<>();
        long previous = art.request("video-a", callbacks::add);
        assertFalse(art.complete(previous, null, "old-bitmap"));
        assertTrue(callbacks.isEmpty());
        assertNull(art.get("video-a"));
    }
}
