package com.newtube.mobile.ui.browse;

import android.content.Context;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * NEWTUBE(buttons): wording of the sign-in-gated empty state, per section. The old single template
 * "Sign in to see your %1$s" pasted the section title in verbatim, which reads fine for
 * "Subscriptions" but not for "My videos" ("Sign in to see your My videos") or "Channels".
 * Sections without a dedicated line keep the template (or the generic line when no title is known).
 */
final class SignInCopy {
    private SignInCopy() {
    }

    static String forSection(Context context, int sectionId, String sectionTitle) {
        int resId = resFor(sectionId);
        if (resId != 0) {
            return context.getString(resId);
        }
        return sectionTitle != null
                ? context.getString(R.string.mobile_empty_signin_section, sectionTitle)
                : context.getString(R.string.mobile_empty_signin_generic);
    }

    /** The dedicated line for {@code sectionId}, or 0 when the section has none. */
    static int resFor(int sectionId) {
        switch (sectionId) {
            case MediaGroup.TYPE_SUBSCRIPTIONS:
                return R.string.mobile_empty_signin_subscriptions;
            case MediaGroup.TYPE_CHANNEL_UPLOADS:
                return R.string.mobile_empty_signin_channels;
            case MediaGroup.TYPE_USER_PLAYLISTS:
                return R.string.mobile_empty_signin_playlists;
            case MediaGroup.TYPE_MY_VIDEOS:
                return R.string.mobile_empty_signin_my_videos;
            case MediaGroup.TYPE_HISTORY:
                return R.string.mobile_empty_signin_history;
            case MediaGroup.TYPE_NOTIFICATIONS:
                return R.string.mobile_empty_signin_notifications;
            default:
                return 0;
        }
    }
}
