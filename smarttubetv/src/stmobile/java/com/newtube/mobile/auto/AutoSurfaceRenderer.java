package com.newtube.mobile.auto;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.CarContext;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

/**
 * NEWTUBE(android-auto): draws on the surface Android Auto gives a navigation app. A private virtual display
 * wraps that surface and a {@link Presentation} on it hosts a {@link PlayerView} (fit, black bars)
 * under a NewTube-styled overlay: title and channel on top, a red progress bar with times below.
 * Parked, the overlay fades after a few seconds and a tap on the screen brings it back; driving,
 * the screen shows the cover, title and a "sound only" note instead of the picture.
 */
public final class AutoSurfaceRenderer implements SurfaceCallback, AutoPlayer.Listener {
    // NewTube's own colours (colors_mobile.xml): brand red, progress red, near-black surface.
    private static final int BRAND_RED = 0xFFFF0000;
    private static final int PROGRESS_RED = 0xFFFF0033;
    private static final int SURFACE_DARK = 0xFF0F0F0F;
    private static final int TEXT_SECONDARY = 0xFFAAAAAA;
    private static final long OVERLAY_MS = 4_000;
    private static final long TICK_MS = 500;

    private final CarContext mCarContext;
    private final AutoPlayer mPlayer;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @Nullable private VirtualDisplay mDisplay;
    @Nullable private Presentation mPresentation;
    @Nullable private FrameLayout mRoot;
    @Nullable private PlayerView mPlayerView;
    // Driving / no video: cover, title, note.
    @Nullable private View mIdle;
    @Nullable private ImageView mCover;
    @Nullable private TextView mIdleTitle;
    @Nullable private TextView mIdleChannel;
    @Nullable private TextView mNote;
    // Over the picture: top and bottom bars.
    @Nullable private View mTopBar;
    @Nullable private TextView mTopTitle;
    @Nullable private TextView mTopChannel;
    @Nullable private View mBottomBar;
    @Nullable private ProgressBar mProgress;
    @Nullable private TextView mTime;
    @Nullable private TextView mStatus;

    @Nullable private String mCoverUrl;
    private String mParkedHint = "";
    private final Rect mVisible = new Rect();
    private int mWidth;
    private int mHeight;
    private long mOverlayUntil;
    private boolean mTicking;

    public AutoSurfaceRenderer(CarContext carContext) {
        mCarContext = carContext;
        mPlayer = AutoPlayer.get(carContext);
        mPlayer.addListener(this);
    }

    /** Shown under the title while video is off: why, and what to do about it. */
    public void setParkedHint(String hint) {
        mParkedHint = hint != null ? hint : "";
        refresh();
    }

    /** Bring the bars back for a few seconds (a button was pressed, the screen was tapped). */
    public void pokeOverlay() {
        mOverlayUntil = SystemClock.elapsedRealtime() + OVERLAY_MS;
        refresh();
    }

    // ------------------------------------------------------------------ surface

