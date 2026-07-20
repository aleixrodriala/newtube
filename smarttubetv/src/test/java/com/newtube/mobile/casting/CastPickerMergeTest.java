package com.newtube.mobile.casting;

import com.liskovsoft.mediaserviceinterfaces.data.CastScreen;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The saved-screen -> live-Cast-device merge core ({@link CastPickerSheet#findSavedScreenId}):
 * name is the ONLY feasible key (a Lounge screenId and a Cast host can't be correlated without an
 * mdx read), so the match rules - trimmed, case-insensitive, first hit wins, junk entries skipped
 * - are pinned here on the pure JVM.
 */
public class CastPickerMergeTest {

    @Test
    public void exactNameMatchDonatesScreenId() {
        assertEquals("id-1", CastPickerSheet.findSavedScreenId(
                Arrays.asList(new CastScreen("id-0", "Bedroom TV"), new CastScreen("id-1", "58PUS8517/12")),
                "58PUS8517/12"));
    }

    /** TV-code pairings store the TV's self-reported name - cosmetic case/space drift must not break the merge. */
    @Test
    public void matchIsCaseInsensitiveAndTrimmed() {
        assertEquals("id-1", CastPickerSheet.findSavedScreenId(
                Collections.singletonList(new CastScreen("id-1", "  living room tv ")),
                "Living Room TV"));
    }

    @Test
    public void noMatchLeavesSavedRowAlone() {
        assertNull(CastPickerSheet.findSavedScreenId(
                Collections.singletonList(new CastScreen("id-1", "Bedroom TV")),
                "Kitchen display"));
    }

    @Test
    public void nullOrBlankDeviceNameNeverMatches() {
        assertNull(CastPickerSheet.findSavedScreenId(
                Collections.singletonList(new CastScreen("id-1", "Bedroom TV")), null));
        assertNull(CastPickerSheet.findSavedScreenId(
                Collections.singletonList(new CastScreen("id-1", "   ")), "   "));
    }

    /** A saved entry without a usable screenId can't power the instant connect - skip it. */
    @Test
    public void screenWithoutIdIsSkipped() {
        assertEquals("id-2", CastPickerSheet.findSavedScreenId(
                Arrays.asList(
                        null,
                        new CastScreen(null, "Bedroom TV"),
                        new CastScreen("", "Bedroom TV"),
                        new CastScreen("id-2", "Bedroom TV")),
                "Bedroom TV"));
    }

    /** Duplicate names (the documented residual risk): deterministic first-hit-wins, no throw. */
    @Test
    public void duplicateNamesFirstMatchWins() {
        assertEquals("id-1", CastPickerSheet.findSavedScreenId(
                Arrays.asList(new CastScreen("id-1", "Bedroom TV"), new CastScreen("id-2", "Bedroom TV")),
                "Bedroom TV"));
    }
}
