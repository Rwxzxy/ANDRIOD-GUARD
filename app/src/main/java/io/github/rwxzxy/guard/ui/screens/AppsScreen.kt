@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.rwxzxy.guard.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rwxzxy.guard.core.model.AllFilesAccess
import io.github.rwxzxy.guard.core.model.LocationAccess
import io.github.rwxzxy.guard.core.model.PhotoAccess
import io.github.rwxzxy.guard.core.risk.RiskLevel
import io.github.rwxzxy.guard.engine.AppRow
import io.github.rwxzxy.guard.ui.MainViewModel
import io.github.rwxzxy.guard.ui.components.AppIcon
import io.github.rwxzxy.guard.ui.components.Hint
import io.github.rwxzxy.guard.ui.components.Pill
import io.github.rwxzxy.guard.ui.components.RiskBadge
import io.github.rwxzxy.guard.ui.components.ShizukuCard
import io.github.rwxzxy.guard.ui.theme.riskColor

private enum class AppFilter(val label: String) { ATTENTION("需要注意"), ALL("全部"), TRUSTED("已信任") }

@Composable
fun AppsScreen(vm: MainViewModel, onOpen: (String) -> Unit) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val status by vm.shizukuStatus.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(AppFilter.ATTENTION) }
    var showSystem by rememberSaveable { mutableStateOf(false) }

    val filtered = remember(rows, query, filter) {
        rows.filter { row ->
            val q = query.trim()
            val matchesQuery = q.isEmpty() || row.facts.label.contains(q, ignoreCase = true) ||
                row.packageName.contains(q, ignoreCase = true)
            val matchesFilter = when (filter) {
                AppFilter.ALL -> true
                AppFilter.ATTENTION -> row.assessment.alerting
                AppFilter.TRUSTED -> row.trusted
            }
            matchesQuery && matchesFilter
        }
    }
    val userApps = filtered.filter { !it.facts.isSystemApp }
    val systemApps = filtered.filter { it.facts.isSystemApp }
    val alertingCount = rows.count { it.assessment.alerting }
    val highCount = rows.count { !it.trusted && it.assessment.level >= RiskLevel.HIGH }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (loading) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        item {
            ShizukuCard(
                status = status,
                onRequestPermission = vm::requestShizukuPermission,
                onRecheck = vm::refreshShizuku,
                compact = true,
            )
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Stat("需要注意", alertingCount.toString(), Modifier.weight(1f))
                    Stat("高风险及以上", highCount.toString(), Modifier.weight(1f))
                    Stat("已扫描", rows.size.toString(), Modifier.weight(1f))
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("搜索应用名或包名") },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppFilter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                }
            }
        }
        if (userApps.isEmpty() && !loading) {
            item {
                Hint(
                    if (filter == AppFilter.ATTENTION) "没有需要注意的用户应用。切换到“全部”查看所有应用。" else "没有匹配的应用。",
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        items(userApps, key = { "u:" + it.packageName }) { row -> AppListItem(row, onOpen) }
        if (systemApps.isNotEmpty()) {
            item(key = "system-header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showSystem = !showSystem }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "系统应用（${systemApps.size}）",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(if (showSystem) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
                }
            }
            if (showSystem) {
                items(systemApps, key = { "s:" + it.packageName }) { row -> AppListItem(row, onOpen) }
            }
        }
        item {
            Hint(
                "评级只看“实际已授予”的权限（requestedPermissionsFlags）。系统应用默认折叠。点进应用可以看到每一条评级理由，并跳转系统设置撤销。",
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AppListItem(row: AppRow, onOpen: (String) -> Unit) {
    val f = row.facts
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(row.packageName) },
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(row.packageName)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        f.label,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    RiskBadge(row.assessment.level, row.assessment.score, row.trusted)
                }
                Text(
                    f.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val tags = accessTags(row)
                if (tags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        tags.forEach { (text, level) -> Pill(text, riskColor(level)) }
                    }
                }
            }
        }
    }
}

/** 列表里显示的简短标签。 */
private fun accessTags(row: AppRow): List<Pair<String, RiskLevel>> {
    val f = row.facts
    val tags = mutableListOf<Pair<String, RiskLevel>>()
    when (f.photoAccess) {
        PhotoAccess.FULL -> tags += "照片：完全" to RiskLevel.HIGH
        PhotoAccess.PARTIAL -> tags += "照片：部分" to RiskLevel.LOW
        PhotoAccess.NONE -> Unit
    }
    if (f.canReadPhotoLocation && f.photoAccess != PhotoAccess.NONE) tags += "照片位置" to RiskLevel.MEDIUM
    when (f.allFiles) {
        AllFilesAccess.GRANTED -> tags += "所有文件" to RiskLevel.CRITICAL
        AllFilesAccess.UNKNOWN -> tags += "所有文件？" to RiskLevel.MEDIUM
        else -> Unit
    }
    when (f.locationAccess) {
        LocationAccess.BACKGROUND -> tags += "后台定位" to RiskLevel.HIGH
        LocationAccess.FOREGROUND -> tags += "定位" to RiskLevel.LOW
        LocationAccess.NONE -> Unit
    }
    if (f.canUseCamera) tags += "相机" to RiskLevel.LOW
    if (f.canUseMicrophone) tags += "麦克风" to RiskLevel.LOW
    if (!f.isSystemApp && !f.installSource.isFromStore) tags += f.installSource.kind.label to RiskLevel.MEDIUM
    return tags
}
