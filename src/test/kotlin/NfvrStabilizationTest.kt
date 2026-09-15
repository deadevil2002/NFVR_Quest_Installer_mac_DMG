import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.File

class NfvrStabilizationTest {
    @Test
    fun parsesBoundedBatteryAndSafeOsValues() {
        val battery = parseQuestBatteryDump(
            "level: 87\nstatus: 2\nAC powered: false\nUSB powered: true\nWireless powered: false\n" +
                "noise=".repeat(20_000)
        )
        assertEquals(87, battery.percentage)
        assertEquals(true, battery.charging)
        assertEquals("USB", battery.source)
        assertEquals("Quest OS 64.0", safeQuestOsVersion("Quest OS 64.0\nsecret\n"))
        assertNull(safeQuestOsVersion("<script>"))
    }

    @Test
    fun authorizedDeviceNeedsNoSetupGuidanceAndLicenseIsMasked() {
        assertEquals(DeviceGuidanceState.AUTHORIZED, deviceGuidanceState(
            listOf(AdbDeviceRow("q", AdbDeviceState.DEVICE)), true
        ))
        assertEquals(DeviceGuidanceState.NO_DEVICE, deviceGuidanceState(emptyList(), false))
        assertTrue(maskLicenseKey("ABCD-12345678-WXYZ").startsWith("ABCD"))
        assertTrue(maskLicenseKey("ABCD-12345678-WXYZ").endsWith("WXYZ"))
        assertTrue(!maskLicenseKey("ABCD-12345678-WXYZ").contains("12345678"))
    }

    @Test
    fun installFormatterNeverInventsPercentForIndeterminateWork() {
        val apk = formatInstallProgress(InstallProgressState(false, "APK", 0, 2))
        assertTrue(apk.contains("APK") && apk.contains("0 / 2") && !apk.contains("%"))
        assertEquals("OBB — 1 / 2 — 50%", formatInstallProgress(
            InstallProgressState(true, "OBB", 1, 2)
        ))
        assertNull(InstallProgressState(false, "restart", 1, 2).percent)
        assertEquals("جاهز", formatInstallProgress(InstallProgressState(false, null, 0, null)))
        val active = formatInstallProgress(
            InstallProgressState(
                measurable = true,
                currentItem = "OBB",
                completed = 1,
                total = 2,
                gameName = "Demo Game",
                phase = GameInstallPhase.TRANSFERRING_OBB,
                percentOverride = 37
            )
        )
        assertTrue(active.contains("Demo Game"))
        assertTrue(active.contains("1 / 2"))
        assertTrue(active.contains("TRANSFERRING_OBB"))
        assertTrue(active.endsWith("37%"))
    }

    @Test
    fun chooserGateSerializesAndCanBeReleasedAfterCancellation() {
        val gate = DesktopChooserGate()
        assertTrue(gate.tryAcquire())
        assertTrue(gate.isOpen())
        assertTrue(!gate.tryAcquire())
        gate.release()
        assertTrue(!gate.isOpen())
        assertTrue(gate.tryAcquire())
        gate.release()
    }

    @Test
    fun queuedScanCallbackCannotClearNewerSelection() {
        val selected = InstalledQuestApp(
            packageName = "com.example.newselection",
            versionName = "1.0",
            versionCode = 1,
            displayName = "New Selection",
            apkPath = "/data/app/new.apk",
            thirdParty = true
        )
        assertEquals(
            selected,
            resolveScanSelection(
                capturedPackageName = null,
                currentPackageName = selected.packageName,
                apps = listOf(selected)
            )
        )
    }

    @Test
    fun secondLockIsRejectedAndReleaseAllowsAnotherTestOwner() {
        val file = File.createTempFile("nfvr-lock-", ".lock")
        val first = NfvrSingleInstanceLock.tryAcquire(file)
        assertTrue(first != null)
        assertNull(NfvrSingleInstanceLock.tryAcquire(file))
        first!!.close()
        val second = NfvrSingleInstanceLock.tryAcquire(file)
        assertTrue(second != null)
        second!!.close()
        file.delete()
    }
    @Test
    fun parsesEveryAdbStateWithoutChoosingTheFirstRow() {
        val result = selectAdbDevice(
            """
            List of devices attached
            quest-offline offline transport_id:1
            quest-unauthorized unauthorized usb:
            quest-ready device product:hollywood model:Quest_3
            """.trimIndent()
        )

        assertEquals(3, result.rows.size)
        assertEquals(AdbDeviceState.OFFLINE, result.rows[0].state)
        assertEquals(AdbDeviceState.UNAUTHORIZED, result.rows[1].state)
        assertEquals("quest-ready", result.selectedSerial)
    }

