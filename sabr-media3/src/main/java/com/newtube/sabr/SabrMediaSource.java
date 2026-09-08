package com.newtube.sabr;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.TrackGroup;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.BaseMediaSource;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.source.SampleQueue;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.source.SinglePeriodTimeline;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.source.chunk.BundledChunkExtractor;
import androidx.media3.exoplayer.source.chunk.ChunkExtractor;
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.upstream.Loader;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.DummyTrackOutput;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional, ordinary-VOD-only SABR source. Shares Media3's track selection, sample queues and stock
 * container extractors/decoders. The caller must supply uncached, POST-preserving media transport.
 * Every error is terminal; an HTTP or protocol denial never causes an alternate-source attempt.
 */
public final class SabrMediaSource extends BaseMediaSource {
    private final SabrStreamInfo info;
    private final DataSource.Factory transport;
    private final BandwidthMeter bandwidth;
    private final MediaItem mediaItem;

    public SabrMediaSource(SabrStreamInfo info, DataSource.Factory transport, BandwidthMeter bandwidth) {
        this.info = info;
        this.transport = transport;
        this.bandwidth = bandwidth;
        // Never expose signed endpoint/configuration in the MediaItem, timeline or debug UI.
        mediaItem = new MediaItem.Builder().setMediaId(info.videoId)
                .setUri("sabr://vod/" + info.videoId).build();
    }

    @Override public MediaItem getMediaItem() { return mediaItem; }
    @Override protected void prepareSourceInternal(@Nullable TransferListener listener) {
        refreshSourceInfo(new SinglePeriodTimeline(info.durationUs, true, false, false, null, mediaItem));
    }
    @Override public void maybeThrowSourceInfoRefreshError() {}
    @Override protected void releaseSourceInternal() {}
    @Override public MediaPeriod createPeriod(MediaPeriodId id, Allocator allocator, long startPositionUs) {
        return new VodPeriod(info, transport, bandwidth, allocator, createEventDispatcher(id), getPlayerId());
    }
    @Override public void releasePeriod(MediaPeriod period) { ((VodPeriod) period).release(); }

    static final class VodPeriod implements MediaPeriod {
        final SabrStreamInfo info;
        final DataSource.Factory transport;
        final BandwidthMeter bandwidth;
        final Allocator allocator;
        final MediaSourceEventListener.EventDispatcher events;
        final PlayerId playerId;
        final Handler handler = new Handler(Looper.myLooper());
        final AtomicLong requestNumbers = new AtomicLong();
        final TrackGroupArray groups;
        final List<List<SabrStreamInfo.Track>> groupTracks = new ArrayList<>();
        final List<TrackStream> streams = new ArrayList<>();
        @Nullable Callback callback;
        @Nullable SabrException fatal;
        boolean released;
        long playbackPositionUs;

        VodPeriod(SabrStreamInfo info, DataSource.Factory transport, BandwidthMeter bandwidth,
                Allocator allocator, MediaSourceEventListener.EventDispatcher events, PlayerId playerId) {
            this.info = info;
            this.transport = transport;
            this.bandwidth = bandwidth;
            this.allocator = allocator;
            this.events = events;
            this.playerId = playerId;
            Map<String, List<SabrStreamInfo.Track>> grouped = new LinkedHashMap<>();
            for (SabrStreamInfo.Track track : info.tracks) {
                grouped.computeIfAbsent(track.groupKey(), key -> new ArrayList<>()).add(track);
            }
            List<TrackGroup> list = new ArrayList<>();
            for (Map.Entry<String, List<SabrStreamInfo.Track>> group : grouped.entrySet()) {
                groupTracks.add(group.getValue());
                Format[] formats = new Format[group.getValue().size()];
                // BaseTrackSelection resolves indexes by Format object identity. Even callers
                // reusing one public Format must retain distinct lmt/xtags protocol tuples.
                for (int i = 0; i < formats.length; i++) formats[i] = group.getValue().get(i).format.buildUpon().build();
                list.add(new TrackGroup("sabr:" + group.getKey(), formats));
            }
            groups = new TrackGroupArray(list.toArray(new TrackGroup[0]));
        }

        /**
         * The audio this period is loading on its own stream, or null before one is selected.
         * A video request has no way to ask for video alone, so it must name a companion audio
         * format; naming the one already being fetched is what keeps the server from adding a
         * second, unrelated audio track to every video response.
         */
        @Nullable SabrStreamInfo.Track selectedAudioTrack() {
            for (TrackStream stream : streams) {
                if (stream.track != null && stream.track.type == C.TRACK_TYPE_AUDIO) return stream.track;
            }
            return null;
        }

