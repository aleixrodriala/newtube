package com.newtube.mobile.ui.playback;

import android.app.Activity;
import android.content.Context;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDialog;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsCompat;

import com.liskovsoft.smartyoutubetv2.tv.R;

import java.util.List;

/**
 * NEWTUBE(write-comments): the comment and reply composer - the person's avatar, the text and
 * Post, in a window docked above the keyboard over a light dim, like YouTube's own.
 *
 * <p>A separate floating window rather than a view in the comments panel: the player's window is
 * never resized or panned for the keyboard (the video must not move), while a floating window is
 * moved above it by the system on every Android version. Where the platform lays it out edge to
 * edge anyway (Android 16+ ignores the opt-out), the composer pads itself by the keyboard's inset
 * and follows it frame by frame, the way Search does.</p>
 *
 * <p>The panel owns what happens: {@link Callback#onSend} starts the post and {@link #finish()}
 * closes the composer once it is through; anything else that closes it (a tap outside, Back, a
 * failure) hands the text back as a draft.</p>
 */
final class CommentComposer {
    /** YouTube's limit for a comment. */
    private static final int MAX_LENGTH = 10_000;

    interface Callback {
        /** Post was tapped with something to post (trimmed); the composer shows it is sending. */
        void onSend(CommentComposer composer, String text);

        /** The composer is gone; {@code draft} is its text unless it was posted ({@link #finish()}). */
        void onClosed(CommentComposer composer, @Nullable String draft);
    }

    private final AppCompatDialog mDialog;
    private final View mRoot;
    private final EditText mInput;
    private final ImageButton mSend;
    private final View mProgress;
    private final Callback mCallback;
    /** "@handle " a reply starts with: not enough to post on its own. */
    @Nullable
    private final String mPrefill;
    private final int mBottomPadding;
    private boolean mSending;
    private boolean mPosted;
    private boolean mClosedReported;
    private boolean mImeAnimating;

