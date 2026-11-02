package io.github.rwxzxy.guard.core

import io.github.rwxzxy.guard.core.install.InstallSourceClassifier
import io.github.rwxzxy.guard.core.model.AllFilesAccess
import io.github.rwxzxy.guard.core.model.AppFacts
import io.github.rwxzxy.guard.core.model.InstallSource

const val DAY = 24L * 60 * 60 * 1000
const val NOW = 1_750_000_000_000L

fun facts(
    granted: Set<String> = emptySet(),
    requested: Set<String> = granted,
    installer: String? = "com.android.vending",
    isSystem: Boolean = false,
    installedDaysAgo: Long = 100,
    deviceSdk: Int = 34,
    targetSdk: Int = 34,
    allFiles: AllFilesAccess = AllFilesAccess.NOT_REQUESTED,
    source: InstallSource = InstallSourceClassifier.classify(installer, isSystem),
) = AppFacts(
    packageName = "com.example.app",
    label = "示例",
    uid = 10_123,
    isSystemApp = isSystem,
    firstInstallTime = NOW - installedDaysAgo * DAY,
    lastUpdateTime = NOW - installedDaysAgo * DAY,
    targetSdk = targetSdk,
    deviceSdk = deviceSdk,
    installSource = source,
    requestedPermissions = requested,
    grantedPermissions = granted,
    allFiles = allFiles,
)
