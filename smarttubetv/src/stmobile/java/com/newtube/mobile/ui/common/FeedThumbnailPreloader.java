package com.newtube.mobile.ui.common;

import android.content.Context;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.request.target.Target;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.newtube.mobile.ui.browse.VideoCardAdapter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * NEWTUBE(scroll): fetches and decodes the thumbnails of the next few feed cards before they
 * scroll into view, so a card arrives with its picture instead of as a grey box that fades in.
 *
 * <p>Glide only starts a card's image when RecyclerView binds it, i.e. about one card ahead of
 * the viewport; on a fling a full-width card needs its 30-110 KB thumbnail within a frame or two
 * of appearing, so every new card showed the placeholder first. This asks for the next
 * {@link #AHEAD} cards in the scroll direction with the card's exact request
 * ({@link VideoCardAdapter.VideoViewHolder#thumbnailRequest}), which lands the decoded bitmap in
 * Glide's memory cache under the key the bind uses - the bind then paints synchronously and skips
 * the cross-fade.</p>
 *
 * <p>Bounded on purpose: preloads run at {@link Priority#LOW} so on-screen cards always win the
 * two image threads, and only the most recent {@link #MAX_OUTSTANDING} are kept - older ones are
 * cancelled, so a long fling does not leave a queue of images for cards already flown past.</p>
 */
public final class FeedThumbnailPreloader extends RecyclerView.OnScrollListener {
    /** Cards to prepare ahead of the viewport: ~1.5 screens of full-width portrait cards. */
    static final int AHEAD = 4;
    private static final int MAX_OUTSTANDING = AHEAD * 2;

    /** Maps a grid position to the card it shows, or null (header/footer rows). */
    public interface CardAt {
        Video cardAt(int gridPosition);
    }

    /** Where a preload goes; the Glide one in production, a recorder in tests. */
    interface Sink {
        /** Starts a preload for {@code video}; returns a handle for {@link #cancel}, or null. */
        Object preload(Video video);

        void cancel(Object handle);
    }

    private final CardAt mCards;
    private final Sink mSink;
    private final ArrayDeque<Object[]> mOutstanding = new ArrayDeque<>(); // {url, handle}
    /** URLs with a live (or finished) preload - a small scroll must not re-request them. */
    private final Set<String> mRequested = new HashSet<>();
    private final UrlOf mUrlOf;

    interface UrlOf {
        String url(Video video);
    }

    FeedThumbnailPreloader(CardAt cards, UrlOf urlOf, Sink sink) {
        mCards = cards;
        mUrlOf = urlOf;
        mSink = sink;
    }

    /** Grid whose adapter is the card adapter itself (card position == grid position). */
    public static FeedThumbnailPreloader attach(RecyclerView grid, VideoCardAdapter adapter) {
        return attach(grid, position -> position >= 0 && position < adapter.getItemCount()
                ? adapter.getCurrentList().get(position) : null);
    }

    public static FeedThumbnailPreloader attach(RecyclerView grid, CardAt cards) {
        Context context = grid.getContext();
        RequestManager glide = Glide.with(context);
        FeedThumbnailPreloader preloader = new FeedThumbnailPreloader(cards,
                video -> VideoCardAdapter.VideoViewHolder.thumbnailUrl(context, video),
                new Sink() {
                    @Override
                    public Object preload(Video video) {
                        return VideoCardAdapter.VideoViewHolder.thumbnailRequest(glide, context, video)
                                .priority(Priority.LOW)
                                .preload();
                    }

                    @Override
                    @SuppressWarnings("unchecked")
                    public void cancel(Object handle) {
                        glide.clear((Target<Drawable>) handle);
                    }
                });
        grid.addOnScrollListener(preloader);
        return preloader;
    }

    @Override
    public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
        // Also called with dy == 0 after a layout that changed the visible range (first content,
        // a list swap), which prepares the cards just below the first screen.
        RecyclerView.LayoutManager manager = recyclerView.getLayoutManager();
        RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
        if (!(manager instanceof LinearLayoutManager) || adapter == null) {
            return;
        }
        LinearLayoutManager layout = (LinearLayoutManager) manager;
        onViewport(layout.findFirstVisibleItemPosition(), layout.findLastVisibleItemPosition(),
                adapter.getItemCount(), dy < 0);
    }

    void onViewport(int first, int last, int itemCount, boolean scrollingUp) {
        for (int position : window(first, last, itemCount, scrollingUp, AHEAD)) {
            Video video = mCards.cardAt(position);
            if (video == null || video.isChannel() && !video.isPlaylistAsChannel()) {
                continue; // channel rows load a round avatar, not a thumbnail
            }
            String url = mUrlOf.url(video);
            if (url == null || !mRequested.add(url)) {
                continue;
            }
            Object handle = mSink.preload(video);
            mOutstanding.addLast(new Object[]{url, handle});
            if (mOutstanding.size() > MAX_OUTSTANDING) {
                Object[] oldest = mOutstanding.removeFirst();
                mRequested.remove((String) oldest[0]);
                if (oldest[1] != null) {
                    mSink.cancel(oldest[1]);
                }
            }
        }
    }

    /** The {@code ahead} grid positions just past the viewport [first, last] in the scroll direction. */
    static List<Integer> window(int first, int last, int itemCount, boolean scrollingUp, int ahead) {
        List<Integer> positions = new ArrayList<>();
        if (itemCount <= 0 || first < 0 || last < first) {
            return positions;
        }
        if (scrollingUp) {
            for (int position = first - 1; position >= Math.max(0, first - ahead); position--) {
                positions.add(position);
            }
        } else {
            for (int position = last + 1; position <= Math.min(itemCount - 1, last + ahead); position++) {
                positions.add(position);
            }
        }
        return positions;
    }
}
