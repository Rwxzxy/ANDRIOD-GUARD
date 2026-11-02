package io.github.rwxzxy.guard.data

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.InstallSourceInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import io.github.rwxzxy.guard.core.install.DeclaredPackageSource
import io.github.rwxzxy.guard.core.install.InstallSourceClassifier
import io.github.rwxzxy.guard.core.model.AllFilesAccess
import io.github.rwxzxy.guard.core.model.AppFacts
import io.github.rwxzxy.guard.core.model.Perm

/** 一次扫描得到的单个应用。 */
data class ScannedApp(
    val facts: AppFacts,
    val versionName: String?,
    val enabled: Boolean,
)

/**
 * 用 PackageManager 枚举所有已安装应用并读取实际授予的权限。不需要 Shizuku。
 *
 * - 授予状态来自 PackageInfo.requestedPermissionsFlags 的 REQUESTED_PERMISSION_GRANTED 位；
 * - MANAGE_EXTERNAL_STORAGE 是 appop：按 AOSP AppOpsService.checkOperation 的实现（android11~15），
 *   它不要求调用方持有权限、也不校验 uid 是否属于调用方，只受包可见性过滤，
 *   所以声明了 QUERY_ALL_PACKAGES 的普通应用可以用 AppOpsManager.unsafeCheckOpNoThrow 查询别的应用。
 *   如果某个 ROM 改了这里而抛出 SecurityException，就标记为“需要 Shizuku 才能确认”。
 */
class AppScanner(private val context: Context) {

    private val pm: PackageManager = context.packageManager
    private val appOps: AppOpsManager = context.getSystemService(AppOpsManager::class.java)

    fun scan(): List<ScannedApp> {
        val packages = installedPackages()
        val own = context.packageName
        return packages.mapNotNull { pi ->
            if (pi.packageName == own) return@mapNotNull null
            val ai = pi.applicationInfo ?: return@mapNotNull null
            toScannedApp(pi, ai)
        }
    }

    fun scanOne(packageName: String): ScannedApp? = try {
        val pi = packageInfo(packageName)
        pi.applicationInfo?.let { toScannedApp(pi, it) }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** 当前用户 id（uid / 100000）。 */
    val userId: Int get() = Process.myUid() / 100_000

    private fun installedPackages(): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }

    private fun packageInfo(packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        }

    private fun toScannedApp(pi: PackageInfo, ai: ApplicationInfo): ScannedApp {
        val requestedArray: Array<out String?> = pi.requestedPermissions ?: emptyArray()
        val flags: IntArray = pi.requestedPermissionsFlags ?: IntArray(0)
        val requested = mutableSetOf<String>()
        val granted = mutableSetOf<String>()
        requestedArray.forEachIndexed { index, name ->
            if (name == null || name !in Perm.AUDITED) return@forEachIndexed
            requested += name
            val f = flags.getOrElse(index) { 0 }
            if (f and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0) granted += name
        }

        val isSystem = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val allFiles = allFilesAccess(pi.packageName, ai.uid, Perm.MANAGE_EXTERNAL_STORAGE in requested)
        if (allFiles == AllFilesAccess.GRANTED) granted += Perm.MANAGE_EXTERNAL_STORAGE

        val (installer, declared) = installerOf(pi.packageName)
        val installerName = installer?.let { labelOf(it) }
        val source = InstallSourceClassifier.classify(installer, isSystem, declared, installerName)

        val facts = AppFacts(
            packageName = pi.packageName,
            label = ai.loadLabel(pm).toString(),
            uid = ai.uid,
            isSystemApp = isSystem,
            firstInstallTime = pi.firstInstallTime,
            lastUpdateTime = pi.lastUpdateTime,
            targetSdk = ai.targetSdkVersion,
            deviceSdk = Build.VERSION.SDK_INT,
            installSource = source,
            requestedPermissions = requested,
            grantedPermissions = granted,
            allFiles = allFiles,
        )
        return ScannedApp(facts, pi.versionName, ai.enabled)
    }

    private fun allFilesAccess(packageName: String, uid: Int, requested: Boolean): AllFilesAccess {
        if (!requested) return AllFilesAccess.NOT_REQUESTED
        // MANAGE_EXTERNAL_STORAGE 从 Android 11 开始才存在。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return AllFilesAccess.NOT_GRANTED
        return try {
            when (appOps.unsafeCheckOpNoThrow(OPSTR_MANAGE_EXTERNAL_STORAGE, uid, packageName)) {
                AppOpsManager.MODE_ALLOWED -> AllFilesAccess.GRANTED
                AppOpsManager.MODE_DEFAULT ->
                    if (pm.checkPermission(Perm.MANAGE_EXTERNAL_STORAGE, packageName) == PackageManager.PERMISSION_GRANTED) {
                        AllFilesAccess.GRANTED
                    } else {
                        AllFilesAccess.NOT_GRANTED
                    }
                else -> AllFilesAccess.NOT_GRANTED
            }
        } catch (_: SecurityException) {
            AllFilesAccess.UNKNOWN
        } catch (_: IllegalArgumentException) {
            AllFilesAccess.UNKNOWN
        }
    }

    private fun installerOf(packageName: String): Pair<String?, DeclaredPackageSource?> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val info = pm.getInstallSourceInfo(packageName)
                info.installingPackageName to declaredSource(info)
            } catch (_: PackageManager.NameNotFoundException) {
                null to null
            } catch (_: SecurityException) {
                null to null
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(packageName) to null
            } catch (_: IllegalArgumentException) {
                null to null
            }
        }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun declaredSource(info: InstallSourceInfo): DeclaredPackageSource? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) packageSourceOf(info) else null

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun packageSourceOf(info: InstallSourceInfo): DeclaredPackageSource = when (info.packageSource) {
        PackageInstaller.PACKAGE_SOURCE_STORE -> DeclaredPackageSource.STORE
        PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE -> DeclaredPackageSource.LOCAL_FILE
        PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE -> DeclaredPackageSource.DOWNLOADED_FILE
        PackageInstaller.PACKAGE_SOURCE_OTHER -> DeclaredPackageSource.OTHER
        else -> DeclaredPackageSource.UNSPECIFIED
    }

    private fun labelOf(packageName: String): String? = try {
        pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    companion object {
        /** AppOpsManager.OPSTR_MANAGE_EXTERNAL_STORAGE（API 30）。写成字面量避免 InlinedApi。 */
        const val OPSTR_MANAGE_EXTERNAL_STORAGE = "android:manage_external_storage"
    }
}
