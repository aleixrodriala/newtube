package com.newtube.mobile.casting;

import android.app.Activity;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.DigitsKeyListener;
import androidx.activity.OnBackPressedCallback;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.liskovsoft.mediaserviceinterfaces.CastSenderService;
import com.liskovsoft.mediaserviceinterfaces.data.CastScreen;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.casting.castv2.CastV2Discovery;
import com.newtube.mobile.casting.castv2.MdxScreenIdReader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * The cast device picker: one bottom sheet, ONE row per physical device, plain-words copy - the
 * earlier per-protocol row list (three rows for one TV, badge jargon, explainer card) was
 * reviewed on-device and rejected as too technical.
 *
 * <p>Merging, per device: a live mDNS Cast receiver is a single row that carries BOTH its
 * abilities (Direct cast and its YouTube app); a persisted Lounge screen folds into that row by
 * NAME ({@link #takeSavedScreenIdMatching}), donating its screenId so the YouTube-app mode can
 * connect without re-running the mdx read; a DIAL device folds in by host (same IP = same
 * device, the strongest correlation we have). Saved screens and DIAL TVs with no live Cast twin
 * stay their own "YouTube app on the TV" row and connect immediately on tap.</p>
 *
 * <p>A mode choice is only shown when a choice exists: tapping a Cast-device row swaps the sheet
 * content IN PLACE (same sheet, no stacked dialog) for a two-option chooser - "Cast without ads"
 * (default, Route A) and "Use the TV's YouTube app" (saved screenId, else the mdx shim with an
 * inline spinner). The chooser header and the system back key both return to the device list;
 * the tradeoff copy lives in the option subtitles, exactly where the decision happens.</p>
 *
 * <p>Both discoveries run only while the sheet is open (stopped on dismiss); either failing
 * silently is fine - the caller already gated on the local-network permission. The sheet is
 * presented through the host player's immersive-safe presenter (MobilePlaybackActivity
 * .showPlayerSheet). When the Lounge sender hasn't landed ({@code getCastSenderService()} ==
 * null) the picker still opens and lists devices, but Lounge connect/pair attempts toast "not
 * available yet" (Direct cast is self-contained and unaffected).</p>
 */
public class CastPickerSheet {

    /** Presents a built sheet with the player's immersive handling (showPlayerSheet). */
    public interface SheetPresenter {
        void present(BottomSheetDialog dialog);
    }

    private static final String TAG = CastPickerSheet.class.getSimpleName();
    private static final int TV_CODE_LENGTH = 12;
    /** mdx shim budget: channel open + YouTube-app launch + first mdxSessionStatus answer. */
    private static final long MDX_READ_TIMEOUT_MS = 15_000;

    private final Activity mActivity;
    private final CastSessionManager mSessionManager;
    private final DialDiscovery mDiscovery;
    private final CastV2Discovery mCastDiscovery;
    /** Dedupe key -> row view, so re-discoveries update in place instead of duplicating rows. */
    private final Map<String, View> mRows = new HashMap<>();
    /** Dedupe key -> target, so the merges can inspect what a row represents. */
    private final Map<String, CastTarget> mRowTargets = new HashMap<>();
    /** Hosts seen over mDNS; DIAL rows resolving to one of these are suppressed (Cast row wins). */
    private final Set<String> mCastHosts = new HashSet<>();

    private BottomSheetDialog mDialog;
    private View mListSection;
    private View mChooserSection;
    private TextView mChooserTitle;
    private LinearLayout mChooserOptions;
    private LinearLayout mTargetsContainer;
    private View mProgressRow;
    private TextView mEmptyView;
    private Disposable mPairAction;
    /** One launch/pairing flow at a time - shared by the DIAL launch and the mdx shim. */
    private boolean mLaunchInFlight;
    /**
     * Bumped every time the chooser leaves the screen (back / new chooser). An mdx read that
     * finishes after its chooser is gone compares generations and must NOT connect - the user
     * backing out mid-spinner is a cancel. (The successful pairing is still persisted: it's
     * real, and it makes the option instant next time.)
     */
    private int mChooserGeneration;
    /** Enabled only while the chooser is up: back returns to the device list instead of dismissing. */
    @Nullable
    private OnBackPressedCallback mChooserBackCallback;

