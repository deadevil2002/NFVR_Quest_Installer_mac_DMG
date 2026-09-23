import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for installed-mod version decisions and the staged
 * update transaction.
 *
 * A virtual Quest filesystem (in-memory fake ADB) proves: version
 * relations from pallet metadata only, staged transfer aside, verify
 * before swap, backup until final verification, rollback on failure,
 * and byte-exact final trees with no old/new merge.
 */
class ModUpdateFlowTest {
    private val packageId = ModPackageAnalyzer.BONELAB_PACKAGE_ID
    private val app = InstalledQuestApp(packageId, "1.2974.57485", 2974L)
    private val modRoot = "TestMod"
    private val base = "/sdcard/Android/data/$packageId/files/Mods"

    // ---- version compare ----

    @Test
    fun versionCompareFollowsNumericSegments() {
        assertEquals(0, compareModVersions("1.0.0", "1.0.0"))
        assertEquals(0, compareModVersions("1.2", "1.2.0"))
        assertTrue(compareModVersions("1.1.0", "1.0.9")!! > 0)
        assertTrue(compareModVersions("2.0", "10.0")!! < 0)
        assertTrue(compareModVersions("v1.3", "1.2.9")!! > 0)
        assertNull(compareModVersions(null, "1.0.0"))
        assertNull(compareModVersions("1.0.0", null))
        assertNull(compareModVersions("", "1.0.0"))
        assertNull(compareModVersions("beta", "1.0.0"))
        assertNull(compareModVersions("1.0.0", "1.0.x"))
        assertNull(compareModVersions("1.0.0.0.0.0.0.0.0", "1.0.0"))
    }

    // ---- pallet identity ----

    @Test
    fun palletIdentityRequiresBarcode() {
        val json = org.json.JSONObject(
            """{"root":{"ref":"1","type":"pallet#0"},"objects":{"1":{"barcode":"A.B","version":"1.2.3","title":"T","author":"Au"}}}"""
        )
        val identity = parseBonelabPalletIdentity(json, modRoot = "A.B", palletSha256 = "ab")
        assertTrue(identity != null)
        assertEquals("A.B", identity.barcode)
        assertEquals("1.2.3", identity.version)
        assertNull(parseBonelabPalletIdentity(org.json.JSONObject("{}")))
        assertNull(
            parseBonelabPalletIdentity(
                org.json.JSONObject("""{"root":{"ref":"1","type":"pallet#0"},"objects":{"1":{"version":"1"}}}""")
            )
        )
    }

    // ---- relations ----

    private fun identity(barcode: String, version: String?, sha: String? = null) =
        BonelabPalletIdentity(barcode, version, palletSha256 = sha, modRoot = modRoot)

    @Test
    fun relationsFollowEvidenceOnly() {
        assertEquals(
            InstalledModRelation.ABSENT,
            decideInstalledModRelation(identity("A.B", "1.0.0"), null)
        )
        assertEquals(
            InstalledModRelation.IDENTITY_MISMATCH,
            decideInstalledModRelation(identity("A.B", "1.0.0"), identity("X.Y", "1.0.0"))
        )
        assertEquals(
            InstalledModRelation.UNKNOWN_VERSION_RELATION,
            decideInstalledModRelation(identity("A.B", null), identity("A.B", "1.0.0"))
        )
        assertEquals(
            InstalledModRelation.UNKNOWN_VERSION_RELATION,
            decideInstalledModRelation(identity("A.B", "beta"), identity("A.B", "1.0.0"))
        )
        assertEquals(
            InstalledModRelation.NEWER_THAN_INSTALLED,
            decideInstalledModRelation(identity("A.B", "1.1.0"), identity("A.B", "1.0.9"))
        )
        assertEquals(
            InstalledModRelation.OLDER_THAN_INSTALLED,
            decideInstalledModRelation(identity("A.B", "1.0.0"), identity("A.B", "2.0.0"))
        )
        assertEquals(
            InstalledModRelation.SAME_VERSION,
            decideInstalledModRelation(identity("A.B", "1.0.0"), identity("A.B", "1.0.0"))
        )
        assertEquals(
            InstalledModRelation.IDENTICAL_CONTENT,
            decideInstalledModRelation(identity("A.B", "1.0.0", "aa"), identity("A.B", "1.0.0", "AA"))
        )
        assertEquals(
            InstalledModRelation.SAME_VERSION,
            decideInstalledModRelation(identity("A.B", "1.0.0", "aa"), identity("A.B", "1.0.0", "bb"))
        )
    }

