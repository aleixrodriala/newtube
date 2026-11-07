package com.newtube.mobile.ui.common;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared behaviour for the Material bottom sheets that open at a fixed fraction of the screen
 * (comments, live chat) rather than wrapping their content.
 *
 * <p>The sheet's SURFACE is not handled here: it comes from {@code bottomSheetDialogTheme} in
 * {@code styles_mobile.xml}, because Material re-applies its own {@code MaterialShapeDrawable}
 * to the sheet frame on every layout and quietly wins over any background assigned in code.</p>
 */
public final class MobileSheets {
    private MobileSheets() {}

    /** NEWTUBE(sheet-landscape): see {@link #setPlayerShown}. */
    private static boolean sPlayerShown;
    private static boolean sPlayerFullscreen;
    /** NEWTUBE(sheet-landscape): see {@link #setPickerResumed}. */
    private static int sPickersResumed;

    /**
     * NEWTUBE(sheet-landscape): the player is on screen, in fullscreen with its bars hidden or on
     * its watch page. Set by the player; a dialog screen opened over it ({@code
     * MobileAppDialogActivity}: Repeat, Zoom, Save to playlist...) reads it to keep the fullscreen
     * video's bars hidden ({@link #keepSystemBarsHidden}), and to follow the player into and out of
     * fullscreen when the phone turns - on any other screen the bars stay.
     */
    public static void setPlayerShown(boolean shown, boolean fullscreen) {
        sPlayerShown = shown;
        sPlayerFullscreen = shown && fullscreen;
    }

    public static boolean isPlayerShown() {
        return sPlayerShown;
    }

    public static boolean isFullscreenPlayerShown() {
        return sPlayerFullscreen;
    }

    /**
     * NEWTUBE(sheet-landscape): a dialog screen ({@code MobileAppDialogActivity}) is in front.
     * The presenter cannot tell: it sees its view as shown only while the screen is still on its
     * way ({@code AppDialogPresenter.isDialogShown()} knows fragments, and this is an activity).
     * Counted from resume to pause, as it closes before the screen under it resumes.
     */
    public static void setPickerResumed(boolean resumed) {
        sPickersResumed = Math.max(0, sPickersResumed + (resumed ? 1 : -1));
    }

    public static boolean isPickerShown() {
        return sPickersResumed > 0;
    }

    /** NEWTUBE(sheet-landscape): see {@link #setCoveringSheetListener}. */
    private static Runnable sCoveringSheetListener;

    /**
     * NEWTUBE(sheet-landscape): runs once a dialog screen ({@code MobileAppDialogActivity}) starts
     * sliding its sheet in. The player keeps the sheet a row was tapped on until then, so the
     * picker that row opens takes its place without the bare video flashing up in between.
     */
    public static void setCoveringSheetListener(Runnable listener) {
        sCoveringSheetListener = listener;
    }

    public static void notifyCoveringSheetShown() {
        Runnable listener = sCoveringSheetListener;
        sCoveringSheetListener = null;
        if (listener != null) {
            listener.run();
        }
    }

    /**
     * Opens {@code dialog} expanded at {@code heightFraction} of the screen.
     *
     * <p>Deliberately NOT {@code getResources().getDisplayMetrics()}: before 1.10.4
     * {@code MotherActivity} replaced the process's DisplayMetrics with a single cached instance
     * built at the first Activity's onCreate, so its {@code heightPixels} was frozen at whatever
     * orientation the app happened to start in. Launch the app in landscape and the comments sheet opened 918px tall
     * (85% of 1080) in portrait forever after. The live display is the right measure.</p>
     */
    public static void expandTo(Dialog dialog, float heightFraction) {
        if (dialog == null) {
            return;
        }

        View frame = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (frame == null) {
            return;
        }

        int height = Math.round(screenHeightPx(dialog.getContext()) * heightFraction);
        frame.getLayoutParams().height = height;
        frame.requestLayout();

        BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(frame);
        behavior.setPeekHeight(height);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    /**
     * NEWTUBE(sheet-landscape): keeps the system bars hidden on a window shown over the fullscreen
     * player - a sheet dialog's, or a dialog activity's.
     *
     * <p>A window that takes focus decides whether the bars show. The player hides them through
     * its insets controller, and from Android 11 that request is not visible in
     * {@code getSystemUiVisibility()}, so copying those flags onto a new window copied nothing: the
     * window came up asking for the bars, the status bar and the navigation bar slid in over the
     * video, the sheet moved up by the navigation bar's height a moment after its entrance, and
     * the player's controls moved under it. The new window has to ask for the same hidden, swipe
     * to reveal bars itself. The cutout mode follows the player's too, so a window laid out over
     * the video spans the same full display.</p>
     */
    public static void keepSystemBarsHidden(Window window) {
        if (window == null) {
            return;
        }

        // Attributes first: on Android 11 an attribute change drops a behaviour set through the
        // controller (MobilePlaybackActivity.applyImmersiveSystemBars has the story).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            if (attributes.layoutInDisplayCutoutMode != mode) {
                attributes.layoutInDisplayCutoutMode = mode;
                window.setAttributes(attributes);
            }
        }

