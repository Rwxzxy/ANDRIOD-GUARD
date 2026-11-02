package io.github.rwxzxy.guard.core

import io.github.rwxzxy.guard.core.appops.AccessCategory
import io.github.rwxzxy.guard.core.appops.AccessSummary
import io.github.rwxzxy.guard.core.appops.CategoryAccess
import io.github.rwxzxy.guard.core.model.AllFilesAccess
import io.github.rwxzxy.guard.core.model.LocationAccess
import io.github.rwxzxy.guard.core.model.Perm
import io.github.rwxzxy.guard.core.model.PhotoAccess
import io.github.rwxzxy.guard.core.risk.ReasonCode
import io.github.rwxzxy.guard.core.risk.RiskLevel
import io.github.rwxzxy.guard.core.risk.RiskRater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskRaterTest {

    @Test
    fun appWithoutSensitiveAccessFromStoreIsNone() {
        val r = RiskRater.assess(facts(), NOW, trusted = false)
        assertEquals(0, r.score)
        assertEquals(RiskLevel.NONE, r.level)
        assertTrue(r.reasons.isEmpty())
        assertFalse(r.alerting)
    }

    @Test
    fun fullPhotoAccessIsMediumWithReason() {
        val r = RiskRater.assess(facts(granted = setOf(Perm.READ_MEDIA_IMAGES)), NOW, trusted = false)
        assertEquals(30, r.score)
        assertEquals(RiskLevel.MEDIUM, r.level)
        assertTrue(r.has(ReasonCode.FULL_PHOTOS))
        assertTrue(r.alerting)
    }

    @Test
    fun partialPhotoAccessOnAndroid14IsLow() {
        val f = facts(granted = setOf(Perm.READ_MEDIA_VISUAL_USER_SELECTED))
        assertEquals(PhotoAccess.PARTIAL, f.photoAccess)
        val r = RiskRater.assess(f, NOW, trusted = false)
        assertEquals(RiskLevel.LOW, r.level)
        assertTrue(r.has(ReasonCode.PARTIAL_PHOTOS))
        assertFalse(r.has(ReasonCode.FULL_PHOTOS))
    }

    @Test
    fun fullGrantWinsOverUserSelected() {
        // Android 14 完全授权时，USER_SELECTED 也会一起被授予
        val f = facts(granted = setOf(Perm.READ_MEDIA_IMAGES, Perm.READ_MEDIA_VISUAL_USER_SELECTED))
        assertEquals(PhotoAccess.FULL, f.photoAccess)
    }

    @Test
    fun legacyStorageOnAndroid12CountsAsFullPhotos() {
        val f = facts(granted = setOf(Perm.READ_EXTERNAL_STORAGE), deviceSdk = 31, targetSdk = 31)
        assertEquals(PhotoAccess.FULL, f.photoAccess)
        val r = RiskRater.assess(f, NOW, trusted = false)
        assertTrue(r.has(ReasonCode.LEGACY_STORAGE_PHOTOS))
    }

    @Test
    fun readExternalStorageAloneOnAndroid13ForModernAppIsNotPhotos() {
        val f = facts(granted = setOf(Perm.READ_EXTERNAL_STORAGE), deviceSdk = 33, targetSdk = 33)
        assertEquals(PhotoAccess.NONE, f.photoAccess)
    }

    @Test
    fun mediaLocationOnlyCountsFullyWithPhotoAccess() {
        val withPhotos = RiskRater.assess(
            facts(granted = setOf(Perm.READ_MEDIA_IMAGES, Perm.ACCESS_MEDIA_LOCATION)), NOW, trusted = false,
        )
        assertTrue(withPhotos.has(ReasonCode.PHOTO_LOCATION))
        assertEquals(45, withPhotos.score)
        assertEquals(RiskLevel.HIGH, withPhotos.level)

        val withoutPhotos = RiskRater.assess(facts(granted = setOf(Perm.ACCESS_MEDIA_LOCATION)), NOW, trusted = false)
        assertTrue(withoutPhotos.has(ReasonCode.MEDIA_LOCATION_IDLE))
        assertEquals(RiskLevel.LOW, withoutPhotos.level)
    }

    @Test
    fun allFilesGrantedIsAtLeastHigh() {
        val r = RiskRater.assess(facts(allFiles = AllFilesAccess.GRANTED), NOW, trusted = false)
        assertEquals(40, r.score)
        assertEquals(RiskLevel.HIGH, r.level)
        assertTrue(r.has(ReasonCode.ALL_FILES_GRANTED))
        assertTrue(r.has(ReasonCode.ESCALATED))
    }

    @Test
    fun allFilesUnknownIsExplained() {
        val r = RiskRater.assess(facts(allFiles = AllFilesAccess.UNKNOWN), NOW, trusted = false)
        val reason = r.reasons.single { it.code == ReasonCode.ALL_FILES_UNKNOWN }
        assertTrue(reason.title.contains("Shizuku"))
        assertEquals(RiskLevel.LOW, r.level)
    }

    @Test
    fun backgroundVsForegroundLocation() {
        val bg = facts(granted = setOf(Perm.ACCESS_FINE_LOCATION, Perm.ACCESS_BACKGROUND_LOCATION))
        assertEquals(LocationAccess.BACKGROUND, bg.locationAccess)
        val bgRating = RiskRater.assess(bg, NOW, trusted = false)
        assertTrue(bgRating.has(ReasonCode.BACKGROUND_LOCATION))
        assertEquals(30, bgRating.score)

        val fg = facts(granted = setOf(Perm.ACCESS_COARSE_LOCATION))
        assertEquals(LocationAccess.FOREGROUND, fg.locationAccess)
        val fgRating = RiskRater.assess(fg, NOW, trusted = false)
        assertTrue(fgRating.has(ReasonCode.FOREGROUND_LOCATION))
        assertEquals(5, fgRating.score)

        // 只有后台权限、没有前台定位权限时不算定位
        assertEquals(LocationAccess.NONE, facts(granted = setOf(Perm.ACCESS_BACKGROUND_LOCATION)).locationAccess)
    }

    @Test
    fun sideloadedRecentAppWithFullPhotosIsEscalated() {
        val f = facts(
            granted = setOf(Perm.READ_MEDIA_IMAGES),
            installer = "com.google.android.packageinstaller",
            installedDaysAgo = 1,
        )
        val r = RiskRater.assess(f, NOW, trusted = false)
        assertTrue(r.has(ReasonCode.SIDELOADED))
        assertTrue(r.has(ReasonCode.RECENTLY_INSTALLED))
        // 30 + 15 + 15 = 60
        assertEquals(60, r.score)
        assertEquals(RiskLevel.HIGH, r.level)
    }

    @Test
    fun recentInstallWithoutSensitiveAccessIsMinor() {
        val r = RiskRater.assess(facts(installedDaysAgo = 0), NOW, trusted = false)
        val reason = r.reasons.single { it.code == ReasonCode.RECENTLY_INSTALLED }
        assertEquals(5, reason.points)
        assertTrue(reason.title.contains("今天"))
    }

    @Test
    fun unknownAndOtherAppSources() {
        assertTrue(RiskRater.assess(facts(installer = null), NOW, false).has(ReasonCode.UNKNOWN_SOURCE))
        assertTrue(RiskRater.assess(facts(installer = "com.some.browser"), NOW, false).has(ReasonCode.INSTALLED_BY_OTHER_APP))
    }

    @Test
    fun systemAppsAreNotPenalizedForSource() {
        val r = RiskRater.assess(facts(installer = null, isSystem = true), NOW, false)
        assertFalse(r.has(ReasonCode.UNKNOWN_SOURCE))
        assertEquals(RiskLevel.NONE, r.level)
    }

    @Test
    fun trustedAppIsRatedButDoesNotAlert() {
        val r = RiskRater.assess(facts(allFiles = AllFilesAccess.GRANTED), NOW, trusted = true)
        assertEquals(RiskLevel.HIGH, r.level)
        assertTrue(r.trusted)
        assertFalse(r.alerting)
    }

    @Test
    fun recentClipboardReadFromShizukuAddsPoints() {
        val access = AccessSummary(
            packageName = "com.example.app",
            capturedAt = NOW,
            categories = mapOf(AccessCategory.CLIPBOARD to CategoryAccess(NOW - 60_000, null, false)),
            clipboardMode = "allow",
            allFilesMode = null,
        )
        val r = RiskRater.assess(facts(), NOW, trusted = false, access = access)
        assertTrue(r.has(ReasonCode.RECENT_CLIPBOARD_READ))
        assertEquals(8, r.score)

        val old = access.copy(categories = mapOf(AccessCategory.CLIPBOARD to CategoryAccess(NOW - 3 * DAY, null, false)))
        assertFalse(RiskRater.assess(facts(), NOW, trusted = false, access = old).has(ReasonCode.RECENT_CLIPBOARD_READ))
    }

    @Test
    fun everythingIsCritical() {
        val f = facts(
            granted = setOf(
                Perm.READ_MEDIA_IMAGES, Perm.ACCESS_MEDIA_LOCATION, Perm.CAMERA, Perm.RECORD_AUDIO,
                Perm.ACCESS_FINE_LOCATION, Perm.ACCESS_BACKGROUND_LOCATION,
            ),
            allFiles = AllFilesAccess.GRANTED,
        )
        val r = RiskRater.assess(f, NOW, trusted = false)
        assertEquals(RiskLevel.CRITICAL, r.level)
        // 理由按分数从高到低排序
        assertEquals(r.reasons.map { it.points }.sortedDescending(), r.reasons.map { it.points })
    }

    @Test
    fun levelThresholds() {
        assertEquals(RiskLevel.NONE, RiskRater.levelFor(0))
        assertEquals(RiskLevel.LOW, RiskRater.levelFor(1))
        assertEquals(RiskLevel.LOW, RiskRater.levelFor(19))
        assertEquals(RiskLevel.MEDIUM, RiskRater.levelFor(20))
        assertEquals(RiskLevel.HIGH, RiskRater.levelFor(45))
        assertEquals(RiskLevel.CRITICAL, RiskRater.levelFor(70))
    }
}
