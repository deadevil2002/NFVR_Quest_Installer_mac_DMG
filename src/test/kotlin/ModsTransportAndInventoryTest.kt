import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for literal remote paths and the Beat Saber installed
 * dependency inventory.
 *
 * `adb shell` re-parses joined args on device, so every remote path must
 * travel single-quoted.  The inventory resolves QMOD declarations against
 * on-device Scotland2 evidence without downloading anything.
 */
class ModsTransportAndInventoryTest {
    // ---- shell quoting ----

    @Test
    fun dollarPathsStayLiteral() {
        assertEquals(
            "'/sdcard/Android/data/x/files/Mods/M/\$black_color.bundle'",
            shellQuoteRemotePath("/sdcard/Android/data/x/files/Mods/M/\$black_color.bundle")
        )
    }

    @Test
    fun spacesAndMetacharactersStayLiteral() {
        assertEquals(
            "'/sdcard/a b;c|d`e\"f'",
            shellQuoteRemotePath("/sdcard/a b;c|d`e\"f")
        )
    }

    @Test
    fun embeddedQuotesEscapeSafely() {
        assertEquals("'a'\\''b'", shellQuoteRemotePath("a'b"))
        assertEquals("a'b", shellUnquoteRemotePath("'a'\\''b'"))
    }

    @Test
    fun dollarNamesAcceptedWhileShellMetacharsRejected() {
        assertTrue(
            AndroidPathValidator.isSafe("/sdcard/Android/data/x/files/Mods/M/\$black_color.bundle")
        )
        assertTrue(
            AndroidPathValidator.isSafe("/sdcard/Android/data/x/files/Mods/M/mango'sm16.bundle")
        )
        assertTrue(
            AndroidPathValidator.isSafe("/sdcard/Android/data/x/files/Mods/M/s&wshield.bundle")
        )
        assertTrue(
            AndroidPathValidator.isSafe("/sdcard/Android/data/x/files/Mods/M/reticle++-1.bundle")
        )
        assertTrue(
            AndroidPathValidator.isSafe("/sdcard/Android/data/x/files/Mods/M/sr2m“veresk”.bundle")
        )
        assertFalse(AndroidPathValidator.isSafe("/sdcard/a;b"))
        assertTrue(AndroidPathValidator.isSafe("/sdcard/a&b"))
        assertFalse(AndroidPathValidator.isSafe("/sdcard/a|b"))
        assertFalse(AndroidPathValidator.isSafe("/sdcard/a`b"))
        assertFalse(AndroidPathValidator.isSafe("/sdcard/a>b"))
        assertTrue(AndroidPathValidator.isSafe("/sdcard/with space/and(parens)"))
    }

    @Test
    fun quotingRoundTrips() {
        listOf(
            "/sdcard/ModData/com.beatgames.beatsaber/Modloader/mods/x.so",
            "/sdcard/Android/data/x/files/Mods/M/\$white_ao.bundle",
            "/sdcard/with space/and;metachars"
        ).forEach { path ->
            assertEquals(path, shellUnquoteRemotePath(shellQuoteRemotePath(path)))
        }
        assertEquals("plain", shellUnquoteRemotePath("plain"))
    }

    // ---- package dir parsing ----

    @Test
    fun scotland2PackageDirsParse() {
        val parsed = parseScotland2PackageDir("beatsaber-hook_v6.4.2", "src")
        assertEquals("beatsaber-hook", parsed.id)
        assertEquals("6.4.2", parsed.version)
        val bare = parseScotland2PackageDir("SongCore", "src")
        assertEquals("SongCore", bare.id)
        assertEquals(null, bare.version)
    }

    // ---- semver ranges ----

