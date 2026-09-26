package com.newtube.mobile;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.smartyoutubetv2.common.misc.NetPath;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * NEWTUBE(startup): the launch phases that were invisible in release logs, plus the one hook
 * deferred startup work needs - "the first screen of this process has drawn".
 *
 * <p>Every line is a NetPath line (release builds log them too) of the form
 * {@code launch <event> ... +<ms since process start>}. One line per event per process, so a
 * cold start costs a handful of log lines. The measurement harness can time cold launches
 * without a debug build: {@code launch first-frame}, {@code launch feed-snapshot},
 * {@code launch feed-fresh}, {@code launch first-thumb}.</p>
 *
 * <p>{@link #runAfterFirstFrame} runs work after the first Activity of the process to resume has
 * submitted its first frame (Splash finishes inside onCreate and never resumes, so on a launcher
 * start that is Home, on a share link the player). Anything that must touch the main thread but
 * is not needed for that frame - the BotGuard WebView is the measured one - belongs there.</p>
 */
public final class LaunchMilestones {
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean sFirstFrameSeen = new AtomicBoolean();
    private static final List<Runnable> sAfterFirstFrame = new ArrayList<>();
    private static final AtomicBoolean sFeedSnapshotLogged = new AtomicBoolean();
    private static final AtomicBoolean sFeedFreshLogged = new AtomicBoolean();
    private static final AtomicBoolean sFirstThumbLogged = new AtomicBoolean();
    private static boolean sInstalled;

    private LaunchMilestones() {
    }

    /** Milliseconds since this process was forked. */
    public static long sinceProcessStartMs() {
        return SystemClock.uptimeMillis() - Process.getStartUptimeMillis();
    }

    public static void log(String event) {
        NetPath.log("launch " + event + " +" + sinceProcessStartMs());
    }

    /** Call once from Application.onCreate, before any Activity can resume. */
    public static synchronized void install(Application application) {
        if (sInstalled) {
            return;
        }
        sInstalled = true;
        application.registerActivityLifecycleCallbacks(new FirstFrameWatcher(application));
    }

    /**
     * Run {@code task} on the main thread right after the process's first frame, or after
     * {@code fallbackMs} if no Activity draws by then (a process started for a service or a
     * broadcast). Runs at most once; after the first frame it runs on the next loop.
     */
    public static void runAfterFirstFrame(long fallbackMs, Runnable task) {
        AtomicBoolean ran = new AtomicBoolean();
        Runnable once = () -> {
            if (ran.compareAndSet(false, true)) {
                task.run();
            }
        };

        synchronized (LaunchMilestones.class) {
            if (!sFirstFrameSeen.get()) {
                sAfterFirstFrame.add(once);
                sMainHandler.postDelayed(once, fallbackMs);
                return;
            }
        }
        sMainHandler.post(once);
    }

    /** A feed repainted its last-known snapshot (disk or memory) - the first cards on screen. */
    public static void onFeedSnapshotPainted(int sectionId, int items) {
        if (items > 0 && sFeedSnapshotLogged.compareAndSet(false, true)) {
            log("feed-snapshot section=" + sectionId + " items=" + items);
        }
    }

    /** First network-fresh feed content bound to the grid in this process. */
    public static void onFeedFreshBound(int sectionId, int items) {
        if (items > 0 && sFeedFreshLogged.compareAndSet(false, true)) {
            log("feed-fresh section=" + sectionId + " items=" + items);
        }
    }

    /** Cheap guard for the per-bind listener: false once the first thumbnail has been logged. */
    public static boolean wantsFirstThumb() {
        return !sFirstThumbLogged.get();
    }

    /** First feed thumbnail decoded and handed to its card; {@code source} is Glide's DataSource. */
    public static void onFirstThumb(@Nullable Object source) {
        if (sFirstThumbLogged.compareAndSet(false, true)) {
            log("first-thumb source=" + source);
        }
    }

    static void onFirstFrame(String activityName) {
        List<Runnable> pending;
        synchronized (LaunchMilestones.class) {
            if (!sFirstFrameSeen.compareAndSet(false, true)) {
                return;
            }
            pending = new ArrayList<>(sAfterFirstFrame);
            sAfterFirstFrame.clear();
        }
        log("first-frame activity=" + activityName);
        for (Runnable task : pending) {
            sMainHandler.post(task);
        }
    }

    /** Test hook: forget this process's milestones. */
    static synchronized void resetForTest() {
        sFirstFrameSeen.set(false);
        sAfterFirstFrame.clear();
        sFeedSnapshotLogged.set(false);
        sFeedFreshLogged.set(false);
        sFirstThumbLogged.set(false);
    }

    private static final class FirstFrameWatcher implements Application.ActivityLifecycleCallbacks {
        private final Application mApplication;

        FirstFrameWatcher(Application application) {
            mApplication = application;
        }

        @Override
        public void onActivityResumed(@NonNull Activity activity) {
            // Arm every Activity that resumes before the first frame: one that finishes or goes
            // away without drawing must not leave the milestone (and the deferred work) waiting
            // for the fallback. The first draw wins; the other listeners retire on their own draw.
            if (sFirstFrameSeen.get()) {
                return;
            }
            View decor = activity.getWindow() != null ? activity.getWindow().peekDecorView() : null;
            if (decor == null) {
                return;
            }
            decor.getViewTreeObserver().addOnDrawListener(new ViewTreeObserver.OnDrawListener() {
                private boolean mSeen;

                @Override
                public void onDraw() {
                    if (mSeen) {
                        return;
                    }
                    mSeen = true;
                    ViewTreeObserver.OnDrawListener self = this;
                    // Posted from inside the draw: runs once this frame has been handed to the
                    // renderer. A listener cannot be removed from within its own onDraw.
                    decor.post(() -> {
                        ViewTreeObserver live = decor.getViewTreeObserver();
                        if (live.isAlive()) {
                            live.removeOnDrawListener(self);
                        }
                        if (!sFirstFrameSeen.get()) {
                            mApplication.unregisterActivityLifecycleCallbacks(FirstFrameWatcher.this);
                            onFirstFrame(activity.getClass().getSimpleName());
                        }
                    });
                }
            });
        }

        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(@NonNull Activity activity) {
        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
        }
    }
}
