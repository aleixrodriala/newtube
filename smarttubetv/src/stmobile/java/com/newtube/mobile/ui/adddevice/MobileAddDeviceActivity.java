package com.newtube.mobile.ui.adddevice;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AddDevicePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.AddDeviceView;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.MobileActivity;

/**
 * Touch companion-device / remote-control pairing screen (GAP 3).
 *
 * <p>Replaces the Leanback {@code AddDeviceActivity} (a GuidedStep) that
 * {@link com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.RemoteControlSettingsPresenter}
 * launches. The remote-control backend is reused UNCHANGED - this Activity is only the touch re-skin
 * of the {@link AddDeviceView} contract:</p>
 *
 * <ul>
 *   <li>{@link #showCode(String)} renders the pairing code (large + monospace). The code is generated
 *       by {@code AddDevicePresenter} via {@code RemoteControlService.getPairingCodeObserve()}; the
 *       user types it into the YouTube app on their phone (Settings / Watch on TV) to link this
 *       device.</li>
 *   <li>The "Done" button (and the toolbar back arrow) call {@code AddDevicePresenter.onActionClicked()}
 *       which calls back into {@link #close()} to finish.</li>
 * </ul>
 *
 * <p>Presenter wiring mirrors the TV {@code AddDeviceFragment}: {@code setView} + {@code
 * onViewInitialized} in {@link #onCreate} (the presenter subscribes and pushes the code through
 * {@link #showCode}), {@code onViewDestroyed} in {@link #onDestroy}.</p>
 */
public class MobileAddDeviceActivity extends MobileActivity implements AddDeviceView {
    private AddDevicePresenter mPresenter;

    private ProgressBar mProgress;
    private View mContent;
    private TextView mCodeView;
    private MaterialButton mDoneButton;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_mobile_adddevice);

        mProgress = findViewById(R.id.mobile_add_device_progress);
        mContent = findViewById(R.id.mobile_add_device_content);
        mCodeView = findViewById(R.id.mobile_add_device_code);
        mDoneButton = findViewById(R.id.mobile_add_device_done);

        findViewById(R.id.mobile_add_device_back).setOnClickListener(v -> onDone());
        mDoneButton.setOnClickListener(v -> onDone());

        mPresenter = AddDevicePresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    private void onDone() {
        if (mPresenter != null) {
            mPresenter.onActionClicked(); // -> close()
        } else {
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        if (mPresenter != null && mPresenter.getView() == this) {
            mPresenter.onViewDestroyed();
        }

        super.onDestroy();
    }

    // ---------------------------------------------------------------------------------
    // AddDeviceView
    // ---------------------------------------------------------------------------------

    @Override
    public void showCode(String userCode) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || TextUtils.isEmpty(userCode)) {
                return;
            }

            mProgress.setVisibility(View.GONE);
            mContent.setVisibility(View.VISIBLE);
            mCodeView.setText(userCode);
        });
    }

    @Override
    public void close() {
        runOnUiThread(this::finish);
    }
}
