package com.newtube.sabr;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;

import com.newtube.sabr.proto.videostreaming.FormatInitializationMetadata;
import com.newtube.sabr.proto.videostreaming.MediaHeader;
import com.newtube.sabr.ump.UMPPartId;

import java.io.ByteArrayOutputStream;
import java.util.List;

final class SabrTestData {
    static final String VIDEO = "Fo89b8zAIE4";
    static final String ENDPOINT = "https://media.googlevideo.com/videoplayback?opaque=private-fixture";
    static SabrStreamInfo.Track video() {
        return new SabrStreamInfo.Track(new Format.Builder().setId("136")
                .setSampleMimeType(MimeTypes.VIDEO_H264).setContainerMimeType(MimeTypes.VIDEO_MP4)
                .setCodecs("avc1.42001e").setWidth(640).setHeight(360).setAverageBitrate(800_000).build(),
                123456, null, null, false);
    }
    static SabrStreamInfo.Track audio() {
        return new SabrStreamInfo.Track(new Format.Builder().setId("140")
                .setSampleMimeType(MimeTypes.AUDIO_AAC).setContainerMimeType(MimeTypes.AUDIO_MP4)
                .setCodecs("mp4a.40.2").setAverageBitrate(128_000).build(),
                123457, null, "en.1", false);
    }
    static SabrStreamInfo info(SabrStreamInfo.Track... tracks) {
        return new SabrStreamInfo(VIDEO, 6_000_000, ENDPOINT, "AA", "TVHTML5", "fixture", null, null, List.of(tracks));
    }
    static byte[] declaration(SabrStreamInfo.Track track) {
        return part(UMPPartId.FORMAT_INITIALIZATION_METADATA, FormatInitializationMetadata.newBuilder()
                .setVideoId(VIDEO).setFormatId(track.identity).setMimeType(track.format.containerMimeType)
                .setEndTimeMs(6000).build().toByteArray());
    }
    static byte[] segment(SabrStreamInfo.Track track, int id, boolean init, int startMs, int durationMs, byte[] bytes) {
        return concat(part(UMPPartId.MEDIA_HEADER, MediaHeader.newBuilder().setHeaderId(id)
                        .setVideoId(VIDEO).setFormatId(track.identity).setIsInitSeg(init)
                        .setSequenceNumber(id).setStartMs(startMs).setDurationMs(durationMs)
                        .setContentLength(bytes.length).build().toByteArray()),
                part(UMPPartId.MEDIA, concat(varint(id), bytes)), part(UMPPartId.MEDIA_END, varint(id)));
    }
    static byte[] part(int type, byte[] bytes) { return concat(varint(type), varint(bytes.length), bytes); }
    static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (byte[] array : arrays) result.write(array, 0, array.length);
        return result.toByteArray();
    }
    static byte[] varint(long value) {
        int size = value < 128 ? 1 : value < 16384 ? 2 : value < 2097152 ? 3 : value < 268435456 ? 4 : 5;
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int bits = size == 5 ? 0 : 8 - size;
        int prefix = size == 1 ? 0 : size == 2 ? 0x80 : size == 3 ? 0xc0 : size == 4 ? 0xe0 : 0xf0;
        result.write(prefix | (int) (value & ((1L << bits) - 1)));
        value >>= bits;
        for (int i = 1; i < size; i++) { result.write((int) (value & 255)); value >>= 8; }
        return result.toByteArray();
    }
}
