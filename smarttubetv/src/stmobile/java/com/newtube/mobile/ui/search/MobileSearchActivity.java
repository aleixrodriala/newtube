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
import android.util.DisplayMetrics;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;

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
 *       shown as a horizontal chip strip ({@link SearchTagAdapter}) above the results.
 *       Submitting (keyboard search action) or tapping a chip runs the real search via
 *       {@link SearchPresenter#onSearch(String)}.</li>
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

    private SearchPresenter mPresenter;

    private EditText mSearchInput;
    private ImageButton mBackButton;
    private ImageButton mMicButton;
    private RecyclerView mSuggestions;
    private SearchTagAdapter mTagAdapter;
    private RecyclerView mGrid;
    private GridLayoutManager mLayoutManager;
    private VideoCardAdapter mAdapter;
    private ProgressBar mProgressBar;

    private MediaServiceSearchTagProvider mTagsProvider;

    private final List<Video> mVideos = new ArrayList<>();
    private int mLastPaginationTriggerCount = -1;
    /** Guards against the TextWatcher reacting to programmatic field changes. */
    private boolean mSuppressTextWatcher;

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

        mPresenter = SearchPresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    private void bindViews() {
        mSearchInput = findViewById(R.id.mobile_search_input);
        mBackButton = findViewById(R.id.mobile_search_back);
        mMicButton = findViewById(R.id.mobile_search_mic);
        mSuggestions = findViewById(R.id.mobile_search_suggestions);
        mGrid = findViewById(R.id.mobile_search_grid);
        mProgressBar = findViewById(R.id.mobile_search_progress);
    }

    private void setupSuggestions() {
        mTagAdapter = new SearchTagAdapter(this::onTagClicked, this::onTagLongClicked);
        mSuggestions.setAdapter(mTagAdapter);
    }

    private void setupGrid() {
        mLayoutManager = new GridLayoutManager(this, computeSpanCount());
        mAdapter = new VideoCardAdapter(this::onVideoClicked, this::onVideoLongClicked);

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
                if (!mSuppressTextWatcher) {
                    loadSearchTags(s.toString());
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
    }

    // ---------------------------------------------------------------------------------
    // Tag suggestions
    // ---------------------------------------------------------------------------------

    private void loadSearchTags(String query) {
        if (mTagsProvider == null) {
            return;
        }

        mTagsProvider.search(query, results -> runOnUiThread(() -> {
            mTagAdapter.setTags(results);
            mSuggestions.setVisibility(mTagAdapter.isEmpty() ? View.GONE : View.VISIBLE);
        }));
    }

    private void onTagClicked(Tag tag) {
        if (tag == null || tag.tag == null) {
            return;
        }
        setQueryText(tag.tag);
        submitSearch(tag.tag);
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
    }

    private void submitSearch(String query) {
        if (mPresenter == null || TextUtils.isEmpty(query)) {
            return;
        }

        hideKeyboard();
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
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        float cardWidthPx = getResources().getDimension(R.dimen.mobile_card_target_width);
        float spacingPx = getResources().getDimension(R.dimen.mobile_card_spacing);

        int span = (int) (metrics.widthPixels / (cardWidthPx + spacingPx));

        return Math.max(2, span);
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

        showSystemBars();
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

    // NOTE: no onBackPressed() override. Back -> Activity.onBackPressed() -> MobileActivity.finish()
    // -> finishReally() below, which is the single place SearchPresenter.onFinish() is invoked.
    // (Previously it was also called here on back, so onFinish() ran twice per back press.)

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (mLayoutManager != null) {
            mLayoutManager.setSpanCount(computeSpanCount());
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
                    mVideos.addAll(group.getVideos());
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
                    appendNew(group.getVideos());
                    break;
            }

            mLastPaginationTriggerCount = -1; // allow pagination to fire again at the new size
            mAdapter.submitList(new ArrayList<>(mVideos));
        });
    }

    private void appendNew(List<Video> videos) {
        for (Video video : videos) {
            if (!mVideos.contains(video)) {
                mVideos.add(video);
            }
        }
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
        runOnUiThread(() -> mProgressBar.setVisibility(show ? View.VISIBLE : View.GONE));
    }

    @Override
    public void startSearch(String searchText) {
        runOnUiThread(() -> {
            if (TextUtils.isEmpty(searchText)) {
                // Opened fresh (no query): clear the field, focus it, surface the keyboard.
                setQueryText("");
                showKeyboard();
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
