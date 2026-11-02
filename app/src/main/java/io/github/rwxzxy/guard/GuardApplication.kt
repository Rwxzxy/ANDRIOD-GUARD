package io.github.rwxzxy.guard

import android.app.Application
import io.github.rwxzxy.guard.notify.Notifier
import io.github.rwxzxy.guard.work.WorkScheduler
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

class GuardApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifier.createChannels(this)
        container.shizuku.start()
        // 策略里的检查间隔变化时重新安排 WorkManager 周期任务。
        container.appScope.launch {
            container.settings.state
                .distinctUntilChangedBy { it.policy.intervalMinutes }
                .collect { WorkScheduler.schedule(this@GuardApplication, it.policy.intervalMinutes) }
        }
    }
}
