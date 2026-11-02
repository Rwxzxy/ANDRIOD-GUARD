@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.rwxzxy.guard.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rwxzxy.guard.core.appops.AccessCategory
import io.github.rwxzxy.guard.core.log.OperationRecord
import io.github.rwxzxy.guard.core.model.AllFilesAccess
import io.github.rwxzxy.guard.core.model.Op
import io.github.rwxzxy.guard.core.model.OpMode
import io.github.rwxzxy.guard.core.model.Perm
import io.github.rwxzxy.guard.core.model.PhotoAccess
import io.github.rwxzxy.guard.core.policy.Change
import io.github.rwxzxy.guard.core.policy.ChangeDescriber
import io.github.rwxzxy.guard.core.risk.RiskLevel
import io.github.rwxzxy.guard.engine.AppRow
import io.github.rwxzxy.guard.shizuku.ShizukuStatus
import io.github.rwxzxy.guard.ui.MainViewModel
import io.github.rwxzxy.guard.ui.components.AppIcon
import io.github.rwxzxy.guard.ui.components.Hint
import io.github.rwxzxy.guard.ui.components.KeyValue
import io.github.rwxzxy.guard.ui.components.Pill
import io.github.rwxzxy.guard.ui.components.RiskBadge
import io.github.rwxzxy.guard.ui.components.SectionCard
import io.github.rwxzxy.guard.ui.components.ShizukuCard
import io.github.rwxzxy.guard.ui.components.formatDateTime
import io.github.rwxzxy.guard.ui.components.formatDateTimeSeconds
import io.github.rwxzxy.guard.ui.components.formatRelative
import io.github.rwxzxy.guard.ui.theme.riskColor

/** 等待用户确认的操作。 */
private sealed interface Pending {
    data class Apply(val change: Change) : Pending
    data class Experiment(val op: String) : Pending
}

private fun openAppSettings(context: Context, packageName: String) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private fun openAllFilesSettings(context: Context, packageName: String) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.fromParts("package", packageName, null))
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    }
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        openAppSettings(context, packageName)
    }
}

