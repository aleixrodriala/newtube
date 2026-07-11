package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.SeekParameters;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.drm.DefaultDrmSessionManager;
import com.google.android.exoplayer2.drm.DrmSessionManager;
import com.google.android.exoplayer2.drm.ExoMediaDrm.KeyRequest;
import com.google.android.exoplayer2.drm.ExoMediaDrm.ProvisionRequest;
import com.google.android.exoplayer2.drm.FrameworkMediaCrypto;
import com.google.android.exoplayer2.drm.MediaDrmCallback;
import com.google.android.exoplayer2.drm.UnsupportedDrmException;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.upstream.BandwidthMeter;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.liskovsoft.sharedutils.helpers.DeviceHelpers;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.ExoMediaSourceFactory;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;

import java.util.UUID;

public class ExoPlayerInitializer {
    private final int mMaxBufferBytes;
    private final PlayerData mPlayerData;
    private final PlayerTweaksData mPlayerTweaksData;
    private VolumeBooster mVolumeBooster;
    private float mVolumeBoost;
    private SimpleExoPlayer mPlayer;
    private static AudioAttributes sAudioAttributes;

    // NEWTUBE(mobile-seek): optional back-buffer widening installed by the touch (stmobile) flavor
    // via setBackBufferOverride() from MobileMainApplication. SmartTube keeps only ~50s behind the
    // playhead (BUFFER_HIGH -> setBackBuffer(50s)), so a backward seek further than that re-buffers
    // and re-downloads. On the real streams here (SABR = HTTP POST) the on-disk media cache can't
    // help (POST bodies aren't cacheable), so widening the *in-memory* back-buffer is the only way
    // to make far backward seeks instant regardless of stream type. When set (>0) this overrides the
    // LoadControl back-buffer for every player created in this process; the target buffer-bytes
    // budget is raised alongside it so a full back-buffer never gates the forward buffer. Left unset
    // (-1) on the TV builds -> createLoadControl() is byte-for-byte unchanged there.
    private static volatile int sBackBufferMsOverride = -1;
    private static volatile int sTargetBufferBytesOverride = -1;

    // NEWTUBE(mobile-ttff): optional start-buffer gate override installed by the touch (stmobile)
    // flavor via setStartBufferOverride() from MobileMainApplication. ExoPlayer will not start (or
    // resume after a rebuffer) rendering until bufferForPlaybackMs (/ ...AfterRebufferMs) of media is
    // buffered. SmartTube's defaults (2500ms / 5000ms) delay the very first frame; lowering them on
    // mobile lets playback start sooner. Left unset (-1) on the TV builds -> createLoadControl() start
    // gate is byte-for-byte unchanged there. The steady-state min/max buffer is NOT touched.
    private static volatile int sBufferForPlaybackMsOverride = -1;
    private static volatile int sBufferForPlaybackAfterRebufferMsOverride = -1;

    /**
     * NEWTUBE(mobile-seek): widen the in-memory back-buffer (and raise the buffer-bytes budget to
     * match) for every {@link SimpleExoPlayer} created afterwards. Call once from the Application.
     * A {@code backBufferMs <= 0} disables the override (TV default). See field docs above.
     *
     * @param backBufferMs      how much already-played media to retain behind the playhead, in ms.
     * @param targetBufferBytes soft cap on total buffered bytes so the enlarged back-buffer does not
     *                          starve forward buffering; ignored when {@code <= 0}.
     */
    public static void setBackBufferOverride(int backBufferMs, int targetBufferBytes) {
        sBackBufferMsOverride = backBufferMs;
        sTargetBufferBytesOverride = targetBufferBytes;
    }

    /**
     * NEWTUBE(mobile-ttff): lower the first-frame / after-rebuffer start gate for every
     * {@link SimpleExoPlayer} created afterwards (touch flavor only). Values {@code <= 0} disable the
     * override (TV default). Only the start gate changes; the steady-state min/max buffer is untouched.
     *
     * @param bufferForPlaybackMs              media to buffer before the FIRST frame renders, in ms.
     * @param bufferForPlaybackAfterRebufferMs media to buffer before resuming after a rebuffer, in ms.
     */
    public static void setStartBufferOverride(int bufferForPlaybackMs, int bufferForPlaybackAfterRebufferMs) {
        sBufferForPlaybackMsOverride = bufferForPlaybackMs;
        sBufferForPlaybackAfterRebufferMsOverride = bufferForPlaybackAfterRebufferMs;
    }

    private static volatile int sMaxForwardBufferMsOverride = -1;

    /**
     * NEWTUBE(mobile-seek): raise the FORWARD buffer ceiling (maxBufferMs) so forward seeks within
     * that window play instantly from memory instead of re-fetching over the network. Only raises -
     * never lowers - the buffer-type preset's own value, and leaves minBufferMs alone (loading still
     * resumes at the preset threshold). Real memory use stays bounded by targetBufferBytes (the
     * RAM-clamped budget set with the back-buffer override). {@code <= 0} disables (TV default).
     */
    public static void setMaxForwardBufferOverride(int maxBufferMs) {
        sMaxForwardBufferMsOverride = maxBufferMs;
    }

