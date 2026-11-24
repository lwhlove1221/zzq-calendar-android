package com.zzq.calendar.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.zzq.calendar.R
import com.zzq.calendar.model.DayAgenda
import com.zzq.calendar.model.EventInstance
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/** Seven day columns over a 24h grid; week view is an overview, tap a day header to edit. */
class WeekTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var onEventClick: ((EventInstance) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val hourHeight = resources.getDimension(R.dimen.hour_height)
    private val minBarHeight = resources.getDimension(R.dimen.event_bar_min_height)
    private val blockInset = 1.5f * density
    private val cornerRadius = 3f * density
    private val gutterWidth = 30f * density

    private val surfaceColor = ContextCompat.getColor(context, R.color.surface)
    private val hourTextColor = ContextCompat.getColor(context, R.color.hour_text)
    private val gridColor = ContextCompat.getColor(context, R.color.grid_line)
    private val blockColor = ContextCompat.getColor(context, R.color.event_blue)
    private val onBlockColor = ContextCompat.getColor(context, R.color.on_event_blue)
    private val todayTintColor = ContextCompat.getColor(context, R.color.today_tint)

    private val hourPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f)
        color = hourTextColor
        textAlign = Paint.Align.RIGHT
    }

    private val blockTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(9f)
        color = onBlockColor
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gridColor
        strokeWidth = 1f * density
    }

    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = blockColor
        style = Paint.Style.FILL
    }

    private val todayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = todayTintColor
        style = Paint.Style.FILL
    }

    private data class Block(
        val instance: EventInstance,
        val visual: RectF,
        val hit: RectF,
    )

    private var days: List<DayAgenda> = emptyList()
    private var zone: ZoneId = ZoneId.systemDefault()
    private var blocks: List<Block> = emptyList()
    private var downX = 0f
    private var downY = 0f

    init {
        setBackgroundColor(surfaceColor)
        isClickable = true
        isFocusable = true
    }

    fun submit(newDays: List<DayAgenda>, zoneId: ZoneId) {
        days = newDays
        zone = zoneId
        blocks = emptyList()
        requestLayout()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        blocks = buildBlocks()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(suggestedMinimumWidth, widthMeasureSpec)
        val desiredHeight = (hourHeight * 24).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (blocks.isEmpty() && days.isNotEmpty() && width > 0) {
            blocks = buildBlocks()
        }
        drawTodayColumn(canvas)
        drawGrid(canvas)
        drawBlocks(canvas)
    }

    private fun availableLeft(): Float = paddingLeft + gutterWidth

    private fun columnWidth(): Float =
        (width - paddingLeft - paddingRight - gutterWidth) / DAYS

    private fun drawTodayColumn(canvas: Canvas) {
        val today = LocalDate.now(zone)
        val index = days.indexOfFirst { it.day == today }
        if (index < 0) return
        val left = availableLeft() + index * columnWidth()
        canvas.drawRect(left, paddingTop.toFloat(), left + columnWidth(), paddingTop + 24 * hourHeight, todayPaint)
    }

    private fun drawGrid(canvas: Canvas) {
        val left = availableLeft()
        val bottom = paddingTop + 24 * hourHeight
        for (hour in 0..24) {
            val y = paddingTop + hour * hourHeight
            canvas.drawLine(left, y, width - paddingRight.toFloat(), y, gridPaint)
            if (hour < 24) {
                canvas.drawText(
                    hour.toString(),
                    left - 4f * density,
                    y + hourPaint.textSize,
                    hourPaint,
                )
            }
        }
        for (column in 0..DAYS) {
            val x = left + column * columnWidth()
            canvas.drawLine(x, paddingTop.toFloat(), x, bottom, gridPaint)
        }
    }

    private fun drawBlocks(canvas: Canvas) {
        blocks.forEach { block ->
            canvas.drawRoundRect(block.visual, cornerRadius, cornerRadius, blockPaint)
            val maxWidth = block.visual.width() - 4f * density
            if (maxWidth <= 0f || block.visual.height() < blockTextPaint.textSize) return@forEach
            val text = TextUtils.ellipsize(block.instance.title, blockTextPaint, maxWidth, TextUtils.TruncateAt.END)
            canvas.drawText(
                text,
                0,
                text.length,
                block.visual.left + 2f * density,
                block.visual.top + blockTextPaint.textSize + 1f * density,
                blockTextPaint,
            )
        }
    }

    private fun buildBlocks(): List<Block> {
        if (days.isEmpty() || width == 0) return emptyList()
        val width = columnWidth()
        if (width <= 0f) return emptyList()
        val result = mutableListOf<Block>()
        days.forEachIndexed { index, agenda ->
            val left = availableLeft() + index * width
            placeDay(agenda.timed, left, width, result)
        }
        return result
    }

    private fun placeDay(
        instances: List<EventInstance>,
        columnLeft: Float,
        columnWidth: Float,
        out: MutableList<Block>,
    ) {
        if (instances.isEmpty()) return
        val sorted = instances.sortedBy { it.start }
        val groups = mutableListOf<MutableList<EventInstance>>()
        var groupEnd = Instant.MIN
        sorted.forEach { instance ->
            if (groups.isEmpty() || !instance.start.isBefore(groupEnd)) {
                groups += mutableListOf(instance)
                groupEnd = instance.end
            } else {
                groups.last() += instance
                if (instance.end.isAfter(groupEnd)) groupEnd = instance.end
            }
        }

        groups.forEach { group ->
            val columnEnds = mutableListOf<Instant>()
            val assigned = mutableListOf<Pair<EventInstance, Int>>()
            group.forEach { instance ->
                var column = columnEnds.indexOfFirst { !it.isAfter(instance.start) }
                if (column < 0) {
                    column = columnEnds.size
                    columnEnds += instance.end
                } else {
                    columnEnds[column] = instance.end
                }
                assigned += instance to column
            }
            val count = max(1, columnEnds.size)
            val slot = (columnWidth - 2 * blockInset) / count
            assigned.forEach { (instance, column) ->
                val startMinutes = minutesOfDay(instance.start)
                val endMinutes = max(startMinutes + 1, minutesOfDay(instance.end))
                val top = paddingTop + startMinutes / 60f * hourHeight
                val bottom = max(top + minBarHeight, paddingTop + endMinutes / 60f * hourHeight)
                val left = columnLeft + blockInset + column * slot
                val right = left + slot
                val visual = RectF(left, top, right, min(bottom, paddingTop + 24 * hourHeight))
                val hitHeight = max(24f * density, visual.height())
                val hitTop = visual.centerY() - hitHeight / 2f
                out += Block(
                    instance = instance,
                    visual = visual,
                    hit = RectF(left, hitTop, right, hitTop + hitHeight),
                )
            }
        }
    }

    private fun minutesOfDay(instant: Instant): Int {
        val local = LocalDateTime.ofInstant(instant, zone)
        return (local.hour * 60 + local.minute).coerceIn(0, 23 * 60 + 59)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (kotlin.math.abs(event.x - downX) > 12f * density || kotlin.math.abs(event.y - downY) > 12f * density) {
                    return true
                }
                val hit = blocks
                    .filter { it.hit.contains(event.x, event.y) }
                    .minByOrNull { it.hit.width() * it.hit.height() }
                if (hit != null) {
                    onEventClick?.invoke(hit.instance)
                    performClick()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    internal fun visualBarsForTest(): List<RectF> = blocks.map { it.visual }

    internal fun columnsForTest(): List<Float> = days.indices.map { availableLeft() + it * columnWidth() }

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )

    private companion object {
        const val DAYS = 7
    }
}
