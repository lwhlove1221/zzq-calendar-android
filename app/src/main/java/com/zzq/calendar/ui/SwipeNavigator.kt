package com.zzq.calendar.ui

import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.zzq.calendar.R
import kotlin.math.abs

enum class SwipeDirection { PREVIOUS, NEXT }

/** Pure gesture math, testable without an Activity. */
object SwipeGesture {

    /** Distance a horizontal drag must cover before it counts as a page flip. */
    const val THRESHOLD_DP = 64f

    fun direction(dxPx: Float, dyPx: Float, thresholdPx: Float): SwipeDirection? {
        if (abs(dxPx) < thresholdPx) return null
        if (abs(dxPx) <= abs(dyPx)) return null
        return if (dxPx < 0f) SwipeDirection.NEXT else SwipeDirection.PREVIOUS
    }
}

/**
 * Wraps the activity content in a horizontal pager and turns left / right drags into page flips.
 * The wrapper only intercepts once a horizontal drag beats the touch slop, so taps, vertical
 * scrolling and date-cell clicks keep working. When [canNavigate] says the target is out of
 * range nothing is animated, so the edge does not overshoot or bounce.
 */
object SwipeNavigator {

    /** Enter animation length; must stay at or under 200ms. */
    const val ANIMATION_MS = 160L
    const val ENTER_OFFSET_DP = 20f

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

    private val thresholdPx = SwipeGesture.THRESHOLD_DP * resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var tracking = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                tracking = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    tracking = abs(dx) > touchSlop && abs(dx) > abs(dy)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return tracking
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val direction = SwipeGesture.direction(event.x - downX, event.y - downY, thresholdPx)
                tracking = false
                if (direction != null && canNavigate(direction)) {
                    onNavigate(direction)
                    animatePageIn(direction)
                }
            }
            MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun animatePageIn(direction: SwipeDirection) {
        val page = getChildAt(0) ?: return
        val offset = SwipeNavigator.ENTER_OFFSET_DP * resources.displayMetrics.density *
            if (direction == SwipeDirection.NEXT) 1f else -1f
        page.animate().cancel()
        page.translationX = offset
        page.alpha = 0.55f
        page.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(SwipeNavigator.ANIMATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
}