    @Override
    public void onSurfaceAvailable(@NonNull SurfaceContainer container) {
        releaseDisplay();
        if (container.getSurface() == null || container.getWidth() <= 0 || container.getHeight() <= 0) {
            return;
        }
        mWidth = container.getWidth();
        mHeight = container.getHeight();
        DisplayManager dm = (DisplayManager) mCarContext.getSystemService(Context.DISPLAY_SERVICE);
        mDisplay = dm.createVirtualDisplay("NewTubeAuto", mWidth, mHeight, container.getDpi(),
                container.getSurface(), 0);
        mPresentation = new Presentation(mCarContext, mDisplay.getDisplay(),
                android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        mPresentation.setContentView(buildViews(mPresentation.getContext()));
        try {
            mPresentation.show();
        } catch (Throwable error) {
            mPresentation = null;
        }
        applyVisibleArea();
        pokeOverlay();
        startTicking();
    }

    @Override
    public void onVisibleAreaChanged(@NonNull Rect visibleArea) {
        mVisible.set(visibleArea);
        applyVisibleArea();
    }

    @Override
    public void onStableAreaChanged(@NonNull Rect stableArea) {
    }

    @Override
    public void onSurfaceDestroyed(@NonNull SurfaceContainer container) {
        releaseDisplay();
    }

    /** A tap on the picture (car API 5+): show or hide the bars. */
    @Override
    public void onClick(float x, float y) {
        if (overlayShowing()) {
            mOverlayUntil = 0;
            refresh();
        } else {
            pokeOverlay();
        }
    }

    public void release() {
        mPlayer.removeListener(this);
        releaseDisplay();
    }

    private void releaseDisplay() {
        mTicking = false;
        mHandler.removeCallbacksAndMessages(null);
        if (mPlayerView != null) mPlayerView.setPlayer(null);
        if (mPresentation != null) {
            try {
                mPresentation.dismiss();
            } catch (Throwable ignored) {
            }
        }
        mPresentation = null;
        if (mDisplay != null) mDisplay.release();
        mDisplay = null;
        mRoot = null;
        mPlayerView = null;
        mIdle = null;
        mTopBar = null;
        mBottomBar = null;
        mCoverUrl = null;
    }

    // ------------------------------------------------------------------ views

    private View buildViews(Context context) {
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);
        float base = Math.max(1f, mHeight / 480f); // text scales with the car screen

        PlayerView playerView = new PlayerView(context);
        playerView.setUseController(false);
        playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        playerView.setShutterBackgroundColor(Color.BLACK);
        playerView.setKeepContentOnPlayerReset(false);
        root.addView(playerView, match());

        // ---- idle (driving / nothing playing)
        LinearLayout idle = new LinearLayout(context);
        idle.setOrientation(LinearLayout.VERTICAL);
        idle.setGravity(Gravity.CENTER);
        idle.setBackgroundColor(SURFACE_DARK);
        int pad = dp(context, 24);
        idle.setPadding(pad, pad, pad, pad);

        ImageView cover = new ImageView(context);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable coverShape = new GradientDrawable();
        coverShape.setCornerRadius(dp(context, 12));
        coverShape.setColor(0xFF272727);
        cover.setBackground(coverShape);
        cover.setClipToOutline(true);
        int coverHeight = Math.max(dp(context, 90), mHeight * 2 / 5);
        idle.addView(cover, new LinearLayout.LayoutParams(coverHeight * 16 / 9, coverHeight));

        TextView idleTitle = text(context, Color.WHITE, 20 * base, true);
        idleTitle.setGravity(Gravity.CENTER);
        idleTitle.setMaxLines(2);
        idle.addView(idleTitle, wrap(dp(context, 16)));

        TextView idleChannel = text(context, TEXT_SECONDARY, 15 * base, false);
        idleChannel.setGravity(Gravity.CENTER);
        idle.addView(idleChannel, wrap(dp(context, 4)));

        TextView note = text(context, Color.WHITE, 14 * base, false);
        note.setGravity(Gravity.CENTER);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(context, 100));
        pill.setColor(0x33FFFFFF);
        note.setBackground(pill);
        note.setPadding(dp(context, 14), dp(context, 6), dp(context, 14), dp(context, 6));
        idle.addView(note, wrap(dp(context, 14)));
        root.addView(idle, match());

