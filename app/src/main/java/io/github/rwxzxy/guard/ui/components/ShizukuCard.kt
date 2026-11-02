package io.github.rwxzxy.guard.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.rwxzxy.guard.shizuku.ShizukuController
import io.github.rwxzxy.guard.shizuku.ShizukuStatus

private const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
private const val SHIZUKU_GUIDE_URL = "https://shizuku.rikka.app/guide/setup/"

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        // 没有浏览器时忽略
    }
}

private fun openShizuku(context: Context): Boolean {
    val intent = context.packageManager.getLaunchIntentForPackage(ShizukuController.MANAGER_PACKAGE) ?: return false
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return true
}

/**
 * Shizuku 状态卡片：根据四种状态给出引导。
 * @param compact 在应用列表顶部使用的精简版本
 */
@Composable
fun ShizukuCard(
    status: ShizukuStatus,
    onRequestPermission: () -> Unit,
    onRecheck: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    when (status) {
        is ShizukuStatus.Ready -> {
            if (compact) {
                SectionCard(
                    title = "Shizuku 模式已启用",
                    icon = Icons.Filled.CheckCircle,
                    modifier = modifier,
                ) {
                    Hint("以 ${if (status.uid == 0) "root" else "adb（shell）"} 身份运行，可查看实际访问记录并直接撤销 / 收紧权限。")
                }
            } else {
                SectionCard(title = "Shizuku 已连接", icon = Icons.Filled.CheckCircle, modifier = modifier) {
                    Hint("身份：${if (status.uid == 0) "root (uid 0)" else "adb shell (uid ${status.uid})"}；Shizuku API 版本 ${status.version}。")
                }
            }
        }

        ShizukuStatus.NotInstalled -> SectionCard(
            title = "当前为审计模式（未安装 Shizuku）",
            icon = Icons.Filled.AdminPanelSettings,
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Hint("审计模式可以列出谁拿到了敏感权限并跳转系统设置撤销。安装 Shizuku 后还能看到实际访问记录（含剪贴板）、在本应用内直接撤销，以及按策略自动收紧。不需要 root。")
            if (!compact) {
                Text(
                    "安装步骤：\n" +
                        "1. 从 Shizuku 官网或 Google Play 安装 Shizuku。\n" +
                        "2. Android 11 及以上：打开“开发者选项 → 无线调试”，在 Shizuku 里选择“通过无线调试启动”并按提示配对；" +
                        "Android 10：需要用电脑执行一次 adb 命令启动（Shizuku 内有说明）。\n" +
                        "3. 回到本应用，点“授权”。\n" +
                        "注意：手机重启后 Shizuku 需要重新启动。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { openUrl(context, SHIZUKU_DOWNLOAD_URL) }) { Text("下载 Shizuku") }
                OutlinedButton(onClick = onRecheck) { Text("重新检测") }
            }
        }

        ShizukuStatus.NotRunning -> SectionCard(
            title = "Shizuku 未启动，已退回审计模式",
            icon = Icons.Filled.Warning,
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Hint("Shizuku 已安装但服务没有运行（每次重启手机后都需要重新启动）。打开 Shizuku，用“无线调试”（Android 11+）或电脑 adb 启动后回到这里。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (!openShizuku(context)) openUrl(context, SHIZUKU_GUIDE_URL) }) { Text("打开 Shizuku") }
                OutlinedButton(onClick = onRecheck) { Text("重新检测") }
            }
            if (!compact) {
                OutlinedButton(onClick = { openUrl(context, SHIZUKU_GUIDE_URL) }) { Text("查看启动说明") }
            }
        }

        is ShizukuStatus.PermissionRequired -> SectionCard(
            title = "需要授权本应用使用 Shizuku",
            icon = Icons.Filled.Warning,
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ) {
            if (status.permanentlyDenied) {
                Hint("你之前选择了“拒绝且不再询问”。请打开 Shizuku → 已授权应用，手动允许“隐私守卫”。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openShizuku(context) }) { Text("打开 Shizuku") }
                    OutlinedButton(onClick = onRecheck) { Text("重新检测") }
                }
            } else {
                Hint("Shizuku 正在运行。授权后本应用才能以 adb 身份读取 appops 记录、撤销权限。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRequestPermission) { Text("授权") }
                    OutlinedButton(onClick = onRecheck) { Text("重新检测") }
                }
            }
        }

        ShizukuStatus.TooOld -> SectionCard(
            title = "Shizuku 版本过旧",
            icon = Icons.Filled.Warning,
            modifier = modifier,
        ) {
            Hint("需要 Shizuku v11 或更高版本（支持 UserService）。请更新 Shizuku。")
            Button(onClick = { openUrl(context, SHIZUKU_DOWNLOAD_URL) }) { Text("下载新版") }
        }
    }
}
