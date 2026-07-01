package com.newtube.mobile.ui.webbrowser;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.WebBrowserPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.WebBrowserView;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * Touch replacement for the Leanback {@code WebBrowserActivity}.
 *
 * <p>The inherited TV screen renders an in-app WebView that shows a QR code / "scan on your phone"
 * page - pointless on the phone itself. Here the {@link WebBrowserView#loadUrl(String)} contract is
 * satisfied by simply handing the URL to the device's browser via an {@code ACTION_VIEW} intent and
 * finishing immediately, so About / SponsorBlock / DeArrow "open link" actions land on the real
 * page in the user's browser.</p>
 *
 * <p>The About-screen links arrive wrapped by {@code Utils.toQrCodeLink()} as
 * {@code https://api.qrserver.com/v1/create-qr-code/?data=<realUrl>} (the TV path turns them into a
 * scannable QR). On mobile that wrapper is unwrapped ({@link #unwrapQrCodeLink}) so the actual
 * target page opens rather than an image of a QR code. Links that come through un-wrapped
 * (SponsorBlock / DeArrow status/provider pages) open as-is.</p>
 *
 * <p>Deliberately extends {@link MotherActivity} rather than the shared {@code MobileActivity}: this
 * screen finishes in {@code onCreate} and must NOT relaunch its parent (Home) on top of the caller
 * (e.g. the About dialog), which {@code MobileActivity.finish()} would do via {@code startParentView}.
 * A plain {@code finish()} (MotherActivity does not override it) just tears this transient activity
 * down.</p>
 */
public class MobileWebBrowserActivity extends MotherActivity implements WebBrowserView {
    private WebBrowserPresenter mPresenter;
    /** loadUrl() may be invoked twice (presenter's direct call + onViewInitialized); launch once. */
    private boolean mHandled;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mPresenter = WebBrowserPresenter.instance(this);
        mPresenter.setView(this);
        mPresenter.onViewInitialized();
    }

    @Override
    protected void onDestroy() {
        if (mPresenter != null && mPresenter.getView() == this) {
            mPresenter.onViewDestroyed();
        }
        super.onDestroy();
    }

    @Override
    public void loadUrl(String url) {
        if (mHandled) {
            return;
        }
        mHandled = true;

        String target = unwrapQrCodeLink(url);

        if (!TextUtils.isEmpty(target)) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(target));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                // No browser installed: fall back to the shared Custom Tabs / open-link helper.
                try {
                    Utils.openLinkExt(this, target);
                } catch (Exception ignored) {
                    MessageHelpers.showMessage(this, R.string.mobile_no_browser);
                }
            }
        }

        finish();
    }

    /**
     * About-screen links are wrapped as {@code https://api.qrserver.com/v1/create-qr-code/?data=<url>}
     * (see {@code Utils.toQrCodeLink}); return the inner {@code data} URL so the real page opens on
     * mobile instead of an image of a QR code. Returns the input unchanged for any other link.
     */
    private static String unwrapQrCodeLink(String url) {
        if (TextUtils.isEmpty(url)) {
            return url;
        }

        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host != null && host.contains("qrserver.com")) {
                String data = uri.getQueryParameter("data");
                if (!TextUtils.isEmpty(data)) {
                    return data;
                }
            }
        } catch (Exception ignored) {
            // Malformed URL: fall through and use it verbatim.
        }

        return url;
    }
}
