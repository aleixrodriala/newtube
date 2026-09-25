package com.newtube.mobile.ui.search;

import android.view.View;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.utils.LoadFailure;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * NEWTUBE(page-load-errors): what a search that ended with no rows says. It used to be one grey
 * line, "No results. Tap to retry.", whether the phone was offline, the request failed or the
 * query truly matched nothing - blaming the query for a dead network, and offering a retry that
 * can't help an honest empty answer. Same layout as the channel/playlist pages
 * ({@code mobile_page_load_state.xml}: icon, message, Try again), three wordings:
 * no connection and couldn't-load keep Try again; "No results for “query”" drops it only when the
 * search positively completed with nothing (a failure the service swallowed into a cause-free
 * error also classifies as EMPTY - see LoadFailure - and keeps the retry).
 */
final class SearchLoadState {
    private final View mContainer;
    private final TextView mMessage;
    private final View mAction;

    SearchLoadState(View container, Runnable onRetry) {
        mContainer = container;
        mMessage = container.findViewById(R.id.mobile_page_load_state_message);
        mAction = container.findViewById(R.id.mobile_page_load_state_action);
        mAction.setOnClickListener(v -> onRetry.run());
    }

    void show(int state, String query, boolean positivelyEmpty) {
        switch (state) {
            case LoadFailure.NO_CONNECTION:
                mMessage.setText(R.string.mobile_empty_no_connection);
                mAction.setVisibility(View.VISIBLE);
                break;
            case LoadFailure.ERROR:
                mMessage.setText(R.string.mobile_search_error);
                mAction.setVisibility(View.VISIBLE);
                break;
            case LoadFailure.EMPTY:
            default:
                mMessage.setText(mMessage.getContext().getString(R.string.mobile_search_no_results,
                        query != null ? query : ""));
                mAction.setVisibility(positivelyEmpty ? View.GONE : View.VISIBLE);
                break;
        }
        mContainer.setVisibility(View.VISIBLE);
    }
}
