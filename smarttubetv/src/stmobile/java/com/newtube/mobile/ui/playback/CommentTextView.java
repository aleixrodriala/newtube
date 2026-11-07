package com.newtube.mobile.ui.playback;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.text.Layout;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * NEWTUBE(comments-panel): a comment's text, folded at {@link #FOLD_LINES} lines with "Read more"
 * over the end of the last one, like YouTube's. A tap unfolds it and a second tap folds it back,
 * both animating the height (Material standard, 250ms) so the rows below follow; a tap on a link
 * or timestamp is the link's, never the fold's.
 *
 * <p>The whole text is always laid out; folding only limits the measured height, and the parent
 * clips the rest. That keeps a fold a pure height change - no re-layout of the text per frame.</p>
 */
public class CommentTextView extends AppCompatTextView {

    public interface Listener {
        /** The person tapped the text to unfold (true) or fold (false) it. */
        void onFoldToggled(CommentTextView view, boolean expanded);

        /** A long press on the text: the comment's whole-row long press. */
        void onTextLongPressed(CommentTextView view);
    }

    static final int FOLD_LINES = 4;
    private static final long FOLD_MS = 250;
    private static final long READ_MORE_FADE_MS = 150;
    private static final Interpolator STANDARD = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private final GestureDetector mGestures;
    private final Paint mFadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mReadMorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int mFadeWidth;
    private final int mReadMoreGap;
    private final String mReadMore;
    private final int mReadMoreColor;
    private int mGroundColor;
    /** Where "Read more" and its fade were last drawn (the part of the text it covers). */
    private final android.graphics.RectF mReadMoreArea = new android.graphics.RectF();

    @Nullable
    private Listener mListener;
    private boolean mFoldable = true;
    private boolean mExpanded;
    /** Height the view animates through; -1 when not animating. */
    private int mAnimatedHeight = -1;
    private float mReadMoreAlpha = 1f;
    @Nullable
    private ValueAnimator mFoldAnimator;

    public CommentTextView(@NonNull Context context) {
        this(context, null);
    }

