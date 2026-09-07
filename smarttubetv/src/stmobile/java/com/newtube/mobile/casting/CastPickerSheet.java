package com.newtube.mobile.casting;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;
import com.liskovsoft.mediaserviceinterfaces.CastSenderService;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.casting.castv2.CastV2Discovery;

import java.util.Collections;
import java.util.List;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/** One row per TV, with each receiver preserved and ad-free routes selected first. */
public class CastPickerSheet {
    public interface SheetPresenter {
        void present(BottomSheetDialog dialog);
    }

    private static final int TV_CODE_LENGTH = 12;
    // Cached YouTube rows must not win a quick tap while Cast discovery is still resolving.
    static final long DISCOVERY_GRACE_MS = 4_000;
    private final Activity mActivity;
    private final CastSessionManager mSessionManager;
    private DialDiscovery mDiscovery;
    private CastV2Discovery mCastDiscovery;
    private final CastDeviceRegistry mDevices = new CastDeviceRegistry();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private BottomSheetDialog mDialog;
    private View mListSection;
    private View mChooserSection;
    private TextView mChooserTitle;
    private LinearLayout mChooserOptions;
    private LinearLayout mTargetsContainer;
    private View mProgressRow;
    private View mEmptyView;
    private View mSmartTubePrompt;
    private Disposable mPairAction;
    private boolean mDiscoveryReady;
    private boolean mClosed;
    @Nullable private CastTarget mPendingTarget;
    @Nullable private CastTarget mChooserTarget;
    @Nullable private OnBackPressedCallback mChooserBackCallback;

    public CastPickerSheet(Activity activity) {
        this(activity, CastSessionManager.instance(activity));
    }

    CastPickerSheet(Activity activity, CastSessionManager sessions) {
        mActivity = activity;
        mSessionManager = sessions;
    }

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
        mSmartTubePrompt = content.findViewById(R.id.cast_smarttube_prompt);
        content.findViewById(R.id.cast_sheet_link_code).setOnClickListener(v -> showCodeDialog());
        content.findViewById(R.id.cast_chooser_back).setOnClickListener(v -> showDeviceList());

        for (CastTarget target : CastPrefs.getPairedTargets(mActivity)) mDevices.add(target);
        renderDevices();
        mDialog.setOnDismissListener(d -> teardown());
        startDiscovery();
        mHandler.postDelayed(() -> {
            mDiscoveryReady = true;
            renderDevices();
            maybeConnectPending();
        }, DISCOVERY_GRACE_MS);
        presenter.present(mDialog);

