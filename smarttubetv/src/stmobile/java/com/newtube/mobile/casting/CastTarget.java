package com.newtube.mobile.casting;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.CastScreen;

/**
 * One row in the cast picker: a discovered or manually paired receiver the phone can drive.
 *
 * <p>Route model (CASTING.md "Shared architecture"): each target carries the {@link Route} it will
 * be driven over plus honest capability flags. Lounge routes (Route B) and the Direct-cast route
 * (Route A, {@link Route#CAST_V2}) share this one data model. A physical Cast device is ONE picker
 * row (a {@link #fromCastDevice} target, optionally carrying a {@link #getSavedScreenId saved
 * Lounge screenId}); its YouTube-app mode ({@link #fromCastDeviceYouTubeApp}) only materializes as
 * a target when the user picks that mode in the chooser — it is never a row of its own.</p>
 */
public final class CastTarget {

    /** How a session with this target is established/driven. */
    public enum Route {
        /** YouTube Lounge session, target discovered via DIAL (SSDP). */
        LOUNGE_DIAL,
        /** YouTube Lounge session, target paired manually with a TV code (persisted). */
        LOUNGE_MANUAL,
        /**
         * YouTube Lounge session on a Google Cast device: dongles dropped DIAL, so the screenId is
         * read over the Cast v2 mdx shim ({@code MdxScreenIdReader}) at tap time. Not connectable
         * until {@link #withScreenId} ran.
         */
        LOUNGE_MDX,
        /** Direct cast (Cast v2 + phone proxy) - Route A: Default Media Receiver fed by the phone. */
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

    // ---- Cast v2 endpoint (CAST_V2 + LOUNGE_MDX: mDNS-discovered devices) ----
    @Nullable
    private final String mCastHost;
    private final int mCastPort;
    /**
     * CAST_V2 rows only: the Lounge screenId of a previously paired screen the picker merged into
     * this device (by name, or learned from a past mdx read). Lets the "Use the TV's YouTube app"
     * chooser option connect instantly instead of re-running the 15s mdx shim. Distinct from
     * {@link #mScreen} on purpose: this target still connects over Route A, and the session
     * manager must keep seeing the exact CAST_V2 shape it always has.
     */
    @Nullable
    private final String mSavedScreenId;

    private CastTarget(Route route, String name, boolean adFree, boolean phoneFree,
                       @Nullable CastScreen screen, @Nullable String dialAppUrl, @Nullable String dialLocation,
                       @Nullable String castHost, int castPort, @Nullable String savedScreenId) {
        mRoute = route;
        mName = name;
        mAdFree = adFree;
        mPhoneFree = phoneFree;
        mScreen = screen;
        mDialAppUrl = dialAppUrl;
        mDialLocation = dialLocation;
        mCastHost = castHost;
        mCastPort = castPort;
        mSavedScreenId = savedScreenId;
    }

    /** A DIAL-discovered TV. {@code screenId} may be null: present-but-needs-launch (see DialDiscovery). */
    public static CastTarget fromDial(String friendlyName, @Nullable String screenId,
                                      String appUrl, String location) {
        CastScreen screen = TextUtils.isEmpty(screenId) ? null : new CastScreen(screenId, friendlyName);
        // Honest default: a SmartTube receiver can't be told apart from stock YouTube
        // automatically, so every Lounge target advertises "has ads" (adFree=false) for now.
        return new CastTarget(Route.LOUNGE_DIAL, friendlyName, false, true, screen, appUrl, location, null, -1, null);
    }

    /** A manually paired (TV-code) screen, fresh from pairing or restored from prefs. */
    public static CastTarget fromPairedScreen(CastScreen screen) {
        return new CastTarget(Route.LOUNGE_MANUAL, screen.getName(), false, true, screen, null, null, null, -1, null);
    }

    /**
     * An mDNS-discovered Cast receiver, driven directly (Route A). Ad-free by construction (the
     * phone feeds the Default Media Receiver our own proxied stream), but NOT phone-free: every
     * media byte relays through the phone - the chooser subtitle says so.
     */
    public static CastTarget fromCastDevice(String name, String host, int port) {
        return new CastTarget(Route.CAST_V2, name, true, false, null, null, null, host, port, null);
    }

    /**
     * The same Cast device's YouTube app, driven over Lounge (Route B semantics: TV plays natively,
     * has ads, phone can leave). Not connectable yet - the picker runs the mdx shim (or reuses a
     * {@link #getSavedScreenId saved screenId}) and upgrades this target with {@link #withScreenId}.
     */
    public static CastTarget fromCastDeviceYouTubeApp(String name, String host, int port) {
        return new CastTarget(Route.LOUNGE_MDX, name, false, true, null, null, null, host, port, null);
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

    /**
     * True once the target has everything needed to connect: a screenId for the Lounge routes,
     * a Cast host for Route A (which needs no pairing - the TLS channel is the whole handshake).
     */
    public boolean isConnectable() {
        if (mRoute == Route.CAST_V2) {
            return !TextUtils.isEmpty(mCastHost);
        }
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

    /** Cast v2 endpoint host (CAST_V2 / LOUNGE_MDX targets only). */
    @Nullable
    public String getCastHost() {
        return mCastHost;
    }

    /** Cast v2 endpoint TLS port (usually 8009); -1 on non-Cast targets. */
    public int getCastPort() {
        return mCastPort;
    }

    /** Saved Lounge screenId merged into a CAST_V2 row; null when the YouTube-app mode still needs the mdx read. */
    @Nullable
    public String getSavedScreenId() {
        return mSavedScreenId;
    }

    /** Same device, now with a screenId (post DIAL launch/poll or mdx shim). Keeps route + endpoint data. */
    public CastTarget withScreenId(String screenId) {
        return new CastTarget(mRoute, mName, mAdFree, mPhoneFree,
                new CastScreen(screenId, mName), mDialAppUrl, mDialLocation, mCastHost, mCastPort, mSavedScreenId);
    }

    /** Same CAST_V2 device, now remembering a paired Lounge screen for its YouTube-app mode. */
    public CastTarget withSavedScreenId(@Nullable String savedScreenId) {
        return new CastTarget(mRoute, mName, mAdFree, mPhoneFree,
                mScreen, mDialAppUrl, mDialLocation, mCastHost, mCastPort, savedScreenId);
    }

    /**
     * Picker dedupe key = one row per physical device: both modes of a Cast device share the
     * {@code cast:<host>} key (only CAST_V2 targets become rows; LOUNGE_MDX maps to the same key
     * defensively); paired screens and DIAL devices that resolve to the same Lounge screen
     * collapse into one row; un-launched DIAL devices key on their SSDP location.
     */
    public String getDedupeKey() {
        if (mRoute == Route.CAST_V2 || mRoute == Route.LOUNGE_MDX) {
            return "cast:" + mCastHost;
        }
        if (mScreen != null && !TextUtils.isEmpty(mScreen.getScreenId())) {
            return "screen:" + mScreen.getScreenId();
        }
        return "dial:" + mDialLocation;
    }

    @NonNull
    @Override
    public String toString() {
        return "CastTarget{" + mRoute + ", " + mName
                + ", screenId=" + (mScreen != null ? mScreen.getScreenId() : null)
                + (mCastHost != null ? ", cast=" + mCastHost + ":" + mCastPort : "") + "}";
    }
}
