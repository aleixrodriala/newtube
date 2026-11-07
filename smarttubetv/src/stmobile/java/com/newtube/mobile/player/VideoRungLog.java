package com.newtube.mobile.player;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;

import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;

/**
 * NEWTUBE(quality-start): one NetPath line whenever the video rung changes, in every build type -
 * the evidence behind "quality takes too long to go up" reports, which per-chunk logging (debug
 * builds only) cannot give from a user's diagnostic log or a release-like benchmark build.
 * <ul>
 *   <li>{@code rung fetch}: the first media chunk of a new rung finished loading
 *   ({@code pos} = where in the video that chunk starts);</li>
 *   <li>{@code rung show}: the decoder's input switched to a new rung ({@code pos} = the playback
 *   position) - its first frame is rendered right after, not proven by this line.</li>
 * </ul>
 * Both carry the time since the open ({@code +ms}). A rung is its format id (the itag); a new
 * episode starts over.
 */
public final class VideoRungLog implements AnalyticsListener {
    @Nullable private String mFetchEpisode;
    @Nullable private String mFetchId;
    @Nullable private String mShowEpisode;
    @Nullable private String mShowId;

    @Override
    public void onLoadCompleted(EventTime eventTime, LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData) {
        Format format = mediaLoadData.trackFormat;
        if (mediaLoadData.trackType != C.TRACK_TYPE_VIDEO || mediaLoadData.dataType != C.DATA_TYPE_MEDIA
                || format == null) {
            return;
        }
        String episode = NetPath.context();
        if (episode.equals(mFetchEpisode) && equalId(format.id, mFetchId)) {
            return;
        }
        mFetchEpisode = episode;
        mFetchId = format.id;
        NetPath.log(episode + " rung fetch +" + NetPath.elapsedMs() + " " + describe(format)
                + " reason=" + reason(mediaLoadData.trackSelectionReason)
                + " pos=" + mediaLoadData.mediaStartTimeMs);
    }

    @Override
    public void onVideoInputFormatChanged(EventTime eventTime, Format format,
            @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
        String episode = NetPath.context();
        if (episode.equals(mShowEpisode) && equalId(format.id, mShowId)) {
            return;
        }
        mShowEpisode = episode;
        mShowId = format.id;
        NetPath.log(episode + " rung show +" + NetPath.elapsedMs() + " " + describe(format)
                + " pos=" + eventTime.eventPlaybackPositionMs);
    }

    static String describe(Format format) {
        return "itag=" + format.id + " " + format.width + "x" + format.height
                + (format.frameRate > 0 ? "@" + Math.round(format.frameRate) : "")
                + " kbps=" + (format.bitrate > 0 ? format.bitrate / 1000 : -1);
    }

    private static String reason(int reason) {
        switch (reason) {
            case C.SELECTION_REASON_INITIAL: return "initial";
            case C.SELECTION_REASON_MANUAL: return "manual";
            case C.SELECTION_REASON_ADAPTIVE: return "adaptive";
            case C.SELECTION_REASON_TRICK_PLAY: return "trick-play";
            default: return String.valueOf(reason);
        }
    }

    private static boolean equalId(@Nullable String a, @Nullable String b) {
        return a == null ? b == null : a.equals(b);
    }
}
