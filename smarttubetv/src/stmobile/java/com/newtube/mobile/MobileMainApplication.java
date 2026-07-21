package com.newtube.mobile;

import android.app.Activity;

import com.liskovsoft.smartyoutubetv2.common.app.views.AddDeviceView;
import com.liskovsoft.smartyoutubetv2.common.app.views.AppDialogView;
import com.liskovsoft.smartyoutubetv2.common.app.views.BrowseView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelUploadsView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ChannelView;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.app.views.SearchView;
import com.liskovsoft.smartyoutubetv2.common.app.views.SignInView;
import com.liskovsoft.smartyoutubetv2.common.app.views.ViewManager;
import com.liskovsoft.smartyoutubetv2.common.app.views.WebBrowserView;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers.SuggestionsController;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.YTSignInPresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.smartyoutubetv2.tv.ui.main.MainApplication;
import com.liskovsoft.youtubeapi.app.AppServiceIntCached;
import com.liskovsoft.youtubeapi.service.YouTubeMediaItemService;
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService;
import com.newtube.mobile.ui.adddevice.MobileAddDeviceActivity;
import com.newtube.mobile.ui.browse.MobileBrowseActivity;
import com.newtube.mobile.ui.channel.MobileChannelActivity;
import com.newtube.mobile.ui.channel.MobileChannelUploadsActivity;
import com.newtube.mobile.ui.common.FeedCache;
import com.newtube.mobile.ui.dialog.MobileAppDialogActivity;
import com.newtube.mobile.ui.playback.MobilePlaybackActivity;
import com.newtube.mobile.ui.playback.SystemPipBridge;
import com.newtube.mobile.ui.search.MobileSearchActivity;
import com.newtube.mobile.ui.signin.MobileSignInActivity;
import com.newtube.mobile.ui.webbrowser.MobileWebBrowserActivity;

/**
 * stmobile flavor application class.
 *
 * Wave 1 vertical slice: keep every existing TV init (GlobalPreferences, exception
 * handler, the full {@link ViewManager} mapping for every other screen) by calling
 * {@code super.onCreate()}, then re-point only the {@link BrowseView} (Home) mapping
 * at the touch {@link MobileBrowseActivity}.
 *
 * Wave 2: also re-point the {@link PlaybackView} mapping at the touch
 * {@link MobilePlaybackActivity}. Without this, {@code PlaybackView} would still
 * resolve to the inherited TV mapping ({@code MainApplication.setupViewManager()}
 * registers it as {@code PlaybackView -> PlaybackActivity (Leanback), parent
 * BrowseActivity (Leanback)}), so tapping a video card would launch the Leanback
 * player on a touch phone instead of the new touch one.
 *
 * Wave 3: also re-point the {@link AppDialogView} mapping at the touch
 * {@link MobileAppDialogActivity} (parent: {@link MobileBrowseActivity}, mirroring TV's
 * {@code AppDialogView -> AppDialogActivity, parent BrowseActivity}). Without this override
 * every settings screen and every long-press context menu - which all funnel through
 * {@code AppDialogPresenter} - would still launch the Leanback {@code AppDialogActivity}.
 *

 * NOTE: We also re-point {@link ViewManager#setRoot}. The TV {@code MainApplication}
 * sets the root activity to the Leanback {@code BrowseActivity} inside
 * {@code setupViewManager()}. {@code ViewManager.startDefaultView()} (used by
 * {@code SplashPresenter}'s "last resort" intent-chain handler, i.e. a normal cold
 * launch from the launcher icon) falls back directly to that root Activity class -
 * not through the {@link #register} mapping - so without this override a fresh
 * install would briefly launch the Leanback Home before anything touch-specific ever
 * runs.
 */
public class MobileMainApplication extends MainApplication {

    @Override
    public boolean restorePictureInPictureFromLauncher(Activity launcher) {
        return SystemPipBridge.restoreFromLauncher(launcher);
    }

