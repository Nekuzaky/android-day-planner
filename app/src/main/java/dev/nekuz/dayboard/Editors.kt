package dev.nekuz.dayboard

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

abstract class EditorActivity : Activity() {
    protected var populated = false
    protected var dialog: Dialog? = null
    protected var itemId = 0
    private val listener: () -> Unit = { onStoreChanged() }

    protected abstract fun populate(state: StoreState): Boolean

    protected open fun renderEditor() = Unit

    protected fun bindBack() {
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        populated = savedInstanceState?.getBoolean(STATE_POPULATED) ?: false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_POPULATED, populated)
    }

    override fun onStart() {
        super.onStart()
        Store.addListener(listener)
        Store.start(this)
        onStoreChanged()
    }

    override fun onStop() {
        super.onStop()
        Store.removeListener(listener)
        Store.flush()
    }

    override fun onDestroy() {
        super.onDestroy()
        dialog?.dismiss()
    }

    private fun onStoreChanged() {
        val state = Store.state
        if (!state.loaded) return
        if (!populated) {
            if (!populate(state)) {
                Actions.toast(this, R.string.item_missing)
                finish()
                return
            }
            populated = true
        }
        renderEditor()
    }

    protected fun confirmDelete(title: Int, message: Int, delete: () -> Unit) {
        dialog = Actions.show(
            this,
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.delete) { _, _ ->
                    delete()
                    finish()
                }
                .setNegativeButton(R.string.cancel, null),
        )
    }

    protected fun pickTime(initial: LocalTime, chosen: (LocalTime) -> Unit) {
        val picker = TimePickerDialog(
            this,
            { _, hour, minute -> chosen(LocalTime.of(hour, minute)) },
            initial.hour,
            initial.minute,
            DateFormat.is24HourFormat(this),
        )
        picker.setOnShowListener { picker.window?.decorView?.filterTouchesWhenObscured = true }
        dialog = picker
        picker.show()
    }

    private companion object {
        const val STATE_POPULATED = "populated"
    }
}

class TaskEditActivity : EditorActivity() {
    private lateinit var titleInput: EditText
    private lateinit var dateButton: Button
    private lateinit var timeButton: Button
    private lateinit var clearTime: Button
    private lateinit var remindSwitch: Switch
    private lateinit var reminderNote: TextView
    private lateinit var fixNotifications: Button
    private lateinit var fixExact: Button
    private lateinit var repeatSpinner: Spinner
    private lateinit var repeatNote: TextView
    private lateinit var dayRow: View
    private lateinit var saveButton: Button
    private lateinit var deleteButton: Button
    private lateinit var dayButtons: Map<DayOfWeek, Button>

