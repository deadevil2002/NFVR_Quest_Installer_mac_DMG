import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityAndUpdateTest {
    @Test
    fun comparesSemanticVersions() {
        assertTrue(UpdateManager.compareVersions("2.1.0", "2.0.9") > 0)
        assertEquals(0, UpdateManager.compareVersions("2.0", "2.0.0"))
        assertTrue(UpdateManager.compareVersions("1.9.9", "2.0.0") < 0)
        assertTrue(UpdateManager.compareVersions("2.0.0", "2.0.0-beta.2") > 0)
        assertTrue(UpdateManager.compareVersions("2.0.0-beta.10", "2.0.0-beta.2") > 0)
    }

    @Test
    fun onlyAcceptsSafeHttpsUrlsAndAndroidPaths() {
        assertTrue(UpdateManager.isHttpsUrl("https://updates.example.com/app.msi"))
        assertFalse(UpdateManager.isHttpsUrl("http://updates.example.com/app.msi"))
        assertTrue(AndroidPathValidator.isSafe("/sdcard/Android/data/example/files/Mods"))
        assertFalse(AndroidPathValidator.isSafe("/sdcard/Mods;rm -rf /"))
        assertFalse(AndroidPathValidator.isSafe("/sdcard/../data"))
        assertFalse(AndroidPathValidator.isSafe("/sdcard/Mods/*.zip"))
    }

    @Test
    fun rejectsZipTraversal() {
        val zip = File.createTempFile("nfvr-test-", ".zip")
        ZipOutputStream(zip.outputStream()).use {
            it.putNextEntry(ZipEntry("../escaped.txt"))
            it.write("blocked".toByteArray())
            it.closeEntry()
        }
         val result = ModPackageAnalyzer().analyze(zip)
         assertFalse(result.recognized)
        zip.delete()
    }

    @Test
    fun parsesKnownLicenseResponsesOnly() {
        assertTrue(OnlineLicenseApi.parseResponse(200, """{"status":"ok","message":"valid"}""").ok)
        assertTrue(OnlineLicenseApi.parseResponse(200, """{"status":"activated"}""").ok)
        assertFalse(OnlineLicenseApi.parseResponse(200, """{"status":"unexpected"}""").ok)
        assertFalse(OnlineLicenseApi.parseResponse(500, """{"status":"ok"}""").ok)
    }

    @Test
    fun parsesSafeUpdatePolicyMetadata() {
        val metadata = UpdateManager.parseMetadata(
            """
            {
              "latestVersion": "2.1.0",
              "minimumVersion": "2.0.0",
              "mandatory": true,
              "downloadUrl": "https://updates.example.com/nfvr.msi",
              "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "releaseNotes": "Security update"
            }
            """.trimIndent()
        )
        assertEquals("2.1.0", metadata.latestVersion)
        assertTrue(metadata.mandatory)
    }

    @Test
    fun safelyInspectsNormalZipWithoutExtracting() {
        val zip = File.createTempFile("nfvr-test-", ".zip")
        ZipOutputStream(zip.outputStream()).use {
            it.putNextEntry(ZipEntry("Mods/example/mod.json"))
            it.write("{}".toByteArray())
            it.closeEntry()
        }
        val result = ModPackageAnalyzer().analyze(zip)
        assertFalse(result.installable)
        assertTrue(result.message.isNotBlank())
        zip.delete()
    }
}