        SabrStreamInfo.Track selectedTrack(ExoTrackSelection selection) {
            int group = groups.indexOf(selection.getTrackGroup());
            if (group < 0) throw new IllegalArgumentException("Unknown SABR track group");
            return groupTracks.get(group).get(selection.getSelectedIndexInTrackGroup());
        }

        @Override public void prepare(Callback callback, long positionUs) {
            this.callback = callback;
            playbackPositionUs = positionUs;
            callback.onPrepared(this);
        }
        @Override public void maybeThrowPrepareError() throws IOException { if (fatal != null) throw fatal; }
        @Override public TrackGroupArray getTrackGroups() { return groups; }

        @Override public long selectTracks(ExoTrackSelection[] selections, boolean[] retain,
                SampleStream[] samples, boolean[] resetFlags, long positionUs) {
            playbackPositionUs = positionUs;
            for (int i = 0; i < selections.length; i++) {
                if (samples[i] != null && (selections[i] == null || !retain[i])) {
                    TrackStream old = (TrackStream) samples[i];
                    streams.remove(old);
                    old.release();
                    samples[i] = null;
                }
                if (selections[i] == null) continue;
                if (samples[i] == null) {
                    TrackStream stream = new TrackStream(this, selections[i], positionUs);
                    streams.add(stream);
                    samples[i] = stream;
                    resetFlags[i] = true;
                } else ((TrackStream) samples[i]).selection = selections[i];
            }
            return positionUs;
        }

        @Override public void discardBuffer(long positionUs, boolean toKeyframe) {
            for (TrackStream stream : streams) {
                if (!stream.pendingReset) stream.queue.discardTo(positionUs, toKeyframe, true);
            }
        }
        @Override public long readDiscontinuity() { return C.TIME_UNSET; }
        @Override public long seekToUs(long positionUs) {
            positionUs = Math.max(0, Math.min(positionUs, info.durationUs));
            playbackPositionUs = positionUs;
            // Clear protocol history on both forward and backward seek, even when sample data
            // happens to remain buffered. This avoids the legacy stale-consumed-range seek stall.
            for (TrackStream stream : streams) stream.reset(positionUs);
            return positionUs;
        }
        @Override public long getAdjustedSeekPositionUs(long positionUs, SeekParameters parameters) {
            return Math.max(0, Math.min(positionUs, info.durationUs));
        }
        @Override public long getBufferedPositionUs() {
            long minimum = Long.MAX_VALUE;
            for (TrackStream stream : streams) {
                if (!stream.finished) minimum = Math.min(minimum, stream.bufferedPositionUs());
            }
            return minimum == Long.MAX_VALUE ? C.TIME_END_OF_SOURCE : minimum;
        }
        @Override public long getNextLoadPositionUs() {
            long minimum = Long.MAX_VALUE;
            for (TrackStream stream : streams) {
                if (!stream.finished) minimum = Math.min(minimum, stream.nextLoadUs);
            }
            return minimum == Long.MAX_VALUE ? C.TIME_END_OF_SOURCE : minimum;
        }
        @Override public boolean continueLoading(LoadingInfo loadingInfo) {
            if (released || fatal != null) return false;
            playbackPositionUs = Math.max(0, loadingInfo.playbackPositionUs);
            long minimum = getNextLoadPositionUs();
            boolean madeProgress = false;
            for (TrackStream stream : streams) {
                // Don't let a fast track race unboundedly ahead while the other is still starting.
                if (stream.nextLoadUs <= minimum + 10_000_000) madeProgress |= stream.start(loadingInfo);
            }
            return madeProgress;
        }
        @Override public boolean isLoading() {
            for (TrackStream stream : streams) if (stream.loader.isLoading()) return true;
            return false;
        }
        @Override public void reevaluateBuffer(long positionUs) {}

        void requestContinue() {
            if (!released && callback != null) callback.onContinueLoadingRequested(this);
        }
        void fail(SabrException error, TrackStream source) {
            if (released || fatal != null) return;
            fatal = error;
            for (TrackStream stream : streams) {
                if (stream != source && stream.loader.isLoading()) stream.loader.cancelLoading();
            }
            requestContinue();
        }
        void release() {
            released = true;
            handler.removeCallbacksAndMessages(null);
            for (TrackStream stream : streams) stream.release();
            streams.clear();
            callback = null;
        }
    }

