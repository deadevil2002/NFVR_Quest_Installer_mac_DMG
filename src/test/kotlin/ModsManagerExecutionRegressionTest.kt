import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private typealias ModExecutionProgress = ModsManager.ModExecutionProgress
private typealias ModInstallPhase = ModsManager.ModInstallPhase

/**
 * End-to-end execution regressions for the reviewed content profiles.  The
 * fake below is an ADB transport only: archive classification, extraction,
 * binding, transfer orchestration, and verification all remain production
 * code in ModPackageAnalyzer and ModsManager.
 */
class ModsManagerExecutionRegressionTest {
    private val bonelab = InstalledQuestApp(
        ModPackageAnalyzer.BONELAB_PACKAGE_ID,
        "1.2974.57485",
        2974L,
        "BONELAB",
        apkSha256 = "02adecc4af7354296205b4c2dbb50fba4132628aa0f0186ea9a7cf7419f670b1"
    )
    private val nomad = InstalledQuestApp(
        ModPackageAnalyzer.NOMAD_PACKAGE_ID,
        "1.0.7",
        682L,
        "Blade & Sorcery: Nomad",
        apkSha256 = "48e49e3d88590ae572df1885f67f4a5f2d9d8397dae16475c25b4d471e6e2324"
    )

    @Test
    fun adbPushProgressParserConvertsObservedPercentToBytes() {
        assertEquals(250L, adbPushProgressBytes("\r25%", 1_000L))
        assertEquals(750L, adbPushProgressBytes("75%", 1_000L, 250L))
    }

    @Test
    fun modsPushCommandMatchesBundledWindowsAdbContract() {
        val binary = File("src/main/resources/adb/win/adb.exe").readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(binary.contains("push [--sync]"))
        assertEquals(
            listOf("adb.exe", "-s", "quest", "push", "C:\\Mods\\item.bin", "/sdcard/Mods/item.bin"),
            questModPushCommand("adb.exe", "quest", "C:\\Mods\\item.bin", "/sdcard/Mods/item.bin")
        )
        assertEquals(500L, adbPushProgressBytes("[ 50%] /sdcard/Mods/item.bin\r", 1_000L))
    }

    @Test
    fun bonelabReviewedContentTransfersWithRealProgressAndVerifiesEveryFile() =
        runBlocking {
            val archive = bonelabFixture()
            try {
                val adb = DeviceAdb(bonelab)
                val manager = manager(adb, bonelab)
                val analysis = manager.analyzeModPackage(archive, bonelab)
                assertTrue(analysis.installable, analysis.message)
                val plan = analysis.installPlan.bindToDevice("SERIAL")
                adb.expectedSizes = plan.mappings.associate { it.destinationPath to it.sizeBytes }

                val progress = mutableListOf<ModExecutionProgress>()
                val result = manager.executeInstallPlan(
                    "SERIAL",
                    archive,
                    plan,
                    progress::add
                )

                assertTrue(result.success, result.message)
                assertEquals(plan.mappings.map { it.destinationPath }.toSet(), adb.verifiedFiles)
                assertTrue(adb.rootVerified)
                assertTrue(adb.mkdirCalls > 0)
                assertEquals(plan.mappings.size, adb.pushCalls)
                assertTrue(progress.any { it.phase == ModInstallPhase.TRANSFERRING })
                assertTrue(progress.any {
                    it.phase == ModInstallPhase.TRANSFERRING &&
                        (it.fraction ?: 0.0) > 0.0 &&
                        (it.fraction ?: 1.0) < 1.0
                })
                assertTrue(progress.any { it.phase == ModInstallPhase.VERIFYING })
                assertEquals(ModInstallPhase.COMPLETED, progress.last().phase)
                assertFalse(result.message.contains("بصمة APK"))
            } finally {
                archive.delete()
            }
        }

