import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for safe large-archive handling and Pavlov UGC support.
 *
 * Safety model: inspection/extraction stream with bounded memory; bombs are
 * rejected by expansion ratio (not arbitrary totals); single entries are
 * bounded by the ZIP format range; host and Quest free space gate the
 * transfer.  Pavlov mod.io UGC is recognized by content but authorizes no
 * destination.
 */
class PavlovAndLargeArchiveTest {
    private val bonelab = InstalledQuestApp(
        ModPackageAnalyzer.BONELAB_PACKAGE_ID,
        "1.2974.57485",
        2974L
    )
    private val pavlov = InstalledQuestApp(
        ModPackageAnalyzer.PAVLOV_PACKAGE_ID,
        "1.0.29",
        2397L,
        "Pavlov Shack"
    )

    // ---- 1. Expansion-ratio guard (pure) ----

    @Test
    fun legitimateLargeArchiveRatioAccepted() {
        // Mango-scale: ~3.5GB expanded from ~3.4GB compressed (ratio ~1).
        assertFalse(expansionAbuse(3_758_096_384L, 3_500_000_000L))
        // FXDux-scale: ~1GB from ~990MB.
        assertFalse(expansionAbuse(1_040_558_515L, 990_041_489L))
    }

    @Test
    fun unknownCompressedTotalNeverTriggers() {
        assertFalse(expansionAbuse(10L * 1024L * 1024L * 1024L, 0L))
        assertFalse(expansionAbuse(10L * 1024L * 1024L * 1024L, -1L))
    }

    @Test
    fun smallArchivesUnaffectedByRatio() {
        // 1000x ratio but under the floor: accepted (floor protects tests
        // and small legitimate files from ratio noise).
        assertFalse(expansionAbuse(100L * 1024L * 1024L, 100L * 1024L))
    }

    @Test
    fun bombLikeRatioRejected() {
        // 5GB expanded from 50MB compressed (100x).
        assertTrue(expansionAbuse(5L * 1024L * 1024L * 1024L, 50L * 1024L * 1024L))
    }

    // ---- 2. Single-entry bound (pure) ----

    @Test
    fun largeLegitimateEntryAllowedByFormatRange() {
        // Real Pavlov .pak: 581,640,199 bytes.
        assertTrue(entrySizeAllowed(581_640_199L))
        assertTrue(entrySizeAllowed(0xFFFFFFFFL))
        assertFalse(entrySizeAllowed(0x100000000L))
        assertFalse(entrySizeAllowed(-1L))
    }

    // ---- 3. File-level bomb vs legitimate-large ----

    @Test
    fun storedLargeEntryInspectsWithoutSizeRejection() {
        val archive = largeStoredZeros("Big/big.bin", 600L * 1024L * 1024L)
        try {
            val analysis = ModPackageAnalyzer().analyze(archive, bonelab)
            // Content is not BONELAB-shaped, but it must NOT be rejected
            // for size: the failure mode under test is the old 512MB cap.
            val sizeBlocked = analysis.installPlan.preconditions.any {
                !it.satisfied && it.message.contains("safe size limit")
            } || (analysis.outcome == ModInstallOutcome.UNSAFE_ARCHIVE &&
                analysis.message.contains("safe size limit"))
            assertFalse(sizeBlocked, analysis.message)
        } finally {
            archive.delete()
        }
    }

    @Test
    fun deflatedBombRejectedByRatio() {
        val archive = largeDeflatedZeros("bomb/bomb.bin", 600L * 1024L * 1024L)
        try {
            val analysis = ModPackageAnalyzer().analyze(archive, bonelab)
            assertFalse(analysis.installable)
            assertEquals(ModInstallOutcome.UNSAFE_ARCHIVE, analysis.outcome)
            assertTrue(analysis.message.contains("expansion ratio"), analysis.message)
        } finally {
            archive.delete()
        }
    }

    // ---- 4/5. Disk gates ----

    @Test
    fun hostDiskGateBlocksWithoutMargin() {
        val required = 2L * 1024L * 1024L * 1024L
        assertFalse(installDiskSpaceSufficient(1L * 1024L * 1024L * 1024L, required))
        assertFalse(installDiskSpaceSufficient(required, required))
        assertTrue(installDiskSpaceSufficient(required + MOD_INSTALL_DISK_MARGIN_BYTES, required))
        assertTrue(installDiskSpaceSufficient(null, required))
        assertTrue(installDiskSpaceSufficient(0L, 0L))
    }

    @Test
    fun questDiskGateBlocksInstallBeforeAnyPush() = runBlocking {
        val archive = bonelabPalletFixture()
        try {
            val adb = TinyDfAdb(bonelab)
            val manager = ModsManager(
                adb,
                StaticModLoaderDetector(ModLoaderDetection(bonelab.packageName, emptyMap())),
                NoOpApkModLoaderPatcher
            )
            val plan = manager.analyzeModPackage(archive, bonelab).installPlan
                .bindToDevice("SERIAL")
            val result = manager.executeInstallPlan("SERIAL", archive, plan)
            assertFalse(result.success)
            assertTrue(result.message.contains("مساحة"), result.message)
            assertEquals(0, adb.pushCalls)
            assertEquals(0, adb.mkdirCalls)
        } finally {
            archive.delete()
        }
    }

