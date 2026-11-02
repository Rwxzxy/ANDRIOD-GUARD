package io.github.rwxzxy.guard.core

import io.github.rwxzxy.guard.core.install.DeclaredPackageSource
import io.github.rwxzxy.guard.core.install.InstallSourceClassifier
import io.github.rwxzxy.guard.core.log.OperationRecord
import io.github.rwxzxy.guard.core.log.Trigger
import io.github.rwxzxy.guard.core.model.InstallSourceKind
import io.github.rwxzxy.guard.core.model.Op
import io.github.rwxzxy.guard.core.model.Perm
import io.github.rwxzxy.guard.core.policy.Change
import io.github.rwxzxy.guard.core.shell.ShellCommands
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

class InstallAndShellTest {

    @Test
    fun classifiesInstallSources() {
        assertEquals(InstallSourceKind.STORE, InstallSourceClassifier.classify("com.android.vending", false).kind)
        assertEquals("Google Play", InstallSourceClassifier.classify("com.android.vending", false).installerName)
        assertEquals(InstallSourceKind.STORE, InstallSourceClassifier.classify("com.huawei.appmarket", false).kind)
        assertEquals(
            InstallSourceKind.PACKAGE_INSTALLER,
            InstallSourceClassifier.classify("com.google.android.packageinstaller", false).kind,
        )
        assertEquals(InstallSourceKind.UNKNOWN, InstallSourceClassifier.classify(null, false).kind)
        assertEquals(InstallSourceKind.PREINSTALLED, InstallSourceClassifier.classify(null, true).kind)
        assertEquals(InstallSourceKind.OTHER_APP, InstallSourceClassifier.classify("com.browser", false).kind)
        // Android 13+ 声明的来源
        assertEquals(
            InstallSourceKind.STORE,
            InstallSourceClassifier.classify("com.unknown.store", false, DeclaredPackageSource.STORE).kind,
        )
        assertEquals(
            InstallSourceKind.PACKAGE_INSTALLER,
            InstallSourceClassifier.classify("com.files", false, DeclaredPackageSource.DOWNLOADED_FILE).kind,
        )
    }

    @Test
    fun buildsCommands() {
        assertEquals(
            listOf("pm", "revoke", "--user", "0", "com.a.b", Perm.READ_MEDIA_IMAGES),
            ShellCommands.revoke("com.a.b", Perm.READ_MEDIA_IMAGES, 0),
        )
        assertEquals(
            listOf("cmd", "appops", "set", "10123", Op.READ_CLIPBOARD, "ignore"),
            ShellCommands.forChange(Change.SetUidAppOp(Op.READ_CLIPBOARD, "ignore"), "com.a.b", 10123, 0),
        )
        assertEquals(listOf("cmd", "appops", "get", "10123", Op.READ_CLIPBOARD), ShellCommands.appOpsGetUid(10123, Op.READ_CLIPBOARD))
        assertEquals(listOf("cmd", "appops", "get", "--user", "10", "com.a.b"), ShellCommands.appOpsGetPackage("com.a.b", 10))
    }

    @Test
    fun rejectsInjection() {
        assertFailsWith<IllegalArgumentException> { ShellCommands.revoke("com.a; rm -rf /", Perm.CAMERA, 0) }
        assertFailsWith<IllegalArgumentException> { ShellCommands.revoke("com.a", "x' y", 0) }
        assertFailsWith<IllegalArgumentException> { ShellCommands.appOpsSetUid(1, "CAMERA;reboot", "ignore") }
        assertFailsWith<IllegalArgumentException> { ShellCommands.appOpsSetUid(1, "CAMERA", "evil") }
        assertFailsWith<IllegalArgumentException> { ShellCommands.batchAppOpsScript(listOf("\$(reboot)"), 0) }
        assertFalse(ShellCommands.isValidPackage("nodot"))
        assertTrue(ShellCommands.isValidPackage("com.example_app.x1"))
    }

    @Test
    fun operationRecordSerializationRoundTrips() {
        val json = Json { ignoreUnknownKeys = true }
        val record = OperationRecord(
            id = "1",
            timestamp = 5,
            trigger = Trigger.AUTO_PERIODIC,
            packageName = "com.a.b",
            appLabel = "A",
            uid = 10001,
            change = Change.SetUidAppOp(Op.READ_CLIPBOARD, "ignore"),
            success = true,
            message = "",
            previousUidMode = "allow",
        )
        val text = json.encodeToString(OperationRecord.serializer(), record)
        assertTrue(text.contains("set_uid_op"))
        assertEquals(record, json.decodeFromString(OperationRecord.serializer(), text))
        assertTrue(record.canUndo)
        assertFalse(record.copy(previousUidMode = null).canUndo)
        assertFalse(record.copy(undoneBy = "2").canUndo)
    }
}
