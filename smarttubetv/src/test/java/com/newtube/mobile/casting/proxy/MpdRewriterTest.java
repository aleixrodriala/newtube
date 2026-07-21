package com.newtube.mobile.casting.proxy;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The sample manifests mirror the exact shape YouTubeMPDBuilder emits for VOD: kxml prolog,
 * MPD/Period prologue attributes, AdaptationSet-per-mime-group with a Role tag, Representation
 * with itag id + codecs + bandwidth, BaseURL text carrying the absolute (entity-escaped)
 * googlevideo URL with an optional yt:contentLength attribute, and either SegmentBase
 * (indexRange + Initialization@range) or SegmentList (Initialization@sourceURL + SegmentURL@media).
 */
public class MpdRewriterTest {
    private static final String LOCAL_BASE = "http://192.168.1.23:40123";

    private static final String VIDEO_1080_URL =
            "https://rr4---sn-25ge7nsd.googlevideo.com/videoplayback?expire=1721400000&ei=aB3cD"
                    + "&ip=203.0.113.7&id=o-AXYZ&itag=137&aitags=133%2C134&source=youtube"
                    + "&mime=video%2Fmp4&gir=yes&clen=123456789&dur=634.007&lmt=1700000000000000"
                    + "&sig=AJfQdSswRQIgXyZ&lsparams=met%2Cmh%2Cmm&mh=abc";
    private static final String VIDEO_720_URL =
            "https://rr4---sn-25ge7nsd.googlevideo.com/videoplayback?itag=136&mime=video%2Fmp4&clen=555";
    private static final String VIDEO_2160_URL =
            "https://rr4---sn-25ge7nsd.googlevideo.com/videoplayback?itag=266&mime=video%2Fmp4&clen=999";
    private static final String VIDEO_VP9_URL =
            "https://rr4---sn-25ge7nsd.googlevideo.com/videoplayback?itag=248&mime=video%2Fwebm&clen=777";
    private static final String AUDIO_AAC_URL =
            "https://rr4---sn-25ge7nsd.googlevideo.com/videoplayback?itag=140&mime=audio%2Fmp4&clen=10193051";
    private static final String AUDIO_OPUS_URL =
            "https://rr4---sn-25ge7nsd.googlevideo.com/videoplayback?itag=251&mime=audio%2Fwebm&clen=888";
    private static final String SUBS_URL =
            "https://www.youtube.com/api/timedtext?v=abc123&lang=en&fmt=vtt";

