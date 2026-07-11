package com.newtube.mobile;

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
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorManager;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.ExoPlayerInitializer;
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

    /**
     * SEEK-BACK FIX: how much already-played media to keep resident behind the playhead. 2 minutes
     * covers the "minute 10 -> minute 8" (~120s) backward-seek repro, while staying bounded (see byte
     * budget below) so memory does not run away on a phone. Reduced from 180s -> 120s (~33% less
     * back-buffer memory) since the repro only needs ~2 min behind the playhead.
     */
    private static final int BACK_BUFFER_MS = 120_000; // 2 min

    /**
     * TTFF FIX: lower the first-frame start gate on the touch flavor. ExoPlayer won't render the first
     * frame until this much media is buffered; SmartTube's default is 2500ms. 1000ms lets playback
     * start sooner. The after-rebuffer gate is lowered to 2500ms (default 5000ms) too. Steady-state
     * min/max buffering is unchanged. TV never calls this -> TV start gate unchanged.
     */
    private static final int START_BUFFER_MS = 1_000;
    private static final int START_BUFFER_AFTER_REBUFFER_MS = 2_500;

    /**
     * SEEK FIX: forward-buffer ceiling. The default BUFFER_MEDIUM preset keeps only 30s ahead, so any
     * forward seek beyond 30s re-fetches over the network. 75s makes typical "skip ahead" jumps play
     * from memory. Real memory stays bounded by the RAM-clamped byte budget above; at 1080p this is
     * roughly 45-55MB of forward media, well within it.
     */
    private static final int MAX_FORWARD_BUFFER_MS = 75_000;

    /**
     * SEEK-BACK FIX: soft cap on total buffered bytes (back + forward) so the enlarged back-buffer
     * never trips the LoadControl size gate and starves forward buffering. This is a threshold, not a
     * pre-allocation; the real memory bound is (back 180s + forward 50s) worth of media. A few
     * hundred MB is acceptable on a phone with largeHeap; this stays comfortably under that.
     */
    private static final int TARGET_BUFFER_BYTES = 192 * 1024 * 1024; // 192 MB

    @Override
    public void onCreate() {
        // TTFF FIX (mobile-only, biggest click-to-play win): cap the DEFAULT video quality at 1080p
        // instead of the fixed high rung (~4K/FHD). The first media segment + decode is then <=1080p
        // (a 4K VP9 first frame dominates click-to-play time) and never 1440p/2160p, and phones only
        // display ~1080p anyway. In SmartTube the default is a *preset* whose resolution is a strict
        // ceiling inside TrackSelectorManager.findBestMatch (the real selection path), so a 1080p preset
        // reliably caps selection on every device - unlike a DefaultTrackSelector maxVideoSize param,
        // which RestoreTrackSelector.selectVideoTrack bypasses for video. Set BEFORE super.onCreate() so
        // it's in place before PlayerData first restores state. Only changes the DEFAULT (unset) value -
        // a resolution the user explicitly picks is still persisted and honored. TV never calls this ->
        // TV default unchanged.
        PlayerData.setDefaultVideoFormatMax1080(true);

        // Keep ALL existing TV init: Conscrypt, GlobalPreferences, multidex, the
        // global exception handler and every other View->Activity mapping.
        super.onCreate();

        // SEEK-BACK FIX (primary): widen the in-memory back-buffer for the touch player.
        // The real streams here are SABR (HTTP POST bodies), which the on-disk cache below cannot
        // touch, so the ONLY thing retained behind the playhead is ExoPlayer's in-memory back-buffer.
        // SmartTube's BUFFER_HIGH keeps just ~50s, so seeking back further re-buffers/re-downloads.
        // Retain 3 minutes instead (retainBackBufferFromKeyframe=true), which makes backward seeks
        // within that window instant regardless of stream type. The back-buffer is retained purely by
        // the LoadControl's back-buffer duration (independent of maxBufferMs), so memory stays bounded
        // at roughly (back 180s + forward 50s) worth of media; the raised byte budget only keeps the
        // full back-buffer from starving forward buffering. TV builds never call this -> TV unchanged.
        ExoPlayerInitializer.setBackBufferOverride(BACK_BUFFER_MS, TARGET_BUFFER_BYTES);

        // TTFF FIX (mobile-only): lower the first-frame / after-rebuffer start gate so playback starts
        // sooner. Steady-state buffering is untouched. TV never calls this -> TV start gate unchanged.
        ExoPlayerInitializer.setStartBufferOverride(START_BUFFER_MS, START_BUFFER_AFTER_REBUFFER_MS);

        // SEEK FIX (mobile-only): keep 75s buffered AHEAD (default preset keeps 30s) so forward seeks
        // within that window play from memory instead of re-fetching. TV never calls this.
        ExoPlayerInitializer.setMaxForwardBufferOverride(MAX_FORWARD_BUFFER_MS);

        // TTFF FIX (mobile-only, biggest cold-start win): make getVideoInfo try a no-PO-token/no-cipher
        // client (ANDROID_VR, then ANDROID_REEL) BEFORE WEB_EMBED. WEB_EMBED (the default first client)
        // requires a synchronous PO token + signature deciphering (~2.7s of self-inflicted latency on
        // every cold start); the fast clients skip both. WEB_EMBED and the rest of the client list
        // remain as fallbacks (firstInfoWith falls through), so restricted / age-gated videos still
        // play. The winning fast client is persisted so subsequent cold starts skip straight to it.
        // TV never calls this -> TV keeps the WEB_EMBED-first order unchanged.
        VideoInfoService.setPreferNoPotClient(true);

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
        AppServiceIntCached.setPersistedAppInfoEnabled(true);

        // SIGN-IN FIX (mobile-only): on a phone the device-code approval happens in a browser on
        // the SAME device, so the sign-in poll completes while NewTube is backgrounded and the TV
        // flow's follow-up account-picker startActivity is silently blocked by Android 10+
        // background-start rules - the app looked like sign-in did nothing. Skip the picker (the
        // account is auto-selected on token persist) and toast instead; Home refreshes itself via
        // the account-change listener chain. TV keeps the picker.
        YTSignInPresenter.setSilentSuccessEnabled(true);

        // AUDIO DEFAULT (mobile-only): multi-audio (dubbed) videos default to the ORIGINAL track,
        // not the device-locale dub (YouTube's own default). The gate only acts while the user has
        // never explicitly chosen an audio language; any explicit pick (player audio sheet or the
        // settings audio-language dialog) takes over from then on. TV never calls this.
        TrackSelectorManager.setPreferOriginalAudioDefault(true);

        // HTTP/2 (mobile-only): unpin the API OkHttp client from HTTP/1.1. The pin dodges a
        // StreamResetException on old TV boxes; on phones it just costs every InnerTube call
        // multiplexing + connection reuse (Home fires several section fetches in parallel = N TLS
        // handshakes on H1 vs one shared H2 connection). Must run before the first client build
        // (earliest network is SessionWarmup at +1200ms). TV never calls this.
        OkHttpManager.setPreferHttp2(true);

        // IN-STREAM ABR (mobile-only): while quality is "Auto" (a preset ceiling, the default),
        // hand ExoPlayer the whole rung ladder under the ceiling instead of one fixed track, so an
        // AdaptiveTrackSelection down-switches on bandwidth dips instead of stalling. An explicit
        // quality pick still locks that exact track. TV never calls this -> "quality sticks" kept.
        TrackSelectorManager.setAdaptiveVideoPresets(true);

        // SEEK-BACK FIX (secondary, defense-in-depth): the on-disk media cache moved into the
        // media3 engine (Media3SourceFactory + Media3PlayerCache, its own directory + stable
        // YouTube cache keys). The legacy ExoMediaSourceFactory.setMediaCache(MobilePlayerCache...)
        // registration is gone on purpose: it would eagerly create a second 256MB SimpleCache for
        // the now-unused legacy engine. MobilePlayerCache stays in the tree for a potential legacy
        // fallback build.

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
        // the background at app start, while the user is still browsing Home, instead of inside
        // their FIRST video tap. See SessionWarmup for the locking/caching guarantees. TV never
        // calls this -> TV startup unchanged.
        SessionWarmup.start(this);

        // FEED SNAPSHOTS (mobile-only): the grids repaint their last-known content instantly
        // while the presenters refetch (see FeedCache). Feeds are per-account, so an account
        // switch must drop every snapshot or the next repaint shows the previous user's feed.
        MediaServiceManager.instance().addAccountListener(account -> FeedCache.clear());

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
}