    static final class TrackStream implements SampleStream, Loader.Callback<MediaLoad> {
        /** First wait after a paced, media-free response; doubled per consecutive wait. */
        static final int IDLE_DELAY_MS = 250;
        static final int MAX_IDLE_DELAY_MS = 4000;
        /** Media-free responses tolerated while the stream has nothing buffered to play. */
        static final int MAX_STARVED_IDLE_LOADS = 3;

        final VodPeriod period;
        final Loader loader = new Loader("SabrVOD");
        final SampleQueue queue;
        ExoTrackSelection selection;
        SabrProtocol.State state;
        @Nullable SabrStreamInfo.Track track;
        @Nullable ChunkExtractor extractor;
        long generation;
        long nextLoadUs;
        long resetPositionUs;
        long notBeforeMs;
        int idleLoads;
        int starvedIdleLoads;
        boolean pendingReset;
        boolean finished;
        boolean released;
        final Runnable continueAfterBackoff;

        TrackStream(VodPeriod period, ExoTrackSelection selection, long positionUs) {
            this.period = period;
            this.selection = selection;
            queue = SampleQueue.createWithoutDrm(period.allocator);
            nextLoadUs = resetPositionUs = positionUs;
            state = new SabrProtocol.State(positionUs, period.info.durationUs);
            queue.setStartTimeUs(positionUs);
            continueAfterBackoff = period::requestContinue;
        }

        @Override public boolean isReady() {
            return !pendingReset && period.fatal == null && queue.isReady(finished);
        }
        @Override public void maybeThrowError() throws IOException {
            if (period.fatal != null) throw period.fatal;
            loader.maybeThrowError();
        }
        @Override public int readData(FormatHolder holder, DecoderInputBuffer buffer, int flags) {
            return pendingReset || period.fatal != null ? C.RESULT_NOTHING_READ
                    : queue.read(holder, buffer, flags, finished);
        }
        @Override public int skipData(long positionUs) {
            if (pendingReset || period.fatal != null) return 0;
            int count = queue.getSkipCount(positionUs, finished);
            queue.skip(count);
            return count;
        }

        long bufferedPositionUs() {
            return pendingReset ? resetPositionUs : Math.max(nextLoadUs, queue.getLargestQueuedTimestampUs());
        }

        boolean start(LoadingInfo loadingInfo) {
            if (released || finished || loader.isLoading() || period.fatal != null) return false;
            if (SystemClock.elapsedRealtime() < notBeforeMs) return false;
            if (pendingReset) completeReset();
            if (nextLoadUs >= period.info.durationUs) { finished = true; return true; }
            MediaChunkIterator[] iterators = new MediaChunkIterator[selection.length()];
            Arrays.fill(iterators, MediaChunkIterator.EMPTY);
            selection.updateSelectedTrack(period.playbackPositionUs,
                    Math.max(0, bufferedPositionUs() - period.playbackPositionUs),
                    C.TIME_UNSET, Collections.emptyList(), iterators);
            SabrStreamInfo.Track chosen = period.selectedTrack(selection);
            if (track != chosen) {
                track = chosen;
                if (extractor != null) extractor.release();
                extractor = new BundledChunkExtractor.Factory().createProgressiveMediaExtractor(
                        track.type, track.format, false, Collections.emptyList(), null, period.playerId);
                if (extractor == null) { period.fail(new SabrException("unsupported_extractor"), this); return false; }
                // An adaptive switch keeps samples already queued from the previous quality.
                // Preserve those REAL ranges (and this session's opaque policy), otherwise the
                // server sees an empty buffer at the playhead and repeats already-buffered media.
                // Only initialization belongs to the newly selected format. A seek/replacement
                // instead goes through completeReset/new TrackStream and discards all history.
                state = state.copy();
                state.declared = state.initialized = state.bound = false;
                state.knownDurationUs = period.info.durationUs;
            }
            MediaLoad load = new MediaLoad(this, generation, period.requestNumbers.incrementAndGet(),
                    loadingInfo.playbackSpeed);
            long started = loader.startLoading(load, this, 0);
            period.events.loadStarted(load.event(started, 0), C.DATA_TYPE_MEDIA,
                    track.type, track.format, selection.getSelectionReason(), null, nextLoadUs, C.TIME_UNSET);
            return true;
        }

        void reset(long positionUs) {
            generation++;
            resetPositionUs = nextLoadUs = positionUs;
            finished = false;
            pendingReset = true;
            notBeforeMs = 0;
            period.handler.removeCallbacks(continueAfterBackoff);
            if (loader.isLoading()) loader.cancelLoading();
            else completeReset();
        }