    private static String fullMpd() {
        return "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n"
                + "<MPD xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
                + " xmlns=\"urn:mpeg:DASH:schema:MPD:2011\""
                + " xmlns:yt=\"http://youtube.com/yt/2012/10/10\""
                + " xsi:schemaLocation=\"urn:mpeg:DASH:schema:MPD:2011 DASH-MPD.xsd\""
                + " minBufferTime=\"PT1.500S\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\""
                + " type=\"static\" mediaPresentationDuration=\"PT634S\">\n"
                + "  <Period duration=\"PT634S\">\n"
                // --- video/mp4 group ---
                + "    <AdaptationSet id=\"0\" mimeType=\"video/mp4\" subsegmentAlignment=\"true\">\n"
                + "      <Role schemeIdUri=\"urn:mpeg:DASH:role:2011\" value=\"main\" />\n"
                + "      <Representation id=\"266\" codecs=\"avc1.640033\" startWithSAP=\"1\""
                + " bandwidth=\"18000000\" width=\"3840\" height=\"2160\" maxPlayoutRate=\"1\" frameRate=\"24\">\n"
                + "        <BaseURL yt:contentLength=\"999\">" + escape(VIDEO_2160_URL) + "</BaseURL>\n"
                + "        <SegmentBase indexRange=\"705-1244\" indexRangeExact=\"true\">\n"
                + "          <Initialization range=\"0-704\" />\n"
                + "        </SegmentBase>\n"
                + "      </Representation>\n"
                + "      <Representation id=\"137\" codecs=\"avc1.640028\" startWithSAP=\"1\""
                + " bandwidth=\"4318573\" width=\"1920\" height=\"1080\" maxPlayoutRate=\"1\" frameRate=\"24\">\n"
                + "        <BaseURL yt:contentLength=\"123456789\">" + escape(VIDEO_1080_URL) + "</BaseURL>\n"
                + "        <SegmentBase indexRange=\"705-1244\" indexRangeExact=\"true\">\n"
                + "          <Initialization range=\"0-704\" />\n"
                + "        </SegmentBase>\n"
                + "      </Representation>\n"
                + "      <Representation id=\"136\" codecs=\"avc1.4d401f\" startWithSAP=\"1\""
                + " bandwidth=\"2000000\" width=\"1280\" height=\"720\" maxPlayoutRate=\"1\" frameRate=\"24\">\n"
                + "        <BaseURL yt:contentLength=\"555\">" + escape(VIDEO_720_URL) + "</BaseURL>\n"
                + "        <SegmentList>\n"
                + "          <Initialization sourceURL=\"" + escape(VIDEO_720_URL + "&sq=0") + "\" />\n"
                + "          <SegmentURL media=\"" + escape(VIDEO_720_URL + "&sq=1") + "\" />\n"
                + "          <SegmentURL media=\"" + escape(VIDEO_720_URL + "&sq=2") + "\" />\n"
                + "        </SegmentList>\n"
                + "      </Representation>\n"
                + "    </AdaptationSet>\n"
                // --- video/webm group (vp9) ---
                + "    <AdaptationSet id=\"1\" mimeType=\"video/webm\" subsegmentAlignment=\"true\">\n"
                + "      <Role schemeIdUri=\"urn:mpeg:DASH:role:2011\" value=\"main\" />\n"
                + "      <Representation id=\"248\" codecs=\"vp9\" startWithSAP=\"1\""
                + " bandwidth=\"3000000\" width=\"1920\" height=\"1080\" maxPlayoutRate=\"1\" frameRate=\"24\">\n"
                + "        <BaseURL yt:contentLength=\"777\">" + escape(VIDEO_VP9_URL) + "</BaseURL>\n"
                + "        <SegmentBase indexRange=\"219-800\" indexRangeExact=\"true\">\n"
                + "          <Initialization range=\"0-218\" />\n"
                + "        </SegmentBase>\n"
                + "      </Representation>\n"
                + "    </AdaptationSet>\n"
                // --- audio/mp4 group ---
                + "    <AdaptationSet id=\"2\" mimeType=\"audio/mp4\" lang=\"en\" subsegmentAlignment=\"true\">\n"
                + "      <Role schemeIdUri=\"urn:mpeg:DASH:role:2011\" value=\"main\" />\n"
                + "      <Representation id=\"140\" codecs=\"mp4a.40.2\" startWithSAP=\"1\""
                + " bandwidth=\"129478\" audioSamplingRate=\"44100\">\n"
                + "        <BaseURL yt:contentLength=\"10193051\">" + escape(AUDIO_AAC_URL) + "</BaseURL>\n"
                + "        <SegmentBase indexRange=\"592-1332\" indexRangeExact=\"true\">\n"
                + "          <Initialization range=\"0-591\" />\n"
                + "        </SegmentBase>\n"
                + "      </Representation>\n"
                + "    </AdaptationSet>\n"
                // --- audio/webm group (opus) ---
                + "    <AdaptationSet id=\"3\" mimeType=\"audio/webm\" lang=\"en\" subsegmentAlignment=\"true\">\n"
                + "      <Role schemeIdUri=\"urn:mpeg:DASH:role:2011\" value=\"main\" />\n"
                + "      <Representation id=\"251\" codecs=\"opus\" startWithSAP=\"1\""
                + " bandwidth=\"140000\" audioSamplingRate=\"48000\">\n"
                + "        <BaseURL yt:contentLength=\"888\">" + escape(AUDIO_OPUS_URL) + "</BaseURL>\n"
                + "      </Representation>\n"
                + "    </AdaptationSet>\n"
                // --- subtitles ---
                + "    <AdaptationSet id=\"4\" mimeType=\"text/vtt\" lang=\"en\">\n"
                + "      <Role schemeIdUri=\"urn:mpeg:DASH:role:2011\" value=\"subtitle\" />\n"
                + "      <Representation id=\"5\" bandwidth=\"268\" codecs=\"wvtt\">\n"
                + "        <BaseURL>" + escape(SUBS_URL) + "</BaseURL>\n"
                + "      </Representation>\n"
                + "    </AdaptationSet>\n"
                + "  </Period>\n"
                + "</MPD>";
    }