    private var date: LocalDate = LocalDate.now(Device.zone())
    private var time: LocalTime? = null
    private var remind = false
    private var repeatKind = KIND_NONE
    private var days: Set<DayOfWeek> = emptySet()
    private var monthDay = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_task)
        itemId = intent.getIntExtra(EXTRA_ID, NEW_ID)
        titleInput = findViewById(R.id.titleInput)
        dateButton = findViewById(R.id.dateButton)
        timeButton = findViewById(R.id.timeButton)
        clearTime = findViewById(R.id.clearTime)
        remindSwitch = findViewById(R.id.remindSwitch)
        reminderNote = findViewById(R.id.reminderNote)
        fixNotifications = findViewById(R.id.fixNotifications)
        fixExact = findViewById(R.id.fixExact)
        repeatSpinner = findViewById(R.id.repeatSpinner)
        repeatNote = findViewById(R.id.repeatNote)
        dayRow = findViewById(R.id.dayRow)
        saveButton = findViewById(R.id.saveButton)
        deleteButton = findViewById(R.id.deleteButton)
        dayButtons = mapOf(
            DayOfWeek.MONDAY to findViewById(R.id.dayMon),
            DayOfWeek.TUESDAY to findViewById(R.id.dayTue),
            DayOfWeek.WEDNESDAY to findViewById(R.id.dayWed),
            DayOfWeek.THURSDAY to findViewById(R.id.dayThu),
            DayOfWeek.FRIDAY to findViewById(R.id.dayFri),
            DayOfWeek.SATURDAY to findViewById(R.id.daySat),
            DayOfWeek.SUNDAY to findViewById(R.id.daySun),
        )
        bindBack()
        restore(savedInstanceState)
        repeatSpinner.adapter = ArrayAdapter.createFromResource(
            this,
            R.array.repeat_kinds,
            android.R.layout.simple_spinner_dropdown_item,
        )
        repeatSpinner.setSelection(repeatKind)
        repeatSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == repeatKind) return
                repeatKind = position
                if (repeatKind == KIND_WEEKLY && days.isEmpty()) days = setOf(date.dayOfWeek)
                if (repeatKind == KIND_MONTHLY) monthDay = date.dayOfMonth
                renderEditor()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        dayButtons.forEach { (day, button) ->
            button.text = Format.shortDay(day)
            button.setOnClickListener {
                days = if (day in days) days - day else days + day
                renderEditor()
            }
        }
        dateButton.setOnClickListener { pickDate() }
        timeButton.setOnClickListener {
            pickTime(time ?: LocalTime.of(DEFAULT_HOUR, 0)) {
                time = it
                renderEditor()
            }
        }
        clearTime.setOnClickListener {
            time = null
            remind = false
            renderEditor()
        }
        remindSwitch.setOnClickListener {
            remind = remindSwitch.isChecked && time != null
            if (remind && Notifications.needsPrompt(this)) requestNotifications()
            renderEditor()
        }
        fixNotifications.setOnClickListener { Reminding.openNotificationSettings(this) }
        fixExact.setOnClickListener { Reminding.openExactSettings(this) }
        saveButton.setOnClickListener { save() }
        deleteButton.setOnClickListener {
            confirmDelete(R.string.delete_task_title, R.string.delete_task_message) {
                val id = itemId
                Store.update(this) { Boards.deleteTask(it, id) }
            }
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Store.resync(this, false)
        renderEditor()
    }

    override fun onResume() {
        super.onResume()
        renderEditor()
    }

    private fun restore(saved: Bundle?) {
        if (saved == null) return
        date = LocalDate.ofEpochDay(saved.getLong(STATE_DATE, date.toEpochDay()))
        val minutes = saved.getInt(STATE_TIME, -1)
        time = if (minutes in 0 until MINUTES_IN_DAY) LocalTime.of(minutes / 60, minutes % 60) else null
        remind = saved.getBoolean(STATE_REMIND) && time != null
        repeatKind = saved.getInt(STATE_KIND, KIND_NONE).coerceIn(KIND_NONE, KIND_MONTHLY)
        days = (saved.getIntArray(STATE_DAYS) ?: IntArray(0))
            .filter { it in 1..7 }
            .mapTo(HashSet()) { DayOfWeek.of(it) }
        monthDay = saved.getInt(STATE_MONTH_DAY, 1).coerceIn(1, 31)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_DATE, date.toEpochDay())
        outState.putInt(STATE_TIME, time?.let { it.hour * 60 + it.minute } ?: -1)
        outState.putBoolean(STATE_REMIND, remind)
        outState.putInt(STATE_KIND, repeatKind)
        outState.putIntArray(STATE_DAYS, days.map { it.value }.toIntArray())
        outState.putInt(STATE_MONTH_DAY, monthDay)
    }

    override fun populate(state: StoreState): Boolean {
        if (itemId == NEW_ID) {
            date = LocalDate.now(Device.zone())
            return true
        }
        val task = state.board.tasks.firstOrNull { it.id == itemId } ?: return false
        titleInput.setText(task.title)
        date = task.date
        time = task.time
        remind = task.remind
        when (val repeat = task.repeat) {
            Repeat.None -> repeatKind = KIND_NONE
            Repeat.Daily -> repeatKind = KIND_DAILY
            Repeat.Weekdays -> repeatKind = KIND_WEEKDAYS
            is Repeat.Weekly -> {
                repeatKind = KIND_WEEKLY
                days = repeat.days
            }
            is Repeat.Monthly -> {
                repeatKind = KIND_MONTHLY
                monthDay = repeat.day
            }
        }
        repeatSpinner.setSelection(repeatKind)
        return true
    }

    override fun renderEditor() {
        val ready = Store.state.loaded
        saveButton.isEnabled = ready
        deleteButton.visibility = if (itemId == NEW_ID) View.GONE else View.VISIBLE
        dateButton.text = getString(R.string.editor_date, Format.date(date))
        val is24 = DateFormat.is24HourFormat(this)
        val currentTime = time
        timeButton.text = if (currentTime == null) {
            getString(R.string.editor_time_none)
        } else {
            getString(R.string.editor_time, Format.time(currentTime, is24))
        }
        clearTime.visibility = if (currentTime == null) View.GONE else View.VISIBLE
        remindSwitch.isEnabled = currentTime != null
        remindSwitch.isChecked = remind && currentTime != null
        renderReminderState(currentTime)
        dayRow.visibility = if (repeatKind == KIND_WEEKLY) View.VISIBLE else View.GONE
        dayButtons.forEach { (day, button) ->
            val selected = day in days
            button.setBackgroundResource(if (selected) R.drawable.bg_button_primary else R.drawable.bg_button_outline)
            button.setTextColor(getColor(if (selected) R.color.on_brand else R.color.text_primary))
        }
        repeatNote.visibility = if (repeatKind == KIND_MONTHLY || repeatKind == KIND_WEEKLY) View.VISIBLE else View.GONE
        repeatNote.text = when (repeatKind) {
            KIND_MONTHLY -> getString(R.string.repeat_monthly_note, monthDay)
            else -> getString(R.string.repeat_weekly_note)
        }
    }

    private fun renderReminderState(currentTime: LocalTime?) {
        val refused = remind && !Notifications.enabled(this)
        val inexact = remind && !Alarms.canExact(this)
        reminderNote.text = when {
            currentTime == null -> getString(R.string.reminder_needs_time)
            !remind -> getString(R.string.reminder_off)
            refused -> getString(R.string.notice_notifications)
            inexact -> getString(R.string.notice_exact)
            else -> getString(R.string.reminder_exact)
        }
        fixNotifications.visibility = if (refused) View.VISIBLE else View.GONE
        fixExact.visibility = if (!refused && inexact) View.VISIBLE else View.GONE
    }

    private fun pickDate() {
        val picker = DatePickerDialog(
            this,
            { _, year, month, day ->
                date = LocalDate.of(year, month + 1, day)
                monthDay = date.dayOfMonth
                renderEditor()
            },
            date.year,
            date.monthValue - 1,
            date.dayOfMonth,
        )
        picker.setOnShowListener { picker.window?.decorView?.filterTouchesWhenObscured = true }
        dialog = picker
        picker.show()
    }

    private fun currentRepeat(): Repeat = when (repeatKind) {
        KIND_DAILY -> Repeat.Daily
        KIND_WEEKDAYS -> Repeat.Weekdays
        KIND_WEEKLY -> Repeat.Weekly(days)
        KIND_MONTHLY -> Repeat.Monthly(monthDay)
        else -> Repeat.None
    }

    private fun save() {
        val state = Store.state
        if (!state.loaded) return
        val repeat = currentRepeat()
        val draft = TaskDraft(titleInput.text.toString(), date, time, remind && time != null, repeat)
        if (Titles.clean(draft.title, Limits.MAX_TITLE).isEmpty()) {
            titleInput.error = getString(R.string.title_required)
            return
        }
        if (!Recurrence.isValid(repeat)) {
            Actions.toast(this, R.string.repeat_needs_day)
            return
        }
        if (!Zones.isValidYear(date)) {
            Actions.toast(this, R.string.date_out_of_range)
            return
        }
        val id = itemId
        if (id == NEW_ID) {
            if (state.board.tasks.size >= Limits.MAX_TASKS) {
                Actions.toast(this, R.string.limit_reached)
                return
            }
            Store.update(this) { Boards.addTask(it, draft, Instant.now(), Device.zone()) }
        } else {
            Store.update(this) { Boards.updateTask(it, id, draft, Instant.now(), Device.zone()) }
        }
        finish()
    }

    companion object {
        const val EXTRA_ID = "id"
        const val NEW_ID = 0
        private const val KIND_NONE = 0
        private const val KIND_DAILY = 1
        private const val KIND_WEEKDAYS = 2
        private const val KIND_WEEKLY = 3
        private const val KIND_MONTHLY = 4
        private const val DEFAULT_HOUR = 9
        private const val MINUTES_IN_DAY = 1440
        private const val REQUEST_NOTIFICATIONS = 10
        private const val STATE_DATE = "date"
        private const val STATE_TIME = "time"
        private const val STATE_REMIND = "remind"
        private const val STATE_KIND = "kind"
        private const val STATE_DAYS = "days"
        private const val STATE_MONTH_DAY = "monthDay"
    }
}