@Composable
fun AppDetailScreen(vm: MainViewModel, packageName: String) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val status by vm.shizukuStatus.collectAsStateWithLifecycle()
    val log by vm.operationLog.collectAsStateWithLifecycle()
    val row = rows.firstOrNull { it.packageName == packageName }
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Pending?>(null) }

    if (row == null) {
        Column(Modifier.padding(16.dp)) { Hint("找不到该应用（可能已卸载）。") }
        return
    }
    val f = row.facts
    val ready = status is ShizukuStatus.Ready
    val records = log.filter { it.packageName == packageName }.take(20)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 头部
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(packageName, size = 56.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.label, style = MaterialTheme.typography.titleLarge)
                Text(packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.padding(2.dp))
                RiskBadge(row.assessment.level, row.assessment.score, row.trusted)
            }
        }

        SectionCard(title = "基本信息") {
            KeyValue("版本", row.versionName ?: "未知")
            KeyValue("首次安装", formatDateTime(f.firstInstallTime))
            KeyValue("最近更新", formatDateTime(f.lastUpdateTime))
            KeyValue("安装来源", f.installSource.kind.label + (f.installSource.installerName?.let { "（$it）" } ?: ""))
            KeyValue("目标 SDK", f.targetSdk.toString())
            KeyValue("类型", if (f.isSystemApp) "系统应用" else "用户应用")
            KeyValue("UID", f.uid.toString())
        }

        // 评级理由
        SectionCard(title = "风险评级：${if (row.trusted) "已信任" else row.assessment.level.label}（${row.assessment.score} 分）", icon = Icons.Filled.Warning) {
            if (row.assessment.reasons.isEmpty()) {
                Hint("没有发现敏感授权，也没有来源方面的风险。")
            }
            row.assessment.reasons.forEach { reason ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(reason.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        if (reason.points > 0) Pill("+${reason.points}", riskColor(RiskLevel.MEDIUM))
                    }
                    Hint(reason.detail)
                }
            }
            if (row.trusted) Hint("已信任：评级照常计算，但不会告警，也不会被自动收紧。")
        }

        // 操作
        SectionCard(title = "处理", icon = Icons.Filled.Security) {
            Button(onClick = { openAppSettings(context, packageName) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("打开系统设置撤销权限")
            }
            if (f.allFiles != AllFilesAccess.NOT_REQUESTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                OutlinedButton(onClick = { openAllFilesSettings(context, packageName) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("打开“所有文件访问”设置")
                }
            }
            OutlinedButton(onClick = { vm.setTrusted(packageName, !row.trusted) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.VerifiedUser, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (row.trusted) "取消信任" else "信任此应用（不再告警、不自动收紧）")
            }
        }

        PermissionsCard(row)

        // Shizuku 模式
        if (!ready) {
            ShizukuCard(status, vm::requestShizukuPermission, vm::refreshShizuku)
        } else {
            AccessCard(row, onRefresh = vm::refreshAccess)
            DirectActionsCard(row, busy = vm.busy, onRequest = { pending = it })
        }

        if (records.isNotEmpty()) {
            SectionCard(title = "本应用的操作记录", icon = Icons.Filled.History) {
                records.forEach { r -> RecordLine(r, onUndo = { vm.undo(r.id) }, busy = vm.busy) }
            }
        }
    }

    pending?.let { p ->
        val (title, body) = when (p) {
            is Pending.Apply -> ChangeDescriber.title(p.change) to ChangeDescriber.effect(p.change)
            is Pending.Experiment -> "实验：把 ${p.op} 设为 ignore" to
                (ChangeDescriber.effect(Change.SetUidAppOp(p.op, OpMode.IGNORE)) +
                    "\n\n执行后 3 秒复查实际模式，并写入操作日志；如果没被改回，可以在日志里撤回。")
        }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = {
                    when (p) {
                        is Pending.Apply -> vm.applyChange(packageName, p.change)
                        is Pending.Experiment -> vm.experiment(packageName, p.op)
                    }
                    pending = null
                }) { Text("执行") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun PermissionsCard(row: AppRow) {
    val f = row.facts
    SectionCard(title = "敏感权限（实际授予状态）") {
        KeyValue("照片/视频", f.photoAccess.label + if (f.photoAccess == PhotoAccess.FULL && f.photoAccessIsLegacyStorage) "（旧版存储权限）" else "")
        KeyValue("位置", f.locationAccess.label + if (f.hasPreciseLocation) "（精确）" else "")
        KeyValue("所有文件访问", f.allFiles.label)
        HorizontalDivider()
        val requested = Perm.AUDITED.filter { it in f.requestedPermissions }
        if (requested.isEmpty()) {
            Hint("没有申请本应用关注的敏感权限。")
        }
        requested.forEach { perm ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Perm.label(perm), style = MaterialTheme.typography.bodyMedium)
                    Hint(Perm.shortName(perm))
                }
                val granted = f.isGranted(perm)
                val unknown = perm == Perm.MANAGE_EXTERNAL_STORAGE && f.allFiles == AllFilesAccess.UNKNOWN
                Pill(
                    when {
                        unknown -> "需 Shizuku 确认"
                        granted -> "已授予"
                        else -> "未授予"
                    },
                    if (granted) riskColor(RiskLevel.HIGH) else riskColor(RiskLevel.NONE),
                )
            }
        }
        if (f.deviceSdk >= 34 && f.targetSdk < 34 && f.photoAccess == PhotoAccess.FULL) {
            Hint("提示：该应用的 targetSdk < 34。Android 14 上这类应用在你选择“仅允许部分照片”时，系统会临时授予完整权限，只在前台期间生效，所以这里可能显示为“完全”。")
        }
    }
}

@Composable
private fun AccessCard(row: AppRow, onRefresh: () -> Unit) {
    val access = row.access
    SectionCard(title = "实际访问记录（appops）", icon = Icons.Filled.Timeline) {
        if (access == null) {
            Hint("还没有读取过该应用的访问记录。")
        } else {
            AccessCategory.entries.forEach { cat ->
                val a = access.categories[cat]
                val text = when {
                    a == null -> "无记录"
                    a.running -> "正在使用"
                    else -> formatRelative(a.lastAccessAt)
                }
                KeyValue(cat.label, text)
                a?.lastRejectAt?.let { Hint("　最近一次被拒绝：${formatRelative(it)}") }
            }
            Hint("读取于 ${formatDateTimeSeconds(access.capturedAt)}。数据来自 `cmd appops get`，只记录每类最近一次访问，不是完整历史。剪贴板记录只有 Shizuku 模式能看到。")
        }
        OutlinedButton(onClick = onRefresh) { Text("刷新访问记录") }
    }
}

