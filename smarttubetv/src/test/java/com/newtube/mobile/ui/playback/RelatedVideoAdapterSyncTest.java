package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Application;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies {@link RelatedVideoAdapter#refreshItems} rebinds Up-next rows mutated in place
 * ({@code VideoGroup.ACTION_SYNC}, such as DeArrow title and thumbnail overrides).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class RelatedVideoAdapterSyncTest {

    private ContextThemeWrapper themed;

    @Before
    public void setUp() {
        Application context = RuntimeEnvironment.getApplication();
        themed = new ContextThemeWrapper(context, R.style.Theme_NewTube);
    }

    @Test
    public void refreshItems_rebindsInPlaceDeArrowTitleMutation() {
        RelatedVideoAdapter adapter = new RelatedVideoAdapter((v, thumb) -> { });
        AtomicInteger changedCount = new AtomicInteger();
        AtomicReference<Object> lastPayload = new AtomicReference<>();
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onItemRangeChanged(int positionStart, int itemCount, @Nullable Object payload) {
                if (positionStart == 0 && itemCount == 1) {
                    changedCount.incrementAndGet();
                    lastPayload.set(payload);
                }
            }
        });

        Video video = new Video();
        video.videoId = "abc123";
        video.title = "YOU WON'T BELIEVE THIS!!!";
        video.author = "Creator";
        video.cardImageUrl = "https://i.ytimg.com/vi/abc123/hqdefault.jpg";

        adapter.submitList(Collections.singletonList(video));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        FrameLayout parent = new FrameLayout(themed);
        RelatedVideoAdapter.RelatedViewHolder holder = adapter.onCreateViewHolder(parent, 0);
        adapter.onBindViewHolder(holder, 0);

        TextView titleView = holder.itemView.findViewById(R.id.related_title);
        assertEquals("YOU WON'T BELIEVE THIS!!!", titleView.getText().toString());

        // Mutate the existing Video instance in place as DeArrowProcessor does on ACTION_SYNC.
        video.deArrowTitle = "How battery anodes degrade";
        video.altCardImageUrl = "https://dearrow.example/thumb.jpg";

        adapter.refreshItems(Collections.singletonList(video));

        assertEquals(1, changedCount.get());
        assertNotNull(lastPayload.get());

        adapter.onBindViewHolder(holder, 0, Collections.singletonList(lastPayload.get()));
        assertEquals("How battery anodes degrade", titleView.getText().toString());
    }

    @Test
    public void refreshItems_ignoresNullAndUnknownVideos() {
        RelatedVideoAdapter adapter = new RelatedVideoAdapter((v, thumb) -> { });
        AtomicInteger changedCount = new AtomicInteger();
        adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onItemRangeChanged(int positionStart, int itemCount, @Nullable Object payload) {
                changedCount.incrementAndGet();
            }
        });

        Video shown = new Video();
        shown.videoId = "shown";
        shown.title = "Shown";
        adapter.submitList(Collections.singletonList(shown));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        Video other = new Video();
        other.videoId = "other";
        other.title = "Other";

        adapter.refreshItems(null);
        adapter.refreshItems(Collections.singletonList(other));
        assertEquals(0, changedCount.get());
    }
}
