package com.newtube.sabr;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.dash.DefaultDashChunkSource;
import androidx.media3.exoplayer.dash.manifest.DashManifestParser;

import com.newtube.sabr.proto.videostreaming.FormatInitializationMetadata;
import com.newtube.sabr.proto.videostreaming.MediaHeader;
import com.newtube.sabr.proto.videostreaming.VideoPlaybackAbrRequest;
import com.newtube.sabr.ump.UMPPartId;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Shared unit/device fixture only. Synthetic container assets are wrapped as UMP in memory. */
public final class SabrFixtures implements DataSource.Factory {
    public interface Assets { InputStream open(String name) throws IOException; }
    public static final String VIDEO = "sabrfixture";
    public static final String ENDPOINT = "https://fixture.googlevideo.com/videoplayback?fixture=yes";
    public final SabrStreamInfo info;
    public final List<DataSpec> requests = new CopyOnWriteArrayList<>();
    public final AtomicLong bytesRead = new AtomicLong();
    public final AtomicInteger openCount = new AtomicInteger();
    public final AtomicInteger closedCount = new AtomicInteger();
    public volatile int status = 200;
    public volatile int maxRead = 8192;
    public volatile boolean blockReads;
    /**
     * Server-side pacing: refuse to deliver more than this far ahead of the request's playhead,
     * answering with control parts and no media. Real YouTube does exactly this at roughly thirty
     * seconds - measured 2026-09-08. Zero disables it, as in every pre-existing test.
     */
    public volatile long pacingLimitMs;
    /** Answer every request after initialization with control parts and no media. */
    public volatile boolean mediaFree;
    public volatile boolean truncate;
    private final List<Container> containers = new ArrayList<>();

    /** Optional 60-second, 720p benchmark assets, generated into the test APK only. */
    public static SabrFixtures comparison720p(Assets assets) throws IOException {
        return new SabrFixtures(assets);
    }

    private SabrFixtures(Assets assets) throws IOException {
        SabrStreamInfo.Track video = track("136", MimeTypes.VIDEO_MP4, MimeTypes.VIDEO_H264,
                "avc1.64001f", 2_000_000);
        video = new SabrStreamInfo.Track(video.format.buildUpon().setWidth(1280).setHeight(720).build(),
                123456, null, null, false);
        containers.add(new Container(readAll(assets.open("avc.mp4")), false, video, 60000));
        containers.add(new Container(readAll(assets.open("aac.mp4")), false,
                track("140", MimeTypes.AUDIO_MP4, MimeTypes.AUDIO_AAC, "mp4a.40.2", 96000), 60022));
        info = new SabrStreamInfo(VIDEO, 60_050_000, ENDPOINT, "AA", "TVHTML5", "local-fixture-720p",
                null, null, Arrays.asList(containers.get(0).track, containers.get(1).track));
    }

    public SabrFixtures(Assets assets, boolean webm) throws IOException {
        this(assets, webm, false);
    }

    public SabrFixtures(Assets assets, boolean webm, boolean secondVideoQuality) throws IOException {
        if (webm) {
            containers.add(new Container(readAll(assets.open("vp9.webm")), true,
                    track("244", MimeTypes.VIDEO_WEBM, MimeTypes.VIDEO_VP9, "vp09.00.10.08", 100000), 12000));
            containers.add(new Container(readAll(assets.open("opus.webm")), true,
                    track("251", MimeTypes.AUDIO_WEBM, MimeTypes.AUDIO_OPUS, "opus", 24000), 12008));
        } else {
            containers.add(new Container(readAll(assets.open("avc.mp4")), false,
                    track("136", MimeTypes.VIDEO_MP4, MimeTypes.VIDEO_H264, "avc1.64000d", 120000), 12000));
            containers.add(new Container(readAll(assets.open("aac.mp4")), false,
                    track("140", MimeTypes.AUDIO_MP4, MimeTypes.AUDIO_AAC, "mp4a.40.2", 32000), 12022));
            if (secondVideoQuality) {
                SabrStreamInfo.Track normal = track("133", MimeTypes.VIDEO_MP4, MimeTypes.VIDEO_H264, "avc1.64000d", 55000);
                SabrStreamInfo.Track low = new SabrStreamInfo.Track(normal.format.buildUpon()
                        .setWidth(160).setHeight(90).build(), 123456, null, null, false);
                containers.add(new Container(readAll(assets.open("avc-low.mp4")), false, low, 12000));
            }
        }
        List<SabrStreamInfo.Track> tracks = new ArrayList<>();
        for (Container container : containers) tracks.add(container.track);
        info = new SabrStreamInfo(VIDEO, 12_050_000, ENDPOINT, "AA", "TVHTML5", "local-fixture",
                null, null, tracks);
    }

