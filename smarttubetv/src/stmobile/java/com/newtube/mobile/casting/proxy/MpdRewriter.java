package com.newtube.mobile.casting.proxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;

/**
 * Rewrites the app's generated DASH manifest ({@code MediaItemFormatInfo.createMpdStream()}, shape
 * defined by {@code YouTubeMPDBuilder}) for Route A "Direct cast" (CASTING.md): every absolute
 * googlevideo URL is replaced with {@code <localBase>/seg/<token>} pointing at
 * {@link CastProxyServer}, and the representation set is trimmed to what a Default Media Receiver
 * can decode.
 *
 * <p>v1 compatibility policy (CASTING.md "universal decode"): keep ONLY H.264 video
 * ({@code codecs} starting {@code avc1}) and AAC audio ({@code mp4a}); VP9/AV1/Opus and subtitle
 * representations are dropped (gen-1/2 Chromecasts can't decode them). Video above 1080p is also
 * dropped - most dongles won't do 4K H.264 reliably and the phone relays every media byte, so the
 * bandwidth would double for nothing. OTF/live representations (SegmentTemplate with a
 * {@code $Number$} media template) are dropped too: tokens wrap a complete URL, so a template
 * placeholder inside a token would never be expanded by the receiver - live needs proxied HLS,
 * which is explicitly post-v1.</p>
 *
 * <p>Rewrite points, matched to what YouTubeMPDBuilder actually emits for VOD: {@code BaseURL}
 * text (the common case: absolute videoplayback URL, optional {@code yt:contentLength} attribute),
 * {@code SegmentList > Initialization@sourceURL} and {@code SegmentList > SegmentURL@media}
 * (absolute URLs). {@code SegmentBase/Initialization@range} holds BYTE ranges, never URLs - it is
 * deliberately untouched (the 416/cache-poisoning post-mortem rule: ranges ride the Range header,
 * nothing else).</p>
 */
public final class MpdRewriter {
    private static final String TAG = MpdRewriter.class.getSimpleName();

    public static final String MIME_TYPE = "application/dash+xml";

    /** Universal Direct-cast ceiling; callers may request a lower user-selected cap. */
    public static final int MAX_VIDEO_HEIGHT = 1080;

    private MpdRewriter() {
    }

    /** Outcome of a rewrite: the manifest to serve plus what survived the compatibility filter. */
    public static final class Result {
        private final byte[] mMpdBytes;
        private final int mMaxVideoHeight;
        private final boolean mHasCompatibleVideo;
        private final boolean mSourceHadVideo;
        private final boolean mHasAudio;

        Result(byte[] mpdBytes, int maxVideoHeight, boolean hasCompatibleVideo,
                boolean sourceHadVideo, boolean hasAudio) {
            mMpdBytes = mpdBytes;
            mMaxVideoHeight = maxVideoHeight;
            mHasCompatibleVideo = hasCompatibleVideo;
            mSourceHadVideo = sourceHadVideo;
            mHasAudio = hasAudio;
        }

        /** Rewritten manifest, UTF-8, ready for {@link CastProxyServer#loadVideo(byte[])}. */
        public byte[] getMpdBytes() {
            return mMpdBytes;
        }

        public String getMimeType() {
            return MIME_TYPE;
        }

        /** Tallest surviving video representation (0 when none). */
        public int getMaxVideoHeight() {
            return mMaxVideoHeight;
        }

        /** At least one avc1 representation (&le;1080p) survived. */
        public boolean hasCompatibleVideo() {
            return mHasCompatibleVideo;
        }

        /** The manifest will play sound only (audio survived, no compatible video). */
        public boolean isAudioOnly() {
            return !mHasCompatibleVideo && mHasAudio;
        }

        /**
         * The source HAD video but none of it is castable (no avc1 &le;1080p) - the integrator
         * should surface "can't direct-cast this video" instead of silently casting audio.
         */
        public boolean isVideoDroppedForCompatibility() {
            return mSourceHadVideo && !mHasCompatibleVideo;
        }

