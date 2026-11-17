package com.newtube.mobile.auto;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener.PlayerEventListener;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.newtube.mobile.player.Media3PlayerController;
import com.newtube.mobile.player.Media3PlayerInitializer;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * NEWTUBE(android-auto): the player behind the Android Auto screens. It owns its own media3 player, built
 * by the same {@link Media3PlayerInitializer} / {@link Media3PlayerController} as the phone player,
 * so the car gets the phone's network stack (Cronet, SABR, DASH, live) without the phone's activity.
 *
 * <p>Video is drawn only while {@link #setVideoAllowed} says the car is parked; otherwise the video
 * track is switched off (no video data is fetched) and only the sound plays.
 */
public final class AutoPlayer {
    public interface Listener {
        void onAutoPlayerChanged();
    }

    private static AutoPlayer sInstance;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final List<Listener> mListeners = new CopyOnWriteArrayList<>();
    private final List<Video> mQueue = new ArrayList<>();
    private int mIndex = -1;

    @Nullable private Media3PlayerInitializer mInitializer;
    @Nullable private Media3PlayerController mController;
    @Nullable private ExoPlayer mPlayer;
    @Nullable private Disposable mLoad;
    @Nullable private MediaSessionCompat mSession;

    private boolean mVideoAllowed;
    private boolean mAudioOnlyByUser;
    private String mStatus = "";

    private AutoPlayer(Context context) {
        mContext = context.getApplicationContext();
    }

    public static AutoPlayer get(Context context) {
        if (sInstance == null) {
            sInstance = new AutoPlayer(context);
        }
        return sInstance;
    }

    // ------------------------------------------------------------------ queue

    public void play(List<Video> videos, int index) {
        mQueue.clear();
        for (Video video : videos) {
            if (video != null && video.videoId != null) {
                mQueue.add(video);
            }
        }
        Video picked = index >= 0 && index < videos.size() ? videos.get(index) : null;
        mIndex = picked != null ? Math.max(0, mQueue.indexOf(picked)) : 0;
        if (mQueue.isEmpty()) {
            return;
        }
        load(mQueue.get(mIndex));
    }

    public boolean hasNext() {
        return mIndex >= 0 && mIndex + 1 < mQueue.size();
    }

    public boolean hasPrevious() {
        return mIndex > 0;
    }

    public void next() {
        if (hasNext()) {
            mIndex++;
            load(mQueue.get(mIndex));
        }
    }

    public void previous() {
        if (mPlayer != null && mPlayer.getCurrentPosition() > 5_000) {
            mPlayer.seekTo(0);
            return;
        }
        if (hasPrevious()) {
            mIndex--;
            load(mQueue.get(mIndex));
        }
    }

    @Nullable
    public Video getVideo() {
        return mIndex >= 0 && mIndex < mQueue.size() ? mQueue.get(mIndex) : null;
    }

    public boolean isActive() {
        return getVideo() != null && mPlayer != null;
    }

    // ------------------------------------------------------------------ transport

    public boolean isPlaying() {
        return mPlayer != null && mPlayer.getPlayWhenReady();
    }

    public void togglePlay() {
        if (mPlayer == null) return;
        mPlayer.setPlayWhenReady(!mPlayer.getPlayWhenReady());
        changed();
    }

    public void seekBy(long deltaMs) {
        if (mPlayer == null) return;
        long duration = mPlayer.getDuration();
        long target = Math.max(0, mPlayer.getCurrentPosition() + deltaMs);
        if (duration > 0) target = Math.min(target, duration - 1_000);
        mPlayer.seekTo(target);
    }

    @Nullable
    public ExoPlayer getExoPlayer() {
        return mPlayer;
    }

    public String getStatus() {
        return mStatus;
    }

    // ------------------------------------------------------------------ video gate

    /** Parked = true. Moving or unknown = false: sound only. */
    public void setVideoAllowed(boolean allowed) {
        if (mVideoAllowed == allowed) return;
        mVideoAllowed = allowed;
        applyVideoTrack();
        changed();
    }

    public boolean isVideoAllowed() {
        return mVideoAllowed;
    }

    public void setAudioOnlyByUser(boolean audioOnly) {
        mAudioOnlyByUser = audioOnly;
        applyVideoTrack();
        changed();
    }

    public boolean isAudioOnlyByUser() {
        return mAudioOnlyByUser;
    }

    /** What the car screen should do right now. */
    public boolean shouldShowVideo() {
        return mVideoAllowed && !mAudioOnlyByUser && isActive();
    }

    private void applyVideoTrack() {
        if (mController != null) {
            mController.setVideoTrackDisabled(!(mVideoAllowed && !mAudioOnlyByUser));
        }
    }

    // ------------------------------------------------------------------ listeners

    public void addListener(Listener listener) {
        mListeners.add(listener);
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    private void changed() {
        mHandler.post(() -> {
            updateSession();
            for (Listener listener : mListeners) {
                listener.onAutoPlayerChanged();
            }
        });
    }

    // ------------------------------------------------------------------ engine

    private void ensurePlayer() {
        if (mPlayer != null) return;
        mInitializer = new Media3PlayerInitializer(mContext);
        mController = new Media3PlayerController(mContext, createEngineListener());
        DefaultTrackSelector trackSelector = mInitializer.createTrackSelector();
        mController.setTrackSelector(trackSelector);
        mPlayer = mInitializer.createPlayer(trackSelector,
                mController.getMediaSourceFactory().getBandwidthMeter());
        mPlayer.setPlayWhenReady(true);
        mController.setPlayer(mPlayer);
        mPlayer.addListener(new Player.Listener() {
            @Override
            public void onEvents(Player player, Player.Events events) {
                changed();
            }
        });
        ensureSession();
    }

    private void load(Video video) {
        ensurePlayer();
        if (mLoad != null) mLoad.dispose();
        mStatus = "A carregar…";
        mController.resetPlayerState();
        mController.setVideo(video);
        applyVideoTrack();
        changed();
        mLoad = YouTubeServiceManager.instance().getMediaItemService()
                .getFormatInfoObserve(video.videoId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(formatInfo -> open(video, formatInfo), error -> {
                    mStatus = "Não foi possível abrir: " + error.getMessage();
                    changed();
                });
    }

    private void open(Video video, MediaItemFormatInfo info) {
        if (video != getVideo() || mController == null) return;
        if (info == null || info.isUnplayable() || !info.containsMedia()) {
            String reason = info != null ? info.getPlayabilityReason() : null;
            mStatus = reason != null && !reason.isEmpty() ? reason : "Vídeo indisponível";
            changed();
            // Skip on only if the driver has not picked something else in the meantime.
            if (hasNext()) mHandler.postDelayed(() -> { if (getVideo() == video) next(); }, 4_000);
            return;
        }
        mStatus = "";
        video.isLive = info.isLive();
        if (info.isLive() && info.containsDashUrl()) {
            mController.openDashUrl(info.getDashManifestUrl());
        } else if (info.isLive() && info.containsHlsUrl()) {
            mController.openHlsUrl(info.getHlsManifestUrl());
        } else if (info.containsDashFormats()) {
            mController.openDash(info);
        } else if (info.containsSabrFormats()) {
            mController.openSabr(info);
        } else if (info.isHlsVodSelected()) {
            mController.openHlsVod(info);
        } else if (info.containsUrlFormats()) {
            mController.openProgressive(info);
        } else if (info.containsHlsUrl()) {
            mController.openHlsUrl(info.getHlsManifestUrl());
        } else if (info.containsDashUrl()) {
            mController.openDashUrl(info.getDashManifestUrl());
        } else {
            mStatus = "Formato não suportado";
        }
        mPlayer.setPlayWhenReady(true);
        applyVideoTrack();
        changed();
    }

    /**
     * The controller reports through the phone player's wide listener interface; only a few
     * events matter here, so a proxy answers the rest with defaults.
     */
    private PlayerEventListener createEngineListener() {
        return (PlayerEventListener) Proxy.newProxyInstance(
                PlayerEventListener.class.getClassLoader(),
                new Class<?>[] {PlayerEventListener.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "onPlayEnd":
                            mHandler.post(() -> {
                                if (hasNext()) next();
                                else changed();
                            });
                            break;
                        case "onEngineError":
                            Throwable error = args != null && args.length > 2 ? (Throwable) args[2] : null;
                            mStatus = "Erro de reprodução" + (error != null && error.getMessage() != null
                                    ? ": " + error.getMessage() : "");
                            changed();
                            break;
                        case "onVideoLoaded":
                            mHandler.post(this::applyVideoTrack);
                            changed();
                            break;
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return args != null && proxy == args[0];
                        case "toString":
                            return "AutoPlayerListener";
                        default:
                            break;
                    }
                    return defaultValue(method.getReturnType());
                });
    }

    @Nullable
    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        return 0d;
    }

    // ------------------------------------------------------------------ media session (steering wheel)

    /** Feeds the media browser listed, by id, so a tap in the car's music screen can play them. */
    private final java.util.Map<String, List<Video>> mCatalog = new java.util.HashMap<>();

    public void putCatalog(String feedId, List<Video> videos) {
        mCatalog.put(feedId, new ArrayList<>(videos));
    }

    /** Media id of a browser item: {@code feedId|index}. */
    public static String mediaId(String feedId, int index) {
        return feedId + "|" + index;
    }

    /**
     * The media session, created once and kept for the life of the process: the car's music screen
     * (AutoMediaBrowserService) holds its token even while no car screen is open.
     */
    public MediaSessionCompat.Token ensureSession() {
        if (mSession == null) createSession();
        return mSession.getSessionToken();
    }

    private void createSession() {
        mSession = new MediaSessionCompat(mContext, "NewTubeAuto");
        mSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { if (mPlayer != null) mPlayer.setPlayWhenReady(true); }
            @Override public void onPause() { if (mPlayer != null) mPlayer.setPlayWhenReady(false); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onSeekTo(long pos) { if (mPlayer != null) mPlayer.seekTo(pos); }
            @Override public void onFastForward() { seekBy(10_000); }
            @Override public void onRewind() { seekBy(-10_000); }
            @Override public void onStop() { release(); }

            @Override
            public void onPlayFromMediaId(String mediaId, android.os.Bundle extras) {
                if (mediaId == null) return;
                int bar = mediaId.lastIndexOf('|');
                if (bar < 0) return;
                List<Video> feed = mCatalog.get(mediaId.substring(0, bar));
                if (feed == null) return;
                try {
                    play(feed, Integer.parseInt(mediaId.substring(bar + 1)));
                } catch (NumberFormatException ignored) {
                }
            }

            @Override
            public void onPlayFromSearch(String query, android.os.Bundle extras) {
                playSearch(query);
            }
        });
        mSession.setActive(true);
    }

    /** Voice: "play X on NewTube" - the first search result, and the rest as the queue. */
    public void playSearch(String query) {
        if (query == null || query.trim().isEmpty()) {
            if (mPlayer != null) mPlayer.setPlayWhenReady(true);
            return;
        }
        mStatus = "A procurar…";
        changed();
        Schedulers.io().scheduleDirect(() -> {
            List<Video> found = AutoFeeds.load(AutoFeeds.SEARCH, query.trim());
            mHandler.post(() -> {
                if (found.isEmpty()) {
                    mStatus = "Sem resultados";
                    changed();
                } else {
                    play(found, 0);
                }
            });
        });
    }

    private void updateSession() {
        if (mSession == null || mPlayer == null) return;
        Video video = getVideo();
        int state;
        switch (mPlayer.getPlaybackState()) {
            case Player.STATE_BUFFERING: state = PlaybackStateCompat.STATE_BUFFERING; break;
            case Player.STATE_ENDED: state = PlaybackStateCompat.STATE_STOPPED; break;
            case Player.STATE_IDLE: state = PlaybackStateCompat.STATE_NONE; break;
            default: state = mPlayer.getPlayWhenReady()
                    ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
        }
        long actions = PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SEEK_TO
                | PlaybackStateCompat.ACTION_FAST_FORWARD | PlaybackStateCompat.ACTION_REWIND
                | PlaybackStateCompat.ACTION_STOP | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID | PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH;
        if (hasNext()) actions |= PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
        mSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, mPlayer.getCurrentPosition(), mPlayer.getPlaybackParameters().speed)
                .build());
        if (video != null) {
            long duration = mPlayer.getDuration();
            mSession.setMetadata(new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, video.title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, video.getAuthor())
                    .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, video.getCardImageUrl())
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration > 0 ? duration : -1)
                    .build());
        }
    }

    // ------------------------------------------------------------------ teardown

    /** The car disconnected: stop the sound and free the player. */
    public void release() {
        mHandler.removeCallbacksAndMessages(null);
        if (mLoad != null) mLoad.dispose();
        mLoad = null;
        if (mController != null) mController.release(); // also stops and releases mPlayer
        else if (mPlayer != null) mPlayer.release();
        mController = null;
        mPlayer = null;
        mInitializer = null;
        if (mSession != null) {
            // The session stays (the music screen holds its token); it only reports "stopped".
            mSession.setPlaybackState(new PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                            | PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH)
                    .setState(PlaybackStateCompat.STATE_STOPPED, 0, 1f)
                    .build());
        }
        mQueue.clear();
        mIndex = -1;
        mStatus = "";
        for (Listener listener : mListeners) {
            listener.onAutoPlayerChanged();
        }
    }
}
