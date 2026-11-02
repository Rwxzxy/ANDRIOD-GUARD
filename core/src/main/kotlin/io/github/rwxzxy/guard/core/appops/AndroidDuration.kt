package io.github.rwxzxy.guard.core.appops

/**
 * 解析 android.util.TimeUtils.formatDuration(long, PrintWriter) 的输出。
 *
 * 格式（已在 AOSP android10-release 与 android15-release 的 TimeUtils.java 中核对，两者相同）：
 * - 时长为 0 时输出 `0`；
 * - 否则先输出符号 `+` 或 `-`，再依次输出 `Nd`、`Nh`、`Nm`、`Ns`、`Nms`。
 *   天数为 0 时省略；一旦前面输出过字段，后面的字段即使为 0 也会输出；毫秒总是输出。
 *   例：`+5ms`、`+12s345ms`、`+1h0m3s4ms`、`+3d4h5m6s7ms`。
 */
object AndroidDuration {
    private val FULL = Regex("""^([+-])((?:\d+d)?(?:\d+h)?(?:\d+m(?!s))?(?:\d+s)?(?:\d+ms))$""")
    private val TOKEN = Regex("""(\d+)(ms|d|h|m|s)""")

    /** 返回毫秒数；格式不符合时返回 null。 */
    fun parseMillis(text: String): Long? {
        val s = text.trim()
        if (s == "0") return 0L
        val m = FULL.matchEntire(s) ?: return null
        val sign = if (m.groupValues[1] == "-") -1L else 1L
        var total = 0L
        for (t in TOKEN.findAll(m.groupValues[2])) {
            val n = t.groupValues[1].toLongOrNull() ?: return null
            total += when (t.groupValues[2]) {
                "d" -> n * 86_400_000L
                "h" -> n * 3_600_000L
                "m" -> n * 60_000L
                "s" -> n * 1_000L
                "ms" -> n
                else -> return null
            }
        }
        return sign * total
    }

    /** 生成与 TimeUtils.formatDuration 相同格式的字符串（用于测试数据构造）。 */
    fun format(durationMs: Long): String {
        if (durationMs == 0L) return "0"
        val sb = StringBuilder()
        sb.append(if (durationMs > 0) '+' else '-')
        val d = kotlin.math.abs(durationMs)
        val millis = d % 1000
        var seconds = d / 1000
        val days = seconds / 86_400
        seconds -= days * 86_400
        val hours = seconds / 3_600
        seconds -= hours * 3_600
        val minutes = seconds / 60
        seconds -= minutes * 60
        var printed = false
        if (days > 0) { sb.append(days).append('d'); printed = true }
        if (printed || hours > 0) { sb.append(hours).append('h'); printed = true }
        if (printed || minutes > 0) { sb.append(minutes).append('m'); printed = true }
        if (printed || seconds > 0) { sb.append(seconds).append('s') }
        sb.append(millis).append("ms")
        return sb.toString()
    }
}
