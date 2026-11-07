package dev.nekuz.dayboard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.IOException
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

object Device {
    fun zone(): ZoneId = try {
        ZoneId.systemDefault()
    } catch (e: DateTimeException) {
        ZoneOffset.UTC
    }
}

object Alarms {
    private const val LEDGER = "alarms.json"
    private const val MAX_LEDGER_BYTES = 256 * 1024

    fun canExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
    }

    fun sync(context: Context, directory: File, board: Board, force: Boolean) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val exact = canExact(context)
        val notify = Notifications.enabled(context)
        val planned = Reminders.plan(board.tasks, Device.zone(), Instant.now())
        val ledger = File(directory, LEDGER)
        val previous = readLedger(ledger)
        val changes = Reminders.changes(
            previous?.alarms.orEmpty(),
            planned,
            force || AlarmLedger.needsFullSync(previous, exact, notify),
        )
        writeLedger(ledger, AlarmLedger.Entry(exact, notify, planned))
        changes.cancel.forEach { cancel(context, manager, it) }
        changes.set.forEach { set(context, manager, it, exact) }
    }

    private fun readLedger(file: File): AlarmLedger.Entry? = try {
        if (!file.exists()) {
            null
        } else {
            file.inputStream().use { BoundedIo.read(it, MAX_LEDGER_BYTES) }
                ?.let(BoundedIo::utf8)
                ?.let(AlarmLedger::decode)
        }
    } catch (e: IOException) {
        null
    }

    private fun writeLedger(file: File, entry: AlarmLedger.Entry) {
        try {
            file.parentFile?.mkdirs()
            AtomicFiles.write(file, AlarmLedger.encode(entry).toByteArray(Charsets.UTF_8))
        } catch (e: IOException) {
            file.delete()
        }
    }

    private fun intent(context: Context, taskId: Int) =
        Intent(context, AlarmReceiver::class.java)
            .setAction(ReminderIntent.ACTION)
            .putExtra(ReminderIntent.EXTRA_TASK_ID, taskId)

    private fun set(context: Context, manager: AlarmManager, alarm: PlannedAlarm, exact: Boolean) {
        val pending = PendingIntent.getBroadcast(
            context,
            alarm.taskId,
            intent(context, alarm.taskId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        try {
            if (exact && canExact(context)) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.triggerMillis, pending)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.triggerMillis, pending)
            }
        } catch (e: SecurityException) {
            setInexact(manager, alarm, pending)
        } catch (e: IllegalStateException) {
            return
        }
    }

    private fun setInexact(manager: AlarmManager, alarm: PlannedAlarm, pending: PendingIntent) {
        try {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.triggerMillis, pending)
        } catch (e: RuntimeException) {
            return
        }
    }

    private fun cancel(context: Context, manager: AlarmManager, taskId: Int) {
        val pending = PendingIntent.getBroadcast(
            context,
            taskId,
            intent(context, taskId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) ?: return
        manager.cancel(pending)
        pending.cancel()
    }
}
