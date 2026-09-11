package com.newtube.mobile.downloads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class DownloadOptionsTest {
    private static final FakeMediaFormat AAC = FakeMediaFormat.audio("140", 129_000, 5_000_000);

    @Test
    public void offersOneAvcRungPerHeightTallestFirstPlusAudio() {
        List<MediaFormat> adaptive = Arrays.asList(
                FakeMediaFormat.video("134", 360, 400_000, 20_000_000),
                FakeMediaFormat.video("137", 1080, 2_500_000, 120_000_000),
                FakeMediaFormat.video("136", 720, 1_200_000, 60_000_000),
                FakeMediaFormat.vp9("248", 1080, 2_000_000, 100_000_000),
                FakeMediaFormat.vp9("271", 1440, 5_000_000, 300_000_000),
                FakeMediaFormat.opus("251", 140_000, 6_000_000),
                AAC);

        List<DownloadOption> options = DownloadOptions.build(adaptive, null);

        assertEquals(4, options.size());
        assertEquals("1080p", options.get(0).qualityLabel);
        assertEquals("720p", options.get(1).qualityLabel);
        assertEquals("360p", options.get(2).qualityLabel);
        assertTrue(options.get(3).isAudioOnly());
        assertEquals(125_000_000L, options.get(0).totalBytes);
        assertEquals(5_000_000L, options.get(3).totalBytes);
        assertSame(AAC, options.get(0).audio);
        assertFalse(options.get(0).isProgressive());
    }

    @Test
    public void sameHeightKeepsTheHigherBitrateVariant() {
        FakeMediaFormat p60 = FakeMediaFormat.video("299", 1080, 4_000_000, 200_000_000).label("1080p60");
        List<MediaFormat> adaptive = Arrays.asList(
                FakeMediaFormat.video("137", 1080, 2_500_000, 120_000_000), p60, AAC);

        List<DownloadOption> options = DownloadOptions.build(adaptive, null);

        assertSame(p60, options.get(0).video);
        assertEquals("1080p60", options.get(0).qualityLabel);
    }

    @Test
    public void prefersTheOriginalAudioTrackOverDubsAndDrc() {
        FakeMediaFormat dubbed = FakeMediaFormat.audio("140", 129_000, 5_000_000).track("es-ES.10");
        FakeMediaFormat original = FakeMediaFormat.audio("140", 129_000, 5_000_000).track("en.4");
        FakeMediaFormat drc = FakeMediaFormat.audio("140", 200_000, 5_000_000).track("en.4").drc();

        assertSame(original, DownloadOptions.pickAudio(Arrays.asList(dubbed, drc, original)));
        assertSame(dubbed, DownloadOptions.pickAudio(Collections.singletonList(dubbed)));
        assertNull(DownloadOptions.pickAudio(Collections.singletonList(FakeMediaFormat.opus("251", 1, 1))));
    }

    @Test
    public void fallsBackToProgressiveStreamsWhenNoSeparateAvcTrack() {
        List<MediaFormat> adaptive = Arrays.asList(FakeMediaFormat.vp9("248", 1080, 2_000_000, 1), AAC);
        List<MediaFormat> progressive = Arrays.asList(
                FakeMediaFormat.video("18", 360, 500_000, 30_000_000),
                FakeMediaFormat.video("22", 720, 1_500_000, 90_000_000));

        List<DownloadOption> options = DownloadOptions.build(adaptive, progressive);

        assertEquals(3, options.size());
        assertTrue(options.get(0).isProgressive());
        assertEquals(720, options.get(0).height);
        assertEquals(90_000_000L, options.get(0).totalBytes);
        assertTrue(options.get(2).isAudioOnly());
    }

    @Test
    public void unknownLengthMakesTheTotalUnknown() {
        List<MediaFormat> adaptive = Arrays.asList(
                FakeMediaFormat.video("137", 1080, 2_500_000, -1), AAC);

        assertEquals(-1, DownloadOptions.build(adaptive, null).get(0).totalBytes);
    }

    @Test
    public void nothingUsableGivesNoOptions() {
        assertTrue(DownloadOptions.build(null, null).isEmpty());
        assertTrue(DownloadOptions.build(Collections.singletonList(FakeMediaFormat.vp9("248", 1080, 1, 1)), null).isEmpty());
    }

    @Test
    public void formatsBytesLikeTheFileManager() {
        assertEquals("", DownloadOptions.formatBytes(-1));
        assertEquals("512 B", DownloadOptions.formatBytes(512));
        assertEquals("4.2 MB", DownloadOptions.formatBytes(4_200_000));
        assertEquals("245 MB", DownloadOptions.formatBytes(245_000_000));
        assertEquals("1.3 GB", DownloadOptions.formatBytes(1_300_000_000));
    }

    @Test
    public void formatsDurationLikeTheCardBadge() {
        assertEquals("0:07", DownloadOptions.formatDuration(7_400));
        assertEquals("12:34", DownloadOptions.formatDuration(754_000));
        assertEquals("1:02:03", DownloadOptions.formatDuration(3_723_000));
    }

    @Test
    public void fileNamesAreSafeAndBounded() {
        assertEquals("Why not A talk about C C++ (part 1).mp4",
                DownloadOptions.fileNameFor("Why not? A talk about C/C++ (part 1)", "abc", "mp4"));
        assertEquals("abc.m4a", DownloadOptions.fileNameFor("   ", "abc", "m4a"));
        assertEquals("abc.mp4", DownloadOptions.fileNameFor(null, "abc", "mp4"));
        String longTitle = new String(new char[300]).replace('\0', 'x');
        assertEquals(120 + ".mp4".length(), DownloadOptions.fileNameFor(longTitle, "abc", "mp4").length());
        assertFalse(DownloadOptions.fileNameFor("a\tb\nc:d|e", "abc", "mp4").matches(".*[\\t\\n:|].*"));
    }
}
