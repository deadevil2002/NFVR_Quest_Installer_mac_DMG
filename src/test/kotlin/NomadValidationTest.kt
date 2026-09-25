import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for Blade & Sorcery: Nomad validation.
 *
 * The manifest GameVersion tracks the ThunderRoad content generation
 * (1.0.x for the U12+ era), never the Meta store version.  Managed
 * script assemblies must be validated PE/CLI, and their Quest runtime
 * support stays blocked until proven on device.
 */
class NomadValidationTest {
    private val nomad131 = InstalledQuestApp(
        ModPackageAnalyzer.NOMAD_PACKAGE_ID, "1.3.1", 260730005L
    )
    private val nomad107 = InstalledQuestApp(
        ModPackageAnalyzer.NOMAD_PACKAGE_ID, "1.0.7", 682L
    )

    // ---- generation evaluation ----

    @Test
    fun generationRuleAcceptsCurrentEraRejectsLegacy() {
        val current = NomadCompatibilityEvaluator.evaluateForGeneration("1.0.0.0", 1, 0)
        assertEquals(NomadCompatibilityState.COMPATIBLE_FAMILY, current.state)
        assertTrue(current.installAllowed)
        val legacy = NomadCompatibilityEvaluator.evaluateForGeneration("0.11.0.0", 1, 0)
        assertEquals(NomadCompatibilityState.INCOMPATIBLE, legacy.state)
        assertFalse(legacy.installAllowed)
        val future = NomadCompatibilityEvaluator.evaluateForGeneration("2.0.0.0", 1, 0)
        assertEquals(NomadCompatibilityState.INCOMPATIBLE, future.state)
        val malformed = NomadCompatibilityEvaluator.evaluateForGeneration("beta", 1, 0)
        assertEquals(NomadCompatibilityState.WARNING, malformed.state)
        assertFalse(malformed.installAllowed)
        val missing = NomadCompatibilityEvaluator.evaluateForGeneration(null, 1, 0)
        assertEquals(NomadCompatibilityState.WARNING, missing.state)
    }

    @Test
    fun profileCarriesEvidencedGeneration() {
        val profile = GameModProfileRegistry.findByPackageId(ModPackageAnalyzer.NOMAD_PACKAGE_ID)
        assertTrue(profile != null)
        assertEquals("1.0", profile.versionRules["thunderRoadGeneration"])
        assertTrue(profile.supportsVersion(nomad131))
        assertTrue(profile.supportsVersion(nomad107))
    }

    // ---- fixtures ----

    private fun manifest(name: String, modVersion: String, gameVersion: String?): String {
        val game = if (gameVersion == null) "" else ""","GameVersion":"$gameVersion""""
        return """{"Name":"$name","Description":"D","Author":"A","ModVersion":"$modVersion"$game,"Thumbnail":""}"""
    }

    private fun contentArchive(
        root: String = "MarvelMod",
        gameVersion: String? = "1.0.0.0",
        extra: List<Pair<String, String>> = emptyList()
    ): File {
        val entries = mutableListOf(
            "$root/manifest.json" to manifest("MarvelMod", "1.0", gameVersion),
            "$root/catalog_MarvelMod.json" to """{"modules":[]}""",
            "$root/marvel_assets_all.bundle" to "bundle-bytes"
        )
        entries.addAll(extra)
        return zipOf(*entries.toTypedArray())
    }

    private fun managedCliBytes(): ByteArray {
        // Minimal PE32 with a non-empty CLI header directory: satisfies
        // isManagedPeCli without a real assembly.
        val bytes = ByteArray(512)
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        fun le32(offset: Int, value: Int) {
            for (shift in 0 until 4) bytes[offset + shift] = ((value ushr (shift * 8)) and 0xff).toByte()
        }
        fun le16(offset: Int, value: Int) {
            bytes[offset] = (value and 0xff).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
        }
        le32(0x3c, 64)
        bytes[64] = 'P'.code.toByte()
        bytes[65] = 'E'.code.toByte()
        bytes[66] = 0
        bytes[67] = 0
        le16(64 + 24, 0x10b)
        val directory = 64 + 24 + 96
        val cli = directory + 14 * 8
        le32(cli, 0x2000)
        le32(cli + 4, 72)
        return bytes
    }