    @Test
    fun multipleAuthorizedDevicesAreNeverSelectedSilently() {
        val result = selectAdbDevice(
            "List of devices attached\none\tdevice\ntwo\tdevice\n"
        )
        assertNull(result.selectedSerial)
        assertTrue(result.message.contains("أكثر من جهاز"))
    }

    @Test
    fun translatesStorageAndRetainsBoundedRawOutput() {
        assertTrue(translateAdbFailure("adb: error: no space left on device").contains("مساحة"))
        assertTrue(translateAdbFailure("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]").contains("أحدث"))
        assertTrue(translateAdbFailure("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]").contains("توقيع"))
        assertTrue(translateAdbFailure("Failure [INSTALL_PARSE_FAILED_BAD_MANIFEST]").contains("غير صالح"))
        val raw = boundedRawCommandOutput("out", "err", 20)
        assertTrue(raw.length <= 60)
        assertTrue(raw.contains("stdout"))
    }

    @Test
    fun insufficientStorageAlwaysPausesDuringPreflight() {
        assertEquals(
            GameInstallPhase.PREFLIGHT,
            transitionInstallPhase(GameInstallPhase.PREFLIGHT, InstallPhaseTransition.STORAGE_CHECK_STARTED)
        )
        assertEquals(
            GameInstallPhase.PAUSED,
            transitionInstallPhase(GameInstallPhase.PREFLIGHT, InstallPhaseTransition.STORAGE_INSUFFICIENT)
        )
        assertEquals(
            GameInstallPhase.INSTALLING_APK,
            transitionInstallPhase(GameInstallPhase.PREFLIGHT, InstallPhaseTransition.STORAGE_AVAILABLE)
        )
    }

    @Test
    fun readsBoundedPlainManifestAndChecksExactObbSizes() {
        val apk = java.io.File.createTempFile("nfvr-test-", ".apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("""<manifest package="com.example.quest"/>""".toByteArray())
            zip.closeEntry()
        }
        assertEquals("com.example.quest", readApkPackageName(apk))
        assertNull(readApkPackageName(apk, maxManifestBytes = 10))

        val expected = listOf(
            ExpectedObbFile("/sdcard/Android/obb/game/main.obb", 4L),
            ExpectedObbFile("/sdcard/Android/obb/game/patch.obb", 2L)
        )
        val valid = verifyExpectedObbFiles(
            expected,
            listOf(
                ActualObbFile(expected[0].remotePath, 4L),
                ActualObbFile(expected[1].remotePath, 2L)
            )
        )
        assertTrue(valid.valid)
        assertTrue(
            !verifyExpectedObbFiles(
                expected,
                listOf(ActualObbFile(expected[0].remotePath, 1L))
            ).valid
        )
        apk.delete()
    }

    @Test
    fun normalQuestFilterKeepsUnknownSourcesAndHidesInternalPackages() {
        assertTrue(!shouldIncludeQuestPackage("com.oculus.store"))
        assertTrue(!shouldIncludeQuestPackage("com.meta.quest.services"))
        assertTrue(!shouldIncludeQuestPackage("com.oculus.accountscenter"))
        assertTrue(shouldIncludeQuestPackage("com.oculus.legitimategame"))
        assertTrue(shouldIncludeQuestPackage("com.example.service"))
        assertTrue(shouldIncludeQuestPackage("com.indie.unknownsource"))
        assertTrue(shouldIncludeQuestPackage("com.oculus.store", showAll = true))
    }

    @Test
    fun appSnapshotKeepsStableObjectsOrderAndSelectionByPackageId() {
        val old = InstalledQuestApp(
            packageName = "com.example.old",
            versionName = "1",
            displayName = "Old"
        )
        val incomingOld = old.copy()
        val incomingNew = InstalledQuestApp(
            packageName = "com.example.new",
            versionName = "1",
            displayName = "New"
        )
        val merged = mergeInstalledAppSnapshot(
            listOf(old),
            listOf(incomingNew, incomingOld)
        )
        assertTrue(merged[0] === old)
        assertEquals(listOf("com.example.old", "com.example.new"), merged.map { it.packageName })
        assertTrue(stableSelectedPackage("com.example.old", merged) === old)
    }

    @Test
    fun scanProgressDoesNotExposePercentBeforeTotalIsKnown() {
        assertNull(InstalledAppsScanProgress(emptyList(), 2, null).percent)
        assertEquals(50, InstalledAppsScanProgress(emptyList(), 2, 4).percent)
    }
}