    public CastPickerSheet(Activity activity) {
        mActivity = activity;
        mSessionManager = CastSessionManager.instance(activity);
        mDiscovery = new DialDiscovery(activity);
        mCastDiscovery = new CastV2Discovery(activity);
    }

    /** Build and present the picker; discovery starts now and stops when the sheet is dismissed. */
    public void show(SheetPresenter presenter) {
        mDialog = new BottomSheetDialog(mActivity);
        View content = LayoutInflater.from(mActivity).inflate(R.layout.sheet_mobile_cast, null);
        mDialog.setContentView(content);

        mListSection = content.findViewById(R.id.cast_sheet_list);
        mChooserSection = content.findViewById(R.id.cast_sheet_chooser);
        mChooserTitle = content.findViewById(R.id.cast_chooser_title);
        mChooserOptions = content.findViewById(R.id.cast_chooser_options);
        mTargetsContainer = content.findViewById(R.id.cast_sheet_targets);
        mProgressRow = content.findViewById(R.id.cast_sheet_progress);
        mEmptyView = content.findViewById(R.id.cast_sheet_empty);
        content.findViewById(R.id.cast_sheet_link_code).setOnClickListener(v -> showCodeDialog());
        content.findViewById(R.id.cast_chooser_back).setOnClickListener(v -> showDeviceList());

        // Persisted screens first: they're instant, and paired screens must reappear. A screen
        // whose device also shows up live over mDNS gets merged into that device's row later
        // (onCastDeviceFound); until then it is its own connectable row.
        for (CastScreen screen : CastPrefs.getPairedScreens(mActivity)) {
            addOrUpdateRow(CastTarget.fromPairedScreen(screen));
        }

        mDialog.setOnDismissListener(d -> teardown());

        mDiscovery.start(new DialDiscovery.Listener() {
            @Override
            public void onTargetFound(CastTarget target) {
                addOrUpdateRow(target);
            }

            @Override
            public void onDiscoveryFinished() {
                if (mProgressRow != null) {
                    mProgressRow.setVisibility(View.GONE);
                }
                if (mEmptyView != null && mRows.isEmpty()) {
                    mEmptyView.setVisibility(View.VISIBLE);
                }
            }
        });

        // mDNS Cast discovery in parallel (callbacks on main, deduped by host inside).
        mCastDiscovery.start(this::onCastDeviceFound);

        presenter.present(mDialog);

        // Back = list, not dismiss, while the chooser is up. The material BottomSheetDialog routes
        // back through its OnBackPressedDispatcher (which pre-empts Dialog#setOnKeyListener on API
        // 33+), so intercept THERE: a callback registered after show() sits at the front of the
        // LIFO dispatcher, ahead of the dialog's own dismiss callback. Enabled only on the chooser.
        mChooserBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                showDeviceList();
            }
        };
        mDialog.getOnBackPressedDispatcher().addCallback(mChooserBackCallback);
    }

    private void teardown() {
        mDiscovery.stop();
        mCastDiscovery.stop();
        if (mPairAction != null && !mPairAction.isDisposed()) {
            mPairAction.dispose();
        }
        mPairAction = null;
    }

    // ---------------------------------------------------------------------------------
    // Device rows (merged: one per physical device)
    // ---------------------------------------------------------------------------------

    /**
     * A live Cast receiver becomes THE row for its physical device: any DIAL row on the same
     * host and any saved Lounge screen with the same name fold into it, and whatever screenId
     * they donate rides along so the YouTube-app mode connects instantly.
     */
    private void onCastDeviceFound(String name, String host, int port) {
        mCastHosts.add(host);
        // Host match first (same IP = same device beats any name heuristic)...
        String savedScreenId = removeDialRowsForHost(host);
        if (savedScreenId == null) {
            // ...then the name match against saved screens (see takeSavedScreenIdMatching for
            // why name is the only feasible key).
            savedScreenId = takeSavedScreenIdMatching(name);
        }
        CastTarget device = CastTarget.fromCastDevice(name, host, port);
        if (savedScreenId == null) {
            // mDNS re-announce: don't lose a screenId the row already learned earlier.
            CastTarget existing = mRowTargets.get(device.getDedupeKey());
            savedScreenId = existing != null ? existing.getSavedScreenId() : null;
        }
        if (savedScreenId != null) {
            device = device.withSavedScreenId(savedScreenId);
        }
        addOrUpdateRow(device);
    }

    /**
     * Same physical device seen via both DIAL and mDNS: the Cast row replaces the DIAL row.
     *
     * @return a Lounge screenId one of the removed DIAL rows already knew, so the Cast row
     *         inherits it instead of dropping it on the floor; null when none did
     */
    @Nullable
    private String removeDialRowsForHost(String host) {
        String screenId = null;
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, CastTarget> entry : mRowTargets.entrySet()) {
            CastTarget target = entry.getValue();
            if (target.getRoute() == CastTarget.Route.LOUNGE_DIAL
                    && host.equals(hostOfUrl(target.getDialLocation()))) {
                stale.add(entry.getKey());
                if (screenId == null && target.getScreen() != null) {
                    screenId = target.getScreen().getScreenId();
                }
            }
        }
        for (String key : stale) {
            removeRow(key);
        }
        return screenId;
    }

    /**
     * The saved-screen half of the merge: a persisted Lounge screen whose NAME matches the live
     * Cast device folds into the device's row (the row is removed; the screenId is returned).
     *
     * <p>Name is the only feasible merge key: a Lounge screenId and a Cast host can't be
     * correlated without actually running an mdx read against the device - which is exactly the
     * round-trip the saved id exists to skip. We make the match reliable by persisting mdx
     * pairings under the mDNS device name (CastPrefs javadoc). Residual risk: two different
     * devices sharing one name merge wrongly - rare enough on home networks, and the failure is
     * soft (the YouTube-app mode targets the other same-named screen).</p>
     */
    @Nullable
    private String takeSavedScreenIdMatching(String deviceName) {
        List<CastScreen> candidates = new ArrayList<>();
        for (CastTarget target : mRowTargets.values()) {
            if (target.getRoute() != CastTarget.Route.CAST_V2 && target.getScreen() != null) {
                candidates.add(target.getScreen());
            }
        }
        String screenId = findSavedScreenId(candidates, deviceName);
        if (screenId != null) {
            for (Map.Entry<String, CastTarget> entry : new ArrayList<>(mRowTargets.entrySet())) {
                CastScreen screen = entry.getValue().getScreen();
                if (screen != null && screenId.equals(screen.getScreenId())) {
                    removeRow(entry.getKey());
                    break;
                }
            }
        }
        return screenId;
    }

    /**
     * Pure name-match core of the saved-screen merge, kept static and Android-free so a plain
     * JVM test can pin it down: first screen whose trimmed, case-insensitive name equals the
     * device name donates its screenId. Case/whitespace tolerance because TV-code pairings store
     * the TV's self-reported Lounge name, which can differ cosmetically from the mDNS name.
     */
    @Nullable
    static String findSavedScreenId(List<CastScreen> savedScreens, @Nullable String deviceName) {
        if (deviceName == null) {
            return null;
        }
        String wanted = deviceName.trim();
        if (wanted.isEmpty()) {
            return null;
        }
        for (CastScreen screen : savedScreens) {
            if (screen == null) {
                continue;
            }
            String name = screen.getName();
            String screenId = screen.getScreenId();
            if (name != null && name.trim().equalsIgnoreCase(wanted)
                    && screenId != null && !screenId.isEmpty()) {
                return screenId;
            }
        }
        return null;
    }

    private void removeRow(String key) {
        View row = mRows.remove(key);
        mRowTargets.remove(key);
        if (row != null && mTargetsContainer != null) {
            mTargetsContainer.removeView(row);
        }
    }

    private void addOrUpdateRow(CastTarget target) {
        if (mTargetsContainer == null) {
            return;
        }
        // A DIAL discovery arriving AFTER the same device showed over mDNS: Cast row wins.
        if (target.getRoute() == CastTarget.Route.LOUNGE_DIAL
                && mCastHosts.contains(hostOfUrl(target.getDialLocation()))) {
            return;
        }
        if (mEmptyView != null) {
            mEmptyView.setVisibility(View.GONE);
        }

        String key = target.getDedupeKey();
        View row = mRows.get(key);
        if (row == null) {
            row = LayoutInflater.from(mActivity)
                    .inflate(R.layout.item_mobile_cast_target, mTargetsContainer, false);
            mRows.put(key, row);
            mTargetsContainer.addView(row);
        }
        mRowTargets.put(key, target);

        TextView name = row.findViewById(R.id.cast_target_name);
        TextView subtitle = row.findViewById(R.id.cast_target_badge);
        name.setText(target.getName());
        // Plain words only. The Cast row promises the default mode; the full tradeoff story
        // waits for the chooser, where the user is actually deciding.
        subtitle.setText(target.getRoute() == CastTarget.Route.CAST_V2
                ? R.string.mobile_cast_subtitle_no_ads
                : R.string.mobile_cast_subtitle_youtube_app);

        View finalRow = row;
        row.setOnClickListener(target.getRoute() == CastTarget.Route.CAST_V2
                ? v -> showChooser(target)
                : v -> onLoungeRowClicked(target, finalRow));
    }

    /** Lounge-only rows (saved screen / DIAL TV) have no mode choice: tap connects immediately. */
    private void onLoungeRowClicked(CastTarget target, View row) {
        if (!mSessionManager.isSenderAvailable()) {
            // Submodule sender implementation hasn't landed: browsing works, connecting doesn't.
            MessageHelpers.showMessage(mActivity, R.string.mobile_cast_unavailable);
            return;
        }

        if (target.isConnectable()) {
            connectAndDismiss(target);
            return;
        }

        // DIAL target without a screenId: the TV's YouTube app isn't running (or hides its id
        // until launched). POST the DIAL launch and poll for the screenId, showing an inline
        // spinner on the row; one launch at a time.
        if (mLaunchInFlight) {
            return;
        }
        mLaunchInFlight = true;
        ProgressBar progress = row.findViewById(R.id.cast_target_progress);
        if (progress != null) {
            progress.setVisibility(View.VISIBLE);
        }
        mDiscovery.launchYouTube(target, resolved -> {
            mLaunchInFlight = false;
            if (progress != null) {
                progress.setVisibility(View.GONE);
            }
            if (resolved != null && resolved.isConnectable()) {
                addOrUpdateRow(resolved); // row can now connect directly next time
                connectAndDismiss(resolved);
            } else {
                MessageHelpers.showMessage(mActivity, R.string.mobile_cast_launch_failed);
            }
        });
    }

    // ---------------------------------------------------------------------------------
    // Mode chooser (Cast devices only - the one place a choice exists)
    // ---------------------------------------------------------------------------------

    private boolean isChooserShowing() {
        return mChooserSection != null && mChooserSection.getVisibility() == View.VISIBLE;
    }

    /** Swap the sheet content in place: device list -> two-mode chooser for one Cast device. */
    private void showChooser(CastTarget device) {
        if (mChooserSection == null || mChooserOptions == null) {
            return;
        }
        mChooserGeneration++; // any earlier chooser's in-flight mdx read is now stale
        mChooserTitle.setText(mActivity.getString(R.string.mobile_cast_chooser_title, device.getName()));
        mChooserOptions.removeAllViews();

        // Option 1 (first = the default we recommend): Route A, ad-free through the phone.
        View direct = inflateOption(R.string.mobile_cast_option_direct_title,
                R.string.mobile_cast_option_direct_subtitle);
        direct.setOnClickListener(v -> connectAndDismiss(device));

        // Option 2: the TV's own YouTube app (saved screen if we have one, else the mdx shim).
        View app = inflateOption(R.string.mobile_cast_option_app_title,
                R.string.mobile_cast_option_app_subtitle);
        app.setOnClickListener(v -> onYouTubeAppOptionClicked(device, app));

        mListSection.setVisibility(View.GONE);
        mChooserSection.setVisibility(View.VISIBLE);
        if (mChooserBackCallback != null) {
            mChooserBackCallback.setEnabled(true); // back now returns to the list
        }
    }

    /** Back to the device list; a chooser mdx read still in flight is orphaned (generation bump). */
    private void showDeviceList() {
        if (mChooserSection == null) {
            return;
        }
        mChooserGeneration++;
        mChooserSection.setVisibility(View.GONE);
        mListSection.setVisibility(View.VISIBLE);
        if (mChooserBackCallback != null) {
            mChooserBackCallback.setEnabled(false); // back again dismisses the sheet
        }
    }

    /** A chooser option row: same layout as a device row, minus the device glyph. */
    private View inflateOption(int titleRes, int subtitleRes) {
        View option = LayoutInflater.from(mActivity)
                .inflate(R.layout.item_mobile_cast_target, mChooserOptions, false);
        option.findViewById(R.id.cast_target_icon).setVisibility(View.GONE);
        TextView title = option.findViewById(R.id.cast_target_name);
        TextView subtitle = option.findViewById(R.id.cast_target_badge);
        title.setText(titleRes);
        subtitle.setText(subtitleRes);
        mChooserOptions.addView(option);
        return option;
    }

    private void onYouTubeAppOptionClicked(CastTarget device, View optionRow) {
        if (!mSessionManager.isSenderAvailable()) {
            MessageHelpers.showMessage(mActivity, R.string.mobile_cast_unavailable);
            return;
        }

        // A merged saved screen (or an earlier mdx read) means we can connect the Lounge session
        // straight away - same resolved-target shape the mdx success path produces.
        String savedScreenId = device.getSavedScreenId();
        if (savedScreenId != null) {
            connectAndDismiss(youTubeAppTarget(device, savedScreenId));
            return;
        }

        startMdxPairing(device, optionRow);
    }

    /** The Lounge target for a Cast device's YouTube-app mode, resolved with a screenId. */
    private static CastTarget youTubeAppTarget(CastTarget device, String screenId) {
        return CastTarget.fromCastDeviceYouTubeApp(
                device.getName(), device.getCastHost(), device.getCastPort()).withScreenId(screenId);
    }

    /**
     * The mdx shim: read the Lounge screenId out of the Cast device's YouTube app (launching it
     * if needed - MdxScreenIdReader leaves it running so the Lounge session can attach right
     * after), then connect exactly like any paired Lounge target. The pairing persists under the
     * DEVICE name, which is what merges it back into this device's row next time.
     */
    private void startMdxPairing(CastTarget device, View optionRow) {
        if (mLaunchInFlight) {
            return;
        }
        mLaunchInFlight = true;
        final int generation = mChooserGeneration;
        optionRow.setEnabled(false);
        ProgressBar progress = optionRow.findViewById(R.id.cast_target_progress);
        TextView subtitle = optionRow.findViewById(R.id.cast_target_badge);
        if (progress != null) {
            progress.setVisibility(View.VISIBLE);
        }
        if (subtitle != null) {
            subtitle.setText(R.string.mobile_cast_connecting_youtube_app);
        }

        MdxScreenIdReader.readScreenId(device.getCastHost(), device.getCastPort(), MDX_READ_TIMEOUT_MS,
                new MdxScreenIdReader.Callback() {
                    @Override
                    public void onScreenId(String screenId) {
                        // Callback arrives on the reader's internal thread - hop to main.
                        mActivity.runOnUiThread(() -> {
                            mLaunchInFlight = false;
                            CastTarget resolved = youTubeAppTarget(device, screenId);
                            // Persist even if the user backed out meanwhile: the pairing is
                            // real, and the device name keyed here powers the future merge.
                            CastPrefs.addPairedScreen(mActivity, resolved.getScreen());
                            if (mDialog == null || !mDialog.isShowing()) {
                                return; // sheet dismissed mid-shim: don't connect out of the blue
                            }
                            // Teach the (possibly hidden) device row, so the option is instant
                            // from now on even within this sheet.
                            addOrUpdateRow(device.withSavedScreenId(screenId));
                            if (generation != mChooserGeneration) {
                                return; // user backed out of this chooser mid-spinner = cancel
                            }
                            resetMdxOption(optionRow, progress, subtitle);
                            connectAndDismiss(resolved);
                        });
                    }

                    @Override
                    public void onError(String reason) {
                        Log.e(TAG, "mdx screenId read failed: " + reason);
                        mActivity.runOnUiThread(() -> {
                            mLaunchInFlight = false;
                            resetMdxOption(optionRow, progress, subtitle);
                            if (mDialog != null && mDialog.isShowing()
                                    && generation == mChooserGeneration) {
                                MessageHelpers.showMessage(mActivity, R.string.mobile_cast_launch_failed);
                            }
                        });
                    }
                });
    }

    private void resetMdxOption(View optionRow, @Nullable ProgressBar progress, @Nullable TextView subtitle) {
        optionRow.setEnabled(true);
        if (progress != null) {
            progress.setVisibility(View.GONE);
        }
        if (subtitle != null) {
            subtitle.setText(R.string.mobile_cast_option_app_subtitle);
        }
    }

    // ---------------------------------------------------------------------------------
    // Connect + shared helpers
    // ---------------------------------------------------------------------------------

    private void connectAndDismiss(CastTarget target) {
        if (mSessionManager.connect(target)) {
            MessageHelpers.showMessage(mActivity,
                    mActivity.getString(R.string.mobile_cast_connecting, target.getName()));
            if (mDialog != null && mDialog.isShowing()) {
                mDialog.dismiss();
            }
        } else {
            MessageHelpers.showMessage(mActivity, R.string.mobile_cast_unavailable);
        }
    }

    /** Host of an http(s) URL (DIAL LOCATION) for the mDNS-vs-DIAL device merge; null when unparsable. */
    @Nullable
    private static String hostOfUrl(@Nullable String url) {
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------
    // "Link with TV code" dialog
    // ---------------------------------------------------------------------------------

    private void showCodeDialog() {
        EditText input = new EditText(mActivity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        // The TV shows the code in dash-separated groups; accept dashes/spaces and strip later.
        input.setKeyListener(DigitsKeyListener.getInstance("0123456789- "));
        input.setHint(R.string.mobile_cast_code_hint);
        input.setMaxLines(1);

        FrameLayout wrapper = new FrameLayout(mActivity);
        int pad = (int) (20 * mActivity.getResources().getDisplayMetrics().density);
        wrapper.setPadding(pad, pad / 2, pad, 0);
        wrapper.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(mActivity)
                .setTitle(R.string.mobile_cast_link_code)
                .setMessage(R.string.mobile_cast_code_help)
                .setView(wrapper)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.mobile_cast_code_positive, null) // validated below
                .create();

        dialog.setOnShowListener(d ->
                // Manual click listener so an invalid code keeps the dialog open for a retry.
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String code = input.getText() != null
                            ? input.getText().toString().replaceAll("\\D", "") : "";
                    if (code.length() != TV_CODE_LENGTH) {
                        input.setError(mActivity.getString(R.string.mobile_cast_code_invalid));
                        return;
                    }
                    pairWithCode(code, dialog);
                }));

        dialog.show();
    }

    private void pairWithCode(String code, AlertDialog dialog) {
        CastSenderService sender = mSessionManager.getSender();
        if (sender == null) {
            MessageHelpers.showMessage(mActivity, R.string.mobile_cast_unavailable);
            return;
        }
        if (mPairAction != null && !mPairAction.isDisposed()) {
            return; // pairing already in flight
        }

        MessageHelpers.showMessage(mActivity, R.string.mobile_cast_pairing);
        mPairAction = sender.pairWithCodeObserve(code)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(screen -> {
                    // Persist so the screen reappears in the picker, then connect right away.
                    CastPrefs.addPairedScreen(mActivity, screen);
                    if (dialog.isShowing()) {
                        dialog.dismiss();
                    }
                    connectAndDismiss(CastTarget.fromPairedScreen(screen));
                }, error -> {
                    Log.e(TAG, "TV-code pairing failed: " + error);
                    MessageHelpers.showMessage(mActivity, R.string.mobile_cast_pair_failed);
                });
    }
}
