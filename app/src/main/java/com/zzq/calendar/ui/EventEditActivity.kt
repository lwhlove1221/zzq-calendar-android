package com.zzq.calendar.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.zzq.calendar.AppGraph
import com.zzq.calendar.R
import com.zzq.calendar.data.EventDraft
import com.zzq.calendar.data.RRuleBuilder
import com.zzq.calendar.databinding.ActivityEventEditBinding
import com.zzq.calendar.export.IcsExporter
import com.zzq.calendar.model.EventMaster
import com.zzq.calendar.model.RRule
import com.zzq.calendar.model.RepeatSpec
import com.zzq.calendar.model.Valarm
import com.zzq.calendar.model.WallTime
import com.zzq.calendar.reminder.ReminderPlanner
import com.zzq.calendar.reminder.ReminderScheduler
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.Instant
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
    private var writeBackChoice: Boolean? = null
    private var forceLocalOnly = false
    private var gatePending = false
    private var pendingAction: (() -> Unit)? = null
    private var pendingDelete = false
    private var alarms: List<Valarm> = emptyList()
    private var alarmsTouched = false

    private val createIcs = registerForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        uri?.let { exportCurrent(it) }
    }

    private val writePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants[Manifest.permission.WRITE_CALENDAR] == true
        val action = pendingAction
        val wasDelete = pendingDelete
        pendingAction = null
        pendingDelete = false
        gatePending = false
        if (granted) {
            action?.invoke()
            return@registerForActivityResult
        }
        val mirror = existing?.owner == EventMaster.OWNER_SYSTEM
        Toast.makeText(
            this,
            if (mirror) R.string.mirror_write_permission else R.string.writeback_permission,
            Toast.LENGTH_LONG,
        ).show()
        if (!mirror && !wasDelete) {
            forceLocalOnly = true
            action?.invoke()
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePrefs.applyAccent(this)
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
            if (!alarmsTouched) {
                alarms = if (checked) listOf(defaultAllDayAlarm()) else emptyList()
            }
            renderTimes()
        }
        binding.cancelButton.setOnClickListener { finish() }
        binding.saveButton.setOnClickListener { save() }
        binding.deleteButton.setOnClickListener { confirmDelete() }
        binding.reminderButton.setOnClickListener { pickReminders() }
        binding.exportButton.setOnClickListener {
            val stamp = LocalDate.now(zone).toString()
            createIcs.launch(getString(R.string.export_ics_name, stamp))
        }

        val id = eventId
        if (id == null) {
            binding.editHeading.setText(R.string.edit_title)
            renderTimes()
        } else {
            binding.editHeading.setText(R.string.edit_title_existing)
            binding.deleteButton.visibility = View.VISIBLE
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
        renderReminders()
    }

    private fun defaultAllDayAlarm(): Valarm =
        Valarm(ReminderPlanner.TRIGGER_CALENDAR, 9 * 3600, eventTzid)

    private fun renderReminders() {
        binding.reminderButton.text = if (alarms.isEmpty()) {
            getString(R.string.reminder_none)
        } else {
            alarms.joinToString("、") { labelOf(it.offsetSeconds) }
        }
    }

    private fun labelOf(offsetSeconds: Int): String = getString(
        when (offsetSeconds) {
            0 -> R.string.reminder_at_start
            -300 -> R.string.reminder_5m
            -900 -> R.string.reminder_15m
            -1800 -> R.string.reminder_30m
            -3600 -> R.string.reminder_1h
            -86_400 -> R.string.reminder_1d
            32_400 -> R.string.reminder_allday_9
            -14_400 -> R.string.reminder_allday_prev20
            -54_000 -> R.string.reminder_allday_prev9
            else -> R.string.reminder_custom
        },
        if (offsetSeconds == 0) 0 else -offsetSeconds / 60,
    )

    private data class ReminderOption(val label: Int, val alarm: Valarm)

    private fun reminderOptions(): List<ReminderOption> = if (allDay) {
        listOf(
            ReminderOption(R.string.reminder_allday_9, Valarm(ReminderPlanner.TRIGGER_CALENDAR, 32_400, eventTzid)),
            ReminderOption(R.string.reminder_allday_prev20, Valarm(ReminderPlanner.TRIGGER_CALENDAR, -14_400, eventTzid)),
            ReminderOption(R.string.reminder_allday_prev9, Valarm(ReminderPlanner.TRIGGER_CALENDAR, -54_000, eventTzid)),
        )
    } else {
        listOf(
            ReminderOption(R.string.reminder_at_start, Valarm("duration", 0, eventTzid)),
            ReminderOption(R.string.reminder_5m, Valarm("duration", -300, eventTzid)),
            ReminderOption(R.string.reminder_15m, Valarm("duration", -900, eventTzid)),
            ReminderOption(R.string.reminder_30m, Valarm("duration", -1_800, eventTzid)),
            ReminderOption(R.string.reminder_1h, Valarm("duration", -3_600, eventTzid)),
            ReminderOption(R.string.reminder_1d, Valarm("duration", -86_400, eventTzid)),
        )
    }

    private fun pickReminders() {
        val options = reminderOptions()
        val checked = BooleanArray(options.size) { index ->
            alarms.any {
                it.triggerType == options[index].alarm.triggerType &&
                    it.offsetSeconds == options[index].alarm.offsetSeconds
            }
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.reminder_pick_title)
            .setMultiChoiceItems(options.map { getString(it.label) }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setNeutralButton(R.string.reminder_pick_clear) { _, _ ->
                alarms = emptyList()
                alarmsTouched = true
                renderReminders()
            }
            .setPositiveButton(R.string.reminder_pick_done) { _, _ ->
                alarms = options.filterIndexed { index, _ -> checked[index] }.map { it.alarm }
                alarmsTouched = true
                renderReminders()
                if (alarms.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !ReminderScheduler.notificationsAllowed(this)
                ) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        if (!ReminderScheduler.notificationsAllowed(this)) {
            builder.setMessage(R.string.reminder_notify_hint)
        }
        builder.show()
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
            alarms = master.valarms
            alarmsTouched = true
            val ruleComplex = isRuleComplex(master)
            repeatLocked = ruleComplex
            binding.repeatSpinner.isEnabled = !ruleComplex
            binding.repeatCountInput.isEnabled = !ruleComplex
            binding.seriesWarning.setText(if (ruleComplex) R.string.series_locked else R.string.series_warning)
            binding.seriesWarning.visibility = if (master.rruleRaw.isBlank()) View.GONE else View.VISIBLE
            renderTimes()
            binding.exportButton.visibility =
                if (master.owner == EventMaster.OWNER_SYSTEM) View.GONE else View.VISIBLE
            val reminderVisibility = if (master.owner == EventMaster.OWNER_SYSTEM) View.GONE else View.VISIBLE
            binding.reminderButton.visibility = reminderVisibility
            binding.reminderLabel.visibility = reminderVisibility
        }
    }

    private fun exportCurrent(uri: Uri) {
        val id = eventId ?: return
        lifecycleScope.launch(AppGraph.mainDispatcher) {
            val written = runCatching {
                val stored = repository.get(id) ?: error("event missing")
                val text = IcsExporter.export(listOf(stored), Instant.now())
                val stream = contentResolver.openOutputStream(uri) ?: error("cannot open export target")
                stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            }
            Toast.makeText(
                this@EventEditActivity,
                if (written.isSuccess) R.string.export_done else R.string.export_failed,
                Toast.LENGTH_SHORT,
            ).show()
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
        if (saving || gatePending) return
        if (!validateInput()) return
        if (!ensureWritePolicy()) return
        doSave()
    }

    private fun validateInput(): Boolean {
        binding.titleLayout.error = null
        binding.repeatCountLayout.error = null

        val title = binding.titleInput.text?.toString().orEmpty().trim()
        if (title.isEmpty()) {
            binding.titleLayout.error = getString(R.string.error_title_empty)
            binding.titleInput.requestFocus()
            return false
        }

        val frequency = selectedFrequency()
        val countText = binding.repeatCountInput.text?.toString().orEmpty().trim()
        if (frequency != null && countText.isNotEmpty()) {
            val parsed = countText.toIntOrNull()
            if (parsed == null || parsed < 1) {
                binding.repeatCountLayout.error = getString(R.string.error_repeat_count)
                return false
            }
        }

        if (allDay) {
            if (!end.toLocalDate().isAfter(start.toLocalDate())) {
                Toast.makeText(this, R.string.error_all_day_range, Toast.LENGTH_SHORT).show()
                return false
            }
        } else if (!end.isAfter(start)) {
            Toast.makeText(this, R.string.error_time_range, Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    /** Asks for the target calendar / imported-item choice before the first save. */
    private fun ensureWritePolicy(): Boolean {
        val current = existing
        if (current?.owner == EventMaster.OWNER_SYSTEM) {
            if (!hasWritePermission()) {
                requestWriteAccess()
                return false
            }
            return true
        }
        val imported = current?.owner == EventMaster.OWNER_IMPORTED
        if (imported && writeBackChoice == null) {
            gatePending = true
            AlertDialog.Builder(this)
                .setTitle(R.string.import_choice_title)
                .setMessage(R.string.import_choice_message)
                .setPositiveButton(R.string.import_choice_write) { _, _ ->
                    gatePending = false
                    writeBackChoice = true
                    save()
                }
                .setNegativeButton(R.string.import_choice_local) { _, _ ->
                    gatePending = false
                    writeBackChoice = false
                    save()
                }
                .show()
            return false
        }
        val writeBack = when {
            forceLocalOnly -> false
            imported -> writeBackChoice == true
            else -> current?.writeBack ?: true
        }
        if (!writeBack) return true
        if (!hasWritePermission()) {
            requestWriteAccess()
            return false
        }
        if (repository.targetCalendarId() == null) {
            chooseTargetCalendar()
            return false
        }
        return true
    }

    private fun hasWritePermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestWriteAccess() {
        gatePending = true
        pendingAction = { save() }
        writePermission.launch(
            arrayOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR),
        )
    }

    private fun chooseTargetCalendar() {
        gatePending = true
        lifecycleScope.launch(AppGraph.mainDispatcher) {
            val calendars = repository.writableCalendars()
            if (calendars.isEmpty()) {
                gatePending = false
                forceLocalOnly = true
                Toast.makeText(this@EventEditActivity, R.string.writeback_no_calendar, Toast.LENGTH_LONG).show()
                save()
                return@launch
            }
            AlertDialog.Builder(this@EventEditActivity)
                .setTitle(R.string.writeback_pick_title)
                .setItems(calendars.map { it.name }.toTypedArray()) { _, index ->
                    gatePending = false
                    repository.setTargetCalendarId(calendars[index].id)
                    save()
                }
                .setOnCancelListener {
                    gatePending = false
                    forceLocalOnly = true
                    save()
                }
                .show()
        }
    }

    private fun doSave() {
        binding.titleLayout.error = null

        val title = binding.titleInput.text?.toString().orEmpty().trim()
        val frequency = selectedFrequency()
        val countText = binding.repeatCountInput.text?.toString().orEmpty().trim()
        val count = if (frequency == null || countText.isEmpty()) null else countText.toInt()

        val startWall = WallTime.format(start)
        val endWall = WallTime.format(end)
        val repeat = RepeatSpec(frequency = frequency, interval = 1, count = count)
        val current = existing
        val writeBack = when {
            current?.owner == EventMaster.OWNER_SYSTEM -> true
            forceLocalOnly -> false
            current?.owner == EventMaster.OWNER_IMPORTED -> writeBackChoice == true
            else -> current?.writeBack ?: true
        }
        val draft = EventDraft(
            title = title,
            allDay = allDay,
            startWall = startWall,
            endWall = endWall,
            tzid = eventTzid,
            description = binding.noteInput.text?.toString().orEmpty(),
            repeat = repeat,
            valarms = alarms,
            writeBack = writeBack,
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
                            valarms = alarms,
                            owner = if (master.owner == EventMaster.OWNER_IMPORTED) {
                                EventMaster.OWNER_APP
                            } else {
                                master.owner
                            },
                            writeBack = writeBack,
                        ),
                    )
                }
            }
            saving = false
            binding.saveButton.isEnabled = true
            if (result.isSuccess) {
                AppGraph.reminders(this@EventEditActivity).reconcileAsync(force = true)
                if (alarms.isNotEmpty() && !ReminderScheduler.notificationsAllowed(this@EventEditActivity)) {
                    Toast.makeText(this@EventEditActivity, R.string.reminder_notify_off, Toast.LENGTH_LONG).show()
                } else if (alarms.isNotEmpty() && !ReminderScheduler.exactAllowed(this@EventEditActivity)) {
                    Toast.makeText(this@EventEditActivity, R.string.reminder_exact_off, Toast.LENGTH_LONG).show()
                }
                setResult(RESULT_OK)
                finish()
            } else {
                Toast.makeText(this@EventEditActivity, R.string.save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDelete() {
        val id = eventId ?: return
        val master = existing ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(R.string.delete_confirm_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (master.systemEventId != null && !hasWritePermission()) {
                    gatePending = true
                    pendingDelete = true
                    pendingAction = { confirmDeleteAfterPermission(id) }
                    writePermission.launch(
                        arrayOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR),
                    )
                } else {
                    deleteEvent(id)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteAfterPermission(id: String) {
        gatePending = false
        deleteEvent(id)
    }

    private fun deleteEvent(id: String) {
        saving = true
        binding.deleteButton.isEnabled = false
        lifecycleScope.launch(AppGraph.mainDispatcher) {
            val deleted = runCatching { repository.delete(id) }.getOrDefault(false)
            saving = false
            if (deleted) {
                AppGraph.reminders(this@EventEditActivity).reconcileAsync(force = true)
                setResult(RESULT_OK)
                finish()
            } else {
                binding.deleteButton.isEnabled = true
                Toast.makeText(this@EventEditActivity, R.string.delete_failed, Toast.LENGTH_SHORT).show()
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
