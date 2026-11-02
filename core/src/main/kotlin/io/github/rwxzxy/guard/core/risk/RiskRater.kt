package io.github.rwxzxy.guard.core.risk

import io.github.rwxzxy.guard.core.appops.AccessCategory
import io.github.rwxzxy.guard.core.appops.AccessSummary
import io.github.rwxzxy.guard.core.model.AllFilesAccess
import io.github.rwxzxy.guard.core.model.AppFacts
import io.github.rwxzxy.guard.core.model.InstallSourceKind
import io.github.rwxzxy.guard.core.model.LocationAccess
import io.github.rwxzxy.guard.core.model.PhotoAccess

enum class RiskLevel(val label: String) {
    NONE("无风险"),
    LOW("低风险"),
    MEDIUM("中风险"),
    HIGH("高风险"),
    CRITICAL("严重"),
}

/** 评级理由的种类（稳定标识，便于测试和界面图标）。 */
enum class ReasonCode {
    FULL_PHOTOS,
    LEGACY_STORAGE_PHOTOS,
    PARTIAL_PHOTOS,
    PHOTO_LOCATION,
    MEDIA_LOCATION_IDLE,
    ALL_FILES_GRANTED,
    ALL_FILES_UNKNOWN,
    BACKGROUND_LOCATION,
    FOREGROUND_LOCATION,
    CAMERA,
    MICROPHONE,
    RECENTLY_INSTALLED,
    SIDELOADED,
    INSTALLED_BY_OTHER_APP,
    UNKNOWN_SOURCE,
    RECENT_CLIPBOARD_READ,
    ESCALATED,
}

data class RiskReason(
    val code: ReasonCode,
    val title: String,
    val detail: String,
    /** 该理由贡献的分数；0 表示只是说明，不计分。 */
    val points: Int,
)

data class RiskAssessment(
    val score: Int,
    val level: RiskLevel,
    val reasons: List<RiskReason>,
    val trusted: Boolean,
) {
    /** 是否需要告警：被信任的应用永远不告警。 */
    val alerting: Boolean get() = !trusted && level >= RiskLevel.MEDIUM

    fun has(code: ReasonCode): Boolean = reasons.any { it.code == code }
}

/** 评级参数。默认值即应用使用的规则，写在这里方便测试和在 README 中说明。 */
data class RiskConfig(
    val recentInstallWindowMs: Long = 7L * 24 * 60 * 60 * 1000,
    val recentClipboardWindowMs: Long = 24L * 60 * 60 * 1000,
    val lowThreshold: Int = 1,
    val mediumThreshold: Int = 20,
    val highThreshold: Int = 45,
    val criticalThreshold: Int = 70,
)

/**
 * 风险评级：纯函数，不依赖 Android。
 *
 * 规则（分数相加，再按阈值映射为等级，最后应用“至少为某等级”的升级规则）：
 * - 照片完全授权 +30；旧版存储权限（Android 12-）读全部照片 +30；部分授权 +3
 * - 能读照片拍摄位置（且有照片访问）+15；已授予但当前无照片访问 +2
 * - 所有文件访问 已授予 +40；已申请但无法确认 +10
 * - 后台定位 +30；仅前台定位 精确 +10 / 大致 +5
 * - 相机 +10；麦克风 +12
 * - 最近 7 天新装：有敏感访问 +15，否则 +5
 * - 非商店来源（仅非系统应用）：安装包侧载 +15，由其他应用安装 +12，来源未知 +10
 * - （Shizuku 模式）最近 24 小时读过剪贴板 +8
 * 升级：所有文件访问已授予 ⇒ 至少“高”；完全照片 + 后台定位 ⇒ 至少“高”；
 *       完全照片 + （侧载/未知来源/最近新装）⇒ 至少“高”。
 */
object RiskRater {

