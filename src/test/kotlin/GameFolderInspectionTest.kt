import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GameFolderInspectionTest {
    @Test
    fun oneApkWithNoObbIsInstallable() {
        inTempDirectory { folder ->
            writeApk(folder, "game.apk", "com.example.game")

            val inspection = inspectGameFolder(folder)

            assertEquals(GameFolderKind.APK_ONLY, inspection.kind)
            assertTrue(inspection.installable)
            assertEquals("com.example.game", inspection.packageName)
            assertTrue(inspection.obbDirs.isEmpty())
            assertNotNull(inspection.apk)
        }
    }

    @Test
    fun matchingObbDirectoryIsAcceptedAndUnrelatedMetadataIsIgnored() {
        inTempDirectory { folder ->
            writeApk(folder, "com.example.game.apk", "com.example.game")
            File(folder, "release.manifest").writeText("metadata")
            File(folder, "tools").mkdirs()
            File(folder, "tools/readme.txt").writeText("not game data")

            // The directory name is not authoritative; matching .obb names
            // are. Metadata beside them must remain ignored.
            val obb = File(folder, "payload")
            obb.mkdirs()
            File(obb, "main.42.com.example.game.obb").writeBytes(ByteArray(8) { 1 })
            File(obb, "release.manifest").writeText("metadata")

            val inspection = inspectGameFolder(folder)

            assertEquals(GameFolderKind.APK_WITH_OBB, inspection.kind)
            assertEquals(listOf(obb), inspection.obbDirs)
            assertEquals("com.example.game", inspection.packageName)
            assertEquals(
                listOf("payload/main.42.com.example.game.obb"),
                inspection.allowedObbFiles.map { it.relativePath }
            )
            assertTrue(inspection.requiredBytes >= 8L)
        }
    }

    @Test
    fun unrelatedDirectoryAndObbNamesAreNotInstalled() {
        inTempDirectory { folder ->
            writeApk(folder, "game.apk", "com.example.game")
            val unrelated = File(folder, "downloads")
            unrelated.mkdirs()
            File(unrelated, "main.1.com.other.game.obb").writeBytes(ByteArray(4) { 1 })
            val packageDirectory = File(folder, "com.example.game")
            packageDirectory.mkdirs()
            File(packageDirectory, "notes.txt").writeText("not an OBB")

            val inspection = inspectGameFolder(folder)

            assertEquals(GameFolderKind.APK_ONLY, inspection.kind)
            assertTrue(inspection.obbDirs.isEmpty())
            assertTrue(inspection.allowedObbFiles.isEmpty())
        }
    }

    @Test
    fun allowlistIsCanonicalAndRevalidationRejectsReplacementOrEscape() {
        inTempDirectory { folder ->
            writeApk(folder, "game.apk", "com.example.game")
            val payload = File(folder, "arbitrary-name").also { it.mkdirs() }
            val obb = File(payload, "patch.7.com.example.game.obb")
                .also { it.writeBytes(ByteArray(5) { 2 }) }
            val inspection = inspectGameFolder(folder)
            val allowed = inspection.allowedObbFiles.single()

            assertEquals(obb.canonicalFile, allowed.file)
            assertEquals(allowed, inspection.allowedObbFiles.single())
            assertEquals(64, allowed.sha256.length)
            assertEquals(emptyList(), validateAllowedObbFiles(folder, inspection.allowedObbFiles))

            // Same-size replacement must still fail the captured-content
            // check; metadata/length alone are not sufficient.
            obb.writeBytes(ByteArray(5) { 3 })
            assertEquals(listOf(allowed.relativePath), validateAllowedObbFiles(folder, inspection.allowedObbFiles))

            val newlyAdded = File(payload, "main.8.com.example.game.obb")
                .also { it.writeBytes(ByteArray(4) { 4 }) }
            assertTrue(
                validateAllowedObbFiles(folder, inspection.allowedObbFiles)
                    .any { newlyAdded.name in it }
            )
        }
    }

    @Test
    fun rejectsObbSymlinkEscapingSelectedFolder() {
        inTempDirectory { folder ->
            writeApk(folder, "game.apk", "com.example.game")
            val outside = Files.createTempDirectory("nfvr-obb-outside-").toFile()
            try {
                val external = File(outside, "main.1.com.example.game.obb")
                    .also { it.writeBytes(ByteArray(6) { 7 }) }
                val payload = File(folder, "payload").also { it.mkdirs() }
                val link = File(payload, external.name)
                val created = runCatching {
                    Files.createSymbolicLink(link.toPath(), external.toPath())
                }.isSuccess
                if (!created) return@inTempDirectory

                val escaped = AllowedObbFile(
                    file = link,
                    relativePath = "payload/${external.name}",
                    packageName = "com.example.game",
                    sizeBytes = external.length(),
                    sha256 = sha256File(external)!!
                )
                assertEquals(null, revalidateAllowedObbFile(folder, escaped))
                assertTrue(inspectGameFolder(folder).allowedObbFiles.isEmpty())
                assertTrue(
                    validateAllowedObbFiles(folder, listOf(escaped), "com.example.game")
                        .isNotEmpty()
                )
            } finally {
                outside.deleteRecursively()
            }
        }
    }

    @Test
    fun multipleApksAreReportedAsSplitInsteadOfChoosingRandomly() {
        inTempDirectory { folder ->
            writeApk(folder, "base.apk", "com.example.game")
            writeApk(folder, "config.arm64_v8a.apk", "com.example.game")

            val inspection = inspectGameFolder(folder)

            assertEquals(GameFolderKind.SPLIT_APK, inspection.kind)
            assertFalse(inspection.installable)
            assertTrue(inspection.reason.orEmpty().contains("split APKs"))
            assertEquals(null, inspection.apk)
        }
    }

    private fun writeApk(folder: File, name: String, packageName: String) {
        ZipOutputStream(File(folder, name).outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write("""<manifest package="$packageName"/>""".toByteArray())
            zip.closeEntry()
        }
    }

    private fun inTempDirectory(block: (File) -> Unit) {
        val folder = Files.createTempDirectory("nfvr-game-folder-test-").toFile()
        try {
            block(folder)
        } finally {
            folder.deleteRecursively()
        }
    }
}