    public ExoPlayerInitializer(Context context) {
        mPlayerData = PlayerData.instance(context);
        mPlayerTweaksData = PlayerTweaksData.instance(context);

        long deviceRam = DeviceHelpers.getDeviceRam(context);

        // If ram is too big, bigger then max int value DeviceRam will return a negative number...
        // use 196MB as that can only happens if device has more than 17GB of RAM, so 196 is enough and safe
        // https://github.com/yuliskov/SmartYouTubeTV/issues/532
        mMaxBufferBytes = deviceRam <= 0 ? 196_000_000 : (int)(deviceRam / 18);
    }

    public SimpleExoPlayer createPlayer(Context context, DefaultRenderersFactory renderersFactory, DefaultTrackSelector trackSelector) {
        DefaultLoadControl loadControl = createLoadControl();

        // HDR fix?
        //trackSelector.setParameters(trackSelector.buildUponParameters().setTunnelingAudioSessionId(C.generateAudioSessionIdV21(context)));

        // Old initializer
        // The network data sources report transfers to this exact same meter. Passing it into the
        // player is what turns multi-track "Auto" selections into real throughput-driven ABR.
        SimpleExoPlayer player = ExoPlayerFactory.newSimpleInstance(
                context,
                renderersFactory,
                trackSelector,
                loadControl,
                null,
                ExoMediaSourceFactory.getBandwidthMeter(context));

        // New initializer
        //SimpleExoPlayer player = ExoPlayerFactory.newSimpleInstance(
        //        context, renderersFactory, trackSelector, loadControl,
        //        null, new DummyBandwidthMeter(), new AnalyticsCollector.Factory(), Util.getLooper()
        //);

        //enableAudioFocus(player);

        // Lead to numbered errors
        //player.setRepeatMode(Player.REPEAT_MODE_ONE);

        // Fix still image while audio is playing (happens after format change or exit from sleep)
        //player.setPlayWhenReady(true);

        applyPlaybackFixes(player);

        setupAudioFocus(player);

        setupVolumeBoost(player);

        mPlayer = player;

        return player;
    }

    private static AudioAttributes getAudioAttributes() {
        if (sAudioAttributes == null) {
            sAudioAttributes = new AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.CONTENT_TYPE_MOVIE)
                    .build();
        }

