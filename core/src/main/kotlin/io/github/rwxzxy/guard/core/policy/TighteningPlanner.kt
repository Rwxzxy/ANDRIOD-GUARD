package io.github.rwxzxy.guard.core.policy

import io.github.rwxzxy.guard.core.model.Op
import io.github.rwxzxy.guard.core.model.OpMode
import io.github.rwxzxy.guard.core.model.Perm
import kotlinx.serialization.Serializable

/** 自动收紧规则。 */
@Serializable
enum class TightenRule(val title: String, val description: String) {
    REVOKE_FULL_PHOTOS(
        "撤销照片完全授权",
        "撤销 READ_MEDIA_IMAGES / READ_MEDIA_VIDEO / READ_EXTERNAL_STORAGE。Android 14+ 上若对方还有“所选照片”授权，会降级为只能读你选过的照片。",
    ),
    REVOKE_MEDIA_LOCATION(
        "撤销读取照片位置",
        "撤销 ACCESS_MEDIA_LOCATION，对方读到的照片 EXIF 中的 GPS 位置会被系统抹掉。",
    ),
    REVOKE_BACKGROUND_LOCATION(
        "撤销后台定位",
        "撤销 ACCESS_BACKGROUND_LOCATION，保留“使用期间”定位。",
    ),
    DENY_ALL_FILES(
        "关闭所有文件访问",
        "把 MANAGE_EXTERNAL_STORAGE 这个 appop 设为 deny（与系统设置里关闭开关的效果一致）。",
    ),
    IGNORE_CLIPBOARD(
        "屏蔽读取剪贴板",
        "把 READ_CLIPBOARD 设为 ignore：对方读剪贴板时拿到空内容而不是报错。已启用的输入法不会被处理。",
    ),
    REVOKE_CAMERA("撤销相机", "撤销 CAMERA 运行时权限。"),
    REVOKE_MICROPHONE("撤销麦克风", "撤销 RECORD_AUDIO 运行时权限。"),
}

@Serializable
enum class PolicyScope(val title: String, val description: String) {
    NEW_GRANTS_ONLY(
        "只处理新获得的授权",
        "与上一次检查对比，只收紧“新装的应用”或“刚刚拿到该权限”的应用。第一次检查只建立基线。",
    ),
    ALL_UNTRUSTED(
        "处理所有未信任应用",
        "每次检查都把所有不在信任列表里的应用收紧到规则要求的状态。",
    ),
}

@Serializable
data class TightenPolicy(
    val enabled: Boolean = false,
    val rules: Set<TightenRule> = setOf(TightenRule.REVOKE_FULL_PHOTOS, TightenRule.REVOKE_MEDIA_LOCATION),
    val scope: PolicyScope = PolicyScope.NEW_GRANTS_ONLY,
    val includeSystemApps: Boolean = false,
    /** WorkManager 周期检查的间隔（分钟，最小 15）。 */
    val intervalMinutes: Int = 60,
)

/** 用户撤回某次自动收紧后，对 (应用, 规则) 的豁免，避免下次检查又被收紧。 */
@Serializable
data class Exemption(val packageName: String, val rule: TightenRule)

/** 一个应用在某次检查时的授权状态。用于对比“新获得的授权”。 */
@Serializable
data class AppGrantState(
    val packageName: String,
    val uid: Int,
    val isSystemApp: Boolean,
    val granted: Set<String>,
    /** 所有文件访问是否已开启；null 表示无法确认。 */
    val allFilesGranted: Boolean? = null,
    /** 是否能读剪贴板（READ_CLIPBOARD 的有效模式是 allow/foreground）；null 表示未知（没有 Shizuku 数据）。 */
    val clipboardReadable: Boolean? = null,
)

@Serializable
data class GrantSnapshot(
    val takenAt: Long,
    val apps: Map<String, AppGrantState>,
)

@Serializable
data class PlannedAction(
    val packageName: String,
    val uid: Int,
    val rule: TightenRule,
    val change: Change,
    val reason: String,
)

/** 自动收紧的策略判断：纯函数。 */
object TighteningPlanner {

    private val FULL_PHOTO_PERMS = listOf(Perm.READ_MEDIA_IMAGES, Perm.READ_MEDIA_VIDEO, Perm.READ_EXTERNAL_STORAGE)