        /** Nothing survived at all; the manifest is unplayable and must not be cast. */
        public boolean isEmpty() {
            return !mHasCompatibleVideo && !mHasAudio;
        }
    }

    /**
     * @param originalMpd  the stream from {@code MediaItemFormatInfo.createMpdStream()} (consumed
     *                     and closed here)
     * @param localBaseUrl the proxy's base, e.g. {@code http://192.168.1.23:40123} (trailing slash
     *                     tolerated)
     * @throws IOException on malformed XML - callers treat it as "can't direct-cast"
     */
    public static Result rewrite(InputStream originalMpd, String localBaseUrl) throws IOException {
        return rewrite(originalMpd, localBaseUrl, MAX_VIDEO_HEIGHT);
    }

    /**
     * Rewrite with a user-selected maximum video height. Direct cast keeps DASH adaptation below
     * the cap instead of pinning one representation, so the TV can still step down when its
     * connection needs it. Values {@code <= 0} mean the universal 1080p automatic ceiling.
     */
    public static Result rewrite(InputStream originalMpd, String localBaseUrl,
                                 int requestedMaxVideoHeight) throws IOException {
        String xml = readFully(originalMpd);
        String base = localBaseUrl.endsWith("/")
                ? localBaseUrl.substring(0, localBaseUrl.length() - 1) : localBaseUrl;
        int videoHeightCap = requestedMaxVideoHeight > 0
                ? Math.min(requestedMaxVideoHeight, MAX_VIDEO_HEIGHT) : MAX_VIDEO_HEIGHT;

        MpdXml.Document doc = MpdXml.parse(xml);
        if (!"MPD".equals(doc.root.name)) {
            throw new IOException("Not an MPD document: <" + doc.root.name + ">");
        }

        int maxHeight = 0;
        boolean sourceHadVideo = false;
        boolean hasVideo = false;
        boolean hasAudio = false;

        for (MpdXml.Element period : doc.root.childElements("Period")) {
            keepOnlyOriginalAudio(period);
            Iterator<Object> setIterator = period.children.iterator();
            while (setIterator.hasNext()) {
                Object child = setIterator.next();
                if (!(child instanceof MpdXml.Element)
                        || !"AdaptationSet".equals(((MpdXml.Element) child).name)) {
                    continue;
                }
                MpdXml.Element adaptationSet = (MpdXml.Element) child;

                Iterator<Object> repIterator = adaptationSet.children.iterator();
                int keptInSet = 0;
                while (repIterator.hasNext()) {
                    Object repChild = repIterator.next();
                    if (!(repChild instanceof MpdXml.Element)
                            || !"Representation".equals(((MpdXml.Element) repChild).name)) {
                        continue;
                    }
                    MpdXml.Element rep = (MpdXml.Element) repChild;
                    boolean isVideo = rep.rawAttr("height") != null;
                    if (isVideo) {
                        sourceHadVideo = true;
                    }

                    if (!isCastable(rep, isVideo, videoHeightCap)) {
                        repIterator.remove();
                        continue;
                    }

                    rewriteUrls(rep, base);
                    keptInSet++;
                    if (isVideo) {
                        hasVideo = true;
                        maxHeight = Math.max(maxHeight, parseIntSafe(rep.attr("height")));
                    } else {
                        hasAudio = true;
                    }
                }

                if (keptInSet == 0) {
                    setIterator.remove();
                }
            }
        }

        byte[] bytes = MpdXml.serialize(doc).getBytes(StandardCharsets.UTF_8);
        ProxyLog.d(TAG, "rewrite: cap=" + videoHeightCap + " maxHeight=" + maxHeight + " video=" + hasVideo
                + " audio=" + hasAudio + " sourceHadVideo=" + sourceHadVideo
                + " bytes=" + bytes.length);
        return new Result(bytes, maxHeight, hasVideo, sourceHadVideo, hasAudio);
    }

