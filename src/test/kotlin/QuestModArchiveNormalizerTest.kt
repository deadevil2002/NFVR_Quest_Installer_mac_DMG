import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * These are sanitized structural fixtures derived from the four supplied
 * archives.  They intentionally contain no customer bundles/scripts and are
 * small enough to exercise the central-directory/metadata path only.
 */
class QuestModArchiveNormalizerTest {
    private val analyzer = ModPackageAnalyzer()

    private val bonelab = InstalledQuestApp(
        packageName = "com.StressLevelZero.BONELAB",
        versionName = "1.2974.57485",
        versionCode = 2974L
    )
    private val nomad = InstalledQuestApp(
        packageName = "com.Warpfrog.BladeAndSorcery",
        versionName = "1.0.7",
        versionCode = 682L
    )

    @Test
    fun normalPapaRootScopedPalletAndCatalogAreContentEvidence() {
        val archive = zipOf(
            "NormalPapa.BLPlane/NormalPapa.BLPlane.pallet.json" to
                """{"version":2,"root":{"ref":"1","type":"pallet#0"},"objects":{"1":{"barcode":"NormalPapa.BLPlane","title":"BL_Plane","author":"Normal Papa","version":"0.0.0","sdkVersion":"1.2.0","crates":[{"ref":"2","type":"crate-level#0"}]}}}""",
            "NormalPapa.BLPlane/catalog_NormalPapa.BLPlane.json" to
                """{"m_InternalIds":["Assets/_MarrowAssets/Levels/BL_Plane(Night).unity","PALLET_BARCODE:NormalPapa.BLPlane:\\blplane_levels_scenes_level/bl_plane(night).bundle"]}""",
            "NormalPapa.BLPlane/blplane_levels_scenes_level/bl_plane(night).bundle" to "bundle",
            "NormalPapa.BLPlane/NormalPapa.BLPlane_monoscripts.bundle" to "bundle"
        )

        val tree = QuestModArchiveNormalizer.normalize(archive)
        assertEquals("NormalPapa.BLPlane", tree.normalizedRoot)
        assertEquals(1, tree.roots.count { it.kind == QuestModArchiveRootKind.MOD_ROOT })
        assertTrue(tree.metadata.keys.any { it.endsWith(".pallet.json") })
        assertTrue(tree.metadata.keys.any { it.contains("catalog_") })

        val standalone = analyzer.analyze(archive, bonelab)
        assertTrue(standalone.recognized)
        val missingRoot = analyzer.analyze(
            archive,
            bonelab,
            directoryDiscovery = verifiedDiscovery(bonelab).copy(
                candidates = verifiedDiscovery(bonelab).candidates.map { it.copy(exists = false) }
            )
        )
        assertFalse(missingRoot.installable)
        assertTrue(missingRoot.installPlan.preconditions.any {
            it.code == "MOD_DESTINATION_REQUIRED"
        })
        val analysis = analyzeAfterRename(archive, bonelab, verifiedDiscovery(bonelab))
        assertEquals(ModPackageType.BONELAB_NATIVE_CONTENT, analysis.packageType)
        assertTrue(analysis.installable, analysis.message)
        assertTrue(
            analysis.installPlan.mappings.all {
                it.destinationPath.startsWith(
                    "/sdcard/Android/data/com.StressLevelZero.BONELAB/files/Mods/NormalPapa.BLPlane/"
                )
            }
        )
        assertTrue(analysis.installPlan.mappings.any {
            it.sourcePath.contains("(night).bundle")
        })
    }