@Composable
private fun DirectActionsCard(row: AppRow, busy: Boolean, onRequest: (Pending) -> Unit) {
    val f = row.facts
    val access = row.access
    SectionCard(title = "直接撤销 / 收紧（Shizuku）", icon = Icons.Filled.Security) {
        Hint("运行时权限用 pm revoke 撤销：按 AOSP 源码，Android 11 起权限控制器会监听运行时权限对应的 appop，一旦改动就按授予状态改回，所以直接改这些 appop 不可靠。")

        val revocable = Perm.AUDITED.filter { it != Perm.MANAGE_EXTERNAL_STORAGE && it in f.requestedPermissions }
        revocable.forEach { perm ->
            val granted = f.isGranted(perm)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Perm.label(perm), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (granted) {
                    Button(enabled = !busy, onClick = { onRequest(Pending.Apply(Change.RevokePermission(perm))) }) { Text("撤销") }
                } else {
                    OutlinedButton(enabled = !busy, onClick = { onRequest(Pending.Apply(Change.GrantPermission(perm))) }) { Text("重新授予") }
                }
            }
        }
        if (revocable.isEmpty()) Hint("该应用没有可撤销的敏感运行时权限。")

        HorizontalDivider()
        // 剪贴板
        val clipboardMode = access?.clipboardMode
        val clipboardBlocked = clipboardMode == OpMode.IGNORE || clipboardMode == OpMode.DENY
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ContentPaste, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("读取剪贴板", style = MaterialTheme.typography.bodyMedium)
                Hint("当前：${OpMode.label(clipboardMode ?: OpMode.ALLOW)}" + if (access == null) "（未读取，按默认显示）" else "")
            }
            if (clipboardBlocked) {
                OutlinedButton(enabled = !busy, onClick = { onRequest(Pending.Apply(Change.SetUidAppOp(Op.READ_CLIPBOARD, OpMode.ALLOW))) }) { Text("恢复") }
            } else {
                Button(enabled = !busy, onClick = { onRequest(Pending.Apply(Change.SetUidAppOp(Op.READ_CLIPBOARD, OpMode.IGNORE))) }) { Text("屏蔽") }
            }
        }

        // 所有文件访问
        if (f.allFiles == AllFilesAccess.GRANTED) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("所有文件访问（已开启）", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Button(enabled = !busy, onClick = { onRequest(Pending.Apply(Change.SetUidAppOp(Op.MANAGE_EXTERNAL_STORAGE, OpMode.DENY))) }) { Text("关闭") }
            }
        }

        HorizontalDivider()
        // 实验
        val experimentOps = revocable.filter { f.isGranted(it) }.mapNotNull { Op.forPermission(it) }.distinct()
        if (experimentOps.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Science, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("实验：验证 appop 会不会被改回", style = MaterialTheme.typography.bodyMedium)
            }
            Hint("把运行时权限对应的 appop 设为 ignore，3 秒后复查。按 AOSP 源码，Android 11+ 应该会被改回；Android 10 只有定位相关的会被改回。结果写入操作日志，可撤回。")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                experimentOps.forEach { op ->
                    OutlinedButton(enabled = !busy, onClick = { onRequest(Pending.Experiment(op)) }) { Text(op) }
                }
            }
        }
    }
}

@Composable
fun RecordLine(record: OperationRecord, onUndo: () -> Unit, busy: Boolean, showApp: Boolean = false, onOpen: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(if (record.success) "成功" else "失败", if (record.success) riskColor(RiskLevel.LOW) else riskColor(RiskLevel.CRITICAL))
            Spacer(Modifier.width(6.dp))
            Text(
                (if (showApp) "${record.appLabel}：" else "") + ChangeDescriber.title(record.change),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
        }
        Hint("${formatDateTimeSeconds(record.timestamp)} · ${record.trigger.label}")
        record.rule?.let { Hint("规则：${it.title}") }
        record.reason?.let { Hint("原因：$it") }
        Hint(record.message)
        if (record.previousUidMode != null || record.verifiedMode != null) {
            Hint("变更前 uid 模式：${record.previousUidMode ?: "—"}；复查结果：${record.verifiedMode ?: "—"}")
        }
        when {
            record.undoneBy != null -> Hint("已撤回")
            record.undoOf != null -> Hint("这是一条撤回操作")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (record.canUndo) {
                OutlinedButton(enabled = !busy, onClick = onUndo) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("撤回")
                }
            }
            if (onOpen != null) {
                TextButton(onClick = onOpen) { Text("查看应用") }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
    }
}
