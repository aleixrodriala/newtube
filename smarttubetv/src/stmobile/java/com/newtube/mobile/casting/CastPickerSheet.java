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

import java.util.HashMap;
import java.util.Map;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * The cast device picker (CASTING.md UX): one bottom sheet listing persisted TV-code screens and
 * DIAL-discovered TVs (each with its one-line honesty badge) plus the universal "Link with TV
 * code" fallback. DIAL discovery runs only while the sheet is open (stopped on dismiss).
 *
 * <p>The sheet is presented through the host player's immersive-safe presenter
 * (MobilePlaybackActivity.showPlayerSheet) so it behaves like the quality/overflow sheets.
 * When the Lounge sender hasn't landed yet ({@code getCastSenderService()} == null) the picker
 * still opens and lists targets, but connect/pair attempts toast "not available yet".</p>
 */
public class CastPickerSheet {

    /** Presents a built sheet with the player's immersive handling (showPlayerSheet). */
    public interface SheetPresenter {
        void present(BottomSheetDialog dialog);
    }

    private static final String TAG = CastPickerSheet.class.getSimpleName();
    private static final int TV_CODE_LENGTH = 12;

    private final Activity mActivity;
    private final CastSessionManager mSessionManager;
    private final DialDiscovery mDiscovery;
    /** Dedupe key -> row view, so re-discoveries update in place instead of duplicating rows. */
    private final Map<String, View> mRows = new HashMap<>();

    private BottomSheetDialog mDialog;
    private LinearLayout mTargetsContainer;
    private View mProgressRow;
    private TextView mEmptyView;
    private Disposable mPairAction;
    private boolean mLaunchInFlight;

    public CastPickerSheet(Activity activity) {
        mActivity = activity;
        mSessionManager = CastSessionManager.instance(activity);
        mDiscovery = new DialDiscovery(activity);
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

        presenter.present(mDialog);
    }

    private void teardown() {
        mDiscovery.stop();
        if (mPairAction != null && !mPairAction.isDisposed()) {
            mPairAction.dispose();
        }
        mPairAction = null;
    }

    // ---------------------------------------------------------------------------------
    // Target rows
    // ---------------------------------------------------------------------------------

    private void addOrUpdateRow(CastTarget target) {
        if (mTargetsContainer == null) {
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

        TextView name = row.findViewById(R.id.cast_target_name);
        TextView badge = row.findViewById(R.id.cast_target_badge);
        name.setText(target.getName());
        // All Lounge targets carry the generic badge for now: a SmartTube receiver can't be told
        // apart from stock YouTube automatically (CASTING.md), so never over-promise ad-freedom.
        badge.setText(target.isConnectable()
                ? R.string.mobile_cast_badge_lounge
                : R.string.mobile_cast_badge_needs_launch);

        View finalRow = row;
        row.setOnClickListener(v -> onTargetClicked(target, finalRow));
    }

    private void onTargetClicked(CastTarget target, View row) {
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
