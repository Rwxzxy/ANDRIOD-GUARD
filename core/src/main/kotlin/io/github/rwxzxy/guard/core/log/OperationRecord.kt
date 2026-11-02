package io.github.rwxzxy.guard.core.log

import io.github.rwxzxy.guard.core.policy.Change
import io.github.rwxzxy.guard.core.policy.TightenRule
import kotlinx.serialization.Serializable

/** 操作是怎么触发的。 */
@Serializable
enum class Trigger(val label: String) {
    MANUAL("手动操作"),
    AUTO_PERIODIC("自动收紧：后台定期检查"),
    AUTO_APP_OPEN("自动收紧：打开应用时检查"),
    AUTO_MANUAL_CHECK("自动收紧：手动点击“立即检查”"),
    UNDO_FROM_NOTIFICATION("撤回：通知栏一键撤回"),
    UNDO_FROM_LOG("撤回：操作日志"),
    EXPERIMENT("实验：测试 appop 是否会被改回"),
}

/** 操作日志中的一条记录：本应用做过的每一次撤销 / 收紧 / 恢复。 */
@Serializable
data class OperationRecord(
    val id: String,
    val timestamp: Long,
    val trigger: Trigger,
    val packageName: String,
    val appLabel: String,
    val uid: Int,
    val change: Change,
    val rule: TightenRule? = null,
    val reason: String? = null,
    val success: Boolean,
    /** 执行结果 / 错误输出。 */
    val message: String,
    /** appop 变更前 uid 级别的模式，用于撤回。 */
    val previousUidMode: String? = null,
    /** 执行后复查到的实际模式（appop 变更才有）。 */
    val verifiedMode: String? = null,
    /** 如果这条记录本身是撤回操作，指向被撤回的记录。 */
    val undoOf: String? = null,
    /** 如果这条记录已被撤回，指向撤回它的记录。 */
    val undoneBy: String? = null,
) {
    val canUndo: Boolean get() = success && undoneBy == null && undoOf == null &&
        (change !is Change.SetUidAppOp || previousUidMode != null)
}