class HabitEditActivity : EditorActivity() {
    private lateinit var nameInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_habit)
        itemId = intent.getIntExtra(EXTRA_ID, 0)
        nameInput = findViewById(R.id.nameInput)
        bindBack()
        findViewById<View>(R.id.saveButton).setOnClickListener { save() }
        findViewById<View>(R.id.deleteButton).setOnClickListener {
            confirmDelete(R.string.delete_habit_title, R.string.delete_habit_message) {
                val id = itemId
                Store.update(this) { Boards.deleteHabit(it, id) }
            }
        }
    }

    override fun populate(state: StoreState): Boolean {
        val habit = state.board.habits.firstOrNull { it.id == itemId } ?: return false
        nameInput.setText(habit.name)
        return true
    }

    private fun save() {
        if (!Store.state.loaded) return
        val text = nameInput.text.toString()
        if (Titles.clean(text, Limits.MAX_HABIT_NAME).isEmpty()) {
            nameInput.error = getString(R.string.title_required)
            return
        }
        val id = itemId
        Store.update(this) { Boards.renameHabit(it, id, text) }
        finish()
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}

class SlotEditActivity : EditorActivity() {
    private lateinit var titleInput: EditText
    private lateinit var daySpinner: Spinner
    private lateinit var startButton: Button
    private lateinit var endButton: Button
    private lateinit var deleteButton: Button