    public CommentTextView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, android.R.attr.textViewStyle);
    }

    public CommentTextView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = getResources().getDisplayMetrics().density;
        mFadeWidth = Math.round(26 * density);
        mReadMoreGap = Math.round(28 * density) - mFadeWidth;
        mReadMore = context.getString(R.string.mobile_comments_read_more);
        mGroundColor = com.newtube.mobile.ui.common.ThemeMode.color(context, R.attr.newtubeColorBackground);
        mReadMoreColor = ContextCompat.getColor(context, R.color.mobile_color_on_surface_secondary);
        mReadMorePaint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        mGestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(@NonNull MotionEvent e) {
                // "Read more" is painted over the end of the last folded line; whatever text (a
                // link, a timestamp) lies under it is hidden, so the label wins there.
                ClickableSpan span = isOnReadMore(e) ? null : spanAt(e);
                if (span != null) {
                    span.onClick(CommentTextView.this);
                    return true;
                }
                if (canFold()) {
                    setExpanded(!mExpanded, true);
                    if (mListener != null) {
                        mListener.onFoldToggled(CommentTextView.this, mExpanded);
                    }
                    return true;
                }
                return false;
            }

            @Override
            public void onLongPress(@NonNull MotionEvent e) {
                if (mListener != null) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                    mListener.onTextLongPressed(CommentTextView.this);
                }
            }
        });
        // Links are handled here (spanAt), not by a MovementMethod, which would also make the
        // text selectable-looking and swallow the fold tap. TalkBack still lists the links.
        setLinksClickable(false);
        ViewCompat.enableAccessibleClickableSpanSupport(this);
    }

    public void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    /** The colour behind the text, which "Read more"'s fade blends into. */
    public void setGroundColor(int color) {
        if (mGroundColor != color) {
            mGroundColor = color;
            invalidate();
        }
    }

    /** False for text that always shows in full (the comment at the top of a replies page). */
    public void setFoldable(boolean foldable) {
        if (mFoldable != foldable) {
            mFoldable = foldable;
            requestLayout();
        }
    }

    public boolean isExpanded() {
        return mExpanded;
    }

    /** Whether the text is longer than the fold (only meaningful once laid out). */
    public boolean canFold() {
        Layout layout = getLayout();
        return mFoldable && layout != null && layout.getLineCount() > FOLD_LINES;
    }

    /** Set without animation (binding a row), or animated from the current height (a tap). */
    public void setExpanded(boolean expanded, boolean animate) {
        if (mFoldAnimator != null) {
            mFoldAnimator.cancel();
            mFoldAnimator = null;
        }
        boolean changed = mExpanded != expanded;
        mExpanded = expanded;
        if (!animate || !changed || getLayout() == null || !canFold()) {
            mAnimatedHeight = -1;
            mReadMoreAlpha = expanded ? 0f : 1f;
            requestLayout();
            invalidate();
            return;
        }

        int from = getHeight();
        int to = heightFor(expanded);
        mFoldAnimator = ValueAnimator.ofInt(from, to);
        mFoldAnimator.setDuration(FOLD_MS);
        mFoldAnimator.setInterpolator(STANDARD);
        float alphaFrom = mReadMoreAlpha;
        float alphaTo = expanded ? 0f : 1f;
        // "Read more" leaves in the first 150ms of an unfold, and returns in the last 150ms of a fold.
        float fadeShare = (float) READ_MORE_FADE_MS / FOLD_MS;
        mFoldAnimator.addUpdateListener(a -> {
            mAnimatedHeight = (int) a.getAnimatedValue();
            float t = a.getAnimatedFraction();
            float fade = expanded ? Math.min(1f, t / fadeShare) : Math.max(0f, (t - (1f - fadeShare)) / fadeShare);
            mReadMoreAlpha = alphaFrom + (alphaTo - alphaFrom) * fade;
            requestLayout();
            invalidate();
        });
        mFoldAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                mCancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (!mCancelled) {
                    mFoldAnimator = null;
                    mAnimatedHeight = -1;
                    mReadMoreAlpha = alphaTo;
                    requestLayout();
                    invalidate();
                }
            }
        });
        mFoldAnimator.start();
    }

    /** Stop a fold halfway (the row is being recycled): jump to where it was going. */
    public void finishFold() {
        if (mFoldAnimator != null) {
            mFoldAnimator.end();
        }
    }

    private int heightFor(boolean expanded) {
        Layout layout = getLayout();
        int vertical = getCompoundPaddingTop() + getCompoundPaddingBottom();
        if (layout == null) {
            return getMeasuredHeight();
        }
        int lines = expanded ? layout.getLineCount() : Math.min(FOLD_LINES, layout.getLineCount());
        return layout.getLineTop(lines) + vertical;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        Layout layout = getLayout();
        if (layout == null) {
            return;
        }
        int height;
        if (mAnimatedHeight >= 0) {
            height = mAnimatedHeight;
        } else if (!mExpanded && canFold()) {
            height = heightFor(false);
        } else {
            return;
        }
        setMeasuredDimension(getMeasuredWidth(), height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mReadMoreAlpha <= 0f || !canFold()) {
            return;
        }
        // "Read more" sits at the end of the last folded line, over a fade that blends the text
        // into the ground (end side; mirrored for right-to-left text).
        Layout layout = getLayout();
        int line = FOLD_LINES - 1;
        int top = getCompoundPaddingTop();
        float baseline = top + layout.getLineBaseline(line);
        float lineTop = top + layout.getLineTop(line);
        float lineBottom = top + layout.getLineTop(line + 1);
        mReadMorePaint.setTextSize(getTextSize());
        float textWidth = mReadMorePaint.measureText(mReadMore);
        boolean rtl = layout.getParagraphDirection(line) == Layout.DIR_RIGHT_TO_LEFT;
        int left = getCompoundPaddingLeft();
        int right = getWidth() - getCompoundPaddingRight();
        float textStart = rtl ? left : right - textWidth;
        float fadeEnd = rtl ? textStart + textWidth + mReadMoreGap : textStart - mReadMoreGap;
        float fadeStart = rtl ? fadeEnd + mFadeWidth : fadeEnd - mFadeWidth;

        int solid = ColorUtils.setAlphaComponent(mGroundColor,
                Math.round(Color.alpha(mGroundColor) * mReadMoreAlpha));
        int clear = ColorUtils.setAlphaComponent(mGroundColor, 0);
        mFadePaint.setShader(new LinearGradient(fadeStart, 0, fadeEnd, 0, clear, solid, Shader.TileMode.CLAMP));
        if (rtl) {
            canvas.drawRect(left, lineTop, fadeStart, lineBottom, mFadePaint);
            mReadMoreArea.set(left, lineTop, fadeStart, lineBottom);
        } else {
            canvas.drawRect(fadeStart, lineTop, right, lineBottom, mFadePaint);
            mReadMoreArea.set(fadeStart, lineTop, right, lineBottom);
        }
        mReadMorePaint.setColor(ColorUtils.setAlphaComponent(mReadMoreColor,
                Math.round(Color.alpha(mReadMoreColor) * mReadMoreAlpha)));
        canvas.drawText(mReadMore, textStart, baseline, mReadMorePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        // The list still scrolls: RecyclerView takes the gesture over (a CANCEL here) once it moves.
        return mGestures.onTouchEvent(event);
    }

    private boolean isOnReadMore(MotionEvent e) {
        return !mExpanded && mReadMoreAlpha > 0f && canFold() && mReadMoreArea.contains(e.getX(), e.getY());
    }

    @Nullable
    private ClickableSpan spanAt(MotionEvent e) {
        CharSequence text = getText();
        Layout layout = getLayout();
        if (!(text instanceof Spanned) || layout == null) {
            return null;
        }
        int x = (int) e.getX() - getTotalPaddingLeft() + getScrollX();
        int y = (int) e.getY() - getTotalPaddingTop() + getScrollY();
        if (y < 0 || y > layout.getHeight() || y > getHeight()) {
            return null;
        }
        int line = layout.getLineForVertical(y);
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) {
            return null;
        }
        int offset = layout.getOffsetForHorizontal(line, x);
        ClickableSpan[] spans = ((Spanned) text).getSpans(offset, offset, ClickableSpan.class);
        return spans.length > 0 ? spans[0] : null;
    }
}
