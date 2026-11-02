package io.github.rwxzxy.guard

import android.content.Context
import io.github.rwxzxy.guard.data.AccessCacheRepository
import io.github.rwxzxy.guard.data.AppScanner
import io.github.rwxzxy.guard.data.OperationLogRepository
import io.github.rwxzxy.guard.data.SettingsRepository
import io.github.rwxzxy.guard.data.SnapshotRepository
import io.github.rwxzxy.guard.engine.PrivacyEngine
import io.github.rwxzxy.guard.notify.Notifier
import io.github.rwxzxy.guard.shizuku.ShizukuController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 手写的依赖容器：整个进程只有一份。 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(appContext)
    val snapshots = SnapshotRepository(appContext)
    val operationLog = OperationLogRepository(appContext)
    val accessCache = AccessCacheRepository(appContext)
    val scanner = AppScanner(appContext)
    val shizuku = ShizukuController(appContext)
    val notifier = Notifier(appContext)

    val engine = PrivacyEngine(
        context = appContext,
        scope = appScope,
        scanner = scanner,
        shizuku = shizuku,
        settings = settings,
        snapshots = snapshots,
        operationLog = operationLog,
        accessCache = accessCache,
        notifier = notifier,
    )
}

val Context.container: AppContainer
    get() = (applicationContext as GuardApplication).container
