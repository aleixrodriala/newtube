package com.newtube.mobile.ui.channel;

import android.view.View;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.utils.LoadFailure;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * NEWTUBE(page-load-errors): the first-page failure state of the channel and uploads/playlist
 * pages ({@code mobile_page_load_state.xml}) - the Home feed's empty state, same icon, type and
 * Try again button.
 *
 * <p>The button is shown for {@link LoadFailure#EMPTY} too: at this seam an empty destination and a
 * failure the service swallowed into {@code null} look identical (see {@link LoadFailure}), so the
 * wording stays neutral but the way out stays on screen.</p>
 */
final class PageLoadState {
    private final View mContainer;
    private final TextView mMessage;

    PageLoadState(View container, Runnable onRetry) {
        mContainer = container;
        mMessage = container.findViewById(R.id.mobile_page_load_state_message);
        container.findViewById(R.id.mobile_page_load_state_action).setOnClickListener(v -> onRetry.run());
    }

    void show(int state) {
        mMessage.setText(messageFor(state));
        mContainer.setVisibility(View.VISIBLE);
    }

    void hide() {
        mContainer.setVisibility(View.GONE);
    }

    boolean isShowing() {
        return mContainer.getVisibility() == View.VISIBLE;
    }

    /** First-page wording; also the snackbar wording when a refresh fails over kept rows. */
    static int messageFor(int state) {
        switch (state) {
            case LoadFailure.NO_CONNECTION:
                return R.string.mobile_empty_no_connection;
            case LoadFailure.ERROR:
                return R.string.mobile_page_load_error;
            case LoadFailure.EMPTY:
            default:
                return R.string.mobile_empty_generic;
        }
    }
}
