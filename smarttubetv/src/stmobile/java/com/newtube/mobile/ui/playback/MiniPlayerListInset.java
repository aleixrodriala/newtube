package com.newtube.mobile.ui.playback;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.recyclerview.widget.RecyclerView;

/**
 * NEWTUBE(mini-inset): while the floating mini-player is docked, the list under it can scroll its
 * last item clear of the card. The card (180x102dp, bottom-end) sat over the last row's title and
 * its menu button with no way to scroll them out from under it.
 *
 * <p>Follows the card's visibility itself (a layout listener on the card's tree), so every host -
 * Browse's own mini card and the {@link MobileMiniPlayerController} screens - attaches with one
 * call and no show/hide bookkeeping. The list must not clip to its padding (all hosts already set
 * clipToPadding=false), so rows still scroll under the card and the extra space only matters at
 * the end of the list.</p>
 */
public final class MiniPlayerListInset implements ViewTreeObserver.OnGlobalLayoutListener {
    private static final int GAP_DP = 12;

    private final View mCard;
    private final RecyclerView mList;
    private final int mBasePaddingBottom;
    private final int mGapPx;
    private int mAppliedExtra;

    private MiniPlayerListInset(View card, RecyclerView list) {
        mCard = card;
        mList = list;
        mBasePaddingBottom = list.getPaddingBottom();
        mGapPx = Math.round(GAP_DP * list.getResources().getDisplayMetrics().density);
    }

    /** Keep {@code list}'s bottom padding clear of {@code card} whenever the card is shown. */
    public static void attach(View card, RecyclerView list) {
        if (card == null || list == null) {
            return;
        }
        list.setClipToPadding(false);
        card.getViewTreeObserver().addOnGlobalLayoutListener(new MiniPlayerListInset(card, list));
    }

    @Override
    public void onGlobalLayout() {
        int extra = requiredExtra();
        if (extra != mAppliedExtra) {
            mAppliedExtra = extra;
            mList.setPadding(mList.getPaddingLeft(), mList.getPaddingTop(),
                    mList.getPaddingRight(), mBasePaddingBottom + extra);
        }
    }

    private int requiredExtra() {
        if (mCard.getVisibility() != View.VISIBLE || mCard.getHeight() <= 0) {
            return 0;
        }
        int margin = 0;
        ViewGroup.LayoutParams lp = mCard.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            margin = ((ViewGroup.MarginLayoutParams) lp).bottomMargin;
        }
        return mCard.getHeight() + margin + mGapPx;
    }
}
