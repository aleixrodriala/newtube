package com.newtube.mobile.ui.common;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
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
 * download also dismissed the sheet) it waits for the screen underneath to resume - or, when that
 * screen was never paused behind the sheet, shows there once the sheet is gone. With no screen in
 * front at all (backgrounded) it falls back to the old Toast; a message is never dropped.</p>
 */
public final class MobileSnackbar {
    private static final long PENDING_MAX_AGE_MS = 3_000;
    private static final long FALLBACK_TOAST_MS = 1_500;
    /** Long enough to read and reach the action (Material: 4-10 s for a snackbar with an action). */
    private static final int ACTION_DURATION_MS = 4_000;
    private static final int PLAIN_DURATION_MS = 2_750;

    private static boolean sInstalled;
    @Nullable private static WeakReference<Activity> sResumed;
    /**
     * The last screen that could host a Snackbar and is still started (visible) - the one under a
     * menu sheet. Unlike {@link #sResumed} it survives the screen being paused behind the sheet.
     */
    @Nullable private static WeakReference<Activity> sHost;
    /** {@link #sHost} was paused (it will resume, and take the message then). */
    private static boolean sHostPaused;
    @Nullable private static Pending sPending;
    /** Handed to a screen and drawn on its next frame; {@link #replaceText} can still reword it. */
    @Nullable private static Pending sPosted;
    private static final long HOST_FALLBACK_MS = 250;
    /** The Snackbar last shown, and its text - see {@link #replaceText}. */
    @Nullable private static WeakReference<Snackbar> sLast;
    @Nullable private static CharSequence sLastText;

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
                MobileSnackbar::show);
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                sResumed = new WeakReference<>(activity);
                if (canHost(activity)) {
                    sHost = new WeakReference<>(activity);
                    sHostPaused = false;
                    deliverPending(activity);
                }
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
                if (sResumed != null && sResumed.get() == activity) {
                    sResumed = null;
                }
                if (sHost != null && sHost.get() == activity) {
                    sHostPaused = true;
                }
                if (activity instanceof MobileAppDialogActivity && sPending != null) {
                    deliverToUnpausedHostLater(sPending);
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
                if (sHost != null && sHost.get() == activity) {
                    sHost = null;
                }
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
            }
        });
    }

    /**
     * A menu sheet going away normally resumes the screen under it, which then takes the waiting
     * message (onActivityResumed). But a screen can stay RESUMED behind a translucent sheet - on
     * the Pixel (Android 17) the watch page did - and then gets no such callback: the message waited
     * for nothing and ended as the fallback Toast (the watch page's "Download started" with its
     * View). So shortly after, if the screen under the sheet was never paused and the sheet is
     * gone (or going), show it there.
     */
    private static void deliverToUnpausedHostLater(Pending pending) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (sPending != pending || sHostPaused) {
                return;
            }
            Activity host = sHost != null ? sHost.get() : null;
            Activity front = sResumed != null ? sResumed.get() : null;
            boolean sheetStillOpen = front instanceof MobileAppDialogActivity && !front.isFinishing();
            if (host != null && canHost(host) && !sheetStillOpen) {
                deliverPending(host);
            }
        }, HOST_FALLBACK_MS);
    }

    /** Show the waiting message (if any, and still fresh) on {@code host}, from its next frame. */
    private static void deliverPending(Activity host) {
        Pending pending = sPending;
        if (pending == null) {
            return;
        }
        if (SystemClock.uptimeMillis() - pending.createdAtMs > PENDING_MAX_AGE_MS) {
            // Too late for this screen. Leave it waiting: while it is still sPending, its fallback
            // Toast has not run yet (that clears it), so the Toast still comes and nothing is lost.
            // Reached when the main thread was busy for seconds - a resume queued before the
            // Toast's time but run after this age used to drop the message silently.
            return;
        }
        sPending = null;
        sPosted = pending;
        // Next frame, not now: onActivityResumed runs inside super.onResume(), before the screen's
        // own onResume has re-shown what the Snackbar anchors above (Browse re-attaches its
        // mini-player card there).
        host.getWindow().getDecorView().post(() -> {
            if (sPosted == pending) {
                sPosted = null;
            }
            if (canHost(host)) {
                make(host, pending.text, pending.action, pending.onAction);
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
        deliverToUnpausedHostLater(pending);
        Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (sPending == pending) {
                sPending = null;
                MessageHelpers.showMessage(app, pending.text.toString());
            }
        }, FALLBACK_TOAST_MS);
    }

    /**
     * Rewords a message still on screen (or still waiting for its screen) that reads {@code from} -
     * e.g. "Download started" once a small file is already done before the Snackbar has gone.
     * Does nothing once the message is gone or another one replaced it.
     */
    public static void replaceText(CharSequence from, CharSequence to) {
        Pending pending = sPending != null ? sPending : sPosted;
        if (pending != null && TextUtils.equals(pending.text, from)) {
            // In place: the scheduled deliveries and the fallback Toast know it by identity. A new
            // object here left all of them waiting for one that was gone - message lost.
            pending.text = to;
            return;
        }
        Snackbar last = sLast != null ? sLast.get() : null;
        if (last != null && last.isShownOrQueued() && TextUtils.equals(sLastText, from)) {
            last.setText(to);
            sLastText = to;
        }
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
        sLast = new WeakReference<>(snackbar);
        sLastText = text;
        // Above what sits at the bottom rather than over it: the docked mini-player card (its
        // pause button was covered for the whole message), else Browse's bottom nav.
        View mini = activity.findViewById(R.id.mobile_mini_player);
        View nav = activity.findViewById(R.id.mobile_bottom_nav);
        if (mini != null && mini.getVisibility() == View.VISIBLE) {
            snackbar.setAnchorView(mini);
        } else if (nav != null && nav.getVisibility() == View.VISIBLE) {
            snackbar.setAnchorView(nav);
        }
        snackbar.show();
    }

    private static final class Pending {
        /** Reworded in place by {@link #replaceText}. */
        CharSequence text;
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
