import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the Mods duplicate-trigger bugs.
 *
 * Every file selection fires two analysis triggers (picker callback plus
 * automatic analysis).  The second must be a silent no-op while the first
 * runs; only a genuinely different operation may fail.  A verified
 * install must log its success exactly once.
 */
class ModAnalysisSingleFlightTest {
    private val app = InstalledQuestApp(
        ModPackageAnalyzer.BONELAB_PACKAGE_ID,
        "1.2974.57485",
        2974L
    )

    private fun archive(name: String = "mod.zip"): File {
        val dir = Files.createTempDirectory("nfvr-singleflight-").toFile()
        val file = File(dir, name)
        file.writeText("payload")
        file.setLastModified(1_700_000_000_000L)
        return file
    }

    @Test
    fun oneSelectionProducesOneIdentity() {
        val file = archive()
        try {
            val first = modAnalysisIdentityKey("SERIAL", app, file, null)
            val second = modAnalysisIdentityKey("SERIAL", app, file, null)
            assertTrue(first != null)
            assertEquals(first, second)
        } finally {
            file.parentFile.deleteRecursively()
        }
    }

    @Test
    fun identityDistinguishesDeviceGameAndArchive() {
        val file = archive()
        val other = archive("other.zip")
        try {
            val base = modAnalysisIdentityKey("SERIAL", app, file, null)
            assertFalse(base == modAnalysisIdentityKey("OTHER", app, file, null))
            assertFalse(
                base == modAnalysisIdentityKey(
                    "SERIAL",
                    app.copy(versionName = "9.9.9"),
                    file,
                    null
                )
            )
            assertFalse(base == modAnalysisIdentityKey("SERIAL", app, other, null))
            assertFalse(base == modAnalysisIdentityKey("SERIAL", app, file, "a".repeat(64)))
        } finally {
            file.parentFile.deleteRecursively()
            other.parentFile.deleteRecursively()
        }
    }

    @Test
    fun incompleteSelectionHasNoIdentity() {
        val file = archive()
        try {
            assertNull(modAnalysisIdentityKey("SERIAL", null, file, null))
            assertNull(modAnalysisIdentityKey("SERIAL", app, null, null))
            assertNull(modAnalysisIdentityKey("SERIAL", app, File(file.parentFile, "missing.zip"), null))
        } finally {
            file.parentFile.deleteRecursively()
        }
    }

    @Test
    fun duplicateRunningAnalysisSkipsSilently() {
        val file = archive()
        try {
            val active = modAnalysisIdentityKey("SERIAL", app, file, null)
            val duplicate = modAnalysisIdentityKey("SERIAL", app, file, null)
            // The guard returns before any failure/log call in the caller.
            assertTrue(shouldSkipDuplicateModAnalysis(active, duplicate))
        } finally {
            file.parentFile.deleteRecursively()
        }
    }

    @Test
    fun genuinelyDifferentOperationIsNotSkipped() {
        val file = archive()
        val otherArchive = archive("other.zip")
        try {
            val active = modAnalysisIdentityKey("SERIAL", app, file, null)
            val otherFile = modAnalysisIdentityKey("SERIAL", app, otherArchive, null)
            val otherGame = modAnalysisIdentityKey(
                "SERIAL",
                app.copy(packageName = "com.example.other"),
                file,
                null
            )
            assertFalse(shouldSkipDuplicateModAnalysis(active, otherFile))
            assertFalse(shouldSkipDuplicateModAnalysis(active, otherGame))
            assertFalse(shouldSkipDuplicateModAnalysis(active, null))
            assertFalse(shouldSkipDuplicateModAnalysis(null, otherFile))
        } finally {
            file.parentFile.deleteRecursively()
            otherArchive.parentFile.deleteRecursively()
        }
    }

    @Test
    fun newArchiveAfterCompletionIsNotSkipped() {
        val first = archive("first.zip")
        val second = archive("second.zip")
        try {
            // Previous analysis finished: no active key, fresh trigger runs.
            val incoming = modAnalysisIdentityKey("SERIAL", app, second, null)
            assertFalse(shouldSkipDuplicateModAnalysis(null, incoming))
            assertTrue(incoming != null)
            assertTrue(modAnalysisIdentityKey("SERIAL", app, first, null) != incoming)
        } finally {
            first.parentFile.deleteRecursively()
            second.parentFile.deleteRecursively()
        }
    }

    @Test
    fun verifiedInstallEmitsExactlyOneSuccessMessage() {
        val lines = modInstallSuccessLogLines("تم تثبيت المود والتحقق من الملفات بنجاح.")
        assertEquals(3, lines.size)
        assertEquals("==============================================", lines[0])
        assertEquals("تم تثبيت المود والتحقق من الملفات بنجاح.", lines[1])
        assertEquals("==============================================", lines[2])
        assertEquals(1, lines.count { it.contains("تم تثبيت المود") })
    }
}
