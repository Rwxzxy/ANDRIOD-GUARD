package io.github.rwxzxy.guard.data

import android.content.Context
import io.github.rwxzxy.guard.core.appops.AccessSummary
import io.github.rwxzxy.guard.core.log.OperationRecord
import io.github.rwxzxy.guard.core.policy.Exemption
import io.github.rwxzxy.guard.core.policy.GrantSnapshot
import io.github.rwxzxy.guard.core.policy.TightenPolicy
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import java.io.File

@Serializable
data class GuardSettings(
    /** 信任列表：不告警、不自动收紧。 */
    val trusted: Set<String> = emptySet(),
    val policy: TightenPolicy = TightenPolicy(),
    /** 撤回自动收紧后产生的豁免。 */
    val exemptions: Set<Exemption> = emptySet(),
    /** 是否在后台发现新的风险应用时发通知。 */
    val riskAlertsEnabled: Boolean = true,
    /** 已经提醒过的风险应用（避免重复通知）。 */
    val alertedPackages: Set<String> = emptySet(),
    /** 第一次检查只建立风险提醒的基线，不发通知。 */
    val alertBaselineDone: Boolean = false,
    val lastAutoCheckAt: Long = 0,
    val lastAutoCheckSummary: String? = null,
    /** 上次“检测到需要收紧但 Shizuku 不可用”时通知过的内容，避免重复通知。 */
    val lastBlockedNoticeKey: String? = null,
)

class SettingsRepository(context: Context) {
    private val store = JsonFileStore(File(context.filesDir, "settings.json"), GuardSettings.serializer()) { GuardSettings() }
    val state: StateFlow<GuardSettings> = store.state

    suspend fun update(transform: (GuardSettings) -> GuardSettings) = store.update(transform)

    suspend fun setTrusted(packageName: String, trusted: Boolean) = update {
        it.copy(trusted = if (trusted) it.trusted + packageName else it.trusted - packageName)
    }

    suspend fun setPolicy(policy: TightenPolicy) = update { it.copy(policy = policy) }

    suspend fun addExemption(exemption: Exemption) = update { it.copy(exemptions = it.exemptions + exemption) }

    suspend fun removeExemption(exemption: Exemption) = update { it.copy(exemptions = it.exemptions - exemption) }
}

class SnapshotRepository(context: Context) {
    private val store = JsonFileStore(File(context.filesDir, "grant_snapshot.json"), GrantSnapshot.serializer().nullable) { null }
    val state: StateFlow<GrantSnapshot?> = store.state

    suspend fun save(snapshot: GrantSnapshot) = store.update { snapshot }
}

class OperationLogRepository(context: Context) {
    private val store = JsonFileStore(
        File(context.filesDir, "operation_log.json"),
        ListSerializer(OperationRecord.serializer()),
    ) { emptyList() }

    /** 最新的记录在前。 */
    val state: StateFlow<List<OperationRecord>> = store.state

    fun find(id: String): OperationRecord? = state.value.firstOrNull { it.id == id }

    suspend fun add(record: OperationRecord) = store.update { (listOf(record) + it).take(MAX_RECORDS) }

    suspend fun markUndone(id: String, undoneBy: String) = store.update { list ->
        list.map { if (it.id == id) it.copy(undoneBy = undoneBy) else it }
    }

    suspend fun clear() = store.update { emptyList() }

    companion object {
        const val MAX_RECORDS = 1000
    }
}

/** 最近一次通过 Shizuku 抓取的访问记录（包名 -> 汇总），App 重启后仍可显示。 */
class AccessCacheRepository(context: Context) {
    private val store = JsonFileStore(
        File(context.filesDir, "access_cache.json"),
        MapSerializer(String.serializer(), AccessSummary.serializer()),
    ) { emptyMap() }
    val state: StateFlow<Map<String, AccessSummary>> = store.state

    suspend fun replace(map: Map<String, AccessSummary>) = store.update { map }

    suspend fun put(summary: AccessSummary) = store.update { it + (summary.packageName to summary) }
}