    CommentComposer(Activity activity, int hint, @Nullable CharSequence text, @Nullable String prefill,
                    @Nullable String avatarUrl, Callback callback) {
        mCallback = callback;
        mPrefill = prefill != null ? prefill.trim() : null;
        mDialog = new AppCompatDialog(activity, R.style.MobileCommentComposer);
        Context context = mDialog.getContext();
        // NEWTUBE(theme): the composer's style is a whole app theme, laid over a copy of the
        // screen's - which resets pure black's page colours. Put them back.
        com.newtube.mobile.ui.common.ThemeMode.applyPalette(context);
        mRoot = LayoutInflater.from(context).inflate(R.layout.mobile_comment_composer, null, false);
        mInput = mRoot.findViewById(R.id.comment_composer_input);
        mSend = mRoot.findViewById(R.id.comment_composer_send);
        mProgress = mRoot.findViewById(R.id.comment_composer_progress);
        mBottomPadding = mRoot.getPaddingBottom();

        CommentsAdapter.loadAvatar(mRoot.findViewById(R.id.comment_composer_avatar), avatarUrl,
                context.getResources().getDimensionPixelSize(R.dimen.mobile_comment_avatar));
        mInput.setHint(hint);
        if (!TextUtils.isEmpty(text)) {
            mInput.setText(text);
            mInput.setSelection(mInput.length());
        }
        // While a post is on its way the text is what was sent: no edits that would be lost.
        mInput.setFilters(new InputFilter[] {(source, start, end, dest, dstart, dend) ->
                mSending ? dest.subSequence(dstart, dend) : null,
                new InputFilter.LengthFilter(MAX_LENGTH)});
        mInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updateSend();
            }
        });
        mSend.setOnClickListener(v -> send());
        updateSend();

        mDialog.setContentView(mRoot, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        mDialog.setCanceledOnTouchOutside(true);
        mDialog.setOnShowListener(d -> showKeyboard());
        mDialog.setOnDismissListener(d -> reportClosed());
        Window window = mDialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        installImeInsets();
    }

    void show() {
        mDialog.show();
    }

    boolean isShowing() {
        return mDialog.isShowing();
    }

    /**
     * Closed for another reason (the video changed, the panel went): the text stays a draft. The
     * panel hears it at once - a dialog's own dismiss listener runs a message later, after the
     * panel may already have moved on to another video.
     */
    void dismiss() {
        reportClosed();
        if (mDialog.isShowing()) {
            mDialog.dismiss();
        }
    }

    private void reportClosed() {
        if (!mClosedReported) {
            mClosedReported = true;
            mCallback.onClosed(this, mPosted ? null : mInput.getText().toString());
        }
    }

    /** The post went through: close, with no draft left behind. */
    void finish() {
        mPosted = true;
        dismiss();
    }

    /** While sending: the arrow gives way to a spinner, and a stray tap outside can't close it. */
    void setSending(boolean sending) {
        mSending = sending;
        mDialog.setCancelable(!sending);
        mDialog.setCanceledOnTouchOutside(!sending);
        mSend.setVisibility(sending ? View.INVISIBLE : View.VISIBLE);
        mProgress.setVisibility(sending ? View.VISIBLE : View.GONE);
        updateSend();
    }

    private void send() {
        String text = mInput.getText().toString().trim();
        if (mSending || !hasContent(text)) {
            return;
        }
        mCallback.onSend(this, text);
    }

    /** Something to post: not blank, and more than the "@handle" a reply starts with. */
    private boolean hasContent(String text) {
        return !text.isEmpty() && !text.equals(mPrefill);
    }

    private void updateSend() {
        boolean ready = !mSending && hasContent(mInput.getText().toString().trim());
        mSend.setEnabled(ready);
        mSend.setColorFilter(ContextCompat.getColor(mRoot.getContext(),
                ready ? R.color.mobile_color_on_surface : R.color.mobile_color_on_surface_secondary));
        mSend.setAlpha(ready ? 1f : 0.5f);
    }

    private void showKeyboard() {
        mInput.requestFocus();
        mInput.post(() -> {
            InputMethodManager imm = (InputMethodManager) mInput.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(mInput, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    /**
     * Pads the bar by the keyboard (or navigation bar) it would otherwise sit under. Adds nothing
     * where the system already moved the window above the keyboard: those insets arrive empty.
     */
    private void installImeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(mRoot, (v, insets) -> {
            if (!mImeAnimating) {
                applyInsets(insets);
            }
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.setWindowInsetsAnimationCallback(mRoot,
                new WindowInsetsAnimationCompat.Callback(WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_STOP) {
                    @Override
                    public void onPrepare(@NonNull WindowInsetsAnimationCompat animation) {
                        if ((animation.getTypeMask() & WindowInsetsCompat.Type.ime()) != 0) {
                            mImeAnimating = true;
                        }
                    }

                    @NonNull
                    @Override
                    public WindowInsetsCompat onProgress(@NonNull WindowInsetsCompat insets,
                                                         @NonNull List<WindowInsetsAnimationCompat> running) {
                        if (mImeAnimating) {
                            applyInsets(insets);
                        }
                        return insets;
                    }

                    @Override
                    public void onEnd(@NonNull WindowInsetsAnimationCompat animation) {
                        if ((animation.getTypeMask() & WindowInsetsCompat.Type.ime()) != 0) {
                            mImeAnimating = false;
                            ViewCompat.requestApplyInsets(mRoot);
                        }
                    }
                });
    }

    private void applyInsets(WindowInsetsCompat insets) {
        int ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
        int bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
        int bottom = mBottomPadding + Math.max(ime, bars);
        if (mRoot.getPaddingBottom() != bottom) {
            mRoot.setPadding(mRoot.getPaddingLeft(), mRoot.getPaddingTop(), mRoot.getPaddingRight(), bottom);
        }
    }
}
