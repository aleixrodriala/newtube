package com.newtube.mobile.player;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;

import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;

import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * NEWTUBE(open-phases): release-build NetPath milestones for the part of an open that used to be
 * one opaque number - {@code prepare +X} to {@code first-frame +Y} (median 198 ms Wi-Fi / 379 ms
 * LTE on the Pixel card taps of 2026-09-25, and 342-404 ms on cold share links). Per-chunk lines
 * exist, but only in debug builds, which run uncompiled on a device and so cannot be timed.
 *
 * <p>At most ~8 lines per open, each the FIRST of its kind after {@link #onPrepare()}:</p>
 * <ul>
 *   <li>{@code media-load init +X track=video id= h=} - the playback thread finished prepare +
 *       track selection and asked for the first initialization/index range (the format is the
 *       start rung ABR picked);</li>
 *   <li>{@code media-init done +X track=video|audio loadMs= bytes=} - that range arrived;</li>
 *   <li>{@code media-load chunk +X track=video} / {@code media-chunk done +X track=video loadMs=}
 *       - the first media segment of the video track;</li>
 *   <li>{@code decoder init +X type=video|audio name= initMs=} - a codec was created (media3's own
 *       create+configure+start measurement), or {@code decoder reuse +X type= result=} - the
 *       codec kept from the previous open served the new stream (see the foreground mode in
 *       {@link Media3PlayerInitializer}); a refused reuse shows up as the init line instead;</li>
 *   <li>{@code ready +X bufferedMs=} - the start gate opened (first STATE_READY), with the buffer
 *       media3 held at that moment;</li>
 *   <li>{@code renderer-ready +X type=video|audio} - the first time each renderer allowed playback:
 *       whichever comes last is what READY waited for;</li>
 *   <li>audio, for the resume/READY analysis (r3d: READY 0.8-1.1 s after the first frame):
 *       {@code media-load chunk +X track=audio start=}, {@code media-chunk done +X track=audio ...
 *       start= src=cache|net} (first two each) and {@code media-load canceled +X track= start=}
 *       (first two canceled media loads). {@code src} is {@code net} when the load carried HTTP
 *       response headers (CacheDataSource reports none for bytes it served itself).</li>
 * </ul>
 *
 * <p>Timing is {@link NetPath#elapsedMs()}, i.e. the same {@code +X} clock as the tap/open/info/
 * prepare/first-frame milestones. Events that arrive before the first {@link #onPrepare()} or
 * belong to no open are ignored. Callbacks run on the application thread; everything is O(1).</p>
 */
final class OpenPhaseLog implements AnalyticsListener {
    private final Consumer<String> mSink;
    private final Supplier<String> mContext;
    private final LongSupplier mElapsed;

    private boolean mArmed;
    private boolean mFirstLoadLogged;
    private boolean mVideoInitDone;
    private boolean mAudioInitDone;
    private boolean mVideoChunkStarted;
    private boolean mVideoChunkDone;
    private boolean mVideoDecoderLogged;
    private boolean mAudioDecoderLogged;
    private boolean mReadyLogged;
    private int mAudioChunkStarts;
    private int mAudioChunkDones;
    private int mCanceledLogged;
    private boolean mVideoRendererReady;
    private boolean mAudioRendererReady;
    /** Per-open line budgets: enough for "first chunk" plus "the one after a resume snap". */
    private static final int MAX_AUDIO_CHUNK_LINES = 2;
    private static final int MAX_CANCELED_LINES = 2;

    OpenPhaseLog() {
        this(NetPath::log, NetPath::context, NetPath::elapsedMs);
    }

    OpenPhaseLog(Consumer<String> sink, Supplier<String> context, LongSupplier elapsed) {
        mSink = sink;
        mContext = context;
        mElapsed = elapsed;
    }

    /** A new source was handed to the player: every "first" below belongs to this open. */
    void onPrepare() {
        mArmed = true;
        mFirstLoadLogged = false;
        mVideoInitDone = false;
        mAudioInitDone = false;
        mVideoChunkStarted = false;
        mVideoChunkDone = false;
        mVideoDecoderLogged = false;
        mAudioDecoderLogged = false;
        mReadyLogged = false;
        mAudioChunkStarts = 0;
        mAudioChunkDones = 0;
        mCanceledLogged = 0;
        mVideoRendererReady = false;
        mAudioRendererReady = false;
    }

    @Override
    public void onLoadStarted(EventTime eventTime, LoadEventInfo loadEventInfo,
            MediaLoadData mediaLoadData, int retryCount) {
        if (!mArmed) {
            return;
        }
        boolean videoChunk = mediaLoadData.trackType == C.TRACK_TYPE_VIDEO
                && mediaLoadData.dataType == C.DATA_TYPE_MEDIA;
        if (!mFirstLoadLogged && isMediaTrack(mediaLoadData.trackType)
                && (mediaLoadData.dataType == C.DATA_TYPE_MEDIA_INITIALIZATION
                        || mediaLoadData.dataType == C.DATA_TYPE_MEDIA)) {
            mFirstLoadLogged = true;
            // A source without separate init requests (progressive, SABR) starts with media: that
            // one line is then also the first video chunk.
            mVideoChunkStarted |= videoChunk;
            emit("media-load " + kind(mediaLoadData.dataType) + " +" + mElapsed.getAsLong()
                    + " track=" + trackName(mediaLoadData.trackType)
                    + formatTag(mediaLoadData.trackFormat) + startTag(mediaLoadData));
            if (mediaLoadData.trackType == C.TRACK_TYPE_AUDIO && mediaLoadData.dataType == C.DATA_TYPE_MEDIA) {
                mAudioChunkStarts++;
            }
            return;
        }
        if (!mVideoChunkStarted && videoChunk) {
            mVideoChunkStarted = true;
            emit("media-load chunk +" + mElapsed.getAsLong() + " track=video"
                    + formatTag(mediaLoadData.trackFormat) + startTag(mediaLoadData));
        } else if (mediaLoadData.trackType == C.TRACK_TYPE_AUDIO
                && mediaLoadData.dataType == C.DATA_TYPE_MEDIA
                && mAudioChunkStarts < MAX_AUDIO_CHUNK_LINES) {
            mAudioChunkStarts++;
            emit("media-load chunk +" + mElapsed.getAsLong() + " track=audio"
                    + formatTag(mediaLoadData.trackFormat) + startTag(mediaLoadData));
        }
    }

    @Override
    public void onLoadCanceled(EventTime eventTime, LoadEventInfo loadEventInfo,
            MediaLoadData mediaLoadData) {
        if (!mArmed || mReadyLogged || mCanceledLogged >= MAX_CANCELED_LINES
                || mediaLoadData.dataType != C.DATA_TYPE_MEDIA || !isMediaTrack(mediaLoadData.trackType)) {
            return; // after READY a cancel is ordinary ABR/seek business, not startup
        }
        mCanceledLogged++;
        emit("media-load canceled +" + mElapsed.getAsLong() + " track="
                + trackName(mediaLoadData.trackType) + startTag(mediaLoadData)
                + " loadMs=" + loadEventInfo.loadDurationMs + " bytes=" + loadEventInfo.bytesLoaded);
    }

    @Override
    public void onRendererReadyChanged(EventTime eventTime, int rendererIndex, int rendererTrackType,
            boolean isRendererReady) {
        if (!mArmed || !isRendererReady) {
            return;
        }
        if (rendererTrackType == C.TRACK_TYPE_VIDEO && !mVideoRendererReady) {
            mVideoRendererReady = true;
            emit("renderer-ready +" + mElapsed.getAsLong() + " type=video");
        } else if (rendererTrackType == C.TRACK_TYPE_AUDIO && !mAudioRendererReady) {
            mAudioRendererReady = true;
            emit("renderer-ready +" + mElapsed.getAsLong() + " type=audio");
        }
    }

    @Override
    public void onLoadCompleted(EventTime eventTime, LoadEventInfo loadEventInfo,
            MediaLoadData mediaLoadData) {
        if (!mArmed) {
            return;
        }
        if (mediaLoadData.dataType == C.DATA_TYPE_MEDIA_INITIALIZATION) {
            if (mediaLoadData.trackType == C.TRACK_TYPE_VIDEO && !mVideoInitDone) {
                mVideoInitDone = true;
                emitLoadDone("media-init", "video", loadEventInfo);
            } else if (mediaLoadData.trackType == C.TRACK_TYPE_AUDIO && !mAudioInitDone) {
                mAudioInitDone = true;
                emitLoadDone("media-init", "audio", loadEventInfo);
            }
        } else if (mediaLoadData.dataType == C.DATA_TYPE_MEDIA
                && mediaLoadData.trackType == C.TRACK_TYPE_VIDEO && !mVideoChunkDone) {
            mVideoChunkDone = true;
            emit("media-chunk done +" + mElapsed.getAsLong() + " track=video loadMs="
                    + loadEventInfo.loadDurationMs + " bytes=" + loadEventInfo.bytesLoaded
                    + startTag(mediaLoadData) + srcTag(loadEventInfo));
        } else if (mediaLoadData.dataType == C.DATA_TYPE_MEDIA
                && mediaLoadData.trackType == C.TRACK_TYPE_AUDIO
                && mAudioChunkDones < MAX_AUDIO_CHUNK_LINES) {
            mAudioChunkDones++;
            emit("media-chunk done +" + mElapsed.getAsLong() + " track=audio loadMs="
                    + loadEventInfo.loadDurationMs + " bytes=" + loadEventInfo.bytesLoaded
                    + startTag(mediaLoadData) + srcTag(loadEventInfo));
        }
    }

    @Override
    public void onVideoDecoderInitialized(EventTime eventTime, String decoderName,
            long initializedTimestampMs, long initializationDurationMs) {
        if (!mArmed || mVideoDecoderLogged) {
            return;
        }
        mVideoDecoderLogged = true;
        emit("decoder init +" + mElapsed.getAsLong() + " type=video name=" + decoderName
                + " initMs=" + initializationDurationMs);
    }

    @Override
    public void onAudioDecoderInitialized(EventTime eventTime, String decoderName,
            long initializedTimestampMs, long initializationDurationMs) {
        if (!mArmed || mAudioDecoderLogged) {
            return;
        }
        mAudioDecoderLogged = true;
        emit("decoder init +" + mElapsed.getAsLong() + " type=audio name=" + decoderName
                + " initMs=" + initializationDurationMs);
    }

    @Override
    public void onVideoInputFormatChanged(EventTime eventTime, Format format,
            @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
        if (!mArmed || mVideoDecoderLogged || decoderReuseEvaluation == null) {
            return; // null = no codec existed yet: the init line above reports that open
        }
        if (decoderReuseEvaluation.result == DecoderReuseEvaluation.REUSE_RESULT_NO) {
            return; // refused: the codec is re-created and decoder init follows with its cost
        }
        mVideoDecoderLogged = true;
        emit("decoder reuse +" + mElapsed.getAsLong() + " type=video name="
                + decoderReuseEvaluation.decoderName
                + " result=" + reuseName(decoderReuseEvaluation.result) + formatTag(format));
    }

    @Override
    public void onAudioInputFormatChanged(EventTime eventTime, Format format,
            @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
        if (!mArmed || mAudioDecoderLogged || decoderReuseEvaluation == null
                || decoderReuseEvaluation.result == DecoderReuseEvaluation.REUSE_RESULT_NO) {
            return;
        }
        mAudioDecoderLogged = true;
        emit("decoder reuse +" + mElapsed.getAsLong() + " type=audio name="
                + decoderReuseEvaluation.decoderName
                + " result=" + reuseName(decoderReuseEvaluation.result));
    }

    @Override
    public void onPlaybackStateChanged(EventTime eventTime, int state) {
        if (!mArmed || mReadyLogged || state != Player.STATE_READY) {
            return;
        }
        mReadyLogged = true;
        emit("ready +" + mElapsed.getAsLong() + " bufferedMs=" + eventTime.totalBufferedDurationMs);
    }

    private void emitLoadDone(String what, String track, LoadEventInfo info) {
        emit(what + " done +" + mElapsed.getAsLong() + " track=" + track
                + " loadMs=" + info.loadDurationMs + " bytes=" + info.bytesLoaded);
    }

    private void emit(String line) {
        mSink.accept(mContext.get() + ' ' + line);
    }

    private static boolean isMediaTrack(int trackType) {
        return trackType == C.TRACK_TYPE_VIDEO || trackType == C.TRACK_TYPE_AUDIO
                || trackType == C.TRACK_TYPE_DEFAULT;
    }

    /** The chunk's media start time: after a seek/snap this tells which segment was requested. */
    private static String startTag(MediaLoadData data) {
        return data.dataType == C.DATA_TYPE_MEDIA && data.mediaStartTimeMs != C.TIME_UNSET
                ? " start=" + data.mediaStartTimeMs : "";
    }

    /** CacheDataSource reports no response headers for bytes it served from the media cache. */
    private static String srcTag(LoadEventInfo info) {
        return " src=" + (info.responseHeaders == null || info.responseHeaders.isEmpty() ? "cache" : "net");
    }

    private static String kind(int dataType) {
        return dataType == C.DATA_TYPE_MEDIA_INITIALIZATION ? "init" : "chunk";
    }

    private static String trackName(int trackType) {
        switch (trackType) {
            case C.TRACK_TYPE_VIDEO:
                return "video";
            case C.TRACK_TYPE_AUDIO:
                return "audio";
            default:
                return "av";
        }
    }

    private static String formatTag(@Nullable Format format) {
        if (format == null) {
            return "";
        }
        StringBuilder tag = new StringBuilder();
        if (format.id != null) {
            tag.append(" id=").append(NetPath.trunc(format.id, 16));
        }
        if (format.height > 0) {
            tag.append(" h=").append(format.height);
        }
        return tag.toString();
    }

    private static String reuseName(int result) {
        switch (result) {
            case DecoderReuseEvaluation.REUSE_RESULT_YES_WITH_FLUSH:
                return "flush";
            case DecoderReuseEvaluation.REUSE_RESULT_YES_WITH_RECONFIGURATION:
                return "reconfigure";
            case DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION:
                return "as-is";
            default:
                return "no";
        }
    }
}