    @Test
    fun frameworkPackManagedAssemblyIsNomadContentNotAssumedExternalLoader() {
        val archive = zipOf(
            "Framework Pack/manifest.json" to
                """{"GameVersion":"1.0.0.0","GameName":"Blade & Sorcery: Nomad"}""",
            "Framework Pack/Framework Pack.dll" to "sanitized-managed-assembly",
            "Framework Pack/Framework Pack.pdb" to "sanitized-symbols"
        )

        val analysis = analyzeAfterRename(archive, nomad, verifiedDiscovery(nomad))
        assertEquals(ModPackageType.KNOWN_GAME_PROFILE, analysis.packageType)
        assertTrue(analysis.installable, analysis.message)
        assertEquals(ModInstallOutcome.DIRECT_INSTALL_READY, analysis.outcome)
        assertTrue(
            analysis.installPlan.preconditions.any {
                it.code == "NOMAD_GAME_VERSION_COMPATIBILITY_WARNING" && it.satisfied
            }
        )
        assertEquals(
            "/sdcard/Android/data/com.Warpfrog.BladeAndSorcery/files/Mods",
            analysis.installPlan.destinationRoot
        )
        assertTrue(analysis.installPlan.loaderRequirement == null)
        assertEquals(
            setOf(
                "Framework Pack/manifest.json",
                "Framework Pack/Framework Pack.dll",
                "Framework Pack/Framework Pack.pdb"
            ),
            analysis.installPlan.mappings.map { it.destinationPath.substringAfterLast("/Mods/") }.toSet()
        )
    }

    @Test
    fun gorillaVirtualStumpFixturesExposeMultipleRootsAndDoNotBecomeGenericCopy() {
        val names = listOf(
            "GorillaTagCustomMap12a_win64",
            "GorillaTagCustomMap12a_android"
        )
        val packageJson = """{"pcFileName":"${names[0]}","androidFileName":"${names[1]}","descriptor":null,"initialScene":"","initialScenes":["Scene"],"customMapSupportVersion":5,"maxPlayers":10,"availableGameModes":[7],"defaultGameMode":7}"""
        val archive = zipOf(
            names[0] to "pc-placeholder",
            names[1] to "android-placeholder",
            "package.json" to packageJson
        )

        val tree = QuestModArchiveNormalizer.normalize(archive)
        assertFalse(tree.hasMultipleRoots)
        assertEquals(1, tree.roots.count { it.path.isEmpty() })
        assertEquals(1, tree.roots.count { it.kind == QuestModArchiveRootKind.PACKAGING_METADATA })
        assertEquals(null, tree.normalizedRoot)

        val analysis = analyzeAfterRename(
            archive,
            InstalledQuestApp("com.AnotherAxiom.GorillaTag", "1.1.145", 29679L)
        )
        assertEquals(ModPackageType.GORILLA_TAG_VIRTUAL_STUMP, analysis.packageType)
        assertFalse(analysis.installable)
        assertEquals(ModInstallOutcome.BUILT_IN_GAME_CONTENT, analysis.outcome)
        assertTrue(customerAnalysisMessage(analysis).contains("مستورد"))
    }

    @Test
    fun barkVirtualStumpPreservesItsDifferentBranchAfterArchiveRename() {
        val archive = zipOf(
            "bark vs_win64" to "pc-placeholder",
            "bark vs_android" to "android-placeholder",
            "package.json" to
                """{"pcFileName":"bark vs_win64","androidFileName":"bark vs_android","descriptor":null,"initialScene":"","initialScenes":["Forest"],"customMapSupportVersion":5,"maxPlayers":10,"availableGameModes":[7],"defaultGameMode":7}"""
        )

        val analysis = analyzeAfterRename(
            archive,
            InstalledQuestApp("com.AnotherAxiom.GorillaTag", "1.1.145", 29679L)
        )
        assertEquals(ModPackageType.GORILLA_TAG_VIRTUAL_STUMP, analysis.packageType)
        assertFalse(analysis.installable)
        assertTrue(analysis.archiveTree?.roots?.any { it.path.isEmpty() } == true)
        assertTrue(customerAnalysisMessage(analysis).contains("مستورد"))
    }

    @Test
    fun nestedArchivesAreReportedButNeverFlattened() {
        val archive = zipOf(
            "MyMod/manifest.json" to """{"name":"sanitized"}""",
            "MyMod/dependencies.json" to """{"framework":"1.x"}""",
            "MyMod/optional-content.zip" to "nested-placeholder"
        )

        val tree = QuestModArchiveNormalizer.normalize(archive)
        assertEquals("MyMod", tree.wrapperRoot)
        assertEquals(listOf("MyMod/dependencies.json"), tree.dependencyPaths)
        assertEquals(listOf("MyMod/optional-content.zip"), tree.nestedArchivePaths)
        assertTrue(tree.hasNestedArchives)
    }

