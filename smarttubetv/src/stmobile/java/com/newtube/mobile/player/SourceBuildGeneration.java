package com.newtube.mobile.player;

/**
 * Per-player cancellation for work on the shared source-build executor.
 *
 * <p>Dropping stale work is the point of this class, but a drop that leaves no trace is
 * indistinguishable from a player that simply never opened anything - which is exactly the
 * shape of a stall that survives QA (no error, no log, a player parked at 00:00). So every
 * drop is reported to a {@link DropListener}. The listener is injected rather than logged
 * from here so this stays a plain JVM class the unit tests can drive without Android.
 */
final class SourceBuildGeneration {
    /** Notified whenever queued work is discarded because a newer open/reset/release superseded it. */
    interface DropListener {
        void onDropped(String stage, int generation, int currentGeneration);
    }

    private int mGeneration;
    private DropListener mDropListener;

    synchronized void setDropListener(DropListener listener) {
        mDropListener = listener;
    }

    synchronized int current() {
        return mGeneration;
    }

    synchronized int next() {
        return ++mGeneration;
    }

    /** Invalidation and stash cleanup must be atomic with worker-thread publication. */
    synchronized void invalidate(Runnable cleanup) {
        ++mGeneration;
        cleanup.run();
    }

    /**
     * Check when the queued task starts, before expensive work. Do not hold the lock while
     * building: a reset must be able to invalidate a build that is already running. Its result
     * still needs a separate check at publication/delivery time.
     */
    Runnable guard(int generation, Runnable task) {
        return guard(generation, "build", task);
    }

    /** @param stage where the drop happened, for the log line - e.g. {@code build}, {@code deliver}. */
    Runnable guard(int generation, String stage, Runnable task) {
        return () -> {
            final int currentGeneration;
            final DropListener listener;
            synchronized (this) {
                currentGeneration = mGeneration;
                listener = mDropListener;
            }

            if (generation != currentGeneration) {
                if (listener != null) {
                    listener.onDropped(stage, generation, currentGeneration);
                }
                return;
            }

            task.run();
        };
    }

    /** For short stash writes only, never the source build itself. */
    void publishIfCurrent(int generation, Runnable publication) {
        publishIfCurrent(generation, "publish", publication);
    }

    void publishIfCurrent(int generation, String stage, Runnable publication) {
        // The publication itself must stay under the lock (it has to be atomic against
        // invalidate's cleanup); only the drop notification is moved out, so a listener can never
        // block a reset.
        final int currentGeneration;
        final DropListener listener;
        synchronized (this) {
            if (generation == mGeneration) {
                publication.run();
                return;
            }
            currentGeneration = mGeneration;
            listener = mDropListener;
        }

        if (listener != null) {
            listener.onDropped(stage, generation, currentGeneration);
        }
    }
}
