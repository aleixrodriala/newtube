package com.liskovsoft.smartyoutubetv2.common.app.views;

public interface SignInView {
    void showCode(String userCode, String signInUrl);
    void showCode(String userCode, String signInUrl, String fullSignInUrl);
    void close();
    /**
     * Sign-in completed while this view is (possibly) behind the browser/YouTube approval UI.
     * Views that can render a success state override this to confirm + bring themselves back;
     * the default keeps the old contract (just close).
     */
    default void showSuccess() {
        close();
    }
}
