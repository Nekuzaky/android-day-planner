package dev.nekuz.dayboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!SystemEvents.isAccepted(intent.action)) return
        val result = goAsync()
        Store.resync(context, true) { result.finish() }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ReminderIntent.ACTION) return
        val raw = intent.getIntExtra(ReminderIntent.EXTRA_TASK_ID, ReminderIntent.MISSING_ID)
        val id = ReminderIntent.validId(action, raw) ?: return
        val result = goAsync()
        Store.fire(context, id) { result.finish() }
    }
}
