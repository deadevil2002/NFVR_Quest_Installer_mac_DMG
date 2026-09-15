import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NfvrStabilizationTest {
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
}