package com.newtube.mobile.ui.playback;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemStoryboard;

import io.reactivex.rxjava3.disposables.Disposable;

/** Loads on the first scrub, retaining only the current sprite rather than a bitmap per frame. */
final class StoryboardPreview {
    private final ImageView mView;
    private final MediaItemService mService;
    private Disposable mMetadataRequest;
    private CustomTarget<Bitmap> mTarget;
    private MediaItemStoryboard mStoryboard;
    private Bitmap mSprite;
    private String mVideoId;
    private String mUrl;
    private long mPositionMs;
    private boolean mRequested;
    private boolean mShown;

    StoryboardPreview(ImageView view, MediaItemService service) {
        mView = view;
        mService = service;
    }

    void reset(String videoId) {
        if (mMetadataRequest != null) {
            mMetadataRequest.dispose();
        }
        if (mTarget != null) {
            // onDestroy runs after the Activity is marked destroyed; cleanup needs no Activity manager.
            Glide.with(mView.getContext().getApplicationContext()).clear(mTarget);
        }
        mVideoId = videoId;
        mTarget = null;
        mStoryboard = null;
        mSprite = null;
        mUrl = null;
        mRequested = mShown = false;
        mView.setImageDrawable(null);
        mView.setVisibility(View.GONE);
    }

    void hide() {
        mShown = false;
        mView.setVisibility(View.GONE);
    }

    void show(long positionMs) {
        mPositionMs = Math.max(0, positionMs);
        mShown = true;
        if (!mRequested && mVideoId != null) {
            mRequested = true;
            String videoId = mVideoId;
            mMetadataRequest = mService.getStoryboardObserve(videoId).subscribe(storyboard -> {
                if (videoId.equals(mVideoId)) {
                    mStoryboard = storyboard;
                    update();
                }
            }, error -> { /* A missing preview must never interrupt seeking or playback. */ });
        }
        update();
    }

    private void update() {
        if (!mShown || mStoryboard == null || mStoryboard.getGroupSize() == null) {
            return;
        }
        MediaItemStoryboard.Size size = mStoryboard.getGroupSize();
        if (size.getDurationEachMS() <= 0 || size.getColCount() <= 0 || size.getRowCount() <= 0) {
            return;
        }
        long frames = (long) size.getColCount() * size.getRowCount();
        int group = (int) (mPositionMs / size.getDurationEachMS() / frames);
        String url = mStoryboard.getGroupUrl(group);
        if (url == null) {
            return;
        }
        if (!url.equals(mUrl)) {
            if (mTarget != null) {
                Glide.with(mView).clear(mTarget);
            }
            mUrl = url;
            mSprite = null;
            mView.setVisibility(View.GONE);
            mTarget = new CustomTarget<Bitmap>() {
                @Override
                public void onResourceReady(@NonNull Bitmap bitmap, @Nullable Transition<? super Bitmap> transition) {
                    if (mTarget == this) {
                        mSprite = bitmap;
                        drawFrame();
                    }
                }

                @Override
                public void onLoadCleared(@Nullable Drawable placeholder) {
                    if (mTarget == this) {
                        mSprite = null;
                        mView.setImageDrawable(null);
                        mView.setVisibility(View.GONE);
                    }
                }
            };
            Glide.with(mView).asBitmap().load(url).priority(Priority.IMMEDIATE).into(mTarget);
        }
        drawFrame();
    }

    private void drawFrame() {
        if (!mShown || mSprite == null || mStoryboard == null) {
            return;
        }
        MediaItemStoryboard.Size size = mStoryboard.getGroupSize();
        Rect frame = frameRect(mPositionMs, size.getDurationEachMS(), size.getColCount(),
                size.getRowCount(), mSprite.getWidth(), mSprite.getHeight());
        if (frame != null) {
            mView.setImageDrawable(new Frame(mSprite, frame));
            mView.setVisibility(View.VISIBLE);
        }
    }

    static Rect frameRect(long positionMs, int frameMs, int columns, int rows, int width, int height) {
        if (frameMs <= 0 || columns <= 0 || rows <= 0 || width < columns || height < rows) {
            return null;
        }
        long index = Math.max(0, positionMs) / frameMs % ((long) columns * rows);
        int column = (int) (index % columns);
        int row = (int) (index / columns);
        return new Rect(column * width / columns, row * height / rows,
                (column + 1) * width / columns, (row + 1) * height / rows);
    }

    private static final class Frame extends Drawable {
        private final Bitmap mBitmap;
        private final Rect mSource;
        private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

        Frame(Bitmap bitmap, Rect source) { mBitmap = bitmap; mSource = source; }
        @Override public void draw(@NonNull Canvas canvas) { canvas.drawBitmap(mBitmap, mSource, getBounds(), mPaint); }
        @Override public void setAlpha(int alpha) { mPaint.setAlpha(alpha); }
        @Override public void setColorFilter(@Nullable ColorFilter filter) { mPaint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