    private static String escape(String url) {
        return url.replace("&", "&amp;");
    }

    private static InputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static MpdXml.Element findRepresentation(MpdXml.Document doc, String id) {
        for (MpdXml.Element period : doc.root.childElements("Period")) {
            for (MpdXml.Element set : period.childElements("AdaptationSet")) {
                for (MpdXml.Element rep : set.childElements("Representation")) {
                    if (id.equals(rep.attr("id"))) {
                        return rep;
                    }
                }
            }
        }
        return null;
    }

    @Test
    public void keepsOnlyAvc1AndAacUpTo1080p() throws IOException {
        MpdRewriter.Result result = MpdRewriter.rewrite(stream(fullMpd()), LOCAL_BASE);
        MpdXml.Document doc = MpdXml.parse(new String(result.getMpdBytes(), StandardCharsets.UTF_8));

        assertNotNull(findRepresentation(doc, "137")); // avc1 1080p
        assertNotNull(findRepresentation(doc, "136")); // avc1 720p
        assertNotNull(findRepresentation(doc, "140")); // mp4a

        assertNull(findRepresentation(doc, "266")); // avc1 2160p - over the cap
        assertNull(findRepresentation(doc, "248")); // vp9
        assertNull(findRepresentation(doc, "251")); // opus
        assertNull(findRepresentation(doc, "5"));   // subtitles

        // Emptied AdaptationSets disappear entirely: only video/mp4 + audio/mp4 remain.
        List<MpdXml.Element> sets = doc.root.childElements("Period").get(0).childElements("AdaptationSet");
        assertEquals(2, sets.size());

        assertTrue(result.hasCompatibleVideo());
        assertFalse(result.isAudioOnly());
        assertFalse(result.isVideoDroppedForCompatibility());
        assertFalse(result.isEmpty());
        assertEquals(1080, result.getMaxVideoHeight());
        assertEquals("application/dash+xml", result.getMimeType());
    }

    @Test
    public void rewritesBaseUrlToLocalTokenReversibly() throws IOException {
        MpdRewriter.Result result = MpdRewriter.rewrite(stream(fullMpd()), LOCAL_BASE);
        MpdXml.Document doc = MpdXml.parse(new String(result.getMpdBytes(), StandardCharsets.UTF_8));

        String rewritten = findRepresentation(doc, "137").firstChild("BaseURL").text();
        assertTrue(rewritten.startsWith(LOCAL_BASE + "/seg/"));
        String token = rewritten.substring((LOCAL_BASE + "/seg/").length());
        // Reversible: the token decodes to the ORIGINAL absolute URL, real ampersands intact.
        assertEquals(VIDEO_1080_URL, SegmentToken.decode(token));
    }

    @Test
    public void rewritesSegmentListUrlsAndKeepsByteRangesUntouched() throws IOException {
        MpdRewriter.Result result = MpdRewriter.rewrite(stream(fullMpd()), LOCAL_BASE);
        MpdXml.Document doc = MpdXml.parse(new String(result.getMpdBytes(), StandardCharsets.UTF_8));

        MpdXml.Element rep136 = findRepresentation(doc, "136");
        MpdXml.Element segmentList = rep136.firstChild("SegmentList");
        String init = segmentList.firstChild("Initialization").attr("sourceURL");
        assertTrue(init.startsWith(LOCAL_BASE + "/seg/"));
        assertEquals(VIDEO_720_URL + "&sq=0",
                SegmentToken.decode(init.substring((LOCAL_BASE + "/seg/").length())));

        List<MpdXml.Element> segmentUrls = segmentList.childElements("SegmentURL");
        assertEquals(2, segmentUrls.size());
        for (int i = 0; i < segmentUrls.size(); i++) {
            String media = segmentUrls.get(i).attr("media");
            assertTrue(media.startsWith(LOCAL_BASE + "/seg/"));
            assertEquals(VIDEO_720_URL + "&sq=" + (i + 1),
                    SegmentToken.decode(media.substring((LOCAL_BASE + "/seg/").length())));
        }

        // SegmentBase byte ranges are NOT URLs and must survive verbatim (range info rides the
        // Range header only - GOOGLEVIDEO_RANGE_QUERY post-mortem).
        MpdXml.Element rep137 = findRepresentation(doc, "137");
        MpdXml.Element segmentBase = rep137.firstChild("SegmentBase");
        assertEquals("705-1244", segmentBase.attr("indexRange"));
        assertEquals("0-704", segmentBase.firstChild("Initialization").attr("range"));
        // yt:contentLength on BaseURL survives too.
        assertEquals("123456789", rep137.firstChild("BaseURL").attr("yt:contentLength"));
    }

