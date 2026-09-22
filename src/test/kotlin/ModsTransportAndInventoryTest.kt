import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
        assertFalse(AndroidPathValidator.isSafe("/sdcard/a;b"))
        assertFalse(AndroidPathValidator.isSafe("/sdcard/a&b"))
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
