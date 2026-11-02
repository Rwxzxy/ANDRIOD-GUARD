package io.github.rwxzxy.guard.core.appops

import io.github.rwxzxy.guard.core.model.Op
import kotlinx.serialization.Serializable

/**
 * `cmd appops get <包名|uid> [op]` 输出中的一行。
 *
 * @property timeAgoMs 最近一次被允许的访问距离抓取时刻多久（毫秒）；没有记录时为 null。
 * @property rejectTimeAgoMs 最近一次被拒绝的访问距离抓取时刻多久。
 * @property durationMs 最近一次访问持续的时长。
 * @property running 抓取时该访问仍在进行（例如正在录音）。
 */
data class AppOpEntry(
    val op: String,
    val mode: String,
    val timeAgoMs: Long?,
    val rejectTimeAgoMs: Long?,
    val durationMs: Long?,
    val running: Boolean,
    /** 这一行是否紧跟在 "Uid mode: " 前缀之后（只能确定 uid 段的第一行）。 */
    val prefixedWithUidMode: Boolean,
)

data class AppOpsDump(
    val entries: List<AppOpEntry>,
    /** 输出为 "No operations."。 */
    val noOperations: Boolean,
    /** 指定了 op 且没有记录时，输出里的 "Default mode: xxx"。 */
    val defaultMode: String?,
    /** 以 "Error:" 开头的行。 */
    val errors: List<String>,
    /** 无法识别的行，便于排查不同 ROM 的格式差异。 */
    val unparsedLines: List<String>,
) {
    val hasUidSection: Boolean get() = entries.firstOrNull()?.prefixedWithUidMode == true

    /**
     * 有效模式。依据 AOSP AppOpsService.checkOperationUnchecked：
     * uid 级别的非默认模式优先于包级别模式；而 `appops get` 先输出 uid 段（getUidOps 只返回非默认的 uid 模式），
     * 再输出包段。所以某个 op 第一次出现时的模式就是有效模式。
     */
    fun effectiveMode(op: String): String? = entries.firstOrNull { it.op == op }?.mode

    /** 该 op 最近一次被允许访问距抓取时刻的毫秒数（多行时取最近的一次）。 */
    fun lastAccessAgo(op: String): Long? = entries.filter { it.op == op }.mapNotNull { it.timeAgoMs }.minOrNull()

    fun lastRejectAgo(op: String): Long? = entries.filter { it.op == op }.mapNotNull { it.rejectTimeAgoMs }.minOrNull()

    fun isRunning(op: String): Boolean = entries.any { it.op == op && it.running }
}

/**
 * 解析 `cmd appops get` 的文本输出。
 *
 * 格式依据 AOSP AppOpsService.Shell 中 `case "get"` 的实现：
 * - Android 10（android10-release）：
 *   `OP: mode`，若 time != 0 追加 `; time=<dur> ago`，rejectTime != 0 追加 `; rejectTime=<dur> ago`，
 *   duration == -1 追加 ` (running)`，否则 duration != 0 追加 `; duration=<dur>`。
 * - Android 11 ~ 15（android11-release ~ android15-release，这几个版本该段代码完全相同）：
 *   同样的字段与顺序，只是“没有记录”的判断从 `!= 0` 变成了 `!= -1`，`(running)` 来自 isRunning()。
 * - uid 段：每个没有包名的 PackageOps 之前只打印一次 `Uid mode: `，所以只有 uid 段的第一行带前缀。
 * - 没有任何记录时输出 `No operations.`，若指定了 op 还会输出 `Default mode: <mode>`。
 * 时长格式见 [AndroidDuration]。
 */
object AppOpsParser {
    private const val UID_PREFIX = "Uid mode: "
    private val LINE = Regex("""^([A-Za-z0-9_]+|Unknown\(\d+\)): (allow|ignore|deny|default|foreground|mode=-?\d+)(.*)$""")
    private val TIME = Regex("""; time=(\S+) ago""")
    private val REJECT = Regex("""; rejectTime=(\S+) ago""")
    private val DURATION = Regex("""; duration=(\S+)""")

