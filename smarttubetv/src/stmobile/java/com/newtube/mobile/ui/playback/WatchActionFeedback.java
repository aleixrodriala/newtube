package com.newtube.mobile.ui.playback;

import android.app.Activity;
import android.text.TextUtils;

import com.liskovsoft.smartyoutubetv2.common.app.presenters.YTSignInPresenter;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeSignInService;
import com.newtube.mobile.ui.common.MobileSnackbar;

/**
 * NEWTUBE(snackbar): the watch page's confirmations. Like/Dislike/Save need an account; signed out
 * they used to raise a pale system Toast ("Sign in to use this") with no way to act on it - now a
 * Snackbar says what signing in is for and carries the Sign in action. Subscribe/Unsubscribe
 * flipped the button silently (and signed out it quietly keeps a LOCAL subscription); now it says
 * which channel, with Undo.
 */
final class WatchActionFeedback {
    private WatchActionFeedback() {
    }

    /**
     * Signed out: explain with {@code messageRes} + a Sign in action and return true (the caller
     * must not go on - the shared controller would only add its own Toast). Signed in: false.
     */
    static boolean blockIfSignedOut(Activity activity, int messageRes) {
        if (YouTubeSignInService.instance().isSigned()) {
            return false;
        }
        MobileSnackbar.show(activity, activity.getString(messageRes),
                activity.getString(R.string.action_signin),
                () -> YTSignInPresenter.instance(activity).start());
        return true;
    }

    /** "Added to Liked videos" / "Removed from Liked videos" / "You disliked..." / "Dislike removed", with Undo. */
    static void confirmRating(Activity activity, boolean like, boolean on, Runnable undo) {
        int text = like
                ? (on ? R.string.mobile_liked : R.string.mobile_like_removed)
                : (on ? R.string.mobile_disliked : R.string.mobile_dislike_removed);
        MobileSnackbar.show(activity, activity.getString(text), activity.getString(R.string.mobile_undo), undo);
    }

    /** A rating YouTube did not take (offline, or the request failed); the thumbs are back as they were. */
    static void ratingNotSaved(Activity activity) {
        MobileSnackbar.show(activity, R.string.mobile_rating_not_saved);
    }

    /** A rating tapped before the video's data arrived (nothing was sent). */
    static void rateNotReady(Activity activity) {
        MobileSnackbar.show(activity, R.string.mobile_rate_not_ready);
    }

    static void confirmSubscription(Activity activity, boolean subscribed, String channel, Runnable undo) {
        String text;
        if (TextUtils.isEmpty(channel)) {
            text = activity.getString(subscribed ? R.string.mobile_subscribed : R.string.mobile_unsubscribed);
        } else {
            text = activity.getString(subscribed ? R.string.mobile_subscribed_to : R.string.mobile_unsubscribed_from,
                    channel);
        }
        MobileSnackbar.show(activity, text, activity.getString(R.string.mobile_undo), undo);
    }
}
