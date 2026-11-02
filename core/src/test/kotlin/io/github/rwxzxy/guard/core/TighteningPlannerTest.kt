package io.github.rwxzxy.guard.core

import io.github.rwxzxy.guard.core.model.Op
import io.github.rwxzxy.guard.core.model.OpMode
import io.github.rwxzxy.guard.core.model.Perm
import io.github.rwxzxy.guard.core.policy.AppGrantState
import io.github.rwxzxy.guard.core.policy.Change
import io.github.rwxzxy.guard.core.policy.ChangeDescriber
import io.github.rwxzxy.guard.core.policy.Exemption
import io.github.rwxzxy.guard.core.policy.GrantSnapshot
import io.github.rwxzxy.guard.core.policy.PolicyScope
import io.github.rwxzxy.guard.core.policy.TightenPolicy
import io.github.rwxzxy.guard.core.policy.TightenRule
import io.github.rwxzxy.guard.core.policy.TighteningPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TighteningPlannerTest {

    private val photosPolicy = TightenPolicy(enabled = true, rules = setOf(TightenRule.REVOKE_FULL_PHOTOS))

    private fun state(pkg: String, vararg granted: String, system: Boolean = false, clipboard: Boolean? = null, allFiles: Boolean? = null) =
        AppGrantState(pkg, 10_000 + pkg.hashCode().mod(1000), system, granted.toSet(), allFiles, clipboard)

    private fun snapshot(vararg states: AppGrantState) = GrantSnapshot(0, states.associateBy { it.packageName })

    private fun plan(
        policy: TightenPolicy,
        current: List<AppGrantState>,
        previous: GrantSnapshot?,
        trusted: Set<String> = emptySet(),
        exemptions: Set<Exemption> = emptySet(),
        protected: Set<String> = emptySet(),
    ) = TighteningPlanner.plan(policy, current, previous, trusted, exemptions, protected)

    @Test
    fun disabledPolicyDoesNothing() {
        val cur = listOf(state("com.a", Perm.READ_MEDIA_IMAGES))
        assertTrue(plan(photosPolicy.copy(enabled = false), cur, snapshot()).isEmpty())
    }

    @Test
    fun firstRunOnlyBuildsBaselineForNewGrantsScope() {
        val cur = listOf(state("com.a", Perm.READ_MEDIA_IMAGES))
        assertTrue(plan(photosPolicy, cur, previous = null).isEmpty())
    }

    @Test
    fun newlyInstalledAppWithPhotosIsTightened() {
        val cur = listOf(state("com.a", Perm.READ_MEDIA_IMAGES, Perm.READ_MEDIA_VIDEO), state("com.old", Perm.READ_MEDIA_IMAGES))
        val prev = snapshot(state("com.old", Perm.READ_MEDIA_IMAGES))
        val actions = plan(photosPolicy, cur, prev)
        assertEquals(2, actions.size)
        assertTrue(actions.all { it.packageName == "com.a" })
        assertEquals(
            setOf(Change.RevokePermission(Perm.READ_MEDIA_IMAGES), Change.RevokePermission(Perm.READ_MEDIA_VIDEO)),
            actions.map { it.change }.toSet(),
        )
        assertTrue(actions.first().reason.contains("新安装"))
    }

    @Test
    fun existingAppThatNewlyGainedPhotosIsTightened() {
        val cur = listOf(state("com.a", Perm.READ_MEDIA_IMAGES))
        val prev = snapshot(state("com.a"))
        val actions = plan(photosPolicy, cur, prev)
        assertEquals(listOf(Change.RevokePermission(Perm.READ_MEDIA_IMAGES)), actions.map { it.change })
        assertTrue(actions.single().reason.contains("新获得"))
    }

    @Test
    fun existingGrantIsLeftAloneInNewGrantsScope() {
        val cur = listOf(state("com.a", Perm.READ_MEDIA_IMAGES))
        assertTrue(plan(photosPolicy, cur, snapshot(state("com.a", Perm.READ_MEDIA_IMAGES))).isEmpty())
    }

    @Test
    fun allUntrustedScopeEnforcesCurrentStateEvenWithoutBaseline() {
        val policy = photosPolicy.copy(scope = PolicyScope.ALL_UNTRUSTED)
        val cur = listOf(state("com.a", Perm.READ_MEDIA_IMAGES), state("com.b"))
        val actions = plan(policy, cur, previous = null)
        assertEquals(listOf("com.a"), actions.map { it.packageName })
    }

    @Test
    fun trustedProtectedAndExemptedAppsAreSkipped() {
        val policy = photosPolicy.copy(scope = PolicyScope.ALL_UNTRUSTED)
        val cur = listOf(
            state("com.trusted", Perm.READ_MEDIA_IMAGES),
            state("com.protected", Perm.READ_MEDIA_IMAGES),
            state("com.exempt", Perm.READ_MEDIA_IMAGES),
            state("com.target", Perm.READ_MEDIA_IMAGES),
        )
        val actions = plan(
            policy, cur, null,
            trusted = setOf("com.trusted"),
            protected = setOf("com.protected"),
            exemptions = setOf(Exemption("com.exempt", TightenRule.REVOKE_FULL_PHOTOS)),
        )
        assertEquals(listOf("com.target"), actions.map { it.packageName })
    }

    @Test
    fun exemptionIsPerRule() {
        val policy = TightenPolicy(
            enabled = true,
            scope = PolicyScope.ALL_UNTRUSTED,
            rules = setOf(TightenRule.REVOKE_FULL_PHOTOS, TightenRule.REVOKE_MEDIA_LOCATION),
        )
        val cur = listOf(state("com.a", Perm.READ_MEDIA_IMAGES, Perm.ACCESS_MEDIA_LOCATION))
        val actions = plan(policy, cur, null, exemptions = setOf(Exemption("com.a", TightenRule.REVOKE_FULL_PHOTOS)))
        assertEquals(listOf(Change.RevokePermission(Perm.ACCESS_MEDIA_LOCATION)), actions.map { it.change })
    }

    @Test
    fun systemAppsSkippedUnlessIncluded() {
        val policy = photosPolicy.copy(scope = PolicyScope.ALL_UNTRUSTED)
        val cur = listOf(state("com.sys", Perm.READ_MEDIA_IMAGES, system = true))
        assertTrue(plan(policy, cur, null).isEmpty())
        assertEquals(1, plan(policy.copy(includeSystemApps = true), cur, null).size)
    }

    @Test
    fun clipboardRuleNeedsKnownState() {
        val policy = TightenPolicy(enabled = true, rules = setOf(TightenRule.IGNORE_CLIPBOARD), scope = PolicyScope.ALL_UNTRUSTED)
        val unknown = listOf(state("com.a", clipboard = null))
        assertTrue(plan(policy, unknown, null).isEmpty())

        val readable = listOf(state("com.a", clipboard = true), state("com.b", clipboard = false))
        val actions = plan(policy, readable, null)
        assertEquals(listOf("com.a"), actions.map { it.packageName })
        assertEquals(Change.SetUidAppOp(Op.READ_CLIPBOARD, OpMode.IGNORE), actions.single().change)
    }

    @Test
    fun clipboardUnknownInPreviousSnapshotIsNotTreatedAsNew() {
        val policy = TightenPolicy(enabled = true, rules = setOf(TightenRule.IGNORE_CLIPBOARD))
        val cur = listOf(state("com.a", clipboard = true), state("com.new", clipboard = true))
        val prev = snapshot(state("com.a", clipboard = null))
        assertEquals(listOf("com.new"), plan(policy, cur, prev).map { it.packageName })
    }

    @Test
    fun allFilesRuleSetsUidAppOpToDeny() {
        val policy = TightenPolicy(enabled = true, rules = setOf(TightenRule.DENY_ALL_FILES))
        val cur = listOf(state("com.a", allFiles = true))
        val prev = snapshot(state("com.a", allFiles = false))
        val action = plan(policy, cur, prev).single()
        assertEquals(Change.SetUidAppOp(Op.MANAGE_EXTERNAL_STORAGE, OpMode.DENY), action.change)
        assertEquals(TightenRule.DENY_ALL_FILES, action.rule)
    }

    @Test
    fun locationCameraMicRules() {
        val policy = TightenPolicy(
            enabled = true,
            scope = PolicyScope.ALL_UNTRUSTED,
            rules = setOf(TightenRule.REVOKE_BACKGROUND_LOCATION, TightenRule.REVOKE_CAMERA, TightenRule.REVOKE_MICROPHONE),
        )
        val cur = listOf(state("com.a", Perm.ACCESS_FINE_LOCATION, Perm.ACCESS_BACKGROUND_LOCATION, Perm.CAMERA, Perm.RECORD_AUDIO))
        val changes = plan(policy, cur, null).map { it.change }.toSet()
        assertEquals(
            setOf(
                Change.RevokePermission(Perm.ACCESS_BACKGROUND_LOCATION),
                Change.RevokePermission(Perm.CAMERA),
                Change.RevokePermission(Perm.RECORD_AUDIO),
            ),
            changes,
        )
    }

    @Test
    fun inverseChanges() {
        assertEquals(
            Change.GrantPermission(Perm.CAMERA),
            ChangeDescriber.inverse(Change.RevokePermission(Perm.CAMERA), null),
        )
        assertEquals(
            Change.SetUidAppOp(Op.READ_CLIPBOARD, OpMode.ALLOW),
            ChangeDescriber.inverse(Change.SetUidAppOp(Op.READ_CLIPBOARD, OpMode.IGNORE), OpMode.ALLOW),
        )
        assertNull(ChangeDescriber.inverse(Change.SetUidAppOp(Op.READ_CLIPBOARD, OpMode.IGNORE), null))
    }

    @Test
    fun revertWarningFollowsAospBehaviour() {
        val cameraIgnore = Change.SetUidAppOp(Op.CAMERA, OpMode.IGNORE)
        assertTrue(ChangeDescriber.likelyRevertedBySystem(cameraIgnore, deviceSdk = 30))
        // Android 10 只同步定位和受限权限
        assertFalse(ChangeDescriber.likelyRevertedBySystem(cameraIgnore, deviceSdk = 29))
        assertTrue(ChangeDescriber.likelyRevertedBySystem(Change.SetUidAppOp(Op.COARSE_LOCATION, OpMode.IGNORE), 29))
        assertFalse(ChangeDescriber.likelyRevertedBySystem(Change.SetUidAppOp(Op.READ_CLIPBOARD, OpMode.IGNORE), 35))
        assertFalse(ChangeDescriber.likelyRevertedBySystem(Change.RevokePermission(Perm.CAMERA), 35))
    }
}
