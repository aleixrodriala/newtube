package com.github.vkay94.dtpv3

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.GestureDetectorCompat
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView

/**
 * Double-tap player surface for the media3 touch player.
 *
 * Unlike the original module's class this does NOT extend media3's PlayerView: the vendored
 * exoplayer2-ui and media3-ui libraries both ship a `layout/exo_player_view` resource, and the
 * app's resource merger can only keep one - inflating media3 PlayerView then explodes on the
 * other library's inner views (ClassCastException on AspectRatioFrameLayout). The touch player
 * only ever used PlayerView with `surface_type="none"` + `use_controller="false"` anyway, so all
 * that's actually needed is rebuilt here directly: an [AspectRatioFrameLayout] content box
 * (the Activity parents its session TextureView into it), a [SubtitleView], video-size driven
 * aspect ratio, and the double-tap gesture detection.
 */
open class DoubleTapPlayerViewImpl @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), DoubleTapPlayerView {

    private val gestureDetector: GestureDetectorCompat
    private val gestureListener: DoubleTapGestureListener = DoubleTapGestureListener(this)

    private val contentFrame: AspectRatioFrameLayout = AspectRatioFrameLayout(context)
    private val subtitleView: SubtitleView = SubtitleView(context)

    private var player: Player? = null

    private val componentListener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            updateAspectRatio(videoSize)
        }
    }

    private var controller: PlayerDoubleTapListener? = null
        get() = gestureListener.controls
        set(value) {
            gestureListener.controls = value
            field = value
        }

    init {
        gestureDetector = GestureDetectorCompat(context, gestureListener)

        contentFrame.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        addView(contentFrame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            .apply { gravity = Gravity.CENTER })

        // Subtitles live inside the content frame so they track the video box, not the letterbox.
        subtitleView.setUserDefaultStyle()
        subtitleView.setUserDefaultTextSize()
        contentFrame.addView(subtitleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    // ------------------------------------------------------------------------------
    // The PlayerView-like surface the Activity uses
    // ------------------------------------------------------------------------------

    /** The aspect-ratio box the Activity parents its code-managed TextureView into. */
    fun getContentFrame(): FrameLayout = contentFrame

    fun getSubtitleView(): SubtitleView = subtitleView

    fun setPlayer(newPlayer: Player?) {
        if (player === newPlayer) {
            return
        }
        player?.removeListener(componentListener)
        player = newPlayer
        if (newPlayer != null) {
            newPlayer.addListener(componentListener)
            updateAspectRatio(newPlayer.videoSize)
        }
    }

    fun setResizeMode(mode: Int) {
        contentFrame.resizeMode = mode
    }

    fun getResizeMode(): Int = contentFrame.resizeMode

    @Suppress("UNUSED_PARAMETER")
    fun setShutterBackgroundColor(color: Int) {
        // No shutter view here: the Activity's own loading-still covers stale frames.
    }

    private fun updateAspectRatio(videoSize: VideoSize) {
        if (videoSize.width == 0 || videoSize.height == 0) {
            return
        }
        contentFrame.setAspectRatio(
            videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
    }

    // ------------------------------------------------------------------------------
    // Double-tap handling (unchanged from the original module)
    // ------------------------------------------------------------------------------

    override val playerWidth: Int
        get() = width

    override var isDoubleTapEnabled = true

    override var doubleTapDelay: Long = 700
        get() = gestureListener.doubleTapDelay
        set(value) {
            gestureListener.doubleTapDelay = value
            field = value
        }

    override fun controller(controller: PlayerDoubleTapListener?) = apply { this.controller = controller }

    override fun isInDoubleTapMode(): Boolean = gestureListener.isDoubleTapping

    override fun keepInDoubleTapMode() {
        gestureListener.keepInDoubleTapMode()
    }

    override fun cancelInDoubleTapMode() {
        gestureListener.cancelInDoubleTapMode()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (isDoubleTapEnabled) {
            gestureDetector.onTouchEvent(ev)

            // Do not trigger original behavior when double tapping
            // otherwise the controller would show/hide - it would flack
            return true
        }
        return super.onTouchEvent(ev)
    }

    /**
     * Gesture Listener for double tapping
     */
    private class DoubleTapGestureListener(private val rootView: View) : GestureDetector.SimpleOnGestureListener() {

        private val mHandler = Handler(Looper.getMainLooper())
        private val mRunnable = Runnable {
            if (DEBUG) Log.d(TAG, "Runnable called")
            isDoubleTapping = false
            controls?.onDoubleTapFinished()
        }

        var controls: PlayerDoubleTapListener? = null
        var isDoubleTapping = false
        var doubleTapDelay: Long = 650

        fun keepInDoubleTapMode() {
            isDoubleTapping = true
            mHandler.removeCallbacks(mRunnable)
            mHandler.postDelayed(mRunnable, doubleTapDelay)
        }

        fun cancelInDoubleTapMode() {
            mHandler.removeCallbacks(mRunnable)
            isDoubleTapping = false
            controls?.onDoubleTapFinished()
        }

        override fun onDown(e: MotionEvent): Boolean {
            if (isDoubleTapping) {
                controls?.onDoubleTapProgressDown(e.x, e.y)
                return true
            }
            return super.onDown(e)
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (isDoubleTapping) {
                if (DEBUG) Log.d(TAG, "onSingleTapUp: isDoubleTapping = true")
                controls?.onDoubleTapProgressUp(e.x, e.y)
                return true
            }
            return super.onSingleTapUp(e)
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (isDoubleTapping) return true
            if (DEBUG) Log.d(TAG, "onSingleTapConfirmed: isDoubleTap = false")
            return rootView.performClick()
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (DEBUG) Log.d(TAG, "onDoubleTap")
            if (!isDoubleTapping) {
                isDoubleTapping = true
                keepInDoubleTapMode()
                controls?.onDoubleTapStarted(e.x, e.y)
            }
            return true
        }

        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP && isDoubleTapping) {
                if (DEBUG) Log.d(TAG, "onDoubleTapEvent, ACTION_UP")
                controls?.onDoubleTapProgressUp(e.x, e.y)
                return true
            }
            return super.onDoubleTapEvent(e)
        }

        companion object {
            private const val TAG = ".DTGListener"
            private var DEBUG = false
        }
    }
}