    @Test
    fun directRootPayloadUsesVirtualRootWithoutInventingBundleWrapper() {
        val tree = QuestModArchiveNormalizer.normalize(
            zipOf(
                "manifest.json" to """{"name":"root mod"}""",
                "content.bundle" to "bundle"
            )
        )
        assertEquals(null, tree.wrapperRoot)
        assertEquals(listOf(""), tree.roots.filter { it.kind == QuestModArchiveRootKind.MOD_ROOT }.map { it.path })
    }

    @Test
    fun multipleDirectoryRootsRemainDistinctAndNestedContentBlocksInstall() {
        val roots = QuestModArchiveNormalizer.normalize(
            zipOf(
                "One/content.bundle" to "one",
                "Two/content.bundle" to "two"
            )
        )
        assertTrue(roots.hasMultipleRoots)
        assertEquals(setOf("One", "Two"), roots.roots.map { it.path }.toSet())

        val archive = zipOf(
            "mod.json" to """{"packageId":"com.StressLevelZero.BONELAB","modFiles":["payload.dat"]}""",
            "payload.dat" to "payload",
            "dependencies.json" to """{"dependencies":[{"id":"framework","version":"1.0"}]}""",
            "nested.zip" to "nested"
        )
        val analysis = analyzer.analyze(archive, bonelab)
        assertFalse(analysis.installable)
        assertTrue(analysis.installPlan.preconditions.any {
            it.code == "NESTED_ARCHIVE_REQUIRES_REVIEW"
        })
        assertTrue(analysis.installPlan.dependencies.any { it.id == "framework" })
        assertTrue(customerAnalysisMessage(analysis).contains("ZIP"))
    }

    @Test
    fun traversalAndAbsolutePathsAreRejectedBeforeClassification() {
        val traversal = zipOf("../escape.dat" to "unsafe")
        val absolute = zipOf("/absolute.dat" to "unsafe")
        try {
            assertFailsUnsafe(traversal)
            assertFailsUnsafe(absolute)
            assertEquals(
                ModInstallOutcome.UNSAFE_ARCHIVE,
                analyzer.analyze(traversal, bonelab).outcome
            )
        } finally {
            traversal.delete()
            absolute.delete()
        }
    }

