package io.github.rwxzxy.guard.core

import io.github.rwxzxy.guard.core.appops.AccessCategory
import io.github.rwxzxy.guard.core.appops.AccessSummary
import io.github.rwxzxy.guard.core.appops.AppOpsParser
import io.github.rwxzxy.guard.core.appops.BatchOutput
import io.github.rwxzxy.guard.core.model.Op
import io.github.rwxzxy.guard.core.shell.ShellCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测试数据按 AOSP AppOpsService.Shell `case "get"` 的打印逻辑构造：
 * 先打印 uid 段（只有第一行带 "Uid mode: " 前缀），再打印包段；
 * 字段顺序：mode → "; time=<dur> ago" → "; rejectTime=<dur> ago" → " (running)" 或 "; duration=<dur>"。
 */
class AppOpsParserTest {

    /** Android 10（android10-release）：time/rejectTime/duration 为 0 时不打印，duration == -1 打印 (running)。 */
    private val android10Output = """
        Uid mode: COARSE_LOCATION: foreground
        CAMERA: ignore
        COARSE_LOCATION: allow; time=+2h3m4s5ms ago
        FINE_LOCATION: allow; time=+5m10s20ms ago (running)
        CAMERA: allow; time=+1h2m3s4ms ago; rejectTime=+30s0ms ago; duration=+2s5ms
        RECORD_AUDIO: allow; time=+3d4h5m6s7ms ago; duration=+1m0s0ms
        READ_CLIPBOARD: allow; time=+12s345ms ago
        READ_EXTERNAL_STORAGE: allow; time=+45s0ms ago
        WRITE_EXTERNAL_STORAGE: allow
        LEGACY_STORAGE: allow
        READ_MEDIA_IMAGES: allow; time=+1m5s0ms ago
    """.trimIndent()

    /** Android 11 ~ 15（android11-release ~ android15-release 中这段代码完全一致）：-1 表示没有记录。 */
    private val android14Output = """
        Uid mode: READ_CLIPBOARD: ignore
        MANAGE_EXTERNAL_STORAGE: allow
        CAMERA: allow; time=+10m0s0ms ago; duration=+3s200ms
        RECORD_AUDIO: allow; time=+1s0ms ago (running)
        READ_CLIPBOARD: allow; time=+1d2h0m0s0ms ago; rejectTime=+5m0s0ms ago
        READ_MEDIA_IMAGES: allow; time=+7m0s0ms ago; duration=0
        READ_MEDIA_VISUAL_USER_SELECTED: allow; time=+3m0s0ms ago
        ACCESS_MEDIA_LOCATION: allow; time=+7m0s0ms ago
        COARSE_LOCATION: foreground; time=+2h0m0s0ms ago; rejectTime=+1h0m0s0ms ago
        FINE_LOCATION: allow; time=+2h0m0s0ms ago
        WAKE_LOCK: allow; time=+20s0ms ago; duration=+1s0ms
    """.trimIndent()

    @Test
    fun parsesAndroid10Format() {
        val dump = AppOpsParser.parse(android10Output)
        assertTrue(dump.hasUidSection)
        assertTrue(dump.unparsedLines.toString(), dump.unparsedLines.isEmpty())
        assertEquals(11, dump.entries.size)

        // uid 段第一行
        val first = dump.entries[0]
        assertEquals(Op.COARSE_LOCATION, first.op)
        assertEquals("foreground", first.mode)
        assertTrue(first.prefixedWithUidMode)
        assertNull(first.timeAgoMs)

        // uid 段的模式覆盖包段：第一次出现即有效模式
        assertEquals("foreground", dump.effectiveMode(Op.COARSE_LOCATION))
        assertEquals("ignore", dump.effectiveMode(Op.CAMERA))

        val camera = dump.entries.first { it.op == Op.CAMERA && it.timeAgoMs != null }
        assertEquals(3_723_004L, camera.timeAgoMs)
        assertEquals(30_000L, camera.rejectTimeAgoMs)
        assertEquals(2_005L, camera.durationMs)
        assertFalse(camera.running)

        assertTrue(dump.isRunning(Op.FINE_LOCATION))
        assertEquals(12_345L, dump.lastAccessAgo(Op.READ_CLIPBOARD))
        assertNull(dump.lastAccessAgo("LEGACY_STORAGE"))
    }