    @Test
    fun questDfParserReadsAvailableBytes() {
        val df = "Filesystem 1K-blocks Used Available Use% Mounted on\n" +
            "/dev/fuse 470203100 352742404 117313240 76% /storage/emulated\n"
        assertEquals(117313240L * 1024L, parseQuestDfAvailableBytes(df))
        assertNull(parseQuestDfAvailableBytes(""))
        assertNull(parseQuestDfAvailableBytes("garbage without header\n1 2 3\n"))
        assertNull(parseQuestDfAvailableBytes("Filesystem 1K-blocks Used\n"))
    }

    // ---- 6. 64-bit sizes end to end ----

    @Test
    fun longSizesPreservedEndToEnd() {
        val mapping = ModFileMapping("big.bundle", "/sdcard/Android/data/x/files/Mods/M/big.bundle", sizeBytes = 5_000_000_000L)
        val plan = ModInstallPlan(mappings = listOf(mapping))
        assertEquals(5_000_000_000L, plan.totalBytes)
        val progress = plan.progress(2_500_000_000L, 0)
        assertEquals(0.5, progress.fraction, 1e-9)
        assertEquals(50, progress.percent)
    }

    // ---- 7/8/9. Pavlov classification ----

    private fun pavlovFixture(): File = zipOf(
        "metadata.json" to """{"EngineVersion":"5.1.1","ModType":1}""",
        "UGC6140536pakchunk0-Android_ASTC.pak" to "pak-bytes"
    )

    @Test
    fun renamedPavlovArchivesClassifyIdentically() {
        val first = pavlovFixture()
        val second = pavlovFixture()
        try {
            val a = ModPackageAnalyzer().analyze(first, pavlov)
            val b = ModPackageAnalyzer().analyze(second, pavlov)
            assertEquals(a.packageType, b.packageType)
            assertEquals(ModPackageType.PAVLOV_UGC_CONTENT, a.packageType)
            assertEquals(a.outcome, b.outcome)
            assertEquals(a.installable, b.installable)
        } finally {
            first.delete()
            second.delete()
        }
    }

    @Test
    fun pavlovContentRecognizedButNeverInstallable() {
        val archive = pavlovFixture()
        try {
            val analysis = ModPackageAnalyzer().analyze(archive, pavlov)
            assertEquals(ModPackageType.PAVLOV_UGC_CONTENT, analysis.packageType)
            assertEquals(ModInstallOutcome.BUILT_IN_GAME_CONTENT, analysis.outcome)
            assertFalse(analysis.installable)
            assertTrue(analysis.installPlan.mappings.isEmpty())
            assertTrue(
                analysis.installPlan.preconditions.any {
                    !it.satisfied && it.code == "PAVLOV_IMPORTER_UNVERIFIED"
                }
            )
            assertEquals("6140536", analysis.metadata["modId"])
            assertTrue(customerPreconditionMessage("PAVLOV_IMPORTER_UNVERIFIED").isNotBlank())
        } finally {
            archive.delete()
        }
    }

    @Test
    fun pavlovWithWrongAppStaysBlocked() {
        val archive = pavlovFixture()
        try {
            val analysis = ModPackageAnalyzer().analyze(archive, bonelab)
            assertFalse(analysis.installable)
            assertTrue(analysis.installPlan.mappings.isEmpty())
        } finally {
            archive.delete()
        }
    }

    @Test
    fun pavlovProfileIsClassificationOnly() {
        val profile = GameModProfileRegistry.findByPackageId(ModPackageAnalyzer.PAVLOV_PACKAGE_ID)
        assertTrue(profile != null)
        assertFalse(profile.writeAuthorized)
        assertTrue(profile.destination.isBlank())
        assertTrue(profile.supportsVersion(pavlov))
        assertTrue(profile.knownContentDirectories.isEmpty())
    }

    @Test
    fun traversalRejected() {
        val archive = zipOf(
            "metadata.json" to """{"EngineVersion":"5.1.1","ModType":1}""",
            "../evil.pak" to "evil"
        )
        try {
            val analysis = ModPackageAnalyzer().analyze(archive, pavlov)
            assertFalse(analysis.installable, analysis.message)
        } finally {
            archive.delete()
        }
    }

    // ---- 10/11/12. No-overwrite, BONELAB, scale ----

    @Test
    fun existingDestinationRefusesOverwriteWithArabicReason() = runBlocking {
        val archive = bonelabPalletFixture()
        try {
            val adb = TinyDfAdb(
                bonelab,
                collision = true,
                availableKb = 20L * 1024L * 1024L
            )
            val manager = ModsManager(
                adb,
                StaticModLoaderDetector(ModLoaderDetection(bonelab.packageName, emptyMap())),
                NoOpApkModLoaderPatcher
            )
            val plan = manager.analyzeModPackage(archive, bonelab).installPlan
                .bindToDevice("SERIAL")
            val result = manager.executeInstallPlan("SERIAL", archive, plan)
            assertFalse(result.success)
            assertTrue(result.message.contains("موجود مسبقًا"), result.message)
            assertEquals(0, adb.mkdirCalls)
            assertEquals(0, adb.pushCalls)
        } finally {
            archive.delete()
        }
    }

