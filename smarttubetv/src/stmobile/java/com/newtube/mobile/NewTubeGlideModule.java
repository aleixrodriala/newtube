package com.newtube.mobile;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.load.engine.cache.MemorySizeCalculator;
import com.bumptech.glide.load.engine.executor.GlideExecutor;
import com.bumptech.glide.load.model.stream.HttpGlideUrlLoader;
import com.bumptech.glide.module.AppGlideModule;
import com.bumptech.glide.request.RequestOptions;

/** Enables annotation-discovered Glide library modules, including the animated-WebP decoder. */
@GlideModule
public final class NewTubeGlideModule extends AppGlideModule {
    /**
     * Image fetching must never crowd out the video stream. Glide sizes its source executor from the
     * CPU count (4 on any current phone), so a feed fling or a freshly opened watch page can hold
     * four concurrent HTTP fetches against googleusercontent while /player and the first media
     * chunks are still in flight - on a narrow link the thumbnails simply win. Two threads still
     * keep a scrolling feed populated.
     */
    private static final int SOURCE_THREADS = 2;

    /**
     * Glide's built-in 2500ms connect+read budget is a LAN default. On a high-RTT mobile link the
     * handshake alone can eat it, so thumbnails fail, the next bind re-requests them, and the result
     * is a request storm whose only visible outcome is permanently grey cards. Fail slowly instead.
     */
    private static final int HTTP_TIMEOUT_MS = 15_000;

    @Override
    public boolean isManifestParsingEnabled() {
        return false;
    }

    @Override
    public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
        builder.setSourceExecutor(GlideExecutor.newSourceBuilder()
                .setThreadCount(SOURCE_THREADS)
                .setName("newtube-glide-source")
                .build());

        builder.setDefaultRequestOptions(
                new RequestOptions().set(HttpGlideUrlLoader.TIMEOUT, HTTP_TIMEOUT_MS));

        // NEWTUBE(scroll): room for one more screen of decoded thumbnails. Glide sizes its memory
        // cache to 2 screens of ARGB pixels (~21 MB on a 1080x2424 phone). A full-width feed card
        // decodes to ~1.3 MB even in RGB_565, so that is ~16 cards - fewer than one feed's bound
        // + view-cached + recycled holders plus the FeedThumbnailPreloader's look-ahead, and a tab
        // switch (the whole grid rebinds) evicted the tab being left: coming back re-decoded its
        // cards from disk and faded them in again. 3 screens keeps both tabs' first screens and
        // the look-ahead hot. Still capped by Glide at 40% of the app's memory class, and the cache
        // is dropped on onTrimMemory like before.
        builder.setMemorySizeCalculator(new MemorySizeCalculator.Builder(context)
                .setMemoryCacheScreens(MEMORY_CACHE_SCREENS));
    }

    private static final float MEMORY_CACHE_SCREENS = 3;
}
