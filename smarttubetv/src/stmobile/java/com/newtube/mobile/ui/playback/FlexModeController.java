package com.newtube.mobile.ui.playback;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.util.Consumer;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.ui.TimeBar;
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter;
import androidx.window.layout.WindowInfoTracker;
import androidx.window.layout.WindowLayoutInfo;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.SeekBarSegment;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.Haptics;

import java.util.List;
import java.util.Locale;

/**
 * Coordinates foldable tabletop / Flex mode in {@link MobilePlaybackActivity}.
 *
 * <p>One job: listen to Jetpack {@link WindowInfoTracker}, track the split bounds above/below a
 * horizontal half-opened hinge, and drive the lower-half Flex deck, watch-page switch, and
 * {@link FlexTouchpadView}.</p>
 */
final class FlexModeController implements FlexTouchpadView.Listener {

    interface Host {
        boolean isFlexAllowed();

        void onFlexStateChanged();

        void onFlexTogglePlayPause();

        void onFlexSeekByMs(long deltaMs);

        void onFlexSeekToMs(long positionMs);

        void onFlexPreviousClicked();

        void onFlexNextClicked();

        void onFlexShowSpeedSheet();

        void onFlexShowQualitySheet();

        void onFlexToggleCaptions();

        void onFlexShowCaptionsSheet();

        void onFlexToggleZoomFill();

        void onFlexOpenComments();

        void onFlexOpenMoreMenu();

        void onFlexShowChaptersSheet();

        void onFlexJumpToLiveEdge();

        long getFlexDurationMs();

        long getFlexPositionMs();
    }

    private static final int MIN_HALF_DP = 180;
    private static final long SKIP_MS = 10_000L;

    private final Activity mActivity;
    private final Host mHost;
    private final View mWatchRoot;
    private final View mHingeSpacer;
    private final View mFlexPanel;
    private final View mDeck;
    private final View mWatchBar;
    private final View mWatchScroll;
    private final TextView mWatchBarTime;
    private final ImageButton mWatchBarPlay;
    private final TextView mTitleView;
    private final TextView mLiveChip;
    private final TextView mChapterBtn;
    private final TextView mTimeView;
    private final PlayerTimeBar mTimeBar;
    private final ImageButton mPlayPauseBtn;
    private final TextView mSpeedBtn;
    private final TextView mQualityBtn;
    private final TextView mCcBtn;
    private final TextView mZoomBtn;
    private final TextView mCommentsBtn;
    private final FlexTouchpadView mTouchpad;
    private final SwipeLevels mSwipeLevels;
    private final WindowInfoTrackerCallbackAdapter mTracker;
    private final Consumer<WindowLayoutInfo> mLayoutConsumer = this::onWindowLayoutInfo;

    @Nullable
    private WindowLayoutInfo mLastInfo;
    @Nullable
    private FlexFoldGeometry.FlexSplit mSplit;
    private boolean mStarted;
    private boolean mShowingWatchPage;
    private boolean mScrubbing;
    private boolean mPlaying;
    private boolean mEnded;
    private int mInsetBottomPx;

