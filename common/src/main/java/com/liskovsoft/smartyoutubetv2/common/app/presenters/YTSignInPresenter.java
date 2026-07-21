package com.liskovsoft.smartyoutubetv2.common.app.presenters;

import android.annotation.SuppressLint;
import android.content.Context;

import com.liskovsoft.mediaserviceinterfaces.ServiceManager;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.AccountSelectionPresenter;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import io.reactivex.rxjava3.disposables.Disposable;

public class YTSignInPresenter extends SignInPresenter {
    private static final String TAG = YTSignInPresenter.class.getSimpleName();
    private static final String SIGN_IN_URL = "https://yt.be/activate"; // 18+, no search history
    private static final String QR_SIGN_IN_URL = "https://youtube.com/qr/activate/";
    //private static final String SIGN_IN_URL = "https://youtube.com/tv/activate"; // 18+, no search history
    //private static final String SIGN_IN_URL = "https://youtube.com/activate"; // age restricted, supports search history
    @SuppressLint("StaticFieldLeak")
    private static YTSignInPresenter sInstance;
    // NEWTUBE(mobile): on phones the user approves the device code in a browser on the SAME device,
    // so success lands while this app is backgrounded - a background startActivity for the account
    // picker is silently blocked on Android 10+ and the app looks like nothing happened. The mobile
    // flavor enables this to skip the picker (the fresh account is auto-selected by
    // YouTubeAccountManager.fixSelectedAccount) and show a toast instead (toasts are allowed from
    // the background). Default OFF = TV behavior unchanged.
    private static boolean sSilentSuccessEnabled;
    private final ServiceManager mService;
    private Disposable mSignInAction;

    private YTSignInPresenter(Context context) {
        super(context);
        mService = YouTubeServiceManager.instance();
    }

    public static YTSignInPresenter instance(Context context) {
        if (sInstance == null) {
            sInstance = new YTSignInPresenter(context);
        }

        sInstance.setContext(context);

        return sInstance;
    }

    public static void setSilentSuccessEnabled(boolean enabled) {
        sSilentSuccessEnabled = enabled;
    }

    public void unhold() {
        RxHelper.disposeActions(mSignInAction);
        sInstance = null;
    }

    @Override
    public void onViewDestroyed() {
        super.onViewDestroyed();
        unhold();
    }

    @Override
    public void onViewInitialized() {
        super.onViewInitialized();
        RxHelper.disposeActions(mSignInAction);
        updateUserCode();
    }

    @Override
    public void onActionClicked() {
        if (getView() != null) {
            getView().close();
        }
    }

    private void updateUserCode() {
        mSignInAction = mService.getSignInService().signInObserve()
                .subscribe(
                        userCode ->
                                getView().showCode(userCode, SIGN_IN_URL, QR_SIGN_IN_URL + userCode.replace(" ", "-")),
                        error -> {
                            Log.e(TAG, "Sign in error: %s", error.getMessage());
                            if (getView() != null) {
                                getView().showCode(error.getMessage(), "");
                            }
                        },
                        () -> {
                            // Success
                            if (sSilentSuccessEnabled) {
                                // See sSilentSuccessEnabled: account already auto-selected; Home
                                // refreshes through the account-change listener chain. The toast is
                                // the only feedback visible when approval ended in ANOTHER task
                                // (YouTube app intercepts the activate link) — keep it even though
                                // the view also renders a success state.
                                MessageHelpers.showMessage(getContext(), R.string.signed_in_success);
                                if (getView() != null) {
                                    getView().showSuccess();
                                }
                            } else {
                                if (getView() != null) {
                                    getView().close();
                                }
                                AccountSelectionPresenter.instance(getContext()).show(true);
                            }
                        }
                 );
    }

    public void start() {
        super.start();
        RxHelper.disposeActions(mSignInAction);
    }
}
