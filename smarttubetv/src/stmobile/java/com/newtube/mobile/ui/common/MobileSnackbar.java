package com.newtube.mobile.ui.common;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.snackbar.Snackbar;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.dialog.MobileAppDialogActivity;

import java.lang.ref.WeakReference;

/**
 * NEWTUBE(snackbar): the app's confirmation line - a bottom Snackbar on the screen the person is
 * looking at, with an optional action ("Sign in", "Undo", "View"). Replaces system Toasts at the
 * phone's own call sites: a Toast is drawn by the system in the system's theme (a pale bubble with
 * the launcher icon over the dark app), cannot carry an action, and got covered by/covered the
 * sheets it confirmed. Standing rule: player actions confirm via a bottom Snackbar.
 *
 * <p>{@link #show(Context, CharSequence, CharSequence, Runnable)} works from any context: it posts
 * on the activity in front, and when that is a menu sheet that is closing (the tap that started a
 * download also dismissed the sheet) it waits for the screen underneath to resume. With no screen
 * in front at all (backgrounded) it falls back to the old Toast.</p>
 */
public final class MobileSnackbar {
    private static final long PENDING_MAX_AGE_MS = 3_000;
    private static final long FALLBACK_TOAST_MS = 1_500;
    /** Long enough to read and reach the action (Material: 4-10 s for a snackbar with an action). */
    private static final int ACTION_DURATION_MS = 4_000;
    private static final int PLAIN_DURATION_MS = 2_750;

    private static boolean sInstalled;
    @Nullable private static WeakReference<Activity> sResumed;
    @Nullable private static Pending sPending;

    private MobileSnackbar() {
    }

    /** Idempotent; called from MobileActivity.onCreate so the first screen is already tracked. */
    public static void install(@NonNull Application application) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        // Card/section menu confirmations (pin to You, subscribe) come from the shared menu presenters.
        com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.BaseMenuPresenter.setConfirmationSink(
                (context, message) -> show(context, message, null, null));
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                sResumed = new WeakReference<>(activity);
                Pending pending = sPending;
                if (pending != null && canHost(activity)) {
                    sPending = null;
                    if (SystemClock.uptimeMillis() - pending.createdAtMs <= PENDING_MAX_AGE_MS) {
                        make(activity, pending.text, pending.action, pending.onAction);
                    }
                }
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
                if (sResumed != null && sResumed.get() == activity) {
                    sResumed = null;
                }
            }

            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
            }

            @Override
            public void onActivityStarted(@NonNull Activity activity) {
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
            }
        });
    }

    public static void show(Context context, int textRes) {
        show(context, context.getString(textRes), null, null);
    }

    public static void show(Context context, CharSequence text, @Nullable CharSequence action,
                            @Nullable Runnable onAction) {
        Activity front = sResumed != null ? sResumed.get() : null;
        if (canHost(front)) {
            make(front, text, action, onAction);
            return;
        }
        // A menu sheet in front (usually closing because of this very tap), or between two screens:
        // the screen that resumes next takes the message. Nothing resumes (app in the background):
        // the old Toast, so the message is never lost.
        Pending pending = new Pending(text, action, onAction);
        sPending = pending;
        Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (sPending == pending) {
                sPending = null;
                MessageHelpers.showMessage(app, text.toString());
            }
        }, FALLBACK_TOAST_MS);
    }

    private static boolean canHost(@Nullable Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed()
                && !(activity instanceof MobileAppDialogActivity);
    }

    private static void make(Activity activity, CharSequence text, @Nullable CharSequence action,
                             @Nullable Runnable onAction) {
        View root = activity.findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        Snackbar snackbar = Snackbar.make(root, text, action != null ? ACTION_DURATION_MS : PLAIN_DURATION_MS);
        if (action != null && onAction != null) {
            snackbar.setAction(action, v -> onAction.run());
        }
        // Above Browse's bottom nav rather than over it.
        View nav = activity.findViewById(R.id.mobile_bottom_nav);
        if (nav != null && nav.getVisibility() == View.VISIBLE) {
            snackbar.setAnchorView(nav);
        }
        snackbar.show();
    }

    private static final class Pending {
        final CharSequence text;
        @Nullable final CharSequence action;
        @Nullable final Runnable onAction;
        final long createdAtMs = SystemClock.uptimeMillis();

        Pending(CharSequence text, @Nullable CharSequence action, @Nullable Runnable onAction) {
            this.text = text;
            this.action = action;
            this.onAction = onAction;
        }
    }
}
