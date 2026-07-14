package com.newtube.mobile.ui.signin;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.YTSignInPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.SignInView;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.MobileActivity;

/**
 * Touch device-code sign-in screen - Wave 5 (ARCHITECTURE.md section 7, "Account-login seam").
 *
 * <p>The account/auth backend ({@link YTSignInPresenter}, {@code SignInService},
 * {@code YouTubeAccountManager}, token storage) is reused UNCHANGED. This Activity is only the
 * touch re-skin of the Leanback {@code SignInFragment} (GuidedStep) - it implements the plain
 * {@link SignInView} interface and renders whatever the presenter pushes through it.
 *
 * <h3>Presenter wiring</h3>
 * Mirrors {@code SignInFragment} but talks to {@link YTSignInPresenter} directly (per
 * ARCHITECTURE.md section 7 / Wave-5 brief): {@code setView(this)} + {@code onViewInitialized()}
 * in {@link #onCreate}, {@code onViewDestroyed()} in {@link #onDestroy}. The base
 * {@code SignInPresenter.onViewInitialized()} no-ops for the YT subclass (it only dispatches when
 * invoked on the base instance), then {@code YTSignInPresenter.onViewInitialized()} kicks off the
 * device-code OAuth poll via {@code SignInService.signInObserve()} and calls back into
 * {@link #showCode}. The Rx stream observes on the main thread, so the callbacks below run on the
 * UI thread - {@code runOnUiThread} is used defensively anyway.
 *
 * <h3>What it renders</h3>
 * Unlike the TV path (which shows a QR meant to be scanned from a second device), this is a phone,
 * so there is nothing to scan: the activation page is opened right here in the device browser.
 * <ul>
 *   <li>When the first code arrives the screen explains the flow; "Continue with Google" opens
 *       the code-prefilled activation page ({@code https://youtube.com/qr/activate/<code>}) via
 *       {@link Utils#openLinkExt} so the user approves on this same device. Deliberately NOT
 *       auto-opened: bouncing into the browser with no context read as clunky (user feedback).</li>
 *   <li>The <b>user code</b> large + monospace, in a card that copies it to the clipboard on tap
 *       (fallback for entering it manually).</li>
 *   <li>The <b>verification URL</b> ({@code https://yt.be/activate}) as a tappable link.</li>
 *   <li>A prominent <b>"Open sign-in page"</b> button that re-opens the activation page in the
 *       browser (for when the user backgrounds / dismisses the auto-opened tab).</li>
 * </ul>
 * A spinner shows until the first code arrives; the {@link #showCode(String, String)} 2-arg error
 * path (empty {@code signInUrl}) renders a human-readable error with a "Try again" button that
 * restarts the flow with a fresh device code (the raw backend error is kept as a small detail
 * line for bug reports). A failed sign-in is never resumed - retrying always mints a new code.
 */
public class MobileSignInActivity extends MobileActivity implements SignInView {

    private YTSignInPresenter mSignInPresenter;

    private ProgressBar mProgress;
    private View mErrorContainer;
    private TextView mErrorView;
    private TextView mErrorDetailView;
    private MaterialButton mRetryButton;
    private View mContent;
    private MaterialCardView mCodeCard;
    private TextView mCodeView;
    private TextView mUrlView;
    private MaterialButton mOpenButton;
    private ImageButton mBackButton;

    private String mUserCode;
    private String mSignInUrl;
    /** The QR/full URL (code pre-filled); falls back to {@link #mSignInUrl} when absent. */
    private String mFullSignInUrl;
    /**
     * Guards the one-shot auto-open of the browser. Only {@code true} on a fresh launch
     * ({@code savedInstanceState == null}); a config change / rotation recreates the Activity with a
     * non-null bundle, so we do NOT re-launch the browser on the user - the button is there for that.
     */

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Only auto-open the browser on the very first launch of this screen, never on rotation.

        setContentView(R.layout.activity_mobile_signin);

        bindViews();

        mBackButton.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        mCodeCard.setOnClickListener(v -> copyCodeToClipboard());
        mUrlView.setOnClickListener(v -> openSignInUrl());
        mOpenButton.setOnClickListener(v -> openSignInUrl());
        mRetryButton.setOnClickListener(v -> restartSignIn());