    @Test
    fun nomadReviewedContentTransfersAndVerifiesDirectoryAndAllFiles() =
        runBlocking {
            val archive = nomadFixture()
            try {
                val adb = DeviceAdb(nomad)
                val manager = manager(adb, nomad)
                val analysis = manager.analyzeModPackage(archive, nomad)
                assertTrue(analysis.installable, analysis.message)
                val plan = analysis.installPlan.bindToDevice("SERIAL")
                adb.expectedSizes = plan.mappings.associate { it.destinationPath to it.sizeBytes }

                val progress = mutableListOf<ModExecutionProgress>()
                val result = manager.executeInstallPlan(
                    "SERIAL",
                    archive,
                    plan,
                    progress::add
                )

                assertTrue(result.success, result.message)
                assertEquals(plan.mappings.map { it.destinationPath }.toSet(), adb.verifiedFiles)
                assertTrue(adb.rootVerified)
                assertEquals(plan.mappings.size, adb.pushCalls)
                assertTrue(progress.zipWithNext().all { (before, after) ->
                    (before.fraction ?: 0.0) <= (after.fraction ?: 0.0)
                })
            } finally {
                archive.delete()
            }
        }

    @Test
    fun missingRemoteFileCannotReportSuccess() = runBlocking {
        val archive = bonelabFixture()
        try {
            val adb = DeviceAdb(bonelab).apply { missingFile = true }
            val manager = manager(adb, bonelab)
            val plan = manager.analyzeModPackage(archive, bonelab).installPlan
                .bindToDevice("SERIAL")
            adb.expectedSizes = plan.mappings.associate { it.destinationPath to it.sizeBytes }

            val result = manager.executeInstallPlan("SERIAL", archive, plan) {
                if (it.phase == ModInstallPhase.COMPLETED) adb.completedReported = true
            }

            assertFalse(result.success)
            assertEquals(plan.mappings.size, adb.pushCalls)
            assertFalse(adb.completedReported)
        } finally {
            archive.delete()
        }
    }

    @Test
    fun wrongRemoteSizeCannotReportSuccess() = runBlocking {
        val archive = nomadFixture()
        try {
            val adb = DeviceAdb(nomad).apply { wrongSize = true }
            val manager = manager(adb, nomad)
            val plan = manager.analyzeModPackage(archive, nomad).installPlan
                .bindToDevice("SERIAL")
            adb.expectedSizes = plan.mappings.associate { it.destinationPath to it.sizeBytes }

            val result = manager.executeInstallPlan("SERIAL", archive, plan) {
                if (it.phase == ModInstallPhase.COMPLETED) adb.completedReported = true
            }

            assertFalse(result.success)
            assertEquals(plan.mappings.size, adb.pushCalls)
            assertFalse(adb.completedReported)
        } finally {
            archive.delete()
        }
    }

    @Test
    fun collisionStopsBeforeMkdirOrPush() = runBlocking {
        val archive = bonelabFixture()
        try {
            val adb = DeviceAdb(bonelab).apply { collision = true }
            val manager = manager(adb, bonelab)
            val plan = manager.analyzeModPackage(archive, bonelab).installPlan
                .bindToDevice("SERIAL")
            adb.expectedSizes = plan.mappings.associate { it.destinationPath to it.sizeBytes }

            val result = manager.executeInstallPlan("SERIAL", archive, plan)

            assertFalse(result.success)
            assertEquals(0, adb.mkdirCalls)
            assertEquals(0, adb.pushCalls)
            assertTrue(adb.collisionChecks > 0)
        } finally {
            archive.delete()
        }
    }

    @Test
    fun staleVersionStopsBeforeAnyTransfer() = runBlocking {
        val archive = nomadFixture()
        try {
            val adb = DeviceAdb(nomad).apply { deviceVersionName = "1.0.6" }
            val manager = manager(adb, nomad)
            val plan = manager.analyzeModPackage(archive, nomad).installPlan
                .bindToDevice("SERIAL")

            val result = manager.executeInstallPlan("SERIAL", archive, plan)

            assertFalse(result.success)
            assertEquals(0, adb.pushCalls)
            assertEquals(0, adb.mkdirCalls)
        } finally {
            archive.delete()
        }
    }