    /**
     * Opt-in acceptance harness for the actual supplied archives.  The ZIPs
     * stay outside source control; set NFVR_REAL_ARCHIVE_FIXTURES to 1 (from
     * the workspace root) or to a directory containing them.  This emits the
     * analyzer's real classification, root tree, plan destination, and
     * blocking codes rather than relying on a hand-written expected report.
     */
    @Test
    fun realAttachedArchivesAreAnalyzedOnlyWhenExplicitlyEnabled() {
        val configured = System.getenv("NFVR_REAL_ARCHIVE_FIXTURES")?.trim().orEmpty()
        if (configured.isBlank() || configured == "0") return
        val root = if (configured == "1") {
            val cwd = File(System.getProperty("user.dir"))
            (if (cwd.resolve("attached_assets").isDirectory) {
                cwd.resolve("attached_assets")
            } else {
                cwd.resolve("../attached_assets")
            }).canonicalFile
        } else {
            File(configured).canonicalFile
        }
        val samples = listOf(
            "gorillatagcustommap12a_1789620124335.zip" to
                InstalledQuestApp("com.AnotherAxiom.GorillaTag", "1.1.145", 29679L),
            "barkvs-9vwq__1789620124335.zip" to
                InstalledQuestApp("com.AnotherAxiom.GorillaTag", "1.1.145", 29679L),
            "normalpapablplane.5_1789620124335.zip" to bonelab,
            "frameworkpack-ei4o_1789620124335.zip" to nomad
        )
        samples.forEach { (name, app) ->
            val archive = root.resolve(name)
            require(archive.isFile) { "real fixture is missing: ${archive.path}" }
            val standalone = analyzer.analyze(archive, app)
            val eligible = analyzer.analyze(archive, app, directoryDiscovery = verifiedDiscovery(app))
            fun report(
                label: String,
                analysis: ModPackageAnalysis,
                standalone: Boolean
            ): String {
                val blocks = analysis.installPlan.preconditions
                    .filterNot { it.satisfied }
                    .joinToString("|") { it.code }
                val messages = analysis.installPlan.preconditions
                    .filterNot { it.satisfied }
                    .joinToString("~") { it.message }
                val modRoots = analysis.archiveTree?.roots
                    ?.filter { it.kind == QuestModArchiveRootKind.MOD_ROOT }
                    ?.joinToString { it.path.ifBlank { "(virtual)" } } ?: "none"
                val packaging = analysis.archiveTree?.roots
                    ?.filter { it.kind == QuestModArchiveRootKind.PACKAGING_METADATA }
                    ?.joinToString { it.path } ?: "none"
                return "REAL_ARCHIVE label=$label name=$name type=${analysis.packageType} " +
                    "outcome=${analysis.outcome} classificationInstallable=${analysis.installable} " +
                    "eligibility=${if (standalone) "UNVERIFIED_DIRECTORY" else analysis.installable} " +
                    "modRoots=$modRoots packagingMetadata=$packaging " +
                    "destination=${analysis.installPlan.destinationRoot ?: "none"} " +
                    "files=${analysis.installPlan.totalFiles} bytes=${analysis.installPlan.totalBytes} " +
                    "blocks=${blocks.ifBlank { "none" }} " +
                    "blockMessages=${messages.ifBlank { "none" }}"
            }
            println(
                report("standalone", standalone, true) + "\n" +
                    report("verified-dir", eligible, false)
            )
            assertTrue(standalone.entries.isNotEmpty(), "$name had no entries")
            assertTrue(standalone.archiveTree != null, "$name had no normalized tree")
            if (name.startsWith("frameworkpack")) {
                val manifest = standalone.archiveTree?.metadata?.entries
                    ?.firstOrNull { it.key.endsWith("/manifest.json") }?.value
                assertEquals("1.0.0.0", manifest?.optString("GameVersion"))
                assertTrue(standalone.installPlan.preconditions.any {
                    it.code == "NOMAD_GAME_VERSION_COMPATIBILITY_WARNING" && it.satisfied
                })
                assertEquals(ModInstallOutcome.DIRECT_INSTALL_READY, eligible.outcome)
                assertEquals(3, eligible.installPlan.totalFiles)
                assertEquals(77162L, eligible.installPlan.totalBytes)
                assertTrue(customerAnalysisMessage(eligible).contains("غير موثقة"))
                assertEquals(
                    "/sdcard/Android/data/com.Warpfrog.BladeAndSorcery/files/Mods",
                    eligible.installPlan.destinationRoot
                )
                assertFalse(routeModWorkflow(eligible).preparationRequired)
            }
            if (name.startsWith("normalpapablplane")) {
                assertEquals(ModPackageType.BONELAB_NATIVE_CONTENT, eligible.packageType)
                assertEquals(ModInstallOutcome.DIRECT_INSTALL_READY, eligible.outcome)
                assertEquals(8, eligible.installPlan.totalFiles)
                assertEquals(426316821L, eligible.installPlan.totalBytes)
                assertTrue(eligible.installPlan.patchRequirement == null)
                assertTrue(eligible.installPlan.loaderRequirement == null)
                assertFalse(routeModWorkflow(eligible).preparationRequired)
            }
        }
    }

    private fun analyzeAfterRename(
        archive: File,
        app: InstalledQuestApp,
        directoryDiscovery: ModDirectoryDiscovery? = null
    ): ModPackageAnalysis {
        val renamed = File.createTempFile("renamed-real-sample-", ".zip")
        archive.copyTo(renamed, overwrite = true)
        archive.delete()
        return try {
            analyzer.analyze(renamed, app, directoryDiscovery = directoryDiscovery)
        } finally {
            renamed.delete()
        }
    }

    private fun verifiedDiscovery(app: InstalledQuestApp): ModDirectoryDiscovery =
        ModDirectoryDiscovery(
            serial = "fixture",
            packageId = app.packageName,
            appVersion = app.versionName,
            candidates = listOf(
                ModDirectoryCandidate(
                    packageId = app.packageName,
                    path = "/sdcard/Android/data/${app.packageName}/files/Mods",
                    exists = true,
                    source = "fixture verified directory"
                )
            )
        )

    private fun assertFailsUnsafe(archive: File) {
        try {
            QuestModArchiveNormalizer.normalize(archive)
            error("unsafe archive was accepted")
        } catch (_: QuestModArchiveNormalizer.UnsafeArchiveException) {
            // expected
        }
    }

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("nfvr-real-structure-", ".zip")
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
        return file
    }
}