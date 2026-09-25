package com.liskovsoft.smartyoutubetv2.common.app.views;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.search.MediaServiceSearchTagProvider;
import com.liskovsoft.smartyoutubetv2.common.app.models.search.vineyard.Tag;

public interface SearchView {
    void updateSearch(VideoGroup group);
    void clearSearch();
    void clearSearchTags();
    void removeSearchTag(Tag tag);
    void setTagsProvider(MediaServiceSearchTagProvider provider);
    void showProgressBar(boolean show);
    void startSearch(String searchText);
    String getSearchText();
    void startVoiceRecognition();
    void finishReally();

    /**
     * NEWTUBE(page-load-errors): the search failed with {@code state}, a
     * {@link com.liskovsoft.smartyoutubetv2.common.utils.LoadFailure} constant. Delivered just before
     * {@link #showProgressBar(boolean) showProgressBar(false)}, so a view that renders an empty-result
     * state there can tell "no connection" and "couldn't load" from "no results".
     */
    default void showLoadFailure(int state) {
    }
}
