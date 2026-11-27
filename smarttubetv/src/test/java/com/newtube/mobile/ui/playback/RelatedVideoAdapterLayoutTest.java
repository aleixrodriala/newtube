package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.Collections;

/**
 * Tests {@link WatchLayoutPrefs} and {@link RelatedVideoAdapter} compact vs full-card layouts,
 * verifying untruncated title wrapping and zero horizontal overflow within a 360dp viewport.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class RelatedVideoAdapterLayoutTest {

    private Application context;
    private ContextThemeWrapper themed;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        WatchLayoutPrefs.setRelatedFullCards(context, false);
        themed = new ContextThemeWrapper(context, R.style.Theme_NewTube);
    }

    @After
    public void tearDown() {
        WatchLayoutPrefs.setRelatedFullCards(context, false);
    }

    @Test
    public void preferenceDefaultsToCompactAndPersists() {
        assertFalse(WatchLayoutPrefs.isRelatedFullCards(context));
        WatchLayoutPrefs.setRelatedFullCards(context, true);
        assertTrue(WatchLayoutPrefs.isRelatedFullCards(context));
        WatchLayoutPrefs.setRelatedFullCards(context, false);
        assertFalse(WatchLayoutPrefs.isRelatedFullCards(context));
    }

    @Test
    public void compactAndFullCardsRenderUntruncatedTitlesWithoutHorizontalOverflow() {
        Video video = new Video();
        video.videoId = "abc123";
        video.title = "Very Long Video Title Without Any Truncation That Must Wrap Cleanly Across Multiple Lines Inside Both Compact Rows And Full Cards Without Overflowing Horizontally On Narrow Screens";
        video.secondTitle = "Very Long Channel Name • 1.2M views • 2 weeks ago • Extra Metadata Segment";

        RelatedVideoAdapter adapter = new RelatedVideoAdapter((v, thumb) -> {});
        adapter.submitList(Collections.singletonList(video));

        int widthPx = Math.round(360 * themed.getResources().getDisplayMetrics().density);
        FrameLayout parent = new FrameLayout(themed);

        // Compact mode
        assertFalse(adapter.isFullCards());
        assertEquals(0, adapter.getItemViewType(0));
        RelatedVideoAdapter.RelatedViewHolder compactHolder =
                adapter.onCreateViewHolder(parent, adapter.getItemViewType(0));
        adapter.onBindViewHolder(compactHolder, 0);
        assertLayoutFitsWithoutTitleTruncation(compactHolder.itemView, widthPx, video.title);

        // Full cards mode
        adapter.setFullCards(true);
        assertTrue(adapter.isFullCards());
        assertEquals(1, adapter.getItemViewType(0));
        RelatedVideoAdapter.RelatedViewHolder fullHolder =
                adapter.onCreateViewHolder(parent, adapter.getItemViewType(0));
        adapter.onBindViewHolder(fullHolder, 0);
        assertLayoutFitsWithoutTitleTruncation(fullHolder.itemView, widthPx, video.title);
    }

    private static void assertLayoutFitsWithoutTitleTruncation(View itemView, int widthPx, String expectedTitle) {
        itemView.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        itemView.layout(0, 0, itemView.getMeasuredWidth(), itemView.getMeasuredHeight());

        assertTrue("Card width overflowed viewport: " + itemView.getMeasuredWidth() + " > " + widthPx,
                itemView.getMeasuredWidth() <= widthPx);

        TextView title = itemView.findViewById(R.id.related_title);
        TextView subtitle = itemView.findViewById(R.id.related_subtitle);
        assertEquals(expectedTitle, title.getText().toString());
        assertEquals(Integer.MAX_VALUE, title.getMaxLines());
        assertNull(title.getEllipsize());
        assertTrue("Title overflowed right bound: " + title.getRight() + " > " + widthPx,
                title.getRight() <= widthPx);
        assertTrue("Subtitle overflowed right bound: " + subtitle.getRight() + " > " + widthPx,
                subtitle.getRight() <= widthPx);
    }
}
