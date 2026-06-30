package com.newtube.mobile.ui.signin;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.card.MaterialCardView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.YTSignInPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.SignInView;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.MobileActivity;

import java.util.EnumMap;
import java.util.Map;

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
 * <ul>
 *   <li>The <b>user code</b> large + monospace, in a card that copies it to the clipboard on tap.</li>
 *   <li>The <b>verification URL</b> ({@code https://yt.be/activate}) as a tappable link that opens
 *       the system browser / Custom Tab (via {@link Utils#openLinkExt}). When the presenter also
 *       supplies the full QR URL ({@code https://youtube.com/qr/activate/<code>}), tapping opens
 *       that instead so the code is pre-filled.</li>
 *   <li>A <b>QR code</b> generated CLIENT-SIDE from the full sign-in URL with zxing (no
 *       {@code api.qrserver.com} round-trip that the TV path uses via {@code Utils.toQrCodeLink}).</li>
 * </ul>
 * A spinner shows until the first code arrives; the {@link #showCode(String, String)} 2-arg error
 * path (empty {@code signInUrl}) surfaces the backend error message verbatim.
 */
public class MobileSignInActivity extends MobileActivity implements SignInView {
    private static final String TAG = MobileSignInActivity.class.getSimpleName();
    /** Encode resolution for the QR bitmap; the ImageView scales it down (fitCenter). */
    private static final int QR_SIZE_PX = 600;

    private YTSignInPresenter mSignInPresenter;

    private ProgressBar mProgress;
    private TextView mErrorView;
    private View mContent;
    private MaterialCardView mCodeCard;
    private TextView mCodeView;
    private TextView mUrlView;
    private ImageView mQrView;
    private ImageButton mBackButton;

    private String mUserCode;
    private String mSignInUrl;
    /** The QR/full URL (code pre-filled); falls back to {@link #mSignInUrl} when absent. */
    private String mFullSignInUrl;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_mobile_signin);

        bindViews();

        mBackButton.setOnClickListener(v -> onBackPressed());
        mCodeCard.setOnClickListener(v -> copyCodeToClipboard());
        mUrlView.setOnClickListener(v -> openSignInUrl());

        mSignInPresenter = YTSignInPresenter.instance(this);
        mSignInPresenter.setView(this);
        mSignInPresenter.onViewInitialized();
    }

    private void bindViews() {
        mProgress = findViewById(R.id.mobile_signin_progress);
        mErrorView = findViewById(R.id.mobile_signin_error);
        mContent = findViewById(R.id.mobile_signin_content);
        mCodeCard = findViewById(R.id.mobile_signin_code_card);
        mCodeView = findViewById(R.id.mobile_signin_code);
        mUrlView = findViewById(R.id.mobile_signin_url);
        mQrView = findViewById(R.id.mobile_signin_qr);
        mBackButton = findViewById(R.id.mobile_signin_back);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Keep the status bar (clock/battery/notifications) visible - this is not the player.
        // MotherActivity.onResume() goes immersive when fullscreen mode is on (default true).
        showSystemBars();
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
            mErrorView.setVisibility(View.GONE);
            mContent.setVisibility(View.VISIBLE);

            mCodeView.setText(userCode);
            mUrlView.setText(signInUrl);

            renderQrCode(mFullSignInUrl);
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
        mErrorView.setVisibility(View.VISIBLE);
        mErrorView.setText(!TextUtils.isEmpty(message) ? message : getString(R.string.mobile_signin_error));
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

    private void renderQrCode(String data) {
        if (TextUtils.isEmpty(data)) {
            return;
        }

        Bitmap bitmap = generateQrBitmap(data, QR_SIZE_PX);
        if (bitmap != null) {
            mQrView.setImageBitmap(bitmap);
            mQrView.setVisibility(View.VISIBLE);
        } else {
            // zxing failed: hide the QR rather than show a blank box. The code + tappable URL
            // are still enough to complete sign-in.
            mQrView.setVisibility(View.GONE);
        }
    }

    private Bitmap generateQrBitmap(String data, int sizePx) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

            BitMatrix matrix = new MultiFormatWriter().encode(data, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);

            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;
        } catch (WriterException | IllegalArgumentException e) {
            Log.e(TAG, "QR generation failed: %s", e.getMessage());
            return null;
        }
    }
}
