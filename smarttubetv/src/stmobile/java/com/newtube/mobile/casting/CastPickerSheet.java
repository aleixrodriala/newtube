package com.newtube.mobile.casting;

import android.app.Activity;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.DigitsKeyListener;
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
 * The cast device picker (CASTING.md UX): one bottom sheet listing persisted TV-code screens,
 * DIAL-discovered TVs and mDNS-discovered Cast receivers (each with its one-line honesty badge)
 * plus the universal "Link with TV code" fallback. Both discoveries run only while the sheet is
 * open (stopped on dismiss); either failing silently is fine - the caller already gated on the
 * local-network permission.
 *
 * <p>Each Cast receiver produces TWO adjacent rows - "Direct cast" (Route A) and "YouTube app"
 * (Lounge via the mdx shim). That's the v1 take on the doc's "one row with a mode choice": two
 * plain rows are simpler inside a sheet; revisit if the list gets crowded. If the same physical
 * device also answers DIAL, the DIAL Lounge row is dropped in favor of the Cast pair (Cast
 * devices answering DIAL is rare - don't over-engineer the merge).</p>
 *
 * <p>The sheet is presented through the host player's immersive-safe presenter
 * (MobilePlaybackActivity.showPlayerSheet) so it behaves like the quality/overflow sheets.
 * When the Lounge sender hasn't landed yet ({@code getCastSenderService()} == null) the picker
 * still opens and lists targets, but Lounge connect/pair attempts toast "not available yet"
 * (Direct cast is self-contained and unaffected).</p>
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
    /** Dedupe key -> target, so the mDNS-vs-DIAL merge can inspect what a row represents. */
    private final Map<String, CastTarget> mRowTargets = new HashMap<>();
    /** Hosts seen over mDNS; DIAL rows resolving to one of these are suppressed (Cast pair wins). */
    private final Set<String> mCastHosts = new HashSet<>();

    private BottomSheetDialog mDialog;
    private LinearLayout mTargetsContainer;
    private View mProgressRow;
    private TextView mEmptyView;
    private Disposable mPairAction;
    /** One launch/pairing flow at a time - shared by the DIAL launch and the mdx shim. */
    private boolean mLaunchInFlight;

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

        mTargetsContainer = content.findViewById(R.id.cast_sheet_targets);
        mProgressRow = content.findViewById(R.id.cast_sheet_progress);
        mEmptyView = content.findViewById(R.id.cast_sheet_empty);
        content.findViewById(R.id.cast_sheet_link_code).setOnClickListener(v -> showCodeDialog());

        // Persisted screens first: they're instant, and CASTING.md wants paired screens to
        // reappear in the picker. Same generic Lounge badge as live-discovered targets.
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
    // Target rows
    // ---------------------------------------------------------------------------------

    /** One Cast receiver -> two adjacent rows: Direct cast (Route A) + its YouTube app (mdx->Lounge). */
    private void onCastDeviceFound(String name, String host, int port) {
        mCastHosts.add(host);
        removeDialRowsForHost(host);
        addOrUpdateRow(CastTarget.fromCastDevice(name, host, port));
        addOrUpdateRow(CastTarget.fromCastDeviceYouTubeApp(name, host, port));
    }

    /** Same physical device seen via both DIAL and mDNS: the Cast row pair replaces the DIAL row. */
    private void removeDialRowsForHost(String host) {
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, CastTarget> entry : mRowTargets.entrySet()) {
            CastTarget target = entry.getValue();
            if (target.getRoute() == CastTarget.Route.LOUNGE_DIAL
                    && host.equals(hostOfUrl(target.getDialLocation()))) {
                stale.add(entry.getKey());
            }
        }
        for (String key : stale) {
            View row = mRows.remove(key);
            mRowTargets.remove(key);
            if (row != null && mTargetsContainer != null) {
                mTargetsContainer.removeView(row);
            }
        }
    }

    private void addOrUpdateRow(CastTarget target) {
        if (mTargetsContainer == null) {
            return;
        }
        // A DIAL discovery arriving AFTER the same device showed over mDNS: Cast pair wins.
        if (target.getRoute() == CastTarget.Route.LOUNGE_DIAL
                && mCastHosts.contains(hostOfUrl(target.getDialLocation()))) {
            return;
        }
        if (mEmptyView != null) {
            mEmptyView.setVisibility(View.GONE);
        }

        // A DIAL device that resolves to an already-persisted screen collapses into that row
        // (same physical TV, one entry - the persisted row can already connect).
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
        TextView badge = row.findViewById(R.id.cast_target_badge);
        name.setText(target.getRoute() == CastTarget.Route.LOUNGE_MDX
                // "<Name> — YouTube app": the row title carries the mode; the target keeps the
                // plain device name (it becomes the overlay/notification "Playing on <Name>").
                ? mActivity.getString(R.string.mobile_cast_row_youtube_app, target.getName())
                : target.getName());
        badge.setText(badgeFor(target));

        View finalRow = row;
        row.setOnClickListener(v -> onTargetClicked(target, finalRow));
    }

    private int badgeFor(CastTarget target) {
        switch (target.getRoute()) {
            case CAST_V2:
                return R.string.mobile_cast_badge_direct;
            case LOUNGE_MDX:
                // Same honesty line as every other YouTube-app target (CASTING.md badge).
                return R.string.mobile_cast_badge_lounge;
            default:
                // All Lounge targets carry the generic badge for now: a SmartTube receiver can't
                // be told apart from stock YouTube automatically (CASTING.md), so never
                // over-promise ad-freedom.
                return target.isConnectable()
                        ? R.string.mobile_cast_badge_lounge
                        : R.string.mobile_cast_badge_needs_launch;
        }
    }

    private void onTargetClicked(CastTarget target, View row) {
        // Route A is self-contained (no Lounge sender involved) - connect straight away.
        if (target.getRoute() == CastTarget.Route.CAST_V2) {
            connectAndDismiss(target);
            return;
        }

        if (!mSessionManager.isSenderAvailable()) {
            // Submodule sender implementation hasn't landed: browsing works, connecting doesn't.
            MessageHelpers.showMessage(mActivity, R.string.mobile_cast_unavailable);
            return;
        }

        if (target.getRoute() == CastTarget.Route.LOUNGE_MDX) {
            startMdxPairing(target, row);
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

    /**
     * The mdx shim: read the Lounge screenId out of the Cast device's YouTube app (launching it if
     * needed - MdxScreenIdReader leaves it running so the Lounge session can attach right after),
     * then connect exactly like any paired Lounge target. Also persisted via CastPrefs so the
     * screen shows up as a saved row next time without the shim round-trip.
     */
    private void startMdxPairing(CastTarget target, View row) {
        if (mLaunchInFlight) {
            return;
        }
        mLaunchInFlight = true;
        row.setEnabled(false);
        ProgressBar progress = row.findViewById(R.id.cast_target_progress);
        TextView badge = row.findViewById(R.id.cast_target_badge);
        if (progress != null) {
            progress.setVisibility(View.VISIBLE);
        }
        if (badge != null) {
            badge.setText(R.string.mobile_cast_connecting_youtube_app);
        }

        MdxScreenIdReader.readScreenId(target.getCastHost(), target.getCastPort(), MDX_READ_TIMEOUT_MS,
                new MdxScreenIdReader.Callback() {
                    @Override
                    public void onScreenId(String screenId) {
                        // Callback arrives on the reader's internal thread - hop to main.
                        mActivity.runOnUiThread(() -> {
                            mLaunchInFlight = false;
                            resetMdxRow(row, progress, badge);
                            if (mDialog == null || !mDialog.isShowing()) {
                                return; // sheet dismissed mid-shim: don't connect out of the blue
                            }
                            CastTarget resolved = target.withScreenId(screenId);
                            CastPrefs.addPairedScreen(mActivity, resolved.getScreen());
                            connectAndDismiss(resolved);
                        });
                    }

                    @Override
                    public void onError(String reason) {
                        Log.e(TAG, "mdx screenId read failed: " + reason);
                        mActivity.runOnUiThread(() -> {
                            mLaunchInFlight = false;
                            resetMdxRow(row, progress, badge);
                            if (mDialog != null && mDialog.isShowing()) {
                                MessageHelpers.showMessage(mActivity, R.string.mobile_cast_launch_failed);
                            }
                        });
                    }
                });
    }

    private void resetMdxRow(View row, @Nullable ProgressBar progress, @Nullable TextView badge) {
        row.setEnabled(true);
        if (progress != null) {
            progress.setVisibility(View.GONE);
        }
        if (badge != null) {
            badge.setText(R.string.mobile_cast_badge_lounge);
        }
    }

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
