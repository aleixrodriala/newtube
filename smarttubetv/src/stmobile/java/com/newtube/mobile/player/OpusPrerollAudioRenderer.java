package com.newtube.mobile.player;

import android.content.Context;
import android.os.Handler;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.MediaSource;

import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;

/**
 * NEWTUBE(opus-preroll): the stock MediaCodec audio renderer (on the Pixel: {@code
 * c2.android.opus.decoder} for YouTube's itag 251/251-drc WebM Opus, there is no libopus extension
 * in the app) that drops Opus packets the decoder does not need to reach a reset position - see
 * {@link OpusPrerollGate} for the why, the 80 ms pre-roll it keeps, and when it is armed.
 *
 * <p>Both sides of the comparison are renderer time: {@code BaseRenderer.readSource} adds the
 * stream offset to every {@code buffer.timeUs}, and {@link #getLastResetPositionUs()} is the
 * renderer position the player reset us to. A dropped buffer is counted in
 * {@code skippedInputBufferCount} by {@code MediaCodecRenderer.shouldDiscardDecoderInputBuffer}
 * and never consumes a codec input slot. One {@code audio-preroll skipped=N fromMs= toMs= resetMs=}
 * NetPath line per reset that skipped anything (logged on the playback thread).</p>
 */
final class OpusPrerollAudioRenderer extends MediaCodecAudioRenderer {
    private final OpusPrerollGate mGate = new OpusPrerollGate();

    OpusPrerollAudioRenderer(Context context, MediaCodecAdapter.Factory codecAdapterFactory,
            MediaCodecSelector mediaCodecSelector, boolean enableDecoderFallback,
            @Nullable Handler eventHandler, @Nullable AudioRendererEventListener eventListener,
            AudioSink audioSink) {
        super(context, codecAdapterFactory, mediaCodecSelector, enableDecoderFallback, eventHandler,
                eventListener, audioSink);
    }

    @Override
    protected void onStreamChanged(Format[] formats, long startPositionUs, long offsetUs,
            MediaSource.MediaPeriodId mediaPeriodId) throws ExoPlaybackException {
        super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId);
        mGate.onStreamChanged();
    }

    @Override
    protected void onPositionReset(long positionUs, boolean joining,
            boolean sampleStreamIsResetToKeyFrame) throws ExoPlaybackException {
        super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame);
        // MediaCodecRenderer flushes (or re-creates) the decoder only for a key-frame reset; any
        // other reset keeps a running decoder that must see every packet.
        report(mGate.onPositionReset(positionUs, sampleStreamIsResetToKeyFrame));
    }

    @Override
    protected boolean shouldSkipDecoderInputBuffer(DecoderInputBuffer buffer) {
        Format codecFormat = getCodecInputFormat();
        return mGate.shouldSkip(codecFormat != null ? codecFormat.sampleMimeType : null,
                getLastResetPositionUs(), buffer.timeUs, buffer.isEndOfStream())
                || super.shouldSkipDecoderInputBuffer(buffer);
    }

    @Override
    protected void onQueueInputBuffer(DecoderInputBuffer buffer) throws ExoPlaybackException {
        super.onQueueInputBuffer(buffer);
        report(mGate.onQueued());
    }

    private static void report(@Nullable String payload) {
        if (payload != null) {
            NetPath.log(NetPath.context() + " audio-preroll " + payload);
        }
    }
}
