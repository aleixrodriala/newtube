package com.newtube.mobile.casting;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.CastScreen;

/**
 * One row in the cast picker: a discovered or manually paired receiver the phone can drive.
 *
 * <p>Route model (CASTING.md "Shared architecture"): each target carries the {@link Route} it will
 * be driven over plus honest capability flags for the picker badges. Today only the Lounge routes
 * exist (Route B); {@link Route#CAST_V2} is reserved for the Direct-cast stack (Route A) so adding
 * it later is a new enum constant + connect path, not a data-model change.</p>
 */
public final class CastTarget {

    /** How a session with this target is established/driven. */
    public enum Route {
        /** YouTube Lounge session, target discovered via DIAL (SSDP). */
        LOUNGE_DIAL,
        /** YouTube Lounge session, target paired manually with a TV code (persisted). */
        LOUNGE_MANUAL,
        /** Direct cast (Cast v2 + phone proxy) - Route A, not implemented yet. */
        CAST_V2
    }

    private final Route mRoute;
    private final String mName;
    /** Ads on the receiver? False for stock YouTube receivers; Route A / SmartTube receivers are ad-free. */
    private final boolean mAdFree;
    /** Can the phone disconnect/leave while the TV keeps playing? True for Lounge, false for Route A. */
    private final boolean mPhoneFree;

    // ---- Lounge connection data (null until pairing/discovery produced a screenId) ----
    @Nullable
    private final CastScreen mScreen;

    // ---- DIAL metadata (LOUNGE_DIAL only) ----
    /** Application-URL base from the DIAL device description (no trailing app name). */
    @Nullable
    private final String mDialAppUrl;
    /** LOCATION header of the SSDP response; used as the dedupe key for discovered devices. */
    @Nullable
    private final String mDialLocation;

    private CastTarget(Route route, String name, boolean adFree, boolean phoneFree,
                       @Nullable CastScreen screen, @Nullable String dialAppUrl, @Nullable String dialLocation) {
        mRoute = route;
        mName = name;
        mAdFree = adFree;
        mPhoneFree = phoneFree;
        mScreen = screen;
        mDialAppUrl = dialAppUrl;
        mDialLocation = dialLocation;
    }

    /** A DIAL-discovered TV. {@code screenId} may be null: present-but-needs-launch (see DialDiscovery). */
    public static CastTarget fromDial(String friendlyName, @Nullable String screenId,
                                      String appUrl, String location) {
        CastScreen screen = TextUtils.isEmpty(screenId) ? null : new CastScreen(screenId, friendlyName);
        // Honest generic badge: a SmartTube receiver can't be told apart from stock YouTube
        // automatically, so every Lounge target advertises "has ads" (adFree=false) for now.
        return new CastTarget(Route.LOUNGE_DIAL, friendlyName, false, true, screen, appUrl, location);
    }

    /** A manually paired (TV-code) screen, fresh from pairing or restored from prefs. */
    public static CastTarget fromPairedScreen(CastScreen screen) {
        return new CastTarget(Route.LOUNGE_MANUAL, screen.getName(), false, true, screen, null, null);
    }

    public Route getRoute() {
        return mRoute;
    }

    public String getName() {
        return mName;
    }

    public boolean isAdFree() {
        return mAdFree;
    }

    public boolean isPhoneFree() {
        return mPhoneFree;
    }

    @Nullable
    public CastScreen getScreen() {
        return mScreen;
    }

    /** True once the target has everything needed for a Lounge connect. */
    public boolean isConnectable() {
        return mScreen != null && !TextUtils.isEmpty(mScreen.getScreenId());
    }

    @Nullable
    public String getDialAppUrl() {
        return mDialAppUrl;
    }

    @Nullable
    public String getDialLocation() {
        return mDialLocation;
    }

    /** Same DIAL device, now with a screenId (post launch/poll). Keeps route + DIAL metadata. */
    public CastTarget withScreenId(String screenId) {
        return new CastTarget(mRoute, mName, mAdFree, mPhoneFree,
                new CastScreen(screenId, mName), mDialAppUrl, mDialLocation);
    }

    /**
     * Picker dedupe key: paired screens and DIAL devices that resolve to the same Lounge screen
     * collapse into one row; un-launched DIAL devices key on their SSDP location.
     */
    public String getDedupeKey() {
        if (mScreen != null && !TextUtils.isEmpty(mScreen.getScreenId())) {
            return "screen:" + mScreen.getScreenId();
        }
        return "dial:" + mDialLocation;
    }

    @NonNull
    @Override
    public String toString() {
        return "CastTarget{" + mRoute + ", " + mName
                + ", screenId=" + (mScreen != null ? mScreen.getScreenId() : null) + "}";
    }
}
