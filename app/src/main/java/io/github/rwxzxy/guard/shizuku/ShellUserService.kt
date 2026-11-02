package io.github.rwxzxy.guard.shizuku

import android.content.Context
import android.os.Bundle
import androidx.annotation.Keep
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Shizuku UserService：由 Shizuku 在独立进程中以 shell（adb）或 root 身份创建。
 * 只负责执行本应用传来的、已经过校验的命令（pm / cmd appops / sh 脚本）。
 */
class ShellUserService() : IShellService.Stub() {

    /** Shizuku v13 起优先使用带 Context 的构造函数。 */
    @Keep
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    override fun destroy() {
        exitProcess(0)
    }

    override fun processUid(): Int = android.os.Process.myUid()

    override fun execute(argv: Array<String>?, stdin: String?, timeoutMs: Long): Bundle {
        val result = Bundle()
        val args = argv?.toList().orEmpty()
        if (args.isEmpty()) {
            result.putInt(KEY_EXIT, -1)
            result.putString(KEY_ERR, "empty command")
            return result
        }
        try {
            val process = ProcessBuilder(args).start()
            val out = StringBuilder()
            val err = StringBuilder()
            val outReader = drain(process.inputStream, out)
            val errReader = drain(process.errorStream, err)
            process.outputStream.use { os ->
                if (stdin != null) os.write(stdin.toByteArray())
            }
            val finished = process.waitFor(timeoutMs.coerceAtLeast(1_000), TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            outReader.join(2_000)
            errReader.join(2_000)
            result.putInt(KEY_EXIT, if (finished) process.exitValue() else -2)
            result.putString(KEY_OUT, synchronized(out) { out.toString() })
            result.putString(KEY_ERR, synchronized(err) { err.toString() })
            result.putBoolean(KEY_TIMEOUT, !finished)
        } catch (e: Exception) {
            result.putInt(KEY_EXIT, -3)
            result.putString(KEY_ERR, e.toString())
        }
        return result
    }

    private fun drain(stream: InputStream, sink: StringBuilder): Thread = thread(isDaemon = true) {
        stream.bufferedReader().use { reader ->
            val buf = CharArray(8192)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                synchronized(sink) { sink.append(buf, 0, n) }
            }
        }
    }

    companion object {
        const val KEY_EXIT = "exit"
        const val KEY_OUT = "out"
        const val KEY_ERR = "err"
        const val KEY_TIMEOUT = "timeout"
    }
}
