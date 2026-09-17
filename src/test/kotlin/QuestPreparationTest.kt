import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class QuestPreparationTest {
    private class FakeAdb(
        private val packageId: String,
        private val serial: String,
        var files: LinkedHashMap<String, ByteArray>
    ) : AdbClient(BundledAdb(HostOs.LINUX)) {
        var pullCount = 0
        var mutateFirstBackupBasePull = false
        override fun devices(): CmdResult =
            CmdResult(0, "List of devices attached\n$serial\tdevice model:Quest\n", "")

        override fun shell(serial: String, vararg args: String): CmdResult {
            if (serial != this.serial) return CmdResult(1, "", "wrong serial")
            return when (args.toList()) {
                listOf("dumpsys", "package", packageId) ->
                    CmdResult(0, "versionName=1.0.0\nversionCode=1\n", "")
                listOf("pm", "path", packageId) ->
                    CmdResult(0, files.keys.joinToString("\n") { "package:$it" }, "")
                else -> {
                    val path = args.lastOrNull()
                    if (args.take(3) == listOf("stat", "-c", "%s") && path != null) {
                        CmdResult(0, "${files[path]?.size ?: -1}\n", "")
                    } else CmdResult(1, "", "unsupported fake command")
                }
            }
        }

        override fun pullReadOnly(serial: String, remotePath: String, localFile: File): CmdResult {
            pullCount++
            val payload = files[remotePath] ?: return CmdResult(1, "", "missing")
            val delivered = if (mutateFirstBackupBasePull &&
                pullCount == 5 &&
                remotePath.endsWith("/base.apk")
            ) {
                byteArrayOf(99, 98, 97)
            } else payload
            localFile.writeBytes(delivered)
            return CmdResult(0, "1 file pulled\n", "")
        }
    }

    private fun app() = InstalledQuestApp(
        packageName = "com.example.game",
        versionName = "1.0.0",
        versionCode = 1,
        apkPath = "/data/app/com.example.game-abc/base.apk"
    )

    private fun fakeFiles(): LinkedHashMap<String, ByteArray> = linkedMapOf(
        "/data/app/com.example.game-abc/base.apk" to byteArrayOf(1, 2, 3),
        "/data/app/com.example.game-abc/split_config.arm64_v8a.apk" to byteArrayOf(4, 5, 6)
    )

    private fun engineWithFake(fake: FakeAdb) = QuestPreparationEngine(fake)

    @Test
    fun `unknown package and version fail closed with scoped blocker`() {
        val app = InstalledQuestApp(
            packageName = "com.example.game",
            versionName = "9.9.9",
            versionCode = 999
        )
        val inventory = QuestApkInventory(
            app.packageName,
            listOf(QuestApkArtifact("/data/app/com.example/base.apk", null, 10))
        )
        val result = DataDrivenQuestGamePreparationStrategy().assess(app, inventory, null)
        assertFalse(result.readyForModInstall)
        assertTrue(result.blockers.any {
            it.code == "UNSUPPORTED_GAME_VERSION" && it.scope == "profile-registry"
        })
    }

    @Test
    fun `pm path parser requires one package-scoped base and rejects traversal`() {
        val output = """
            package:/data/app/com.example.game-abc/base.apk
            package:/data/app/com.example.game-abc/split_config.arm64_v8a.apk
        """.trimIndent()
        val paths = QuestPreparationEngine.parseInstalledApkPaths(output, "com.example.game")
        assertEquals(2, paths.size)
        assertFailsWith<IllegalArgumentException> {
            QuestPreparationEngine.parseInstalledApkPaths(
                "package:/data/app/com.example.game-abc/../base.apk",
                "com.example.game"
            )
        }
        assertFailsWith<IllegalArgumentException> {
            QuestPreparationEngine.parseInstalledApkPaths(
                "package:/data/app/other-abc/base.apk",
                "com.example.game"
            )
        }
    }

    @Test
    fun `zip inspection records abi and il2cpp unity evidence without modifying archive`() {
        val file = Files.createTempFile("quest-prep-", ".apk").toFile()
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { zip ->
            fun add(name: String, payload: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(payload)
                zip.closeEntry()
            }
            val elf = ByteArray(20).also {
                it[0] = 0x7f
                it[1] = 'E'.code.toByte()
                it[2] = 'L'.code.toByte()
                it[3] = 'F'.code.toByte()
                it[4] = 2 // ELF64
                it[5] = 1 // little-endian
                it[6] = 1 // current ELF version
                it[18] = 0xb7.toByte() // EM_AARCH64
            }
            add("lib/arm64-v8a/libil2cpp.so", elf)
            add("lib/arm64-v8a/libunity.so", elf)
            add("assets/data.bin", byteArrayOf(1, 2, 3))
        }
        val before = sha256File(file)
        val inspection = QuestPreparationEngine.inspectZip(file)
        assertTrue(inspection.valid)
        assertEquals(setOf("arm64-v8a"), inspection.abiDirectories)
        assertTrue(inspection.il2cppEvidence)
        assertTrue(inspection.unityEvidence)
        assertEquals(before, sha256File(file))
    }

    @Test
    fun `zip traversal and absolute paths are blocked`() {
        assertFailsWith<IllegalArgumentException> {
            QuestPreparationEngine.normalizeZipPath("../lib/arm64-v8a/libx.so")
        }
        assertFailsWith<IllegalArgumentException> {
            QuestPreparationEngine.normalizeZipPath("/lib/arm64-v8a/libx.so")
        }
        assertFailsWith<IllegalArgumentException> {
            QuestPreparationEngine.normalizeZipPath("C:/temp/payload.so")
        }
    }

    @Test
    fun `malformed ELF magic is rejected even with an otherwise plausible ABI`() {
        val file = Files.createTempFile("quest-malformed-elf-", ".apk").toFile()
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libil2cpp.so"))
            zip.write(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'X'.code.toByte()) +
                ByteArray(16) { 0 })
            zip.closeEntry()
        }
        val inspection = QuestPreparationEngine.inspectZip(file)
        assertFalse(inspection.valid)
        assertFalse(inspection.elfAbiValid)
    }

    @Test
    fun `ZIP evidence rejects an artifact with the wrong source hash`() {
        val file = Files.createTempFile("quest-source-evidence-", ".apk").toFile()
        file.deleteOnExit()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("assets/data.bin"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        val sourceHash = sha256File(file)
        val artifact = QuestApkArtifact(
            remotePath = "/data/app/com.example.game-abc/base.apk",
            splitName = null,
            sizeBytes = file.length(),
            remoteSha256 = sourceHash,
            sha256 = "0".repeat(64),
            localPath = file.absolutePath
        )
        assertFailsWith<IllegalArgumentException> {
            engineWithFake(FakeAdb(app().packageName, "SOURCE-EVIDENCE", fakeFiles())).inspectLocalApk(
                file,
                artifact,
                emptySet()
            )
        }
    }

    @Test
    fun `stale version does not match exact preparation profile`() {
        val profile = QuestPreparationProfile(
            profileId = "test-profile",
            packageId = "com.example.game",
            engine = "Unity IL2CPP",
            allowedVersions = setOf("1.0.0"),
            allowedVersionCodes = setOf(1),
            requiredResourceHashes = mapOf("assets/resource.bin" to "a".repeat(64)),
            allowedAbis = setOf("arm64-v8a"),
            evidenceScope = "test-only",
            evidenceRank = "AUTHORITATIVE_UPSTREAM"
        )
        val stale = InstalledQuestApp("com.example.game", versionName = "1.0.1")
        assertFalse(profile.matches(stale))
        val current = stale.copy(versionName = "1.0.0", versionCode = 1)
        assertTrue(profile.matches(current))
    }

    @Test
    fun `profile rejects cross-product version and version-code sets`() {
        val profile = QuestPreparationProfile(
            profileId = "cross-product",
            packageId = "com.example.game",
            engine = "Unity IL2CPP",
            allowedVersions = setOf("1.0.0", "1.0.1"),
            allowedVersionCodes = setOf(1, 2),
            requiredResourceHashes = mapOf("assets/resource.bin" to "a".repeat(64)),
            allowedAbis = setOf("arm64-v8a"),
            evidenceScope = "test-only",
            evidenceRank = "AUTHORITATIVE_UPSTREAM"
        )
        assertFalse(profile.validForReadiness)
        assertFalse(profile.matches(InstalledQuestApp("com.example.game", "1.0.0", 2)))
    }

    @Test
    fun `eight stages and backup never imply rollback capability`() {
        assertEquals(8, QuestPreparationStage.entries.size)
        val metadata = QuestRollbackMetadata(
            packageId = "com.example.game",
            serialFingerprint = "serial-hash",
            appVersion = "1.0.0",
            appVersionCode = 1,
            originalArtifacts = listOf(
                QuestBackupFile(
                    "/data/app/com.example.game-abc/base.apk",
                    "base.apk",
                    1,
                    "0".repeat(64),
                    "1".repeat(64)
                )
            ),
            createdAt = java.time.Instant.EPOCH
        )
        assertFalse(metadata.rollbackAvailable)
        val report = QuestPreparationReport(
            app = InstalledQuestApp("com.example.game", versionName = "1.0.0"),
            assessment = QuestPreparationAssessment(true),
            backup = QuestBackupStatus(true, true, metadata = metadata)
        )
        assertFalse(report.readyForModInstall)
        assertTrue(report.backup.integrityVerified)
        assertFalse(report.backup.metadata!!.rollbackAvailable)
        assertEquals(8, questPreparationStageRows(null).size)
    }

    @Test
    fun `backup rejects forged inventory path inside backup boundary`() {
        val serial = "SERIAL-1"
        val fake = FakeAdb(app().packageName, serial, fakeFiles())
        val inventory = QuestApkInventory(
            app().packageName,
            listOf(
                QuestApkArtifact(
                    "/sdcard/private/not-the-installed-apk",
                    null,
                    3,
                    "0".repeat(64)
                )
            ),
            serial = serial,
            selectedBasePath = app().apkPath
        )
        assertFailsWith<IllegalArgumentException> {
            engineWithFake(fake).createApkBackup(serial, app(), inventory)
        }
        assertEquals(0, fake.pullCount)
    }

    @Test
    fun `backup binds remote hashes and rejects same-version replacement`() {
        val serial = "SERIAL-2"
        val fake = FakeAdb(app().packageName, serial, fakeFiles())
        val engine = engineWithFake(fake)
        val inventory = engine.inspectInstalledApks(serial, app())
        val oldPullCount = fake.pullCount
        fake.files["/data/app/com.example.game-abc/base.apk"] = byteArrayOf(9, 9, 9)
        assertFailsWith<IllegalArgumentException> {
            engine.createApkBackup(serial, app(), inventory)
        }
        assertTrue(fake.pullCount > oldPullCount)
    }

    @Test
    fun `backup rejects bytes changed only in the backup pull`() {
        val serial = "SERIAL-BACKUP-PULL"
        val fake = FakeAdb(app().packageName, serial, fakeFiles())
        val engine = engineWithFake(fake)
        val inventory = engine.inspectInstalledApks(serial, app())
        fake.mutateFirstBackupBasePull = true
        assertFailsWith<IllegalArgumentException> {
            engine.createApkBackup(serial, app(), inventory)
        }
    }

    @Test
    fun `backup manifest rejects traversal duplicate and rollback claim`() {
        val serial = "SERIAL-3"
        val fake = FakeAdb(app().packageName, serial, fakeFiles())
        val engine = engineWithFake(fake)
        val inventory = engine.inspectInstalledApks(serial, app())
        val status = engine.createApkBackup(serial, app(), inventory)
        assertTrue(status.integrityVerified)
        val directory = assertNotNull(status.directory)
        val savedBase = File(directory, "base.apk")
        savedBase.setWritable(true)
        savedBase.writeBytes(byteArrayOf(8, 8, 8))
        assertFalse(engine.latestBackup(serial, app(), inventory).available)
        // Restore the manifest test fixture with a fresh backup so each
        // tampering assertion exercises the metadata parser independently.
        val fresh = engine.createApkBackup(serial, app(), inventory)
        val freshDirectory = assertNotNull(fresh.directory)
        val manifest = File(freshDirectory, "rollback-metadata.json")
        manifest.setWritable(true)
        val original = manifest.readText()
        manifest.writeText(
            original.replace("\"localPath\": \"base.apk\"", "\"localPath\": \"../outside.apk\"")
        )
        assertFalse(engine.latestBackup(serial, app(), inventory).available)
        manifest.writeText(
            original.replace("\"rollbackAvailable\": false", "\"rollbackAvailable\": true")
        )
        assertFalse(engine.latestBackup(serial, app(), inventory).available)
    }

    @Test
    fun `reordered split inventory still binds by remote path and hash`() {
        val serial = "SERIAL-4"
        val fake = FakeAdb(app().packageName, serial, fakeFiles())
        val engine = engineWithFake(fake)
        val inventory = engine.inspectInstalledApks(serial, app())
        val status = engine.createApkBackup(serial, app(), inventory)
        assertTrue(status.integrityVerified)
        val reordered = inventory.copy(artifacts = inventory.artifacts.reversed())
        assertTrue(engine.latestBackup(serial, app(), reordered).integrityVerified)
    }
}