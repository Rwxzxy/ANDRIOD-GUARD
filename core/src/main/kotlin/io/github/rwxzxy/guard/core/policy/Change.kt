package io.github.rwxzxy.guard.core.policy

import io.github.rwxzxy.guard.core.model.Op
import io.github.rwxzxy.guard.core.model.OpMode
import io.github.rwxzxy.guard.core.model.Perm
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 对某个应用做的一次权限变更。 */
@Serializable
sealed interface Change {
    /** `pm revoke`：撤销运行时权限。 */
    @Serializable
    @SerialName("revoke")
    data class RevokePermission(val permission: String) : Change

    /** `pm grant`：重新授予运行时权限（用于撤回）。 */
    @Serializable
    @SerialName("grant")
    data class GrantPermission(val permission: String) : Change

    /** `cmd appops set <uid> <op> <mode>`：设置 uid 级别的 appop 模式。 */
    @Serializable
    @SerialName("set_uid_op")
    data class SetUidAppOp(val op: String, val mode: String) : Change
}

/** 变更的中文说明与实际效果。效果描述均以 AOSP 源码为依据（见 README“行为核实”一节）。 */
object ChangeDescriber {

    fun title(change: Change): String = when (change) {
        is Change.RevokePermission -> "撤销「${Perm.label(change.permission)}」"
        is Change.GrantPermission -> "恢复「${Perm.label(change.permission)}」"
        is Change.SetUidAppOp -> "把「${Op.label(change.op)}」设为 ${change.mode}（${OpMode.label(change.mode)}）"
    }

    fun effect(change: Change): String = when (change) {
        is Change.RevokePermission ->
            "通过 pm revoke 撤销运行时权限。权限控制器会随之把对应的 appop 同步为 ignore，所以这是持久有效的收紧方式。" +
                "撤销正在使用的权限时，系统通常会结束该应用的进程。对方之后仍可以再次弹窗向你申请。"
        is Change.GrantPermission ->
            "通过 pm grant 重新授予运行时权限，等同于在系统设置里重新打开。"
        is Change.SetUidAppOp -> when {
            change.op == Op.READ_CLIPBOARD && change.mode == OpMode.IGNORE ->
                "对方读取剪贴板时拿到的是空内容（ClipboardManager.getPrimaryClip() 返回 null），不会收到报错，一般不会崩溃。" +
                    "这个 op 没有对应的运行时权限，系统不会把它改回去。"
            change.op == Op.READ_CLIPBOARD && change.mode == OpMode.DENY ->
                "对方读取剪贴板时会抛出 SecurityException，可能直接崩溃。一般请用 ignore。"
            change.op == Op.MANAGE_EXTERNAL_STORAGE && (change.mode == OpMode.DENY || change.mode == OpMode.IGNORE) ->
                "关闭“所有文件访问”。对方的 Environment.isExternalStorageManager() 会返回 false，只能按普通存储/媒体权限访问文件。" +
                    "这是 appop 权限，系统不会自动改回（除非你在设置里重新打开）。"
            change.op in Op.RUNTIME_PERMISSION_BACKED ->
                "这是运行时权限对应的 appop。按 AOSP 源码，Android 11 及以上的 PermissionPolicyService 会监听它的变化，" +
                    "并按权限授予状态改回（还会把包级别的模式重置为默认）。所以这里只作为实验：执行后会延迟复查实际结果。"
            change.mode == OpMode.IGNORE -> "对方的访问会被静默拦截（拿到空结果），通常不报错。"
            change.mode == OpMode.DENY -> "对方的访问会被拒绝并可能收到 SecurityException。"
            else -> "恢复为 ${change.mode}。"
        }
    }

    /** 这次变更是否会被系统权限同步改回（依据 AOSP，仅用于提示）。 */
    fun likelyRevertedBySystem(change: Change, deviceSdk: Int): Boolean =
        change is Change.SetUidAppOp &&
            change.mode != OpMode.ALLOW &&
            change.op in Op.RUNTIME_PERMISSION_BACKED &&
            (deviceSdk >= 30 || change.op == Op.COARSE_LOCATION || change.op == Op.FINE_LOCATION)

    /** 撤回某次变更时需要执行的反向变更。appop 需要事先记录变更前 uid 级别的模式。 */
    fun inverse(change: Change, previousUidMode: String?): Change? = when (change) {
        is Change.RevokePermission -> Change.GrantPermission(change.permission)
        is Change.GrantPermission -> Change.RevokePermission(change.permission)
        is Change.SetUidAppOp -> previousUidMode?.let { Change.SetUidAppOp(change.op, it) }
    }
}