    private fun scriptArchive(
        root: String = "ForceMod",
        gameVersion: String? = "1.0.0.0",
        dllBytes: ByteArray? = null
    ): File {
        val file = File.createTempFile("nfvr-nomad-script-", ".zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("$root/manifest.json"))
            zip.write(manifest("ForceMod", "5.0", gameVersion).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("$root/ForceMod.dll"))
            zip.write(dllBytes ?: managedCliBytes())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("$root/content.bundle"))
            zip.write("bundle".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return file
    }

    // ---- content classification ----

    @Test
    fun validContentModInstallsOnCurrentEra() {
        val archive = contentArchive()
        try {
            val analysis = ModPackageAnalyzer().analyze(archive, nomad131)
            assertEquals(ModPackageType.KNOWN_GAME_PROFILE, analysis.packageType)
            assertTrue(analysis.installable, analysis.message)
            assertEquals(ModInstallOutcome.DIRECT_INSTALL_READY, analysis.outcome)
            assertTrue(
                analysis.installPlan.preconditions.any {
                    it.satisfied && it.code == "NOMAD_GAME_VERSION_COMPATIBILITY_WARNING"
                }
            )
            assertTrue(analysis.nomadIdentity != null)
            assertEquals("MarvelMod", analysis.nomadIdentity?.name)
            assertEquals("MarvelMod", analysis.nomadIdentity?.modRoot)
        } finally {
            archive.delete()
        }
    }

    @Test
    fun legacyEraGameVersionBlocked() {
        val archive = contentArchive(gameVersion = "0.11.0.0")
        try {
            val analysis = ModPackageAnalyzer().analyze(archive, nomad131)
            assertFalse(analysis.installable)
            assertTrue(
                analysis.installPlan.preconditions.any {
                    !it.satisfied && it.code == "GAME_VERSION_UNSUPPORTED"
                }
            )
        } finally {
            archive.delete()
        }
    }

    @Test
    fun missingGameVersionNotRecognizedAsNomadContent() {
        // The SDK template always writes GameVersion; without it the
        // archive carries no Nomad generation evidence and must not
        // route into the Nomad content installer.
        val archive = contentArchive(gameVersion = null)
        try {
            val analysis = ModPackageAnalyzer().analyze(archive, nomad131)
            assertFalse(analysis.installable, analysis.message)
            assertFalse(
                analysis.packageType == ModPackageType.KNOWN_GAME_PROFILE && analysis.installable
            )
        } finally {
            archive.delete()
        }
    }

    @Test
    fun modsWrapperPrefixNormalizes() {
        val archive = zipOf(
            "Mods/MarvelMod/manifest.json" to manifest("MarvelMod", "1.0", "1.0.0.0"),
            "Mods/MarvelMod/content.bundle" to "bundle"
        )
        try {
            val analysis = ModPackageAnalyzer().analyze(archive, nomad131)
            assertTrue(analysis.installable, analysis.message)
            assertTrue(
                analysis.installPlan.mappings.all {
                    it.destinationPath.startsWith(
                        "/sdcard/Android/data/com.Warpfrog.BladeAndSorcery/files/Mods/MarvelMod/"
                    )
                }
            )
        } finally {
            archive.delete()
        }
    }

    @Test
    fun multipleRootsBlocked() {
        val archive = zipOf(
            "ModA/manifest.json" to manifest("ModA", "1.0", "1.0.0.0"),
            "ModA/a.bundle" to "bundle",
            "ModB/manifest.json" to manifest("ModB", "1.0", "1.0.0.0"),
            "ModB/b.bundle" to "bundle"
        )
        try {
            val analysis = ModPackageAnalyzer().analyze(archive, nomad131)
            assertFalse(analysis.installable)
            assertTrue(
                analysis.installPlan.preconditions.any { it.code == "MOD_FOLDER_REQUIRED" }
            )
        } finally {
            archive.delete()
        }
    }

    @Test
    fun unrelatedFilesBlocked() {
        val archive = contentArchive(
            extra = listOf("Unrelated/readme.txt" to "hello")
        )
        try {
            val analysis = ModPackageAnalyzer().analyze(archive, nomad131)
            assertFalse(analysis.installable)
            assertTrue(
                analysis.installPlan.preconditions.any { it.code == "UNRELATED_ARCHIVE_CONTENT" }
            )
            assertFalse(analysis.installPlan.mappings.any { it.sourcePath.startsWith("Unrelated/") })
        } finally {
            archive.delete()
        }
    }

    @Test
    fun traversalBlocked() {
        val archive = zipOf("../escape.txt" to "evil")
        try {
            val analysis = ModPackageAnalyzer().analyze(archive, nomad131)
            assertFalse(analysis.installable)
            assertEquals(ModInstallOutcome.UNSAFE_ARCHIVE, analysis.outcome)
        } finally {
            archive.delete()
        }
    }

    @Test
    fun pcOnlyPayloadBlocked() {
        val archive = zipOf(
            "pc/manifest.json" to manifest("PcMod", "1.0", "1.0.0.0"),
            "pc/game.dll" to "native"
        )
        try {
            val analysis = ModPackageAnalyzer().analyze(archive, nomad131)
            assertFalse(analysis.installable, analysis.message)
        } finally {
            archive.delete()
        }
    }

    // ---- script mods ----

    @Test
    fun validatedScriptModInstallsWithoutExternalLoader() {
        // ThunderRoad loads managed mod assemblies natively: a validated
        // PE/CLI DLL in the current generation needs no external loader.
        // Spoofed or foreign payloads stay blocked by their own gates.
        val archive = scriptArchive()
        try {
            val analysis = ModPackageAnalyzer().analyze(archive, nomad131)
            assertEquals(ModPackageType.KNOWN_GAME_PROFILE, analysis.packageType)
            assertTrue(analysis.installable, analysis.message)
            assertEquals(ModInstallOutcome.DIRECT_INSTALL_READY, analysis.outcome)
            assertTrue(analysis.installPlan.loaderRequirement == null)
            assertTrue(
                analysis.installPlan.mappings.any { it.sourcePath.endsWith("ForceMod.dll") }
            )
        } finally {
            archive.delete()
        }
    }

    @Test
    fun spoofedNativeDllBlockedAsInvalidCode() {
        val archive = scriptArchive(dllBytes = "not-a-real-assembly".toByteArray())
        try {
            val analysis = ModPackageAnalyzer().analyze(archive, nomad131)
            assertFalse(analysis.installable)
            assertTrue(
                analysis.installPlan.preconditions.any {
                    !it.satisfied && it.code == "INVALID_MANAGED_CODE"
                }
            )
        } finally {
            archive.delete()
        }
    }

    @Test
    fun smuggledNativeLibraryBlocked() {
        val file = File.createTempFile("nfvr-nomad-native-", ".zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("ForceMod/manifest.json"))
            zip.write(manifest("ForceMod", "5.0", "1.0.0.0").toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("ForceMod/ForceMod.dll"))
            zip.write(managedCliBytes())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("ForceMod/content.bundle"))
            zip.write("bundle".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("Other/arm64cuts.so"))
            zip.write("native".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        try {
            val analysis = ModPackageAnalyzer().analyze(file, nomad131)
            assertFalse(analysis.installable)
            assertTrue(
                analysis.installPlan.preconditions.any {
                    !it.satisfied && it.code == "NOMAD_NATIVE_PAYLOAD"
                }
            )
        } finally {
            file.delete()
        }
    }

    // ---- installed assessment ----

    @Test
    fun nomadRelationsFollowManifestEvidence() {
        fun identity(name: String, author: String?, version: String?) =
            NomadModIdentity(name, author, version, "1.0.0.0", "M")
        assertEquals(
            InstalledModRelation.ABSENT,
            decideNomadInstalledRelation(identity("M", "A", "1.0"), null)
        )
        assertEquals(
            InstalledModRelation.SAME_VERSION,
            decideNomadInstalledRelation(identity("M", "A", "1.0"), identity("M", "A", "1.0"))
        )
        assertEquals(
            InstalledModRelation.NEWER_THAN_INSTALLED,
            decideNomadInstalledRelation(identity("M", "A", "5.0"), identity("M", "A", "1.0"))
        )
        assertEquals(
            InstalledModRelation.OLDER_THAN_INSTALLED,
            decideNomadInstalledRelation(identity("M", "A", "1.0"), identity("M", "A", "5.0"))
        )
        assertEquals(
            InstalledModRelation.UNKNOWN_VERSION_RELATION,
            decideNomadInstalledRelation(identity("M", "A", null), identity("M", "A", "1.0"))
        )
        assertEquals(
            InstalledModRelation.IDENTITY_MISMATCH,
            decideNomadInstalledRelation(identity("M", "A", "1.0"), identity("Other", "A", "1.0"))
        )
        assertEquals(
            InstalledModRelation.IDENTITY_MISMATCH,
            decideNomadInstalledRelation(identity("M", "A", "1.0"), identity("M", "B", "1.0"))
        )
        // Missing author on either side does not force a mismatch.
        assertEquals(
            InstalledModRelation.SAME_VERSION,
            decideNomadInstalledRelation(identity("M", null, "1.0"), identity("M", "A", "1.0"))
        )
    }

    @Test
    fun nomadAssessmentReadsInstalledManifests() = runBlocking {
        val archive = contentArchive(root = "MarvelMod")
        try {
            val adb = NomadLabAdb(
                installed = mapOf(
                    "MarvelMod" to """{"Name":"MarvelMod","Author":"A","ModVersion":"0.9","GameVersion":"1.0.0.0"}""",
                    "OtherMod" to """{"Name":"OtherMod","Author":"B","ModVersion":"1.0","GameVersion":"1.0.0.0"}"""
                )
            )
            val manager = ModsManager(
                adb,
                StaticModLoaderDetector(ModLoaderDetection(nomad131.packageName, emptyMap())),
                NoOpApkModLoaderPatcher
            )
            val analysis = manager.analyzeModPackage("SERIAL", archive, nomad131)
            val assessment = manager.assessInstalledNomadMod("SERIAL", analysis)
            assertTrue(assessment != null)
            assertEquals(InstalledModRelation.NEWER_THAN_INSTALLED, assessment.relation)
            assertEquals("MarvelMod", assessment.modRoot)
            assertEquals("0.9", assessment.installedIdentity?.modVersion)
            assertEquals("1.0", assessment.archiveIdentity.modVersion)
            // CTA stays hidden: Nomad has no staged-update execution yet.
            assertEquals(
                ModInstallActionState.HIDDEN,
                resolveModInstallActionState(
                    analysis.copy(nomadAssessment = assessment),
                    operationBound = true, destinationConfirmed = true,
                    installing = false, executionProgress = null, installSucceeded = false
                )
            )
        } finally {
            archive.delete()
        }
    }

    @Test
    fun nomadAbsentInstallStaysNormal() = runBlocking {
        val archive = contentArchive(root = "MarvelMod")
        try {
            val adb = NomadLabAdb(installed = emptyMap())
            val manager = ModsManager(
                adb,
                StaticModLoaderDetector(ModLoaderDetection(nomad131.packageName, emptyMap())),
                NoOpApkModLoaderPatcher
            )
            val analysis = manager.analyzeModPackage("SERIAL", archive, nomad131)
            val assessment = manager.assessInstalledNomadMod("SERIAL", analysis)
            assertTrue(assessment != null)
            assertEquals(InstalledModRelation.ABSENT, assessment.relation)
        } finally {
            archive.delete()
        }
    }

    @Test
    fun nomadNormalInstallProtectedAndVerified() = runBlocking {
        val archive = contentArchive(root = "MarvelMod")
        try {
            val adb = NomadLabAdb(installed = emptyMap())
            val manager = ModsManager(
                adb,
                StaticModLoaderDetector(ModLoaderDetection(nomad131.packageName, emptyMap())),
                NoOpApkModLoaderPatcher
            )
            val analysis = manager.analyzeModPackage("SERIAL", archive, nomad131)
            assertTrue(analysis.installable, analysis.message)
            val plan = analysis.installPlan.bindToDevice("SERIAL")
            val result = manager.executeInstallPlan("SERIAL", archive, plan)
            assertTrue(result.success, result.message)
            assertEquals(plan.mappings.map { it.destinationPath }.toSet(), adb.verifiedFiles)
            assertTrue(adb.pushCalls == plan.mappings.size)
        } finally {
            archive.delete()
        }
    }

    @Test
    fun nomadExistingFolderRefusesSilentOverwrite() = runBlocking {
        val archive = contentArchive(root = "MarvelMod")
        try {
            val adb = NomadLabAdb(
                installed = mapOf(
                    "MarvelMod" to """{"Name":"MarvelMod","Author":"A","ModVersion":"1.0","GameVersion":"1.0.0.0"}"""
                )
            )
            val manager = ModsManager(
                adb,
                StaticModLoaderDetector(ModLoaderDetection(nomad131.packageName, emptyMap())),
                NoOpApkModLoaderPatcher
            )
            val analysis = manager.analyzeModPackage("SERIAL", archive, nomad131)
            val plan = analysis.installPlan.bindToDevice("SERIAL")
            val result = manager.executeInstallPlan("SERIAL", archive, plan)
            assertFalse(result.success)
            assertEquals(0, adb.pushCalls)
            assertEquals("1.0", adb.installedManifestVersion("MarvelMod"))
        } finally {
            archive.delete()
        }
    }

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("nfvr-nomad-", ".zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    /**
     * Minimal virtual Nomad device: seeded mod folders with manifest
     * bytes, package-bound test/stat, exact-size verification.
     */
    private class NomadLabAdb(
        private val installed: Map<String, String>
    ) : AdbClient(BundledAdb(HostOs.LINUX)) {
        private val modRootBase =
            "/sdcard/Android/data/com.Warpfrog.BladeAndSorcery/files/Mods"
        private val files = linkedMapOf<String, ByteArray>()
        val verifiedFiles = linkedSetOf<String>()
        var pushCalls = 0

        init {
            installed.forEach { (folder, manifest) ->
                files["$modRootBase/$folder/manifest.json"] = manifest.toByteArray(Charsets.UTF_8)
            }
        }

        fun installedManifestVersion(folder: String): String? {
            val bytes = files["$modRootBase/$folder/manifest.json"] ?: return null
            return runCatching {
                org.json.JSONObject(String(bytes, Charsets.UTF_8)).optString("ModVersion")
            }.getOrNull()
        }

        private fun unq(raw: String): String {
            val value = raw.trim()
            return if (value.length >= 2 && value.startsWith("'") && value.endsWith("'")) {
                value.substring(1, value.length - 1).replace("'\\''", "'")
            } else value
        }

        override fun shell(serial: String, vararg args: String): CmdResult {
            val command = args.toList().map(::unq)
            fun arg(index: Int) = command.getOrNull(index).orEmpty()
            return when {
                command == listOf("pm", "list", "packages", "-3", "-f") ->
                    CmdResult(0, "package:/data/app/com.Warpfrog.BladeAndSorcery/base.apk=com.Warpfrog.BladeAndSorcery\n", "")
                command == listOf("dumpsys", "package", "com.Warpfrog.BladeAndSorcery") ->
                    CmdResult(0, "versionName=1.3.1 versionCode=260730005\n", "")
                command.firstOrNull() == "df" ->
                    CmdResult(0, "Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/fuse 99999999 1000 99998999 1% /storage/emulated\n", "")
                command.firstOrNull() == "ls" ->
                    CmdResult(0, installed.keys.joinToString("\n", postfix = "\n"), "")
                command.firstOrNull() == "test" && arg(1) == "!" && arg(2) == "-e" ->
                    CmdResult(if (files.containsKey(arg(3)) || isDir(arg(3))) 1 else 0, "", "")
                command.firstOrNull() == "test" && arg(1) == "-d" ->
                    CmdResult(if (isDir(arg(2))) 0 else 1, "", "")
                command.firstOrNull() == "test" && arg(1) == "-f" ->
                    CmdResult(if (files.containsKey(arg(2))) 0 else 1, "", "")
                command.firstOrNull() == "stat" -> {
                    val path = command.lastOrNull().orEmpty()
                    val size = files[path]?.size ?: return CmdResult(1, "", "missing")
                    verifiedFiles += path
                    CmdResult(0, "$size\n", "")
                }
                command.firstOrNull() == "mkdir" -> {
                    val path = arg(1)
                    if (files.containsKey(path) || isDir(path)) return CmdResult(1, "", "exists")
                    dirsAdd(path)
                    CmdResult(0, "", "")
                }
                command.firstOrNull() == "mv" -> {
                    val src = arg(1)
                    val dst = arg(2)
                    if (files.containsKey(src)) {
                        files[dst] = files.remove(src)!!
                        return CmdResult(0, "", "")
                    }
                    return CmdResult(1, "", "no such file")
                }
                command.firstOrNull() == "rm" -> CmdResult(0, "", "")
                command.firstOrNull() == "find" -> {
                    val dir = arg(1)
                    CmdResult(0, files.keys.filter { it.startsWith("$dir/") }.joinToString("\n", postfix = "\n"), "")
                }
                else -> CmdResult(0, "", "")
            }
        }

        private val extraDirs = linkedSetOf<String>()
        private fun isDir(path: String): Boolean {
            if (extraDirs.contains(path)) return true
            if (files.keys.any { it == path || it.startsWith("$path/") }) return true
            return path == modRootBase
        }

        private fun dirsAdd(path: String) {
            extraDirs += path
        }

        override fun pullReadOnly(serial: String, remotePath: String, localFile: File): CmdResult {
            val bytes = files[remotePath] ?: return CmdResult(1, "", "missing")
            localFile.writeBytes(bytes)
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
            files[toDevicePath] = from.readBytes()
            onProgress(from.length(), from.length())
            return CmdResult(0, "", "")
        }
    }
}
