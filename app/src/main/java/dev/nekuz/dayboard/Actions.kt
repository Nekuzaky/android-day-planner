package dev.nekuz.dayboard

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

object Actions {
    private const val CLIP_LABEL = "DayBoard"

    fun copy(context: Context, value: String) {
        val done = try {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, value))
            true
        } catch (e: RuntimeException) {
            false
        }
        toast(context, if (done) R.string.copied else R.string.copy_failed)
    }

    fun toast(context: Context, text: Int) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    fun open(context: Context, intent: Intent, failure: Int) {
        try {
            context.startActivity(intent)
        } catch (e: RuntimeException) {
            toast(context, failure)
        }
    }

    fun show(activity: Activity, builder: AlertDialog.Builder): AlertDialog? {
        if (activity.isFinishing || activity.isDestroyed) return null
        val dialog = builder.create()
        dialog.setOnShowListener { dialog.window?.decorView?.filterTouchesWhenObscured = true }
        dialog.show()
        return dialog
    }
}
