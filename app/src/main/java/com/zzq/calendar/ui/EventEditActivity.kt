package com.zzq.calendar.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.zzq.calendar.AppGraph
import com.zzq.calendar.R
import com.zzq.calendar.data.EventDraft
import com.zzq.calendar.data.RRuleBuilder
import com.zzq.calendar.databinding.ActivityEventEditBinding
import com.zzq.calendar.model.EventMaster
import com.zzq.calendar.model.RRule
import com.zzq.calendar.model.RepeatSpec
import com.zzq.calendar.model.WallTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

class EventEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEventEditBinding
    private val repository by lazy { AppGraph.repository(this) }
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private var eventId: String? = null
    private var existing: EventMaster? = null
    private var start: LocalDateTime = LocalDateTime.now()
    private var end: LocalDateTime = LocalDateTime.now().plusHours(1)
    private var allDay = false
    private var repeatIndex = 0
    private var eventTzid: String = ""
    private var repeatLocked = false
    private var originalRuleRaw = ""
    private var originalStartDate: LocalDate? = null
    private var originalRepeatIndex = 0
    private var originalCountText = ""
    private var saving = false

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEventEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        eventId = intent.getStringExtra(EXTRA_EVENT_ID)
        eventTzid = zone.id
        val initialStart = intent.getStringExtra(EXTRA_START_WALL)
            ?.let { runCatching { WallTime.parse(it) }.getOrNull() }
            ?: LocalDateTime.now().plusMinutes(30).withSecond(0).withNano(0)
        start = initialStart
        end = initialStart.plusHours(1)

        setupRepeatSpinner()
        setupPickers()
        binding.allDaySwitch.setOnCheckedChangeListener { _, checked ->
            allDay = checked
            normalizeAllDayRange()
            renderTimes()
        }
        binding.cancelButton.setOnClickListener { finish() }
        binding.saveButton.setOnClickListener { save() }

        val id = eventId
        if (id == null) {
            binding.editHeading.setText(R.string.edit_title)
            renderTimes()
        } else {
            binding.editHeading.setText(R.string.edit_title_existing)
            loadExisting(id)
        }
    }

    private fun setupRepeatSpinner() {
        val adapter = ArrayAdapter.createFromResource(this, R.array.repeat_options, android.R.layout.simple_spinner_item)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.repeatSpinner.adapter = adapter
        binding.repeatSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                repeatIndex = position
                binding.repeatCountLayout.visibility = if (position == 0) View.GONE else View.VISIBLE
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setupPickers() {
        binding.startDateButton.setOnClickListener {
            pickDate(start.toLocalDate()) { date ->
                start = LocalDateTime.of(date, start.toLocalTime())
                if (!allDay && !end.isAfter(start)) end = start.plusHours(1)
                renderTimes()
            }
        }
        binding.endDateButton.setOnClickListener {
            val displayEnd = if (allDay) end.toLocalDate().minusDays(1) else end.toLocalDate()
            pickDate(displayEnd) { date ->
                if (allDay) {
                    end = date.plusDays(1).atStartOfDay()
                } else {
                    end = LocalDateTime.of(date, end.toLocalTime())
                }
                renderTimes()
            }
        }
        binding.startTimeButton.setOnClickListener {
            pickTime(start.toLocalTime()) { time ->
                start = LocalDateTime.of(start.toLocalDate(), time)
                if (!end.isAfter(start)) end = start.plusHours(1)
                renderTimes()
            }
        }
        binding.endTimeButton.setOnClickListener {
            pickTime(end.toLocalTime()) { time ->
                end = LocalDateTime.of(end.toLocalDate(), time)
                renderTimes()
            }
        }
    }

    private fun pickDate(current: LocalDate, onPicked: (LocalDate) -> Unit) {
        DatePickerDialog(
            this,
            { _, year, month, day -> onPicked(LocalDate.of(year, month + 1, day)) },
            current.year,
            current.monthValue - 1,
            current.dayOfMonth,
        ).show()
    }

    private fun pickTime(current: LocalTime, onPicked: (LocalTime) -> Unit) {
        TimePickerDialog(
            this,
            { _, hour, minute -> onPicked(LocalTime.of(hour, minute)) },
            current.hour,
            current.minute,
            true,
        ).show()
    }

    private fun normalizeAllDayRange() {
        if (allDay) {
            val startDate = start.toLocalDate()
            val lastDay = end.toLocalDate().let { if (end.toLocalTime() == LocalTime.MIDNIGHT) it.minusDays(1) else it }
            start = startDate.atStartOfDay()
            end = (if (lastDay.isAfter(startDate)) lastDay else startDate).plusDays(1).atStartOfDay()
        } else {
            start = start.toLocalDate().atTime(9, 0)
            end = start.plusHours(1)
        }
    }

    private fun renderTimes() {
        binding.startDateButton.text = start.toLocalDate().format(dateFormatter)
        binding.endDateButton.text = (if (allDay) end.toLocalDate().minusDays(1) else end.toLocalDate()).format(dateFormatter)
        binding.startTimeButton.text = start.toLocalTime().format(timeFormatter)
        binding.endTimeButton.text = end.toLocalTime().format(timeFormatter)
        val timeVisibility = if (allDay) View.GONE else View.VISIBLE
        binding.startTimeButton.visibility = timeVisibility
        binding.endTimeButton.visibility = timeVisibility
        binding.editTimezoneHint.text = getString(R.string.timezone_hint, eventTzid)
    }

    private fun loadExisting(id: String) {
        lifecycleScope.launch(AppGraph.mainDispatcher) {
            val stored = runCatching { repository.get(id) }.getOrNull()
            if (stored == null) {
                Toast.makeText(this@EventEditActivity, R.string.event_missing, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            existing = stored.master
            val master = stored.master
            originalRuleRaw = master.rruleRaw
            eventTzid = master.tzid
            allDay = master.allDay
            binding.allDaySwitch.isChecked = allDay
            binding.titleInput.setText(master.title)
            binding.noteInput.setText(master.description)
            start = WallTime.parse(master.dtstartWall)
            end = WallTime.parse(master.dtendWall)
            originalStartDate = start.toLocalDate()
            bindRepeatSelection(master.rruleRaw)
            val ruleComplex = isRuleComplex(master)
            repeatLocked = ruleComplex
            binding.repeatSpinner.isEnabled = !ruleComplex
            binding.repeatCountInput.isEnabled = !ruleComplex
            binding.seriesWarning.setText(if (ruleComplex) R.string.series_locked else R.string.series_warning)
            binding.seriesWarning.visibility = if (master.rruleRaw.isBlank()) View.GONE else View.VISIBLE
            renderTimes()
        }
    }

    private fun isRuleComplex(master: EventMaster): Boolean {
        if (master.rruleRaw.isBlank()) return false
        val rule = RRule.parse(master.rruleRaw)
        return rule.isReadOnly ||
            master.exdates.isNotEmpty() ||
            master.rdates.isNotEmpty() ||
            rule.interval > 1 ||
            rule.wkst != java.time.DayOfWeek.MONDAY ||
            rule.byDay.size > 1 ||
            rule.byMonthDay.size > 1 ||
            rule.until != null
    }

    private fun bindRepeatSelection(rruleRaw: String) {
        val rule = RRule.parse(rruleRaw)
        val index = when (rule.freq) {
            RRule.Freq.DAILY -> 1
            RRule.Freq.WEEKLY -> 2
            RRule.Freq.MONTHLY -> 3
            RRule.Freq.YEARLY -> 4
            null -> 0
        }
        binding.repeatSpinner.setSelection(index)
        originalRepeatIndex = index
        rule.count?.let { binding.repeatCountInput.setText(it.toString()) }
        originalCountText = rule.count?.toString().orEmpty()
    }

    private fun selectedFrequency(): RRule.Freq? = when (repeatIndex) {
        1 -> RRule.Freq.DAILY
        2 -> RRule.Freq.WEEKLY
        3 -> RRule.Freq.MONTHLY
        4 -> RRule.Freq.YEARLY
        else -> null
    }

    private fun save() {
        if (saving) return
        binding.titleLayout.error = null
        binding.repeatCountLayout.error = null

        val title = binding.titleInput.text?.toString().orEmpty().trim()
        if (title.isEmpty()) {
            binding.titleLayout.error = getString(R.string.error_title_empty)
            binding.titleInput.requestFocus()
            return
        }

        val frequency = selectedFrequency()
        val countText = binding.repeatCountInput.text?.toString().orEmpty().trim()
        val count = if (frequency == null || countText.isEmpty()) {
            null
        } else {
            val parsed = countText.toIntOrNull()
            if (parsed == null || parsed < 1) {
                binding.repeatCountLayout.error = getString(R.string.error_repeat_count)
                return
            }
            parsed
        }

        if (allDay) {
            if (!end.toLocalDate().isAfter(start.toLocalDate())) {
                Toast.makeText(this, R.string.error_all_day_range, Toast.LENGTH_SHORT).show()
                return
            }
        } else if (!end.isAfter(start)) {
            Toast.makeText(this, R.string.error_time_range, Toast.LENGTH_SHORT).show()
            return
        }

        val startWall = WallTime.format(start)
        val endWall = WallTime.format(end)
        val repeat = RepeatSpec(frequency = frequency, interval = 1, count = count)
        val draft = EventDraft(
            title = title,
            allDay = allDay,
            startWall = startWall,
            endWall = endWall,
            tzid = eventTzid,
            description = binding.noteInput.text?.toString().orEmpty(),
            repeat = repeat,
        )

        val errors = repository.validate(draft)
        if (errors.isNotEmpty()) {
            Toast.makeText(this, errors.first(), Toast.LENGTH_SHORT).show()
            return
        }

        saving = true
        binding.saveButton.isEnabled = false
        lifecycleScope.launch(AppGraph.mainDispatcher) {
            val result = runCatching {
                val master = existing
                if (master == null) {
                    repository.create(draft)
                } else {
                    val startDateUnchanged = originalStartDate == start.toLocalDate()
                    val repeatUnchanged = repeatIndex == originalRepeatIndex && countText == originalCountText
                    val rruleRaw = when {
                        repeatLocked -> originalRuleRaw
                        startDateUnchanged && repeatUnchanged -> originalRuleRaw
                        else -> RRuleBuilder.build(repeat, startWall)
                    }
                    repository.update(
                        master.copy(
                            title = title,
                            description = draft.description.trim(),
                            allDay = allDay,
                            dtstartWall = startWall,
                            dtendWall = endWall,
                            tzid = eventTzid,
                            rruleRaw = rruleRaw,
                        ),
                    )
                }
            }
            saving = false
            binding.saveButton.isEnabled = true
            if (result.isSuccess) {
                setResult(RESULT_OK)
                finish()
            } else {
                Toast.makeText(this@EventEditActivity, R.string.save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val EXTRA_EVENT_ID = "event_id"
        private const val EXTRA_START_WALL = "start_wall"

        fun createIntent(context: Context, start: LocalDateTime): Intent =
            Intent(context, EventEditActivity::class.java)
                .putExtra(EXTRA_START_WALL, WallTime.format(start))

        fun editIntent(context: Context, eventId: String): Intent =
            Intent(context, EventEditActivity::class.java)
                .putExtra(EXTRA_EVENT_ID, eventId)
    }
}
