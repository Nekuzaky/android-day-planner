package dev.nekuz.dayboard

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class MainActivity : Activity(), RowActions {

    private enum class Tab { TODAY, HABITS, STREAM, BACKUP }

    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var bar: ProgressBar
    private lateinit var adapter: RowAdapter
    private lateinit var tabs: Map<Tab, Button>
    private lateinit var panels: Map<Tab, View>
    private lateinit var quickInput: EditText
    private lateinit var habitInput: EditText
    private lateinit var copyButton: Button

    private var tab = Tab.TODAY
    private var corruptPending: String? = null
    private var dialog: AlertDialog? = null
    private var importDialog: AlertDialog? = null
    private var importShown: Board? = null
    private var errorShown: String? = null
    private val listener: () -> Unit = { render() }
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        bar = findViewById(R.id.bar)
        quickInput = findViewById(R.id.quickInput)
        habitInput = findViewById(R.id.habitInput)
        copyButton = findViewById(R.id.copySchedule)

        tab = savedInstanceState?.getString(STATE_TAB)?.let { name ->
            Tab.entries.firstOrNull { it.name == name }
        } ?: Tab.TODAY
        corruptPending = savedInstanceState?.getString(STATE_CORRUPT)

        adapter = RowAdapter(this, this)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> onRowClick(adapter.rows.getOrNull(position)) }

        tabs = mapOf(
            Tab.TODAY to findViewById(R.id.tabToday),
            Tab.HABITS to findViewById(R.id.tabHabits),
            Tab.STREAM to findViewById(R.id.tabStream),
            Tab.BACKUP to findViewById(R.id.tabBackup),
        )
        panels = mapOf(
            Tab.TODAY to findViewById(R.id.panelToday),
            Tab.HABITS to findViewById(R.id.panelHabits),
            Tab.STREAM to findViewById(R.id.panelStream),
            Tab.BACKUP to findViewById(R.id.panelBackup),
        )
        tabs.forEach { (target, button) ->
            button.setOnClickListener {
                tab = target
                hideKeyboard()
                render()
                list.setSelection(0)
            }
        }

        findViewById<View>(R.id.quickAdd).setOnClickListener { quickAdd() }
        findViewById<View>(R.id.addDetails).setOnClickListener { openTask(TaskEditActivity.NEW_ID) }
        quickInput.setOnEditorActionListener { _, action, event ->
            if (Keys.isSubmit(action, event)) {
                quickAdd()
                true
            } else {
                false
            }
        }
        findViewById<View>(R.id.habitAdd).setOnClickListener { addHabit() }
        habitInput.setOnEditorActionListener { _, action, event ->
            if (Keys.isSubmit(action, event)) {
                addHabit()
                true
            } else {
                false
            }
        }
        findViewById<View>(R.id.slotAdd).setOnClickListener { openSlot(SlotEditActivity.NEW_ID) }
        copyButton.setOnClickListener { copySchedule() }
        findViewById<View>(R.id.exportButton).setOnClickListener { startExport() }
        findViewById<View>(R.id.importButton).setOnClickListener { startImport() }
        findViewById<View>(R.id.infoButton).setOnClickListener {
            dialog = Actions.show(
                this,
                AlertDialog.Builder(this)
                    .setTitle(R.string.info)
                    .setMessage(R.string.info_message)
                    .setPositiveButton(R.string.close, null),
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, tab.name)
        outState.putString(STATE_CORRUPT, corruptPending)
    }

    override fun onStart() {
        super.onStart()
        Store.addListener(listener)
        Store.resync(this, false)
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        Store.removeListener(listener)
        Store.flush()
    }

    override fun onDestroy() {
        super.onDestroy()
        dialog?.dismiss()
        importDialog?.dismiss()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null || uri.scheme != CONTENT_SCHEME) return
        when (requestCode) {
            REQUEST_EXPORT -> Store.export(this, uri)
            REQUEST_IMPORT -> Store.readImport(this, uri)
            REQUEST_CORRUPT -> corruptPending?.let { Store.exportCorrupt(this, it, uri) }
        }
    }

    override fun onTaskChecked(row: Row.TaskItem) {
        if (row.checked) {
            Store.update(this) { Boards.uncomplete(it, row.id, Instant.now(), Device.zone()) }
        } else {
            val today = LocalDate.now(Device.zone())
            Store.update(this) { Boards.complete(it, row.id, row.date, today) }
        }
    }

    override fun onHabitChecked(row: Row.HabitItem) {
        val today = LocalDate.now(Device.zone())
        Store.update(this) { Boards.setCheckIn(it, row.id, today, !row.checked) }
    }

    private fun onRowClick(row: Row?) {
        when (row) {
            is Row.TaskItem -> openTask(row.id)
            is Row.HabitItem -> startActivity(
                Intent(this, HabitEditActivity::class.java).putExtra(HabitEditActivity.EXTRA_ID, row.id),
            )
            is Row.SlotItem -> openSlot(row.id)
            is Row.Notice -> onNotice(row.kind)
            else -> Unit
        }
    }

    private fun onNotice(kind: NoticeKind) {
        when (kind) {
            NoticeKind.RETRY_SAVE -> Store.retrySave(this)
            NoticeKind.STORAGE_BLOCKED -> Unit
            NoticeKind.CORRUPT -> showCorrupt()
            NoticeKind.NOTIFICATIONS -> Reminding.openNotificationSettings(this)
            NoticeKind.EXACT_ALARMS -> Reminding.openExactSettings(this)
            NoticeKind.CLEAR_DONE -> confirmClearDone()
        }
    }

    private fun openTask(id: Int) {
        startActivity(Intent(this, TaskEditActivity::class.java).putExtra(TaskEditActivity.EXTRA_ID, id))
    }

    private fun openSlot(id: Int) {
        startActivity(Intent(this, SlotEditActivity::class.java).putExtra(SlotEditActivity.EXTRA_ID, id))
    }

    private fun quickAdd() {
        val state = Store.state
        if (!state.loaded) return
        val text = quickInput.text.toString()
        if (Titles.clean(text, Limits.MAX_TITLE).isEmpty()) {
            quickInput.error = getString(R.string.title_required)
            return
        }
        if (state.board.tasks.size >= Limits.MAX_TASKS) {
            Actions.toast(this, R.string.limit_reached)
            return
        }
        val today = LocalDate.now(Device.zone())
        Store.update(this) { Boards.quickAdd(it, text, today) }
        quickInput.text.clear()
    }

    private fun addHabit() {
        val state = Store.state
        if (!state.loaded) return
        val text = habitInput.text.toString()
        if (Titles.clean(text, Limits.MAX_HABIT_NAME).isEmpty()) {
            habitInput.error = getString(R.string.title_required)
            return
        }
        if (state.board.habits.size >= Limits.MAX_HABITS) {
            Actions.toast(this, R.string.limit_reached)
            return
        }
        Store.update(this) { Boards.addHabit(it, text) }
        habitInput.text.clear()
    }

    private fun copySchedule() {
        val text = ScheduleText.render(Store.state.board.slots, Instant.now(), Device.zone())
        if (text.isNotEmpty()) Actions.copy(this, text)
    }

    private fun startExport() {
        if (!Store.state.loaded || Store.state.storageBlocked) {
            Actions.toast(this, R.string.export_failed)
            return
        }
        createDocument("dayboard-backup-${LocalDate.now(Device.zone())}.json", REQUEST_EXPORT)
    }

    private fun createDocument(name: String, request: Int) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(JSON_TYPE)
            .putExtra(Intent.EXTRA_TITLE, name)
        launch(intent, request)
    }

    private fun startImport() {
        if (!Store.state.loaded || Store.state.storageBlocked) {
            Actions.toast(this, R.string.import_unreadable)
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        launch(intent, REQUEST_IMPORT)
    }

    private fun launch(intent: Intent, request: Int) {
        try {
            startActivityForResult(intent, request)
        } catch (e: RuntimeException) {
            Actions.toast(this, R.string.picker_failed)
        }
    }

    private fun showCorrupt() {
        val name = Store.state.corruptNames.lastOrNull() ?: return
        dialog = Actions.show(
            this,
            AlertDialog.Builder(this)
                .setTitle(R.string.corrupt_title)
                .setMessage(getString(R.string.corrupt_message, ForeignText.clean(name)))
                .setPositiveButton(R.string.corrupt_save) { _, _ ->
                    corruptPending = name
                    createDocument(name, REQUEST_CORRUPT)
                }
                .setNegativeButton(R.string.corrupt_delete) { _, _ -> confirmDeleteCorrupt(name) }
                .setNeutralButton(R.string.close, null),
        )
    }

    private fun confirmDeleteCorrupt(name: String) {
        dialog = Actions.show(
            this,
            AlertDialog.Builder(this)
                .setTitle(R.string.corrupt_delete_title)
                .setMessage(R.string.corrupt_delete_message)
                .setPositiveButton(R.string.delete) { _, _ -> Store.deleteCorrupt(this, name) }
                .setNegativeButton(R.string.cancel, null),
        )
    }

    private fun confirmClearDone() {
        dialog = Actions.show(
            this,
            AlertDialog.Builder(this)
                .setTitle(R.string.clear_done_title)
                .setMessage(R.string.clear_done_message)
                .setPositiveButton(R.string.delete) { _, _ -> Store.update(this, Boards::clearDone) }
                .setNegativeButton(R.string.cancel, null),
        )
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun render() {
        val state = Store.state
        tabs.forEach { (target, button) ->
            val selected = target == tab
            button.setBackgroundResource(if (selected) R.drawable.bg_button_primary else R.drawable.bg_button_outline)
            button.setTextColor(getColor(if (selected) R.color.on_brand else R.color.text_primary))
        }
        panels.forEach { (target, panel) -> panel.visibility = if (target == tab) View.VISIBLE else View.GONE }
        bar.visibility = if (state.loaded) View.GONE else View.VISIBLE
        val rows = ArrayList<Row>()
        rows.addAll(storageNotices(state))
        if (state.loaded) {
            when (tab) {
                Tab.TODAY -> renderToday(state, rows)
                Tab.HABITS -> renderHabits(state, rows)
                Tab.STREAM -> renderStream(state, rows)
                Tab.BACKUP -> renderBackup(state, rows)
            }
        }
        adapter.rows = rows
        renderImport(state)
    }

    private fun showEmpty(text: Int?) {
        empty.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null) empty.setText(text)
    }

    private fun storageNotices(state: StoreState): List<Row> {
        val rows = ArrayList<Row>()
        if (state.storageBlocked) rows.add(Row.Notice(NoticeKind.STORAGE_BLOCKED, getString(R.string.notice_blocked)))
        if (state.saveFailed && !state.storageBlocked) {
            rows.add(Row.Notice(NoticeKind.RETRY_SAVE, getString(R.string.notice_save_failed)))
        }
        if (state.corruptNames.isNotEmpty()) rows.add(Row.Notice(NoticeKind.CORRUPT, getString(R.string.notice_corrupt)))
        return rows
    }

    private fun reminderNotices(state: StoreState): List<Row> {
        if (!Reminders.hasActiveReminder(state.board.tasks)) return emptyList()
        val rows = ArrayList<Row>()
        if (!Notifications.enabled(this)) {
            rows.add(Row.Notice(NoticeKind.NOTIFICATIONS, getString(R.string.notice_notifications)))
        }
        if (!Alarms.canExact(this)) {
            rows.add(Row.Notice(NoticeKind.EXACT_ALARMS, getString(R.string.notice_exact)))
        }
        return rows
    }

    private fun taskRow(task: Task, today: LocalDate, is24: Boolean, checked: Boolean, overdue: Boolean) = Row.TaskItem(
        id = task.id,
        date = task.date,
        title = task.title,
        meta = Format.taskMeta(task, today, is24),
        checked = checked,
        overdue = overdue,
    )

    private fun renderToday(state: StoreState, rows: MutableList<Row>) {
        rows.addAll(reminderNotices(state))
        val now = LocalDateTime.now(Device.zone())
        val today = now.toLocalDate()
        val is24 = DateFormat.is24HourFormat(this)
        val sections = TodayView.build(state.board.tasks, now)
        status.text = getString(R.string.today_status, Format.date(today), sections.overdue.size, sections.today.size)
        if (sections.overdue.isNotEmpty()) {
            rows.add(Row.Header(getString(R.string.section_overdue)))
            sections.overdue.forEach { rows.add(taskRow(it, today, is24, checked = false, overdue = true)) }
        }
        if (sections.today.isNotEmpty()) {
            rows.add(Row.Header(getString(R.string.section_today)))
            sections.today.forEach { rows.add(taskRow(it, today, is24, checked = false, overdue = false)) }
        }
        if (sections.upcoming.isNotEmpty()) {
            rows.add(Row.Header(getString(R.string.section_upcoming)))
            sections.upcoming.take(MAX_LISTED).forEach {
                rows.add(taskRow(it, today, is24, checked = false, overdue = false))
            }
        }
        if (sections.done.isNotEmpty()) {
            rows.add(Row.Header(getString(R.string.section_done)))
            sections.done.take(MAX_LISTED).forEach {
                rows.add(taskRow(it, today, is24, checked = true, overdue = false))
            }
            val cleared = sections.done.count { it.doneOn != null }
            if (cleared > 0) rows.add(Row.Notice(NoticeKind.CLEAR_DONE, getString(R.string.clear_done, cleared)))
        }
        showEmpty(if (state.board.tasks.isEmpty()) R.string.today_empty else null)
    }

    private fun renderHabits(state: StoreState, rows: MutableList<Row>) {
        val today = LocalDate.now(Device.zone())
        val count = state.board.habits.size
        status.text = resources.getQuantityString(R.plurals.habits_status, count, count)
        state.board.habits.forEach { habit ->
            val streak = Streaks.of(habit.checkIns, today)
            rows.add(
                Row.HabitItem(
                    habit.id,
                    habit.name,
                    getString(R.string.habit_meta, streak.current, streak.best),
                    today in habit.checkIns,
                ),
            )
        }
        showEmpty(if (count == 0) R.string.habits_empty else null)
    }

    private fun renderStream(state: StoreState, rows: MutableList<Row>) {
        val zone = Device.zone()
        val is24 = DateFormat.is24HourFormat(this)
        val slots = state.board.slots
        val text = ScheduleText.render(slots, Instant.now(), zone)
        status.text = getString(R.string.stream_status, ForeignText.clean(zone.id, ForeignText.MAX_LABEL))
        copyButton.isEnabled = text.isNotEmpty()
        copyButton.alpha = if (text.isNotEmpty()) 1f else 0.4f
        slots.sortedWith(compareBy({ it.day }, { it.start }, { it.id })).forEach { slot ->
            rows.add(
                Row.SlotItem(
                    slot.id,
                    slot.title,
                    "${Format.shortDay(slot.day)} ${Format.time(slot.start, is24)} - ${Format.time(slot.end, is24)}",
                ),
            )
        }
        if (text.isNotEmpty()) {
            rows.add(Row.Header(getString(R.string.section_discord)))
            rows.add(Row.Value(text))
            if (text.length > Limits.DISCORD_MESSAGE_LIMIT) {
                rows.add(Row.Note(getString(R.string.discord_too_long, text.length, Limits.DISCORD_MESSAGE_LIMIT)))
            }
        }
        showEmpty(if (slots.isEmpty()) R.string.stream_empty else null)
    }

    private fun renderBackup(state: StoreState, rows: MutableList<Row>) {
        val board = state.board
        status.text = getString(R.string.backup_status, board.tasks.size, board.habits.size, board.slots.size)
        rows.addAll(reminderNotices(state))
        rows.add(Row.Header(getString(R.string.section_reminders)))
        rows.add(Row.Note(getString(if (Alarms.canExact(this)) R.string.state_exact_on else R.string.state_exact_off)))
        rows.add(
            Row.Note(
                getString(if (Notifications.enabled(this)) R.string.state_notifications_on else R.string.state_notifications_off),
            ),
        )
        rows.add(Row.Header(getString(R.string.section_backup)))
        rows.add(Row.Note(getString(R.string.backup_note)))
        showEmpty(null)
    }

    private fun renderImport(state: StoreState) {
        val candidate = state.importCandidate
        if (candidate != null && importShown !== candidate) {
            importShown = candidate
            importDialog = Actions.show(
                this,
                AlertDialog.Builder(this)
                    .setTitle(R.string.import_title)
                    .setMessage(
                        getString(
                            R.string.import_message,
                            candidate.tasks.size,
                            candidate.habits.size,
                            candidate.slots.size,
                        ),
                    )
                    .setPositiveButton(R.string.import_confirm) { _, _ -> Store.confirmImport(this, candidate) }
                    .setNegativeButton(R.string.cancel) { _, _ -> Store.clearImport(this) }
                    .setOnCancelListener { Store.clearImport(this) },
            )
        }
        if (candidate == null) importShown = null
        val error = state.importError
        if (error != null && errorShown !== error) {
            errorShown = error
            importDialog = Actions.show(
                this,
                AlertDialog.Builder(this)
                    .setTitle(R.string.import_failed)
                    .setMessage(getString(R.string.import_failed_message, error))
                    .setPositiveButton(R.string.close) { _, _ -> Store.clearImport(this) }
                    .setOnCancelListener { Store.clearImport(this) },
            )
        }
        if (error == null) errorShown = null
    }

    private companion object {
        const val STATE_TAB = "tab"
        const val STATE_CORRUPT = "corrupt"
        const val CONTENT_SCHEME = "content"
        const val JSON_TYPE = "application/json"
        const val REQUEST_EXPORT = 1
        const val REQUEST_IMPORT = 2
        const val REQUEST_CORRUPT = 3
        const val TICK_MILLIS = 30_000L
        const val MAX_LISTED = 200
    }
}

object Keys {
    fun isSubmit(action: Int, event: KeyEvent?): Boolean =
        action == EditorInfo.IME_ACTION_DONE ||
            (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
}

object Reminding {
    fun openNotificationSettings(context: Context) {
        Actions.open(
            context,
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            R.string.settings_failed,
        )
    }

    fun openExactSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        Actions.open(
            context,
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
            R.string.settings_failed,
        )
    }
}
