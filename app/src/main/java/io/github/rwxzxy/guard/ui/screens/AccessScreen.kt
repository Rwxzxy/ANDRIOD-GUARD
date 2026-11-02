@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.rwxzxy.guard.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rwxzxy.guard.core.appops.AccessCategory
import io.github.rwxzxy.guard.core.risk.RiskLevel
import io.github.rwxzxy.guard.shizuku.ShizukuStatus
import io.github.rwxzxy.guard.ui.MainViewModel
import io.github.rwxzxy.guard.ui.components.AppIcon
import io.github.rwxzxy.guard.ui.components.Hint
import io.github.rwxzxy.guard.ui.components.Pill
import io.github.rwxzxy.guard.ui.components.ShizukuCard
import io.github.rwxzxy.guard.ui.components.formatRelative
import io.github.rwxzxy.guard.ui.theme.riskColor

@Composable
fun AccessScreen(vm: MainViewModel, onOpen: (String) -> Unit) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val status by vm.shizukuStatus.collectAsStateWithLifecycle()
    val loading by vm.accessLoading.collectAsStateWithLifecycle()
    val error by vm.accessError.collectAsStateWithLifecycle()
    // null 表示全部类别
    var category by rememberSaveable { mutableStateOf<AccessCategory?>(null) }
    var includeSystem by rememberSaveable { mutableStateOf(false) }

    val now = System.currentTimeMillis()
    val accessRows = remember(rows, category, includeSystem) {
        rows.filter { it.access != null && (includeSystem || !it.facts.isSystemApp) }
            .mapNotNull { row ->
                val access = row.access ?: return@mapNotNull null
                val cats = if (category == null) AccessCategory.entries else listOf(category!!)
                val latest = cats.mapNotNull { access.categories[it]?.lastAccessAt }.maxOrNull()
                val running = cats.any { access.categories[it]?.running == true }
                if (latest == null && !running) null else Triple(row, latest ?: now, running)
            }
            .sortedWith(compareByDescending<Triple<io.github.rwxzxy.guard.engine.AppRow, Long, Boolean>> { it.third }.thenByDescending { it.second })
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (status !is ShizukuStatus.Ready) {
            item {
                ShizukuCard(status, vm::requestShizukuPermission, vm::refreshShizuku)
            }
            item {
                Hint(
                    "访问记录需要 Shizuku：普通应用无法读取其他应用的 appops 记录。剪贴板读取记录也只有在 Shizuku 模式下才能看到。" +
                        "下面显示的是上次在 Shizuku 模式下读取到的数据（如果有）。",
                )
            }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = vm::refreshAccess, enabled = !loading) { Text(if (loading) "读取中…" else "刷新访问记录") }
                    Spacer(Modifier.width(12.dp))
                    Hint("数据来自 cmd appops get")
                }
            }
        }
        if (loading) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        error?.let { e -> item { Text(e, color = MaterialTheme.colorScheme.error) } }
        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = category == null, onClick = { category = null }, label = { Text("全部") })
                AccessCategory.entries.forEach { c ->
                    FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c.label) })
                }
                FilterChip(selected = includeSystem, onClick = { includeSystem = !includeSystem }, label = { Text("含系统应用") })
            }
        }
        if (accessRows.isEmpty() && !loading) {
            item { Hint("没有访问记录。", modifier = Modifier.padding(8.dp)) }
        }
        items(accessRows, key = { it.first.packageName }) { (row, _, running) ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(row.packageName) },
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(row.packageName)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                row.facts.label,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (running) Pill("正在使用", riskColor(RiskLevel.CRITICAL), filled = true)
                        }
                        val access = row.access
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            AccessCategory.entries.forEach { c ->
                                val a = access?.categories?.get(c)
                                if (a != null && (a.lastAccessAt != null || a.running)) {
                                    val text = if (a.running) "${c.label}：正在使用" else "${c.label}：${formatRelative(a.lastAccessAt, now)}"
                                    val recent = a.running || (a.lastAccessAt != null && now - a.lastAccessAt!! < 24L * 3600 * 1000)
                                    Pill(text, if (recent) riskColor(RiskLevel.HIGH) else riskColor(RiskLevel.NONE))
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Hint(
                "说明：appops 只保存每类访问最近一次的时间（以及最近一次被拒绝的时间），不是完整的访问历史。" +
                    "“照片/媒体”包含 READ_MEDIA_IMAGES / READ_MEDIA_VIDEO / 所选照片 / READ_EXTERNAL_STORAGE 四个 op；" +
                    "“定位”包含精确和大致定位。",
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}
