import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModPackageAnalyzerTest {
    private val analyzer = ModPackageAnalyzer()
    private val bonelab = InstalledQuestApp(
        "com.StressLevelZero.BONELAB",
        "1.2974.57485",
        2974L,
        apkSha256 = "02adecc4af7354296205b4c2dbb50fba4132628aa0f0186ea9a7cf7419f670b1"
    )

    @Test
    fun recognizesQmodAndBuildsVettedPlan() {
        val zip = zipOf(
            "mod.json" to """
                {"name":"Tiny mod","id":"tiny","author":"NFVR","version":"1.0",
                 "packageId":"com.StressLevelZero.BONELAB","modFiles":["lib/tiny.dat"]}
            """.trimIndent(),
            "lib/tiny.dat" to "binary"
        )
        val analysis = analyzer.analyze(
            zip,
            bonelab,
            ModLoaderDetection(
                bonelab.packageName,
                mapOf(
                    ModLoaderKind.QUEST_LOADER to ModLoaderEvidence(
                        ModLoaderKind.QUEST_LOADER,
                        ModLoaderStatus.DETECTED
                    )
                )
            )
        )
        assertEquals(ModPackageType.QMOD, analysis.packageType)
        assertTrue(analysis.installable)
        assertEquals(1, analysis.installPlan.mappings.size)
        assertTrue(analysis.installPlan.mappings.single().destinationPath.endsWith("/mods/tiny.dat"))
        zip.delete()
    }

    @Test
    fun rejectsQmodTargetMismatchBeforePlanningInstall() {
        val zip = zipOf(
            "mod.json" to """{"packageId":"com.StressLevelZero.BONELAB","modFiles":["a.so"]}""",
            "a.so" to "x"
        )
        val analysis = analyzer.analyze(zip, InstalledQuestApp("com.example.other", "1.0"))
        assertFalse(analysis.installable)
        assertTrue(analysis.installPlan.preconditions.any { it.code == "TARGET_PACKAGE_MISMATCH" })
        zip.delete()
    }

    @Test
    fun rejectsUnknownAndKnownUnimplementedQmodFields() {
        val zip = zipOf(
            "mod.json" to """
                {"packageId":"com.StressLevelZero.BONELAB","modFiles":["a.so"],
                 "packageVersion":"1.0","actions":[],"unexpected":true}
            """.trimIndent(),
            "a.so" to "x"
        )
        val analysis = analyzer.analyze(zip, bonelab)
        assertFalse(analysis.installable)
        assertTrue(analysis.installPlan.preconditions.any { it.code == "UNKNOWN_QMOD_FIELD" })
        assertTrue(analysis.installPlan.preconditions.any { it.code == "UNSUPPORTED_QMOD_FIELD" })
        zip.delete()
    }

    @Test
    fun rejectsEveryCombinationOfRootManifestCandidates() {
        val zip = zipOf(
            "package.json" to """{"pcFileName":"pc","androidFileName":"android"}""",
            "qmod.json" to """{"name":"not-a-qmod"}"""
        )
        val analysis = analyzer.analyze(zip, bonelab)
        assertFalse(analysis.recognized)
        assertTrue(analysis.message.contains("multiple", ignoreCase = true))
        zip.delete()
    }

    @Test
    fun recognizesVirtualStumpWithoutInventingDestination() {
        val zip = zipOf(
            "package.json" to """
                {"pcFileName":"map.bundle","androidFileName":"map.android",
                 "customMapSupportVersion":"1","initialScenes":["Scene"],
                 "availableGameModes":["infection"]}
            """.trimIndent(),
            "map.android" to "asset"
        )
        val analysis = analyzer.analyze(zip, bonelab)
        assertEquals(ModPackageType.GORILLA_TAG_VIRTUAL_STUMP, analysis.packageType)
        assertFalse(analysis.installable)
        assertEquals(null, analysis.installPlan.destinationRoot)
        assertTrue(analysis.installPlan.preconditions.any { it.code == "MOD_IO_MANAGED" })
        zip.delete()
    }

    @Test
    fun parsesStrictNfvrManifestAndChecksHashShapeAndDestination() {
        val zip = zipOf(
            "nfvr-mod.json" to """
                {"schemaVersion":1,"name":"Controlled","version":"1",
                 "targetPackageId":"com.StressLevelZero.BONELAB",
                 "files":[{"source":"payload/mod.dat","destination":"controlled/mod.dat",
                 "sha256":"239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5"}]}
            """.trimIndent(),
            "payload/mod.dat" to "payload"
        )
        val analysis = analyzer.analyze(zip, bonelab)
        assertEquals(ModPackageType.NFVR_MANIFEST, analysis.packageType)
        assertTrue(analysis.installable)
        assertEquals(
            "/sdcard/Android/data/com.StressLevelZero.BONELAB/files/Mods/controlled/mod.dat",
            analysis.installPlan.mappings.single().destinationPath
        )
        zip.delete()
    }

    @Test
    fun rejectsUnsafeManifestDestinationAndZipTraversal() {
        val unsafeDestination = zipOf(
            "nfvr-mod.json" to """
                {"schemaVersion":1,"targetPackageId":"com.StressLevelZero.BONELAB",
                 "files":[{"source":"payload.dat","destination":"../payload.dat"}]}
            """.trimIndent(),
            "payload.dat" to "payload"
        )
        val destinationAnalysis = analyzer.analyze(unsafeDestination, bonelab)
        assertFalse(destinationAnalysis.installable)
        assertTrue(destinationAnalysis.installPlan.preconditions.any { it.code == "UNSAFE_PATH" })
        unsafeDestination.delete()

        val traversal = zipOf("../escaped.so" to "blocked")
        val traversalAnalysis = analyzer.analyze(traversal, bonelab)
        assertFalse(traversalAnalysis.recognized)
        traversal.delete()
    }

    @Test
    fun exposesMeasurableProgress() {
        val plan = ModInstallPlan(
            installable = true,
            mappings = listOf(
                ModFileMapping("a", "/sdcard/a", sizeBytes = 10),
                ModFileMapping("b", "/sdcard/b", sizeBytes = 30)
            )
        )
        val progress = plan.progress(20, 1)
        assertEquals(0.5, progress.fraction)
        assertEquals(50, progress.percent)
    }

    @Test
    fun computesMissingHashesAndRejectsDangerousSemantics() {
        val zip = zipOf(
            "mod.json" to """
                {"packageId":"com.StressLevelZero.BONELAB","modFiles":["payload.dat"],
                 "commands":["rm -rf /"]}
            """.trimIndent(),
            "payload.dat" to "payload",
            "install.ps1" to "Write-Host unsafe"
        )
        val analysis = analyzer.analyze(zip, bonelab)
        assertFalse(analysis.installable)
        assertTrue(analysis.installPlan.preconditions.any {
            it.code == "UNKNOWN_QMOD_FIELD" || it.code == "UNSUPPORTED_QMOD_FIELD"
        })
        assertTrue(analysis.installPlan.preconditions.any { it.code == "DANGEROUS_PAYLOAD" })
        zip.delete()

        val safe = zipOf(
            "nfvr-mod.json" to """
                {"schemaVersion":1,"targetPackageId":"com.StressLevelZero.BONELAB",
                 "files":[{"source":"payload.dat","destination":"payload.dat"}]}
            """.trimIndent(),
            "payload.dat" to "payload"
        )
        val safeAnalysis = analyzer.analyze(safe, bonelab)
        assertTrue(safeAnalysis.installable)
        assertEquals(
            "239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5",
            safeAnalysis.installPlan.mappings.single().sha256
        )
        safe.delete()
    }

    @Test
    fun rejectsCaseFoldedDuplicateMappings() {
        val zip = zipOf(
            "nfvr-mod.json" to """
                {"schemaVersion":1,"targetPackageId":"com.StressLevelZero.BONELAB",
                 "files":[
                   {"source":"a.so","destination":"same.so"},
                   {"source":"b.so","destination":"SAME.so"}]}
            """.trimIndent(),
            "a.so" to "one",
            "b.so" to "two"
        )
        val analysis = analyzer.analyze(zip, bonelab)
        assertFalse(analysis.installable)
        assertTrue(analysis.installPlan.preconditions.any {
            it.code == "DUPLICATE_MAPPING" || it.code == "CONFLICTING_MAPPING"
        })
        zip.delete()
    }

    @Test
    fun archiveIdentityChangesAfterMutation() {
        val zip = zipOf(
            "nfvr-mod.json" to """
                {"schemaVersion":1,"targetPackageId":"com.StressLevelZero.BONELAB",
                 "files":[{"source":"payload.dat","destination":"payload.dat"}]}
            """.trimIndent(),
            "payload.dat" to "payload"
        )
        val before = analyzer.analyze(zip, bonelab)
        assertTrue(before.installable)
        val identity = before.installPlan.archiveIdentity
        zip.appendText("mutation")
        val after = analyzer.analyze(zip, bonelab)
        assertTrue(identity != after.installPlan.archiveIdentity)
        zip.delete()
    }

    @Test
    fun executorRejectsArchiveMutationBeforeAnyPush() {
        runBlocking {
            val zip = zipOf(
                "nfvr-mod.json" to """
                    {"schemaVersion":1,"targetPackageId":"com.StressLevelZero.BONELAB",
                     "files":[{"source":"payload.dat","destination":"payload.dat"}]}
                """.trimIndent(),
                "payload.dat" to "payload"
            )
            val analysis = analyzer.analyze(zip, bonelab)
            assertTrue(analysis.installable)
            zip.appendText("changed-after-review")
            val adb = FakeAdb()
            val result = ModsManager(adb, apkEvidenceRefresher = testApkEvidence()).executeInstallPlan(
                "SERIAL",
                zip,
                analysis.installPlan.bindToDevice("SERIAL")
            )
            assertFalse(result.success)
            assertTrue(result.message.contains("تغير") || result.message.contains("تحليل"))
            assertEquals(0, adb.pushCalls)
            assertTrue(zip.delete())
        }
    }

    @Test
    fun executorReportsRemoteVerificationFailure() {
        runBlocking {
            val zip = zipOf(
                "nfvr-mod.json" to """
                    {"schemaVersion":1,"targetPackageId":"com.StressLevelZero.BONELAB",
                     "files":[{"source":"payload.dat","destination":"payload.dat"}]}
                """.trimIndent(),
                "payload.dat" to "payload"
            )
            val analysis = analyzer.analyze(zip, bonelab)
            assertTrue(analysis.installable)
            val adb = FakeAdb()
            val result = ModsManager(adb, apkEvidenceRefresher = testApkEvidence()).executeInstallPlan(
                "SERIAL",
                zip,
                analysis.installPlan.bindToDevice("SERIAL")
            )
            assertFalse(result.success)
            assertTrue(result.message.contains("التحقق"))
            assertEquals(1, adb.pushCalls)
            assertTrue(zip.delete())
        }
    }

    @Test
    fun executorRejectsUnboundPlanBeforeDeviceScanOrWrite() {
        runBlocking {
            val zip = zipOf(
                "nfvr-mod.json" to """
                    {"schemaVersion":1,"targetPackageId":"com.StressLevelZero.BONELAB",
                     "files":[{"source":"payload.dat","destination":"payload.dat"}]}
                """.trimIndent(),
                "payload.dat" to "payload"
            )
            val analysis = analyzer.analyze(zip, bonelab)
            val adb = FakeAdb()

            val result = ModsManager(
                adb,
                apkEvidenceRefresher = testApkEvidence()
            ).executeInstallPlan("SERIAL", zip, analysis.installPlan)

            assertFalse(result.success)
            assertTrue(result.message.contains("مرتبطة") || result.message.contains("ربط"))
            assertEquals(0, adb.pushCalls)
            zip.delete()
        }
    }

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("nfvr-mod-test-", ".zip")
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
        return file
    }

    private fun testApkEvidence() = QuestApkEvidenceRefresher { _, app, _, _ ->
        app.copy(
            apkSha256 = GameModProfileRegistry.findByPackageId(app.packageName)?.apkSha256
        )
    }

    private class FakeAdb : AdbClient(BundledAdb(HostOs.LINUX)) {
        var pushCalls = 0

        override fun pullReadOnly(serial: String, remotePath: String, localFile: File): CmdResult =
            CmdResult(1, "", "test fake has no APK artifact")

        override fun shell(serial: String, vararg args: String): CmdResult =
            when (args.firstOrNull()) {
                "pm" -> CmdResult(
                    0,
                    "package:/data/app/com.StressLevelZero.BONELAB/base.apk=com.StressLevelZero.BONELAB\n",
                    ""
                )
                "dumpsys" -> CmdResult(0, "versionName=1.2974.57485 versionCode=2974\n", "")
                else -> CmdResult(0, "", "")
            }

        override fun pushWithProgress(
            serial: String,
            from: File,
            toDevicePath: String,
            onProgress: (Long, Long) -> Unit
        ): CmdResult {
            pushCalls++
            return CmdResult(0, "ok", "")
        }

        override fun pushModFileWithProgress(
            serial: String,
            from: File,
            toDevicePath: String,
            onProgress: (Long, Long) -> Unit,
            cancelled: () -> Boolean
        ): CmdResult {
            pushCalls++
            onProgress(0L, from.length())
            onProgress(from.length(), from.length())
            return CmdResult(0, "ok", "")
        }
    }
}