package com.liskovsoft.smartyoutubetv2.common.misc;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * NEWTUBE(reminder-backoff): per-reminder "when to ask /player next" for
 * {@link StreamReminderService}. The service is ticked once a minute for as long as the process
 * lives and used to spend one /player walk per reminder on every tick; now a reminder is only
 * checked when its own {@link LiveStartPollPolicy#REMINDER} delay has run out.
 *
 * <p>In-memory only: a process restart forgets the streaks and starts over at the floor, which
 * errs on the side of firing promptly. Main thread only (tick and answers both land there).</p>
 */
final class ReminderPollSchedule {
    /**
     * TickleManager aligns ticks to wall-clock minutes, but each check is measured from the tick
     * that issued it, so a 60 s delay must still count as due on the very next tick despite a few
     * ms of scheduling jitter. Anything under a minute works; a few seconds is plenty.
     */
    static final long TICK_SLACK_MS = 5_000;

    private static final class Entry {
        long scheduledStartMs;
        int notLiveAnswers;
        long nextCheckAtMs;
    }

    private final LiveStartPollPolicy mPolicy;
    private final Map<String, Entry> mEntries = new HashMap<>();

    ReminderPollSchedule(LiveStartPollPolicy policy) {
        mPolicy = policy;
    }

    /** Unknown reminders are always due: a fresh one is checked on the next tick. */
    boolean isDue(String videoId, long nowMs) {
        Entry entry = mEntries.get(videoId);
        return entry == null || nowMs + TICK_SLACK_MS >= entry.nextCheckAtMs;
    }

    /**
     * Records a not-yet-live answer (or a failed check, which says nothing new about the stream
     * but must not be retried every minute either) and returns the chosen delay.
     *
     * @param checkedAtMs      wall-clock time of the tick that issued the check
     * @param scheduledStartMs start reported by this answer, {@code <= 0} keeps the last known one
     */
    long onNotLive(String videoId, long checkedAtMs, long scheduledStartMs) {
        Entry entry = mEntries.get(videoId);
        if (entry == null) {
            entry = new Entry();
            mEntries.put(videoId, entry);
        }

        if (scheduledStartMs > 0) {
            entry.scheduledStartMs = scheduledStartMs;
        }

        long delayMs = mPolicy.delayMs(checkedAtMs, entry.scheduledStartMs, entry.notLiveAnswers);
        entry.notLiveAnswers++;
        entry.nextCheckAtMs = checkedAtMs + delayMs;
        return delayMs;
    }

    long getScheduledStartMs(String videoId) {
        Entry entry = mEntries.get(videoId);
        return entry != null ? entry.scheduledStartMs : 0;
    }

    int getNotLiveAnswers(String videoId) {
        Entry entry = mEntries.get(videoId);
        return entry != null ? entry.notLiveAnswers : 0;
    }

    void remove(String videoId) {
        mEntries.remove(videoId);
    }

    /** Drops the state of reminders that were removed elsewhere (menu toggle, stream started). */
    void retainOnly(Collection<String> videoIds) {
        mEntries.keySet().retainAll(videoIds);
    }
}
