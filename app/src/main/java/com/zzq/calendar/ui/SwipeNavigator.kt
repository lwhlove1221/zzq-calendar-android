package com.zzq.calendar.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.zzq.calendar.R
import kotlin.math.abs
import kotlin.math.max

enum class SwipeDirection { PREVIOUS, NEXT }

/** Pure gesture math, testable without an Activity. */
object SwipeGesture {

    /** Distance a horizontal drag must cover before it is allowed to count as a page flip. */
    const val THRESHOLD_DP = 64f

    /** A flick faster than this flips even when the drag itself is short. */
    const val FLING_VELOCITY_DP = 700f

    /** Share of the screen width that also counts as a completed drag. */
    const val DISTANCE_RATIO = 0.18f

    /**
     * Release decision: a quick flick wins over distance, otherwise the page must travel
     * [DISTANCE_RATIO] of the width (or [THRESHOLD_DP], whichever is larger).
     */
    fun releaseDirection(
        dxPx: Float,
        dyPx: Float,
        velocityX: Float,
        widthPx: Float,
        density: Float,
    ): SwipeDirection? {
        if (abs(dxPx) <= abs(dyPx)) return null
        if (velocityX.isFinite() && abs(velocityX) >= FLING_VELOCITY_DP * density) {
            return if (velocityX < 0f) SwipeDirection.NEXT else SwipeDirection.PREVIOUS
        }
        val trigger = max(THRESHOLD_DP * density, widthPx * DISTANCE_RATIO)
        if (abs(dxPx) < trigger) return null
        return if (dxPx < 0f) SwipeDirection.NEXT else SwipeDirection.PREVIOUS
    }
}

/**
 * Wraps the activity content in a horizontal pager and turns left / right drags into page flips.
 * The page follows the finger from the moment a horizontal drag beats the touch slop, so taps,
 * vertical scrolling and date-cell clicks keep working. Past the first / last page the drag is
 * damped and springs back.
 */
object SwipeNavigator {

    /** Release animation length: the flip or the rebound finishes within 300ms. */
    const val ANIMATION_MS = 260L

    /** Share of the width the incoming page starts from. */
    const val ENTER_RATIO = 0.45f

    /** Share of the width the outgoing page travels to. */
    const val EXIT_RATIO = 1f

    /** Rubber-band factor past the first / last page. */
    const val EDGE_DAMPING = 0.3f

    /** A single drag can never reveal more than this share of background. */
    const val MAX_DRAG_RATIO = 0.6f

    fun attach(
        activity: Activity,
        canNavigate: (SwipeDirection) -> Boolean = { true },
        onNavigate: (SwipeDirection) -> Unit,
    ): SwipePageLayout? {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
        val page = content.getChildAt(0) ?: return null
        if (page is SwipePageLayout) return page

        content.removeViewAt(0)
        val pager = SwipePageLayout(activity).apply {
            id = R.id.swipeSurface
            this.canNavigate = canNavigate
            this.onNavigate = onNavigate
        }
        pager.addView(
            page,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        content.addView(
            pager,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        return pager
    }
}

class SwipePageLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    var canNavigate: (SwipeDirection) -> Boolean = { true }
    var onNavigate: (SwipeDirection) -> Unit = {}

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val interpolator = DecelerateInterpolator()
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var velocityTracker: VelocityTracker? = null
    private var snapshot: ImageView? = null

    private val page: View? get() = getChildAt(0)

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginGesture(event)
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                if (!tracking) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    tracking = abs(dx) > touchSlop && abs(dx) > abs(dy)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!tracking) endGesture()
        }
        return tracking
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginGesture(event)
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                page?.translationX = dragOffset(event.x - downX)
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                release(event.x - downX, event.y - downY, velocityTracker?.xVelocity ?: 0f)
            }
            MotionEvent.ACTION_CANCEL -> {
                endGesture()
                rebound()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun beginGesture(event: MotionEvent) {
        downX = event.x
        downY = event.y
        tracking = false
        settle()
        velocityTracker?.recycle()
        velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
    }

    private fun endGesture() {
        tracking = false
        velocityTracker?.recycle()
        velocityTracker = null
    }

    /** Drop any running transition and put the page back where the finger expects it. */
    private fun settle() {
        page?.animate()?.cancel()
        removeSnapshot()
        page?.apply {
            translationX = 0f
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
            visibility = View.VISIBLE
        }
    }

    private fun dragOffset(dx: Float): Float {
        val direction = if (dx < 0f) SwipeDirection.NEXT else SwipeDirection.PREVIOUS
        val damped = if (canNavigate(direction)) dx else dx * SwipeNavigator.EDGE_DAMPING
        val limit = width * SwipeNavigator.MAX_DRAG_RATIO
        return if (limit > 0f) damped.coerceIn(-limit, limit) else damped
    }

    private fun release(dx: Float, dy: Float, velocityX: Float) {
        endGesture()
        val direction = SwipeGesture.releaseDirection(dx, dy, velocityX, width.toFloat(), density)
        if (direction != null && canNavigate(direction)) flip(direction) else rebound()
    }

    private fun rebound() {
        val page = page ?: return
        page.animate().cancel()
        page.animate()
            .translationX(0f)
            .setDuration(SwipeNavigator.ANIMATION_MS)
            .setInterpolator(interpolator)
            .start()
    }

    /**
     * The outgoing page keeps sliding from wherever the finger left it while the destination
     * (reloaded by [onNavigate]) scales and fades in. Capturing the outgoing page first is what
     * lets the new content render without the text swapping under the finger.
     */
    private fun flip(direction: SwipeDirection) {
        val page = page ?: return
        val enterFrom = if (direction == SwipeDirection.NEXT) 1f else -1f
        val leaving = capture(page)

        page.animate().cancel()
        page.translationX = enterFrom * width * SwipeNavigator.ENTER_RATIO
        page.alpha = 0.6f
        page.scaleX = 0.95f
        page.scaleY = 0.95f

        onNavigate(direction)

        page.animate()
            .translationX(0f)
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(SwipeNavigator.ANIMATION_MS)
            .setInterpolator(interpolator)
            .start()

        leaving?.animate()
            ?.translationX(-enterFrom * width * SwipeNavigator.EXIT_RATIO)
            ?.alpha(0.6f)
            ?.scaleX(0.95f)
            ?.scaleY(0.95f)
            ?.setDuration(SwipeNavigator.ANIMATION_MS)
            ?.setInterpolator(interpolator)
            ?.withEndAction { removeSnapshot() }
            ?.start()
    }

    /**
     * Freeze the current page into an overlay so it can keep moving while the live page reloads.
     * Half resolution is enough for a 260ms transition and keeps the allocation small.
     */
    private fun capture(page: View): ImageView? {
        val w = page.width
        val h = page.height
        if (w <= 0 || h <= 0) return null
        val bitmap = runCatching {
            Bitmap.createBitmap(w / 2, h / 2, Bitmap.Config.ARGB_8888).also { bmp ->
                val canvas = Canvas(bmp)
                canvas.scale(0.5f, 0.5f)
                page.draw(canvas)
            }
        }.getOrNull() ?: return null
        val overlay = ImageView(context).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_XY
            translationX = page.translationX
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        snapshot = overlay
        return overlay
    }

    private fun removeSnapshot() {
        val overlay = snapshot ?: return
        snapshot = null
        overlay.animate().cancel()
        removeView(overlay)
        (overlay.drawable as? BitmapDrawable)?.bitmap?.recycle()
    }
}
