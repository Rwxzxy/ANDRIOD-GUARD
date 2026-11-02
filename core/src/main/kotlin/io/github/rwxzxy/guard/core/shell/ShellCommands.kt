package io.github.rwxzxy.guard.core.shell

import io.github.rwxzxy.guard.core.appops.BatchOutput
import io.github.rwxzxy.guard.core.model.OpMode
import io.github.rwxzxy.guard.core.policy.Change

/**
 * 生成在 Shizuku（shell 身份）下执行的命令。所有参数都先校验，杜绝命令注入：
 * 只允许合法的包名 / 权限名 / op 名 / 模式名。
 */
object ShellCommands {
    private val PACKAGE = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$""")
    private val PERMISSION = Regex("""^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$""")
    private val OP = Regex("""^[A-Z][A-Z0-9_]*$""")

    fun isValidPackage(name: String): Boolean = PACKAGE.matches(name)

    private fun requirePackage(name: String) = require(isValidPackage(name)) { "非法包名: $name" }
    private fun requirePermission(name: String) = require(PERMISSION.matches(name)) { "非法权限名: $name" }
    private fun requireOp(name: String) = require(OP.matches(name)) { "非法 op: $name" }
    private fun requireMode(mode: String) = require(mode in OpMode.ALL) { "非法模式: $mode" }
    private fun requireNonNegative(value: Int, what: String) = require(value >= 0) { "非法 $what: $value" }

    fun revoke(packageName: String, permission: String, userId: Int): List<String> {
        requirePackage(packageName); requirePermission(permission); requireNonNegative(userId, "userId")
        return listOf("pm", "revoke", "--user", userId.toString(), packageName, permission)
    }

    fun grant(packageName: String, permission: String, userId: Int): List<String> {
        requirePackage(packageName); requirePermission(permission); requireNonNegative(userId, "userId")
        return listOf("pm", "grant", "--user", userId.toString(), packageName, permission)
    }

    /** 查询某个 uid 的 uid 级别模式（数字参数会走 AppOpsService.Shell 里的 nonpackageUid 分支）。 */
    fun appOpsGetUid(uid: Int, op: String? = null): List<String> {
        requireNonNegative(uid, "uid")
        op?.let { requireOp(it) }
        return listOfNotNull("cmd", "appops", "get", uid.toString(), op)
    }

    fun appOpsGetPackage(packageName: String, userId: Int, op: String? = null): List<String> {
        requirePackage(packageName); requireNonNegative(userId, "userId")
        op?.let { requireOp(it) }
        return listOfNotNull("cmd", "appops", "get", "--user", userId.toString(), packageName, op)
    }

    /** 设置 uid 级别的模式（数字 uid 参数会调用 setUidMode）。 */
    fun appOpsSetUid(uid: Int, op: String, mode: String): List<String> {
        requireNonNegative(uid, "uid"); requireOp(op); requireMode(mode)
        return listOf("cmd", "appops", "set", uid.toString(), op, mode)
    }

    fun forChange(change: Change, packageName: String, uid: Int, userId: Int): List<String> = when (change) {
        is Change.RevokePermission -> revoke(packageName, change.permission, userId)
        is Change.GrantPermission -> grant(packageName, change.permission, userId)
        is Change.SetUidAppOp -> appOpsSetUid(uid, change.op, change.mode)
    }

    /**
     * 一次性查询多个包的 appops 记录的 sh 脚本，每个包的输出用标记包起来，结果用 [BatchOutput.split] 拆分。
     * 包名都经过校验，只含字母、数字、下划线和点。
     */
    fun batchAppOpsScript(packageNames: List<String>, userId: Int): String {
        requireNonNegative(userId, "userId")
        packageNames.forEach { requirePackage(it) }
        val sb = StringBuilder()
        for (p in packageNames) {
            sb.append("echo '").append(BatchOutput.BEGIN).append(p).append("'\n")
            sb.append("cmd appops get --user ").append(userId).append(' ').append(p).append(" 2>&1\n")
            sb.append("echo \"").append(BatchOutput.END).append(p).append(" \$?\"\n")
        }
        return sb.toString()
    }
}
