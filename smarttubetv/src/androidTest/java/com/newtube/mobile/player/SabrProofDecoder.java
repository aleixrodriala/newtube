package com.newtube.mobile.player;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaDataSource;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.SystemClock;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Instrumentation-only proof that delivered container bytes produce decoder output.
 * No Surface or AudioTrack is created: these are decoded buffers, not displayed/heard media.
 */
public final class SabrProofDecoder {
    private static final long MAX_WALL_MS = 8_000;
    private static final long POLL_US = 10_000;
    private static final int MAX_OUTPUT_BUFFERS = 30;

    private SabrProofDecoder() {
    }

    /** Numeric, nonsecret evidence only. firstOutputMs is -1 when no output was decoded. */
    public static final class Result {
        public final int sampleCount;
        public final int outputBufferCount;
        public final long firstOutputMs;
        public final boolean outputEos;

        private Result(int sampleCount, int outputBufferCount, long firstOutputMs,
                boolean outputEos) {
            this.sampleCount = sampleCount;
            this.outputBufferCount = outputBufferCount;
            this.firstOutputMs = firstOutputMs;
            this.outputEos = outputEos;
        }
    }

    /**
     * Decode one requested track from in-memory init+media container bytes.
     * The caller must not mutate data while this method is running. The wall-clock
     * deadline also covers native setup: cancellation interrupts the worker, which
     * owns cleanup in finally (an unresponsive native call may finish cleanup later).
     * Exceptions deliberately contain no platform messages, input bytes or causes.
     */
    public static Result decode(byte[] data, boolean video) throws IOException {
        if (data == null || data.length == 0) {
            throw new IOException("decode_empty_input");
        }
        long startedMs = SystemClock.elapsedRealtime();
        long deadlineMs = startedMs + MAX_WALL_MS;
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SabrProofDecoder");
            thread.setDaemon(true);
            return thread;
        });
        Future<Result> future = executor.submit(() -> decodeInternal(data, video, startedMs, deadlineMs));
        try {
            long remainingMs = deadlineMs - SystemClock.elapsedRealtime();
            if (remainingMs <= 0) {
                throw new TimeoutException();
            }
            return future.get(remainingMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            throw new IOException("decode_timeout");
        } catch (InterruptedException error) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("decode_interrupted");
        } catch (ExecutionException error) {
            throw new IOException("decode_failed");
        } finally {
            executor.shutdownNow();
        }
    }

    private static Result decodeInternal(byte[] data, boolean video, long startedMs,
            long deadlineMs) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MemorySource source = new MemorySource(data, deadlineMs);
        MediaCodec codec = null;
        boolean codecStarted = false;
        try {
            checkDeadline(deadlineMs);
            extractor.setDataSource(source);
            int selectedTrack = -1;
            MediaFormat selectedFormat = null;
            String selectedMime = null;
            for (int index = 0; index < extractor.getTrackCount(); index++) {
                checkDeadline(deadlineMs);
                MediaFormat format = extractor.getTrackFormat(index);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith(video ? "video/" : "audio/")) {
                    selectedTrack = index;
                    selectedFormat = format;
                    selectedMime = mime;
                    break;
                }
            }
            if (selectedTrack < 0) {
                throw new IOException("decode_missing_track");
            }
            extractor.selectTrack(selectedTrack);
            if (video) {
                selectedFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            }
            checkDeadline(deadlineMs);
            codec = MediaCodec.createDecoderByType(selectedMime);
            codec.configure(selectedFormat, null, null, 0);
            checkDeadline(deadlineMs);
            codec.start();
            codecStarted = true;

            int sampleCount = 0;
            int outputCount = 0;
            long firstOutputMs = -1;
            boolean inputEos = false;
            boolean outputEos = false;
            MediaCodec.BufferInfo outputInfo = new MediaCodec.BufferInfo();
            while (!outputEos && outputCount < MAX_OUTPUT_BUFFERS) {
                checkDeadline(deadlineMs);
                if (!inputEos) {
                    int inputIndex = codec.dequeueInputBuffer(POLL_US);
                    if (inputIndex >= 0) {
                        ByteBuffer input = codec.getInputBuffer(inputIndex);
                        if (input == null) {
                            throw new IOException("decode_missing_input_buffer");
                        }
                        input.clear();
                        int size = extractor.readSampleData(input, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEos = true;
                        } else {
                            int sampleFlags = extractor.getSampleFlags();
                            if ((sampleFlags & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) {
                                throw new IOException("decode_encrypted_sample_unsupported");
                            }
                            int codecFlags = (sampleFlags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0
                                    ? MediaCodec.BUFFER_FLAG_PARTIAL_FRAME : 0;
                            codec.queueInputBuffer(inputIndex, 0, size,
                                    extractor.getSampleTime(), codecFlags);
                            if (size > 0) {
                                sampleCount++;
                            }
                            extractor.advance();
                        }
                    }
                }

                checkDeadline(deadlineMs);
                int outputIndex = codec.dequeueOutputBuffer(outputInfo, POLL_US);
                if (outputIndex >= 0) {
                    try {
                        boolean config = (outputInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                        if (!config && outputInfo.size > 0) {
                            outputCount++;
                            if (firstOutputMs < 0) {
                                firstOutputMs = SystemClock.elapsedRealtime() - startedMs;
                            }
                        }
                        outputEos = (outputInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    } finally {
                        codec.releaseOutputBuffer(outputIndex, false);
                    }
                }
            }
            return new Result(sampleCount, outputCount, firstOutputMs, outputEos);
        } finally {
            if (codec != null) {
                if (codecStarted) {
                    try {
                        codec.stop();
                    } catch (RuntimeException ignored) {
                        // A failed/interrupting codec still needs release below.
                    }
                }
                try {
                    codec.release();
                } catch (RuntimeException ignored) {
                    // Continue releasing the independent extractor/source resources.
                }
            }
            try {
                extractor.release();
            } finally {
                source.close();
            }
        }
    }

    private static void checkDeadline(long deadlineMs) throws IOException {
        if (Thread.currentThread().isInterrupted()
                || SystemClock.elapsedRealtime() >= deadlineMs) {
            throw new IOException("decode_cancelled");
        }
    }

    private static final class MemorySource extends MediaDataSource {
        private final byte[] data;
        private final long deadlineMs;
        private volatile boolean closed;

        private MemorySource(byte[] data, long deadlineMs) {
            this.data = data;
            this.deadlineMs = deadlineMs;
        }

        @Override
        public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
            checkDeadline(deadlineMs);
            if (closed || position < 0 || offset < 0 || size < 0
                    || buffer == null || offset > buffer.length - size) {
                throw new IOException("decode_invalid_read");
            }
            if (size == 0) return 0;
            if (position >= data.length) return -1;
            int count = (int) Math.min(size, data.length - position);
            System.arraycopy(data, (int) position, buffer, offset, count);
            return count;
        }

        @Override
        public long getSize() throws IOException {
            checkDeadline(deadlineMs);
            if (closed) throw new IOException("decode_source_closed");
            return data.length;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