        mSignInPresenter = YTSignInPresenter.instance(this);
        mSignInPresenter.setView(this);
        mSignInPresenter.onViewInitialized();
    }

    private void bindViews() {
        mProgress = findViewById(R.id.mobile_signin_progress);
        mErrorContainer = findViewById(R.id.mobile_signin_error_container);
        mErrorView = findViewById(R.id.mobile_signin_error);
        mErrorDetailView = findViewById(R.id.mobile_signin_error_detail);
        mRetryButton = findViewById(R.id.mobile_signin_retry_button);
        mContent = findViewById(R.id.mobile_signin_content);
        mCodeCard = findViewById(R.id.mobile_signin_code_card);
        mCodeView = findViewById(R.id.mobile_signin_code);
        mUrlView = findViewById(R.id.mobile_signin_url);
        mOpenButton = findViewById(R.id.mobile_signin_open_button);
        mBackButton = findViewById(R.id.mobile_signin_back);
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        if (mSignInPresenter != null && mSignInPresenter.getView() == this) {
            mSignInPresenter.onViewDestroyed();
        }

        super.onDestroy();
    }

    // ---------------------------------------------------------------------------------
    // SignInView
    // ---------------------------------------------------------------------------------

    @Override
    public void showCode(String userCode, String signInUrl) {
        showCode(userCode, signInUrl, null);
    }

    @Override
    public void showCode(String userCode, String signInUrl, String fullSignInUrl) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }

            // Error path: YTSignInPresenter routes backend/network errors through the 2-arg
            // overload with an empty URL and the error text in the 'userCode' slot.
            if (TextUtils.isEmpty(signInUrl)) {
                showError(userCode);
                return;
            }

            mUserCode = userCode;
            mSignInUrl = signInUrl;
            mFullSignInUrl = !TextUtils.isEmpty(fullSignInUrl) ? fullSignInUrl : signInUrl;

            mProgress.setVisibility(View.GONE);
            mErrorContainer.setVisibility(View.GONE);
            mContent.setVisibility(View.VISIBLE);

            mCodeView.setText(userCode);
            mUrlView.setText(signInUrl);

            // No auto-open: bouncing straight into the browser with zero context read as clunky
            // (user feedback). This screen explains what's about to happen; "Open sign-in page"
            // launches the code-prefilled approval page when the user is ready.
        });
    }

    @Override
    public void close() {
        runOnUiThread(this::finish);
    }

    // ---------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------

    private void showError(String message) {
        mProgress.setVisibility(View.GONE);
        mContent.setVisibility(View.GONE);
        mErrorContainer.setVisibility(View.VISIBLE);
        mErrorView.setText(R.string.mobile_signin_error);
        mErrorDetailView.setText(message);
        mErrorDetailView.setVisibility(!TextUtils.isEmpty(message) ? View.VISIBLE : View.GONE);
    }

    /**
     * "Try again" - restart the whole device-code flow from scratch. The presenter's
     * onViewInitialized() disposes the dead sign-in chain and mints a FRESH code (a failed
     * sign-in is never resumed - simpler and the old code may be expired anyway).
     */
    private void restartSignIn() {
        mErrorContainer.setVisibility(View.GONE);
        mContent.setVisibility(View.GONE);
        mProgress.setVisibility(View.VISIBLE);

        mUserCode = null;
        mSignInUrl = null;
        mFullSignInUrl = null;

        if (mSignInPresenter != null) {
            mSignInPresenter.onViewInitialized();
        }
    }

    private void copyCodeToClipboard() {
        if (TextUtils.isEmpty(mUserCode)) {
            return;
        }

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.mobile_signin_title), mUserCode));
            MessageHelpers.showMessage(this, R.string.mobile_signin_code_copied);
        }
    }

    private void openSignInUrl() {
        // Prefer the full URL (code pre-filled). Custom Tab / system browser, like the TV
        // "Login from browser" action does with the full URL.
        String url = !TextUtils.isEmpty(mFullSignInUrl) ? mFullSignInUrl : mSignInUrl;
        if (!TextUtils.isEmpty(url)) {
            Utils.openLinkExt(this, url);
        }
    }
}
