package io.github.rwxzxy.guard.core.model

/** 照片/视频访问程度。 */
enum class PhotoAccess(val label: String) {
    NONE("无"),
    PARTIAL("部分（仅所选）"),
    FULL("完全"),
}

/** 位置访问程度。 */
enum class LocationAccess(val label: String) {
    NONE("无"),
    FOREGROUND("仅使用期间"),
    BACKGROUND("后台也可"),
}

/** 所有文件访问（MANAGE_EXTERNAL_STORAGE，本质是一个 appop）的状态。 */
enum class AllFilesAccess(val label: String) {
    NOT_REQUESTED("未申请"),
    NOT_GRANTED("已申请，未授予"),
    GRANTED("已授予"),

    /** 已申请，但本进程无法读取 appop 状态（例如 AppOpsManager 调用抛出 SecurityException）。 */
    UNKNOWN("已申请，需要 Shizuku 才能确认"),
}

/** 安装来源的类别。 */
enum class InstallSourceKind(val label: String) {
    STORE("应用商店"),
    PACKAGE_INSTALLER("安装包侧载"),
    OTHER_APP("由其他应用安装"),
    PREINSTALLED("系统预装"),
    UNKNOWN("未知（可能是 adb）"),
}

data class InstallSource(
    val kind: InstallSourceKind,
    val installerPackage: String?,
    val installerName: String?,
) {
    val isFromStore: Boolean get() = kind == InstallSourceKind.STORE
}

/**
 * 从 PackageManager 收集到的、与评级有关的全部事实。
 * 纯数据，便于在 JVM 上做单元测试。
 */
data class AppFacts(
    val packageName: String,
    val label: String,
    val uid: Int,
    val isSystemApp: Boolean,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val targetSdk: Int,
    /** 设备的 Build.VERSION.SDK_INT。 */
    val deviceSdk: Int,
    val installSource: InstallSource,
    val requestedPermissions: Set<String>,
    val grantedPermissions: Set<String>,
    val allFiles: AllFilesAccess,
) {
    fun isGranted(permission: String): Boolean = permission in grantedPermissions

    val photoAccess: PhotoAccess
        get() {
            val full = isGranted(Perm.READ_MEDIA_IMAGES) ||
                isGranted(Perm.READ_MEDIA_VIDEO) ||
                (isGranted(Perm.READ_EXTERNAL_STORAGE) && (deviceSdk < 33 || targetSdk < 33))
            if (full) return PhotoAccess.FULL
            if (deviceSdk >= 34 && isGranted(Perm.READ_MEDIA_VISUAL_USER_SELECTED)) return PhotoAccess.PARTIAL
            return PhotoAccess.NONE
        }

    /** 照片完全授权是否来自旧版存储权限（Android 12 及以下的 READ_EXTERNAL_STORAGE）。 */
    val photoAccessIsLegacyStorage: Boolean
        get() = photoAccess == PhotoAccess.FULL &&
            !isGranted(Perm.READ_MEDIA_IMAGES) && !isGranted(Perm.READ_MEDIA_VIDEO)

    val canReadPhotoLocation: Boolean get() = isGranted(Perm.ACCESS_MEDIA_LOCATION)

    val locationAccess: LocationAccess
        get() {
            val fg = isGranted(Perm.ACCESS_FINE_LOCATION) || isGranted(Perm.ACCESS_COARSE_LOCATION)
            if (!fg) return LocationAccess.NONE
            return if (isGranted(Perm.ACCESS_BACKGROUND_LOCATION)) LocationAccess.BACKGROUND else LocationAccess.FOREGROUND
        }

    val hasPreciseLocation: Boolean get() = isGranted(Perm.ACCESS_FINE_LOCATION)
    val canUseCamera: Boolean get() = isGranted(Perm.CAMERA)
    val canUseMicrophone: Boolean get() = isGranted(Perm.RECORD_AUDIO)

    /** 是否拥有任何一项本应用关心的敏感访问。 */
    val hasSensitiveAccess: Boolean
        get() = photoAccess != PhotoAccess.NONE ||
            allFiles == AllFilesAccess.GRANTED ||
            locationAccess != LocationAccess.NONE ||
            canUseCamera || canUseMicrophone
}
