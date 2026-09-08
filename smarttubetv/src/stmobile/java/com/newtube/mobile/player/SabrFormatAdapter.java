package com.newtube.mobile.player;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.newtube.sabr.SabrStreamInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Metadata-only adapter: never changes endpoint parameters, client identity or authorization. */
final class SabrFormatAdapter {
    private static final Pattern CODECS = Pattern.compile("codecs=\"([^\"]+)\"");
    private SabrFormatAdapter() {}

    static boolean eligible(MediaItemFormatInfo info) {
        return info != null && info.isSabrVodEligible() && !info.isUnplayable()
                && !info.isBotCheckRequired() && !info.isLive() && !info.isLiveContent()
                && info.getClientInfo() != null && info.getAdaptiveFormats() != null
                && info.getServerAbrStreamingUrl() != null && info.getVideoPlaybackUstreamerConfig() != null;
    }

    static SabrStreamInfo adapt(MediaItemFormatInfo info) {
        if (!eligible(info)) throw new IllegalArgumentException("SABR requires accepted TV VOD metadata");
        List<SabrStreamInfo.Track> tracks = new ArrayList<>();
        boolean audio = false;
        boolean video = false;
        for (MediaFormat candidate : info.getAdaptiveFormats()) {
            if (candidate == null || candidate.isOtf() || candidate.getMimeType() == null) continue;
            String mime = candidate.getMimeType();
            String container = mime.split(";", 2)[0].trim();
            if (!MimeTypes.AUDIO_MP4.equals(container) && !MimeTypes.VIDEO_MP4.equals(container)
                    && !MimeTypes.AUDIO_WEBM.equals(container) && !MimeTypes.VIDEO_WEBM.equals(container)) continue;
            Matcher match = CODECS.matcher(mime);
            if (!match.find()) continue;
            String codecs = match.group(1);
            boolean isAudio = container.startsWith("audio/");
            String sampleMime = isAudio ? MimeTypes.getAudioMediaMimeType(codecs) : MimeTypes.getVideoMediaMimeType(codecs);
            if (sampleMime == null) continue;
            long modified = number(candidate.getLmt(), -1);
            if (modified <= 0 || number(candidate.getITag(), -1) <= 0) continue;
            String language = candidate.getLanguage();
            if (language == null && candidate.getAudioTrackId() != null) {
                language = candidate.getAudioTrackId().split("\\.", 2)[0];
            }
            Format.Builder format = new Format.Builder().setId(candidate.getITag())
                    .setContainerMimeType(container).setSampleMimeType(sampleMime).setCodecs(codecs)
                    .setAverageBitrate((int) Math.min(Integer.MAX_VALUE, number(candidate.getBitrate(), Format.NO_VALUE)))
                    .setLanguage(language).setLabel(language);
            if (isAudio) {
                format.setSampleRate((int) number(candidate.getAudioSamplingRate(), Format.NO_VALUE));
                // The extractor supplies the actual channel count and codec initialization.
                audio = true;
            } else {
                format.setWidth(candidate.getWidth()).setHeight(candidate.getHeight())
                        .setFrameRate((float) number(candidate.getFps(), Format.NO_VALUE));
                video = true;
            }
            tracks.add(new SabrStreamInfo.Track(format.build(), modified, candidate.getXtags(),
                    candidate.getAudioTrackId(), candidate.isDrc()));
        }
        if (!audio || !video) throw new IllegalArgumentException("No complete supported SABR A/V selection");
        MediaItemFormatInfo.ClientInfo client = info.getClientInfo();
        return new SabrStreamInfo(info.getVideoId(), Math.multiplyExact(number(info.getLengthSeconds(), -1), 1_000_000),
                info.getServerAbrStreamingUrl(), info.getVideoPlaybackUstreamerConfig(),
                client.getClientName(), client.getClientVersion(), client.getOsName(), client.getOsVersion(), tracks);
    }

    private static long number(String text, long fallback) {
        try { return Long.parseLong(text); } catch (RuntimeException ignored) { return fallback; }
    }
}