    fun parse(output: String): AppOpsDump {
        val entries = mutableListOf<AppOpEntry>()
        val errors = mutableListOf<String>()
        val unparsed = mutableListOf<String>()
        var noOps = false
        var defaultMode: String? = null
        for (raw in output.lineSequence()) {
            val line = raw.trimEnd('\r').trim()
            if (line.isEmpty()) continue
            when {
                line == "No operations." -> noOps = true
                line.startsWith("Default mode: ") -> defaultMode = line.removePrefix("Default mode: ").trim()
                line.startsWith("Error:") || line.startsWith("Exception") || line.startsWith("Security exception") -> errors += line
                else -> {
                    val uidPrefixed = line.startsWith(UID_PREFIX)
                    val body = if (uidPrefixed) line.removePrefix(UID_PREFIX) else line
                    val m = LINE.matchEntire(body)
                    if (m == null) {
                        unparsed += line
                    } else {
                        val rest = m.groupValues[3]
                        entries += AppOpEntry(
                            op = m.groupValues[1],
                            mode = m.groupValues[2],
                            timeAgoMs = TIME.find(rest)?.let { AndroidDuration.parseMillis(it.groupValues[1]) },
                            rejectTimeAgoMs = REJECT.find(rest)?.let { AndroidDuration.parseMillis(it.groupValues[1]) },
                            durationMs = DURATION.find(rest)?.let { AndroidDuration.parseMillis(it.groupValues[1]) },
                            running = rest.contains("(running)"),
                            prefixedWithUidMode = uidPrefixed,
                        )
                    }
                }
            }
        }
        return AppOpsDump(entries, noOps, defaultMode, errors, unparsed)
    }

    /**
     * 解析 `cmd appops get <uid> [op]` 的输出：只含 uid 段，所以每一行都是 uid 级别的模式。
     * 返回 op -> mode；没有 uid 级别设置时为空 map。
     */
    fun parseUidModes(output: String): Map<String, String> =
        parse(output).entries.associate { it.op to it.mode }
}

/** 批量查询时，脚本用来分隔每个包输出的标记。 */
object BatchOutput {
    const val BEGIN = "@@AG_BEGIN "
    const val END = "@@AG_END "

    /** 把批量输出拆成 包名 -> 该包的原始输出。 */
    fun split(output: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var current: String? = null
        val buf = StringBuilder()
        for (line in output.lineSequence()) {
            when {
                line.startsWith(BEGIN) -> {
                    current = line.removePrefix(BEGIN).trim()
                    buf.setLength(0)
                }
                line.startsWith(END) -> {
                    val name = current
                    if (name != null) result[name] = buf.toString()
                    current = null
                    buf.setLength(0)
                }
                current != null -> buf.append(line).append('\n')
            }
        }
        return result
    }
}

/** 访问记录的类别。 */
@Serializable
enum class AccessCategory(val label: String, val ops: List<String>) {
    PHOTOS("照片/媒体", listOf(Op.READ_MEDIA_IMAGES, Op.READ_MEDIA_VIDEO, Op.READ_MEDIA_VISUAL_USER_SELECTED, Op.READ_EXTERNAL_STORAGE)),
    CAMERA("相机", listOf(Op.CAMERA)),
    MICROPHONE("麦克风", listOf(Op.RECORD_AUDIO)),
    LOCATION("定位", listOf(Op.FINE_LOCATION, Op.COARSE_LOCATION)),
    CLIPBOARD("剪贴板", listOf(Op.READ_CLIPBOARD)),
}

@Serializable
data class CategoryAccess(
    /** 最近一次访问的绝对时间（毫秒时间戳）。 */
    val lastAccessAt: Long?,
    val lastRejectAt: Long?,
    val running: Boolean,
)

/** 一个应用在一次抓取时刻的访问记录汇总。 */
@Serializable
data class AccessSummary(
    val packageName: String,
    val capturedAt: Long,
    val categories: Map<AccessCategory, CategoryAccess>,
    val clipboardMode: String?,
    val allFilesMode: String?,
) {
    fun lastAccess(category: AccessCategory): Long? = categories[category]?.lastAccessAt

    val mostRecentAccess: Long? get() = categories.values.mapNotNull { it.lastAccessAt }.maxOrNull()

    /** 能否读取剪贴板：没有记录即为默认的 allow；allow / foreground 视为可读。 */
    val clipboardReadable: Boolean get() = clipboardMode == null || clipboardMode == "allow" || clipboardMode == "foreground"

    /** 所有文件访问是否开启：allow 为开启；deny / ignore 为关闭；default 或无记录时取决于权限（普通应用为关闭）。 */
    val allFilesGranted: Boolean get() = allFilesMode == "allow"

    companion object {
        fun from(packageName: String, dump: AppOpsDump, capturedAt: Long): AccessSummary {
            val map = AccessCategory.entries.associateWith { cat ->
                val ago = cat.ops.mapNotNull { dump.lastAccessAgo(it) }.minOrNull()
                val rejectAgo = cat.ops.mapNotNull { dump.lastRejectAgo(it) }.minOrNull()
                CategoryAccess(
                    lastAccessAt = ago?.let { capturedAt - it },
                    lastRejectAt = rejectAgo?.let { capturedAt - it },
                    running = cat.ops.any { dump.isRunning(it) },
                )
            }
            return AccessSummary(
                packageName = packageName,
                capturedAt = capturedAt,
                categories = map,
                clipboardMode = dump.effectiveMode(Op.READ_CLIPBOARD),
                allFilesMode = dump.effectiveMode(Op.MANAGE_EXTERNAL_STORAGE),
            )
        }
    }
}
