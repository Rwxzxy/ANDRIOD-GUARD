package io.github.rwxzxy.guard.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rwxzxy.guard.ui.MainViewModel
import io.github.rwxzxy.guard.ui.components.Hint

@Composable
fun LogScreen(vm: MainViewModel, onOpen: (String) -> Unit) {
    val log by vm.operationLog.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Hint(
                    "本应用做过的每一次撤销、收紧、恢复和实验，以及触发方式。共 ${log.size} 条。",
                    modifier = Modifier.weight(1f),
                )
                if (log.isNotEmpty()) {
                    OutlinedButton(onClick = { confirmClear = true }) { Text("清空") }
                }
            }
        }
        if (log.isEmpty()) {
            item { Hint("还没有任何操作。", modifier = Modifier.padding(8.dp)) }
        }
        items(log, key = { it.id }) { record ->
            RecordLine(
                record = record,
                onUndo = { vm.undo(record.id) },
                busy = vm.busy,
                showApp = true,
                onOpen = { onOpen(record.packageName) },
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空操作日志？") },
            text = { Text("清空后将无法再从日志撤回之前的操作（已做的更改不会被恢复）。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearLog()
                    confirmClear = false
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}
