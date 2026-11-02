package io.github.rwxzxy.guard.engine

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.view.inputmethod.InputMethodManager
import io.github.rwxzxy.guard.core.appops.AccessSummary
import io.github.rwxzxy.guard.core.appops.AppOpsParser
import io.github.rwxzxy.guard.core.appops.BatchOutput
import io.github.rwxzxy.guard.core.log.OperationRecord
import io.github.rwxzxy.guard.core.log.Trigger
import io.github.rwxzxy.guard.core.model.AllFilesAccess
import io.github.rwxzxy.guard.core.model.AppFacts
import io.github.rwxzxy.guard.core.model.Op
import io.github.rwxzxy.guard.core.model.OpMode
import io.github.rwxzxy.guard.core.model.Perm
import io.github.rwxzxy.guard.core.policy.AppGrantState
import io.github.rwxzxy.guard.core.policy.Change
import io.github.rwxzxy.guard.core.policy.ChangeDescriber
import io.github.rwxzxy.guard.core.policy.Exemption
import io.github.rwxzxy.guard.core.policy.GrantSnapshot
import io.github.rwxzxy.guard.core.policy.PolicyScope
import io.github.rwxzxy.guard.core.policy.TightenRule
import io.github.rwxzxy.guard.core.policy.TighteningPlanner
import io.github.rwxzxy.guard.core.risk.RiskAssessment
import io.github.rwxzxy.guard.core.risk.RiskRater
import io.github.rwxzxy.guard.core.shell.ShellCommands
import io.github.rwxzxy.guard.data.AccessCacheRepository
import io.github.rwxzxy.guard.data.AppScanner
import io.github.rwxzxy.guard.data.GuardSettings
import io.github.rwxzxy.guard.data.OperationLogRepository
import io.github.rwxzxy.guard.data.ScannedApp
import io.github.rwxzxy.guard.data.SettingsRepository
import io.github.rwxzxy.guard.data.SnapshotRepository
import io.github.rwxzxy.guard.notify.Notifier
import io.github.rwxzxy.guard.shizuku.ShizukuController
import io.github.rwxzxy.guard.shizuku.ShizukuStatus
import io.github.rwxzxy.guard.shizuku.ShizukuUnavailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** 列表里的一行：应用事实 + 评级 + （可选的）Shizuku 访问记录。 */
data class AppRow(
    val facts: AppFacts,
    val versionName: String?,
    val assessment: RiskAssessment,
    val access: AccessSummary?,
    val trusted: Boolean,
) {
    val packageName: String get() = facts.packageName
}

/** 一次自动检查的结果。 */
data class AutoCheckOutcome(
    val summary: String,
    val records: List<OperationRecord>,
)

class PrivacyEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val scanner: AppScanner,
    val shizuku: ShizukuController,
    private val settings: SettingsRepository,
    private val snapshots: SnapshotRepository,
    private val operationLog: OperationLogRepository,
    private val accessCache: AccessCacheRepository,
    private val notifier: Notifier,
) {
    private val _scanned = MutableStateFlow<List<ScannedApp>>(emptyList())
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _accessLoading = MutableStateFlow(false)
    val accessLoading: StateFlow<Boolean> = _accessLoading.asStateFlow()

    private val _accessError = MutableStateFlow<String?>(null)
    val accessError: StateFlow<String?> = _accessError.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val checkMutex = Mutex()

    val rows: StateFlow<List<AppRow>> = combine(_scanned, settings.state, accessCache.state) { scanned, s, access ->
        buildRows(scanned, s, access)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun buildRows(scanned: List<ScannedApp>, s: GuardSettings, access: Map<String, AccessSummary>): List<AppRow> {
        val now = System.currentTimeMillis()
        return scanned.map { app ->
            val summary = access[app.facts.packageName]
            val facts = mergeAccess(app.facts, summary)
            val trusted = facts.packageName in s.trusted
            AppRow(
                facts = facts,
                versionName = app.versionName,
                assessment = RiskRater.assess(facts, now, trusted, summary),
                access = summary,
                trusted = trusted,
            )
        }.sortedWith(
            compareByDescending<AppRow> { !it.trusted }
                .thenByDescending { it.assessment.score }
                .thenBy { it.facts.label },
        )
    }

    /** 审计模式无法确认的“所有文件访问”，用 Shizuku 抓到的 appop 记录补上。 */
    private fun mergeAccess(facts: AppFacts, summary: AccessSummary?): AppFacts {
        if (summary == null || facts.allFiles != AllFilesAccess.UNKNOWN) return facts
        return if (summary.allFilesGranted) {
            facts.copy(allFiles = AllFilesAccess.GRANTED, grantedPermissions = facts.grantedPermissions + Perm.MANAGE_EXTERNAL_STORAGE)
        } else {
            facts.copy(allFiles = AllFilesAccess.NOT_GRANTED)
        }
    }

    fun row(packageName: String): AppRow? = rows.value.firstOrNull { it.packageName == packageName }

    // ---------------------------------------------------------------- 审计

    suspend fun refreshApps() {
        _loading.value = true
        try {
            _scanned.value = withContext(Dispatchers.IO) { scanner.scan() }
        } finally {
            _loading.value = false
        }
    }

    private suspend fun refreshOne(packageName: String) {
        val one = withContext(Dispatchers.IO) { scanner.scanOne(packageName) }
        _scanned.value = _scanned.value.mapNotNull { if (it.facts.packageName == packageName) one else it }
    }

    suspend fun setTrusted(packageName: String, trusted: Boolean) {
        settings.setTrusted(packageName, trusted)
        if (trusted) {
            settings.update { it.copy(alertedPackages = it.alertedPackages - packageName) }
        }
    }

    // ---------------------------------------------------------------- Shizuku：访问记录

    /** 需要抓取访问记录的应用：所有用户应用，以及申请了敏感权限的系统应用。 */
    private fun accessTargets(scanned: List<ScannedApp>): List<ScannedApp> = scanned.filter {
        !it.facts.isSystemApp || it.facts.requestedPermissions.isNotEmpty()
    }

    /** 通过 Shizuku 批量执行 `cmd appops get`，返回 包名 -> 访问汇总。 */
    private suspend fun fetchAccess(targets: List<ScannedApp>): Map<String, AccessSummary> {
        val result = linkedMapOf<String, AccessSummary>()
        val names = targets.map { it.facts.packageName }.filter { ShellCommands.isValidPackage(it) }
        for (chunk in names.chunked(40)) {
            val script = ShellCommands.batchAppOpsScript(chunk, scanner.userId)
            val res = shizuku.execScript(script, timeoutMs = 120_000)
            val capturedAt = System.currentTimeMillis()
            if (res.timedOut) throw IllegalStateException("查询 appops 超时")
            for ((pkg, text) in BatchOutput.split(res.stdout)) {
                val dump = AppOpsParser.parse(text)
                if (dump.errors.isNotEmpty() && dump.entries.isEmpty()) continue
                result[pkg] = AccessSummary.from(pkg, dump, capturedAt)
            }
        }
        return result
    }

    /** 刷新所有应用的访问记录。返回抓到的应用数量。 */
    suspend fun refreshAccess(): Int {
        if (!shizuku.status.value.isReady) {
            _accessError.value = "Shizuku 未就绪，无法读取访问记录"
            return 0
        }
        _accessLoading.value = true
        _accessError.value = null
        return try {
            if (_scanned.value.isEmpty()) refreshApps()
            val map = fetchAccess(accessTargets(_scanned.value))
            accessCache.replace(map)
            map.size
        } catch (e: Exception) {
            Log.w(TAG, "读取访问记录失败", e)
            _accessError.value = "读取访问记录失败：${e.message}"
            0
        } finally {
            _accessLoading.value = false
        }
    }

    private suspend fun refreshAccessFor(packageName: String) {
        val app = _scanned.value.firstOrNull { it.facts.packageName == packageName } ?: return
        try {
            fetchAccess(listOf(app))[packageName]?.let { accessCache.put(it) }
        } catch (e: Exception) {
            Log.w(TAG, "刷新 $packageName 的访问记录失败", e)
        }
    }

    // ---------------------------------------------------------------- Shizuku：直接操作

    /**
     * 执行一次变更并写入操作日志。
     * appop 变更会先记录变更前 uid 级别的模式（用于撤回），执行后延迟复查实际模式；
     * 撤销 / 授予权限会用 PackageManager 复查授予状态。
     */
    suspend fun applyChange(
        packageName: String,
        change: Change,
        trigger: Trigger,
        rule: TightenRule? = null,
        reason: String? = null,
        undoOf: String? = null,
        verifyDelayMs: Long = 500,
    ): OperationRecord {
        val scannedApp = _scanned.value.firstOrNull { it.facts.packageName == packageName }
            ?: withContext(Dispatchers.IO) { scanner.scanOne(packageName) }
        val label = scannedApp?.facts?.label ?: packageName
        val uid = scannedApp?.facts?.uid ?: -1
        val userId = if (uid >= 0) uid / 100_000 else scanner.userId

        var previousUidMode: String? = null
        var verifiedMode: String? = null
        var success: Boolean
        var message: String
        try {
            require(uid >= 0) { "找不到应用 $packageName" }
            if (change is Change.SetUidAppOp) {
                val before = shizuku.exec(ShellCommands.appOpsGetUid(uid, change.op))
                val dump = AppOpsParser.parse(before.stdout)
                previousUidMode = dump.entries.firstOrNull { it.op == change.op }?.mode ?: dump.defaultMode
            }
            val res = shizuku.exec(ShellCommands.forChange(change, packageName, uid, userId))
            val output = res.combined
            success = res.ok && !output.contains("Exception") && !output.startsWith("Error")
            message = if (success) "执行成功" else "执行失败（退出码 ${res.exitCode}）：${output.ifBlank { "无输出" }}"

            if (success) {
                when (change) {
                    is Change.SetUidAppOp -> {
                        delay(verifyDelayMs)
                        val after = shizuku.exec(ShellCommands.appOpsGetPackage(packageName, userId, change.op))
                        val dump = AppOpsParser.parse(after.stdout)
                        verifiedMode = dump.effectiveMode(change.op) ?: dump.defaultMode
                        message = if (verifiedMode == change.mode) {
                            "执行成功，${verifyDelayMs / 1000.0} 秒后复查：实际模式为 $verifiedMode"
                        } else {
                            "命令执行成功，但 ${verifyDelayMs / 1000.0} 秒后复查到的实际模式是 ${verifiedMode ?: "未知"}，已被系统改回或被其他设置覆盖"
                        }
                    }
                    is Change.RevokePermission -> {
                        val stillGranted = isGranted(packageName, change.permission)
                        if (stillGranted) {
                            success = false
                            message = "命令执行了，但复查发现权限仍处于授予状态（可能是系统固定权限）"
                        } else {
                            message = "已撤销，复查确认权限未授予"
                        }
                    }
                    is Change.GrantPermission -> {
                        if (!isGranted(packageName, change.permission)) {
                            success = false
                            message = "命令执行了，但复查发现权限仍未授予"
                        } else {
                            message = "已恢复，复查确认权限已授予"
                        }
                    }
                }
            }
        } catch (e: ShizukuUnavailableException) {
            success = false
            message = e.message ?: "Shizuku 不可用"
        } catch (e: Exception) {
            Log.w(TAG, "执行变更失败", e)
            success = false
            message = "执行出错：${e.message}"
        }

        val record = OperationRecord(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            trigger = trigger,
            packageName = packageName,
            appLabel = label,
            uid = uid,
            change = change,
            rule = rule,
            reason = reason,
            success = success,
            message = message,
            previousUidMode = previousUidMode,
            verifiedMode = verifiedMode,
            undoOf = undoOf,
        )
        operationLog.add(record)
        refreshOne(packageName)
        if (change is Change.SetUidAppOp) refreshAccessFor(packageName)
        return record
    }

    private fun isGranted(packageName: String, permission: String): Boolean =
        context.packageManager.checkPermission(permission, packageName) == PackageManager.PERMISSION_GRANTED

    /** 撤回一条记录。自动收紧被撤回时，为 (应用, 规则) 加上豁免，避免下次又被收紧。 */
    suspend fun undo(recordId: String, trigger: Trigger): OperationRecord? {
        val record = operationLog.find(recordId) ?: return null
        if (!record.canUndo) return null
        val inverse = ChangeDescriber.inverse(record.change, record.previousUidMode) ?: return null
        val result = applyChange(
            packageName = record.packageName,
            change = inverse,
            trigger = trigger,
            rule = record.rule,
            reason = "撤回：${ChangeDescriber.title(record.change)}",
            undoOf = record.id,
        )
        if (result.success) {
            operationLog.markUndone(record.id, result.id)
            val rule = record.rule
            if (rule != null) settings.addExemption(Exemption(record.packageName, rule))
        }
        return result
    }

    /**
     * 实验：把运行时权限对应的 appop 设为 ignore，3 秒后复查是否被系统改回。
     * 依据 AOSP，Android 11+ 的 PermissionPolicyService 会监听这些 op 并按权限状态改回。
     */
    suspend fun experimentAppOp(packageName: String, op: String): OperationRecord =
        applyChange(
            packageName = packageName,
            change = Change.SetUidAppOp(op, OpMode.IGNORE),
            trigger = Trigger.EXPERIMENT,
            reason = "验证该 appop 是否会被权限控制器改回",
            verifyDelayMs = 3_000,
        )

    // ---------------------------------------------------------------- 自动收紧

    /** 永远不自动处理的包：本应用、Shizuku 管理器、所有已启用的输入法。 */
    private fun protectedPackages(): Set<String> {
        val set = mutableSetOf(context.packageName, ShizukuController.MANAGER_PACKAGE)
        try {
            val imm = context.getSystemService(InputMethodManager::class.java)
            imm?.enabledInputMethodList?.forEach { set += it.packageName }
        } catch (e: Exception) {
            Log.w(TAG, "读取输入法列表失败", e)
        }
        return set
    }

    private fun grantStates(scanned: List<ScannedApp>, freshAccess: Map<String, AccessSummary>?): List<AppGrantState> =
        scanned.map { app ->
            val f = app.facts
            val access = freshAccess?.get(f.packageName)
            val allFiles: Boolean? = when (f.allFiles) {
                AllFilesAccess.GRANTED -> true
                AllFilesAccess.NOT_GRANTED, AllFilesAccess.NOT_REQUESTED -> false
                AllFilesAccess.UNKNOWN -> access?.allFilesGranted
            }
            AppGrantState(
                packageName = f.packageName,
                uid = f.uid,
                isSystemApp = f.isSystemApp,
                granted = f.grantedPermissions,
                allFilesGranted = allFiles,
                clipboardReadable = access?.clipboardReadable,
            )
        }

    /**
     * 一次完整的检查：扫描 → （有 Shizuku 时）抓访问记录 → 策略判断 → 执行 → 通知 → 更新快照。
     * 同时做风险提醒。WorkManager 周期任务和 App 打开时都会调用。
     */
    suspend fun runAutoCheck(trigger: Trigger): AutoCheckOutcome = checkMutex.withLock {
        _checking.value = true
        try {
            doAutoCheck(trigger)
        } finally {
            _checking.value = false
        }
    }

    private suspend fun doAutoCheck(trigger: Trigger): AutoCheckOutcome {
        val now = System.currentTimeMillis()
        val s = settings.state.value
        val policy = s.policy

        refreshApps()
        val scanned = _scanned.value

        val waitMs = if (trigger == Trigger.AUTO_PERIODIC) 10_000L else 1_500L
        val ready = shizuku.awaitReady(waitMs)
        var freshAccess: Map<String, AccessSummary>? = null
        if (ready) {
            try {
                freshAccess = fetchAccess(accessTargets(scanned))
                accessCache.replace(freshAccess)
            } catch (e: Exception) {
                Log.w(TAG, "自动检查时读取访问记录失败", e)
            }
        }

        // 风险提醒（不需要 Shizuku）
        checkRiskAlerts()

        val states = grantStates(scanned, freshAccess)
        val previous = snapshots.state.value
        val actions = TighteningPlanner.plan(
            policy = policy,
            current = states,
            previous = previous,
            trusted = s.trusted,
            exemptions = s.exemptions,
            protectedPackages = protectedPackages(),
        )

        if (actions.isNotEmpty() && !ready) {
            val pkgs = actions.map { it.packageName }.distinct()
            val key = actions.joinToString("|") { "${it.packageName}:${it.change}" }
            if (s.lastBlockedNoticeKey != key) {
                val labels = pkgs.map { p -> scanned.firstOrNull { it.facts.packageName == p }?.facts?.label ?: p }
                notifier.notifyBlocked(labels, shizuku.status.value)
                settings.update { it.copy(lastBlockedNoticeKey = key) }
            }
            val summary = "发现 ${pkgs.size} 个应用需要收紧，但 Shizuku 不可用（${statusText(shizuku.status.value)}），暂未处理；快照未更新，下次可用时会继续处理"
            settings.update { it.copy(lastAutoCheckAt = now, lastAutoCheckSummary = summary) }
            return AutoCheckOutcome(summary, emptyList())
        }

        val records = mutableListOf<OperationRecord>()
        for (action in actions) {
            records += applyChange(
                packageName = action.packageName,
                change = action.change,
                trigger = trigger,
                rule = action.rule,
                reason = action.reason,
            )
        }
        if (records.isNotEmpty()) {
            records.groupBy { it.packageName }.forEach { (_, list) -> notifier.notifyAutoActions(list) }
        }

        // 收紧之后重新扫描，作为下一次对比的基线
        val finalScanned = if (records.any { it.success }) {
            refreshApps()
            _scanned.value
        } else {
            scanned
        }
        val finalAccess = freshAccess?.let { acc ->
            acc.mapValues { (pkg, summary) ->
                val clipboardIgnored = records.any { r ->
                    val c = r.change
                    r.success && r.packageName == pkg && c is Change.SetUidAppOp && c.op == Op.READ_CLIPBOARD
                }
                if (clipboardIgnored) summary.copy(clipboardMode = OpMode.IGNORE) else summary
            }
        }
        snapshots.save(GrantSnapshot(now, grantStates(finalScanned, finalAccess).associateBy { it.packageName }))

        val ok = records.count { it.success }
        val failed = records.size - ok
        val summary = when {
            !policy.enabled -> "自动收紧未开启；已检查 ${scanned.size} 个应用" + if (ready) "" else "（Shizuku 不可用）"
            previous == null && policy.scope == PolicyScope.NEW_GRANTS_ONLY -> "已建立基线（${scanned.size} 个应用），之后新获得的授权会被自动收紧"
            records.isEmpty() -> "已检查 ${scanned.size} 个应用，没有需要收紧的授权"
            else -> "自动收紧 $ok 项" + if (failed > 0) "，失败 $failed 项" else ""
        }
        settings.update { it.copy(lastAutoCheckAt = now, lastAutoCheckSummary = summary, lastBlockedNoticeKey = null) }
        return AutoCheckOutcome(summary, records)
    }

    /** 新出现的风险应用发通知（被信任的应用不告警）。第一次只建立基线。 */
    private suspend fun checkRiskAlerts() {
        val s = settings.state.value
        val current = buildRows(_scanned.value, s, accessCache.state.value)
        val alerting = current.filter { it.assessment.alerting }
        val alertingNames = alerting.map { it.packageName }.toSet()
        if (!s.alertBaselineDone) {
            settings.update { it.copy(alertBaselineDone = true, alertedPackages = alertingNames) }
            return
        }
        val fresh = alerting.filter { it.packageName !in s.alertedPackages }
        if (fresh.isNotEmpty() && s.riskAlertsEnabled) notifier.notifyRiskApps(fresh)
        settings.update { it.copy(alertedPackages = alertingNames) }
    }

    companion object {
        private const val TAG = "PrivacyEngine"

        fun statusText(status: ShizukuStatus): String = when (status) {
            ShizukuStatus.NotInstalled -> "未安装"
            ShizukuStatus.NotRunning -> "未启动"
            is ShizukuStatus.PermissionRequired -> "未授权"
            ShizukuStatus.TooOld -> "版本过旧"
            is ShizukuStatus.Ready -> "已就绪"
        }
    }
}