        private void completeReset() {
            idleLoads = 0;
            starvedIdleLoads = 0;
            queue.reset(true);
            queue.setStartTimeUs(resetPositionUs);
            if (extractor != null) extractor.release();
            extractor = null;
            track = null;
            state = new SabrProtocol.State(resetPositionUs, period.info.durationUs);
            pendingReset = false;
        }

        void release() {
            if (released) return;
            released = true;
            generation++;
            period.handler.removeCallbacks(continueAfterBackoff);
            queue.preRelease();
            loader.release(() -> {
                if (extractor != null) extractor.release();
                queue.release();
            });
        }

        @Override public void onLoadCompleted(MediaLoad load, long elapsedMs, long durationMs) {
            period.events.loadCompleted(load.event(elapsedMs, durationMs), C.DATA_TYPE_MEDIA,
                    load.track.type, load.track.format, selection.getSelectionReason(), null,
                    load.startUs, load.response == null ? C.TIME_UNSET : load.response.result.endUs);
            if (released || load.generation != generation || period.fatal != null) return;
            state = load.response.result;
            if (load.idle) {
                // Paced wait. Keep the policy/context/cookie the response did carry, keep the
                // buffered position, and ask again a little later. A stream that has NOTHING left
                // to play is starving rather than being paced, so it may not wait forever.
                if (nextLoadUs <= period.playbackPositionUs && ++starvedIdleLoads >= MAX_STARVED_IDLE_LOADS) {
                    period.fail(new SabrException("no_media_progress"), this);
                    return;
                }
                long delay = Math.max(state.backoffMs, Math.min(MAX_IDLE_DELAY_MS,
                        IDLE_DELAY_MS << Math.min(4, idleLoads++)));
                notBeforeMs = elapsedMs + delay;
                period.handler.postDelayed(continueAfterBackoff, delay);
                return;
            }
            idleLoads = 0;
            starvedIdleLoads = 0;
            nextLoadUs = state.endUs;
            finished = load.response.completedSegments > 0 && nextLoadUs >= state.knownDurationUs;
            notBeforeMs = elapsedMs + state.backoffMs;
            if (state.backoffMs > 0) period.handler.postDelayed(continueAfterBackoff, state.backoffMs);
            else period.requestContinue();
        }

        @Override public void onLoadCanceled(MediaLoad load, long elapsedMs, long durationMs, boolean wasReleased) {
            period.events.loadCanceled(load.event(elapsedMs, durationMs), C.DATA_TYPE_MEDIA);
            if (released || wasReleased) return;
            if (pendingReset) completeReset();
            period.requestContinue();
        }

        @Override public Loader.LoadErrorAction onLoadError(MediaLoad load, long elapsedMs,
                long durationMs, IOException error, int errorCount) {
            SabrException safe = error instanceof SabrException ? (SabrException) error
                    : new SabrException("load_failed");
            period.events.loadError(load.event(elapsedMs, durationMs), C.DATA_TYPE_MEDIA, safe, true);
            if (!released && load.generation == generation) period.fail(safe, this);
            return Loader.DONT_RETRY_FATAL;
        }
    }

    static final class MediaLoad implements Loader.Loadable {
        final TrackStream owner;
        final long generation;
        final long taskId = LoadEventInfo.getNewId();
        final long startUs;
        final SabrStreamInfo.Track track;
        final SabrProtocol.State before;
        final DataSpec request;
        final DataSpec safeEventSpec;
        final ChunkExtractor extractor;
        @Nullable SabrProtocol.ResponseInput response;
        /** The server answered normally but sent no media: wait, do not fail. */
        boolean idle;
        volatile boolean canceled;
        @Nullable volatile DataSource activeTransport;

        MediaLoad(TrackStream owner, long generation, long requestNumber, float speed) {
            this.owner = owner;
            this.generation = generation;
            startUs = owner.nextLoadUs;
            track = owner.track;
            before = owner.state.copy();
            extractor = owner.extractor;
            byte[] payload = SabrProtocol.request(owner.period.info, track,
                    track.type == C.TRACK_TYPE_VIDEO ? owner.period.selectedAudioTrack() : null, before,
                    owner.period.playbackPositionUs, owner.period.bandwidth.getBitrateEstimate(), speed);
            request = new DataSpec.Builder().setUri(numberedUri(owner.period.info.endpoint, requestNumber))
                    .setHttpMethod(DataSpec.HTTP_METHOD_POST)
                    .setHttpBody(payload).setHttpRequestHeaders(Map.of(
                            "Content-Type", "application/x-protobuf", "Accept", "application/vnd.yt-ump"))
                    .build();
            // Analytics needs the identity/byte count, never the signed query or POST payload.
            safeEventSpec = new DataSpec.Builder().setUri("sabr://vod/" + owner.period.info.videoId
                    + "/" + track.format.id).build();
        }

