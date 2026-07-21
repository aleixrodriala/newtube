package com.newtube.mobile.ui.signin;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
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
 * Touch device-code sign-in screen - Wave 5 (ARCHITECTURE.md section 7, "Account-login seam"),
 * reworked into a guided-onboarding flow (2026-07).
 *
 * <p>The account/auth backend ({@link YTSignInPresenter}, {@code SignInService},
 * {@code YouTubeAccountManager}, token storage) is reused UNCHANGED. This Activity is only the
 * touch re-skin of the device-code OAuth flow - it implements the plain {@link SignInView}
 * interface and renders whatever the presenter pushes through it.
 *
 * <h3>Why guidance is the whole design</h3>
 * The device-code grant is a TV flow (no redirect URI, approval happens on Google's "activate"
 * pages worded for TVs) that here runs on the same phone. The screen therefore walks the user
 * through it: a 3-step list (tap Continue -> approve on Google's page -> come back), the pairing
 * code kept small but visible (Google's approval UI shows the same code; users compare), and a
 * {@code https://yt.be/activate} manual fallback link.
 *
 * <h3>States</h3>
 * <ul>
 *   <li>{@code LOADING}: spinner until the first code arrives ({@link #showCode}).</li>
 *   <li>{@code READY}: steps + code row + "Continue with Google" (opens the code-prefilled
 *       {@code https://youtube.com/qr/activate/<code>} page via {@link Utils#openLinkExt} -
 *       a Custom Tab in OUR OWN task, unless the installed YouTube app intercepts the link
 *       into its own task with a native approval sheet).</li>
 *   <li>{@code WAITING}: entered when the user opens the approval page - spinner, the code to
 *       match, and a re-open button for a dismissed tab.</li>
 *   <li>{@code SUCCESS}: the 3-second token poll landed. {@link #showSuccess()} relaunches this
 *       activity {@code CLEAR_TOP|SINGLE_TOP}, which pops the Custom Tab sitting above it in the
 *       same task (the standard OAuth auto-return trick; allowed from the background because this
 *       activity is in the foreground task's back stack - silently blocked in the YouTube-app
 *       path, where the presenter's toast is the feedback and the user returns manually). Shows
 *       the checkmark briefly once resumed, then finishes.</li>
 *   <li>{@code ERROR}: human-readable headline + raw-detail line + "Try again" minting a FRESH
 *       code (a failed sign-in is never resumed - the old code may be expired anyway).</li>
 * </ul>
 * The Rx stream observes on the main thread, so callbacks run on the UI thread -
 * {@code runOnUiThread} is used defensively anyway. Rotation does not recreate (manifest
 * {@code configChanges}), so states and the live poll survive it.
 */
public class MobileSignInActivity extends MobileActivity implements SignInView {

    private enum State { LOADING, READY, WAITING, SUCCESS, ERROR }

    private static final long SUCCESS_LINGER_MS = 1_400;

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
    private View mWaitingContainer;
    private TextView mWaitingCodeView;
    private MaterialButton mReopenButton;
    private View mSuccessContainer;
    private ImageButton mBackButton;

    private State mState = State.LOADING;
    private boolean mResumed;
    private boolean mFinishScheduled;

    private String mUserCode;
    private String mSignInUrl;
    /** The QR/full URL (code pre-filled); falls back to {@link #mSignInUrl} when absent. */
    private String mFullSignInUrl;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_mobile_signin);

        bindViews();

        mBackButton.setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        mCodeCard.setOnClickListener(v -> copyCodeToClipboard());
        mWaitingCodeView.setOnClickListener(v -> copyCodeToClipboard());
        mUrlView.setOnClickListener(v -> openSignInUrl());
        mOpenButton.setOnClickListener(v -> openSignInUrl());
        mReopenButton.setOnClickListener(v -> openSignInUrl());
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
        mWaitingContainer = findViewById(R.id.mobile_signin_waiting_container);
        mWaitingCodeView = findViewById(R.id.mobile_signin_waiting_code);
        mReopenButton = findViewById(R.id.mobile_signin_reopen_button);
        mSuccessContainer = findViewById(R.id.mobile_signin_success_container);
        mBackButton = findViewById(R.id.mobile_signin_back);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;

        // Auto-return landed (or the user came back on their own) after the poll succeeded:
        // let the checkmark linger, then hand back to Browse.
        if (mState == State.SUCCESS) {
            scheduleFinish();
        }
    }

    @Override
    protected void onPause() {
        mResumed = false;
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        // CLEAR_TOP|SINGLE_TOP self-relaunch from showSuccess() lands here after popping the
        // Custom Tab - the success state is already rendered, nothing to do.
        super.onNewIntent(intent);
    }

    @Override
    protected void onDestroy() {
        MobileSignInKeepaliveService.stop(this);

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

            mCodeView.setText(userCode);
            mWaitingCodeView.setText(userCode);
            mUrlView.setText(signInUrl);

            // Never auto-open the browser: this screen's job is to explain what's about to
            // happen first (user feedback); "Continue with Google" launches the approval page.
            applyState(State.READY);
        });
    }

    @Override
    public void showSuccess() {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || mState == State.SUCCESS) {
                return;
            }

            MobileSignInKeepaliveService.stop(this);
            applyState(State.SUCCESS);

            // Pop the Custom Tab sitting above us in this task and bring the checkmark forward.
            // In the YouTube-app interception path this start is silently blocked (other task is
            // foreground) - the presenter's "signed in" toast covers that; the user returning
            // manually still lands on this success state.
            try {
                Intent intent = new Intent(this, MobileSignInActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
            } catch (Exception e) {
                // Not fatal: worst case the user returns manually.
            }

            if (mResumed) {
                scheduleFinish();
            }
        });
    }

    @Override
    public void close() {
        runOnUiThread(this::finish);
    }

    // ---------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------

    private void applyState(State state) {
        mState = state;

        mProgress.setVisibility(state == State.LOADING ? View.VISIBLE : View.GONE);
        mContent.setVisibility(state == State.READY ? View.VISIBLE : View.GONE);
        mWaitingContainer.setVisibility(state == State.WAITING ? View.VISIBLE : View.GONE);
        mSuccessContainer.setVisibility(state == State.SUCCESS ? View.VISIBLE : View.GONE);
        mErrorContainer.setVisibility(state == State.ERROR ? View.VISIBLE : View.GONE);
    }

    private void showError(String message) {
        MobileSignInKeepaliveService.stop(this);
        applyState(State.ERROR);
        mErrorView.setText(R.string.mobile_signin_error);
        mErrorDetailView.setText(message);
        mErrorDetailView.setVisibility(!TextUtils.isEmpty(message) ? View.VISIBLE : View.GONE);
    }

    private void scheduleFinish() {
        if (mFinishScheduled) {
            return;
        }
        mFinishScheduled = true;

        Utils.postDelayed(this::finish, SUCCESS_LINGER_MS);
    }

    /**
     * "Try again" - restart the whole device-code flow from scratch. The presenter's
     * onViewInitialized() disposes the dead sign-in chain and mints a FRESH code (a failed
     * sign-in is never resumed - simpler and the old code may be expired anyway).
     */
    private void restartSignIn() {
        applyState(State.LOADING);

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
        if (TextUtils.isEmpty(url)) {
            return;
        }

        // Browser-pinned tab: an unpinned ACTION_VIEW lets the installed YouTube app (verified
        // youtube.com app-link) hijack the approval into ITS task, stranding the user there and
        // defeating the success auto-return. Pinning keeps the page in our task.
        Utils.openLinkInBrowserTab(this, url);

        // Keep the token poll's network alive while the tab covers us (cached apps get their
        // network suspended, which would stall the poll - and the auto-return - until return).
        MobileSignInKeepaliveService.start(this);

        // The approval hand-off started - swap the steps for the waiting state (the poll
        // completes the flow; the button there re-opens a dismissed tab).
        if (mState == State.READY) {
            applyState(State.WAITING);
        }
    }
}
