package com.liskovsoft.smartyoutubetv2.tv.ui.main;

import android.content.Intent;
import android.os.Bundle;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SplashPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.SplashView;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;

public class SplashActivity extends MotherActivity implements SplashView {
    private static final String TAG = SplashActivity.class.getSimpleName();
    private Intent mNewIntent;
    private SplashPresenter mPresenter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        com.liskovsoft.smartyoutubetv2.common.misc.NetPath.log(
                "splash onCreate data=" + (getIntent() != null ? getIntent().getDataString() : null));
        mNewIntent = getIntent();

        if (restorePictureInPictureIfLauncherIntent(mNewIntent)) {
            finish();
            return;
        }

        mPresenter = SplashPresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();

        //finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        com.liskovsoft.smartyoutubetv2.common.misc.NetPath.log(
                "splash onNewIntent data=" + (intent != null ? intent.getDataString() : null));
        mNewIntent = intent;

        if (restorePictureInPictureIfLauncherIntent(intent)) {
            finish();
            return;
        }

        mPresenter.onViewInitialized();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mPresenter != null) {
            mPresenter.onViewDestroyed();
        }
    }

    @Override
    public Intent getNewIntent() {
        return mNewIntent;
    }

    @Override
    public void finishView() {
        try {
            finish();
        } catch (NullPointerException e) {
            // NullPointerException: Attempt to invoke virtual method 'void com.android.server.wm.DisplayContent.moveStack(com.android.server.wm.TaskStack, boolean)'
            e.printStackTrace();
        }
    }

    private boolean restorePictureInPictureIfLauncherIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_MAIN.equals(intent.getAction())
                || !intent.hasCategory(Intent.CATEGORY_LAUNCHER)
                || !(getApplication() instanceof MainApplication)) {
            return false;
        }
        return ((MainApplication) getApplication()).restorePictureInPictureFromLauncher(this);
    }
}
