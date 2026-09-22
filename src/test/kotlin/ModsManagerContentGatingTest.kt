import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Manager-level regression for the content/APK security boundary.  The
 * sanitized fixture keeps the real BONELAB root-scoped pallet schema and
 * representative bundle while the injected ADB transport owns all device
 * effects.
 */
class ModsManagerContentGatingTest {
    @Test
    fun bonelabContentDoesNotPullApkOrRequireExactHash() = runBlocking {
        val app = InstalledQuestApp(
            ModPackageAnalyzer.BONELAB_PACKAGE_ID,
            "1.2974.57485",
            2974L,
            "BONELAB"
        )
        val archive = zipOf(
            "NormalPapa.BLPlane/NormalPapa.BLPlane.pallet.json" to
                """
                {
                  "version": 2,
                  "root": {"ref": "1", "type": "pallet#0"},
                  "objects": {
                    "1": {
                      "barcode": "NormalPapa.BLPlane",
                      "title": "BL_Plane",
                      "author": "Normal Papa",
                      "version": "0.0.0",
                      "sdkVersion": "1.2.0",
                      "crates": [{"ref": "2", "type": "crate-level#0"}],
                      "isa": {"type": "pallet#0"}
                    },
                    "2": {
                      "barcode": "NormalPapa.BLPlane.Level.BLPlane",
                      "title": "BL_Plane",
                      "mainAsset": "bundle",
                      "isa": {"type": "crate-level#0"}
                    }
                  }
                }
                """.trimIndent(),
            "NormalPapa.BLPlane/NormalPapa.BLPlane_level.bundle" to "bundle-data"
        )
        val adb = ContentOnlyAdb(app)
        var apkRefreshes = 0
        val manager = ModsManager(
            adb,
            StaticModLoaderDetector(ModLoaderDetection(app.packageName, emptyMap())),
            NoOpApkModLoaderPatcher,
            QuestApkEvidenceRefresher { _, _, _, _ ->
                apkRefreshes++
                error("content installation must not request APK evidence")
            }
        )
        try {
            val analysis = manager.analyzeModPackage("SERIAL", archive, app)
            assertTrue(analysis.installable, analysis.message)
            assertTrue(analysis.installPlan.preconditions.none {
                it.code == "APK_SHA256_REQUIRED" || it.code == "APK_SHA256_UNSUPPORTED"
            })

            val plan = analysis.installPlan.bindToDevice("SERIAL")
            adb.expectedSizes = plan.mappings.associate { it.destinationPath to it.sizeBytes }
            val result = manager.executeInstallPlan("SERIAL", archive, plan)

            assertTrue(result.success, result.message)
            assertEquals(0, apkRefreshes)
            assertEquals(plan.mappings.size, adb.pushes)
        } finally {
            archive.delete()
        }
    }

    @Test
    fun dedicatedContentIsBlockedWhenApprovedBaseIsMissing() = runBlocking {
        val app = InstalledQuestApp(
            ModPackageAnalyzer.BONELAB_PACKAGE_ID,
            "1.2974.57485",
            2974L,
            "BONELAB"
        )
        val archive = zipOf(
            "NormalPapa.BLPlane/NormalPapa.BLPlane.pallet.json" to
                """{"root":{"ref":"1","type":"pallet#0"},"objects":{"1":{"barcode":"NormalPapa.BLPlane","crates":[],"sdkVersion":"1.2.0"}}}""",
            "NormalPapa.BLPlane/scene.bundle" to "bundle-data"
        )
        val adb = ContentOnlyAdb(app).also { it.baseExists = false }
        val manager = ModsManager(
            adb,
            StaticModLoaderDetector(ModLoaderDetection(app.packageName, emptyMap()))
        )
        try {
            val analysis = manager.analyzeModPackage("SERIAL", archive, app)
            assertTrue(analysis.installPlan.preconditions.any {
                it.code == "MOD_DESTINATION_REQUIRED"
            })
            assertTrue(!analysis.installable)
        } finally {
            archive.delete()
        }
    }