    FlexModeController(Activity activity, Host host, View watchRoot,
            View hingeSpacer, View flexPanel, View watchScroll) {
        mActivity = activity;
        mHost = host;
        mWatchRoot = watchRoot;
        mHingeSpacer = hingeSpacer;
        mFlexPanel = flexPanel;
        mDeck = flexPanel.findViewById(R.id.mobile_flex_deck);
        mWatchBar = flexPanel.findViewById(R.id.mobile_flex_watch_bar);
        mWatchScroll = watchScroll;
        mWatchBarTime = flexPanel.findViewById(R.id.mobile_flex_watch_bar_time);
        mWatchBarPlay = flexPanel.findViewById(R.id.mobile_flex_watch_bar_play);
        mTitleView = flexPanel.findViewById(R.id.flex_title);
        mLiveChip = flexPanel.findViewById(R.id.flex_live);
        mChapterBtn = flexPanel.findViewById(R.id.flex_chapter_btn);
        mTimeView = flexPanel.findViewById(R.id.flex_time);
        mTimeBar = flexPanel.findViewById(R.id.flex_time_bar);
        mPlayPauseBtn = flexPanel.findViewById(R.id.flex_play_pause);
        mSpeedBtn = flexPanel.findViewById(R.id.flex_action_speed);
        mQualityBtn = flexPanel.findViewById(R.id.flex_action_quality);
        mCcBtn = flexPanel.findViewById(R.id.flex_action_cc);
        mZoomBtn = flexPanel.findViewById(R.id.flex_action_zoom);
        mCommentsBtn = flexPanel.findViewById(R.id.flex_action_comments);
        mTouchpad = flexPanel.findViewById(R.id.flex_touchpad);

        LinearLayout levelPill = flexPanel.findViewById(R.id.flex_level_pill);
        ImageView levelIcon = flexPanel.findViewById(R.id.flex_level_icon);
        LevelBar levelBar = flexPanel.findViewById(R.id.flex_level_bar);
        mSwipeLevels = new SwipeLevels(activity, activity.getWindow(), mTouchpad,
                new SwipeLevels.Pill() {
                    @Override
                    public void showLevel(int iconRes, float level) {
                        levelIcon.setImageResource(iconRes);
                        levelBar.setLevel(level);
                        levelPill.setVisibility(View.VISIBLE);
                    }

                    @Override
                    public void hideLevel() {
                        levelPill.setVisibility(View.GONE);
                    }
                });

        mTracker = new WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(activity));
        bindControls();
    }

    private void bindControls() {
        mTimeBar.setTouchRouted(false);
        mTimeBar.setTrackAtBottom(false);
        mTimeBar.setShown(true, false);
        mTimeBar.addListener(new TimeBar.OnScrubListener() {
            @Override
            public void onScrubStart(@NonNull TimeBar timeBar, long position) {
                mScrubbing = true;
                updateScrubTimeText(position);
            }

            @Override
            public void onScrubMove(@NonNull TimeBar timeBar, long position) {
                updateScrubTimeText(position);
            }

            @Override
            public void onScrubStop(@NonNull TimeBar timeBar, long position, boolean canceled) {
                mScrubbing = false;
                if (!canceled) {
                    mHost.onFlexSeekToMs(position);
                }
            }
        });
        mTouchpad.bind(this, mSwipeLevels);

        mFlexPanel.findViewById(R.id.flex_prev).setOnClickListener(v -> mHost.onFlexPreviousClicked());
        mFlexPanel.findViewById(R.id.flex_rew_10).setOnClickListener(v -> skipWithFeedback(false));
        mPlayPauseBtn.setOnClickListener(v -> mHost.onFlexTogglePlayPause());
        mFlexPanel.findViewById(R.id.flex_fwd_10).setOnClickListener(v -> skipWithFeedback(true));
        mFlexPanel.findViewById(R.id.flex_next).setOnClickListener(v -> mHost.onFlexNextClicked());
        mSpeedBtn.setOnClickListener(v -> mHost.onFlexShowSpeedSheet());
        mQualityBtn.setOnClickListener(v -> mHost.onFlexShowQualitySheet());
        mCcBtn.setOnClickListener(v -> mHost.onFlexToggleCaptions());
        mCcBtn.setOnLongClickListener(v -> {
            mHost.onFlexShowCaptionsSheet();
            return true;
        });
        mZoomBtn.setOnClickListener(v -> mHost.onFlexToggleZoomFill());
        mCommentsBtn.setOnClickListener(v -> mHost.onFlexOpenComments());
        mFlexPanel.findViewById(R.id.flex_action_related).setOnClickListener(v -> setShowingWatchPage(true));
        mFlexPanel.findViewById(R.id.flex_action_more).setOnClickListener(v -> mHost.onFlexOpenMoreMenu());
        mChapterBtn.setOnClickListener(v -> mHost.onFlexShowChaptersSheet());
        mLiveChip.setOnClickListener(v -> mHost.onFlexJumpToLiveEdge());
        mFlexPanel.findViewById(R.id.mobile_flex_back_to_controls)
                .setOnClickListener(v -> setShowingWatchPage(false));
        mWatchBarPlay.setOnClickListener(v -> mHost.onFlexTogglePlayPause());
    }

    private void skipWithFeedback(boolean forward) {
        Haptics.tick(mTouchpad);
        mHost.onFlexSeekByMs(forward ? SKIP_MS : -SKIP_MS);
        mTouchpad.showFeedback(forward ? "+10s" : "-10s", 550);
    }

    void start() {
        if (mStarted) {
            return;
        }
        mStarted = true;
        mTracker.addWindowLayoutInfoListener(mActivity, ContextCompat.getMainExecutor(mActivity), mLayoutConsumer);
    }

    void stop() {
        if (!mStarted) {
            return;
        }
        mStarted = false;
        mTracker.removeWindowLayoutInfoListener(mLayoutConsumer);
        mSwipeLevels.cancel();
        mSwipeLevels.setBrightnessActive(false);
    }

    boolean isActive() {
        return mSplit != null && mHost.isFlexAllowed();
    }

    int getTopPanelHeightPx() {
        return mSplit != null ? mSplit.topPanelHeightPx : 0;
    }

    boolean isShowingWatchPage() {
        return isActive() && mShowingWatchPage;
    }

    void closeWatchPage() {
        setShowingWatchPage(false);
    }

    void onRootLayoutChanged() {
        recomputeSplit();
    }

    void onHostStateChanged() {
        recomputeSplit();
        syncPanelVisibility();
    }

    private void onWindowLayoutInfo(WindowLayoutInfo info) {
        mLastInfo = info;
        recomputeSplit();
    }

    private void recomputeSplit() {
        int minHalfPx = Math.round(MIN_HALF_DP * mActivity.getResources().getDisplayMetrics().density);
        FlexFoldGeometry.FlexSplit next = mHost.isFlexAllowed()
                ? FlexFoldGeometry.computeFromLayoutInfo(mLastInfo, mWatchRoot, minHalfPx)
                : null;
        boolean wasActive = mSplit != null;
        boolean nowActive = next != null;
        int prevTop = wasActive ? mSplit.topPanelHeightPx : 0;
        int prevGap = wasActive ? mSplit.hingeSpacerHeightPx : 0;
        mSplit = next;
        if (wasActive != nowActive
                || (nowActive && (Math.abs(next.topPanelHeightPx - prevTop) > 1
                || Math.abs(next.hingeSpacerHeightPx - prevGap) > 1))) {
            if (!nowActive) {
                mShowingWatchPage = false;
                mSwipeLevels.cancel();
            }
            mSwipeLevels.setBrightnessActive(nowActive);
            syncPanelVisibility();
            mHost.onFlexStateChanged();
        }
    }

    private void setShowingWatchPage(boolean show) {
        if (mShowingWatchPage == show) {
            return;
        }
        mShowingWatchPage = show;
        syncPanelVisibility();
        mHost.onFlexStateChanged();
    }

    void syncPanelVisibility() {
        boolean active = isActive();
        if (!active) {
            mFlexPanel.setVisibility(View.GONE);
            mHingeSpacer.setVisibility(View.GONE);
            setWatchScrollTopMargin(0);
            return;
        }
        int spacerPx = mSplit != null ? Math.max(dp(2), mSplit.hingeSpacerHeightPx) : dp(2);
        ViewGroup.LayoutParams spLp = mHingeSpacer.getLayoutParams();
        if (spLp.height != spacerPx) {
            spLp.height = spacerPx;
            mHingeSpacer.setLayoutParams(spLp);
        }
        mHingeSpacer.setVisibility(View.VISIBLE);
        mFlexPanel.setVisibility(View.VISIBLE);
        mDeck.setVisibility(mShowingWatchPage ? View.GONE : View.VISIBLE);
        mWatchBar.setVisibility(mShowingWatchPage ? View.VISIBLE : View.GONE);
        setWatchScrollTopMargin(mShowingWatchPage ? dp(44) : 0);
    }

    void applyInsets(@Nullable WindowInsetsCompat rootInsets) {
        if (rootInsets == null) {
            return;
        }
        Insets bars = rootInsets.getInsets(WindowInsetsCompat.Type.navigationBars()
                | WindowInsetsCompat.Type.displayCutout());
        mInsetBottomPx = bars.bottom;
        mDeck.setPadding(dp(16) + bars.left, dp(10), dp(16) + bars.right, dp(12) + mInsetBottomPx);
        mWatchBar.setPadding(dp(12) + bars.left, 0, dp(12) + bars.right, 0);
    }

    void setChapterStarts(@Nullable long[] starts) {
        mTimeBar.setChapterStarts(starts);
    }

    void setSegments(@Nullable List<SeekBarSegment> segments) {
        mTimeBar.setSegments(segments);
    }

    void updatePlaybackState(CharSequence title, long positionMs, long durationMs, long bufferedMs,
            boolean playing, boolean ended, boolean live, boolean liveAtEdge,
            @Nullable CharSequence chapterText, @Nullable CharSequence speedLabel,
            @Nullable CharSequence qualityLabel, boolean captionsOn, boolean zoomFill,
            boolean commentsAvailable) {
        if (!isActive()) {
            return;
        }
        mPlaying = playing;
        mEnded = ended;
        mTitleView.setText(title);
        if (!mScrubbing) {
            mTimeBar.setDuration(durationMs);
            mTimeBar.setPosition(positionMs);
            mTimeBar.setBufferedPosition(bufferedMs);
            String timePair = formatTime(positionMs) + " / " + formatTime(durationMs);
            mTimeView.setText(timePair);
            mWatchBarTime.setText(timePair);
        }
        mLiveChip.setVisibility(live ? View.VISIBLE : View.GONE);
        if (live) {
            mLiveChip.setAlpha(liveAtEdge ? 1f : 0.55f);
        }
        boolean hasChapter = chapterText != null && chapterText.length() > 0;
        mChapterBtn.setVisibility(hasChapter ? View.VISIBLE : View.GONE);
        if (hasChapter) {
            mChapterBtn.setText(chapterText);
        }
        int iconRes = ended ? R.drawable.ic_player_replay
                : (playing ? R.drawable.ic_player_pause : R.drawable.ic_player_play);
        mPlayPauseBtn.setImageResource(iconRes);
        mWatchBarPlay.setImageResource(iconRes);
        if (speedLabel != null) {
            mSpeedBtn.setText(speedLabel);
        }
        if (qualityLabel != null) {
            mQualityBtn.setText(qualityLabel);
        }
        mCcBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(
                captionsOn ? R.drawable.ic_player_cc : R.drawable.ic_player_cc_off, 0, 0, 0);
        mZoomBtn.setText(zoomFill ? R.string.mobile_flex_zoom_fill : R.string.mobile_flex_zoom_fit);
        mCommentsBtn.setVisibility(commentsAvailable ? View.VISIBLE : View.GONE);
    }

    private void updateScrubTimeText(long positionMs) {
        String text = formatTime(positionMs) + " / " + formatTime(Math.max(0L, mHost.getFlexDurationMs()));
        mTimeView.setText(text);
        mWatchBarTime.setText(text);
    }

    private void setWatchScrollTopMargin(int topMarginPx) {
        if (mWatchScroll.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) mWatchScroll.getLayoutParams();
            if (lp.topMargin != topMarginPx) {
                lp.topMargin = topMarginPx;
                mWatchScroll.setLayoutParams(lp);
            }
        }
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }

    private static String formatTime(long ms) {
        long totalSeconds = Math.max(0L, ms / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    @Override
    public void onTouchpadTogglePlayPause() {
        mHost.onFlexTogglePlayPause();
        boolean nextPlay = !mPlaying || mEnded;
        mTouchpad.showFeedback(nextPlay ? "▶" : "❚❚", 450);
    }

    @Override
    public void onTouchpadDoubleTapSeek(boolean forward) {
        mHost.onFlexSeekByMs(forward ? SKIP_MS : -SKIP_MS);
    }

    @Override
    public long onTouchpadSeekStart() {
        long duration = mHost.getFlexDurationMs();
        if (duration > 0) {
            mScrubbing = true;
            mTouchpad.setSeekOriginMs(Math.max(0L, mHost.getFlexPositionMs()));
        }
        return duration;
    }

    @Override
    public long onTouchpadGetDurationMs() {
        return mHost.getFlexDurationMs();
    }

    @Override
    public void onTouchpadSeekPreview(long positionMs, long deltaMs) {
        mTimeBar.setPosition(positionMs);
        updateScrubTimeText(positionMs);
        String sign = deltaMs >= 0 ? "+" : "-";
        mTouchpad.showFeedback(formatTime(positionMs) + " (" + sign + (Math.abs(deltaMs) / 1000L) + "s)", 0);
    }

    @Override
    public void onTouchpadSeekCommit(long positionMs, boolean canceled) {
        mScrubbing = false;
        if (!canceled) {
            mHost.onFlexSeekToMs(positionMs);
        }
    }
}