    /**
     * YouTube ships auto-dub audio languages as extra AdaptationSets, and the Default Media
     * Receiver ignores DASH roles when free-picking a track (observed live: it chose a Portuguese
     * auto-dub - which, being TTS-generated, also sounds artifact-y/sped-up). Keep exactly ONE
     * audio set: the original among the {@code audio/mp4} sets (YouTubeMPDBuilder gives only the
     * original {@code Role=main}; dubs get dub/alternate/description), else the first audio/mp4
     * set. The pick is restricted to audio/mp4 because webm/opus sets are codec-dropped later -
     * electing a webm "original" here would leave the manifest with no audio at all.
     */
    private static void keepOnlyOriginalAudio(MpdXml.Element period) {
        MpdXml.Element keep = null;
        for (MpdXml.Element set : period.childElements("AdaptationSet")) {
            String mime = set.attr("mimeType");
            if (mime == null || !mime.startsWith("audio/mp4")) {
                continue;
            }
            if (keep == null) {
                keep = set;
            }
            MpdXml.Element role = set.firstChild("Role");
            if (role != null && "main".equals(role.attr("value"))) {
                keep = set;
                break;
            }
        }
        if (keep == null) {
            return; // no audio/mp4 at all - leave the codec filter to do its usual thing
        }
        Iterator<Object> iterator = period.children.iterator();
        while (iterator.hasNext()) {
            Object child = iterator.next();
            if (!(child instanceof MpdXml.Element)
                    || !"AdaptationSet".equals(((MpdXml.Element) child).name)) {
                continue;
            }
            MpdXml.Element set = (MpdXml.Element) child;
            String mime = set.attr("mimeType");
            if (mime != null && mime.startsWith("audio") && set != keep) {
                ProxyLog.d(TAG, "dropping non-original audio set lang=" + set.attr("lang")
                        + " role=" + (set.firstChild("Role") != null ? set.firstChild("Role").attr("value") : null));
                iterator.remove();
            }
        }
    }

    /** v1 keep policy: avc1 video capped at 1080p, mp4a audio; everything else (and OTF) drops. */
    private static boolean isCastable(MpdXml.Element rep, boolean isVideo, int videoHeightCap) {
        String codecs = rep.attr("codecs");
        if (codecs == null) {
            return false;
        }
        codecs = codecs.toLowerCase(Locale.US);
        // SegmentTemplate = OTF/live: media URL is a $Number$ template, incompatible with
        // self-contained tokens (and live is post-v1 anyway).
        if (rep.firstChild("SegmentTemplate") != null) {
            return false;
        }
        if (isVideo) {
            return codecs.startsWith("avc1") && parseIntSafe(rep.attr("height")) <= videoHeightCap;
        }
        // Non-video: only AAC survives; subtitle codecs (wvtt/stpp) and opus fail this check.
        return codecs.startsWith("mp4a");
    }

    /** Rewrites every absolute URL the builder emits for a VOD representation. */
    private static void rewriteUrls(MpdXml.Element rep, String base) {
        MpdXml.Element baseUrl = rep.firstChild("BaseURL");
        if (baseUrl != null) {
            String url = baseUrl.text().trim();
            if (isAbsolute(url)) {
                baseUrl.setText(base + "/seg/" + SegmentToken.encode(url));
            }
        }
        MpdXml.Element segmentList = rep.firstChild("SegmentList");
        if (segmentList != null) {
            MpdXml.Element initialization = segmentList.firstChild("Initialization");
            if (initialization != null) {
                String url = initialization.attr("sourceURL");
                if (isAbsolute(url)) {
                    initialization.setAttr("sourceURL", base + "/seg/" + SegmentToken.encode(url));
                }
            }
            for (MpdXml.Element segmentUrl : segmentList.childElements("SegmentURL")) {
                String url = segmentUrl.attr("media");
                if (isAbsolute(url)) {
                    segmentUrl.setAttr("media", base + "/seg/" + SegmentToken.encode(url));
                }
            }
        }
        // SegmentBase/Initialization@range: byte range, NOT a URL - never rewritten.
    }

    private static boolean isAbsolute(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private static int parseIntSafe(String value) {
        try {
            return value != null ? Integer.parseInt(value.trim()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String readFully(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 * 1024);
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try {
                in.close();
            } catch (IOException e) {
                // best effort
            }
        }
    }
}
