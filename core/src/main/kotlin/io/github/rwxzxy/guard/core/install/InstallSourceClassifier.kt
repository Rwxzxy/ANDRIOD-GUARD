package io.github.rwxzxy.guard.core.install

import io.github.rwxzxy.guard.core.model.InstallSource
import io.github.rwxzxy.guard.core.model.InstallSourceKind

/** Android 13+ InstallSourceInfo.getPackageSource() 的含义（与 PackageInstaller.PACKAGE_SOURCE_* 对应）。 */
enum class DeclaredPackageSource { UNSPECIFIED, OTHER, STORE, LOCAL_FILE, DOWNLOADED_FILE }

/** 根据安装器包名判断应用是不是从应用商店装的。纯函数。 */
object InstallSourceClassifier {

    /** 常见应用商店（安装器包名 -> 显示名）。 */
    val KNOWN_STORES: Map<String, String> = mapOf(
        "com.android.vending" to "Google Play",
        "com.huawei.appmarket" to "华为应用市场",
        "com.hihonor.appmarket" to "荣耀应用市场",
        "com.xiaomi.market" to "小米应用商店",
        "com.xiaomi.mipicks" to "小米应用商店",
        "com.heytap.market" to "OPPO 软件商店",
        "com.oppo.market" to "OPPO 软件商店",
        "com.bbk.appstore" to "vivo 应用商店",
        "com.meizu.mstore" to "魅族应用商店",
        "com.sec.android.app.samsungapps" to "Galaxy Store",
        "com.amazon.venezia" to "Amazon Appstore",
        "com.tencent.android.qqdownloader" to "应用宝",
        "com.qihoo.appstore" to "360 手机助手",
        "com.baidu.appsearch" to "百度手机助手",
        "com.wandoujia.phoenix2" to "豌豆荚",
        "com.coolapk.market" to "酷安",
        "org.fdroid.fdroid" to "F-Droid",
        "org.fdroid.basic" to "F-Droid Basic",
        "com.looker.droidify" to "Droid-ify",
        "com.aurora.store" to "Aurora Store",
        "com.oneplus.market" to "一加应用商店",
        "com.lenovo.leos.appstore" to "联想应用商店",
        "com.zte.appstore" to "中兴应用商店",
        "com.nubia.neostore" to "努比亚应用商店",
        "com.sec.android.app.samsungapps.una" to "Galaxy Store",
    )

    /** 系统的“安装包安装程序”：说明是用户手动打开 APK 文件安装的。 */
    val PACKAGE_INSTALLERS: Set<String> = setOf(
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.coloros.packageinstaller",
        "com.oplus.appdetail",
        "com.vivo.packageinstaller",
        "com.huawei.packageinstaller",
        "com.hihonor.packageinstaller",
        "com.oneplus.packageinstaller",
    )

    fun classify(
        installerPackage: String?,
        isSystemApp: Boolean,
        declaredSource: DeclaredPackageSource? = null,
        installerName: String? = null,
    ): InstallSource {
        val store = installerPackage?.let { KNOWN_STORES[it] }
        if (store != null) return InstallSource(InstallSourceKind.STORE, installerPackage, store)
        if (declaredSource == DeclaredPackageSource.STORE && installerPackage != null) {
            return InstallSource(InstallSourceKind.STORE, installerPackage, installerName ?: installerPackage)
        }
        if (installerPackage != null && installerPackage in PACKAGE_INSTALLERS) {
            return InstallSource(InstallSourceKind.PACKAGE_INSTALLER, installerPackage, installerName ?: "系统安装程序")
        }
        if (declaredSource == DeclaredPackageSource.LOCAL_FILE || declaredSource == DeclaredPackageSource.DOWNLOADED_FILE) {
            return InstallSource(InstallSourceKind.PACKAGE_INSTALLER, installerPackage, installerName ?: installerPackage)
        }
        if (installerPackage == null) {
            return if (isSystemApp) {
                InstallSource(InstallSourceKind.PREINSTALLED, null, null)
            } else {
                InstallSource(InstallSourceKind.UNKNOWN, null, null)
            }
        }
        return InstallSource(InstallSourceKind.OTHER_APP, installerPackage, installerName ?: installerPackage)
    }
}
