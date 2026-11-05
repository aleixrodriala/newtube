package com.newtube.mobile.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * NEWTUBE(quality-pin): an explicit quality pick is the user's standing choice. The selector must
 * pin the exact rung while it exists, and when the rung is absent from the current stream (a
 * persisted pick carried to a video offering other rungs) it must CAP ABR at the picked size -
 * the old code left the Auto ceiling lifted (unbounded), so the player quietly ran 1080p while
 * the sheet still showed the user's 360p.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class Media3TrackAdapterVideoPinTest {
    private DefaultTrackSelector selector;
    private Media3TrackAdapter adapter;

    @Before
    public void setUp() {
        selector = new DefaultTrackSelector(RuntimeEnvironment.getApplication());
        adapter = new Media3TrackAdapter(selector);
    }

    @Test
    public void explicitRungPinsItsExactTrack() {
        adapter.onTracksChanged(tracks(video("137", 1920, 1080, "vp09.00.51.08", 30),
                video("18", 640, 360, "avc1.64001e", 30)));

        FormatItem rung360 = offeredRung(360);
        assertNotNull(rung360);
        adapter.selectFormat(rung360);

        TrackSelectionOverride override = videoOverride();
        assertEquals("18", override.mediaTrackGroup.getFormat(override.trackIndices.get(0)).id);
        // The pin replaces any ceiling: it selects exactly one track.
        assertEquals(Integer.MAX_VALUE, selector.getParameters().maxVideoWidth);
    }

    @Test
    public void pinSurvivesTracksChangeAndReResolves() {
        adapter.onTracksChanged(tracks(video("137", 1920, 1080, "vp09.00.51.08", 30),
                video("18", 640, 360, "avc1.64001e", 30)));
        adapter.selectFormat(offeredRung(360));

        // A manifest/period refresh rebuilds the Tracks: the pin must re-resolve, not drop.
        adapter.onTracksChanged(tracks(video("137", 1920, 1080, "vp09.00.51.08", 30),
                video("18", 640, 360, "avc1.64001e", 30)));

        assertEquals("18", videoOverride().mediaTrackGroup.getFormat(videoOverride().trackIndices.get(0)).id);
    }

    @Test
    public void missingRungCapsAbrAtThePickedSize() {
        // The video offers only 720p/1080p - the persisted 360p pick has no track to pin.
        adapter.onTracksChanged(tracks(video("137", 1920, 1080, "vp09.00.51.08", 30),
                video("136", 1280, 720, "avc1.640028", 30)));

        FormatItem persisted360 = ExoFormatItem.from(FormatItem.TYPE_VIDEO,
                TrackSelectorManager.RENDERER_INDEX_VIDEO, "18", "avc1.64001e",
                640, 360, 30f, null, false, 400_000, false);
        adapter.selectFormat(persisted360);

        assertNull("no track to pin", videoOverrideOrNull());
        // NEWTUBE(quality-pin): ABR is capped at the picked rung - it can fall under, never rise
        // above. The old fallback lifted the ceiling to MAX and the player ran 1080p.
        assertTrue(selector.getParameters().maxVideoWidth <= 640);
        assertTrue(selector.getParameters().maxVideoHeight <= 360);
    }

    // ---------------------------------------------------------------------------------

    private static Format video(String id, int width, int height, String codecs, int frameRate) {
        return new Format.Builder()
                .setId(id)
                .setSampleMimeType(codecs.startsWith("vp09") ? MimeTypes.VIDEO_VP9 : MimeTypes.VIDEO_H264)
                .setCodecs(codecs)
                .setWidth(width)
                .setHeight(height)
                .setFrameRate(frameRate)
                .build();
    }

    private static Tracks tracks(Format... formats) {
        List<Tracks.Group> groups = new ArrayList<>();
        for (Format format : formats) {
            TrackGroup trackGroup = new TrackGroup(format.id + "-group", format);
            int[] support = new int[trackGroup.length];
            Arrays.fill(support, C.FORMAT_HANDLED);
            groups.add(new Tracks.Group(trackGroup, true, support, new boolean[trackGroup.length]));
        }
        return new Tracks(ImmutableList.copyOf(groups));
    }

    private FormatItem offeredRung(int height) {
        for (FormatItem item : adapter.getFormats(TrackSelectorManager.RENDERER_INDEX_VIDEO)) {
            if (item.getHeight() == height) {
                return item;
            }
        }
        return null;
    }

    private TrackSelectionOverride videoOverride() {
        TrackSelectionOverride found = videoOverrideOrNull();
        assertNotNull("a video override", found);
        return found;
    }

    private TrackSelectionOverride videoOverrideOrNull() {
        for (TrackSelectionOverride override : selector.getParameters().overrides.values()) {
            if (override.getType() == C.TRACK_TYPE_VIDEO) {
                return override;
            }
        }
        return null;
    }
}
