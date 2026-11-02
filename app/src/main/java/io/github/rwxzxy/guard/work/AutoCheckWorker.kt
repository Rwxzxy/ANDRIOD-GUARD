package io.github.rwxzxy.guard.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.rwxzxy.guard.container
import io.github.rwxzxy.guard.core.log.Trigger
import java.util.concurrent.TimeUnit

/**
 * 定期检查。Android 8 起 manifest 里静态注册的 ACTION_PACKAGE_ADDED 广播收不到，
 * 所以用 WorkManager 定期对比授权快照来发现“新装的应用 / 新获得的授权”。
 */
class AutoCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val outcome = applicationContext.container.engine.runAutoCheck(Trigger.AUTO_PERIODIC)
        Log.i(TAG, outcome.summary)
        Result.success()
    } catch (e: Exception) {
        Log.w(TAG, "定期检查失败", e)
        Result.retry()
    }

    companion object {
        private const val TAG = "AutoCheckWorker"
    }
}

object WorkScheduler {
    private const val UNIQUE_NAME = "auto-check"

    fun schedule(context: Context, intervalMinutes: Int) {
        val request = PeriodicWorkRequestBuilder<AutoCheckWorker>(
            intervalMinutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES,
        ).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
