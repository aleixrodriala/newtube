package com.newtube.mobile.ui.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.app.Application;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.app.views.SearchView;
import com.newtube.mobile.ui.playback.MiniPlayerBridge;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.Collections;

/**
 * Tests {@link SearchResultsCache} and {@link MiniPlayerBridge} host destination retention when
 * Search is background-destroyed while a video plays.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SearchReturnStateTest {

    @Before
    public void setUp() {
        SearchResultsCache.clear();
        MiniPlayerBridge.resetForTest();
    }

    @After
    public void tearDown() {
        SearchResultsCache.clear();
        MiniPlayerBridge.resetForTest();
    }

    @Test
    public void nonFinishingUnregisterKeepsSearchDestination_finishingClearsToHome() {
        MiniPlayerBridge.MiniHost searchHost = new MiniPlayerBridge.MiniHost() {
            @Override
            public boolean prepareMiniPlayerForHandoff(Runnable onDrawn) {
                return false;
            }

            @Override
            public Class<?> getMiniHostViewClass() {
                return SearchView.class;
            }

            @Override
            public int getMiniCardBottomOffsetPx() {
                return 0;
            }
        };

        MiniPlayerBridge.registerMiniHost(searchHost);
        assertEquals(SearchView.class, MiniPlayerBridge.getMinimizeDestinationViewClass());

        // OS destroyed Search under memory pressure while player was in front (isFinishing == false).
        MiniPlayerBridge.unregisterMiniHost(searchHost, false);
        assertEquals(SearchView.class, MiniPlayerBridge.getMinimizeDestinationViewClass());

        // Re-register when Search recreates, then user exits Search (isFinishing == true).
        MiniPlayerBridge.registerMiniHost(searchHost);
        MiniPlayerBridge.unregisterMiniHost(searchHost, true);
        assertEquals(BrowseView.class, MiniPlayerBridge.getMinimizeDestinationViewClass());
    }

    @Test
    public void searchResultsCacheStoresMatchesByQueryAndClears() {
        Video v = new Video();
        v.videoId = "v1";
        v.title = "Result";

        SearchResultsCache.put("android", Collections.singletonList(v), null);

        SearchResultsCache.Entry hit = SearchResultsCache.get("android");
        assertNotNull(hit);
        assertEquals("android", hit.query);
        assertEquals(1, hit.videos.size());
        assertEquals("v1", hit.videos.get(0).videoId);

        assertNull(SearchResultsCache.get("other"));

        SearchResultsCache.clear();
        assertNull(SearchResultsCache.get("android"));
    }
}