        mChooserBackCallback = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() { showDeviceList(); }
        };
        mDialog.getOnBackPressedDispatcher().addCallback(mChooserBackCallback);
    }

    private void teardown() {
        mClosed = true;
        mPendingTarget = null;
        mHandler.removeCallbacksAndMessages(null);
        if (mDiscovery != null) mDiscovery.stop();
        if (mCastDiscovery != null) mCastDiscovery.stop();
        if (mPairAction != null) mPairAction.dispose();
        mPairAction = null;
    }

    void startDiscovery() {
        mDiscovery = new DialDiscovery(mActivity);
        mCastDiscovery = new CastV2Discovery(mActivity);
        mDiscovery.start(new DialDiscovery.Listener() {
            @Override public void onTargetFound(CastTarget target) { onTarget(target); }
            @Override public void onDiscoveryFinished() {
                // SSDP finishing says nothing about mDNS; the common grace window owns the UI.
            }
        });
        mCastDiscovery.start((name, host, port) -> onTarget(CastTarget.fromCastDevice(name, host, port)));
    }

    void onTarget(CastTarget target) {
        if (mClosed) return;
        mDevices.add(target);
        renderDevices();
        maybeConnectPending();
    }

    private void renderDevices() {
        if (mClosed || mTargetsContainer == null) return;
        List<CastDeviceRegistry.Device> devices = mDevices.devices();
        mTargetsContainer.removeAllViews();
        boolean hasSmartTube = false;
        for (CastDeviceRegistry.Device device : devices) {
            List<CastTarget> routes = device.routes();
            CastTarget preferred = routes.get(0);
            hasSmartTube |= preferred.getReceiverApp() == CastTarget.ReceiverApp.SMARTTUBE;
            View row = inflateRow(mTargetsContainer, device.name(), subtitle(preferred));
            boolean pending = mPendingTarget != null && device.contains(mPendingTarget);
            row.findViewById(R.id.cast_target_progress).setVisibility(pending ? View.VISIBLE : View.GONE);
            if (pending) ((TextView) row.findViewById(R.id.cast_target_badge))
                    .setText(R.string.mobile_cast_checking_ad_free);
            row.setOnClickListener(v -> selectDevice(preferred));
            View more = row.findViewById(R.id.cast_target_more);
            more.setVisibility(View.VISIBLE);
            more.setOnClickListener(v -> {
                mPendingTarget = null;
                showChooser(preferred);
            });
        }
        mProgressRow.setVisibility(mDiscoveryReady ? View.GONE : View.VISIBLE);
        mEmptyView.setVisibility(mDiscoveryReady && devices.isEmpty() ? View.VISIBLE : View.GONE);
        mSmartTubePrompt.setVisibility(hasSmartTube ? View.GONE : View.VISIBLE);
        if (mChooserTarget != null) showChooser(mChooserTarget);
    }

    static boolean shouldWaitForDiscovery(boolean discoveryReady, CastTarget preferred) {
        return !discoveryReady && !preferred.isAdFree();
    }

    private void selectDevice(CastTarget anchor) {
        mPendingTarget = anchor;
        maybeConnectPending();
        renderDevices();
    }

    private void maybeConnectPending() {
        if (mClosed || mPendingTarget == null) return;
        CastDeviceRegistry.Device device = mDevices.deviceFor(mPendingTarget);
        if (device == null || shouldWaitForDiscovery(mDiscoveryReady, device.routes().get(0))) return;
        mPendingTarget = null;
        connectAndDismiss(device.routes(), true);
    }

    private int subtitle(CastTarget target) {
        if (target.getReceiverApp() == CastTarget.ReceiverApp.SMARTTUBE)
            return R.string.mobile_cast_smarttube_subtitle;
        if (target.getRoute() == CastTarget.Route.CAST_V2) return R.string.mobile_cast_subtitle_no_ads;
        return target.getReceiverApp() == CastTarget.ReceiverApp.YOUTUBE
                ? R.string.mobile_cast_youtube_subtitle : R.string.mobile_cast_unknown_subtitle;
    }

    private void showChooser(CastTarget anchor) {
        CastDeviceRegistry.Device device = mDevices.deviceFor(anchor);
        if (device == null) return;
        mChooserTarget = anchor;
        mChooserTitle.setText(mActivity.getString(R.string.mobile_cast_chooser_title, device.name()));
        mChooserOptions.removeAllViews();
        for (CastTarget route : device.routes()) {
            int title = route.getReceiverApp() == CastTarget.ReceiverApp.SMARTTUBE
                    ? R.string.mobile_cast_smarttube_title
                    : route.getRoute() == CastTarget.Route.CAST_V2
                    ? R.string.mobile_cast_option_direct_title
                    : route.getReceiverApp() == CastTarget.ReceiverApp.YOUTUBE
                    ? R.string.mobile_cast_youtube_title : R.string.mobile_cast_unknown_title;
            View option = inflateRow(mChooserOptions, mActivity.getString(title),
                    route.getRoute() == CastTarget.Route.CAST_V2
                            ? R.string.mobile_cast_option_direct_subtitle : subtitle(route));
            option.findViewById(R.id.cast_target_icon).setVisibility(View.GONE);
            option.setOnClickListener(v -> connectAndDismiss(Collections.singletonList(route), false));
            // Legacy pairings can be identified without deleting them or entering another code.
            if (route.getRoute() == CastTarget.Route.LOUNGE_MANUAL) {
                View more = option.findViewById(R.id.cast_target_more);
                more.setVisibility(View.VISIBLE);
                more.setContentDescription(mActivity.getString(R.string.mobile_cast_identify_app));
                more.setOnClickListener(v -> showIdentifyDialog(route));
            }
        }
        mListSection.setVisibility(View.GONE);
        mChooserSection.setVisibility(View.VISIBLE);
        if (mChooserBackCallback != null) mChooserBackCallback.setEnabled(true);
    }

    private void showIdentifyDialog(CastTarget target) {
        new MaterialAlertDialogBuilder(mActivity)
                .setTitle(R.string.mobile_cast_identify_app)
                .setItems(new String[]{mActivity.getString(R.string.mobile_cast_smarttube_title),
                        mActivity.getString(R.string.mobile_cast_youtube_title)}, (dialog, which) -> {
                    CastTarget typed = target.withReceiverApp(which == 0
                            ? CastTarget.ReceiverApp.SMARTTUBE : CastTarget.ReceiverApp.YOUTUBE);
                    CastPrefs.addPairedScreen(mActivity, typed.getScreen(), typed.getReceiverApp());
                    mDevices.add(typed);
                    renderDevices();
                }).show();
    }

    private void showDeviceList() {
        mChooserTarget = null;
        mChooserSection.setVisibility(View.GONE);
        mListSection.setVisibility(View.VISIBLE);
        if (mChooserBackCallback != null) mChooserBackCallback.setEnabled(false);
        renderDevices();
    }

    private View inflateRow(LinearLayout container, String title, int subtitle) {
        View row = LayoutInflater.from(mActivity).inflate(R.layout.item_mobile_cast_target, container, false);
        ((TextView) row.findViewById(R.id.cast_target_name)).setText(title);
        ((TextView) row.findViewById(R.id.cast_target_badge)).setText(subtitle);
        container.addView(row);
        return row;
    }

    private void connectAndDismiss(List<CastTarget> routes, boolean recommended) {
        boolean started = recommended ? mSessionManager.connectWithFallback(routes)
                : mSessionManager.connect(routes.get(0));
        if (started) {
            MessageHelpers.showMessage(mActivity,
                    mActivity.getString(R.string.mobile_cast_connecting, routes.get(0).getName()));
            mDialog.dismiss();
        } else {
            MessageHelpers.showMessage(mActivity, R.string.mobile_cast_unavailable);
        }
    }

    private void showCodeDialog() {
        mPendingTarget = null;
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(mActivity, R.style.MobileCastCodeDialog);
        View content = LayoutInflater.from(builder.getContext()).inflate(R.layout.dialog_mobile_cast_code, null);
        TextInputLayout inputLayout = content.findViewById(R.id.cast_code_input_layout);
        EditText input = content.findViewById(R.id.cast_code_input);
        RadioGroup apps = content.findViewById(R.id.cast_code_apps);
        TextView instruction = content.findViewById(R.id.cast_code_instruction);
        TextView path = content.findViewById(R.id.cast_code_path);
        View progress = content.findViewById(R.id.cast_code_progress);
        input.setKeyListener(DigitsKeyListener.getInstance("0123456789 -"));
        Runnable updateInstructions = () -> {
            int selected = apps.getCheckedRadioButtonId();
            boolean smartTube = selected == R.id.cast_code_app_smarttube;
            instruction.setText(selected == -1 ? R.string.mobile_cast_code_choose_app
                    : smartTube ? R.string.mobile_cast_code_open_smarttube : R.string.mobile_cast_code_open_youtube);
            path.setVisibility(selected == -1 ? View.GONE : View.VISIBLE);
            path.setText(smartTube ? R.string.mobile_cast_code_smarttube_path : R.string.mobile_cast_code_youtube_path);
        };
        updateInstructions.run();

        AlertDialog dialog = builder
                .setTitle(R.string.mobile_cast_code_title)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.mobile_cast_code_positive, null).create();
        dialog.setOnShowListener(d -> {
            View positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Runnable validate = () -> positive.setEnabled(apps.getCheckedRadioButtonId() != -1
                    && normalizeTvCode(input.getText()).length() == TV_CODE_LENGTH);
            validate.run();
            apps.setOnCheckedChangeListener((group, checked) -> {
                updateInstructions.run();
                validate.run();
            });
            input.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    inputLayout.setError(null);
                    validate.run();
                }
                @Override public void afterTextChanged(Editable s) {}
            });
            positive.setOnClickListener(v -> {
                CastTarget.ReceiverApp app = apps.getCheckedRadioButtonId() == R.id.cast_code_app_smarttube
                        ? CastTarget.ReceiverApp.SMARTTUBE : CastTarget.ReceiverApp.YOUTUBE;
                pairWithCode(normalizeTvCode(input.getText()), app, dialog, inputLayout, input, apps, progress);
            });
            input.setOnEditorActionListener((v, actionId, event) -> {
                if (positive.isEnabled()) positive.performClick();
                return true;
            });
        });
        dialog.show();
    }

    private static String normalizeTvCode(CharSequence value) {
        return value == null ? "" : value.toString().replaceAll("\\D", "");
    }

    private static void setPairing(AlertDialog dialog, EditText input, RadioGroup apps,
                                   View progress, boolean pairing) {
        input.setEnabled(!pairing);
        for (int i = 0; i < apps.getChildCount(); i++) apps.getChildAt(i).setEnabled(!pairing);
        progress.setVisibility(pairing ? View.VISIBLE : View.GONE);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!pairing);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!pairing);
        dialog.setCancelable(!pairing);
    }

    private void pairWithCode(String code, CastTarget.ReceiverApp app, AlertDialog dialog,
                              TextInputLayout inputLayout, EditText input, RadioGroup apps, View progress) {
        CastSenderService sender = mSessionManager.getSender();
        if (sender == null) {
            MessageHelpers.showMessage(mActivity, R.string.mobile_cast_unavailable);
            return;
        }
        if (mPairAction != null && !mPairAction.isDisposed()) return;
        setPairing(dialog, input, apps, progress, true);
        mPairAction = sender.pairWithCodeObserve(code)
                .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                .subscribe(screen -> {
                    CastPrefs.addPairedScreen(mActivity, screen, app);
                    if (dialog.isShowing()) dialog.dismiss();
                    connectAndDismiss(Collections.singletonList(CastTarget.fromPairedScreen(screen, app)), false);
                }, error -> {
                    if (dialog.isShowing()) {
                        setPairing(dialog, input, apps, progress, false);
                        inputLayout.setError(mActivity.getString(R.string.mobile_cast_pair_failed));
                    }
                });
    }
}