    private static SabrStreamInfo.Track track(String id, String container, String mime, String codecs, int bitrate) {
        Format.Builder builder = new Format.Builder().setId(id).setContainerMimeType(container)
                .setSampleMimeType(mime).setCodecs(codecs).setAverageBitrate(bitrate);
        if (container.startsWith("video")) builder.setWidth(320).setHeight(180).setFrameRate(24);
        else builder.setSampleRate(48000).setChannelCount(1).setLanguage("en");
        return new SabrStreamInfo.Track(builder.build(), 123456, null,
                container.startsWith("audio") ? "en.1" : null, false);
    }

    @Override public DataSource createDataSource() {
        return new DataSource() {
            ByteArrayInputStream body;
            volatile boolean closed;
            boolean opened;
            @Override public long open(DataSpec spec) throws IOException {
                if (spec.httpMethod != DataSpec.HTTP_METHOD_POST || spec.position != 0 || spec.httpBody == null
                        || spec.httpRequestHeaders.containsKey("Authorization") || spec.httpRequestHeaders.containsKey("Cookie")) {
                    throw new IOException("Invalid fixture POST contract");
                }
                requests.add(spec);
                openCount.incrementAndGet();
                opened = true;
                if (status != 200) throw new HttpDataSource.InvalidResponseCodeException(
                        status, "fixture", null, Collections.emptyMap(), spec, new byte[0]);
                byte[] payload = response(spec.httpBody);
                if (truncate) payload = Arrays.copyOf(payload, payload.length - 1);
                body = new ByteArrayInputStream(payload);
                return C.LENGTH_UNSET;
            }
            @Override public int read(byte[] data, int offset, int length) throws IOException {
                while (blockReads && !closed) {
                    try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("canceled"); }
                }
                if (closed) throw new IOException("closed");
                int count = body.read(data, offset, Math.min(length, maxRead));
                if (count > 0) bytesRead.addAndGet(count);
                return count;
            }
            @Override public void close() {
                if (!closed && opened) closedCount.incrementAndGet();
                closed = true;
            }
            @Override public Uri getUri() { return Uri.parse(ENDPOINT); }
            @Override public Map<String, List<String>> getResponseHeaders() {
                return Map.of("Content-Type", List.of("application/vnd.yt-ump"));
            }
            @Override public void addTransferListener(TransferListener listener) {}
        };
    }

    public int mediaRequests() {
        int count = 0;
        for (DataSpec spec : requests) {
            try { if (VideoPlaybackAbrRequest.parseFrom(spec.httpBody).getSelectedFormatIdsCount() > 0) count++; }
            catch (IOException ignored) { }
        }
        return count;
    }

    /** Identical generated media samples/segments through stock DASH, no cache or external HTTP. */
    public MediaSource dashSource() throws IOException {
        return dashSource(java.util.function.UnaryOperator.identity());
    }

    /** Apply the same test-only link wrapper used for SABR to every DASH response. */
    public MediaSource dashSource(java.util.function.UnaryOperator<DataSource.Factory> decorate) throws IOException {
        String duration = Double.toString(info.durationUs / 1_000_000d);
        StringBuilder xml = new StringBuilder("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" "
                + "mediaPresentationDuration=\"PT" + duration + "S\" minBufferTime=\"PT1S\"><Period duration=\"PT"
                + duration + "S\">");
        for (Container container : containers) {
            Format f = container.track.format;
            xml.append("<AdaptationSet mimeType=\"").append(f.containerMimeType).append("\"><Representation id=\"")
                    .append(f.id).append("\" codecs=\"").append(f.codecs).append("\" bandwidth=\"").append(f.bitrate).append("\"");
            if (container.track.type == C.TRACK_TYPE_VIDEO) xml.append(" width=\"").append(f.width)
                    .append("\" height=\"").append(f.height).append("\" frameRate=\"24\"");
            else xml.append(" audioSamplingRate=\"48000\"");
            xml.append("><SegmentList timescale=\"1000\"><Initialization sourceURL=\"https://fixture.invalid/")
                    .append(f.id).append("/init\"/><SegmentTimeline>");
            for (Fragment fragment : container.fragments) xml.append("<S t=\"").append(fragment.startMs)
                    .append("\" d=\"").append(fragment.endMs - fragment.startMs).append("\"/>");
            xml.append("</SegmentTimeline>");
            for (int i = 0; i < container.fragments.size(); i++) xml.append("<SegmentURL media=\"https://fixture.invalid/")
                    .append(f.id).append('/').append(i).append("\"/>");
            xml.append("</SegmentList></Representation></AdaptationSet>");
        }
        xml.append("</Period></MPD>");
        DataSource.Factory factory = () -> new DataSource() {
            ByteArrayInputStream body;
            Uri uri;
            boolean closed;
            @Override public long open(DataSpec spec) throws IOException {
                if (spec.httpMethod != DataSpec.HTTP_METHOD_GET) throw new IOException("Expected fixture GET");
                uri = spec.uri;
                requests.add(spec);
                openCount.incrementAndGet();
                String itag = uri.getPathSegments().get(0);
                String segment = uri.getLastPathSegment();
                for (Container container : containers) {
                    if (!itag.equals(container.track.format.id)) continue;
                    byte[] data = "init".equals(segment) ? container.initialization
                            : container.fragments.get(Integer.parseInt(segment)).bytes;
                    body = new ByteArrayInputStream(data, (int) spec.position, data.length - (int) spec.position);
                    return data.length - spec.position;
                }
                throw new IOException("Missing fixture segment");
            }
            @Override public int read(byte[] data, int offset, int length) {
                int count = body.read(data, offset, Math.min(length, maxRead));
                if (count > 0) bytesRead.addAndGet(count);
                return count;
            }
            @Override public void close() { if (!closed) closedCount.incrementAndGet(); closed = true; }
            @Override public Uri getUri() { return uri; }
            @Override public void addTransferListener(TransferListener ignored) {}
        };
        Uri uri = Uri.parse("https://fixture.invalid/manifest.mpd");
        return new DashMediaSource.Factory(new DefaultDashChunkSource.Factory(decorate.apply(factory)), null)
                .createMediaSource(new DashManifestParser().parse(uri,
                        new ByteArrayInputStream(xml.toString().getBytes(StandardCharsets.UTF_8))), MediaItem.fromUri(uri));
    }

    private byte[] response(byte[] requestBytes) throws IOException {
        VideoPlaybackAbrRequest request = VideoPlaybackAbrRequest.parseFrom(requestBytes);
        boolean video = request.getPreferredVideoFormatIdsCount() > 0;
        int itag = video ? request.getPreferredVideoFormatIds(0).getItag()
                : request.getPreferredAudioFormatIds(0).getItag();
        Container container = find(itag);
        if (container == null) throw new IOException("Unknown fixture track");
        byte[] own = own(request, container);
        return video ? concat(companion(request, container), own) : own;
    }

    private byte[] own(VideoPlaybackAbrRequest request, Container container) {
        if (request.getSelectedFormatIdsCount() == 0) {
            return container.initialResponse;
        }
        long target = request.getClientAbrState().getPlayerTimeMs();
        for (com.newtube.sabr.proto.videostreaming.BufferedRange range : request.getBufferedRangesList()) {
            // Ranges describe a TRACK's timeline, so an adaptive switch's old-quality ranges still
            // count as video progress. The companion-audio claim a video request carries to
            // suppress audio delivery is the other track, and must not be read as video progress.
            Container source = find(range.getFormatId().getItag());
            if (source == null || source.track.type != container.track.type) continue;
            target = Math.max(target, range.getStartTimeMs() + range.getDurationMs());
        }
        long limit = pacingLimitMs;
        if (mediaFree || limit > 0 && target - request.getClientAbrState().getPlayerTimeMs() >= limit) {
            // Far enough ahead: control parts only, no media and no error.
            return part(UMPPartId.REQUEST_IDENTIFIER, new byte[0]);
        }
        for (int i = 0; i < container.fragments.size(); i++) {
            Fragment fragment = container.fragments.get(i);
            if (fragment.endMs > target) {
                return container.fragmentResponses.get(i);
            }
        }
        return part(UMPPartId.END_OF_TRACK, new byte[0]);
    }

    /**
     * Real YouTube has no video-only request: a video response also carries the audio format the
     * client named, until that format appears in {@code selectedFormatIds}. Measured 2026-09-08 -
     * without the companion claim the server ships its own default audio with every video response,
     * and with it the companion costs one initialization segment and then nothing.
     */
    private byte[] companion(VideoPlaybackAbrRequest request, Container container) {
        if (request.getPreferredAudioFormatIdsCount() == 0) return new byte[0];
        Container audio = find(request.getPreferredAudioFormatIds(0).getItag());
        if (audio == null || audio == container) return new byte[0];
        for (com.newtube.sabr.proto.misc.FormatId selected : request.getSelectedFormatIdsList()) {
            if (selected.equals(audio.track.identity)) return new byte[0];
        }
        return audio.companionResponse;
    }

    private Container find(int itag) {
        for (Container candidate : containers) {
            if (candidate.track.identity.getItag() == itag) return candidate;
        }
        return null;
    }

    private static byte[] segment(Container container, int id, boolean init, long start, long duration, byte[] bytes) {
        return concat(part(UMPPartId.MEDIA_HEADER, MediaHeader.newBuilder().setHeaderId(id).setVideoId(VIDEO)
                        .setFormatId(container.track.identity).setIsInitSeg(init).setSequenceNumber(id)
                        .setStartMs(start).setDurationMs(duration).setContentLength(bytes.length).build().toByteArray()),
                part(UMPPartId.MEDIA, concat(varint(id), bytes)), part(UMPPartId.MEDIA_END, varint(id)));
    }
    private static byte[] part(int type, byte[] bytes) { return concat(varint(type), varint(bytes.length), bytes); }
    private static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] bytes : arrays) out.write(bytes, 0, bytes.length);
        return out.toByteArray();
    }
    private static byte[] varint(long value) {
        int size = value < 128 ? 1 : value < 16384 ? 2 : value < 2097152 ? 3 : value < 268435456 ? 4 : 5;
        int bits = size == 5 ? 0 : 8 - size;
        int prefix = size == 1 ? 0 : size == 2 ? 0x80 : size == 3 ? 0xc0 : size == 4 ? 0xe0 : 0xf0;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(prefix | (int) (value & ((1L << bits) - 1)));
        value >>= bits;
        for (int i = 1; i < size; i++) { out.write((int) value & 255); value >>= 8; }
        return out.toByteArray();
    }
    private static byte[] readAll(InputStream input) throws IOException {
        if (input == null) throw new IOException("Run tools/generate-sabr-fixtures.sh first");
        try (InputStream owned = input) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] bytes = new byte[8192];
            int count;
            while ((count = owned.read(bytes)) != -1) out.write(bytes, 0, count);
            return out.toByteArray();
        }
    }

    private static final class Container {
        final SabrStreamInfo.Track track;
        final long endMs;
        final byte[] initialization;
        final byte[] initialResponse;
        /** The same declaration under a header id that cannot collide with the video's own. */
        final byte[] companionResponse;
        final List<Fragment> fragments = new ArrayList<>();
        final List<byte[]> fragmentResponses = new ArrayList<>();

        Container(byte[] bytes, boolean webm, SabrStreamInfo.Track track, long endMs) throws IOException {
            this.track = track;
            this.endMs = endMs;
            List<Element> media = new ArrayList<>();
            List<Long> starts = new ArrayList<>();
            if (!webm) {
                for (Element box : mp4(bytes, 0, bytes.length)) if (box.id == id("moof")) media.add(box);
                int scale = track.type == C.TRACK_TYPE_VIDEO ? 12288 : 48000;
                for (Element box : media) {
                    Element traf = child(bytes, box, "traf");
                    Element tfdt = child(bytes, traf, "tfdt");
                    long ticks = number(bytes, tfdt.body + 4, bytes[tfdt.body] == 1 ? 8 : 4);
                    starts.add(ticks * 1000 / scale);
                }
            } else {
                Element segment = null;
                for (Element element : ebml(bytes, 0, bytes.length)) if (element.id == 0x18538067L) segment = element;
                if (segment == null) throw new IOException("Missing fixture Segment");
                for (Element element : ebml(bytes, segment.body, segment.end)) {
                    if (element.id == 0x1f43b675L) {
                        media.add(element);
                        long time = -1;
                        for (Element field : ebml(bytes, element.body, element.end)) {
                            if (field.id == 0xe7) { time = number(bytes, field.body, field.end - field.body); break; }
                        }
                        if (time < 0) throw new IOException("Missing cluster time");
                        starts.add(time); // FFmpeg's synthetic WebM uses the default 1 ms scale.
                    }
                }
            }
            if (media.isEmpty()) throw new IOException("No fixture fragments");
            initialization = Arrays.copyOf(bytes, media.get(0).start);
            for (int i = 0; i < media.size(); i++) {
                long end = i + 1 < media.size() ? starts.get(i + 1) : endMs;
                if (end <= starts.get(i)) throw new IOException("Non-increasing fixture timing");
                Element element = media.get(i);
                // MP4's mdat follows moof; WebM Cluster already contains its sample payload.
                int byteEnd = webm ? element.end : i + 1 < media.size() ? media.get(i + 1).start : bytes.length;
                fragments.add(new Fragment(starts.get(i), end, Arrays.copyOfRange(bytes, element.start, byteEnd)));
            }
            // Server-side packaging is prepared BEFORE benchmark timing for both source kinds.
            initialResponse = concat(part(UMPPartId.FORMAT_INITIALIZATION_METADATA,
                            FormatInitializationMetadata.newBuilder().setVideoId(VIDEO).setFormatId(track.identity)
                                    .setMimeType(track.format.containerMimeType).setEndTimeMs(endMs).build().toByteArray()),
                    segment(this, 1, true, 0, 0, initialization));
            companionResponse = concat(part(UMPPartId.FORMAT_INITIALIZATION_METADATA,
                            FormatInitializationMetadata.newBuilder().setVideoId(VIDEO).setFormatId(track.identity)
                                    .setMimeType(track.format.containerMimeType).setEndTimeMs(endMs).build().toByteArray()),
                    segment(this, 1001, true, 0, 0, initialization));
            for (int i = 0; i < fragments.size(); i++) {
                Fragment fragment = fragments.get(i);
                fragmentResponses.add(segment(this, i + 2, false, fragment.startMs,
                        fragment.endMs - fragment.startMs, fragment.bytes));
            }
        }
    }
    private static final class Fragment {
        final long startMs, endMs;
        final byte[] bytes;
        Fragment(long startMs, long endMs, byte[] bytes) { this.startMs = startMs; this.endMs = endMs; this.bytes = bytes; }
    }
    private static final class Element {
        final long id;
        final int start, body, end;
        Element(long id, int start, int body, int end) { this.id = id; this.start = start; this.body = body; this.end = end; }
    }
    private static long id(String text) { return number(text.getBytes(StandardCharsets.US_ASCII), 0, 4); }
    private static long number(byte[] bytes, int offset, int count) {
        long value = 0;
        for (int i = 0; i < count; i++) value = (value << 8) | (bytes[offset + i] & 255L);
        return value;
    }
    private static List<Element> mp4(byte[] bytes, int from, int end) throws IOException {
        List<Element> result = new ArrayList<>();
        for (int pos = from; pos + 8 <= end;) {
            long size = number(bytes, pos, 4);
            int header = 8;
            if (size == 1) { size = number(bytes, pos + 8, 8); header = 16; }
            if (size == 0) size = end - pos;
            if (size < header || size > end - pos) throw new IOException("Invalid fixture MP4 box");
            result.add(new Element(number(bytes, pos + 4, 4), pos, pos + header, pos + (int) size));
            pos += (int) size;
        }
        return result;
    }
    private static Element child(byte[] bytes, Element parent, String type) throws IOException {
        for (Element element : mp4(bytes, parent.body, parent.end)) if (element.id == id(type)) return element;
        throw new IOException("Missing fixture box " + type);
    }
    private static List<Element> ebml(byte[] bytes, int from, int end) throws IOException {
        List<Element> result = new ArrayList<>();
        for (int pos = from; pos < end;) {
            int idLength = vintLength(bytes[pos]);
            long id = number(bytes, pos, idLength);
            int sizeAt = pos + idLength;
            int sizeLength = vintLength(bytes[sizeAt]);
            long size = number(bytes, sizeAt, sizeLength) & ((1L << (7 * sizeLength)) - 1);
            int body = sizeAt + sizeLength;
            if (size == (1L << (7 * sizeLength)) - 1) size = end - body;
            if (size < 0 || size > end - body) throw new IOException("Invalid fixture EBML element");
            result.add(new Element(id, pos, body, body + (int) size));
            pos = body + (int) size;
        }
        return result;
    }
    private static int vintLength(byte first) throws IOException {
        for (int length = 1; length <= 8; length++) if ((first & (0x80 >> (length - 1))) != 0) return length;
        throw new IOException("Invalid fixture EBML integer");
    }
}
