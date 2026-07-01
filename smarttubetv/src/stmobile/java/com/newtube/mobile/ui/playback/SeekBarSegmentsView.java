package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.SeekBarSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin overlay drawn on top of the player's {@link com.google.android.exoplayer2.ui.DefaultTimeBar}
 * to render SponsorBlock colored segment ranges (PLAYER OPTIONS wave, ARCHITECTURE.md section 6:
 * "SponsorBlockController's {@code SeekBarSegment} list - draw colored ranges on scrubber"). This is
 * visual only; the actual skipping is done by {@code SponsorBlockController}, which pushes the ranges
 * here through {@code PlayerUI.setSeekBarSegments(...)}.
 *
 * <p>Each {@link SeekBarSegment} carries {@code startProgress}/{@code endProgress} fractions
 * (0..1 of the video duration) and an already-resolved ARGB {@code color}.</p>
 *
 * <p>The view is placed in the same {@code FrameLayout} cell as the {@code DefaultTimeBar} with
 * matching bounds, so it can reproduce the time bar's horizontal track rect: DefaultTimeBar insets
 * the played/unplayed bar by {@code (maxScrubberSize + 1) / 2} on each side (so the scrubber thumb
 * never clips the edges - see {@code DefaultTimeBar.onLayout}). We mirror that inset and the vertical
 * centering so the colored ranges align with the track underneath. The layout uses
 * {@code scrubber_dragged_size=18dp} as the largest scrubber size.</p>
 */
public class SeekBarSegmentsView extends View {
    /** Largest scrubber size used by the time bar (scrubber_dragged_size in the layout). */
    private static final float MAX_SCRUBBER_DP = 18f;
    /** Matches the time bar's app:bar_height so the markers sit on the track line. */
    private static final float BAR_HEIGHT_DP = 3f;
    /** Minimum on-screen width so a very short segment stays visible. */
    private static final float MIN_SEGMENT_PX = 2f;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<SeekBarSegment> mSegments = new ArrayList<>();
    private final float mScrubberPaddingPx;
    private final float mBarHeightPx;

    public SeekBarSegmentsView(Context context) {
        this(context, null);
    }

    public SeekBarSegmentsView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SeekBarSegmentsView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mPaint.setStyle(Paint.Style.FILL);
        mScrubberPaddingPx = (dp(MAX_SCRUBBER_DP) + 1f) / 2f;
        mBarHeightPx = dp(BAR_HEIGHT_DP);
        setWillNotDraw(false);
    }

    /** Replace the rendered segments. {@code null}/empty clears the overlay. Safe to call off-UI? No. */
    public void setSegments(@Nullable List<SeekBarSegment> segments) {
        mSegments.clear();
        if (segments != null) {
            for (SeekBarSegment segment : segments) {
                if (segment != null) {
                    mSegments.add(segment);
                }
            }
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (mSegments.isEmpty()) {
            return;
        }

        float left = getPaddingLeft() + mScrubberPaddingPx;
        float right = getWidth() - getPaddingRight() - mScrubberPaddingPx;
        float trackWidth = right - left;
        if (trackWidth <= 0f) {
            return;
        }

        float centerY = getHeight() / 2f;
        float top = centerY - mBarHeightPx / 2f;
        float bottom = centerY + mBarHeightPx / 2f;

        for (SeekBarSegment segment : mSegments) {
            float startFraction = clamp(segment.startProgress);
            float endFraction = clamp(segment.endProgress);
            if (endFraction <= startFraction) {
                continue;
            }

            float segLeft = left + trackWidth * startFraction;
            float segRight = left + trackWidth * endFraction;
            if (segRight - segLeft < MIN_SEGMENT_PX) {
                segRight = segLeft + MIN_SEGMENT_PX;
            }

            mPaint.setColor(segment.color);
            canvas.drawRect(segLeft, top, segRight, bottom, mPaint);
        }
    }

    private static float clamp(float value) {
        if (value < 0f) {
            return 0f;
        }
        return Math.min(value, 1f);
    }

    private float dp(float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics());
    }
}
