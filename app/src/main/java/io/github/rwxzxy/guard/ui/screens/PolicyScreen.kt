@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.rwxzxy.guard.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rwxzxy.guard.core.policy.PolicyScope
import io.github.rwxzxy.guard.core.policy.TightenRule
import io.github.rwxzxy.guard.shizuku.ShizukuStatus
import io.github.rwxzxy.guard.ui.MainViewModel
import io.github.rwxzxy.guard.ui.components.Hint
import io.github.rwxzxy.guard.ui.components.SectionCard
import io.github.rwxzxy.guard.ui.components.ShizukuCard
import io.github.rwxzxy.guard.ui.components.formatDateTime

private val INTERVALS = listOf(15, 30, 60, 180, 360, 720)

@Composable
fun PolicyScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by vm.shizukuStatus.collectAsStateWithLifecycle()
    val checking by vm.checking.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val policy = settings.policy

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = "自动收紧", icon = Icons.Filled.Policy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("启用自动收紧", style = MaterialTheme.typography.bodyLarge)
                    Hint("不在信任列表里的应用一旦拿到下面勾选的权限，就自动撤销 / 收紧。每次操作都会发通知，可一键撤回。")
                }
                Switch(checked = policy.enabled, onCheckedChange = { on -> vm.updatePolicy { it.copy(enabled = on) } })
            }
            if (policy.enabled && status !is ShizukuStatus.Ready) {
                Text(
                    "自动收紧需要 Shizuku。当前 Shizuku 不可用：发现需要收紧的应用时只会发通知提醒，快照不会更新，等 Shizuku 可用后再处理。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (status !is ShizukuStatus.Ready) {
            ShizukuCard(status, vm::requestShizukuPermission, vm::refreshShizuku)
        }

        SectionCard(title = "处理范围") {
            PolicyScope.entries.forEach { scope ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.updatePolicy { it.copy(scope = scope) } },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = policy.scope == scope, onClick = { vm.updatePolicy { it.copy(scope = scope) } })
                    Column(Modifier.weight(1f)) {
                        Text(scope.title, style = MaterialTheme.typography.bodyLarge)
                        Hint(scope.description)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("包括系统应用", style = MaterialTheme.typography.bodyLarge)
                    Hint("默认不处理系统应用，避免影响系统功能。本应用、Shizuku 和已启用的输入法永远不会被处理。")
                }
                Switch(checked = policy.includeSystemApps, onCheckedChange = { on -> vm.updatePolicy { it.copy(includeSystemApps = on) } })
            }
        }

        SectionCard(title = "规则", icon = Icons.Filled.Tune) {
            TightenRule.entries.forEach { rule ->
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(
                        checked = rule in policy.rules,
                        onCheckedChange = { on ->
                            vm.updatePolicy { p -> p.copy(rules = if (on) p.rules + rule else p.rules - rule) }
                        },
                    )
                    Column(Modifier.weight(1f).padding(top = 10.dp)) {
                        Text(rule.title, style = MaterialTheme.typography.bodyLarge)
                        Hint(rule.description)
                    }
                }
            }
        }

        SectionCard(title = "检查频率", icon = Icons.Filled.Bolt) {
            Hint("Android 8 起，清单里静态注册的“应用安装”广播收不到，所以用 WorkManager 定期对比授权快照，并在每次打开本应用时检查一次。系统可能为省电推迟后台任务。")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                INTERVALS.forEach { minutes ->
                    FilterChip(
                        selected = policy.intervalMinutes == minutes,
                        onClick = { vm.updatePolicy { it.copy(intervalMinutes = minutes) } },
                        label = { Text(if (minutes < 60) "$minutes 分钟" else "${minutes / 60} 小时") },
                    )
                }
            }
            Hint("上次检查：${if (settings.lastAutoCheckAt > 0) formatDateTime(settings.lastAutoCheckAt) else "还没有"}")
            settings.lastAutoCheckSummary?.let { Hint(it) }
            Button(onClick = vm::runCheckNow, enabled = !checking && !vm.busy) {
                Text(if (checking) "检查中…" else "立即检查")
            }
        }

        SectionCard(title = "风险提醒", icon = Icons.Filled.NotificationsActive) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("发现新的风险应用时通知", style = MaterialTheme.typography.bodyLarge)
                    Hint("后台检查发现新的“中风险”及以上的应用时提醒（不需要 Shizuku）。被信任的应用不会告警。")
                }
                Switch(checked = settings.riskAlertsEnabled, onCheckedChange = vm::setRiskAlerts)
            }
        }

        if (settings.exemptions.isNotEmpty()) {
            SectionCard(title = "豁免（撤回自动收紧后产生）") {
                settings.exemptions.sortedBy { it.packageName }.forEach { ex ->
                    val label = rows.firstOrNull { it.packageName == ex.packageName }?.facts?.label ?: ex.packageName
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                            Hint(ex.rule.title)
                        }
                        OutlinedButton(onClick = { vm.removeExemption(ex) }) { Text("移除") }
                    }
                }
            }
        }

        SectionCard(title = "各种操作的实际效果（依据 AOSP 源码）", icon = Icons.Filled.Info) {
            Hint(
                "• 撤销运行时权限（pm revoke）：持久有效。权限控制器会同步把对应 appop 设为 ignore。撤销正在使用的权限时，系统通常会结束该应用的进程；对方以后还能再向你申请。",
            )
            Hint(
                "• 运行时权限对应的 appop（相机、麦克风、定位、照片/存储）：Android 11 起，PermissionPolicyService 对所有运行时权限的 switch op 调用了 startWatchingMode，" +
                    "op 一变就按权限授予状态把 uid 模式改回，必要时还会把包模式重置为默认。Android 10 只同步定位和受限权限。所以本应用不用 appop 收紧这些权限。",
            )
            Hint(
                "• 剪贴板（READ_CLIPBOARD = ignore）：ClipboardService 在 appop 不是 allow 时让 getPrimaryClip() 返回 null，对方读到的是空剪贴板，不会报错；" +
                    "如果设成 deny，noteOp 会抛 SecurityException，对方可能崩溃。这个 op 没有对应权限，系统不会改回。",
            )
            Hint(
                "• 所有文件访问（MANAGE_EXTERNAL_STORAGE = deny）：与系统设置里关闭开关效果相同，对方的 isExternalStorageManager() 变为 false。",
            )
            Hint("以上行为只在源码层面核实过，不同厂商 ROM 可能有差异。可以在应用详情的“实验”里在你的设备上实际验证。")
        }
        Spacer(Modifier.width(1.dp))
    }
}
