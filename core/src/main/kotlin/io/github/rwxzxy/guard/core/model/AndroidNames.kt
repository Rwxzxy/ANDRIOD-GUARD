package io.github.rwxzxy.guard.core.model

/** 本应用关心的运行时 / 特殊权限名称（与 android.Manifest.permission 中的字符串一致）。 */
object Perm {
    const val READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    const val READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"
    const val READ_MEDIA_VISUAL_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
    const val READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
    const val ACCESS_MEDIA_LOCATION = "android.permission.ACCESS_MEDIA_LOCATION"
    const val MANAGE_EXTERNAL_STORAGE = "android.permission.MANAGE_EXTERNAL_STORAGE"
    const val CAMERA = "android.permission.CAMERA"
    const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    const val ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val ACCESS_COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"
    const val ACCESS_BACKGROUND_LOCATION = "android.permission.ACCESS_BACKGROUND_LOCATION"

    /** 审计时需要读取授予状态的全部权限。 */
    val AUDITED: Set<String> = setOf(
        READ_MEDIA_IMAGES,
        READ_MEDIA_VIDEO,
        READ_MEDIA_VISUAL_USER_SELECTED,
        READ_EXTERNAL_STORAGE,
        ACCESS_MEDIA_LOCATION,
        MANAGE_EXTERNAL_STORAGE,
        CAMERA,
        RECORD_AUDIO,
        ACCESS_FINE_LOCATION,
        ACCESS_COARSE_LOCATION,
        ACCESS_BACKGROUND_LOCATION,
    )

    /** 去掉 "android.permission." 前缀，用于界面显示。 */
    fun shortName(permission: String): String = permission.removePrefix("android.permission.")

    /** 权限的中文名。 */
    fun label(permission: String): String = when (permission) {
        READ_MEDIA_IMAGES -> "读取照片"
        READ_MEDIA_VIDEO -> "读取视频"
        READ_MEDIA_VISUAL_USER_SELECTED -> "读取所选照片/视频（部分授权）"
        READ_EXTERNAL_STORAGE -> "读取存储（旧版，含照片）"
        ACCESS_MEDIA_LOCATION -> "读取照片中的位置"
        MANAGE_EXTERNAL_STORAGE -> "所有文件访问"
        CAMERA -> "相机"
        RECORD_AUDIO -> "麦克风"
        ACCESS_FINE_LOCATION -> "精确位置"
        ACCESS_COARSE_LOCATION -> "大致位置"
        ACCESS_BACKGROUND_LOCATION -> "后台位置"
        else -> shortName(permission)
    }
}

/**
 * `appops` 命令输出中使用的 op 名称（AppOpsManager.opToName 的结果）。
 * 已在 AOSP android10-release ~ android15-release 的 AppOpsManager 中核对。
 */
object Op {
    const val COARSE_LOCATION = "COARSE_LOCATION"
    const val FINE_LOCATION = "FINE_LOCATION"
    const val CAMERA = "CAMERA"
    const val RECORD_AUDIO = "RECORD_AUDIO"
    const val READ_CLIPBOARD = "READ_CLIPBOARD"
    const val READ_EXTERNAL_STORAGE = "READ_EXTERNAL_STORAGE"
    const val READ_MEDIA_IMAGES = "READ_MEDIA_IMAGES"
    const val READ_MEDIA_VIDEO = "READ_MEDIA_VIDEO"
    const val READ_MEDIA_VISUAL_USER_SELECTED = "READ_MEDIA_VISUAL_USER_SELECTED"
    const val ACCESS_MEDIA_LOCATION = "ACCESS_MEDIA_LOCATION"
    const val MANAGE_EXTERNAL_STORAGE = "MANAGE_EXTERNAL_STORAGE"

    /**
     * 与运行时权限绑定的 op（AppOpsManager.permissionToOp 的 switch op）。
     * Android 11+ 的 PermissionPolicyService 会监听这些 op 的变化，并按权限授予状态把它们改回去。
     */
    val RUNTIME_PERMISSION_BACKED: Set<String> = setOf(
        COARSE_LOCATION,
        FINE_LOCATION,
        CAMERA,
        RECORD_AUDIO,
        READ_EXTERNAL_STORAGE,
        READ_MEDIA_IMAGES,
        READ_MEDIA_VIDEO,
        READ_MEDIA_VISUAL_USER_SELECTED,
        ACCESS_MEDIA_LOCATION,
    )

    /** 运行时权限 -> 对应的 op 名称。 */
    fun forPermission(permission: String): String? = when (permission) {
        Perm.ACCESS_COARSE_LOCATION -> COARSE_LOCATION
        Perm.ACCESS_FINE_LOCATION -> COARSE_LOCATION // FINE 的 switch op 是 COARSE_LOCATION
        Perm.CAMERA -> CAMERA
        Perm.RECORD_AUDIO -> RECORD_AUDIO
        Perm.READ_EXTERNAL_STORAGE -> READ_EXTERNAL_STORAGE
        Perm.READ_MEDIA_IMAGES -> READ_MEDIA_IMAGES
        Perm.READ_MEDIA_VIDEO -> READ_MEDIA_VIDEO
        Perm.READ_MEDIA_VISUAL_USER_SELECTED -> READ_MEDIA_VISUAL_USER_SELECTED
        Perm.ACCESS_MEDIA_LOCATION -> ACCESS_MEDIA_LOCATION
        else -> null
    }

    fun label(op: String): String = when (op) {
        COARSE_LOCATION -> "位置（大致/开关）"
        FINE_LOCATION -> "精确位置"
        CAMERA -> "相机"
        RECORD_AUDIO -> "麦克风"
        READ_CLIPBOARD -> "读取剪贴板"
        READ_EXTERNAL_STORAGE -> "读取存储"
        READ_MEDIA_IMAGES -> "读取照片"
        READ_MEDIA_VIDEO -> "读取视频"
        READ_MEDIA_VISUAL_USER_SELECTED -> "读取所选照片"
        ACCESS_MEDIA_LOCATION -> "读取照片位置"
        MANAGE_EXTERNAL_STORAGE -> "所有文件访问"
        else -> op
    }
}

/** `appops` 的模式名（AppOpsManager.MODE_NAMES）。 */
object OpMode {
    const val ALLOW = "allow"
    const val IGNORE = "ignore"
    const val DENY = "deny"
    const val DEFAULT = "default"
    const val FOREGROUND = "foreground"

    val ALL: Set<String> = setOf(ALLOW, IGNORE, DENY, DEFAULT, FOREGROUND)

    fun label(mode: String?): String = when (mode) {
        ALLOW -> "允许"
        IGNORE -> "忽略（静默返回空）"
        DENY -> "拒绝（报错）"
        DEFAULT -> "默认（由权限决定）"
        FOREGROUND -> "仅前台"
        null -> "未知"
        else -> mode
    }
}