    // ---- STARTING visibility ----

    @Test
    fun startingFeedbackShowsOnlyForAcceptedClick() {
        val ready = resolveModInstallActionState(
            analysis = null, operationBound = false, destinationConfirmed = false,
            installing = false, executionProgress = null, installSucceeded = false
        )
        assertEquals(ModInstallActionState.HIDDEN, ready)
        assertFalse(modInstallStartingVisible(ModInstallActionState.HIDDEN, false, true))
        assertFalse(modInstallStartingVisible(ModInstallActionState.READY, true, true))
        assertTrue(modInstallStartingVisible(ModInstallActionState.READY, false, true))
        assertFalse(modInstallStartingVisible(ModInstallActionState.READY, false, false))
    }

    // ---- assessment CTA suppression ----

    @Test
    fun existingInstallHidesNormalCtaUntilConfirmedUpdate() {
        val archive = palletArchive("1.1.0")
        try {
            val analysis = ModPackageAnalyzer().analyze(archive, app)
            assertTrue(analysis.installable)
            fun stateFor(relation: InstalledModRelation, confirmed: Boolean) =
                resolveModInstallActionState(
                    analysis.copy(
                        installedModAssessment = InstalledModAssessment(
                            relation = relation,
                            modRoot = modRoot,
                            archiveIdentity = analysis.bonelabIdentity!!,
                            installedIdentity = BonelabPalletIdentity("TestMod", "1.0.0")
                        )
                    ),
                    operationBound = true, destinationConfirmed = true,
                    installing = false, executionProgress = null, installSucceeded = false,
                    updateConfirmed = confirmed
                )
            assertEquals(ModInstallActionState.READY, stateFor(InstalledModRelation.ABSENT, false))
            assertEquals(ModInstallActionState.HIDDEN, stateFor(InstalledModRelation.SAME_VERSION, false))
            assertEquals(ModInstallActionState.HIDDEN, stateFor(InstalledModRelation.IDENTICAL_CONTENT, false))
            assertEquals(ModInstallActionState.HIDDEN, stateFor(InstalledModRelation.OLDER_THAN_INSTALLED, false))
            assertEquals(ModInstallActionState.HIDDEN, stateFor(InstalledModRelation.UNKNOWN_VERSION_RELATION, false))
            assertEquals(ModInstallActionState.HIDDEN, stateFor(InstalledModRelation.IDENTITY_MISMATCH, false))
            assertEquals(ModInstallActionState.HIDDEN, stateFor(InstalledModRelation.NEWER_THAN_INSTALLED, false))
            assertEquals(ModInstallActionState.READY, stateFor(InstalledModRelation.NEWER_THAN_INSTALLED, true))
        } finally {
            archive.delete()
        }
    }

    // ---- staged update lab ----