    @Override
    public void onCreate() {
        // 403 playground: keep the persisted-app-info optimization independently switchable.
        // This must be read before the first AppService access; no user data is cleared.
        boolean forceFreshAppInfo = com.liskovsoft.smartyoutubetv2.tv.BuildConfig.DEBUG
                && "1".equals(getDebugSystemProperty("debug.arc.fresh_app_info"));

        // TTFF FIX (mobile-only, biggest click-to-play win): cap the DEFAULT video quality at 1080p
        // instead of the fixed high rung (~4K/FHD). The first media segment + decode is then <=1080p
        // (a 4K VP9 first frame dominates click-to-play time) and never 1440p/2160p, and phones only
        // display ~1080p anyway. The default is a *preset* whose resolution acts as a strict
        // selection ceiling (on media3 it maps to maxVideoSize constraints in Media3TrackAdapter),
        // so a 1080p preset reliably caps selection on every device. Set BEFORE super.onCreate() so
        // it's in place before PlayerData first restores state. Only changes the DEFAULT (unset) value -
        // a resolution the user explicitly picks is still persisted and honored. TV never calls this ->
        // TV default unchanged.
        PlayerData.setDefaultVideoFormatMax1080(true);

        // Keep ALL existing TV init: Conscrypt, GlobalPreferences, multidex, the
        // global exception handler and every other View->Activity mapping.
        super.onCreate();

        // CAPTION DEFAULT MIGRATION (mobile-only, one-shot): the default caption look changed
        // from the TV yellow-on-semi preset to the official app's white-on-semi. The style index
        // is persisted inside the PlayerData blob even for users who never touched it, so
        // existing installs would keep yellow forever - rewrite the OLD default exactly once; any
        // style picked after this run sticks (the flag prevents re-migrating).
        android.content.SharedPreferences migrations =
                getSharedPreferences("newtube_migrations", MODE_PRIVATE);
        if (!migrations.getBoolean("caption_style_white_default", false)) {
            PlayerData playerData = PlayerData.instance(this);
            java.util.List<com.liskovsoft.smartyoutubetv2.common.exoplayer.other.SubtitleStyle> styles =
                    playerData.getSubtitleStyles();
            int oldYellowSemiIdx = 4;
            int whiteSemiIdx = 1;
            if (styles.size() > oldYellowSemiIdx
                    && playerData.getSubtitleStyle() == styles.get(oldYellowSemiIdx)) {
                playerData.setSubtitleStyle(styles.get(whiteSemiIdx));
            }
            migrations.edit().putBoolean("caption_style_white_default", true).apply();
        }

        // NOTE(buffering): the back-buffer / start-gate / forward-buffer tuning that used to be
        // pushed into the legacy engine here (ExoPlayerInitializer.set*Override) moved into the
        // media3 engine itself - see Media3PlayerInitializer (back 120s, start gate 1000/2500ms).

        // GVS 403 HYBRID (mobile): use repaired ANDROID_VR as the ordinary anonymous VOD fast path.
        // Pixel 9 isolation on shared Wi-Fi proved
        // the failures were not CGNAT, HTTP Range syntax, Cronet/QUIC reuse, or an appended Web PO
        // token: replaying the exact app-minted URL on the host still returned 403, while yt-dlp's
        // same-client URL returned 206. The Android VR root cause was request identity: NewTube
        // reused visitorData extracted from /tv under a Fire TV user agent. Reusing the fresh Web
        // visitor in both /player JSON and X-Goog-Visitor-Id made the same forced VR deep ranges
        // survive and was ~0.98s faster to first frame at the median. Web-family clients immediately
        // follow VR for restricted content and error recovery; platform-token-dependent iOS stays
        // late in the ring.
        VideoInfoService.setPreferNoPotClient(true);

        // WEB-FAMILY RECOVERY (mobile-only): the normal route is VR -> Web family -> other platform
        // clients. On an error-driven reload, start directly at the Web partition and defer the
        // failed winner, preventing a deterministic GVS rejection from selecting VR again. The
        // recovery cursor is one-shot; TV boxes keep their historical ordering.
        VideoInfoService.setPreferAttestedWebFallback(true);

        // 403 PLAYGROUND (debug builds only): force one /player client and disable the fallback
        // ring so client/token behavior is independently measurable on the connected device.
        // Re-read on process start; device-soak.sh sets the property then force-stops the app.
        if (com.liskovsoft.smartyoutubetv2.tv.BuildConfig.DEBUG) {
            String forcedClient = getDebugSystemProperty("debug.arc.player_client");
            // Android's setprop cannot reliably clear a property with an empty value, so the
            // playground uses "none" as its explicit off sentinel.
            if (!forcedClient.isEmpty() && !"none".equalsIgnoreCase(forcedClient)
                    && !VideoInfoService.setDebugForcedClient(forcedClient)) {
                android.util.Log.w("NetPath", "unknown debug.arc.player_client=" + forcedClient);
            }
        }

        // LIVE ROUTING (mobile-only): WEB_EMBED answers live videos HLS-only (no dashManifestUrl
        // -> no LiveDashManifestParser DVR), and on pot-enforcing networks its HLS segments 403
        // instantly regardless of client-side pot placement (manifest /pot/ path param AND
        // per-URL pot= both verified dead on-device). Walk on to a dash-manifest client
        // (ANDROID_VR/TV) for live; VOD keeps the WEB_EMBED-first order above.
        VideoInfoService.setPreferDashManifestForLive(true);

        // /player FAN-OUT TRIM (mobile-only): skip the four TV-app fallback clients (TV_LEGACY,
        // TV_DOWNGRADED, TV_EMBED, TV_SIMPLY) on the getVideoInfo failover ring. They only earn
        // their keep on TV boxes; on a phone they just add up to 4 extra /player round-trips when
        // a hard video walks the ring (AppClient.TV itself stays - it's the auth-capable client).
        // TV never calls this -> TV keeps the full 13-client ring unchanged.
        VideoInfoService.setSkipTvFallbackClients(true);

        // STORYBOARD TRIM (mobile-only): the touch UI has no seek-preview thumbnails yet
        // (MobilePlaybackActivity.loadStoryboard is a stub), but a winning client without a
        // storyboard spec fires a deferred IOS /player per non-live open purely to refetch that
        // spec. Skip the refetch until the UI consumes storyboards; specs that arrive free with
        // an extended-HLS fetch are still applied.
        VideoInfoService.setSkipStoryboardEnrichment(true);

        // FEED FIRST-PAINT (mobile-only): emit the Subscriptions grid after ONE /browse instead
        // of blocking behind the TV pre-combine loop (up to 10 serial continuations gathering
        // 60+ items purely so live streams can float over page-1 items — measured 4.6s of blank
        // skeleton on a roaming link, 4 of the 5 requests spent before anything painted). Page 1
        // keeps its own live-first sort; deeper pages arrive via normal scroll pagination.
        // TV never calls this -> TV keeps the combined-window sort unchanged.
        com.liskovsoft.youtubeapi.browse.v2.BrowseServiceGates.setSkipContinuationPreCombine(true);

        // FEED FIRST-PAINT (mobile-only, round 2): Home's eager row-pad continuations exist to
        // fill short TV shelf rows to MIN_ROW_GROUP_SIZE=5; the phone flattens every row into one
        // grid, so they were ~6 invisible serial /browse continuations (~350KB) racing the first
        // paint. Scroll-end pagination is untouched. And at boot the view used to select the boot
        // section twice (refreshSections tail + onViewInitialized tail), disposing the in-flight
        // load and resubscribing the same observable — skip the redundant refocus. TV never calls
        // these -> TV shelf fill + focus behavior unchanged.
        com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter.setRowPadContinuationsDisabled(true);
        com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter.setSkipRedundantRefocusLoad(true);

        // API COMPRESSION (mobile-only): negotiate brotli for InnerTube JSON (feeds are the big
        // payloads — br is ~15-25% smaller than gzip on them). Upstream flip-flopped `br` four
        // times for TV-box reasons (decompression RAM on 512MB boxes, ByeByeDPI users); neither
        // applies here, and the br decode path (UnzippingInterceptor) has been wired all along.
        // TV never calls this -> TV keeps gzip-only.
        com.liskovsoft.googlecommon.common.helpers.DefaultHeaders.setBrotliEnabled(true);

        // TTFF FIX (mobile-only, click-to-play parallelization): kick the getVideoInfo fetch the instant a
        // video is tapped (PlaybackPresenter.openVideo) so the network round-trip - the single biggest
        // chunk of click-to-play - overlaps the player Activity's bring-up (layout inflation + ExoPlayer
        // construction) instead of running strictly after it. The single-flight below makes the player's
        // own end-of-onCreate fetch reuse this in-flight request rather than issue a second round-trip.
        // Both are off on TV -> TV click-to-play path unchanged.
        PlaybackPresenter.setPrefetchOnOpenEnabled(true);
        YouTubeMediaItemService.setSingleFlightEnabled(true);

        // TTFF FIX (mobile-only, media network): the moment format info arrives - while the player is
        // still inflating / building the manifest - open a throwaway request to the per-video
        // googlevideo host through the SAME Cronet engine ExoPlayer's media path uses, so the
        // DNS + TLS/QUIC handshake is already done when the first real segment request goes out.
        // Covers Home taps (A2 prefetch), related-video taps and queue loads (all format fetches
        // funnel through the same choke point). TV never calls this.
        YouTubeMediaItemService.setPreconnectMediaHost(true);

        // WEB PO-TOKEN WARMUP (mobile-only): initialize WebView/BotGuard at app start so the first
        // web-family /player request does not pay the ~1.5-2.5s cold cost. Tokens remain scoped to
        // Web clients: a BotGuard token cannot attest ANDROID_VR/TV/IOS and must not be appended as
        // a cross-platform media-URL fallback. TV never calls this.
        VideoInfoService.warmUpPoTokenGate();

        // NOTE(perf history): a head-of-stream segment prefetch into the disk cache was tried here
        // and REMOVED - a cold-cache A/B showed no TTFF win (median +339ms WORSE with it on: the
        // starting player bypasses cache spans the prefetcher still holds and re-downloads the same
        // bytes). The screensaver is also absent on mobile entirely: MobileActivity's
        // createScreensaverManager() returns null (no idle dim, no dim overlay in the hierarchy).

        // TTFF FIX (mobile-only, cold-start network): reuse the persisted, extractor-validated app info
        // (playerUrl/clientUrl/visitorData) at cold process start while it's inside the 10h refresh
        // window, instead of re-fetching youtube.com serially inside the FIRST getVideoInfo of every
        // process. Same playerUrl also means the persisted player-JS/nsig extractor cache stays hot, so
        // the two heaviest cold-start fetches skip together. Self-healing (persisted copy is nulled when
        // its playerUrl stops validating). TV never calls this -> TV fetch behavior unchanged.
        AppServiceIntCached.setPersistedAppInfoEnabled(!forceFreshAppInfo);

        // SIGN-IN FIX (mobile-only): on a phone the device-code approval happens in a browser on
        // the SAME device, so the sign-in poll completes while NewTube is backgrounded and the TV
        // flow's follow-up account-picker startActivity is silently blocked by Android 10+
        // background-start rules - the app looked like sign-in did nothing. Skip the picker (the
        // account is auto-selected on token persist) and toast instead; Home refreshes itself via
        // the account-change listener chain. TV keeps the picker.
        YTSignInPresenter.setSilentSuccessEnabled(true);

        // NOTE(audio default): multi-audio (dubbed) videos defaulting to the ORIGINAL track moved
        // into the media3 engine (Media3TrackAdapter.setPreferOriginalAudio, wired by
        // Media3PlayerController) - the legacy TrackSelectorManager flag is gone with that engine.

        // HTTP/2 (mobile-only): unpin the API OkHttp client from HTTP/1.1. The pin dodges a
        // StreamResetException on old TV boxes; on phones it just costs every InnerTube call
        // multiplexing + connection reuse (Home fires several section fetches in parallel = N TLS
        // handshakes on H1 vs one shared H2 connection). Must run before the first client build
        // (earliest network is SessionWarmup at +1200ms). TV never calls this.
        OkHttpManager.setPreferHttp2(true);

        // NOTE(in-stream ABR): "Auto" quality mapping to real adaptive selection is native on
        // media3 (a preset becomes maxVideoSize/frameRate constraints under which
        // AdaptiveTrackSelection adapts - see Media3TrackAdapter); the legacy adaptive-presets
        // flag is gone with the vendored engine.

        // NOTE(disk cache): the on-disk media cache lives in the media3 engine
        // (Media3SourceFactory + Media3PlayerCache, its own directory + stable YouTube cache
        // keys). The legacy MobilePlayerCache/ExoMediaSourceFactory pair was deleted with the
        // vendored exoplayer2 engine.

        // TRANSITIONS (mobile-only): all touch activities are singleTop in ONE task (see the
        // stmobile manifest note) so screen switches are in-task and the Android 12+ cross-task
        // system slide never plays. This flag makes every ViewManager launch reuse the existing
        // instance (REORDER_TO_FRONT) - the job singleInstance used to do on TV.
        ViewManager.setReorderToFrontEnabled(true);

        // WATCH-PAGE LOAD (mobile-only): start the metadata/suggestions fetch at onNewVideo, in
        // parallel with the format fetch + engine load, so the watch page (title/counts/related)
        // fills a whole video-load earlier; and drop the TV-style eager row continuations - the
        // touch related list is one self-paging list, so those fired ~9 doomed requests per video
        // that competed with the stream fetch right at open. TV never calls these -> TV unchanged.
        SuggestionsController.setEagerSuggestionsEnabled(true);
        SuggestionsController.setRowContinuationsDisabled(true);

        // FIRST-RUN FIX (mobile-only): run the one-time YouTube session setup (visitor identity,
        // app info, player-JS de-scrambler parse, client probing - ~15-20s on a fresh install) in
        // the background, instead of inside the user's FIRST video tap. Round 2: the fetch itself
        // now waits for the first feed paint (MobileBrowseActivity kicks SessionWarmup.start) so
        // its /player + JS parse never race the launch-critical /browse chain; init() restores
        // the persisted warm flag and arms a 15s fallback for offline/deep-link launches. See
        // SessionWarmup for the locking/caching guarantees. TV never calls this.
        SessionWarmup.init(this);

        // FEED SNAPSHOTS (mobile-only): the grids repaint their last-known content instantly
        // while the presenters refetch (see FeedCache), and since round 2 the last session's
        // feeds also persist to disk so even a COLD start paints cards immediately (display-only
        // until the refetch lands — a disk-restored Video has no live VideoGroup to paginate).
        // Feeds are per-account, so an account switch must drop every snapshot (memory + disk)
        // or the next repaint shows the previous user's feed.
        FeedCache.init(this);
        MediaServiceManager.instance().addAccountListener(account -> FeedCache.clear());

        // API PRECONNECT (mobile-only): warm the www.youtube.com TLS/H2 session the instant the
        // process starts. RetrofitOkHttpHelper's InnerTube client is built via newBuilder() off
        // OkHttpManager's base client, so they SHARE one ConnectionPool — a completed handshake
        // here is the connection the first /browse rides, taking DNS+TCP+TLS off the cold-start
        // critical path (the googlevideo preconnect above covers only the media host).
        Thread preconnect = new Thread(() -> {
            try {
                OkHttpManager.instance().warmUpConnection("https://www.youtube.com/generate_204");
                android.util.Log.d("NetPath", "preconnected www.youtube.com");
            } catch (Throwable e) {
                android.util.Log.d("NetPath", "www.youtube.com preconnect skipped: " + e.getMessage());
            }
        }, "TubePreconnect");
        preconnect.setDaemon(true);
        preconnect.start();

        ViewManager viewManager = ViewManager.instance(this);

        // Override just the Home + Playback mappings (and the cold-launch root) with
        // the touch screens. Every other View->Activity mapping from
        // MainApplication.setupViewManager() (Search, Channel, AppDialog, SignIn, ...)
        // is left intact for later waves.
        viewManager.register(BrowseView.class, MobileBrowseActivity.class);
        viewManager.register(PlaybackView.class, MobilePlaybackActivity.class, MobileBrowseActivity.class);
        viewManager.register(AppDialogView.class, MobileAppDialogActivity.class, MobileBrowseActivity.class);

        // Wave 4a: list/channel destinations (e.g. tapping a MIX/CHART/playlist on Home, or
        // "Open channel" from a context menu) - re-point the ChannelUploads/Channel mappings at
        // the touch screens, otherwise they'd still resolve to the inherited Leanback Activities.
        viewManager.register(ChannelUploadsView.class, MobileChannelUploadsActivity.class, MobileBrowseActivity.class);
        viewManager.register(ChannelView.class, MobileChannelActivity.class, MobileBrowseActivity.class);

        // Wave 4b: touch Search screen. Re-point the SearchView mapping (parent = Home) at the
        // touch MobileSearchActivity, otherwise SearchPresenter.startSearch() / the Home search
        // icon would still launch the Leanback SearchTagsActivity on a touch phone.
        viewManager.register(SearchView.class, MobileSearchActivity.class, MobileBrowseActivity.class);

        // Wave 5: touch device-code sign-in screen (ARCHITECTURE.md section 7). Re-point the
        // SignInView mapping (parent = Home, mirroring TV's
        // SignInView -> SignInActivity, parent BrowseActivity) at the touch MobileSignInActivity,
        // otherwise Settings -> Accounts -> "Sign in" (which calls YTSignInPresenter.start() ->
        // ViewManager.startView(SignInView.class)) would still launch the Leanback GuidedStep
        // SignInActivity on a touch phone. The auth backend (YTSignInPresenter / SignInService /
        // token storage) is reused unchanged - only the View is re-skinned.
        viewManager.register(SignInView.class, MobileSignInActivity.class, MobileBrowseActivity.class);

        // Parity gaps: re-point the two remaining inherited Leanback screens at touch equivalents.
        //  * WebBrowserView (About / SponsorBlock / DeArrow "open link"): the TV WebBrowserActivity
        //    shows an in-app QR web page; MobileWebBrowserActivity hands the URL to the device browser.
        //  * AddDeviceView (Settings -> Remote control pairing): the TV AddDeviceActivity is a
        //    GuidedStep; MobileAddDeviceActivity is a touch pairing-code screen.
        viewManager.register(WebBrowserView.class, MobileWebBrowserActivity.class, MobileBrowseActivity.class);
        viewManager.register(AddDeviceView.class, MobileAddDeviceActivity.class, MobileBrowseActivity.class);

        viewManager.setRoot(MobileBrowseActivity.class);
    }

    /** Hidden API access is confined to the debuggable 403 playground and degrades to unset. */
    private static String getDebugSystemProperty(String key) {
        try {
            Class<?> properties = Class.forName("android.os.SystemProperties");
            String value = (String) properties.getMethod("get", String.class, String.class)
                    .invoke(null, key, "");
            return value != null ? value.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