    @Test
    public void prologueAndStructureSurviveRewrite() throws IOException {
        MpdRewriter.Result result = MpdRewriter.rewrite(stream(fullMpd()), LOCAL_BASE);
        String xml = new String(result.getMpdBytes(), StandardCharsets.UTF_8);

        assertTrue(xml.startsWith("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>"));
        assertTrue(xml.contains("mediaPresentationDuration=\"PT634S\""));
        assertTrue(xml.contains("type=\"static\""));
        assertTrue(xml.contains("<Role schemeIdUri=\"urn:mpeg:DASH:role:2011\" value=\"main\" />"));
        // No googlevideo URL leaks through un-rewritten.
        assertFalse(xml.contains("googlevideo.com"));
    }

    @Test
    public void noAvc1VideoIsFlaggedForTheIntegrator() throws IOException {
        String vp9Only = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n"
                + "<MPD type=\"static\" mediaPresentationDuration=\"PT100S\">\n"
                + "  <Period duration=\"PT100S\">\n"
                + "    <AdaptationSet id=\"0\" mimeType=\"video/webm\">\n"
                + "      <Representation id=\"248\" codecs=\"vp9\" bandwidth=\"3000000\""
                + " width=\"1920\" height=\"1080\">\n"
                + "        <BaseURL>" + escape(VIDEO_VP9_URL) + "</BaseURL>\n"
                + "      </Representation>\n"
                + "    </AdaptationSet>\n"
                + "    <AdaptationSet id=\"1\" mimeType=\"audio/mp4\">\n"
                + "      <Representation id=\"140\" codecs=\"mp4a.40.2\" bandwidth=\"129478\""
                + " audioSamplingRate=\"44100\">\n"
                + "        <BaseURL>" + escape(AUDIO_AAC_URL) + "</BaseURL>\n"
                + "      </Representation>\n"
                + "    </AdaptationSet>\n"
                + "  </Period>\n"
                + "</MPD>";

        MpdRewriter.Result result = MpdRewriter.rewrite(stream(vp9Only), LOCAL_BASE);
        assertFalse(result.hasCompatibleVideo());
        assertTrue(result.isVideoDroppedForCompatibility()); // "can't direct-cast this video"
        assertTrue(result.isAudioOnly());
        assertFalse(result.isEmpty());
        assertEquals(0, result.getMaxVideoHeight());
    }

    @Test
    public void otfSegmentTemplateRepresentationsAreDropped() throws IOException {
        String otf = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n"
                + "<MPD type=\"static\" mediaPresentationDuration=\"PT100S\">\n"
                + "  <Period duration=\"PT100S\">\n"
                + "    <AdaptationSet id=\"0\" mimeType=\"video/mp4\">\n"
                + "      <Representation id=\"134\" codecs=\"avc1.4d401e\" bandwidth=\"1000000\""
                + " width=\"854\" height=\"480\">\n"
                + "        <SegmentTemplate timescale=\"1000\""
                + " media=\"" + escape(VIDEO_720_URL + "&sq=$Number$") + "\""
                + " initialization=\"" + escape(VIDEO_720_URL + "&sq=0") + "\" startNumber=\"1\">\n"
                + "          <SegmentTimeline><S t=\"0\" d=\"5100\" r=\"19\" /></SegmentTimeline>\n"
                + "        </SegmentTemplate>\n"
                + "      </Representation>\n"
                + "    </AdaptationSet>\n"
                + "  </Period>\n"
                + "</MPD>";

        MpdRewriter.Result result = MpdRewriter.rewrite(stream(otf), LOCAL_BASE);
        assertFalse(result.hasCompatibleVideo());
        assertTrue(result.isEmpty());
    }

    @Test(expected = IOException.class)
    public void malformedXmlThrows() throws IOException {
        MpdRewriter.rewrite(stream("<MPD><Period></MPD>"), LOCAL_BASE);
    }