    @Test
    fun staleDeviceBindingStopsBeforeDeviceScanOrTransfer() = runBlocking {
        val archive = bonelabFixture()
        try {
            val adb = DeviceAdb(bonelab)
            val manager = manager(adb, bonelab)
            val plan = manager.analyzeModPackage(archive, bonelab).installPlan
                .bindToDevice("OTHER-SERIAL")

            val result = manager.executeInstallPlan("SERIAL", archive, plan)

            assertFalse(result.success)
            assertEquals(0, adb.scanCalls)
            assertEquals(0, adb.pushCalls)
        } finally {
            archive.delete()
        }
    }

    @Test
    fun staleArchiveIdentityStopsBeforeTransfer() = runBlocking {
        val archive = nomadFixture()
        try {
            val adb = DeviceAdb(nomad)
            val manager = manager(adb, nomad)
            val plan = manager.analyzeModPackage(archive, nomad).installPlan
                .bindToDevice("SERIAL")
            archive.appendText("mutation after review")

            val result = manager.executeInstallPlan("SERIAL", archive, plan)

            assertFalse(result.success)
            assertEquals(0, adb.pushCalls)
            assertEquals(0, adb.mkdirCalls)
        } finally {
            archive.delete()
        }
    }

    @Test
    fun ordinaryContentDoesNotRequireMatchingApkHash() = runBlocking {
        val archive = bonelabFixture()
        try {
            val adb = DeviceAdb(bonelab)
            val manager = manager(adb, bonelab, "00".repeat(32))
            val plan = manager.analyzeModPackage(archive, bonelab).installPlan
                .bindToDevice("SERIAL")
            adb.expectedSizes = plan.mappings.associate { it.destinationPath to it.sizeBytes }

            val result = manager.executeInstallPlan("SERIAL", archive, plan)

            assertTrue(
                result.success,
                "${result.message} verified=${adb.verifiedFiles} expected=${adb.expectedSizes.keys} pushes=${adb.pushCalls}"
            )
            assertTrue(adb.pushCalls > 0)
            assertTrue(adb.mkdirCalls > 0)
        } finally {
            archive.delete()
        }
    }

    private fun manager(
        adb: DeviceAdb,
        app: InstalledQuestApp,
        evidenceHash: String? = GameModProfileRegistry.findByPackageId(app.packageName)?.apkSha256
    ): ModsManager =
        ModsManager(
            adb,
            StaticModLoaderDetector(ModLoaderDetection(app.packageName, emptyMap())),
            NoOpApkModLoaderPatcher,
            QuestApkEvidenceRefresher { _, selected, _, _ ->
                selected.copy(apkSha256 = evidenceHash)
            }
        )

    private fun bonelabFixture(): File = zipOf(
        "AvatarPallet/manifest.json" to
            """{"name":"Avatar Pallet","packageId":"${bonelab.packageName}","gameVersion":"${bonelab.versionName}","version":"1.0.0"}""",
        "AvatarPallet/pallet.json" to
            """{"name":"Avatar Pallet","version":"1.0.0","author":"NFVR"}""",
        "AvatarPallet/Android/avatar.assetbundle" to "bundle-bytes"
    )