    @Test
    fun caretTildeExactRanges() {
        assertEquals(true, qmodVersionSatisfies("6.4.2", "^6.4.2"))
        assertEquals(true, qmodVersionSatisfies("6.9.0", "^6.4.2"))
        assertEquals(false, qmodVersionSatisfies("7.0.0", "^6.4.2"))
        assertEquals(false, qmodVersionSatisfies("6.4.1", "^6.4.2"))
        assertEquals(true, qmodVersionSatisfies("0.4.53", "^0.4.53"))
        assertEquals(false, qmodVersionSatisfies("0.5.0", "^0.4.53"))
        assertEquals(true, qmodVersionSatisfies("1.1.22", "~1.1.20"))
        assertEquals(false, qmodVersionSatisfies("1.2.0", "~1.1.20"))
        assertEquals(true, qmodVersionSatisfies("1.1.22", "1.1.22"))
        assertEquals(false, qmodVersionSatisfies("1.1.23", "1.1.22"))
        assertEquals(null, qmodVersionSatisfies("1.1.22", ">=1.0.0 <2.0.0"))
        assertEquals(null, qmodVersionSatisfies(null, "^1.0.0"))
        assertEquals(null, qmodVersionSatisfies("bogus", "^1.0.0"))
    }

    // ---- inventory resolution ----

    private fun inventory() = BeatSaberModInventory(
        serial = "SERIAL",
        packageId = "com.beatgames.beatsaber",
        gameVersion = "1.40.8_7379",
        packages = listOf(
            BeatSaberInstalledPackage("beatsaber-hook", "6.4.2", "Packages/1.40.6_6407/beatsaber-hook_v6.4.2"),
            BeatSaberInstalledPackage("bsml", "0.4.50", "Packages/1.40.6_6407/bsml_v0.4.50")
        ),
        loaderLibs = listOf("libcustom-types.so"),
        mods = emptyList()
    )

    @Test
    fun dependencyStatusesResolveAgainstInventory() {
        val deps = listOf(
            ModPackageDependency(id = "beatsaber-hook", version = "^6.4.2"),
            ModPackageDependency(id = "bsml", version = "^0.4.53"),
            ModPackageDependency(id = "songcore", version = "^1.0.0"),
            ModPackageDependency(id = "custom-types", version = "^0.18.3"),
            ModPackageDependency(id = "holiday-cheer", version = "^1.0.0", optional = true)
        )
        val reports = resolveQmodDependencyStatuses(deps, inventory())
        assertEquals(QmodDependencyStatus.SATISFIED, reports[0].status)
        assertEquals("6.4.2", reports[0].foundVersion)
        assertEquals(QmodDependencyStatus.VERSION_MISMATCH, reports[1].status)
        assertEquals(QmodDependencyStatus.MISSING, reports[2].status)
        // lib .so proves presence but carries no version: never SATISFIED.
        assertEquals(QmodDependencyStatus.UNKNOWN, reports[3].status)
        assertEquals(QmodDependencyStatus.OPTIONAL_MISSING, reports[4].status)
    }

    @Test
    fun nullInventoryKeepsEverythingUnknown() {
        val deps = listOf(ModPackageDependency(id = "beatsaber-hook", version = "^6.4.2"))
        assertEquals(
            QmodDependencyStatus.UNKNOWN,
            resolveQmodDependencyStatuses(deps, null).single().status
        )
    }

    @Test
    fun satisfiedInventoryUnlocksQmodPlan() {
        val archive = qmodWithDeps()
        try {
            val app = InstalledQuestApp("com.beatgames.beatsaber", "1.40.8_7379", 1716L)
            val fullInventory = BeatSaberModInventory(
                serial = "SERIAL",
                packageId = app.packageName,
                gameVersion = app.versionName,
                packages = listOf(
                    BeatSaberInstalledPackage("beatsaber-hook", "6.4.2", "Packages/x"),
                    BeatSaberInstalledPackage("custom-types", "0.18.3", "Packages/x")
                )
            )
            val discovery = ModDirectoryDiscovery(
                serial = "SERIAL",
                packageId = app.packageName,
                beatSaberInventory = fullInventory
            )
            val detection = ModLoaderDetection(
                app.packageName,
                mapOf(
                    ModLoaderKind.SCOTLAND2 to ModLoaderEvidence(
                        ModLoaderKind.SCOTLAND2, ModLoaderStatus.DETECTED, listOf("fs")
                    )
                )
            )
            val analysis = ModPackageAnalyzer().analyze(archive, app, detection, discovery)
            assertTrue(analysis.installable, analysis.message)
            assertEquals(ModInstallOutcome.DIRECT_INSTALL_READY, analysis.outcome)
            assertTrue(
                analysis.installPlan.preconditions.any {
                    it.satisfied && it.code == "DEPENDENCIES_SATISFIED"
                }
            )
        } finally {
            archive.delete()
        }
    }