    @Test
    fun disappearingDedicatedBaseStopsBeforeAnyWrite() = runBlocking {
        val app = InstalledQuestApp(
            ModPackageAnalyzer.BONELAB_PACKAGE_ID,
            "1.2974.57485",
            2974L,
            "BONELAB"
        )
        val archive = zipOf(
            "NormalPapa.BLPlane/NormalPapa.BLPlane.pallet.json" to
                """{"root":{"ref":"1","type":"pallet#0"},"objects":{"1":{"barcode":"NormalPapa.BLPlane","crates":[],"sdkVersion":"1.2.0"}}}""",
            "NormalPapa.BLPlane/scene.bundle" to "bundle-data"
        )
        val adb = ContentOnlyAdb(app)
        val manager = ModsManager(
            adb,
            StaticModLoaderDetector(ModLoaderDetection(app.packageName, emptyMap()))
        )
        try {
            val analysis = manager.analyzeModPackage("SERIAL", archive, app)
            assertTrue(analysis.installable, analysis.message)
            val plan = analysis.installPlan.bindToDevice("SERIAL")
            adb.disappearImmediatelyBeforeChildMkdir = true
            val result = manager.executeInstallPlan("SERIAL", archive, plan)
            assertTrue(!result.success)
            assertEquals(0, adb.pushes)
            assertEquals(0, adb.successfulMkdirs)
        } finally {
            archive.delete()
        }
    }

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("nfvr-manager-content-gating-", ".zip")
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
        return file
    }

    private class ContentOnlyAdb(
        private val app: InstalledQuestApp
    ) : AdbClient(BundledAdb(HostOs.LINUX)) {
        var expectedSizes: Map<String, Long> = emptyMap()
        var pushes: Int = 0
        var mkdirs: Int = 0
        var successfulMkdirs: Int = 0
        var baseExists: Boolean = true
        var disappearImmediatelyBeforeChildMkdir: Boolean = false
        private val modRoot =
            "/sdcard/Android/data/${app.packageName}/files/Mods"
        private val existingDirectories = linkedSetOf(modRoot)

        override fun shell(serial: String, vararg args: String): CmdResult {
            val command = args.toList()
            return when {
                command == listOf("pm", "list", "packages", "-3", "-f") ->
                    CmdResult(
                        0,
                        "package:/data/app/${app.packageName}/base.apk=${app.packageName}\n",
                        ""
                    )
                command == listOf("dumpsys", "package", app.packageName) ->
                    CmdResult(
                        0,
                        "versionName=${app.versionName} versionCode=${app.versionCode}\n",
                        ""
                    )
                command.firstOrNull() == "stat" -> {
                    val path = shellUnquoteRemotePath(command.lastOrNull().orEmpty())
                    CmdResult(0, "${expectedSizes[path] ?: 0L}\n", "")
                }
                command.firstOrNull() == "test" &&
                    command.getOrNull(1) == "-d" -> {
                    val path = shellUnquoteRemotePath(command.getOrNull(2).orEmpty())
                    if (disappearImmediatelyBeforeChildMkdir &&
                        path.startsWith("$modRoot/") &&
                        baseExists
                    ) {
                        disappearImmediatelyBeforeChildMkdir = false
                        baseExists = false
                    }
                    CmdResult(
                        if (path == modRoot) {
                            if (baseExists) 0 else 1
                        } else if (baseExists && existingDirectories.contains(path)) 0 else 1,
                        "",
                        ""
                    )
                }
                command.firstOrNull() == "mkdir" -> {
                    val path = shellUnquoteRemotePath(command.getOrNull(1).orEmpty())
                    val parent = path.substringBeforeLast('/', "")
                    mkdirs++
                    if (baseExists && existingDirectories.contains(parent)) {
                        existingDirectories += path
                        successfulMkdirs++
                        CmdResult(0, "", "")
                    } else {
                        CmdResult(1, "", "approved base disappeared")
                    }
                }
                else -> CmdResult(0, "", "")
            }
        }

        override fun pushModFileWithProgress(
            serial: String,
            from: File,
            toDevicePath: String,
            onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit,
            cancelled: () -> Boolean
        ): CmdResult {
            pushes++
            onProgress(from.length(), from.length())
            return CmdResult(0, "", "")
        }
    }
}