    private fun nomadFixture(): File = zipOf(
        "NomadMod/manifest.json" to
            """{"Name":"Weapons","Author":"Fixture Author","Description":"Nomad content weapons","GameVersion":"${nomad.versionName}","packageId":"${nomad.packageName}","versionName":"${nomad.versionName}","ModVersion":"2.0"}""",
        "NomadMod/module.json" to
            """{"name":"Weapons","id":"weapons","version":"2.0"}""",
        "NomadMod/catalog.json" to
            """{"module":"weapons","bundles":["weapons.assetbundle"]}""",
        "NomadMod/weapons.assetbundle" to "nomad-bundle-bytes"
    )

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("nfvr-manager-execution-", ".zip")
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
        return file
    }

    private class DeviceAdb(
        private val app: InstalledQuestApp
    ) : AdbClient(BundledAdb(HostOs.LINUX)) {
        var deviceVersionName: String? = app.versionName
        var deviceVersionCode: Long? = app.versionCode
        var expectedSizes: Map<String, Long> = emptyMap()
        var missingFile = false
        var wrongSize = false
        var collision = false
        var rootVerified = false
        var completedReported = false
        var mkdirCalls = 0
        var pushCalls = 0
        var scanCalls = 0
        var collisionChecks = 0
        val verifiedFiles = linkedSetOf<String>()
        private val modRoot =
            "/sdcard/Android/data/${app.packageName}/files/Mods"
        private val existingDirectories = linkedSetOf(modRoot)

        override fun shell(serial: String, vararg args: String): CmdResult {
            val command = args.toList()
            return when {
                command == listOf("pm", "list", "packages", "-3", "-f") -> {
                    scanCalls++
                    CmdResult(
                        0,
                        "package:/data/app/${app.packageName}/base.apk=${app.packageName}\n",
                        ""
                    )
                }
                command == listOf("dumpsys", "package", app.packageName) ->
                    CmdResult(
                        0,
                        "versionName=${deviceVersionName ?: "null"} versionCode=${deviceVersionCode ?: "null"}\n",
                        ""
                    )
                command.firstOrNull() == "test" && command.getOrNull(1) == "!" -> {
                    collisionChecks++
                    CmdResult(if (collision) 1 else 0, "", "")
                }
                command.firstOrNull() == "test" && command.getOrNull(1) == "-d" -> {
                    rootVerified = true
                    CmdResult(
                        if (existingDirectories.contains(shellUnquoteRemotePath(command.getOrNull(2).orEmpty()))) 0 else 1,
                        "",
                        ""
                    )
                }
                command.firstOrNull() == "test" && command.getOrNull(1) == "-f" -> {
                    val path = shellUnquoteRemotePath(command.getOrNull(2).orEmpty())
                    if (missingFile && path == expectedSizes.keys.firstOrNull()) {
                        CmdResult(1, "", "missing fixture file")
                    } else {
                        verifiedFiles += path
                        CmdResult(0, "", "")
                    }
                }
                command.firstOrNull() == "stat" -> {
                    val path = shellUnquoteRemotePath(command.lastOrNull().orEmpty())
                    val expected = expectedSizes[path] ?: return CmdResult(1, "", "unknown fixture path")
                    val actual = if (wrongSize && path == expectedSizes.keys.firstOrNull()) {
                        expected + 1L
                    } else {
                        expected
                    }
                    CmdResult(0, "$actual\n", "")
                }
                command.firstOrNull() == "mkdir" -> {
                    val path = shellUnquoteRemotePath(command.getOrNull(1).orEmpty())
                    val parent = path.substringBeforeLast('/', "")
                    if (existingDirectories.contains(parent)) {
                        existingDirectories += path
                        mkdirCalls++
                        CmdResult(0, "", "")
                    } else {
                        CmdResult(1, "", "parent directory does not exist")
                    }
                }
                else -> CmdResult(0, "", "")
            }
        }

        override fun pushWithProgress(
            serial: String,
            from: File,
            toDevicePath: String,
            onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit
        ): CmdResult {
            pushCalls++
            onProgress(from.length(), from.length())
            return CmdResult(0, "", "")
        }

        override fun pushModFileWithProgress(
            serial: String,
            from: File,
            toDevicePath: String,
            onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit,
            cancelled: () -> Boolean
        ): CmdResult {
            pushCalls++
            onProgress(0L, from.length())
            onProgress((from.length() / 2L).coerceAtLeast(1L), from.length())
            onProgress(from.length(), from.length())
            return CmdResult(0, "", "")
        }
    }
}