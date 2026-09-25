package com.newtube.mobile;

import static org.junit.Assert.assertEquals;

import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** NEWTUBE(menu): Share lands right after Download, Block right after Play next, from any start. */
public class CardMenuMigrationTest {
    private static final long A = 1L << 50; // stand-ins for unrelated items
    private static final long B = 1L << 51;
    private static final long DOWNLOAD = MainUIData.MENU_ITEM_DOWNLOAD;
    private static final long SHARE = MainUIData.MENU_ITEM_SHARE_LINK;
    private static final long BLOCK = MainUIData.MENU_ITEM_BLOCK_CHANNEL;
    private static final long PLAY_NEXT = MainUIData.MENU_ITEM_PLAY_NEXT;

    @Test
    public void shareAfterDownloadWhenShareWasBelow() {
        assertEquals(Arrays.asList(A, DOWNLOAD, SHARE, B),
                CardMenuMigration.shareAfterDownload(Arrays.asList(A, DOWNLOAD, B, SHARE)));
    }

    @Test
    public void shareAfterDownloadWhenShareWasAbove() {
        // The case the first version got wrong: removing Share shifts Download up by one.
        assertEquals(Arrays.asList(A, DOWNLOAD, SHARE, B),
                CardMenuMigration.shareAfterDownload(Arrays.asList(SHARE, A, DOWNLOAD, B)));
    }

    @Test
    public void blockMovesDownBelowPlayNext() {
        assertEquals(Arrays.asList(A, PLAY_NEXT, BLOCK, B),
                CardMenuMigration.blockAfterPlayNext(Arrays.asList(BLOCK, A, PLAY_NEXT, B)));
    }

    @Test
    public void blockAlreadyBelowPlayNextStays() {
        List<Long> order = Arrays.asList(PLAY_NEXT, A, BLOCK, B);
        assertEquals(order, CardMenuMigration.blockAfterPlayNext(order));
    }

    @Test
    public void missingAnchorLeavesOrderAlone() {
        List<Long> order = Arrays.asList(A, SHARE, B);
        assertEquals(order, CardMenuMigration.shareAfterDownload(order));
    }
}
