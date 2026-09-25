package com.liskovsoft.smartyoutubetv2.common.app.views;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.utils.LoadFailure;

public interface ChannelUploadsView {
    void update(VideoGroup videoGroup);
    void showProgressBar(boolean show);
    void clear();

    /**
     * NEWTUBE(page-load-errors): the list's first load put nothing on screen. {@code state} is a
     * {@link LoadFailure} constant; the retry is
     * {@link com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelUploadsPresenter#reload}.
     * If the view still holds items (a pull-to-refresh that failed), it keeps them. Dismissed by
     * {@link #clear()} and by items arriving.
     */
    default void showLoadFailure(int state) {
    }

    /**
     * NEWTUBE(page-load-errors): the NEXT page failed. The items on screen stay; the retry is
     * another {@code onScrollEnd}, which re-asks for the same page.
     */
    default void showLoadMoreFailure() {
    }
}