        @Override public void cancelLoad() {
            canceled = true;
            DataSource source = activeTransport;
            if (source != null) {
                try { source.close(); } catch (IOException ignored) { }
            }
        }

        @Override public void load() throws IOException {
            if (canceled) return;
            DataSource source = owner.period.transport.createDataSource();
            activeTransport = source;
            long deadline = SystemClock.elapsedRealtime() + 20_000;
            try {
                if (canceled) return;
                source.open(request);
                String type = null;
                for (Map.Entry<String, List<String>> entry : source.getResponseHeaders().entrySet()) {
                    if ("content-type".equalsIgnoreCase(entry.getKey()) && !entry.getValue().isEmpty()) {
                        type = entry.getValue().get(0);
                    }
                }
                if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/vnd.yt-ump")) {
                    throw new SabrException("unexpected_content_type");
                }
                InputStream input = new InputStream() {
                    final byte[] one = new byte[1];
                    @Override public int read() throws IOException {
                        int count = read(one, 0, 1);
                        return count == -1 ? -1 : one[0] & 255;
                    }
                    @Override public int read(byte[] data, int offset, int length) throws IOException {
                        if (canceled) throw new SabrException("canceled");
                        if (SystemClock.elapsedRealtime() >= deadline) throw new SabrException("response_timeout");
                        return source.read(data, offset, length);
                    }
                };
                response = new SabrProtocol.ResponseInput(input, owner.period.info, track, before);
                DefaultExtractorInput container = new DefaultExtractorInput(response::read, 0, C.LENGTH_UNSET);
                extractor.init((id, typeId) -> typeId == track.type ? owner.queue : new DummyTrackOutput(),
                        startUs, owner.period.info.durationUs);
                while (!canceled && extractor.read(container)) { }
                if (canceled) return;
                if (response.read() != -1 || !response.cleanEof) throw new SabrException("extractor_ended_early");
                boolean newInit = !before.declared && response.result.declared
                        || !before.initialized && response.result.initialized;
                // A well-formed response carrying no media is the server PACING delivery, not a
                // failure: once the client is far enough ahead of the playhead it answers with
                // control parts alone. Measured against real YouTube on 2026-09-08 - after ~30
                // seconds of audio were buffered at playhead 0, the next request returned 740
                // bytes of SELECTABLE_FORMATS/STREAM_PROTECTION_STATUS/REQUEST_IDENTIFIER and no
                // MEDIA at all. Treating that as terminal is what stopped playback a second after
                // the first frame. Only a stream with nothing left to play may call it a failure;
                // see TrackStream.onLoadCompleted.
                idle = response.completedSegments == 0 && !newInit;
                if (response.completedSegments > 0 && response.result.endUs <= before.endUs) {
                    throw new SabrException("repeated_media");
                }
            } catch (HttpDataSource.InvalidResponseCodeException failure) {
                throw new SabrException("http_error", failure.responseCode);
            } catch (SabrException failure) {
                throw failure;
            } catch (IOException | RuntimeException failure) {
                // Exception messages and causes from transports/parsers may embed media secrets.
                throw new SabrException("io_or_container_" + failure.getClass().getSimpleName());
            } finally {
                activeTransport = null;
                try { source.close(); } catch (IOException ignored) { }
            }
        }

        LoadEventInfo event(long elapsedMs, long durationMs) {
            return new LoadEventInfo(taskId, safeEventSpec, safeEventSpec.uri, Collections.emptyMap(),
                    elapsedMs, durationMs, response == null ? 0 : response.wireBytes);
        }
    }

    static Uri numberedUri(Uri endpoint, long number) {
        if (number <= 0) throw new IllegalArgumentException("Invalid request number");
        StringBuilder query = new StringBuilder();
        String encoded = endpoint.getEncodedQuery();
        if (encoded != null && !encoded.isEmpty()) {
            for (String parameter : encoded.split("&", -1)) {
                String key = parameter.split("=", 2)[0];
                if ("rn".equals(Uri.decode(key))) continue;
                // Preserve every non-numbering byte: decoding/re-encoding can change signed queries.
                if (query.length() > 0) query.append('&');
                query.append(parameter);
            }
        }
        if (query.length() > 0) query.append('&');
        return endpoint.buildUpon().encodedQuery(query.append("rn=").append(number).toString()).build();
    }
}
