package com.newtube.mobile.ui.playback;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DeferredPlaybackUiTest {
    @Test
    public void groupsStayQueryableWhileTheirAdapterUpdatesCoalesce() {
        DeferredPlaybackUi gate = new DeferredPlaybackUi();
        Map<String, List<String>> groups = new LinkedHashMap<>();
        List<List<String>> renders = new ArrayList<>();
        Runnable render = () -> {
            List<String> rows = new ArrayList<>();
            groups.values().forEach(rows::addAll);
            renders.add(rows);
        };

        groups.put("queue", new ArrayList<>(Arrays.asList("current", "next")));
        gate.renderWhenReady(render);
        groups.put("related", new ArrayList<>(Arrays.asList("first", "removed")));
        gate.renderWhenReady(render);
        groups.get("related").remove("removed");
        groups.get("related").add("appended");
        gate.renderWhenReady(render);

        assertEquals(Arrays.asList("current", "next"), groups.get("queue"));
        assertEquals(Arrays.asList("first", "appended"), groups.get("related"));
        assertTrue(renders.isEmpty());
        assertFalse(gate.isReleased());

        gate.release();
        assertEquals(1, renders.size());
        assertEquals(Arrays.asList("current", "next", "first", "appended"), renders.get(0));
        assertTrue(gate.isReleased());
    }

    @Test
    public void newVideoDropsOldRenderAndWaitsForItsOwnRelease() {
        DeferredPlaybackUi gate = new DeferredPlaybackUi();
        List<String> renders = new ArrayList<>();
        gate.renderWhenReady(() -> renders.add("old"));
        gate.reset();
        gate.renderWhenReady(() -> renders.add("new"));
        assertTrue(renders.isEmpty());
        gate.release();
        assertEquals(Arrays.asList("new"), renders);

        gate.reset();
        assertFalse(gate.isReleased());
        gate.renderWhenReady(() -> renders.add("third"));
        assertEquals(1, renders.size());
        gate.release();
        assertEquals(Arrays.asList("new", "third"), renders);
    }

    @Test
    public void clearDropsQueuedRowsWithoutRelockingAnAlreadyReleasedVideo() {
        DeferredPlaybackUi gate = new DeferredPlaybackUi();
        List<String> renders = new ArrayList<>();
        gate.renderWhenReady(() -> renders.add("cleared"));
        gate.cancelPending();
        gate.release();
        assertTrue(renders.isEmpty());

        gate.cancelPending();
        gate.renderWhenReady(() -> renders.add("replacement"));
        assertEquals(Arrays.asList("replacement"), renders);
    }

    @Test
    public void noFrameFallbackAllowsLaterSuggestionsToRenderImmediately() {
        DeferredPlaybackUi gate = new DeferredPlaybackUi();
        List<String> renders = new ArrayList<>();
        // Audio-only, error, or the six-second fallback can release before metadata arrives.
        gate.release();
        gate.renderWhenReady(() -> renders.add("late"));
        assertEquals(Arrays.asList("late"), renders);
    }

    @Test
    public void repeatedFrameErrorAndTimeoutSignalsDoNotRepeatAnAdapterSubmission() {
        DeferredPlaybackUi gate = new DeferredPlaybackUi();
        List<String> renders = new ArrayList<>();
        gate.renderWhenReady(() -> renders.add("ready"));
        gate.release();
        gate.release();
        gate.release();
        assertEquals(Arrays.asList("ready"), renders);
    }

    @Test
    public void destructionCancelsPendingViewWork() {
        DeferredPlaybackUi gate = new DeferredPlaybackUi();
        List<String> renders = new ArrayList<>();
        gate.renderWhenReady(() -> renders.add("destroyed"));
        gate.cancelPending();
        gate.release();
        assertTrue(renders.isEmpty());
    }
}
