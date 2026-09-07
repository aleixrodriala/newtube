package com.newtube.mobile.casting;

import com.liskovsoft.mediaserviceinterfaces.RemoteControlService;
import com.liskovsoft.mediaserviceinterfaces.data.CastScreen;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class CastFallbackDecisionTest {
    @Test public void adFreeRoutesPrecedeYoutubeRegardlessOfInputOrder() {
        CastTarget youtube = CastTarget.fromCastDeviceYouTubeApp("TV", "192.0.2.1", 8009);
        CastTarget direct = CastTarget.fromCastDevice("TV", "192.0.2.1", 8009);
        CastTarget smart = CastTarget.fromPairedScreen(new CastScreen("smart", "TV"),
                CastTarget.ReceiverApp.SMARTTUBE);
        CastRoutePlan plan = new CastRoutePlan(Arrays.asList(youtube, direct, smart));
        assertSame(smart, plan.next());
        assertSame(direct, plan.next());
        assertSame(youtube, plan.next());
        assertFalse(plan.hasNext());
    }

    @Test public void explicitChoiceHasNoFallback() {
        CastTarget target = CastTarget.fromCastDevice("TV", "192.0.2.1", 8009);
        CastRoutePlan plan = new CastRoutePlan(Collections.singletonList(target));
        assertSame(target, plan.next());
        assertFalse(plan.hasNext());
    }

    @Test public void matchingPausedLoungeLoadNeedsExplicitPlay() {
        assertTrue(CastSessionManager.shouldAutoPlayLoungeLoad("live", "live",
                RemoteControlService.STATE_PAUSED));
        assertFalse(CastSessionManager.shouldAutoPlayLoungeLoad("live", "old-video",
                RemoteControlService.STATE_PAUSED));
        assertFalse(CastSessionManager.shouldAutoPlayLoungeLoad("live", "live",
                RemoteControlService.STATE_PLAYING));
    }
}
