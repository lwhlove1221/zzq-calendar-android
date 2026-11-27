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
import com.zzq.calendar.model.EventInstance
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class DayTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var onEventClick: ((EventInstance) -> Unit)? = null
    var onEmptyClick: ((LocalDateTime) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val hourHeight = resources.getDimension(R.dimen.hour_height)
    private val minBarHeight = resources.getDimension(R.dimen.event_bar_min_height)
    private val minTouchTarget = resources.getDimension(R.dimen.touch_target_min)
    private val blockInset = 2f * density
    private val cornerRadius = 4f * density

    private val surfaceColor = ContextCompat.getColor(context, R.color.surface)
    private val hourTextColor = ContextCompat.getColor(context, R.color.hour_text)
    private val gridColor = ContextCompat.getColor(context, R.color.grid_line)
    private val blockColor = context.themeColor(com.google.android.material.R.attr.colorPrimary)
    private val onBlockColor = context.themeColor(com.google.android.material.R.attr.colorOnPrimary)
    private val currentTimeColor = ContextCompat.getColor(context, R.color.current_time)

    private val hourPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(12f)
        color = hourTextColor
        textAlign = Paint.Align.RIGHT
    }

    private val blockTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(13f)
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

    private val currentTimePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = currentTimeColor
        strokeWidth = 2f * density
    }

    private val gutterWidth: Float by lazy {
        max(52f * density, hourPaint.measureText("00:00") + 12f * density)
    }

    private data class Block(
        val instance: EventInstance,
        val visual: RectF,
        val hit: RectF,
    )

    private var instances: List<EventInstance> = emptyList()
    private var blocks: List<Block> = emptyList()
    private var day: LocalDate = LocalDate.now()
    private var zone: ZoneId = ZoneId.systemDefault()
    private var isToday: Boolean = false
    private var downX = 0f
    private var downY = 0f

    private val hourFormatter = DateTimeFormatter.ofPattern("HH:mm")

    init {
        setBackgroundColor(surfaceColor)
        isClickable = true
        isFocusable = true
    }

    fun submit(newInstances: List<EventInstance>, selectedDay: LocalDate, zoneId: ZoneId) {
        instances = newInstances
        day = selectedDay
        zone = zoneId
        isToday = selectedDay == LocalDate.now(zoneId)
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
        if (blocks.isEmpty() && instances.isNotEmpty() && width > 0) {
            blocks = buildBlocks()
        }
        drawGrid(canvas)
        drawBlocks(canvas)
        drawCurrentTime(canvas)
    }

    private fun drawGrid(canvas: Canvas) {
        for (hour in 0..24) {
            val y = paddingTop + hour * hourHeight
            canvas.drawLine(gutterWidth, y, width.toFloat(), y, gridPaint)
            if (hour < 24) {
                canvas.drawText(
                    String.format("%02d:00", hour),
                    gutterWidth - 8f * density,
                    y + hourPaint.textSize,
                    hourPaint,
                )
            }
        }
        canvas.drawLine(gutterWidth, paddingTop.toFloat(), gutterWidth, paddingTop + 24 * hourHeight, gridPaint)
    }

    private fun drawBlocks(canvas: Canvas) {
        blocks.forEach { block ->
            canvas.drawRoundRect(block.visual, cornerRadius, cornerRadius, blockPaint)
            val text = buildLabel(block.instance)
            val maxWidth = block.visual.width() - 8f * density
            if (maxWidth > 0f) {
                val ellipsized = TextUtils.ellipsize(text, blockTextPaint, maxWidth, TextUtils.TruncateAt.END)
                canvas.drawText(
                    ellipsized,
                    0,
                    ellipsized.length,
                    block.visual.left + 4f * density,
                    block.visual.top + blockTextPaint.textSize + 2f * density,
                    blockTextPaint,
                )
            }
        }
    }

    private fun drawCurrentTime(canvas: Canvas) {
        if (!isToday) return
        val now = LocalTime.now(zone)
        val minutes = now.hour * 60 + now.minute
        val y = paddingTop + minutes / 60f * hourHeight
        canvas.drawLine(gutterWidth, y, width.toFloat(), y, currentTimePaint)
        canvas.drawCircle(gutterWidth, y, 3f * density, currentTimePaint)
    }

    private fun buildBlocks(): List<Block> {
        if (instances.isEmpty() || width == 0) return emptyList()
        val availableWidth = width - gutterWidth - blockInset
        if (availableWidth <= 0f) return emptyList()

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

        val result = mutableListOf<Block>()
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
            val columnCount = max(1, columnEnds.size)
            val columnWidth = availableWidth / columnCount
            assigned.forEach { (instance, column) ->
                val startMinutes = minutesOfDay(instance.start)
                val endMinutes = max(startMinutes + 1, minutesOfDay(instance.end))
                val top = paddingTop + startMinutes / 60f * hourHeight
                val bottom = max(top + minBarHeight, paddingTop + endMinutes / 60f * hourHeight)
                val left = gutterWidth + blockInset + column * columnWidth
                val right = left + columnWidth - blockInset
                val visual = RectF(left, top, right, min(bottom, paddingTop + 24 * hourHeight))
                val hitHeight = max(minTouchTarget, visual.height())
                val hitTop = visual.centerY() - hitHeight / 2f
                val hitBottom = hitTop + hitHeight
                result += Block(
                    instance = instance,
                    visual = visual,
                    hit = RectF(left, hitTop, right, hitBottom),
                )
            }
        }
        return result
    }

    private fun minutesOfDay(instant: Instant): Int {
        val local = LocalDateTime.ofInstant(instant, zone)
        val minutes = local.hour * 60 + local.minute
        return minutes.coerceIn(0, 23 * 60 + 59)
    }

    private fun buildLabel(instance: EventInstance): String {
        val start = LocalDateTime.ofInstant(instance.start, zone).format(hourFormatter)
        return "$start ${instance.title}"
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
                    return true
                }
                handleEmptyTap(event.y)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleEmptyTap(y: Float) {
        val minuteOfDay = ((y - paddingTop) / hourHeight * 60f).roundToInt()
        if (minuteOfDay < 0) return
        val clamped = minuteOfDay.coerceIn(0, 23 * 60 + 30)
        val snapped = clamped / 30 * 30
        onEmptyClick?.invoke(day.atTime(snapped / 60, snapped % 60))
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    internal fun hitTargetsForTest(): List<RectF> = blocks.map { it.hit }

    internal fun visualBarsForTest(): List<RectF> = blocks.map { it.visual }

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )
}
