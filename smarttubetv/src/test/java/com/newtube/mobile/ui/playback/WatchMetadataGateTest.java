package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;

import org.junit.Test;

import java.lang.reflect.Proxy;

public class WatchMetadataGateTest {
    @Test
    public void coldOpenDefersMetadataUntilFirstFrameThenConsumesIt() {
        WatchMetadataGate gate = new WatchMetadataGate();
        MediaItemMetadata metadata = metadata("first");
        gate.open("first");
        assertNull(gate.offer(metadata));
        assertSame(metadata, gate.release());
        assertNull(gate.release());
    }

    @Test
    public void relatedSwitchWaitsForItsOwnFrameEvenAfterPreviousVideoPlayed() {
        WatchMetadataGate gate = new WatchMetadataGate();
        gate.open("first");
        gate.release();
        gate.open("second");
        MediaItemMetadata second = metadata("second");
        assertNull(gate.offer(second));
        assertSame(second, gate.release());
    }

    @Test
    public void lateMetadataFromPreviousVideoCannotOverwriteSelectedVideo() {
        WatchMetadataGate gate = new WatchMetadataGate();
        gate.open("first");
        gate.offer(metadata("first"));
        gate.open("second");
        assertNull(gate.offer(metadata("first")));
        assertNull(gate.release());
        assertNull(gate.offer(metadata("first")));
        MediaItemMetadata second = metadata("second");
        assertSame(second, gate.offer(second));
    }

    @Test
    public void firstFrameBeforeMetadataAllowsImmediateBindWithoutRetainingDocument() {
        WatchMetadataGate gate = new WatchMetadataGate();
        gate.open("first");
        assertNull(gate.release());
        MediaItemMetadata metadata = metadata("first");
        assertSame(metadata, gate.offer(metadata));
        assertNull(gate.release());
    }

    @Test
    public void latestDocumentWinsWhenSeveralUpdatesArriveBeforeFrame() {
        WatchMetadataGate gate = new WatchMetadataGate();
        gate.open("first");
        gate.offer(metadata("first"));
        MediaItemMetadata latest = metadata("first");
        gate.offer(latest);
        assertSame(latest, gate.release());
    }

    private static MediaItemMetadata metadata(String videoId) {
        return (MediaItemMetadata) Proxy.newProxyInstance(MediaItemMetadata.class.getClassLoader(),
                new Class<?>[] {MediaItemMetadata.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getVideoId")) {
                        return videoId;
                    }
                    throw new AssertionError("Unexpected metadata read: " + method.getName());
                });
    }
}
