package io.github.rwxzxy.guard.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.rwxzxy.guard.container
import io.github.rwxzxy.guard.core.log.Trigger
import kotlinx.coroutines.launch

/** 通知栏“撤回”按钮。 */
class UndoReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ids = intent.getStringArrayExtra(EXTRA_RECORD_IDS) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
        val label = intent.getStringExtra(EXTRA_LABEL) ?: ""
        val pending = goAsync()
        val c = context.container
        c.appScope.launch {
            try {
                c.shizuku.awaitReady(5_000)
                val results = ids.mapNotNull { c.engine.undo(it, Trigger.UNDO_FROM_NOTIFICATION) }
                c.notifier.notifyUndoResult(notificationId, label, results)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_RECORD_IDS = "record_ids"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_LABEL = "label"
    }
}