    @Test
    fun missingDependencyKeepsNamedBlock() {
        val archive = qmodWithDeps()
        try {
            val app = InstalledQuestApp("com.beatgames.beatsaber", "1.40.8_7379", 1716L)
            val discovery = ModDirectoryDiscovery(
                serial = "SERIAL",
                packageId = app.packageName,
                beatSaberInventory = BeatSaberModInventory(
                    serial = "SERIAL",
                    packageId = app.packageName,
                    gameVersion = app.versionName
                )
            )
            val detection = ModLoaderDetection(
                app.packageName,
                mapOf(
                    ModLoaderKind.SCOTLAND2 to ModLoaderEvidence(
                        ModLoaderKind.SCOTLAND2, ModLoaderStatus.DETECTED, listOf("fs")
                    )
                )
            )
            val analysis = ModPackageAnalyzer().analyze(archive, app, detection, discovery)
            assertFalse(analysis.installable)
            val block = analysis.installPlan.preconditions
                .firstOrNull { !it.satisfied && it.code == "DEPENDENCIES_REQUIREMENT" }
            assertTrue(block != null)
            assertTrue(block.message.contains("beatsaber-hook"), block.message)
        } finally {
            archive.delete()
        }
    }

    @Test
    fun schemaKeyAcceptedAsInformationalMetadata() {
        val file = File.createTempFile("nfvr-qmod-schema-", ".zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("mod.json"))
            zip.write(
                """{"${'$'}schema":"https://example.invalid/qmod.schema.json","_QPVersion":"1.2.0","id":"probe","name":"Probe","author":"NFVR","version":"1.0.0","packageId":"com.beatgames.beatsaber","modloader":"Scotland2","modFiles":["probe.dat"]}"""
                    .toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("probe.dat"))
            zip.write("payload".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        try {
            val app = InstalledQuestApp("com.beatgames.beatsaber", "1.40.8_7379", 1716L)
            val detection = ModLoaderDetection(
                app.packageName,
                mapOf(
                    ModLoaderKind.SCOTLAND2 to ModLoaderEvidence(
                        ModLoaderKind.SCOTLAND2, ModLoaderStatus.DETECTED, listOf("fs")
                    )
                )
            )
            val analysis = ModPackageAnalyzer().analyze(file, app, detection)
            assertTrue(analysis.installable, analysis.message)
            assertFalse(
                analysis.installPlan.preconditions.any { it.code == "UNKNOWN_QMOD_FIELD" }
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun sourceCatalogCoversEveryProfileWithoutUniversalPath() {
        val profiles = GameModProfileRegistry.profiles.map { it.packageId }.toSet()
        val cataloged = ModSourceCatalog.entries.map { it.packageId }.toSet()
        assertTrue(cataloged.containsAll(profiles), "catalog=$cataloged profiles=$profiles")
        // Sources and install methods stay per-game: no single shared path.
        val methods = ModSourceCatalog.entries.map { it.installMethod }.toSet()
        assertTrue(methods.size > 1)
        assertTrue(
            ModSourceCatalog.entries.none {
                it.installMethod == GameInstallMethod.DIRECT_COPY &&
                    it.registration.contains("universal", ignoreCase = true)
            }
        )
    }

    // ---- safe temp transport ----

    @Test
    fun windowsIllegalNamesNeedTempStaging() {
        assertTrue(requiresSafeTempName("we\"ird.bundle"))
        assertTrue(requiresSafeTempName("a*b"))
        assertTrue(requiresSafeTempName("a?b"))
        assertTrue(requiresSafeTempName("a<b"))
        assertTrue(requiresSafeTempName("trailing."))
        assertTrue(requiresSafeTempName("trailing "))
        assertTrue(requiresSafeTempName(""))
        // Smart quotes, $, ', &, +, spaces: legal on Windows, direct.
        assertFalse(requiresSafeTempName("sr2m“veresk”.bundle"))
        assertFalse(requiresSafeTempName("\$black_color.bundle"))
        assertFalse(requiresSafeTempName("mango'sm16.bundle"))
        assertFalse(requiresSafeTempName("normal.bundle"))
    }

    @Test
    fun tempNamesAreSafeAndDeterministic() {
        val first = safeLocalTempName(3, "abc123")
        val second = safeLocalTempName(3, "abc123")
        assertEquals(first, second)
        assertTrue(first.endsWith(".nfvrpart"))
        assertFalse(requiresSafeTempName(first))
        assertTrue(safeLocalTempName(4, "abc123") != first)
    }

    @Test
    fun remoteTempDirStaysInsideApprovedRoot() {
        val root = "/sdcard/Android/data/x/files/Mods"
        val tmp = remoteTempDir(root, "planid123456")
        assertTrue(tmp != null && tmp.startsWith("$root/.nfvr-tmp-"))
        assertTrue(isNfvrTempDir(tmp!!, root))
        assertEquals(null, remoteTempDir("", "planid123456"))
        assertEquals(null, remoteTempDir(root, ""))
        assertEquals(null, remoteTempDir("/etc", "planid123456"))
        assertFalse(isNfvrTempDir(root, root))
        assertFalse(isNfvrTempDir("/sdcard/other/.nfvr-tmp-x", root))
        assertFalse(isNfvrTempDir("$root/.nfvr-tmp-", root))
        assertFalse(isNfvrTempDir("$root/other", root))
    }

    @Test
    fun quoteFileStagesThroughTempAndKeepsExactFinalName() = runBlocking {
        val archive = quoteFixture()
        try {
            val adb = TempTransportAdb(bonelabApp())
            val manager = managerFor(adb, bonelabApp())
            val analysis = manager.analyzeModPackage(archive, bonelabApp())
            assertTrue(analysis.installable, analysis.message + " :: " + analysis.installPlan.preconditions.toString())
            val plan = analysis.installPlan.bindToDevice("SERIAL")
            assertTrue(plan.installable, plan.preconditions.toString())
            val result = manager.executeInstallPlan("SERIAL", archive, plan)
            assertTrue(result.success, result.message)
            // The trailing-dot file (Windows-unmaterializable) pushed to
            // temp, then moved to the exact final; others pushed direct.
            val dotFinal = plan.mappings.first { it.destinationPath.endsWith("trailingdot.") }.destinationPath
            val pushes = adb.pushTargets.filter { it.endsWith(".nfvrpart") || it.contains(".nfvr-tmp-") }
            assertTrue(pushes.isNotEmpty(), adb.pushTargets.toString())
            assertTrue(adb.pushTargets.none { it.endsWith("trailingdot.") }, adb.pushTargets.toString())
            assertEquals(1, adb.moves.count { it.second == dotFinal }, adb.moves.toString())
            assertTrue(adb.cleaned, "remote temp dir must be cleaned after success")
            // Final verification saw the exact difficult names.
            assertTrue(adb.verifiedFiles.any { it.endsWith("trailingdot.") }, adb.verifiedFiles.toString())
            assertTrue(adb.verifiedFiles.any { it.contains("“veresk”") }, adb.verifiedFiles.toString())
        } finally {
            archive.delete()
        }
    }

    @Test
    fun failedStagedTransferCleansTempAndWritesNoFinal() = runBlocking {
        val archive = quoteFixture()
        try {
            val adb = TempTransportAdb(bonelabApp(), failPush = true)
            val manager = managerFor(adb, bonelabApp())
            val plan = manager.analyzeModPackage(archive, bonelabApp()).installPlan
                .bindToDevice("SERIAL")
            val result = manager.executeInstallPlan("SERIAL", archive, plan)
            assertFalse(result.success)
            assertTrue(adb.cleaned, "remote temp dir must be cleaned after failure")
            assertTrue(adb.moves.isEmpty())
        } finally {
            archive.delete()
        }
    }

    @Test
    fun mkdirEexistRaceContinuesWhenDirExists() = runBlocking {
        val adb = object : AdbClient(BundledAdb(HostOs.LINUX)) {
            val tests = linkedMapOf<String, Int>()
            override fun shell(serial: String, vararg args: String): CmdResult {
                val command = args.toList()
                if (command.firstOrNull() == "test" && command.getOrNull(1) == "-d") {
                    val path = shellUnquoteRemotePath(command.getOrNull(2).orEmpty())
                    tests[path] = (tests[path] ?: 0) + 1
                    // First probe misses (stale cache), recheck hits.
                    return CmdResult(if ((tests[path] ?: 0) > 1) 0 else 1, "", "")
                }
                if (command.firstOrNull() == "mkdir") {
                    return CmdResult(1, "", "mkdir: File exists")
                }
                return CmdResult(0, "", "")
            }
        }
        val manager = ModsManager(
            adb,
            StaticModLoaderDetector(ModLoaderDetection(bonelabApp().packageName, emptyMap())),
            NoOpApkModLoaderPatcher
        )
        val base = "/sdcard/Android/data/${bonelabApp().packageName}/files/Mods"
        val result = manager.createModPath("SERIAL", "$base/M", approvedBase = base)
        assertEquals(0, result.exit)
    }

    @Test
    fun mkdirStaleCacheRecheckEventuallyContinues() = runBlocking {
        var probes = 0
        val adb = object : AdbClient(BundledAdb(HostOs.LINUX)) {
            override fun shell(serial: String, vararg args: String): CmdResult {
                val command = args.toList()
                if (command.firstOrNull() == "test" && command.getOrNull(1) == "-d") {
                    probes++
                    // Stale negative cache for the first two probes.
                    return CmdResult(if (probes > 2) 0 else 1, "", "")
                }
                if (command.firstOrNull() == "mkdir") {
                    return CmdResult(1, "", "mkdir: File exists")
                }
                return CmdResult(0, "", "")
            }
        }
        val manager = ModsManager(
            adb,
            StaticModLoaderDetector(ModLoaderDetection(bonelabApp().packageName, emptyMap())),
            NoOpApkModLoaderPatcher
        )
        val base = "/sdcard/Android/data/${bonelabApp().packageName}/files/Mods"
        val result = manager.createModPath("SERIAL", "$base/M", approvedBase = base)
        assertEquals(0, result.exit)
        assertTrue(probes >= 3)
    }

    private fun bonelabApp() = InstalledQuestApp(
        ModPackageAnalyzer.BONELAB_PACKAGE_ID, "1.2974.57485", 2974L
    )

    private fun managerFor(adb: TempTransportAdb, app: InstalledQuestApp) = ModsManager(
        adb,
        StaticModLoaderDetector(ModLoaderDetection(app.packageName, emptyMap())),
        NoOpApkModLoaderPatcher
    )

    private fun quoteFixture(): File {
        // NOTE: ASCII quotes can never reach analysis on Windows because
        // Win32 path parsing rejects them during archive inspection (a
        // platform limit, not policy).  Trailing dots exercise the same
        // temp-staging path end to end on this host; smart quotes flow
        // direct like any legal name.
        val file = File.createTempFile("nfvr-quote-", ".zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("M/pallet.json"))
            zip.write(
                """{"name":"Q","packageId":"com.StressLevelZero.BONELAB","gameVersion":"1.2974.57485","version":"1.0.0"}"""
                    .toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("M/trailingdot."))
            zip.write("dot-bytes".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("M/sr2m“veresk”.bundle"))
            zip.write("smart-bytes".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("M/normal.bundle"))
            zip.write("normal-bytes".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return file
    }

    @Test
    fun transientPushFlakeRecoversOnRetry() = runBlocking {
        val archive = quoteFixture()
        try {
            val adb = TempTransportAdb(bonelabApp(), failFirstPush = true)
            val manager = managerFor(adb, bonelabApp())
            val plan = manager.analyzeModPackage(archive, bonelabApp()).installPlan
                .bindToDevice("SERIAL")
            val result = manager.executeInstallPlan("SERIAL", archive, plan)
            assertTrue(result.success, result.message)
            assertTrue(adb.pushCalls >= plan.mappings.size + 1, "expected a retried push")
            assertTrue(adb.cleaned)
        } finally {
            archive.delete()
        }
    }

    private class TempTransportAdb(
        private val app: InstalledQuestApp,
        private val failPush: Boolean = false,
        private val failFirstPush: Boolean = false
    ) : AdbClient(BundledAdb(HostOs.LINUX)) {
        val pushTargets = mutableListOf<String>()
        var pushCalls = 0
        val moves = mutableListOf<Pair<String, String>>()
        val verifiedFiles = linkedSetOf<String>()
        var cleaned = false
        private val files = linkedMapOf<String, Long>()
        private val modRoot = "/sdcard/Android/data/${app.packageName}/files/Mods"

        private fun unq(raw: String) = shellUnquoteRemotePath(raw)

        override fun shell(serial: String, vararg args: String): CmdResult {
            val command = args.toList()
            return when {
                command == listOf("pm", "list", "packages", "-3", "-f") ->
                    CmdResult(0, "package:/data/app/${app.packageName}/base.apk=${app.packageName}\n", "")
                command == listOf("dumpsys", "package", app.packageName) ->
                    CmdResult(0, "versionName=${app.versionName} versionCode=${app.versionCode}\n", "")
                command.firstOrNull() == "df" ->
                    CmdResult(0, "Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/fuse 99999999 1000 99998999 1% /storage/emulated\n", "")
                command.firstOrNull() == "test" && command.getOrNull(1) == "!" ->
                    CmdResult(0, "", "")
                command.firstOrNull() == "test" -> CmdResult(0, "", "")
                command.firstOrNull() == "stat" -> {
                    val path = unq(command.lastOrNull().orEmpty())
                    val size = files[path] ?: return CmdResult(1, "", "missing")
                    verifiedFiles += path
                    CmdResult(0, "$size\n", "")
                }
                command.firstOrNull() == "mkdir" -> CmdResult(0, "", "")
                command.firstOrNull() == "mv" -> {
                    val src = unq(command.getOrNull(1).orEmpty())
                    val dst = unq(command.getOrNull(2).orEmpty())
                    val size = files.remove(src) ?: return CmdResult(1, "", "no such temp file")
                    files[dst] = size
                    moves += src to dst
                    CmdResult(0, "", "")
                }
                command.firstOrNull() == "rm" -> {
                    val target = unq(command.lastOrNull().orEmpty())
                    if (!target.contains(".nfvr-tmp-")) return CmdResult(1, "", "refusing")
                    files.keys.filter { it.startsWith(target.trimEnd('/') + "/") || it == target.trimEnd('/') }
                        .forEach { files.remove(it) }
                    cleaned = true
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
            if (failPush) return CmdResult(1, "", "injected push failure")
            pushCalls++
            if (failFirstPush && pushCalls == 1) return CmdResult(1, "", "injected flake")
            pushTargets += toDevicePath
            files[toDevicePath] = from.length()
            onProgress(from.length(), from.length())
            return CmdResult(0, "", "")
        }
    }

    private fun qmodWithDeps(): File {
        val file = File.createTempFile("nfvr-qmod-deps-", ".zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("mod.json"))
            zip.write(
                """{"_QPVersion":"1.2.0","id":"probe","name":"Probe","author":"NFVR","version":"1.0.0","packageId":"com.beatgames.beatsaber","modloader":"Scotland2","modFiles":["probe.dat"],"dependencies":[{"id":"beatsaber-hook","version":"^6.4.2"},{"id":"custom-types","version":"^0.18.3"}]}"""
                    .toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("probe.dat"))
            zip.write("payload".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return file
    }
}
