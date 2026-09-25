package com.liskovsoft.smartyoutubetv2.common.misc;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.helpers.DateHelper;

/**
 * NEWTUBE(upcoming-poll): how long to wait before asking /player again whether a scheduled live
 * stream or premiere has started. Each ask is a full /player client-ring walk (an upcoming answer
 * carries no media, so no cache ever accepts it), and both callers used to ask on a fixed period
 * forever: the watch page every 30 s, every stream reminder every minute.
 *
 * <p>With a known scheduled start the delay is a quarter of the time left, clamped to
 * [floor, far cap]: it can never sleep past the start, and it reaches the floor for the last few
 * minutes, so an on-time start is seen as promptly as before. Past the start the stream is late;
 * the floor holds for {@link #ON_TIME_WINDOW_MS}, then slows. Without a start time (only the web
 * clients return {@code microformat.liveBroadcastDetails}) the not-yet-live streak drives it.</p>
 *
 * <p>Pure arithmetic - no clock, no Android - so the whole schedule is unit-tested.</p>
 */
public final class LiveStartPollPolicy {
    /** The watch page sitting on an upcoming stream: the user is looking at the countdown slate. */
    public static final LiveStartPollPolicy PLAYER =
            new LiveStartPollPolicy(30_000, 10 * 60_000, 2 * 60_000);
    /** Stream reminders: {@link TickleManager}'s one-minute tick is the floor anyway. */
    public static final LiveStartPollPolicy REMINDER =
            new LiveStartPollPolicy(60_000, 30 * 60_000, 5 * 60_000);

    /** Streams routinely start a few minutes late: keep the floor this long past the schedule. */
    static final long ON_TIME_WINDOW_MS = 15 * 60_000;
    /** After this long past the schedule the stream is probably not coming soon: the late cap. */
    static final long LATE_WINDOW_MS = 60 * 60_000;
    /** Unknown start: this many answers at the floor, then 2x the floor until the next mark. */
    static final int UNKNOWN_START_FLOOR_ANSWERS = 10;
    static final int UNKNOWN_START_SLOW_ANSWERS = 30;
    /** Hidden watch page: at least a minute, at most this. */
    static final long MIN_BACKGROUND_DELAY_MS = 60_000;
    static final long MAX_BACKGROUND_DELAY_MS = 30 * 60_000;

    private final long mFloorMs;
    private final long mMaxAheadMs;
    private final long mMaxLateMs;

    LiveStartPollPolicy(long floorMs, long maxAheadMs, long maxLateMs) {
        mFloorMs = floorMs;
        mMaxAheadMs = maxAheadMs;
        mMaxLateMs = maxLateMs;
    }

    /**
     * @param nowMs            wall clock (the scheduled start is a wall-clock instant)
     * @param scheduledStartMs scheduled start in unix ms, {@code <= 0} when unknown
     * @param notLiveAnswers   not-yet-live answers already received for this video (0 = the first)
     */
    public long delayMs(long nowMs, long scheduledStartMs, int notLiveAnswers) {
        if (scheduledStartMs > 0) {
            long untilStartMs = scheduledStartMs - nowMs;
            if (untilStartMs > 0) {
                return clamp(untilStartMs / 4, mFloorMs, Math.max(mFloorMs, mMaxAheadMs));
            }

            long lateMs = -untilStartMs;
            if (lateMs < ON_TIME_WINDOW_MS) {
                return mFloorMs;
            }
            if (lateMs < LATE_WINDOW_MS) {
                return clamp(2 * mFloorMs, mFloorMs, Math.max(mFloorMs, mMaxLateMs));
            }
            return Math.max(mFloorMs, mMaxLateMs);
        }

        if (notLiveAnswers < UNKNOWN_START_FLOOR_ANSWERS) {
            return mFloorMs;
        }
        if (notLiveAnswers < UNKNOWN_START_SLOW_ANSWERS) {
            return clamp(2 * mFloorMs, mFloorMs, Math.max(mFloorMs, mMaxLateMs));
        }
        return Math.max(mFloorMs, mMaxLateMs);
    }

    /** The same poll while nobody is looking (screen off, background audio, another screen on top). */
    public static long backgroundDelayMs(long foregroundDelayMs) {
        return clamp(2 * foregroundDelayMs, MIN_BACKGROUND_DELAY_MS,
                Math.max(foregroundDelayMs, MAX_BACKGROUND_DELAY_MS));
    }

    /**
     * Scheduled start of an upcoming stream, {@code 0} when the answering client did not say.
     * {@code getStartTimeMs} is the DASH live start (set only once live); the upcoming schedule
     * arrives as {@code microformat...liveBroadcastDetails.startTimestamp} (ISO, UTC).
     */
    public static long scheduledStartMs(MediaItemFormatInfo formatInfo) {
        if (formatInfo == null) {
            return 0;
        }

        if (formatInfo.getStartTimeMs() > 0) {
            return formatInfo.getStartTimeMs();
        }

        try {
            return DateHelper.toUnixTimeMs(formatInfo.getStartTimestamp());
        } catch (RuntimeException e) { // unexpected timestamp shape: treat as unknown
            return 0;
        }
    }

    /** Log helper: seconds until the start ("-" = unknown, negative = late). */
    public static String startInLabel(long nowMs, long scheduledStartMs) {
        return scheduledStartMs > 0 ? ((scheduledStartMs - nowMs) / 1_000) + "s" : "-";
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
