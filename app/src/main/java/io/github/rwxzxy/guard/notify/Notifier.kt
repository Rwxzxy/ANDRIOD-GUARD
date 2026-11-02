package io.github.rwxzxy.guard.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.rwxzxy.guard.R
import io.github.rwxzxy.guard.core.log.OperationRecord
import io.github.rwxzxy.guard.core.policy.ChangeDescriber
import io.github.rwxzxy.guard.engine.AppRow
import io.github.rwxzxy.guard.engine.PrivacyEngine
import io.github.rwxzxy.guard.shizuku.ShizukuStatus
import io.github.rwxzxy.guard.ui.MainActivity

class Notifier(private val context: Context) {

    /** 自动收紧通知：每个应用一条，带“撤回”按钮（撤回这次对该应用做的所有操作）。 */
    fun notifyAutoActions(records: List<OperationRecord>) {
        if (records.isEmpty()) return
        val first = records.first()
        val id = autoActionNotificationId(first.packageName)
        val ok = records.filter { it.success }
        val failed = records.filterNot { it.success }
        val title = if (ok.isNotEmpty()) "已自动收紧「${first.appLabel}」" else "自动收紧「${first.appLabel}」失败"
        val lines = records.map { r ->
            (if (r.success) "✓ " else "✗ ") + ChangeDescriber.title(r.change) + if (r.success) "" else "：${r.message}"
        }
        val text = buildString {
            append(lines.joinToString("\n"))
            first.reason?.let { append("\n原因：").append(it) }
            append("\n触发：").append(first.trigger.label)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_AUTO)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(MainActivity.TAB_LOG, id))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
        val undoable = ok.filter { it.canUndo }
        if (undoable.isNotEmpty()) {
            val undoIntent = Intent(context, UndoReceiver::class.java)
                .putExtra(UndoReceiver.EXTRA_RECORD_IDS, undoable.map { it.id }.toTypedArray())
                .putExtra(UndoReceiver.EXTRA_NOTIFICATION_ID, id)
                .putExtra(UndoReceiver.EXTRA_LABEL, first.appLabel)
            val pi = PendingIntent.getBroadcast(
                context, id, undoIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "撤回", pi)
        }
        if (failed.isNotEmpty() && ok.isEmpty()) builder.setContentText("失败：${failed.first().message}")
        post(id, builder.build())
    }

    fun notifyUndoResult(notificationId: Int, label: String, results: List<OperationRecord>) {
        val ok = results.isNotEmpty() && results.all { it.success }
        val text = if (results.isEmpty()) {
            "没有可撤回的操作（可能已经撤回过）"
        } else {
            results.joinToString("\n") { (if (it.success) "✓ " else "✗ ") + ChangeDescriber.title(it.change) + if (it.success) "" else "：${it.message}" }
        }
        val n = NotificationCompat.Builder(context, CHANNEL_AUTO)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (ok) "已撤回对「$label」的自动收紧" else "撤回「$label」未完全成功")
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text + if (ok) "\n已为该应用的这条规则加上豁免，之后不会再自动收紧。" else ""))
            .setContentIntent(openAppIntent(MainActivity.TAB_LOG, notificationId))
            .setAutoCancel(true)
            .build()
        post(notificationId, n)
    }

    /** 后台检查发现了新的风险应用（被信任的应用不会出现在这里）。 */
    fun notifyRiskApps(rows: List<AppRow>) {
        if (rows.isEmpty()) return
        val title = if (rows.size == 1) "发现风险应用「${rows.first().facts.label}」" else "发现 ${rows.size} 个新的风险应用"
        val text = rows.take(6).joinToString("\n") { r ->
            "${r.facts.label}：${r.assessment.level.label}（${r.assessment.reasons.firstOrNull()?.title ?: ""}）"
        }
        val n = NotificationCompat.Builder(context, CHANNEL_RISK)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(MainActivity.TAB_APPS, RISK_NOTIFICATION_ID))
            .setAutoCancel(true)
            .build()
        post(RISK_NOTIFICATION_ID, n)
    }

    /** 需要收紧但 Shizuku 不可用。 */
    fun notifyBlocked(labels: List<String>, status: ShizukuStatus) {
        val text = "以下应用新获得了策略要收紧的权限：${labels.take(6).joinToString("、")}" +
            (if (labels.size > 6) " 等 ${labels.size} 个" else "") +
            "。Shizuku ${PrivacyEngine.statusText(status)}，暂时无法自动处理；启动 Shizuku 后打开本应用即可继续。"
        val n = NotificationCompat.Builder(context, CHANNEL_AUTO)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("有授权需要收紧，但 Shizuku 不可用")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(MainActivity.TAB_POLICY, BLOCKED_NOTIFICATION_ID))
            .setAutoCancel(true)
            .build()
        post(BLOCKED_NOTIFICATION_ID, n)
    }

    private fun openAppIntent(tab: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_TAB, tab)
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    @SuppressLint("MissingPermission")
    private fun post(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    companion object {
        const val CHANNEL_AUTO = "auto_tighten"
        const val CHANNEL_RISK = "risk_alerts"
        private const val RISK_NOTIFICATION_ID = 1001
        private const val BLOCKED_NOTIFICATION_ID = 1002

        fun autoActionNotificationId(packageName: String): Int = 20_000 + (packageName.hashCode() and 0x7FFF)

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_AUTO, "自动收紧", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "每次自动撤销 / 收紧权限都会通知，并可一键撤回"
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RISK, "风险提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "后台检查发现新的风险应用时提醒"
                },
            )
        }
    }
}