    @Test
    fun workingBonelabFixtureStillPasses() {
        val archive = bonelabPalletFixture()
        try {
            val analysis = ModPackageAnalyzer().analyze(archive, bonelab)
            assertTrue(analysis.installable, analysis.message)
            assertEquals(ModInstallOutcome.DIRECT_INSTALL_READY, analysis.outcome)
            assertEquals(ModPackageType.BONELAB_NATIVE_CONTENT, analysis.packageType)
        } finally {
            archive.delete()
        }
    }

    @Test
    fun largeBonelabShapeAnalysisDoesNotRegress() {
        val entries = buildList {
            add("BigPack/pallet.json" to
                """{"name":"Big","packageId":"com.StressLevelZero.BONELAB","gameVersion":"1.2974.57485","version":"1.0.0"}""")
            repeat(500) { index -> add("BigPack/data/file$index.bundle" to "x") }
        }.toTypedArray()
        val archive = zipOf(*entries)
        try {
            val analysis = ModPackageAnalyzer().analyze(archive, bonelab)
            assertTrue(analysis.installable, analysis.message)
            assertEquals(501, analysis.installPlan.totalFiles)
        } finally {
            archive.delete()
        }
    }

    // ---- helpers ----

    private fun bonelabPalletFixture(): File = zipOf(
        "ProbePallet/pallet.json" to
            """{"name":"Probe","packageId":"com.StressLevelZero.BONELAB","gameVersion":"1.2974.57485","version":"1.0.0"}""",
        "ProbePallet/content.assetbundle" to "bundle-bytes"
    )

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("nfvr-pavlov-", ".zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    private fun largeStoredZeros(entryName: String, sizeBytes: Long): File {
        val file = File.createTempFile("nfvr-big-stored-", ".zip")
        val zeros = ByteArray(1024 * 1024)
        val crc = CRC32()
        var remaining = sizeBytes
        while (remaining > 0) {
            val chunk = minOf(remaining, zeros.size.toLong()).toInt()
            crc.update(zeros, 0, chunk)
            remaining -= chunk
        }
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            val entry = ZipEntry(entryName)
            entry.method = ZipEntry.STORED
            entry.size = sizeBytes
            entry.crc = crc.value
            zip.putNextEntry(entry)
            remaining = sizeBytes
            while (remaining > 0) {
                val chunk = minOf(remaining, zeros.size.toLong()).toInt()
                zip.write(zeros, 0, chunk)
                remaining -= chunk
            }
            zip.closeEntry()
        }
        return file
    }

    private fun largeDeflatedZeros(entryName: String, sizeBytes: Long): File {
        val file = File.createTempFile("nfvr-big-deflated-", ".zip")
        val zeros = ByteArray(1024 * 1024)
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            var remaining = sizeBytes
            while (remaining > 0) {
                val chunk = minOf(remaining, zeros.size.toLong()).toInt()
                zip.write(zeros, 0, chunk)
                remaining -= chunk
            }
            zip.closeEntry()
        }
        return file
    }

    private class TinyDfAdb(
        private val app: InstalledQuestApp,
        private val collision: Boolean = false,
        private val availableKb: Long = 1024L
    ) : AdbClient(BundledAdb(HostOs.LINUX)) {
        var pushCalls = 0
        var mkdirCalls = 0
        private val modRoot = "/sdcard/Android/data/${app.packageName}/files/Mods"

        override fun shell(serial: String, vararg args: String): CmdResult {
            val command = args.toList()
            return when {
                command == listOf("pm", "list", "packages", "-3", "-f") ->
                    CmdResult(0, "package:/data/app/${app.packageName}/base.apk=${app.packageName}\n", "")
                command == listOf("dumpsys", "package", app.packageName) ->
                    CmdResult(0, "versionName=${app.versionName} versionCode=${app.versionCode}\n", "")
                command.firstOrNull() == "df" ->
                    CmdResult(
                        0,
                        "Filesystem 1K-blocks Used Available Use% Mounted on\n" +
                            "/dev/fuse 100000 90000 $availableKb 99% /storage/emulated\n",
                        ""
                    )
                command.firstOrNull() == "test" && command.getOrNull(1) == "!" ->
                    CmdResult(if (collision) 1 else 0, "", "")
                command.firstOrNull() == "test" -> CmdResult(0, "", "")
                command.firstOrNull() == "stat" ->
                    CmdResult(0, "1\n", "")
                command.firstOrNull() == "mkdir" -> {
                    mkdirCalls++
                    CmdResult(0, "", "")
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
            pushCalls++
            return CmdResult(0, "", "")
        }
    }
}