    @Test
    public void userQualityCapKeepsAdaptiveRungsAtOrBelowSelection() throws IOException {
        MpdRewriter.Result result = MpdRewriter.rewrite(stream(fullMpd()), LOCAL_BASE, 720);
        String xml = new String(result.getMpdBytes(), StandardCharsets.UTF_8);

        assertEquals(720, result.getMaxVideoHeight());
        assertTrue(xml.contains("id=\"136\""));
        assertFalse(xml.contains("id=\"137\""));
        assertFalse(xml.contains("id=\"266\""));
        assertTrue("Audio survives a video-quality change", xml.contains("id=\"140\""));
    }

    @Test
    public void automaticQualityStillUsesUniversalCeiling() throws IOException {
        MpdRewriter.Result result = MpdRewriter.rewrite(stream(fullMpd()), LOCAL_BASE, 0);

        assertEquals(MpdRewriter.MAX_VIDEO_HEIGHT, result.getMaxVideoHeight());
    }

    /**
     * Auto-dub layout (observed live: the receiver free-picked a Portuguese TTS dub): several
     * audio/mp4 sets, only the ORIGINAL carries Role=main - and it is deliberately NOT listed
     * first, so "keep the first" would be wrong. Only the main set may survive; the audio/webm
     * original must not be elected (it gets codec-dropped and would leave no audio).
     */
    @Test
    public void keepsOnlyOriginalAudioAmongDubs() throws IOException {
        String dubbed = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n"
                + "<MPD type=\"static\" mediaPresentationDuration=\"PT100S\">\n"
                + "  <Period duration=\"PT100S\">\n"
                + "    <AdaptationSet id=\"0\" mimeType=\"video/mp4\">\n"
                + "      <Representation id=\"136\" codecs=\"avc1.4d401f\" bandwidth=\"2000000\""
                + " width=\"1280\" height=\"720\">\n"
                + "        <BaseURL>" + escape(VIDEO_720_URL) + "</BaseURL>\n"
                + "      </Representation>\n"
                + "    </AdaptationSet>\n"
                + "    <AdaptationSet id=\"1\" mimeType=\"audio/mp4\" lang=\"pt\" label=\"pt (dubbed-auto)\">\n"
                + "      <Role schemeIdUri=\"urn:mpeg:DASH:role:2011\" value=\"dub\" />\n"
                + "      <Representation id=\"140-pt\" codecs=\"mp4a.40.2\" bandwidth=\"129000\">\n"
                + "        <BaseURL>" + escape(AUDIO_AAC_URL + "&xtags=lang%3Dpt") + "</BaseURL>\n"
                + "      </Representation>\n"
                + "    </AdaptationSet>\n"
                + "    <AdaptationSet id=\"2\" mimeType=\"audio/webm\" lang=\"es\" label=\"es (original)\">\n"
                + "      <Role schemeIdUri=\"urn:mpeg:DASH:role:2011\" value=\"main\" />\n"
                + "      <Representation id=\"251-es\" codecs=\"opus\" bandwidth=\"110000\">\n"
                + "        <BaseURL>" + escape(AUDIO_OPUS_URL) + "</BaseURL>\n"
                + "      </Representation>\n"
                + "    </AdaptationSet>\n"
                + "    <AdaptationSet id=\"3\" mimeType=\"audio/mp4\" lang=\"es\" label=\"es (original)\">\n"
                + "      <Role schemeIdUri=\"urn:mpeg:DASH:role:2011\" value=\"main\" />\n"
                + "      <Representation id=\"140-es\" codecs=\"mp4a.40.2\" bandwidth=\"129000\">\n"
                + "        <BaseURL>" + escape(AUDIO_AAC_URL) + "</BaseURL>\n"
                + "      </Representation>\n"
                + "    </AdaptationSet>\n"
                + "  </Period>\n"
                + "</MPD>";

        MpdRewriter.Result result = MpdRewriter.rewrite(stream(dubbed), LOCAL_BASE);
        String xml = new String(result.getMpdBytes(), java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(result.hasCompatibleVideo());
        assertFalse(xml.contains("dubbed-auto"));
        assertFalse(xml.contains("140-pt"));
        assertFalse(xml.contains("251-es")); // webm original codec-dropped, not elected
        assertTrue(xml.contains("140-es")); // the mp4a original survives
    }
}
