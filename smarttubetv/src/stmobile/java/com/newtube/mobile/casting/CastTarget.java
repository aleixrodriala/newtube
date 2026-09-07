package com.newtube.mobile.casting;

import androidx.annotation.Nullable;
import com.liskovsoft.mediaserviceinterfaces.data.CastScreen;

/** One receiver route. CastDeviceRegistry keeps all routes belonging to the same physical TV. */
public final class CastTarget {
    public enum Route { LOUNGE_DIAL, LOUNGE_MANUAL, LOUNGE_MDX, CAST_V2 }

    /** Pairing codes do not identify the app. UNKNOWN includes all legacy pairings. */
    public enum ReceiverApp { UNKNOWN, SMARTTUBE, YOUTUBE }

    private final Route mRoute;
    private final String mName;
    private final ReceiverApp mReceiverApp;
    @Nullable private final CastScreen mScreen;
    @Nullable private final String mDialAppUrl;
    @Nullable private final String mDialLocation;
    @Nullable private final String mCastHost;
    private final int mCastPort;

    private CastTarget(Route route, String name, ReceiverApp app, @Nullable CastScreen screen,
                       @Nullable String appUrl, @Nullable String location, @Nullable String host, int port) {
        mRoute = route;
        mName = name;
        mReceiverApp = app;
        mScreen = screen;
        mDialAppUrl = appUrl;
        mDialLocation = location;
        mCastHost = host;
        mCastPort = port;
    }

    public static CastTarget fromDial(String name, @Nullable String screenId, String appUrl, String location) {
        return new CastTarget(Route.LOUNGE_DIAL, name, ReceiverApp.YOUTUBE,
                empty(screenId) ? null : new CastScreen(screenId, name), appUrl, location, null, -1);
    }

    public static CastTarget fromPairedScreen(CastScreen screen) {
        return fromPairedScreen(screen, ReceiverApp.UNKNOWN);
    }

    public static CastTarget fromPairedScreen(CastScreen screen, ReceiverApp app) {
        return new CastTarget(Route.LOUNGE_MANUAL, screen.getName(), app, screen, null, null, null, -1);
    }

    public static CastTarget fromCastDevice(String name, String host, int port) {
        return new CastTarget(Route.CAST_V2, name, ReceiverApp.UNKNOWN, null, null, null, host, port);
    }

    /** Requires a Cast launch/read before its Lounge session can connect. */
    public static CastTarget fromCastDeviceYouTubeApp(String name, String host, int port) {
        return new CastTarget(Route.LOUNGE_MDX, name, ReceiverApp.YOUTUBE, null, null, null, host, port);
    }

    public Route getRoute() { return mRoute; }
    public String getName() { return mName; }
    public ReceiverApp getReceiverApp() { return mReceiverApp; }
    public boolean isAdFree() { return mRoute == Route.CAST_V2 || mReceiverApp == ReceiverApp.SMARTTUBE; }
    public boolean isPhoneFree() { return mRoute != Route.CAST_V2; }
    @Nullable public CastScreen getScreen() { return mScreen; }
    @Nullable public String getDialAppUrl() { return mDialAppUrl; }
    @Nullable public String getDialLocation() { return mDialLocation; }
    @Nullable public String getCastHost() { return mCastHost; }
    public int getCastPort() { return mCastPort; }

    public boolean isConnectable() {
        return mRoute == Route.CAST_V2 ? !empty(mCastHost)
                : mScreen != null && !empty(mScreen.getScreenId());
    }

    public CastTarget withScreenId(String screenId) {
        return new CastTarget(mRoute, mName, mReceiverApp, new CastScreen(screenId, mName),
                mDialAppUrl, mDialLocation, mCastHost, mCastPort);
    }

    public CastTarget withReceiverApp(ReceiverApp app) {
        return new CastTarget(mRoute, mName, app, mScreen, mDialAppUrl, mDialLocation, mCastHost, mCastPort);
    }

    /** Route identity, independent of display names; two receiver apps retain their own IDs. */
    public String getDedupeKey() {
        if (mRoute == Route.CAST_V2 || mRoute == Route.LOUNGE_MDX) return mRoute + ":" + mCastHost;
        if (mScreen != null && !empty(mScreen.getScreenId())) return "screen:" + mScreen.getScreenId();
        return "dial:" + mDialLocation;
    }

    @Nullable String getDeviceHost() {
        if (!empty(mCastHost)) return mCastHost;
        try {
            return mDialLocation == null ? null : java.net.URI.create(mDialLocation).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean empty(@Nullable String value) { return value == null || value.isEmpty(); }
}
