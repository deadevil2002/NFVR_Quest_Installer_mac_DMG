import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModEngineSections8To23Test {
    private val bonelab = InstalledQuestApp(
        packageName = "com.StressLevelZero.BONELAB",
        versionName = "1.2.3",
        versionCode = 123L
    )
    private val gorilla = InstalledQuestApp(
        packageName = "com.AnotherAxiom.GorillaTag",
        versionName = "1.0.0"
    )

    @Test
    fun gorillaQmodRejectsMismatchedManifestPackageExactly() {
        val archive = zipOf(
            "mod.json" to """{"_QPVersion":"1.2.0","id":"g","name":"G","author":"NFVR",
                "version":"1.0.0","packageId":"com.other.game","modloader":"QuestLoader",
                "modFiles":["plugin.so"]}""".replace("\n", ""),
            "plugin.so" to "native"
        )
        val analysis = ModPackageAnalyzer().analyze(archive, gorilla)
        assertFalse(analysis.installable)
        assertTrue(analysis.plan.preconditions.any { it.code == "TARGET_PACKAGE_MISMATCH" })
        archive.delete()
    }

    @Test
    fun stockGorillaNativePayloadRequiresApkPatchInsteadOfGuessedPath() {
        val archive = zipOf("plugins/native.so" to "native")
        val analysis = ModPackageAnalyzer().analyze(archive, gorilla)
        assertEquals(ModInstallOutcome.APK_PATCH_REQUIRED, analysis.outcome)
        assertFalse(analysis.installable)
        assertTrue(analysis.plan.preconditions.any { it.code == "APK_PATCH_REQUIRED" })
        assertTrue(analysis.plan.mappings.isEmpty())
        archive.delete()
    }

    @Test
    fun authenticatedCompatibleLoaderAloneEnablesOnlyCanonicalGorillaQmodPath() {
        val archive = zipOf(
            "mod.json" to """{"_QPVersion":"1.2.0","id":"g","name":"G","author":"NFVR",
                "version":"1.0.0","packageId":"com.AnotherAxiom.GorillaTag",
                "modloader":"QuestLoader","modFiles":["plugin.dat"]}""".replace("\n", ""),
            "plugin.dat" to "payload"
        )
        val detection = ModLoaderDetection(
            gorilla.packageName,
            mapOf(
                ModLoaderKind.QUEST_LOADER to ModLoaderEvidence(
                    ModLoaderKind.QUEST_LOADER, ModLoaderStatus.DETECTED,
                    listOf("authenticated QuestPatcher modded.json")
                )
            )
        )
        val analysis = ModPackageAnalyzer().analyze(
            archive, gorilla, detection
        )
        assertTrue(analysis.installable)
        assertEquals(
            "/sdcard/Android/data/com.AnotherAxiom.GorillaTag/files/mods/plugin.dat",
            analysis.plan.mappings.single().destinationPath
        )
        assertTrue(analysis.plan.mappings.single().destinationPath.contains(gorilla.packageName))
        archive.delete()
    }

    @Test
    fun authenticatedScotland2DoesNotAuthorizeGorillaWithoutPackageSpecificEvidence() {
        val archive = zipOf(
            "mod.json" to """{"_QPVersion":"1.2.0","id":"g","name":"G","author":"NFVR",
                "version":"1.0.0","packageId":"com.AnotherAxiom.GorillaTag",
                "modloader":"Scotland2","modFiles":["plugin.dat"]}""".replace("\n", ""),
            "plugin.dat" to "payload"
        )
        val detection = ModLoaderDetection(
            gorilla.packageName,
            mapOf(
                ModLoaderKind.SCOTLAND2 to ModLoaderEvidence(
                    ModLoaderKind.SCOTLAND2, ModLoaderStatus.DETECTED,
                    listOf("authenticated QuestPatcher modded.json")
                )
            )
        )
        val analysis = ModPackageAnalyzer().analyze(archive, gorilla, detection)
        assertFalse(analysis.installable)
        assertTrue(analysis.plan.preconditions.any {
            it.code == "GORILLA_LOADER_COMPATIBILITY_UNCONFIRMED"
        })
        archive.delete()
    }

    @Test
    fun gorillaDirectoryDiscoveryCannotAuthorizeOrdinaryArchiveOrConfirmation() {
        val archive = zipOf("Mods/ordinary.dat" to "payload")
        val discovery = ModDirectoryDiscovery(
            serial = "SERIAL",
            packageId = gorilla.packageName,
            appVersion = gorilla.versionName,
            candidates = listOf(
                ModDirectoryCandidate(
                    packageId = gorilla.packageName,
                    path = "/sdcard/Android/data/${gorilla.packageName}/files/Mods",
                    exists = true,
                    source = "read-only test"
                )
            )
        )
        val analysis = ModPackageAnalyzer().analyze(archive, gorilla, null, discovery)
        assertEquals(ModInstallOutcome.APK_PATCH_REQUIRED, analysis.outcome)
        assertFalse(analysis.installable)
        assertTrue(analysis.plan.preconditions.any {
            it.code == "GORILLA_TAG_DESTINATION_UNAUTHORIZED"
        })
        assertTrue(analysis.plan.confirmation == null)
        // There is no confirmation token to turn this classification into a
        // writable plan.
        assertFalse(analysis.plan.confirmDestination("anything").installable)
        archive.delete()
    }

    @Test
    fun exactGorillaAndroidLayoutRemainsExplicitPackageBoundStrategy() {
        val archive = zipOf(
            "Android/data/${gorilla.packageName}/files/verified.dat" to "payload"
        )
        val analysis = ModPackageAnalyzer().analyze(archive, gorilla)
        // Universal explicit Android layouts are package-bound evidence, not
        // guessed mod destinations; the exact selected package is required.
        assertEquals(ModPackageType.ANDROID_DATA_LAYOUT, analysis.packageType)
        assertTrue(analysis.installable)
        assertTrue(analysis.plan.mappings.single().destinationPath.startsWith(
            "/sdcard/Android/data/${gorilla.packageName}/"
        ))
        archive.delete()
    }

    @Test
    fun gorillaQmodFileCopiesCannotEscapeAuthenticatedQuestLoaderRoots() {
        fun detection() = ModLoaderDetection(
            gorilla.packageName,
            mapOf(
                ModLoaderKind.QUEST_LOADER to ModLoaderEvidence(
                    ModLoaderKind.QUEST_LOADER, ModLoaderStatus.DETECTED,
                    listOf("authenticated QuestPatcher modded.json")
                )
            )
        )
        fun analyze(destination: String): ModPackageAnalysis {
            val archive = zipOf(
                "mod.json" to """{"_QPVersion":"1.2.0","id":"g","name":"G","author":"NFVR",
                    "version":"1.0.0","packageId":"${gorilla.packageName}",
                    "modloader":"QuestLoader","fileCopies":[
                    {"name":"payload.dat","destination":"$destination"}]}""".replace("\n", ""),
                "payload.dat" to "payload"
            )
            val result = ModPackageAnalyzer().analyze(archive, gorilla, detection())
            archive.delete()
            return result
        }

        val arbitrary = analyze("/sdcard/Android/data/${gorilla.packageName}/files/arbitrary/payload.dat")
        assertFalse(arbitrary.installable)
        assertTrue(arbitrary.plan.preconditions.any {
            it.code == "GORILLA_QMOD_DESTINATION_UNAUTHORIZED"
        })

        val traversal = analyze("/sdcard/Android/data/${gorilla.packageName}/files/mods/../arbitrary/payload.dat")
        assertFalse(traversal.installable)
        assertTrue(traversal.plan.preconditions.any {
            it.code == "GORILLA_QMOD_DESTINATION_UNAUTHORIZED" || it.code == "UNSAFE_PATH"
        })

        for (root in listOf("mods", "libs")) {
            val canonical = analyze(
                "/sdcard/Android/data/${gorilla.packageName}/files/$root/payload.dat"
            )
            assertTrue(canonical.installable)
            assertTrue(canonical.plan.mappings.single().destinationPath.endsWith("$root/payload.dat"))
        }
    }

    @Test
    fun qmodParsesOptionalDependenciesAndCanonicalCopyExtensions() {
        val archive = zipOf(
            "mod.json" to """
                {
                  "_QPVersion":"1.2.0",
                  "id":"cover-mod",
                  "name":"Cover",
                  "author":"NFVR",
                  "version":"1.0.0",
                  "porter":"Quest porter",
                  "packageId":"com.StressLevelZero.BONELAB",
                  "dependencies":[
                    {"id":"optional-lib","version":"^1.0.0","required":false}
                  ],
                  "copyExtensions":[
                    {"extension":"png","destination":"/sdcard/ModData/com.StressLevelZero.BONELAB/Mods"}
                  ]
                }
            """.trimIndent(),
            "assets/cover.PNG" to "cover"
        )
        val analysis = ModPackageAnalyzer().analyze(archive, bonelab)

        assertFalse(analysis.installable)
        assertEquals(1, analysis.plan.optionalDependencies.size)
        assertTrue(analysis.plan.optionalDependencies.single().optional)
        assertTrue(analysis.plan.preconditions.any {
            it.code == "COPY_EXTENSIONS_REGISTRATION_UNSUPPORTED"
        })
        assertTrue(analysis.plan.mappings.isEmpty())
        archive.delete()
    }

    @Test
    fun qmodAcceptsPublishedSchemaVersionsAndRejectsUnsupportedOrMalformedManifests() {
        val supported = zipOf(
            "mod.json" to """
                {"_QPVersion":"0.1.0","name":"Legacy","id":"legacy",
                 "author":"NFVR","version":"1.0.0",
                 "packageId":"com.StressLevelZero.BONELAB",
                 "fileCopies":[{"name":"payload.dat","destination":"payload.dat"}]}
            """.trimIndent(),
            "payload.dat" to "payload"
        )
        val supportedAnalysis = ModPackageAnalyzer().analyze(supported, bonelab)
        assertTrue(supportedAnalysis.recognized)
        assertTrue(supportedAnalysis.installable)
        supported.delete()

        val unsupported = zipOf(
            "mod.json" to """
                {"_QPVersion":"9.9.9","name":"Bad","id":"bad",
                 "author":"NFVR","version":"1.0.0",
                 "packageId":"com.StressLevelZero.BONELAB",
                 "fileCopies":[{"name":"payload.dat","destination":"payload.dat"}]}
            """.trimIndent(),
            "payload.dat" to "payload"
        )
        val unsupportedAnalysis = ModPackageAnalyzer().analyze(unsupported, bonelab)
        assertTrue(unsupportedAnalysis.plan.preconditions.any {
            it.code == "UNSUPPORTED_QMOD_VERSION"
        })
        unsupported.delete()

        val malformed = zipOf(
            "mod.json" to """
                {"_QPVersion":"1.2.0","name":"Missing author","id":"bad",
                 "version":"not-semver","packageId":"com.StressLevelZero.BONELAB",
                 "fileCopies":[{"name":"payload.dat","destination":"payload.dat"}]}
            """.trimIndent(),
            "payload.dat" to "payload"
        )
        val malformedAnalysis = ModPackageAnalyzer().analyze(malformed, bonelab)
        assertTrue(malformedAnalysis.plan.preconditions.any {
            it.code == "MISSING_QMOD_FIELDS"
        })
        assertTrue(malformedAnalysis.plan.preconditions.any {
            it.code == "INVALID_QMOD_VERSION"
        })
        malformed.delete()
    }

    @Test
    fun qmodRejectsNonCanonicalCopyExtensionsObject() {
        val archive = zipOf(
            "mod.json" to """
                {"_QPVersion":"1.2.0","name":"Bad copies","id":"bad-copies",
                 "author":"NFVR","version":"1.0.0",
                 "packageId":"com.StressLevelZero.BONELAB",
                 "copyExtensions":{"png":"Mods"}}
            """.trimIndent(),
            "cover.png" to "cover"
        )
        val analysis = ModPackageAnalyzer().analyze(archive, bonelab)
        assertTrue(analysis.plan.preconditions.any {
            it.code == "INVALID_COPY_EXTENSIONS"
        })
        assertFalse(analysis.installable)
        archive.delete()
    }

    @Test
    fun androidSdcardPrefixBindsDataAndObbToOneSelectedPackage() {
        val archive = zipOf(
            "sdcard/Android/data/com.example.game/files/Mods/mod.dat" to "mod",
            "sdcard/Android/obb/com.example.game/main.1.com.example.game.obb" to "obb"
        )
        val selected = InstalledQuestApp("com.example.game", "2")
        val analysis = ModPackageAnalyzer().analyze(archive, selected)

        assertEquals(ModPackageType.ANDROID_DATA_LAYOUT, analysis.packageType)
        assertTrue(analysis.installable)
        assertTrue(analysis.plan.mappings.any {
            it.destinationPath == "/sdcard/Android/data/com.example.game/files/Mods/mod.dat"
        })
        assertTrue(analysis.plan.mappings.any {
            it.destinationPath == "/sdcard/Android/obb/com.example.game/main.1.com.example.game.obb"
        })
        archive.delete()
    }

    @Test
    fun patchRequirementIsExplicitAndNeverPretendsToCopyAnApkPatch() {
        val archive = zipOf(
            "mod.json" to """
                {
                  "packageId":"com.StressLevelZero.BONELAB",
                  "patcher":"QuestPatcher",
                  "fileCopies":[{"name":"payload.dat","destination":"payload.dat"}]
                }
            """.trimIndent(),
            "payload.dat" to "payload"
        )
        val analysis = ModPackageAnalyzer().analyze(archive, bonelab)

        assertEquals(ModInstallOutcome.APK_PATCH_REQUIRED, analysis.outcome)
        assertFalse(analysis.installable)
        assertTrue(analysis.plan.preconditions.any { it.code == "APK_PATCH_REQUIRED" })
        assertNotNull(analysis.plan.patchRequirement)
        archive.delete()
    }

    @Test
    fun existingDirectoryProposalNeedsExplicitConfirmation() {
        val archive = zipOf("Mods/unknown.dat" to "data")
        val app = InstalledQuestApp("com.example.game", "1")
        val discovery = ModDirectoryDiscovery(
            serial = "SERIAL",
            packageId = app.packageName,
            appVersion = app.versionName,
            candidates = listOf(
                ModDirectoryCandidate(
                    packageId = app.packageName,
                    path = "/sdcard/Android/data/com.example.game/files/Mods",
                    exists = true,
                    source = "read-only test"
                )
            )
        )
        val analysis = ModPackageAnalyzer().analyze(archive, app, null, discovery)

        assertEquals(ModResolutionStrategy.EXISTING_GAME_MOD_DIRECTORY, analysis.strategy)
        assertFalse(analysis.installable)
        assertTrue(genericDestinationNeedsConfirmation(analysis))
        val token = assertNotNull(analysis.plan.confirmation).token
        val confirmed = analysis.plan.confirmDestination(token)
        assertTrue(confirmed.installable)
        assertEquals(ModInstallOutcome.DIRECT_INSTALL_READY, confirmed.outcome)
        archive.delete()
    }

    @Test
    fun bindingCarriesSerialPackageVersionArchiveAndPlanId() {
        val archive = zipOf(
            "nfvr-mod.json" to """
                {"schemaVersion":1,"targetPackageId":"com.StressLevelZero.BONELAB",
                 "files":[{"source":"payload.dat","destination":"payload.dat"}]}
            """.trimIndent(),
            "payload.dat" to "payload"
        )
        val analysis = ModPackageAnalyzer().analyze(archive, bonelab)
        val bound = analysis.plan.bindToDevice("SERIAL")
        val binding = assertNotNull(bound.operationBinding)

        assertEquals("SERIAL", binding.deviceSerial)
        assertEquals(bonelab.packageName, binding.packageId)
        assertEquals(bonelab.versionName, binding.gameVersion)
        assertEquals(analysis.plan.archiveSha256, binding.archiveSha256)
        assertEquals(binding.analysisPlanId, bound.analysisPlanId)
        archive.delete()
    }

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("nfvr-sections-8-23-", ".zip")
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