    private var start = LocalTime.of(DEFAULT_START, 0)
    private var end = LocalTime.of(DEFAULT_END, 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_slot)
        itemId = intent.getIntExtra(EXTRA_ID, NEW_ID)
        titleInput = findViewById(R.id.titleInput)
        daySpinner = findViewById(R.id.daySpinner)
        startButton = findViewById(R.id.startButton)
        endButton = findViewById(R.id.endButton)
        deleteButton = findViewById(R.id.deleteButton)
        bindBack()
        if (savedInstanceState != null) {
            start = minuteToTime(savedInstanceState.getInt(STATE_START, DEFAULT_START * 60))
            end = minuteToTime(savedInstanceState.getInt(STATE_END, DEFAULT_END * 60))
        }
        daySpinner.adapter = ArrayAdapter.createFromResource(
            this,
            R.array.week_days,
            android.R.layout.simple_spinner_dropdown_item,
        )
        startButton.setOnClickListener {
            pickTime(start) {
                start = it
                renderEditor()
            }
        }
        endButton.setOnClickListener {
            pickTime(end) {
                end = it
                renderEditor()
            }
        }
        findViewById<View>(R.id.saveButton).setOnClickListener { save() }
        deleteButton.setOnClickListener {
            confirmDelete(R.string.delete_slot_title, R.string.delete_slot_message) {
                val id = itemId
                Store.update(this) { Boards.deleteSlot(it, id) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_START, start.hour * 60 + start.minute)
        outState.putInt(STATE_END, end.hour * 60 + end.minute)
    }

    private fun minuteToTime(value: Int): LocalTime {
        val minutes = value.coerceIn(0, 1439)
        return LocalTime.of(minutes / 60, minutes % 60)
    }

    override fun populate(state: StoreState): Boolean {
        if (itemId == NEW_ID) return true
        val slot = state.board.slots.firstOrNull { it.id == itemId } ?: return false
        titleInput.setText(slot.title)
        daySpinner.setSelection(slot.day.value - 1)
        start = slot.start
        end = slot.end
        return true
    }

    override fun renderEditor() {
        val is24 = DateFormat.is24HourFormat(this)
        startButton.text = getString(R.string.editor_start, Format.time(start, is24))
        endButton.text = getString(R.string.editor_end, Format.time(end, is24))
        deleteButton.visibility = if (itemId == NEW_ID) View.GONE else View.VISIBLE
    }

    private fun save() {
        val state = Store.state
        if (!state.loaded) return
        val text = titleInput.text.toString()
        if (Titles.clean(text, Limits.MAX_SLOT_TITLE).isEmpty()) {
            titleInput.error = getString(R.string.title_required)
            return
        }
        if (start == end) {
            Actions.toast(this, R.string.slot_same_times)
            return
        }
        val day = DayOfWeek.of((daySpinner.selectedItemPosition + 1).coerceIn(1, 7))
        val slotStart = start
        val slotEnd = end
        val id = itemId
        if (id == NEW_ID) {
            if (state.board.slots.size >= Limits.MAX_SLOTS) {
                Actions.toast(this, R.string.limit_reached)
                return
            }
            Store.update(this) { Boards.addSlot(it, text, day, slotStart, slotEnd) }
        } else {
            Store.update(this) { Boards.updateSlot(it, id, text, day, slotStart, slotEnd) }
        }
        finish()
    }

    companion object {
        const val EXTRA_ID = "id"
        const val NEW_ID = 0
        private const val DEFAULT_START = 20
        private const val DEFAULT_END = 22
        private const val STATE_START = "start"
        private const val STATE_END = "end"
    }
}
