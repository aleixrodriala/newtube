package com.newtube.mobile.ui.search;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.search.MediaServiceSearchTagProvider;
import com.liskovsoft.smartyoutubetv2.common.app.models.search.vineyard.Tag;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.SearchView;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.browse.VideoCardAdapter;
import com.newtube.mobile.ui.common.MobileActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Touch Search screen (Wave 4b).
 *
 * <p>The touch replacement for the Leanback {@code SearchTagsFragment}/{@code
 * SearchTagsActivity}. Drives the unchanged {@link SearchPresenter} through the standard MVP
 * seam (setView/onViewInitialized + the {@code VideoGroupPresenter} input contract), and
 * renders with ordinary touch widgets instead of the Leanback search fragment:</p>
 *
 * <ul>
 *   <li>A toolbar text {@link EditText} (IME action = search) feeds typed text to the
 *       presenter-supplied {@link MediaServiceSearchTagProvider} to fetch tag SUGGESTIONS,
 *       shown as a full-height vertical list ({@link SearchTagAdapter}) OVER the results
 *       while the field is focused — history rows (clock icon) for an empty query, live
 *       suggestion rows (magnifier) once typing; the trailing NW arrow refines the query
 *       without submitting. Submitting (keyboard action) or tapping a row runs the real
 *       search via {@link SearchPresenter#onSearch(String)}.</li>
 *   <li>Results render in a single Material RecyclerView GRID reusing {@link VideoCardAdapter}
 *       (same runtime span-count math + {@code onScrollEnd} pagination as the Home/Channel
 *       grids). {@link #updateSearch(VideoGroup)} applies per-{@code VideoGroup} actions.</li>
 *   <li>Tap a result -> {@link SearchPresenter#onVideoItemClicked} -> {@code
 *       VideoActionPresenter.apply()} -> normal routing (video plays via {@code
 *       MobilePlaybackActivity}; channel/playlist opens {@code MobileChannel(Uploads)}).
 *       Long-press -> {@link SearchPresenter#onVideoItemLongClicked} -> the {@code
 *       VideoMenuPresenter}/{@code AppDialogPresenter} context menu (rendered by {@code
 *       MobileAppDialogActivity}).</li>
 *   <li>The mic button runs {@link RecognizerIntent#ACTION_RECOGNIZE_SPEECH} via
 *       {@code startActivityForResult}; the recognized text is dropped into the field and
 *       searched. If voice is unavailable (e.g. emulator) it simply falls back to text input
 *       without crashing.</li>
 * </ul>
 */
public class MobileSearchActivity extends MobileActivity implements SearchView {
    private static final int SCROLL_END_THRESHOLD_ITEMS = 6;
    private static final int REQUEST_VOICE = 5001;
    /** Pause after the last keystroke before hitting the suggest endpoint. */
    private static final long SUGGEST_DEBOUNCE_MS = 200;

    private SearchPresenter mPresenter;

    private EditText mSearchInput;
    private ImageButton mBackButton;
    private ImageButton mClearButton;
    private ImageButton mMicButton;
    private RecyclerView mSuggestions;
    private SearchTagAdapter mTagAdapter;
    private RecyclerView mGrid;
    private GridLayoutManager mLayoutManager;
    private VideoCardAdapter mAdapter;
    private ProgressBar mProgressBar;
    private TextView mSearchMessage;

    private MediaServiceSearchTagProvider mTagsProvider;

    private final List<Video> mVideos = new ArrayList<>();
    private int mLastPaginationTriggerCount = -1;
    /** Guards against the TextWatcher reacting to programmatic field changes. */
    private boolean mSuppressTextWatcher;
    /** Debounced suggest reload for the CURRENT field text (one per keystroke burst). */
    private final Runnable mSuggestReload = () -> loadSearchTags(getSearchText());
    /**
     * Suggest responses arrive out of order on slow networks ("ab" landing after the user
     * backspaced to "a"). Each request takes a ticket; only the latest may render.
     */
    private int mSuggestGeneration;
    /** Last query handed to the presenter - the tap-to-retry target for the empty state. */
    private String mSubmittedQuery;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_mobile_search);

        bindViews();
        setupSuggestions();
        setupGrid();
        setupSearchInput();

        mBackButton.setOnClickListener(v -> onBackPressed());
        mMicButton.setOnClickListener(v -> startVoiceRecognition());
        // Clear the query, keep editing: focus stays, history rows replace the suggestions.
        mClearButton.setOnClickListener(v -> {
            setQueryText("");
            showKeyboard();
            loadSearchTags("");
        });

        mPresenter = SearchPresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    private void bindViews() {
        mSearchInput = findViewById(R.id.mobile_search_input);
        mBackButton = findViewById(R.id.mobile_search_back);
        mClearButton = findViewById(R.id.mobile_search_clear);
        mMicButton = findViewById(R.id.mobile_search_mic);
        mSuggestions = findViewById(R.id.mobile_search_suggestions);
        mGrid = findViewById(R.id.mobile_search_grid);
        mProgressBar = findViewById(R.id.mobile_search_progress);
        mSearchMessage = findViewById(R.id.mobile_search_message);
        mSearchMessage.setOnClickListener(v -> {
            if (mSubmittedQuery != null) {
                submitSearch(mSubmittedQuery);
            }
        });
    }

    private void setupSuggestions() {
        mTagAdapter = new SearchTagAdapter(this::onTagClicked, this::onTagLongClicked, this::onTagInserted);
        mSuggestions.setAdapter(mTagAdapter);
    }

    private void setupGrid() {
        mLayoutManager = new GridLayoutManager(this, computeSpanCount());
        mAdapter = new VideoCardAdapter(this::onVideoClicked, this::onVideoLongClicked);

        // Channel results render as full-width rows even when landscape/tablet uses 2+ columns.
        mLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return mAdapter.isFullSpan(position) ? mLayoutManager.getSpanCount() : 1;
            }
        });

        mGrid.setHasFixedSize(true);
        mGrid.setItemViewCacheSize(8);
        mGrid.setLayoutManager(mLayoutManager);
        mGrid.setAdapter(mAdapter);
        mGrid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                maybeTriggerPagination();
            }
        });
    }

    private void setupSearchInput() {
        mSearchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                syncClearButton();
                if (!mSuppressTextWatcher) {
                    // Debounce typed text (one suggest call per keystroke burst, not per key);
                    // an emptied field switches to history immediately - that transition is
                    // the visible one, and stale in-flight suggestions are generation-gated.
                    mSearchInput.removeCallbacks(mSuggestReload);
                    if (TextUtils.isEmpty(s)) {
                        loadSearchTags("");
                    } else {
                        mSearchInput.postDelayed(mSuggestReload, SUGGEST_DEBOUNCE_MS);
                    }
                }
            }
        });

        mSearchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch(mSearchInput.getText().toString());
                return true;
            }
            return false;
        });

        // Tapping back into the field re-opens the suggestion overlay: history when the
        // field is empty, live suggestions for the current text otherwise (YouTube-style).
        mSearchInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                loadSearchTags(getSearchText());
            }
        });
    }

    // ---------------------------------------------------------------------------------
    // Tag suggestions
    // ---------------------------------------------------------------------------------

    private void loadSearchTags(String query) {
        if (mTagsProvider == null) {
            return;
        }

        // Empty query → the suggest endpoint returns the user's search HISTORY (clock rows);
        // typed query → live suggestions (magnifier rows). The Tag model has no origin flag,
        // so the mode is decided by what we asked for.
        final boolean historyMode = TextUtils.isEmpty(query);
        final int generation = ++mSuggestGeneration;

        mTagsProvider.search(query, results -> runOnUiThread(() -> {
            if (generation != mSuggestGeneration) {
                return; // a newer request is in flight/rendered - this response is stale
            }
            mTagAdapter.setHistoryMode(historyMode);
            mTagAdapter.setTags(results);
            // Only surface the overlay while the user is actually editing the query — a slow
            // suggest response must not cover results that were submitted in the meantime.
            mSuggestions.setVisibility(mTagAdapter.isEmpty() || !mSearchInput.hasFocus()
                    ? View.GONE : View.VISIBLE);
        }));
    }

    private void onTagClicked(Tag tag) {
        if (tag == null || tag.tag == null) {
            return;
        }
        setQueryText(tag.tag);
        submitSearch(tag.tag);
    }

    /** NW arrow on a row: put the text into the field for refinement, don't search yet. */
    private void onTagInserted(Tag tag) {
        if (tag == null || tag.tag == null) {
            return;
        }
        setQueryText(tag.tag);
        loadSearchTags(tag.tag); // setQueryText suppresses the watcher; refresh suggestions manually
    }

    private boolean onTagLongClicked(Tag tag) {
        if (mPresenter == null || tag == null) {
            return false;
        }
        mPresenter.onTagLongClicked(tag);
        return true;
    }

    // ---------------------------------------------------------------------------------
    // Search submission
    // ---------------------------------------------------------------------------------

    /** Sets the field text without re-triggering the tag suggestion query. */
    private void setQueryText(String text) {
        mSuppressTextWatcher = true;
        mSearchInput.setText(text);
        if (text != null) {
            mSearchInput.setSelection(text.length());
        }
        mSuppressTextWatcher = false;
        // The watcher still ran (suppressed branch skips only the tag reload) but keep this
        // explicit: the clear button mirrors "field has text".
        syncClearButton();
    }

    private void syncClearButton() {
        if (mClearButton != null) {
            mClearButton.setVisibility(TextUtils.isEmpty(mSearchInput.getText()) ? View.GONE : View.VISIBLE);
        }
    }

    private void submitSearch(String query) {
        if (mPresenter == null || TextUtils.isEmpty(query)) {
            return;
        }

        mSearchInput.removeCallbacks(mSuggestReload); // a pending suggest reload is moot now
        mSuggestGeneration++;                         // and any in-flight response is stale
        mSubmittedQuery = query;
        mSearchMessage.setVisibility(View.GONE);
        hideKeyboard();
        mSearchInput.clearFocus();
        mSuggestions.setVisibility(View.GONE);
        mTagAdapter.clearTags();
        mPresenter.onSearch(query);
    }

    // ---------------------------------------------------------------------------------
    // Results grid callbacks
    // ---------------------------------------------------------------------------------

    private void onVideoClicked(Video video) {
        if (mPresenter == null) {
            return;
        }
        mPresenter.onVideoItemSelected(video);
        mPresenter.onVideoItemClicked(video);
    }

    private boolean onVideoLongClicked(Video video) {
        if (mPresenter == null) {
            return false;
        }
        mPresenter.onVideoItemLongClicked(video);
        return true;
    }

    private void maybeTriggerPagination() {
        if (mVideos.isEmpty() || mPresenter == null) {
            return;
        }

        int lastVisible = mLayoutManager.findLastVisibleItemPosition();
        int itemCount = mAdapter.getItemCount();

        if (lastVisible == RecyclerView.NO_POSITION || itemCount == 0) {
            return;
        }

        if (lastVisible >= itemCount - SCROLL_END_THRESHOLD_ITEMS && itemCount != mLastPaginationTriggerCount) {
            mLastPaginationTriggerCount = itemCount;
            mPresenter.onScrollEnd(mVideos.get(mVideos.size() - 1));
        }
    }

    private int computeSpanCount() {
        return com.newtube.mobile.ui.common.MobileGrid.computeSpanCount(this);
    }

    // ---------------------------------------------------------------------------------
    // Keyboard helpers
    // ---------------------------------------------------------------------------------

    private void showKeyboard() {
        mSearchInput.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(mSearchInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(mSearchInput.getWindowToken(), 0);
        }
    }

    // ---------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();

        if (mPresenter != null) {
            mPresenter.onViewResumed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (mPresenter != null) {
            mPresenter.onViewPaused();
        }
    }

    @Override
    protected void onDestroy() {
        if (mPresenter != null && mPresenter.getView() == this) {
            mPresenter.onViewDestroyed();
        }

        super.onDestroy();
    }

    // NOTE: back normally flows Activity.onBackPressed() -> MobileActivity.finish() ->
    // finishReally() below, which is the single place SearchPresenter.onFinish() is invoked.
    // (Previously it was also called here on back, so onFinish() ran twice per back press.)
    // The only local back handling: when the suggestion overlay covers existing RESULTS,
    // the first back dismisses the overlay instead of leaving the screen (YouTube-style).
    @Override
    public void onBackPressed() {
        if (mSuggestions.getVisibility() == View.VISIBLE && !mVideos.isEmpty()) {
            hideKeyboard();
            mSearchInput.clearFocus();
            mSuggestions.setVisibility(View.GONE);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (mLayoutManager != null) {
            mLayoutManager.setSpanCount(
                    com.newtube.mobile.ui.common.MobileGrid.computeSpanCount(newConfig));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_VOICE && resultCode == RESULT_OK && data != null) {
            List<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (results != null && !results.isEmpty()) {
                String spoken = results.get(0);
                setQueryText(spoken);
                submitSearch(spoken);
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // SearchView
    // ---------------------------------------------------------------------------------

    @Override
    public void updateSearch(VideoGroup group) {
        if (group == null) {
            return;
        }

        runOnUiThread(() -> {
            switch (group.getAction()) {
                case VideoGroup.ACTION_REPLACE:
                    mVideos.clear();
                    mVideos.addAll(hoistChannels(group.getVideos()));
                    break;
                case VideoGroup.ACTION_PREPEND:
                    mVideos.addAll(0, group.getVideos());
                    break;
                case VideoGroup.ACTION_REMOVE:
                    mVideos.removeAll(group.getVideos());
                    break;
                case VideoGroup.ACTION_SYNC:
                    syncVideos(group.getVideos());
                    break;
                case VideoGroup.ACTION_APPEND:
                default:
                    // The FIRST page after clearSearch() arrives as a plain APPEND — that's
                    // where the channel top-pick lives, so hoist there too. Continuation
                    // pages (mVideos non-empty) keep API order.
                    appendNew(mVideos.isEmpty() ? hoistChannels(group.getVideos()) : group.getVideos());
                    break;
            }

            mLastPaginationTriggerCount = -1; // allow pagination to fire again at the new size
            mAdapter.submitList(new ArrayList<>(mVideos));
            if (!mVideos.isEmpty()) {
                mSearchMessage.setVisibility(View.GONE);
            }
        });
    }

    private void appendNew(List<Video> videos) {
        for (Video video : videos) {
            if (!mVideos.contains(video)) {
                mVideos.add(video);
            }
        }
    }

    /**
     * YouTube pins the matching channel above the videos when a query clearly names a
     * channel; the API expresses that by ranking a channel item near the top of the FIRST
     * results page. Honor it: channel rows found in the page's top slice move to the very
     * top (stable order otherwise). Channels ranked deep in the page stay inline — that's
     * the API saying the match wasn't clear.
     */
    private static List<Video> hoistChannels(List<Video> videos) {
        final int topSlice = 10;

        List<Video> channels = new ArrayList<>(2);
        List<Video> rest = new ArrayList<>(videos.size());
        int index = 0;
        for (Video video : videos) {
            // isPlaylistAsChannel: playlist results share the channel item shape (null videoId
            // + browse channelId) but must stay inline as cards, never hoisted as a channel pick.
            if (video != null && video.isChannel() && !video.isPlaylistAsChannel() && index < topSlice) {
                channels.add(video);
            } else {
                rest.add(video);
            }
            index++;
        }

        if (channels.isEmpty()) {
            return videos;
        }

        channels.addAll(rest);
        return channels;
    }

    private void syncVideos(List<Video> videos) {
        for (Video video : videos) {
            int idx = mVideos.indexOf(video);
            if (idx >= 0) {
                mVideos.set(idx, video);
            }
        }
    }

    @Override
    public void clearSearch() {
        runOnUiThread(() -> {
            mVideos.clear();
            mLastPaginationTriggerCount = -1;
            mAdapter.submitList(new ArrayList<>());
        });
    }

    @Override
    public void clearSearchTags() {
        runOnUiThread(() -> {
            mTagAdapter.clearTags();
            mSuggestions.setVisibility(View.GONE);
        });
    }

    @Override
    public void removeSearchTag(Tag tag) {
        runOnUiThread(() -> {
            mTagAdapter.removeTag(tag);
            if (mTagAdapter.isEmpty()) {
                mSuggestions.setVisibility(View.GONE);
            }
        });
    }

    @Override
    public void setTagsProvider(MediaServiceSearchTagProvider provider) {
        mTagsProvider = provider;
    }

    @Override
    public void showProgressBar(boolean show) {
        runOnUiThread(() -> {
            mProgressBar.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show) {
                mSearchMessage.setVisibility(View.GONE);
            } else if (mVideos.isEmpty() && mSubmittedQuery != null
                    && mSuggestions.getVisibility() != View.VISIBLE) {
                // SearchView has no error callback: a failed load and a zero-result search
                // both end exactly here (spinner off, grid empty). Anything beats the old
                // behavior of silently showing a blank screen.
                mSearchMessage.setVisibility(View.VISIBLE);
            }
        });
    }

    @Override
    public void startSearch(String searchText) {
        runOnUiThread(() -> {
            if (TextUtils.isEmpty(searchText)) {
                // Opened fresh (no query): clear the field, focus it, surface the keyboard,
                // and show the user's search history right away (the focus listener only
                // fires on focus CHANGES, so ask explicitly too — the load is deduped by
                // the suggest endpoint being idempotent for the same query).
                setQueryText("");
                showKeyboard();
                loadSearchTags("");
            } else {
                setQueryText(searchText);
                submitSearch(searchText);
            }
        });
    }

    @Override
    public String getSearchText() {
        return mSearchInput.getText().toString();
    }

    @Override
    public void startVoiceRecognition() {
        runOnUiThread(this::launchVoiceRecognition);
    }

    private void launchVoiceRecognition() {
        if (!isRecognitionAvailable()) {
            // Voice unavailable (e.g. emulator without a recognizer): fall back to text.
            showKeyboard();
            return;
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.search_hint));

        try {
            startActivityForResult(intent, REQUEST_VOICE);
        } catch (ActivityNotFoundException e) {
            // No voice activity to handle the intent: fall back to text input.
            showKeyboard();
        }
    }

    private boolean isRecognitionAvailable() {
        try {
            return SpeechRecognizer.isRecognitionAvailable(this);
        } catch (NullPointerException e) {
            return false;
        }
    }

    @Override
    public void finishReally() {
        // Single call site for SearchPresenter.onFinish() (see the onBackPressed note above).
        if (mPresenter != null && mPresenter.getView() == this) {
            mPresenter.onFinish();
        }
        super.finishReally();
    }
}