        return sAudioAttributes;
    }

    /**
     * Increase player's min/max buffer size to 60 secs
     * @return load control
     */
    private DefaultLoadControl createLoadControl() {
        DefaultLoadControl.Builder baseBuilder = new DefaultLoadControl.Builder();

        // Default values
        //DefaultLoadControl.DEFAULT_MIN_BUFFER_MS // 15_000
        //DefaultLoadControl.DEFAULT_MAX_BUFFER_MS // 50_000
        //DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS // 2_500
        //DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS // 5_000

        // Default values
        int minBufferMs = 30_000;
        int maxBufferMs = 30_000;
        int bufferForPlaybackMs = 2_500;
        int bufferForPlaybackAfterRebufferMs = 5_000;

        switch (mPlayerData.getVideoBufferType()) {
            case PlayerData.BUFFER_HIGHEST:
                minBufferMs = 50_000;
                maxBufferMs = 100_000;
                // Infinite buffer works awfully on live streams. Constant stuttering.
                //maxBufferMs = 36_000_000; // technical infinity, recommended here a very high number, the max will be based on setTargetBufferBytes() value
                baseBuilder
                        .setTargetBufferBytes(mMaxBufferBytes);
                baseBuilder.setBackBuffer(minBufferMs, true);
                break;
            case PlayerData.BUFFER_HIGH:
                minBufferMs = 50_000;
                maxBufferMs = 50_000;
                baseBuilder.setBackBuffer(minBufferMs, true);
                break;
            case PlayerData.BUFFER_MEDIUM:
                //minBufferMs = 30_000;
                //maxBufferMs = 30_000;
                break;
            case PlayerData.BUFFER_LOW:
                minBufferMs = 5_000; // LIVE fix
                maxBufferMs = 5_000; // LIVE fix
                //bufferForPlaybackMs = 1_000;
                //bufferForPlaybackAfterRebufferMs = 1_000;
                break;
        }

        // NEWTUBE(mobile-ttff): lower ONLY the start gate on the touch flavor so the first frame
        // renders sooner (both stay well under the 30s+ minBuffer, so DefaultLoadControl's
        // buffer-duration assertions hold). No-op on TV (override unset) -> TV start gate unchanged.
        if (sBufferForPlaybackMsOverride > 0) {
            bufferForPlaybackMs = sBufferForPlaybackMsOverride;
        }
        if (sBufferForPlaybackAfterRebufferMsOverride > 0) {
            bufferForPlaybackAfterRebufferMs = sBufferForPlaybackAfterRebufferMsOverride;
        }

        // NEWTUBE(mobile-seek): raise-only forward ceiling; see setMaxForwardBufferOverride.
        if (sMaxForwardBufferMsOverride > 0 && sMaxForwardBufferMsOverride > maxBufferMs) {
            maxBufferMs = sMaxForwardBufferMsOverride;
        }

        baseBuilder
                .setBufferDurationsMs(minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs);

        // NEWTUBE(mobile-seek): apply the touch flavor's back-buffer override LAST so it wins over the
        // buffer-type default above (BUFFER_HIGH sets only 50s). retainBackBufferFromKeyframe=true so a
        // backward seek lands on a retained keyframe. The back-buffer is retained purely by
        // getBackBufferDurationUs() (the player discards to positionUs - backBufferDurationUs every
        // work cycle), independent of maxBufferMs; we raise targetBufferBytes only so the full
        // back-buffer never trips the size gate in shouldContinueLoading and starves forward loading.
        // No-op on TV (override unset), so TV LoadControl is unchanged.
        if (sBackBufferMsOverride > 0) {
            baseBuilder.setBackBuffer(sBackBufferMsOverride, true);
            if (sTargetBufferBytesOverride > 0) {
                // NEWTUBE(mobile-mem): never exceed the device-RAM budget (deviceRam/18). On a
                // low-RAM phone the override (e.g. 192MB) could OOM; clamp so the enlarged
                // back-buffer stays within what the device can afford. min(override, mMaxBufferBytes).
                baseBuilder.setTargetBufferBytes(Math.min(sTargetBufferBytesOverride, mMaxBufferBytes));
            }
        }

        // Decrease buffer size?
        //baseBuilder.setAllocator(new DefaultAllocator(true, 16 * 1024));

        return baseBuilder.createDefaultLoadControl();
    }

    private void setupVolumeBoost(SimpleExoPlayer player) {
        // 5.1 audio cannot be boosted (format isn't supported error)
        // also, other 2.0 tracks in 5.1 group is already too loud. so cancel them too.
        float volume = mPlayerTweaksData.isPlayerAutoVolumeEnabled() ? mPlayerData.getPlayerVolume() * 2.0f : mPlayerData.getPlayerVolume();
        if (volume > 1f && Build.VERSION.SDK_INT >= 19) {
            mVolumeBooster = new VolumeBooster(true, volume, player);
            player.addAudioListener(mVolumeBooster);
        }
        mVolumeBoost = Math.max(volume, 1f);
    }

    /**
     * Manage audio focus. E.g. use Spotify when audio is disabled.
     */
    private void setupAudioFocus(SimpleExoPlayer player) {
        if (player != null && mPlayerTweaksData.isAudioFocusEnabled()) {
            try {
                player.setAudioAttributes(getAudioAttributes(), true);
            } catch (SecurityException e) { // uid 10390 not allowed to perform TAKE_AUDIO_FOCUS
                e.printStackTrace();
            }
        }
    }

    private void applyPlaybackFixes(SimpleExoPlayer player) {
        // Try to fix decoder error on Nvidia Shield 2019.
        // Init resources as early as possible.
        //player.setForegroundMode(true);
        // NOTE: Avoid using seekParameters. ContentBlock hangs because of constant skipping to the segment start.
        // ContentBlock hangs on the last segment: https://www.youtube.com/watch?v=pYymRbfjKv8

        // Fix seeking on TextureView (some devices only)
        if (mPlayerTweaksData.isTextureViewEnabled()) {
            // Also, live stream (dash) seeking fix
            player.setSeekParameters(SeekParameters.CLOSEST_SYNC);
        }
    }

    public float getVolumeBoost() {
        return mVolumeBoost;
    }

    public void release() {
        if (mVolumeBooster != null) {
            mVolumeBooster.release();
        }

        if (mPlayer != null && mVolumeBooster != null) {
            mPlayer.removeAudioListener(mVolumeBooster);
        }

        mVolumeBooster = null;
        mPlayer = null;
    }

    private DrmSessionManager<FrameworkMediaCrypto> createDrmManager() {
        try {
            return DefaultDrmSessionManager.newWidevineInstance(new MediaDrmCallback() {
                @Override
                public byte[] executeProvisionRequest(UUID uuid, ProvisionRequest request) {
                    return new byte[0];
                }

                @Override
                public byte[] executeKeyRequest(UUID uuid, KeyRequest request) {
                    return new byte[0];
                }
            }, null);
        } catch (UnsupportedDrmException e) {
            e.printStackTrace();
        }

        return null;
    }

    private static final class DummyBandwidthMeter implements BandwidthMeter {
        @Override
        public long getBitrateEstimate() {
            return 0;
        }

        @Nullable
        @Override
        public TransferListener getTransferListener() {
            return null;
        }

        @Override
        public void addEventListener(Handler eventHandler, EventListener eventListener) {
            // Do nothing.
        }

        @Override
        public void removeEventListener(EventListener eventListener) {
            // Do nothing.
        }
    }
}
