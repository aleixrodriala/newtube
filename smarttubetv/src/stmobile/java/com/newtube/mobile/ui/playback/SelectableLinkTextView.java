package com.newtube.mobile.ui.playback;

import android.content.Context;
import android.text.Layout;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.view.ViewCompat;

/** Native selection owns every gesture; only an unmoved short tap can activate a link. */
public class SelectableLinkTextView extends AppCompatTextView {
    private final int mTouchSlop;
    private ClickableSpan mTappedSpan;
    private float mDownX;
    private float mDownY;

    public SelectableLinkTextView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        // TextView's auto-link touch path uses the current selection, not the tapped span.
        setAutoLinkMask(0);
        setTextIsSelectable(true);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        ViewCompat.enableAccessibleClickableSpanSupport(this);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = event.getX();
                mDownY = event.getY();
                mTappedSpan = hasSelection() ? null : spanAt(event);
                break;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(event.getX() - mDownX) > mTouchSlop
                        || Math.abs(event.getY() - mDownY) > mTouchSlop) {
                    mTappedSpan = null;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                mTappedSpan = null;
                break;
            case MotionEvent.ACTION_UP:
                ClickableSpan tapped = mTappedSpan;
                mTappedSpan = null;
                boolean shortTap = event.getEventTime() - event.getDownTime()
                        < ViewConfiguration.getLongPressTimeout();
                boolean handled = super.onTouchEvent(event);
                if (tapped != null && shortTap && !hasSelection() && tapped == spanAt(event)) {
                    tapped.onClick(this);
                    return true;
                }
                return handled;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performLongClick() {
        mTappedSpan = null;
        return super.performLongClick();
    }

    @Nullable
    private ClickableSpan spanAt(MotionEvent event) {
        Layout layout = getLayout();
        if (layout == null || !(getText() instanceof Spanned)) {
            return null;
        }
        float x = event.getX() - getTotalPaddingLeft() + getScrollX();
        float y = event.getY() - getTotalPaddingTop() + getScrollY();
        if (y < 0 || y >= layout.getHeight()) {
            return null;
        }
        int line = layout.getLineForVertical((int) y);
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) {
            return null;
        }
        int offset = layout.getOffsetForHorizontal(line, x);
        Spanned text = (Spanned) getText();
        for (ClickableSpan span : text.getSpans(offset, offset, ClickableSpan.class)) {
            if (offset < text.getSpanEnd(span)) {
                return span;
            }
        }
        return null;
    }
}
