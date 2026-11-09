package com.newtube.mobile.player;

import static org.junit.Assert.*;

import android.app.Application;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import com.google.common.collect.ImmutableList;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.ExoFormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class Media3TrackAdapterVideoTest {
    private DefaultTrackSelector selector;
    private Media3TrackAdapter adapter;

    @Before public void setUp() {
        selector = new DefaultTrackSelector(RuntimeEnvironment.getApplication());
        adapter = new Media3TrackAdapter(selector);
    }

    @After public void tearDown() { selector.release(); }

    private static Tracks videoTracks(String groupId, float frameRate) {
        TrackGroup group = new TrackGroup(groupId,
                new Format.Builder().setId("1:").setSampleMimeType(MimeTypes.VIDEO_H264).setCodecs("avc1.640028")
                        .setWidth(426).setHeight(240).setFrameRate(frameRate).setAverageBitrate(300_000).build(),
                new Format.Builder().setId("1:").setSampleMimeType(MimeTypes.VIDEO_H264).setCodecs("avc1.640028")
                        .setWidth(1920).setHeight(1080).setFrameRate(frameRate).setAverageBitrate(4_000_000).build());
        return new Tracks(ImmutableList.of(new Tracks.Group(group, true,
                new int[] {C.FORMAT_HANDLED, C.FORMAT_HANDLED}, new boolean[] {true, true})));
    }

    private void assertPinned1080() {
        TrackSelectionOverride override = selector.getParameters().overrides.values().iterator().next();
        assertEquals(C.TRACK_TYPE_VIDEO, override.getType());
        assertEquals(1, override.trackIndices.size());
        assertEquals(1080, override.mediaTrackGroup.getFormat(override.trackIndices.get(0)).height);
    }

    @Test public void reusedHlsIdsRespectResolutionCodecAndFrameRateAcrossSources() {
        for (float frameRate : new float[] {25, Format.NO_VALUE}) {
            adapter.onTracksChanged(videoTracks("initial", frameRate));
            FormatItem choice = adapter.getFormats(FormatItem.TYPE_VIDEO).get(0);
            assertEquals("1:", choice.getFormatId());
            adapter.selectFormat(choice);
            assertPinned1080();
            adapter.onSourceChanged();
            adapter.onTracksChanged(videoTracks("replacement", 30));
            assertPinned1080();
            adapter.selectFormat(ExoFormatItem.from(choice.toString()));
            assertPinned1080();
        }
        Format avc = new Format.Builder().setId("1:").setSampleMimeType(MimeTypes.VIDEO_H264)
                .setCodecs("avc1.640028").setWidth(1920).setHeight(1080).setFrameRate(30).build();
        Format vp9 = avc.buildUpon().setSampleMimeType(MimeTypes.VIDEO_VP9).setCodecs("vp09.00.40.08").build();
        Format vp9High = vp9.buildUpon().setFrameRate(60).build();
        TrackGroup group = new TrackGroup("mixed", avc, vp9, vp9High);
        adapter.onTracksChanged(new Tracks(ImmutableList.of(new Tracks.Group(group, true,
                new int[] {C.FORMAT_HANDLED, C.FORMAT_HANDLED, C.FORMAT_HANDLED},
                new boolean[] {true, true, true}))));
        adapter.selectFormat(adapter.getFormats(FormatItem.TYPE_VIDEO).get(0));
        TrackSelectionOverride override = selector.getParameters().overrides.values().iterator().next();
        assertEquals(vp9High, override.mediaTrackGroup.getFormat(override.trackIndices.get(0)));
    }

    @Test public void savedResolutionSurvivesFallbackAndOnlyAutoClearsTheFixedPick() {
        FormatItem saved = ExoFormatItem.fromVideoSpec("1920,1080,60,vp9", true);
        adapter.selectFormat(ExoFormatItem.from(saved.toString()));
        adapter.onTracksChanged(videoTracks("initial", 25)); // AVC-only video, no VP9 or 60 fps
        assertPinned1080();

        adapter.onSourceChanged();
        Format lower = new Format.Builder().setId("136").setSampleMimeType(MimeTypes.VIDEO_H264)
                .setCodecs("avc1.640028").setWidth(1280).setHeight(720).setFrameRate(30).build();
        Format higher = lower.buildUpon().setId("264").setWidth(2560).setHeight(1440).build();
        TrackGroup group = new TrackGroup("no-1080", lower, higher);
        adapter.onTracksChanged(new Tracks(ImmutableList.of(new Tracks.Group(group, true,
                new int[] {C.FORMAT_HANDLED, C.FORMAT_HANDLED}, new boolean[] {true, true}))));
        TrackSelectionOverride override = selector.getParameters().overrides.values().iterator().next();
        assertEquals(lower, override.mediaTrackGroup.getFormat(override.trackIndices.get(0)));

        adapter.onSourceChanged();
        adapter.onTracksChanged(videoTracks("1080-again", 30));
        assertPinned1080(); // fallback must never overwrite the saved choice

        for (FormatItem auto : new FormatItem[] {FormatItem.VIDEO_FHD_VP9_60,
                ExoFormatItem.from(FormatItem.VIDEO_FHD_VP9_60.toString())}) {
            adapter.selectFormat(adapter.getFormats(FormatItem.TYPE_VIDEO).get(0));
            adapter.selectFormat(auto);
            assertTrue(selector.getParameters().overrides.isEmpty());
            assertEquals(1920, selector.getParameters().maxVideoHeight);
        }
    }
}
