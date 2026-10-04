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
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.search.MediaServiceSearchTagProvider;
import com.liskovsoft.smartyoutubetv2.common.app.models.search.vineyard.Tag;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SearchPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.SearchView;
import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.browse.VideoCardAdapter;
import com.newtube.mobile.ui.common.FilteredPageTopUp;
import com.newtube.mobile.ui.common.MobileActivity;
import com.newtube.mobile.ui.common.ShortsFilter;
import com.newtube.mobile.ui.common.SkeletonReveal;
import com.newtube.mobile.ui.playback.MiniPlayerBridge;
import com.newtube.mobile.ui.playback.MobileMiniPlayerController;

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
public class MobileSearchActivity extends MobileActivity
        implements SearchView, MiniPlayerBridge.MiniHost {
    private static final int SCROLL_END_THRESHOLD_ITEMS = 6;
    private static final int REQUEST_VOICE = 5001;
    /** Pause after the last keystroke before hitting the suggest endpoint. */
    private static final long SUGGEST_DEBOUNCE_MS = 200;

    private SearchPresenter mPresenter;

    /** Docks the live player card when a video opened from these results is minimized. */
    private MobileMiniPlayerController mMiniPlayer;

    private EditText mSearchInput;
    private ImageButton mBackButton;
    private ImageButton mClearButton;
    private ImageButton mMicButton;
    private RecyclerView mSuggestions;
    private SearchTagAdapter mTagAdapter;
    private RecyclerView mGrid;
    private GridLayoutManager mLayoutManager;
    private VideoCardAdapter mAdapter;
    private View mSkeleton;
    /** The presenter's loading state (showProgressBar), for a clear that lands mid-load. */
    private boolean mLoading;
    private View mSearchMessage;
    private SearchLoadState mLoadState;
    /** LoadFailure state of the search in flight, set by showLoadFailure; -1 = none reported. */
    private int mPendingFailure = -1;

    private MediaServiceSearchTagProvider mTagsProvider;

    private final List<Video> mVideos = new ArrayList<>();
    private int mLastPaginationTriggerCount = -1;
    /**
     * NEWTUBE(shorts): this search's result groups in arrival order (the first is the main list),
     * for {@link #topUpIfShort}: with its Shorts dropped a page can be too short to scroll.
     */
    private final List<VideoGroup> mResultGroups = new ArrayList<>();
    private final FilteredPageTopUp mTopUp = new FilteredPageTopUp();
    /** Which query a result group belongs to: a late page of an old query is dropped. */
    private final FilteredPageTopUp.Generations<VideoGroup> mGenerations = new FilteredPageTopUp.Generations<>();
    /** onDestroy has begun: the presenter's teardown must not start another page. */
    private boolean mTornDown;
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
    /** Prevent an IME that emits both editor-action and raw-key callbacks from searching twice. */
    private long mLastSubmitAtMs;
    private String mLastSubmitText;
    private int mSubmitSequence;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_mobile_search);

        registerBackHandler(this::handleBack);

        bindViews();
        mMiniPlayer = new MobileMiniPlayerController(this);
        setupSuggestions();
        setupGrid();
        setupSearchInput();

        mBackButton.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
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
        // NEWTUBE(mini-inset): the last row can scroll clear of the docked mini-player card.
        com.newtube.mobile.ui.playback.MiniPlayerListInset.attach(findViewById(R.id.mobile_mini_player), mGrid);
        mSkeleton = findViewById(R.id.mobile_feed_skeleton);
        com.newtube.mobile.ui.common.MobileGrid.setupFeedSkeleton(this, (android.view.ViewGroup) mSkeleton);
        installImeInsets((View) mGrid.getParent());
        mSearchMessage = findViewById(R.id.mobile_search_message);
        mLoadState = new SearchLoadState(mSearchMessage, () -> {
            if (mSubmittedQuery != null) {
                submitSearch(mSubmittedQuery);
            }
        });
    }

    /**
     * NEWTUBE(motion): the results area ends at the keyboard's top edge and follows it frame by
     * frame as it slides. Under enforced edge-to-edge (Android 15+ for this target) adjustResize no
     * longer resizes the window, so the keyboard simply covered the lower suggestions and results;
     * where the window is still resized for it (the opt-out, up to Android 15) the keyboard's inset
     * arrives already absorbed and this adds nothing. The content container already keeps clear of
     * the navigation bar, which the keyboard's inset includes.
     */
    private boolean mImeAnimating;

    private void installImeInsets(View area) {
        ViewCompat.setOnApplyWindowInsetsListener(area, (v, insets) -> {
            if (!mImeAnimating) {
                applyImePadding(v, insets);
            }
            return insets;
        });
        ViewCompat.setWindowInsetsAnimationCallback(area,
                new WindowInsetsAnimationCompat.Callback(WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_STOP) {
                    @Override
                    public void onPrepare(@NonNull WindowInsetsAnimationCompat animation) {
                        if ((animation.getTypeMask() & WindowInsetsCompat.Type.ime()) != 0) {
                            mImeAnimating = true;
                        }
                    }

                    @NonNull
                    @Override
                    public WindowInsetsCompat onProgress(@NonNull WindowInsetsCompat insets,
                            @NonNull java.util.List<WindowInsetsAnimationCompat> running) {
                        if (mImeAnimating) {
                            applyImePadding(area, insets);
                        }
                        return insets;
                    }

                    @Override
                    public void onEnd(@NonNull WindowInsetsAnimationCompat animation) {
                        if ((animation.getTypeMask() & WindowInsetsCompat.Type.ime()) != 0) {
                            mImeAnimating = false;
                            ViewCompat.requestApplyInsets(area);
                        }
                    }
                });
    }

    private static void applyImePadding(View area, WindowInsetsCompat insets) {
        int ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
        int bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
        int bottom = Math.max(0, ime - bars);
        if (area.getPaddingBottom() != bottom) {
            area.setPadding(area.getPaddingLeft(), area.getPaddingTop(), area.getPaddingRight(), bottom);
        }
    }

    private void setupSuggestions() {
        mTagAdapter = new SearchTagAdapter(this::onTagClicked, this::onTagLongClicked, this::onTagInserted);
        mSuggestions.setAdapter(mTagAdapter);
    }

    private void setupGrid() {
        mLayoutManager = new GridLayoutManager(this, computeSpanCount());
        mAdapter = new VideoCardAdapter(this::onVideoClicked, this::onVideoLongClicked);
        mAdapter.setFeedLayout(com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData.instance(this).getMobileFeedLayout());

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
        // Next cards' thumbnails decoded before they scroll in (no grey card + fade on a fling).
        com.newtube.mobile.ui.common.FeedThumbnailPreloader.attach(mGrid, mAdapter);
        mGrid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                // dy == 0: a layout pass changed the visible range, not the user.
                maybeTriggerPagination(dy != 0);
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
                    // NEWTUBE(page-load-errors): the "No results for ..." / offline state speaks for
                    // the SUBMITTED query; once the text is edited it would be about the wrong one
                    // (and it showed through an empty suggestion list).
                    mSearchMessage.setVisibility(View.GONE);
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
            int keyCode = event != null ? event.getKeyCode() : KeyEvent.KEYCODE_UNKNOWN;
            int keyAction = event != null ? event.getAction() : -1;
            if (isSearchSubmission(actionId, keyCode, keyAction)) {
                submitSearch(mSearchInput.getText().toString(),
                        actionId == EditorInfo.IME_ACTION_SEARCH ? "ime-search" : "editor-enter");
                return true;
            }
            return false;
        });

        // Some hardware keyboards and Android/ADB input paths deliver Enter only through OnKey
        // (IME action is NULL); consume ACTION_UP so a DOWN+UP pair cannot submit twice.
        mSearchInput.setOnKeyListener((v, keyCode, event) -> {
            if (isSearchSubmission(EditorInfo.IME_NULL, keyCode, event.getAction())) {
                submitSearch(mSearchInput.getText().toString(), "raw-enter");
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
        submitSearch(query, "ui");
    }

    private void submitSearch(String query, String source) {
        String normalized = query != null ? query.trim() : "";
        if (mPresenter == null || TextUtils.isEmpty(normalized)) {
            NetPath.log("search-submit ignored source=" + source + " reason="
                    + (mPresenter == null ? "presenter-null" : "empty"));
            return;
        }

        long now = android.os.SystemClock.uptimeMillis();
        if (normalized.equals(mLastSubmitText) && now - mLastSubmitAtMs < 750) {
            NetPath.log("search-submit deduped source=" + source
                    + " chars=" + normalized.length());
            return;
        }
        mLastSubmitAtMs = now;
        mLastSubmitText = normalized;
        int sequence = ++mSubmitSequence;
        NetPath.log("search-submit sid=" + sequence + " source=" + source
                + " chars=" + normalized.length()
                + " hadFocus=" + (mSearchInput.hasFocus() ? "y" : "n"));

        mSearchInput.removeCallbacks(mSuggestReload); // a pending suggest reload is moot now
        mSuggestGeneration++;                         // and any in-flight response is stale
        mSubmittedQuery = normalized;
        mPendingFailure = -1;
        mSearchMessage.setVisibility(View.GONE);
        hideKeyboard();
        mSearchInput.clearFocus();
        mSuggestions.setVisibility(View.GONE);
        mTagAdapter.clearTags();
        mPresenter.onSearch(normalized);
    }

    /** Pure submission predicate kept package-visible for the hardware/IME regression test. */
    static boolean isSearchSubmission(int actionId, int keyCode, int keyAction) {
        boolean imeAction = actionId == EditorInfo.IME_ACTION_SEARCH
                || actionId == EditorInfo.IME_ACTION_DONE
                || actionId == EditorInfo.IME_ACTION_GO
                || actionId == EditorInfo.IME_ACTION_SEND;
        boolean enterReleased = keyCode == KeyEvent.KEYCODE_ENTER
                && keyAction == KeyEvent.ACTION_UP;
        return imeAction || enterReleased;
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

    private void maybeTriggerPagination(boolean userScroll) {
        if (mVideos.isEmpty() || mPresenter == null) {
            return;
        }

        int lastVisible = mLayoutManager.findLastVisibleItemPosition();
        int itemCount = mAdapter.getItemCount();

        if (lastVisible == RecyclerView.NO_POSITION || itemCount == 0) {
            return;
        }

        if (lastVisible >= itemCount - SCROLL_END_THRESHOLD_ITEMS && itemCount != mLastPaginationTriggerCount) {
            // NEWTUBE(search-more): continue the main results, not the group of the last card.
            // The last cards usually belong to a trailing shelf ("Over 20 minutes", 9 videos, no
            // next page), and asking for ITS next page failed, so search never loaded more.
            VideoGroup more = firstGroupWithMore();
            if (more == null) {
                return; // the results have ended
            }
            if (userScroll) {
                mTopUp.onUserAction(); // NEWTUBE(shorts): the user asked for more
            } else if (!mTopUp.takeAutomatic(true)) {
                return; // a layout pass, not the user: only within the top-up budget
            }
            mLastPaginationTriggerCount = itemCount;
            mPresenter.onScrollEnd(lastOf(more));
        }
    }

    /** The first result group that still has a next page - the main results come first. */
    private VideoGroup firstGroupWithMore() {
        for (VideoGroup group : mResultGroups) {
            if (group.getNextPageKey() != null && !group.isEmpty()) {
                return group;
            }
        }
        return null;
    }

    private static Video lastOf(VideoGroup group) {
        List<Video> videos = group.getVideos();
        return videos.get(videos.size() - 1);
    }

    /**
     * NEWTUBE(shorts): called when loading stops (the spinner goes off). With its Shorts dropped
     * the list may be too short to scroll, and then nothing would ever ask for the next page
     * ("shorts funny" kept 1 video of 135): fetch it now - only right after a page of this query
     * landed (not after a cancel or a failure) and within {@link FilteredPageTopUp}'s budget.
     * Returns whether a page was asked for.
     */
    private boolean topUpIfShort() {
        if (mPresenter == null || mTornDown || isFinishing() || isDestroyed()) {
            return false;
        }

        VideoGroup next = firstGroupWithMore();
        if (!mTopUp.take(mVideos.size(), next != null)) {
            return false;
        }

        NetPath.log("search-topup sid=" + mSubmitSequence + " page=" + mTopUp.pages()
                + " results=" + mVideos.size() + " group=" + mResultGroups.indexOf(next));
        mPresenter.onScrollEnd(lastOf(next));
        return true;
    }

    private static boolean containsSame(List<VideoGroup> groups, VideoGroup group) {
        for (VideoGroup item : groups) {
            if (item == group) {
                return true;
            }
        }
        return false;
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
        // Last-resumed host wins: a video opened from these results minimizes back onto them.
        MiniPlayerBridge.registerMiniHost(this);
        if (mMiniPlayer != null) {
            mMiniPlayer.sync(false);
        }
    }

    @Override
    protected void onPause() {
        // Free the mini bar's video surface whenever this screen leaves the foreground - the
        // playback activity may be about to re-claim it (expand / new video).
        // NEWTUBE(motion): a docked card stays up frozen until the player covers it.
        if (mMiniPlayer != null) {
            mMiniPlayer.onHostPause();
        }
        super.onPause();

        if (mPresenter != null) {
            mPresenter.onViewPaused();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mMiniPlayer != null) {
            mMiniPlayer.onHostStop();
        }
    }

    @Override
    protected void onDestroy() {
        MiniPlayerBridge.unregisterMiniHost(this);
        mTornDown = true; // NEWTUBE(shorts): the teardown's spinner-off must not page on

        if (mPresenter != null && mPresenter.getView() == this) {
            mPresenter.onViewDestroyed();
        }

        super.onDestroy();
    }

    @Override
    public boolean prepareMiniPlayerForHandoff(Runnable onDrawn) {
        return mMiniPlayer != null && mMiniPlayer.prepareForHandoff(onDrawn);
    }

    @Override
    public Class<?> getMiniHostViewClass() {
        return SearchView.class;
    }

    @Override
    public int getMiniCardBottomOffsetPx() {
        // Overlay card (mobile_mini_player_overlay.xml) sits flush at the content bottom.
        return 0;
    }

    // NOTE: back normally flows OnBackPressedDispatcher -> MobileActivity.finish() ->
    // finishReally() below, which is the single place SearchPresenter.onFinish() is invoked.
    // (Previously it was also called here on back, so onFinish() ran twice per back press.)
    // The only local back handling: when the suggestion overlay covers existing RESULTS,
    // the first back dismisses the overlay instead of leaving the screen (YouTube-style).
    private void handleBack() {
        if (mSuggestions.getVisibility() == View.VISIBLE && !mVideos.isEmpty()) {
            hideKeyboard();
            mSearchInput.clearFocus();
            mSuggestions.setVisibility(View.GONE);
            return;
        }
        finish();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (mLayoutManager != null) {
            mLayoutManager.setSpanCount(
                    com.newtube.mobile.ui.common.MobileGrid.computeSpanCount(this, newConfig));
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
            int incoming = group.getVideos() != null ? group.getVideos().size() : 0;
            List<Video> shown = ShortsFilter.withoutShorts(group.getVideos()); // NEWTUBE(shorts)
            int shortsHidden = incoming - (shown != null ? shown.size() : 0);
            if (group.getAction() != VideoGroup.ACTION_REMOVE && group.getAction() != VideoGroup.ACTION_SYNC) {
                if (!mGenerations.accept(group)) {
                    // NEWTUBE(shorts): a page of an earlier query, landing after this one began.
                    NetPath.log("search-results sid=" + mSubmitSequence + " stale page dropped");
                    return;
                }
                if (group.getAction() == VideoGroup.ACTION_REPLACE) {
                    mResultGroups.clear();
                }
                if (!containsSame(mResultGroups, group)) {
                    mResultGroups.add(group);
                }
                mTopUp.onPageLanded(shortsHidden);
            }
            switch (group.getAction()) {
                case VideoGroup.ACTION_REPLACE:
                    mVideos.clear();
                    mVideos.addAll(hoistChannels(shown));
                    break;
                case VideoGroup.ACTION_PREPEND:
                    mVideos.addAll(0, shown);
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
                    appendNew(mVideos.isEmpty() ? hoistChannels(shown) : shown);
                    break;
            }

            mLastPaginationTriggerCount = -1; // allow pagination to fire again at the new size
            mAdapter.submitList(new ArrayList<>(mVideos));
            NetPath.log("search-results sid=" + mSubmitSequence
                    + " action=" + group.getAction() + " incoming=" + incoming
                    + " shortsHidden=" + shortsHidden + " total=" + mVideos.size());
            if (!mVideos.isEmpty()) {
                mSearchMessage.setVisibility(View.GONE);
                setSkeletonVisible(false);
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

    /**
     * NEWTUBE(motion): the results' loading state - card ghosts under a shimmer where a spinner
     * used to sit, only while the list is empty (a next page loads silently below the rows). They
     * fade out over the first results, and go at once when none come (the message takes over).
     */
    private void setSkeletonVisible(boolean visible) {
        if (visible) {
            mSkeletonShows++;
            mSkeleton.animate().cancel();
            mSkeleton.setAlpha(1f);
            mSkeleton.setVisibility(View.VISIBLE);
            return;
        }
        if (mSkeleton.getVisibility() != View.VISIBLE) {
            return;
        }
        mSkeleton.animate().cancel();
        if (mVideos.isEmpty()) {
            mSkeleton.setVisibility(View.GONE);
            return;
        }
        int shows = mSkeletonShows;
        SkeletonReveal.fadeOverCards(mSkeleton, mGrid, () -> shows == mSkeletonShows, () -> {
            mSkeleton.setVisibility(View.GONE);
            mSkeleton.setAlpha(1f);
        });
    }

    /** Counts skeleton shows, so a reveal waiting for its results yields to a newer search. */
    private int mSkeletonShows;

    @Override
    public void clearSearch() {
        runOnUiThread(() -> {
            mVideos.clear();
            mLastPaginationTriggerCount = -1;
            mResultGroups.clear();
            mTopUp.clear();
            mGenerations.next();
            mAdapter.submitList(new ArrayList<>());
            if (mLoading) {
                setSkeletonVisible(true); // the load began over the previous results
            }
            NetPath.log("search-results sid=" + mSubmitSequence + " cleared");
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
    public void showLoadFailure(int state) {
        runOnUiThread(() -> mPendingFailure = state);
    }

    @Override
    public void setTagsProvider(MediaServiceSearchTagProvider provider) {
        mTagsProvider = provider;
    }

    @Override
    public void showProgressBar(boolean show) {
        runOnUiThread(() -> {
            if (!show && topUpIfShort()) {
                return; // NEWTUBE(shorts): another page is on its way - still loading
            }
            mLoading = show;
            if (show || mSkeleton.getVisibility() != View.VISIBLE) {
                setSkeletonVisible(show && mVideos.isEmpty());
            } else {
                // NEWTUBE(motion): results handed over right after "done" fade the skeleton out
                // over themselves; decided once they are in.
                mSkeleton.post(() -> {
                    if (!mLoading) {
                        setSkeletonVisible(false);
                    }
                });
            }
            NetPath.log("search-progress sid=" + mSubmitSequence
                    + " visible=" + (show ? "y" : "n")
                    + " results=" + mVideos.size());
            if (show) {
                mSearchMessage.setVisibility(View.GONE);
            } else if (mVideos.isEmpty() && mSubmittedQuery != null
                    && mSuggestions.getVisibility() != View.VISIBLE) {
                // A failed load and a zero-result search both end exactly here (spinner off,
                // grid empty); showLoadFailure, when the presenter reported an error, tells
                // them apart. Without one the device's network decides: offline, or no results.
                // Retry stays unless the search positively came back empty: a request that failed
                // into a cause-free error (a 503 surfaces that way) classifies as EMPTY too.
                boolean failed = mPendingFailure >= 0;
                mLoadState.show(failed ? mPendingFailure
                        : com.liskovsoft.smartyoutubetv2.common.utils.LoadFailure.classify(this, null),
                        mSubmittedQuery, !failed);
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
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.mobile_search_hint));

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