    @Test
    fun parsesAndroid11To15Format() {
        val dump = AppOpsParser.parse(android14Output)
        assertTrue(dump.unparsedLines.toString(), dump.unparsedLines.isEmpty())
        assertEquals("ignore", dump.effectiveMode(Op.READ_CLIPBOARD))
        assertEquals("allow", dump.effectiveMode(Op.MANAGE_EXTERNAL_STORAGE))
        assertEquals(1_000L, dump.lastAccessAgo(Op.RECORD_AUDIO))
        assertTrue(dump.isRunning(Op.RECORD_AUDIO))
        assertEquals(26 * 3_600_000L, dump.lastAccessAgo(Op.READ_CLIPBOARD))
        assertEquals(5 * 60_000L, dump.lastRejectAgo(Op.READ_CLIPBOARD))
        val images = dump.entries.first { it.op == Op.READ_MEDIA_IMAGES }
        assertEquals(0L, images.durationMs)
    }

    @Test
    fun summarizesCategoriesRelativeToCaptureTime() {
        val captured = 1_700_000_000_000L
        val summary = AccessSummary.from("com.example", AppOpsParser.parse(android14Output), captured)
        // 照片取 READ_MEDIA_IMAGES(7m) 和 READ_MEDIA_VISUAL_USER_SELECTED(3m) 中最近的
        assertEquals(captured - 3 * 60_000L, summary.lastAccess(AccessCategory.PHOTOS))
        assertEquals(captured - 10 * 60_000L, summary.lastAccess(AccessCategory.CAMERA))
        assertEquals(captured - 1_000L, summary.lastAccess(AccessCategory.MICROPHONE))
        assertTrue(summary.categories.getValue(AccessCategory.MICROPHONE).running)
        assertEquals(captured - 2 * 3_600_000L, summary.lastAccess(AccessCategory.LOCATION))
        assertEquals(captured - 26 * 3_600_000L, summary.lastAccess(AccessCategory.CLIPBOARD))
        assertEquals("ignore", summary.clipboardMode)
        assertEquals("allow", summary.allFilesMode)
        assertEquals(captured - 1_000L, summary.mostRecentAccess)
    }

    @Test
    fun handlesNoOperations() {
        val dump = AppOpsParser.parse("No operations.\nDefault mode: allow\n")
        assertTrue(dump.noOperations)
        assertEquals("allow", dump.defaultMode)
        assertTrue(dump.entries.isEmpty())
        assertNull(dump.effectiveMode(Op.READ_CLIPBOARD))
    }

    @Test
    fun parsesUidOnlyOutput() {
        val modes = AppOpsParser.parseUidModes("Uid mode: READ_CLIPBOARD: ignore\nMANAGE_EXTERNAL_STORAGE: deny\n")
        assertEquals(mapOf(Op.READ_CLIPBOARD to "ignore", Op.MANAGE_EXTERNAL_STORAGE to "deny"), modes)
        assertTrue(AppOpsParser.parseUidModes("No operations.\nDefault mode: allow").isEmpty())
    }

    @Test
    fun collectsErrorsAndUnknownLines() {
        val dump = AppOpsParser.parse("Error: No UID for com.nope in user 0\nsomething weird\nUnknown(999): mode=7\r\n")
        assertEquals(listOf("Error: No UID for com.nope in user 0"), dump.errors)
        assertEquals(listOf("something weird"), dump.unparsedLines)
        assertEquals("mode=7", dump.effectiveMode("Unknown(999)"))
    }

    @Test
    fun splitsBatchOutputProducedByScript() {
        val script = ShellCommands.batchAppOpsScript(listOf("com.a", "com.b"), 0)
        assertTrue(script.contains("cmd appops get --user 0 com.a"))
        val output = buildString {
            append(BatchOutput.BEGIN).append("com.a\n")
            append("CAMERA: allow; time=+1s0ms ago\n")
            append(BatchOutput.END).append("com.a 0\n")
            append(BatchOutput.BEGIN).append("com.b\n")
            append("No operations.\n")
            append(BatchOutput.END).append("com.b 0\n")
        }
        val parts = BatchOutput.split(output)
        assertEquals(setOf("com.a", "com.b"), parts.keys)
        assertEquals(1_000L, AppOpsParser.parse(parts.getValue("com.a")).lastAccessAgo(Op.CAMERA))
        assertTrue(AppOpsParser.parse(parts.getValue("com.b")).noOperations)
    }

}