        // ---- top bar over the picture
        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xCC000000, 0x00000000}));
        top.setPadding(dp(context, 20), dp(context, 14), dp(context, 20), dp(context, 28));
        TextView topTitle = text(context, Color.WHITE, 17 * base, true);
        topTitle.setMaxLines(1);
        topTitle.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(topTitle);
        TextView topChannel = text(context, TEXT_SECONDARY, 13 * base, false);
        topChannel.setMaxLines(1);
        top.addView(topChannel);
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        root.addView(top, topParams);

        // ---- bottom bar: status, time, red progress
        LinearLayout bottom = new LinearLayout(context);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0xCC000000, 0x00000000}));
        bottom.setPadding(dp(context, 20), dp(context, 28), dp(context, 20), dp(context, 14));
        TextView status = text(context, Color.WHITE, 13 * base, false);
        bottom.addView(status);
        TextView time = text(context, Color.WHITE, 13 * base, false);
        bottom.addView(time, wrap(dp(context, 2)));
        ProgressBar progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressDrawable(progressDrawable());
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 4));
        progressParams.topMargin = dp(context, 6);
        bottom.addView(progress, progressParams);
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        root.addView(bottom, bottomParams);

        mRoot = root;
        mPlayerView = playerView;
        mIdle = idle;
        mCover = cover;
        mIdleTitle = idleTitle;
        mIdleChannel = idleChannel;
        mNote = note;
        mTopBar = top;
        mTopTitle = topTitle;
        mTopChannel = topChannel;
        mBottomBar = bottom;
        mProgress = progress;
        mTime = time;
        mStatus = status;
        return root;
    }

    private static LayerDrawable progressDrawable() {
        ColorDrawable track = new ColorDrawable(0x4DFFFFFF);
        ClipDrawable fill = new ClipDrawable(new ColorDrawable(PROGRESS_RED), Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable layers = new LayerDrawable(new android.graphics.drawable.Drawable[] {track, fill});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        return layers;
    }

    private static TextView text(Context context, int color, float sp, boolean bold) {
        TextView view = new TextView(context);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setShadowLayer(4, 0, 1, 0x99000000);
        return view;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private static LinearLayout.LayoutParams wrap(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        return params;
    }

    private void applyVisibleArea() {
        if (mRoot == null || mVisible.isEmpty() || mWidth <= 0) return;
        // The picture uses the whole screen, with the car's buttons floating over it like any
        // video player. Only the text (idle screen, title and progress bars) keeps clear of them.
        int left = mVisible.left, top = mVisible.top;
        int right = Math.max(0, mWidth - mVisible.right), bottom = Math.max(0, mHeight - mVisible.bottom);
        int pad = Math.round(16 * mRoot.getResources().getDisplayMetrics().density);
        mIdle.setPadding(left + pad, top + pad, right + pad, bottom + pad);
        mTopBar.setPadding(left + pad, top + pad / 2, right + pad, pad * 2);
        mBottomBar.setPadding(left + pad, pad * 2, right + pad, bottom + pad / 2);
    }

    // ------------------------------------------------------------------ state

    @Override
    public void onAutoPlayerChanged() {
        boolean wasVideo = mPlayerView != null && mPlayerView.getPlayer() != null;
        if (wasVideo != mPlayer.shouldShowVideo() || !mPlayer.isPlaying()) {
            mOverlayUntil = SystemClock.elapsedRealtime() + OVERLAY_MS;
        }
        refresh();
    }

    private boolean overlayShowing() {
        return mTopBar != null && mTopBar.getVisibility() == View.VISIBLE;
    }

    private void startTicking() {
        if (mTicking) return;
        mTicking = true;
        mHandler.postDelayed(mTick, TICK_MS);
    }

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (!mTicking) return;
            refresh();
            mHandler.postDelayed(this, TICK_MS);
        }
    };

    private void refresh() {
        if (mPlayerView == null || mIdle == null) return;
        boolean video = mPlayer.shouldShowVideo();
        ExoPlayer exo = mPlayer.getExoPlayer();
        if (video) {
            if (mPlayerView.getPlayer() != exo) mPlayerView.setPlayer(exo);
            mPlayerView.setVisibility(View.VISIBLE);
        } else {
            if (mPlayerView.getPlayer() != null) mPlayerView.setPlayer(null);
            mPlayerView.setVisibility(View.INVISIBLE);
        }

        Video current = mPlayer.getVideo();
        String title = current != null && current.title != null ? current.title : "NewTube";
        String channel = current != null && current.getAuthor() != null ? current.getAuthor() : "";
        String status = mPlayer.getStatus();

        // Idle screen (no picture).
        mIdle.setVisibility(video ? View.GONE : View.VISIBLE);
        if (!video) {
            mIdleTitle.setText(title);
            mIdleChannel.setText(channel);
            mIdleChannel.setVisibility(channel.isEmpty() ? View.GONE : View.VISIBLE);
            String note;
            if (!status.isEmpty()) note = status;
            else if (current == null) note = "Escolhe um vídeo na lista";
            else if (mPlayer.isAudioOnlyByUser()) note = "Só áudio";
            else note = mParkedHint;
            mNote.setText(note);
            mNote.setVisibility(note.isEmpty() ? View.GONE : View.VISIBLE);
            mCover.setVisibility(current != null ? View.VISIBLE : View.GONE);
            String url = current != null ? current.getCardImageUrl() : null;
            if (url != null && !url.equals(mCoverUrl)) {
                mCoverUrl = url;
                try {
                    Glide.with(mCover.getContext().getApplicationContext()).load(url).into(mCover);
                } catch (Throwable ignored) {
                }
            }
        }

        // Bars: always with sound only (bottom bar keeps the progress), for a moment over video.
        boolean bars = current != null && (!video || !status.isEmpty() || !mPlayer.isPlaying()
                || SystemClock.elapsedRealtime() < mOverlayUntil);
        mTopBar.setVisibility(bars && video ? View.VISIBLE : View.GONE);
        mBottomBar.setVisibility(bars ? View.VISIBLE : View.GONE);
        mTopTitle.setText(title);
        mTopChannel.setText(channel);
        mTopChannel.setVisibility(channel.isEmpty() ? View.GONE : View.VISIBLE);
        mStatus.setText(video ? status : "");
        mStatus.setVisibility(video && !status.isEmpty() ? View.VISIBLE : View.GONE);

        long position = exo != null ? Math.max(0, exo.getCurrentPosition()) : 0;
        long duration = exo != null ? exo.getDuration() : 0;
        boolean live = current != null && current.isLive;
        if (live) {
            mTime.setText("● AO VIVO");
            mTime.setTextColor(BRAND_RED);
            mProgress.setProgress(1000);
        } else {
            mTime.setTextColor(Color.WHITE);
            mTime.setText(duration > 0 ? format(position) + " / " + format(duration) : format(position));
            mProgress.setProgress(duration > 0 ? (int) (position * 1000 / duration) : 0);
        }
    }

    private static String format(long ms) {
        long total = ms / 1000;
        long h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        return h > 0 ? String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(java.util.Locale.ROOT, "%d:%02d", m, s);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
