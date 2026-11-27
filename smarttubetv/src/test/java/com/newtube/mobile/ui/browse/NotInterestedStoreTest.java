package com.newtube.mobile.ui.browse;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

/**
 * Tests {@link NotInterestedStore} persistence, bounds eviction, and section scoping.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class NotInterestedStoreTest {

    private Application context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        NotInterestedStore.clear(context);
    }

    @After
    public void tearDown() {
        NotInterestedStore.clear(context);
    }

    @Test
    public void persistsDismissedVideosAcrossCacheReset() {
        NotInterestedStore.add(context, "vid_1");
        NotInterestedStore.add(context, "vid_2");
        assertTrue(NotInterestedStore.contains(context, "vid_1"));
        assertTrue(NotInterestedStore.contains(context, "vid_2"));
        assertFalse(NotInterestedStore.contains(context, "vid_3"));

        NotInterestedStore.resetInMemoryCacheForTest();
        assertTrue(NotInterestedStore.contains(context, "vid_1"));
        assertTrue(NotInterestedStore.contains(context, "vid_2"));
    }

    @Test
    public void ignoresNullAndEmptyVideoIds() {
        NotInterestedStore.add(context, null);
        NotInterestedStore.add(context, "");
        assertFalse(NotInterestedStore.contains(context, null));
        assertFalse(NotInterestedStore.contains(context, ""));
        assertFalse(NotInterestedStore.contains(null, "vid_1"));
    }

    @Test
    public void evictsOldestWhenOverCapacity() {
        for (int i = 0; i < NotInterestedStore.MAX_ENTRIES + 5; i++) {
            NotInterestedStore.add(context, "vid_" + i);
        }
        assertFalse(NotInterestedStore.contains(context, "vid_0"));
        assertFalse(NotInterestedStore.contains(context, "vid_4"));
        assertTrue(NotInterestedStore.contains(context, "vid_5"));
        assertTrue(NotInterestedStore.contains(context, "vid_" + (NotInterestedStore.MAX_ENTRIES + 4)));
    }

    @Test
    public void scopesFilteringToRecommendationSections() {
        assertTrue(NotInterestedStore.isRecommendationSection(MediaGroup.TYPE_HOME));
        assertTrue(NotInterestedStore.isRecommendationSection(MediaGroup.TYPE_SHORTS));
        assertFalse(NotInterestedStore.isRecommendationSection(MediaGroup.TYPE_HISTORY));
        assertFalse(NotInterestedStore.isRecommendationSection(MediaGroup.TYPE_SUBSCRIPTIONS));
        assertFalse(NotInterestedStore.isRecommendationSection(MediaGroup.TYPE_USER_PLAYLISTS));
    }
}