        View decor = window.getDecorView();
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
            // Android 11 re-derives the behaviour from these on every later attribute copy.
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, decor);
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }

    /** Undoes {@link #keepSystemBarsHidden}'s request: the window asks for the bars again. */
    public static void showSystemBars(Window window) {
        if (window == null) {
            return;
        }

        View decor = window.getDecorView();
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    & ~(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY));
        }
        WindowCompat.getInsetsController(window, decor).show(WindowInsetsCompat.Type.systemBars());
    }

    /**
     * NEWTUBE(sheet-landscape): caps {@code dialog}'s sheet at {@code maxHeight} (a tighter cap the
     * caller already set wins), and when its list does not fit, lowers the cap a little more so
     * the list's bottom edge cuts the last row it shows about in half.
     *
     * <p>Capped at a round number, the edge can fall right between two rows: the gear's More
     * level, held sideways, ended neatly on "Download", and "Playback queue" and "Stats for nerds"
     * below it looked like they did not exist. A row cut in half is the usual sign that a list goes
     * on. Fitted again whenever the sheet's layout changes before the person scrolls it - on a
     * gesture-navigation phone the gesture area's padding arrives a layout after the first.</p>
     */
    public static void capHeight(BottomSheetDialog dialog, int maxHeight) {
        BottomSheetBehavior<?> behavior = dialog.getBehavior();
        int cap = behavior.getMaxHeight() > 0 ? Math.min(behavior.getMaxHeight(), maxHeight) : maxHeight;
        behavior.setMaxHeight(cap);
        View frame = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (frame != null) {
            new PeekFit(frame, behavior, cap);
        }
    }

    /**
     * Fraction of the last row left showing at the edge (see {@link #capHeight}). Around a half: at
     * two thirds a row's label still showed whole and the row passed for the last one.
     */
    private static final float PEEK_MIN = 0.4f;
    private static final float PEEK_MAX = 0.6f;

    private static final class PeekFit implements View.OnLayoutChangeListener, ViewTreeObserver.OnPreDrawListener {
        private final View mFrame;
        private final BottomSheetBehavior<?> mBehavior;
        private final int mCap;
        private boolean mDirty = true;
        private boolean mFrozen;
        @androidx.annotation.Nullable private View mWatchedList;

        PeekFit(View frame, BottomSheetBehavior<?> behavior, int cap) {
            mFrame = frame;
            mBehavior = behavior;
            mCap = cap;
            frame.addOnLayoutChangeListener(this);
            frame.getViewTreeObserver().addOnPreDrawListener(this);
        }

        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                   int oldLeft, int oldTop, int oldRight, int oldBottom) {
            mDirty = true;
        }

        @Override
        public boolean onPreDraw() {
            if (mFrozen) {
                ViewTreeObserver observer = mFrame.getViewTreeObserver();
                if (observer.isAlive()) {
                    observer.removeOnPreDrawListener(this);
                }
                mFrame.removeOnLayoutChangeListener(this);
                return true;
            }
            if (!mDirty) {
                return true;
            }
            mDirty = false;
            View list = findScrollingList(mFrame);
            watchForScroll(list);
            int target = list != null ? peekingCap(mFrame, list, mCap) : mCap;
            if (target == mBehavior.getMaxHeight()) {
                return true;
            }
            mBehavior.setMaxHeight(target);
            mFrame.requestLayout();
            return false; // drawn at the new height instead
        }

        /** Once the list moves under the person's finger, the sheet keeps the height it has. */
        private void watchForScroll(@androidx.annotation.Nullable View list) {
            if (list == null || list == mWatchedList) {
                return;
            }
            mWatchedList = list;
            if (list instanceof androidx.core.widget.NestedScrollView) {
                ((androidx.core.widget.NestedScrollView) list).setOnScrollChangeListener(
                        (androidx.core.widget.NestedScrollView.OnScrollChangeListener) (v, x, y, oldX, oldY) -> mFrozen = true);
            } else if (list instanceof androidx.recyclerview.widget.RecyclerView) {
                ((androidx.recyclerview.widget.RecyclerView) list).addOnScrollListener(
                        new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                            @Override
                            public void onScrollStateChanged(@androidx.annotation.NonNull androidx.recyclerview.widget.RecyclerView view, int state) {
                                if (state == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING) {
                                    mFrozen = true;
                                }
                            }
                        });
            }
        }
    }

    /**
     * The sheet height, at most {@code cap}, at which {@code list}'s bottom edge cuts a row between
     * {@link #PEEK_MIN} and {@link #PEEK_MAX} of its height; {@code cap} when the list fits there.
     * Worked out for the sheet at {@code cap} whatever height it has now, so fitting again after a
     * fit lands on the same answer.
     */
    @androidx.annotation.VisibleForTesting
    static int peekingCap(View frame, View list, int cap) {
        Rect bounds = inFrame(frame, list);
        // What sits below the list stays: a pinned footer (Subtitles, Speed), the bottom padding.
        int below = frame.getHeight() - bounds.bottom;
        int edge = cap - below;
        int viewport = edge - bounds.top;
        if (!(list instanceof androidx.core.view.ScrollingView) || viewport <= 0
                || ((androidx.core.view.ScrollingView) list).computeVerticalScrollRange() <= viewport) {
            return cap; // everything fits
        }
        List<Rect> rows = new ArrayList<>();
        collectRows(frame, list, rows);
        Rect cut = null;
        float shown = 0;
        int bestMiddle = -1;
        for (Rect row : rows) {
            if (row.height() <= 0) {
                continue;
            }
            if (row.top < edge && row.bottom > edge) {
                cut = row;
                shown = (edge - row.top) / (float) row.height();
            }
            int middle = row.top + row.height() / 2;
            if (middle <= edge && middle > bestMiddle) {
                bestMiddle = middle;
            }
        }
        if (cut != null && shown >= PEEK_MIN && shown <= PEEK_MAX) {
            return cap;
        }
        // Never give up more than half the list for it.
        int lift = edge - bestMiddle;
        return bestMiddle >= 0 && lift < viewport / 2 ? cap - lift : cap;
    }

    @androidx.annotation.Nullable
    private static View findScrollingList(View view) {
        if (view.getVisibility() != View.VISIBLE) {
            return null;
        }
        if (view instanceof androidx.core.widget.NestedScrollView || view instanceof android.widget.ScrollView
                || view instanceof androidx.recyclerview.widget.RecyclerView) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findScrollingList(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Rect inFrame(View frame, View view) {
        Rect rect = new Rect(0, 0, view.getWidth(), view.getHeight());
        ((ViewGroup) frame).offsetDescendantRectToMyCoords(view, rect);
        return rect;
    }

    /**
     * The outermost clickable views under {@code view} - the rows - in {@code frame}'s coordinates.
     * A hidden section keeps the bounds of its last layout, so it is skipped whole (Cast swaps its
     * device list for the TV-code chooser).
     */
    private static void collectRows(View frame, View view, List<Rect> rows) {
        if (view.getVisibility() != View.VISIBLE) {
            return;
        }
        if (view.isClickable() && !(view instanceof androidx.recyclerview.widget.RecyclerView)) {
            rows.add(inFrame(frame, view));
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectRows(frame, group.getChildAt(i), rows);
            }
        }
    }

    private static int screenHeightPx(Context context) {
        WindowManager windowManager =
                (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            return context.getResources().getDisplayMetrics().heightPixels;
        }

        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        return metrics.heightPixels;
    }
}