    private fun palletArchive(version: String, barcode: String = "TestMod"): File {
        val file = File.createTempFile("nfvr-update-", ".zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("$barcode/$barcode.pallet.json"))
            zip.write(
                ("""{"root":{"ref":"1","type":"pallet#0"},"objects":{"1":{"barcode":"""" +
                    barcode + """","title":"T","author":"A","version":"""" + version + """","sdkVersion":"1.2.0","crates":[]}}}""")
                    .toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("$barcode/a.bundle"))
            zip.write("bundle-$version".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return file
    }

    private fun managerFor(adb: UpdateLabAdb) = ModsManager(
        adb,
        StaticModLoaderDetector(ModLoaderDetection(app.packageName, emptyMap())),
        NoOpApkModLoaderPatcher
    )

    private fun confirmFor(assessment: InstalledModAssessment, sha256: String) =
        ModUpdateAuthorization(
            confirmationToken = modUpdateConfirmationToken(
                "SERIAL", packageId, assessment.modRoot, sha256,
                assessment.installedIdentity?.version, assessment.archiveIdentity.version.orEmpty()
            ),
            deviceSerial = "SERIAL",
            packageId = packageId,
            modRoot = assessment.modRoot,
            archiveSha256 = sha256,
            installedVersion = assessment.installedIdentity?.version,
            newVersion = assessment.archiveIdentity.version.orEmpty(),
            installedBarcode = assessment.installedIdentity?.barcode.orEmpty(),
            archiveBarcode = assessment.archiveIdentity.barcode
        )

    @Test
    fun newerVersionUpdatesAtomicallyWithoutMerge() = runBlocking {
        val old = palletArchive("1.0.0")
        val new = palletArchive("1.1.0")
        try {
            // Seed installed v1.0 plus a stale file that must disappear.
            val adb = UpdateLabAdb(packageId, modRoot)
            adb.seedDir("$base/$modRoot")
            adb.seedFile("$base/$modRoot/$modRoot.pallet.json", oldPalletBytes("1.0.0"))
            adb.seedFile("$base/$modRoot/a.bundle", "bundle-1.0.0".toByteArray())
            adb.seedFile("$base/$modRoot/stale-from-v1.bundle", "stale".toByteArray())
            val manager = managerFor(adb)
            val analysis = manager.analyzeModPackage("SERIAL", new, app)
            val assessment = manager.assessInstalledMod("SERIAL", analysis)
            assertTrue(assessment != null)
            assertEquals(InstalledModRelation.NEWER_THAN_INSTALLED, assessment.relation)
            val plan = analysis.installPlan.bindToDevice("SERIAL")
            val sha = plan.archiveIdentity!!.sha256
            val result = manager.executeInstallPlan(
                "SERIAL", new, plan,
                update = confirmFor(assessment, sha)
            )
            assertTrue(result.success, result.message)
            // Exact final tree: new files only, no stale leftovers (no merge).
            assertEquals(
                setOf(
                    "$base/$modRoot/$modRoot.pallet.json",
                    "$base/$modRoot/a.bundle"
                ),
                adb.filesUnder("$base/$modRoot")
            )
            assertFalse(adb.exists("$base/$modRoot/stale-from-v1.bundle"))
            // Temporary staging and backup are gone.
            assertFalse(adb.anyPathStartsWith("$base/.nfvr-update-"))
            assertFalse(adb.anyPathStartsWith("$base/.nfvr-backup-"))
            // Staged metadata proves the new version.
            assertEquals("1.1.0", adb.palletVersion("$base/$modRoot/$modRoot.pallet.json"))
        } finally {
            old.delete()
            new.delete()
        }
    }

    private fun oldPalletBytes(version: String): ByteArray =
        ("""{"root":{"ref":"1","type":"pallet#0"},"objects":{"1":{"barcode":"TestMod","title":"T","author":"A","version":"""" + version + """","sdkVersion":"1.2.0","crates":[]}}}""")
            .toByteArray(Charsets.UTF_8)

    @Test
    fun failedSwapRestoresPreviousVersion() = runBlocking {
        val new = palletArchive("1.1.0")
        try {
            val adb = UpdateLabAdb(packageId, modRoot, failFinalSwap = true)
            adb.seedDir("$base/$modRoot")
            adb.seedFile("$base/$modRoot/$modRoot.pallet.json", oldPalletBytes("1.0.0"))
            adb.seedFile("$base/$modRoot/a.bundle", "bundle-1.0.0".toByteArray())
            val manager = managerFor(adb)
            val analysis = manager.analyzeModPackage("SERIAL", new, app)
            val assessment = manager.assessInstalledMod("SERIAL", analysis)
            assertTrue(assessment != null)
            assertEquals(InstalledModRelation.NEWER_THAN_INSTALLED, assessment.relation)
            val plan = analysis.installPlan.bindToDevice("SERIAL")
            val result = manager.executeInstallPlan(
                "SERIAL", new, plan,
                update = confirmFor(assessment, plan.archiveIdentity!!.sha256)
            )
            assertFalse(result.success)
            assertTrue(result.message.contains("استعادة"), result.message)
            // Original v1.0 tree intact.
            assertEquals(
                setOf("$base/$modRoot/$modRoot.pallet.json", "$base/$modRoot/a.bundle"),
                adb.filesUnder("$base/$modRoot")
            )
            assertEquals("1.0.0", adb.palletVersion("$base/$modRoot/$modRoot.pallet.json"))
        } finally {
            new.delete()
        }
    }

    @Test
    fun normalInstallStillRefusesExistingFolder() = runBlocking {
        val new = palletArchive("1.1.0")
        try {
            val adb = UpdateLabAdb(packageId, modRoot)
            adb.seedDir("$base/$modRoot")
            adb.seedFile("$base/$modRoot/$modRoot.pallet.json", oldPalletBytes("1.0.0"))
            val manager = managerFor(adb)
            val analysis = manager.analyzeModPackage("SERIAL", new, app)
            val plan = analysis.installPlan.bindToDevice("SERIAL")
            // No update authorization: classic protection holds.
            val result = manager.executeInstallPlan("SERIAL", new, plan)
            assertFalse(result.success)
            assertTrue(
                result.message.contains("موجود مسبقًا") || result.message.contains("تعارض"),
                result.message
            )
            assertEquals("1.0.0", adb.palletVersion("$base/$modRoot/$modRoot.pallet.json"))
            assertFalse(adb.anyPathStartsWith("$base/.nfvr-update-"))
        } finally {
            new.delete()
        }
    }

    @Test
    fun mismatchedAuthorizationCannotUpdate() = runBlocking {
        val new = palletArchive("1.1.0")
        try {
            val adb = UpdateLabAdb(packageId, modRoot)
            adb.seedDir("$base/$modRoot")
            adb.seedFile("$base/$modRoot/$modRoot.pallet.json", oldPalletBytes("1.0.0"))
            val manager = managerFor(adb)
            val analysis = manager.analyzeModPackage("SERIAL", new, app)
            val assessment = manager.assessInstalledMod("SERIAL", analysis)
            assertTrue(assessment != null)
            val plan = analysis.installPlan.bindToDevice("SERIAL")
            val good = confirmFor(assessment, plan.archiveIdentity!!.sha256)
            val tampered = good.copy(newVersion = "9.9.9")
            val result = manager.executeInstallPlan("SERIAL", new, plan, update = tampered)
            assertFalse(result.success)
            assertEquals("1.0.0", adb.palletVersion("$base/$modRoot/$modRoot.pallet.json"))
        } finally {
            new.delete()
        }
    }

    /**
     * In-memory Quest filesystem for the update lab.  Paths are matched
     * literally (the production layer already quotes them).
     */
    private class UpdateLabAdb(
        private val packageId: String,
        private val modRoot: String,
        private val failFinalSwap: Boolean = false
    ) : AdbClient(BundledAdb(HostOs.LINUX)) {
        private val dirs = linkedSetOf<String>()
        private val files = linkedMapOf<String, ByteArray>()

        fun seedDir(path: String) {
            var current = ""
            for (segment in path.trim('/').split('/')) {
                current += "/$segment"
                dirs += current
            }
        }

        fun seedFile(path: String, bytes: ByteArray) {
            seedDir(path.substringBeforeLast('/'))
            files[path] = bytes
        }

        fun filesUnder(dir: String): Set<String> =
            files.keys.filter { it == dir || it.startsWith("$dir/") }.toSet()

        fun exists(path: String): Boolean = files.containsKey(path) || dirs.contains(path)

        fun anyPathStartsWith(prefix: String): Boolean =
            files.keys.any { it.startsWith(prefix) } || dirs.any { it.startsWith(prefix) }

        fun palletVersion(path: String): String? {
            val bytes = files[path] ?: return null
            return runCatching {
                val json = org.json.JSONObject(String(bytes, Charsets.UTF_8))
                json.optJSONObject("objects")?.optJSONObject("1")?.optString("version")
                    ?.trim()?.ifBlank { null }
            }.getOrNull()
        }

        private fun parentOf(path: String) = path.substringBeforeLast('/', "")

        override fun shell(serial: String, vararg args: String): CmdResult {
            val command = args.toList().map {
                var value = it.trim()
                if (value.length >= 2 && value.startsWith("'") && value.endsWith("'")) {
                    value = value.substring(1, value.length - 1).replace("'\\''", "'")
                }
                value
            }
            fun arg(index: Int) = command.getOrNull(index).orEmpty()
            return when {
                command == listOf("pm", "list", "packages", "-3", "-f") ->
                    CmdResult(0, "package:/data/app/$packageId/base.apk=$packageId\n", "")
                command == listOf("dumpsys", "package", packageId) ->
                    CmdResult(0, "versionName=1.2974.57485 versionCode=2974\n", "")
                command.firstOrNull() == "df" ->
                    CmdResult(0, "Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/fuse 99999999 1000 99998999 1% /storage/emulated\n", "")
                command.firstOrNull() == "test" && arg(1) == "!" && arg(2) == "-e" ->
                    CmdResult(if (exists(arg(3))) 1 else 0, "", "")
                command.firstOrNull() == "test" && arg(1) == "-d" ->
                    CmdResult(if (dirs.contains(arg(2))) 0 else 1, "", "")
                command.firstOrNull() == "test" && arg(1) == "-f" ->
                    CmdResult(if (files.containsKey(arg(2))) 0 else 1, "", "")
                command.firstOrNull() == "stat" -> {
                    val size = files[command.lastOrNull()]?.size
                        ?: return CmdResult(1, "", "missing")
                    CmdResult(0, "$size\n", "")
                }
                command.firstOrNull() == "mkdir" -> {
                    val path = arg(1)
                    if (exists(path)) return CmdResult(1, "", "File exists")
                    if (!dirs.contains(parentOf(path))) return CmdResult(1, "", "no parent")
                    dirs += path
                    CmdResult(0, "", "")
                }
                command.firstOrNull() == "mv" -> {
                    val src = arg(1)
                    val dst = arg(2)
                    if (failFinalSwap && src.contains(".nfvr-update-") && dst.endsWith("/$modRoot")) {
                        return CmdResult(1, "", "injected swap failure")
                    }
                    if (files.containsKey(src)) {
                        if (exists(dst)) return CmdResult(1, "", "destination exists")
                        files[dst] = files.remove(src)!!
                        return CmdResult(0, "", "")
                    }
                    if (dirs.contains(src)) {
                        if (exists(dst)) return CmdResult(1, "", "destination exists")
                        val movedDirs = dirs.filter { it == src || it.startsWith("$src/") }
                        val movedBytes = files.keys.filter { it.startsWith("$src/") }
                            .associateWith { files.getValue(it) }
                        movedDirs.forEach { dirs.remove(it) }
                        movedBytes.keys.forEach { files.remove(it) }
                        movedDirs.forEach { dirs += dst + it.removePrefix(src) }
                        movedBytes.forEach { (old, bytes) ->
                            files[dst + old.removePrefix(src)] = bytes
                        }
                        return CmdResult(0, "", "")
                    }
                    return CmdResult(1, "", "no such file")
                }
                command.firstOrNull() == "rm" -> {
                    val target = command.lastOrNull().orEmpty()
                    if (target == "/" || target.isBlank()) return CmdResult(1, "", "refusing")
                    files.keys.filter { it == target || it.startsWith("$target/") }
                        .forEach { files.remove(it) }
                    dirs.filter { it == target || it.startsWith("$target/") }
                        .forEach { dirs.remove(it) }
                    CmdResult(0, "", "")
                }
                command.firstOrNull() == "find" -> {
                    val dir = command.getOrNull(1).orEmpty()
                    val maxdepth = command.indexOf("-maxdepth").takeIf { it >= 0 }
                        ?.let { command.getOrNull(it + 1)?.toIntOrNull() }
                    val namePattern = command.indexOf("-name").takeIf { it >= 0 }
                        ?.let { command.getOrNull(it + 1) }
                    val typeFile = command.contains("-type") &&
                        command.getOrNull(command.indexOf("-type") + 1) == "f"
                    val pipe = command.indexOf("|").takeIf { it >= 0 }
                    val listed = (dirs.filter { it == dir || it.startsWith("$dir/") } +
                        files.keys.filter { it.startsWith("$dir/") })
                        .filter { path ->
                            maxdepth == null || depthUnder(path, dir) <= maxdepth
                        }
                        .filter { path ->
                            namePattern == null ||
                                path.substringAfterLast('/').matches(
                                    namePattern.trim('\'').replace(".", "\\.").replace("*", ".*").toRegex()
                                )
                        }
                    if (pipe != null) {
                        CmdResult(0, "${listed.count { files.containsKey(it) }}\n", "")
                    } else {
                        CmdResult(0, listed.joinToString("\n", postfix = "\n"), "")
                    }
                }
                else -> CmdResult(0, "", "")
            }
        }

        private fun depthUnder(path: String, dir: String): Int {
            val relative = path.removePrefix("$dir/").trim('/')
            if (relative.isBlank() || relative == path) return Int.MAX_VALUE
            return relative.split('/').size
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
            files[toDevicePath] = from.readBytes()
            onProgress(from.length(), from.length())
            return CmdResult(0, "", "")
        }
    }
}
