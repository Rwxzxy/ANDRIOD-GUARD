package io.github.rwxzxy.guard.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.rwxzxy.guard.AppContainer
import io.github.rwxzxy.guard.core.log.Trigger
import io.github.rwxzxy.guard.core.policy.Change
import io.github.rwxzxy.guard.core.policy.ChangeDescriber
import io.github.rwxzxy.guard.core.policy.Exemption
import io.github.rwxzxy.guard.core.policy.TightenPolicy
import io.github.rwxzxy.guard.shizuku.ShizukuStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

enum class Tab { APPS, ACCESS, POLICY, LOG }

class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val engine = container.engine
    val shizuku = container.shizuku

    val rows = engine.rows
    val loading = engine.loading
    val accessLoading = engine.accessLoading
    val accessError = engine.accessError
    val checking = engine.checking
    val shizukuStatus = shizuku.status
    val settings = container.settings.state
    val operationLog = container.operationLog.state

    var tab by mutableStateOf(Tab.APPS)
    var detailPackage by mutableStateOf<String?>(null)

    /** 正在执行的操作（用于按钮显示进度）。 */
    var busy by mutableStateOf(false)
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        // App 打开时检查一次（包含扫描、Shizuku 访问记录、自动收紧策略、风险提醒）。
        container.appScope.launch {
            val outcome = engine.runAutoCheck(Trigger.AUTO_APP_OPEN)
            if (outcome.records.isNotEmpty()) _messages.tryEmit(outcome.summary)
        }
        // Shizuku 变为可用时刷新访问记录。
        viewModelScope.launch {
            var wasReady = shizuku.status.value.isReady
            shizuku.status.distinctUntilChangedBy { it.isReady }.collect { status ->
                if (status.isReady && !wasReady) refreshAccess()
                wasReady = status.isReady
            }
        }
    }

    fun refreshAll() {
        container.appScope.launch {
            shizuku.refresh()
            engine.refreshApps()
            if (shizuku.status.value.isReady) engine.refreshAccess()
        }
    }

    fun refreshAccess() {
        container.appScope.launch {
            val n = engine.refreshAccess()
            if (n > 0) _messages.tryEmit("已读取 $n 个应用的访问记录")
        }
    }

    fun setTrusted(packageName: String, trusted: Boolean) {
        viewModelScope.launch {
            engine.setTrusted(packageName, trusted)
            _messages.tryEmit(if (trusted) "已信任：不再告警，也不会被自动收紧" else "已取消信任")
        }
    }

    fun applyChange(packageName: String, change: Change) = runBusy {
        val record = engine.applyChange(packageName, change, Trigger.MANUAL)
        _messages.tryEmit((if (record.success) "✓ " else "✗ ") + ChangeDescriber.title(change) + "：" + record.message)
    }

    fun experiment(packageName: String, op: String) = runBusy {
        val record = engine.experimentAppOp(packageName, op)
        _messages.tryEmit(record.message)
    }

    fun undo(recordId: String) = runBusy {
        val record = engine.undo(recordId, Trigger.UNDO_FROM_LOG)
        _messages.tryEmit(
            when {
                record == null -> "这条记录无法撤回"
                record.success -> "已撤回：${ChangeDescriber.title(record.change)}"
                else -> "撤回失败：${record.message}"
            },
        )
    }

    fun runCheckNow() = runBusy {
        val outcome = engine.runAutoCheck(Trigger.AUTO_MANUAL_CHECK)
        _messages.tryEmit(outcome.summary)
    }

    fun updatePolicy(transform: (TightenPolicy) -> TightenPolicy) {
        viewModelScope.launch { container.settings.setPolicy(transform(settings.value.policy)) }
    }

    fun setRiskAlerts(enabled: Boolean) {
        viewModelScope.launch { container.settings.update { it.copy(riskAlertsEnabled = enabled) } }
    }

    fun removeExemption(exemption: Exemption) {
        viewModelScope.launch { container.settings.removeExemption(exemption) }
    }

    fun clearLog() {
        viewModelScope.launch { container.operationLog.clear() }
    }

    fun requestShizukuPermission() {
        shizuku.requestPermission()
    }

    fun refreshShizuku() {
        shizuku.refresh()
        if (shizuku.status.value is ShizukuStatus.Ready) refreshAccess()
    }

    fun message(text: String) {
        _messages.tryEmit(text)
    }

    /** 在应用级作用域执行（离开界面也不会中断），期间 busy = true。 */
    private fun runBusy(block: suspend () -> Unit) {
        busy = true
        container.appScope.launch {
            try {
                block()
            } finally {
                viewModelScope.launch { busy = false }
            }
        }
    }
}
