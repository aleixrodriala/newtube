package com.newtube.sabr;

import android.net.Uri;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;

import com.google.protobuf.ByteString;
import com.newtube.sabr.proto.misc.FormatId;
import com.newtube.sabr.proto.videostreaming.StreamerContext;

import java.util.ArrayList;
import android.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Immutable metadata for one already-permitted ordinary VOD response. Contains no account API. */
public final class SabrStreamInfo {
    public final String videoId;
    public final long durationUs;
    public final List<Track> tracks;
    final Uri endpoint;
    final ByteString config;
    final StreamerContext.ClientInfo client;

    public SabrStreamInfo(String videoId, long durationUs, String endpoint, String encodedConfig,
            String clientName, String clientVersion, String osName, String osVersion,
            List<Track> tracks) {
        if (videoId == null || !videoId.matches("[A-Za-z0-9_-]{11}") || durationUs <= 0
                || durationUs > 7L * 24 * 60 * 60 * 1_000_000) {
            throw new IllegalArgumentException("Invalid SABR VOD identity/duration");
        }
        Uri uri = Uri.parse(endpoint == null ? "" : endpoint);
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null
                || !host.toLowerCase(Locale.ROOT).endsWith(".googlevideo.com")
                || uri.getUserInfo() != null || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || !"/videoplayback".equals(uri.getPath())) {
            throw new IllegalArgumentException("Invalid SABR media endpoint");
        }
        if (encodedConfig == null || encodedConfig.isEmpty() || encodedConfig.length() > 1024 * 1024
                || !encodedConfig.matches("[A-Za-z0-9_-]+={0,2}")
                || clientName == null || clientVersion == null || clientVersion.isEmpty()) {
            throw new IllegalArgumentException("Missing SABR configuration/client");
        }
        try {
            config = ByteString.copyFrom(Base64.decode(encodedConfig, Base64.URL_SAFE | Base64.NO_WRAP));
            StreamerContext.ClientInfo.Builder builder = StreamerContext.ClientInfo.newBuilder()
                    // Client names are carried in the form the metadata layer uses ("iOS"),
                    // while the protocol enum spells every constant upper case.
                    .setClientName(StreamerContext.ClientName.valueOf(clientName.toUpperCase(Locale.ROOT)))
                    .setClientVersion(clientVersion);
            if (osName != null) builder.setOsName(osName);
            if (osVersion != null) builder.setOsVersion(osVersion);
            client = builder.build();
        } catch (IllegalArgumentException failure) {
            // Base64/enum exception text can reproduce input. Do not propagate it.
            throw new IllegalArgumentException("Invalid SABR configuration/client");
        }
        if (config.isEmpty() || tracks == null || tracks.isEmpty() || tracks.size() > 256) {
            throw new IllegalArgumentException("Missing or excessive SABR tracks");
        }
        Set<FormatId> identities = new HashSet<>();
        for (Track track : tracks) {
            if (track == null || !identities.add(track.identity)) {
                throw new IllegalArgumentException("Ambiguous SABR track identity");
            }
        }
        this.videoId = videoId;
        this.durationUs = durationUs;
        this.endpoint = uri;
        this.tracks = Collections.unmodifiableList(new ArrayList<>(tracks));
    }

    public static final class Track {
        public final Format format;
        public final int type;
        public final String audioTrackId;
        public final boolean drc;
        final FormatId identity;

        public Track(Format format, long lastModified, String xtags, String audioTrackId, boolean drc) {
            int itag;
            try { itag = Integer.parseInt(format.id); }
            catch (RuntimeException ignored) { throw new IllegalArgumentException("Missing SABR itag"); }
            int type = MimeTypes.getTrackType(format.sampleMimeType);
            if (itag <= 0 || lastModified <= 0 || xtags != null && xtags.length() > 4096
                    || type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_VIDEO
                    || !(MimeTypes.VIDEO_MP4.equals(format.containerMimeType)
                        || MimeTypes.AUDIO_MP4.equals(format.containerMimeType)
                        || MimeTypes.VIDEO_WEBM.equals(format.containerMimeType)
                        || MimeTypes.AUDIO_WEBM.equals(format.containerMimeType))) {
                throw new IllegalArgumentException("Unsupported or incomplete SABR track");
            }
            this.format = format;
            this.type = type;
            this.audioTrackId = audioTrackId;
            this.drc = drc;
            FormatId.Builder builder = FormatId.newBuilder().setItag(itag).setLastModified(lastModified);
            if (xtags != null && !xtags.isEmpty()) builder.setXtags(xtags);
            identity = builder.build();
        }

        String groupKey() {
            return type + ":" + format.sampleMimeType + ":" + format.containerMimeType
                    + (type == C.TRACK_TYPE_AUDIO
                    ? ":" + format.language + ":" + audioTrackId + ":" + drc : "");
        }
    }
}
