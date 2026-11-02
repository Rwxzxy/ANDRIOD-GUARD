package io.github.rwxzxy.guard.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import io.github.rwxzxy.guard.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

/** Shizuku 的几种状态。 */
sealed interface ShizukuStatus {
    /** 没装 Shizuku 管理器（也没有 Sui）。 */
    data object NotInstalled : ShizukuStatus

    /** 装了，但服务没有启动（每次重启手机后都需要重新启动）。 */
    data object NotRunning : ShizukuStatus

    /** 服务在运行，但还没授权给本应用；permanentlyDenied 表示用户选了“拒绝且不再询问”。 */
    data class PermissionRequired(val permanentlyDenied: Boolean) : ShizukuStatus

    /** Shizuku 版本过旧（v11 之前），不支持 UserService。 */
    data object TooOld : ShizukuStatus

    /** 可用。uid 2000 = adb 启动，0 = root 启动。 */
    data class Ready(val uid: Int, val version: Int) : ShizukuStatus

    val isReady: Boolean get() = this is Ready
}

data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean) {
    val ok: Boolean get() = exitCode == 0 && !timedOut
    val combined: String get() = listOf(stdout.trim(), stderr.trim()).filter { it.isNotEmpty() }.joinToString("\n")
}

class ShizukuUnavailableException(message: String) : Exception(message)

/** 管理 Shizuku 的状态、授权和 UserService 绑定。 */
class ShizukuController(private val context: Context) {

    private val _status = MutableStateFlow<ShizukuStatus>(ShizukuStatus.NotRunning)
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    private val bindMutex = Mutex()
    @Volatile
    private var service: IShellService? = null
    private var connection: ServiceConnection? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
            .tag("guard-shell")
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        service = null
        connection = null
        refresh()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    fun start() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refresh()
    }

    /** 重新计算状态（安装 / 运行 / 授权）。 */
    fun refresh() {
        _status.value = computeStatus()
    }

    private fun computeStatus(): ShizukuStatus {
        val alive = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
        if (!alive) {
            return if (isManagerInstalled()) ShizukuStatus.NotRunning else ShizukuStatus.NotInstalled
        }
        return try {
            if (Shizuku.isPreV11()) return ShizukuStatus.TooOld
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuStatus.Ready(Shizuku.getUid(), Shizuku.getVersion())
            } else {
                ShizukuStatus.PermissionRequired(Shizuku.shouldShowRequestPermissionRationale())
            }
        } catch (e: Throwable) {
            Log.w(TAG, "查询 Shizuku 状态失败", e)
            ShizukuStatus.NotRunning
        }
    }

    fun isManagerInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    fun requestPermission() {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "请求 Shizuku 授权失败", e)
        }
        refresh()
    }

    /** 等待 Shizuku 就绪（后台任务刚启动时，Shizuku 需要一点时间把 binder 发过来）。 */
    suspend fun awaitReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            refresh()
            if (_status.value.isReady) return true
            if (System.currentTimeMillis() >= deadline) return false
            delay(250)
        }
    }

    private suspend fun obtainService(): IShellService = bindMutex.withLock {
        service?.let { existing ->
            if (existing.asBinder().pingBinder()) return@withLock existing
            service = null
        }
        refresh()
        if (!_status.value.isReady) throw ShizukuUnavailableException("Shizuku 未就绪：${_status.value}")

        val deferred = CompletableDeferred<IShellService>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) {
                    val s = IShellService.Stub.asInterface(binder)
                    service = s
                    deferred.complete(s)
                } else {
                    deferred.completeExceptionally(ShizukuUnavailableException("UserService 返回了无效的 binder"))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        connection = conn
        withContext(Dispatchers.Main) { Shizuku.bindUserService(userServiceArgs, conn) }
        try {
            withTimeout(15_000) { deferred.await() }
        } catch (e: Exception) {
            throw ShizukuUnavailableException("连接 Shizuku UserService 超时：${e.message}")
        }
    }

    /** 以 shell 身份执行命令（argv 不经过 shell 解析）。 */
    suspend fun exec(argv: List<String>, stdin: String? = null, timeoutMs: Long = 30_000): ShellResult {
        val s = obtainService()
        return withContext(Dispatchers.IO) {
            val bundle = s.execute(argv.toTypedArray(), stdin, timeoutMs)
            ShellResult(
                exitCode = bundle.getInt(ShellUserService.KEY_EXIT, -1),
                stdout = bundle.getString(ShellUserService.KEY_OUT).orEmpty(),
                stderr = bundle.getString(ShellUserService.KEY_ERR).orEmpty(),
                timedOut = bundle.getBoolean(ShellUserService.KEY_TIMEOUT, false),
            )
        }
    }

    /** 用 sh 执行一段脚本（脚本内容由本应用生成，参数都已校验）。 */
    suspend fun execScript(script: String, timeoutMs: Long = 120_000): ShellResult =
        exec(listOf("sh"), stdin = script, timeoutMs = timeoutMs)

    companion object {
        private const val TAG = "ShizukuController"
        const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
        const val REQUEST_CODE = 4242
    }
}
