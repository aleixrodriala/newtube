package com.newtube.mobile.downloads;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Joins a DASH video part and a DASH audio part into one ordinary MP4 without re-encoding, by
 * copying samples through {@link MediaExtractor} -> {@link MediaMuxer}. The two YouTube parts
 * are fragmented MP4 (moof/mdat with a sidx), which the platform extractor reads natively; the
 * muxer rewrites them as a classic moov-indexed file that any player and the gallery accept.
 *
 * <p>Samples are interleaved by presentation time so the output plays from a slow disk without
 * seeking back and forth between a video block and an audio block.
 */
final class MediaMuxHelper {
    /** Sample sizes are bounded by the largest keyframe; 1080p keyframes stay well under this. */
    private static final int VIDEO_BUFFER_BYTES = 8 * 1024 * 1024;
    private static final int AUDIO_BUFFER_BYTES = 1024 * 1024;

    interface Progress {
        /** @return false to abort. */
        boolean onProgress(long presentationTimeUs, long durationUs);
    }

    private MediaMuxHelper() {
    }

    /**
     * @return the media duration in ms when the tracks report it, else 0
     */
    static long mux(@Nullable File videoPart, @Nullable File audioPart, MediaMuxer muxer, Progress progress)
            throws IOException {
        Track video = videoPart != null ? Track.open(videoPart, "video/", VIDEO_BUFFER_BYTES) : null;
        Track audio = audioPart != null ? Track.open(audioPart, "audio/", AUDIO_BUFFER_BYTES) : null;

        if (video == null && audio == null) {
            throw new IOException("no tracks to mux");
        }

        try {
            long durationUs = 0;
            if (video != null) {
                video.muxerTrack = muxer.addTrack(video.format);
                durationUs = Math.max(durationUs, video.durationUs());
            }
            if (audio != null) {
                audio.muxerTrack = muxer.addTrack(audio.format);
                durationUs = Math.max(durationUs, audio.durationUs());
            }

            muxer.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long lastReportUs = -1;
            while (true) {
                Track next = pickNext(video, audio);
                if (next == null) {
                    break;
                }
                if (!next.writeSample(muxer, info)) {
                    next.finished = true;
                    continue;
                }
                if (info.presentationTimeUs - lastReportUs >= 2_000_000 || lastReportUs < 0) {
                    lastReportUs = info.presentationTimeUs;
                    if (!progress.onProgress(info.presentationTimeUs, durationUs)) {
                        throw new IOException("cancelled");
                    }
                }
            }

            muxer.stop();
            return durationUs / 1000;
        } finally {
            if (video != null) {
                video.release();
            }
            if (audio != null) {
                audio.release();
            }
        }
    }

    /** The unfinished track whose next sample is earliest. */
    @Nullable
    private static Track pickNext(@Nullable Track a, @Nullable Track b) {
        boolean aLive = a != null && !a.finished;
        boolean bLive = b != null && !b.finished;
        if (aLive && bLive) {
            return a.extractor.getSampleTime() <= b.extractor.getSampleTime() ? a : b;
        }
        if (aLive) {
            return a;
        }
        return bLive ? b : null;
    }

    private static final class Track {
        final MediaExtractor extractor;
        final MediaFormat format;
        final ByteBuffer buffer;
        int muxerTrack = -1;
        boolean finished;

        private Track(MediaExtractor extractor, MediaFormat format, int bufferBytes) {
            this.extractor = extractor;
            this.format = format;
            this.buffer = ByteBuffer.allocateDirect(bufferBytes);
        }

        static Track open(File file, String mimePrefix, int bufferBytes) throws IOException {
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(file.getAbsolutePath());
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith(mimePrefix)) {
                        extractor.selectTrack(i);
                        int maxInput = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                                ? format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) : 0;
                        return new Track(extractor, format, Math.max(bufferBytes, maxInput));
                    }
                }
            } catch (IOException | RuntimeException e) {
                extractor.release();
                throw e instanceof IOException ? (IOException) e : new IOException(e);
            }
            extractor.release();
            throw new IOException("no " + mimePrefix + "* track in " + file.getName());
        }

        long durationUs() {
            return format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0;
        }

        /** @return false when the track is exhausted */
        boolean writeSample(MediaMuxer muxer, MediaCodec.BufferInfo info) {
            buffer.clear();
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) {
                return false;
            }
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = extractor.getSampleTime();
            int flags = 0;
            if ((extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                flags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
            }
            info.flags = flags;
            muxer.writeSampleData(muxerTrack, buffer, info);
            extractor.advance();
            return true;
        }

        void release() {
            extractor.release();
        }
    }
}