    fun assess(
        facts: AppFacts,
        now: Long,
        trusted: Boolean,
        access: AccessSummary? = null,
        config: RiskConfig = RiskConfig(),
    ): RiskAssessment {
        val reasons = mutableListOf<RiskReason>()

        // 照片 / 媒体
        when (facts.photoAccess) {
            PhotoAccess.FULL -> reasons += if (facts.photoAccessIsLegacyStorage) {
                RiskReason(
                    ReasonCode.LEGACY_STORAGE_PHOTOS,
                    "可读取全部照片和文件（旧版存储权限）",
                    "已授予 READ_EXTERNAL_STORAGE。在 Android 12 及以下，这意味着能随时读取相册里的所有照片、视频以及其他共享文件。",
                    30,
                )
            } else {
                RiskReason(
                    ReasonCode.FULL_PHOTOS,
                    "可读取全部照片和视频",
                    "已授予 READ_MEDIA_IMAGES / READ_MEDIA_VIDEO（完全授权），能随时读取相册里的所有内容，而不只是你挑选的那几张。",
                    30,
                )
            }
            PhotoAccess.PARTIAL -> reasons += RiskReason(
                ReasonCode.PARTIAL_PHOTOS,
                "仅能读取你选中的照片",
                "Android 14+ 的部分授权（READ_MEDIA_VISUAL_USER_SELECTED）：只能读取你在系统选择器里勾选过的照片/视频。",
                3,
            )
            PhotoAccess.NONE -> Unit
        }

        if (facts.canReadPhotoLocation) {
            reasons += if (facts.photoAccess != PhotoAccess.NONE) {
                RiskReason(
                    ReasonCode.PHOTO_LOCATION,
                    "可读取照片里的拍摄位置",
                    "已授予 ACCESS_MEDIA_LOCATION：读取照片时能拿到未经脱敏的 EXIF GPS 坐标，可推断你的住址、常去地点。",
                    15,
                )
            } else {
                RiskReason(
                    ReasonCode.MEDIA_LOCATION_IDLE,
                    "已授予“读取照片位置”（当前无照片访问）",
                    "已授予 ACCESS_MEDIA_LOCATION，但目前没有照片访问权限，所以暂时不起作用；一旦再获得照片权限就会生效。",
                    2,
                )
            }
        }

        // 所有文件访问
        when (facts.allFiles) {
            AllFilesAccess.GRANTED -> reasons += RiskReason(
                ReasonCode.ALL_FILES_GRANTED,
                "拥有所有文件访问权限",
                "MANAGE_EXTERNAL_STORAGE 已开启：可以绕过分区存储，直接读写共享存储中的所有文件（包括 DCIM 里的照片原文件）。",
                40,
            )
            AllFilesAccess.UNKNOWN -> reasons += RiskReason(
                ReasonCode.ALL_FILES_UNKNOWN,
                "申请了所有文件访问，是否已开启需要 Shizuku 才能确认",
                "该应用在清单中声明了 MANAGE_EXTERNAL_STORAGE，但本机无法直接读取它的开关状态。连接 Shizuku 后可以确认。",
                10,
            )
            AllFilesAccess.NOT_GRANTED, AllFilesAccess.NOT_REQUESTED -> Unit
        }

        // 定位
        when (facts.locationAccess) {
            LocationAccess.BACKGROUND -> reasons += RiskReason(
                ReasonCode.BACKGROUND_LOCATION,
                "可在后台持续定位",
                "已授予 ACCESS_BACKGROUND_LOCATION：即使你没在用它，也能随时获取位置" +
                    if (facts.hasPreciseLocation) "（精确位置）。" else "（大致位置）。",
                30,
            )
            LocationAccess.FOREGROUND -> reasons += RiskReason(
                ReasonCode.FOREGROUND_LOCATION,
                if (facts.hasPreciseLocation) "使用期间可获取精确位置" else "使用期间可获取大致位置",
                "只在应用处于前台（或有前台服务）时可以定位。",
                if (facts.hasPreciseLocation) 10 else 5,
            )
            LocationAccess.NONE -> Unit
        }

        if (facts.canUseCamera) {
            reasons += RiskReason(ReasonCode.CAMERA, "可使用相机", "已授予 CAMERA。", 10)
        }
        if (facts.canUseMicrophone) {
            reasons += RiskReason(ReasonCode.MICROPHONE, "可使用麦克风", "已授予 RECORD_AUDIO。", 12)
        }

        val sensitive = facts.hasSensitiveAccess

        // 新安装
        val sinceInstall = now - facts.firstInstallTime
        val recentlyInstalled = facts.firstInstallTime > 0 && sinceInstall in 0..config.recentInstallWindowMs
        if (recentlyInstalled) {
            val days = sinceInstall / (24L * 60 * 60 * 1000)
            val whenText = if (days == 0L) "今天" else "$days 天前"
            reasons += RiskReason(
                ReasonCode.RECENTLY_INSTALLED,
                "最近新安装（$whenText）",
                if (sensitive) "新装的应用已经拿到敏感权限，值得再确认一下是不是你主动授予的。" else "新装的应用。",
                if (sensitive) 15 else 5,
            )
        }

        // 安装来源（系统应用不看来源）
        val source = facts.installSource
        if (!facts.isSystemApp) {
            when (source.kind) {
                InstallSourceKind.PACKAGE_INSTALLER -> reasons += RiskReason(
                    ReasonCode.SIDELOADED,
                    "不是从应用商店安装的（安装包侧载）",
                    "通过 APK 安装包安装，没有经过应用商店审核。安装器：${source.installerName ?: source.installerPackage ?: "未知"}。",
                    15,
                )
                InstallSourceKind.OTHER_APP -> reasons += RiskReason(
                    ReasonCode.INSTALLED_BY_OTHER_APP,
                    "由其他应用安装",
                    "安装器是 ${source.installerName ?: source.installerPackage}，不在已知应用商店列表中。",
                    12,
                )
                InstallSourceKind.UNKNOWN -> reasons += RiskReason(
                    ReasonCode.UNKNOWN_SOURCE,
                    "安装来源未知",
                    "系统没有记录安装器，通常是通过 adb 安装或安装器信息被清除。",
                    10,
                )
                InstallSourceKind.STORE, InstallSourceKind.PREINSTALLED -> Unit
            }
        }

        // Shizuku 模式下的实际访问记录
        val clipboardAt = access?.lastAccess(AccessCategory.CLIPBOARD)
        if (clipboardAt != null && now - clipboardAt in 0..config.recentClipboardWindowMs) {
            reasons += RiskReason(
                ReasonCode.RECENT_CLIPBOARD_READ,
                "最近 24 小时内读取过剪贴板",
                "来自系统 appops 记录（READ_CLIPBOARD）。",
                8,
            )
        }

        val score = reasons.sumOf { it.points }
        var level = levelFor(score, config)

        // 升级规则
        val fullPhotos = facts.photoAccess == PhotoAccess.FULL
        val untrustedSource = !facts.isSystemApp && source.kind in setOf(
            InstallSourceKind.PACKAGE_INSTALLER,
            InstallSourceKind.OTHER_APP,
            InstallSourceKind.UNKNOWN,
        )
        val escalations = mutableListOf<String>()
        if (facts.allFiles == AllFilesAccess.GRANTED) escalations += "拥有所有文件访问"
        if (fullPhotos && facts.locationAccess == LocationAccess.BACKGROUND) escalations += "能读全部照片且能后台定位"
        if (fullPhotos && (untrustedSource || recentlyInstalled)) {
            escalations += if (untrustedSource) "非商店来源却能读全部照片" else "新装应用能读全部照片"
        }
        if (escalations.isNotEmpty() && level < RiskLevel.HIGH) {
            level = RiskLevel.HIGH
            reasons += RiskReason(
                ReasonCode.ESCALATED,
                "等级上调为“高风险”",
                "命中组合规则：${escalations.joinToString("；")}。",
                0,
            )
        }

        return RiskAssessment(
            score = score,
            level = level,
            reasons = reasons.sortedByDescending { it.points },
            trusted = trusted,
        )
    }

    fun levelFor(score: Int, config: RiskConfig = RiskConfig()): RiskLevel = when {
        score >= config.criticalThreshold -> RiskLevel.CRITICAL
        score >= config.highThreshold -> RiskLevel.HIGH
        score >= config.mediumThreshold -> RiskLevel.MEDIUM
        score >= config.lowThreshold -> RiskLevel.LOW
        else -> RiskLevel.NONE
    }
}