    /** 规则在某个状态下是否“命中”；null 表示无法判断。 */
    fun triggered(rule: TightenRule, state: AppGrantState): Boolean? = when (rule) {
        TightenRule.REVOKE_FULL_PHOTOS -> FULL_PHOTO_PERMS.any { it in state.granted }
        TightenRule.REVOKE_MEDIA_LOCATION -> Perm.ACCESS_MEDIA_LOCATION in state.granted
        TightenRule.REVOKE_BACKGROUND_LOCATION -> Perm.ACCESS_BACKGROUND_LOCATION in state.granted
        TightenRule.REVOKE_CAMERA -> Perm.CAMERA in state.granted
        TightenRule.REVOKE_MICROPHONE -> Perm.RECORD_AUDIO in state.granted
        TightenRule.DENY_ALL_FILES -> state.allFilesGranted
        TightenRule.IGNORE_CLIPBOARD -> state.clipboardReadable
    }

    /** 命中规则时要执行的变更。 */
    fun changesFor(rule: TightenRule, state: AppGrantState): List<Change> = when (rule) {
        TightenRule.REVOKE_FULL_PHOTOS -> FULL_PHOTO_PERMS.filter { it in state.granted }.map { Change.RevokePermission(it) }
        TightenRule.REVOKE_MEDIA_LOCATION -> listOf(Change.RevokePermission(Perm.ACCESS_MEDIA_LOCATION))
        TightenRule.REVOKE_BACKGROUND_LOCATION -> listOf(Change.RevokePermission(Perm.ACCESS_BACKGROUND_LOCATION))
        TightenRule.REVOKE_CAMERA -> listOf(Change.RevokePermission(Perm.CAMERA))
        TightenRule.REVOKE_MICROPHONE -> listOf(Change.RevokePermission(Perm.RECORD_AUDIO))
        TightenRule.DENY_ALL_FILES -> listOf(Change.SetUidAppOp(Op.MANAGE_EXTERNAL_STORAGE, OpMode.DENY))
        TightenRule.IGNORE_CLIPBOARD -> listOf(Change.SetUidAppOp(Op.READ_CLIPBOARD, OpMode.IGNORE))
    }

    /**
     * @param current 本次检查到的所有应用状态
     * @param previous 上一次检查保存的快照；null 表示还没有基线
     * @param trusted 信任列表
     * @param protectedPackages 永远不处理的包（本应用、Shizuku、已启用的输入法等）
     */
    fun plan(
        policy: TightenPolicy,
        current: Collection<AppGrantState>,
        previous: GrantSnapshot?,
        trusted: Set<String>,
        exemptions: Set<Exemption>,
        protectedPackages: Set<String>,
    ): List<PlannedAction> {
        if (!policy.enabled || policy.rules.isEmpty()) return emptyList()
        if (policy.scope == PolicyScope.NEW_GRANTS_ONLY && previous == null) return emptyList()

        val actions = mutableListOf<PlannedAction>()
        for (state in current.sortedBy { it.packageName }) {
            val pkg = state.packageName
            if (pkg in trusted || pkg in protectedPackages) continue
            if (state.isSystemApp && !policy.includeSystemApps) continue

            val prev = previous?.apps?.get(pkg)
            val seen = mutableSetOf<Change>()
            for (rule in TightenRule.entries) {
                if (rule !in policy.rules) continue
                if (Exemption(pkg, rule) in exemptions) continue
                if (triggered(rule, state) != true) continue

                val reason = when (policy.scope) {
                    PolicyScope.ALL_UNTRUSTED -> "不在信任列表中，按策略收紧"
                    PolicyScope.NEW_GRANTS_ONLY -> when {
                        prev == null -> "新安装的应用拿到了该权限"
                        else -> when (triggered(rule, prev)) {
                            false -> "自上次检查以来新获得了该权限"
                            // 上次已经有了，或者上次无法判断：都不算“新获得”
                            true, null -> null
                        }
                    }
                } ?: continue

                for (change in changesFor(rule, state)) {
                    if (seen.add(change)) {
                        actions += PlannedAction(pkg, state.uid, rule, change, reason)
                    }
                }
            }
        }
        return actions
    }
}
