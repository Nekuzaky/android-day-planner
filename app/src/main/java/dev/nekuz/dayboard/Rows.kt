package dev.nekuz.dayboard

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import java.time.LocalDate

enum class NoticeKind { RETRY_SAVE, STORAGE_BLOCKED, CORRUPT, NOTIFICATIONS, EXACT_ALARMS, CLEAR_DONE }

sealed class Row {
    data class Header(val text: String) : Row()

    data class Note(val text: String) : Row()

    data class Value(val text: String) : Row()

    data class Notice(val kind: NoticeKind, val text: String) : Row()

    data class TaskItem(
        val id: Int,
        val date: LocalDate,
        val title: String,
        val meta: String,
        val checked: Boolean,
        val overdue: Boolean,
    ) : Row()

    data class HabitItem(val id: Int, val name: String, val meta: String, val checked: Boolean) : Row()

    data class SlotItem(val id: Int, val title: String, val meta: String) : Row()
}

interface RowActions {
    fun onTaskChecked(row: Row.TaskItem)

    fun onHabitChecked(row: Row.HabitItem)
}

class RowAdapter(private val context: Context, private val actions: RowActions) : BaseAdapter() {
    var rows: List<Row> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    private val inflater = LayoutInflater.from(context)

    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getViewTypeCount() = 7

    override fun isEnabled(position: Int) = when (rows[position]) {
        is Row.Header, is Row.Note, is Row.Value -> false
        else -> true
    }

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.Header -> 0
        is Row.Note -> 1
        is Row.Value -> 2
        is Row.Notice -> 3
        is Row.TaskItem -> 4
        is Row.HabitItem -> 5
        is Row.SlotItem -> 6
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        when (val row = rows[position]) {
            is Row.Header -> text(convertView, parent, R.layout.row_header, row.text)
            is Row.Note -> text(convertView, parent, R.layout.row_note, row.text)
            is Row.Value -> text(convertView, parent, R.layout.row_value, row.text)
            is Row.Notice -> text(convertView, parent, R.layout.row_notice, row.text)
            is Row.TaskItem -> task(convertView, parent, row)
            is Row.HabitItem -> habit(convertView, parent, row)
            is Row.SlotItem -> slot(convertView, parent, row)
        }

    private fun text(convertView: View?, parent: ViewGroup, layout: Int, text: String): View {
        val view = convertView ?: inflater.inflate(layout, parent, false)
        (view as TextView).text = text
        return view
    }

    private fun task(convertView: View?, parent: ViewGroup, row: Row.TaskItem): View {
        val view = convertView ?: inflater.inflate(R.layout.row_task, parent, false)
        val check = view.findViewById<CheckBox>(R.id.check)
        check.isChecked = row.checked
        check.contentDescription = context.getString(R.string.task_check, row.title)
        check.setOnClickListener {
            check.isChecked = row.checked
            actions.onTaskChecked(row)
        }
        val title = view.findViewById<TextView>(R.id.title)
        title.text = row.title
        title.alpha = if (row.checked) 0.6f else 1f
        val meta = view.findViewById<TextView>(R.id.meta)
        meta.text = row.meta
        meta.visibility = if (row.meta.isEmpty()) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.tag).visibility = if (row.overdue) View.VISIBLE else View.GONE
        view.setBackgroundResource(if (row.overdue) R.drawable.bg_row_highlight else R.drawable.bg_row)
        return view
    }

    private fun habit(convertView: View?, parent: ViewGroup, row: Row.HabitItem): View {
        val view = convertView ?: inflater.inflate(R.layout.row_habit, parent, false)
        view.findViewById<TextView>(R.id.title).text = row.name
        view.findViewById<TextView>(R.id.meta).text = row.meta
        val button = view.findViewById<Button>(R.id.checkIn)
        button.setText(if (row.checked) R.string.habit_checked else R.string.habit_check_in)
        button.setBackgroundResource(if (row.checked) R.drawable.bg_button_primary else R.drawable.bg_button_outline)
        button.setTextColor(context.getColor(if (row.checked) R.color.on_brand else R.color.text_primary))
        button.setOnClickListener { actions.onHabitChecked(row) }
        return view
    }

    private fun slot(convertView: View?, parent: ViewGroup, row: Row.SlotItem): View {
        val view = convertView ?: inflater.inflate(R.layout.row_slot, parent, false)
        view.findViewById<TextView>(R.id.title).text = row.title
        view.findViewById<TextView>(R.id.meta).text = row.meta
        return view
    }
}
