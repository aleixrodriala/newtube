package com.newtube.mobile.ui.dialog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.tv.R;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class VideoMenuLayoutTest {
    @Test
    public void referenceOrderKeepsCallbacksAndMovesEveryExtraToMore() {
        int[] calls = {0};
        OptionCategory play = action(R.string.play_next, calls);
        OptionCategory later = action(R.string.add_video_to_watch_later, calls);
        OptionCategory playlist = action(R.string.dialog_add_to_playlist, calls);
        OptionCategory download = action(R.string.dialog_download, calls);
        OptionCategory share = action(R.string.share_link, calls);
        OptionCategory notInterested = action(R.string.not_interested, calls);
        OptionCategory extra = action(-1, calls);
        OptionCategory extraSwitch = OptionCategory.singleSwitch(UiOptionItem.from("Extra switch", item -> calls[0]++));
        List<OptionCategory> original = Arrays.asList(extra, share, playlist, notInterested, extraSwitch, later, download, play);
        List<OptionCategory> opened = new ArrayList<>();
        List<OptionCategory> primary = VideoMenuLayout.primary(original, "More", opened::addAll);
        assertEquals(Arrays.asList(play, later, playlist, download, share, notInterested), primary.subList(0, 6));
        assertEquals(7, primary.size());
        assertSame(play.options.get(0), primary.get(0).options.get(0));
        primary.get(0).options.get(0).onSelect(true);
        assertEquals(1, calls[0]);
        primary.get(6).options.get(0).onSelect(true);
        assertEquals(Arrays.asList(extra, extraSwitch), opened);
        opened.get(0).options.get(0).onSelect(true);
        assertEquals(2, calls[0]);
        assertEquals(8, original.size()); // Presentation never mutates the presenter's backup.
    }

    @Test
    public void unavailableActionsAreNotInventedAndEmptyMoreIsOmitted() {
        OptionCategory download = action(R.string.dialog_download, new int[1]);
        OptionCategory play = action(R.string.play_next, new int[1]);
        List<OptionCategory> primary = VideoMenuLayout.primary(Arrays.asList(download, play), "More",
                extras -> { throw new AssertionError("No extras exist"); });
        assertEquals(Arrays.asList(play, download), primary);
        assertTrue(VideoMenuLayout.primary(new ArrayList<>(), "More", extras -> { }).isEmpty());
    }

    private static OptionCategory action(int id, int[] calls) {
        return OptionCategory.from(id, OptionCategory.TYPE_SINGLE_BUTTON, null,
                UiOptionItem.from("Same localized label", item -> calls[0]++));
    }
}
