import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModEngineTest {
    private val bonelab = InstalledQuestApp(
        "com.StressLevelZero.BONELAB",
        "1.2974.57485",
        2974L,
        apkSha256 = "02adecc4af7354296205b4c2dbb50fba4132628aa0f0186ea9a7cf7419f670b1"
    )
    private val analyzer = ModPackageAnalyzer()

    @Test
    fun recognizesBonelabNativePalletAndPreservesCompleteFolderDestination() {
        val zip = zipOf(
            "Author.Mod/" to "",
            "Author.Mod/pallet.json" to
                """{"name":"Author Mod","pallet":"avatar","version":"1.0","files":["avatar.assetbundle"]}""",
            "Author.Mod/Assets/avatar.assetbundle" to "asset"
        )
        val analysis = analyzer.analyze(zip, bonelab)

        assertEquals(ModPackageType.BONELAB_NATIVE_CONTENT, analysis.packageType)
        assertEquals(ModInstallOutcome.DIRECT_INSTALL_READY, analysis.outcome)
        assertEquals(
            "/sdcard/Android/data/com.StressLevelZero.BONELAB/files/Mods/Author.Mod/Assets/avatar.assetbundle",
            analysis.plan.mappings.single { it.sourcePath.endsWith("avatar.assetbundle") }.destinationPath
        )
        zip.delete()
    }

    @Test
    fun bonelabDesktopPayloadIsNotAcceptedAsQuestContent() {
        val zip = zipOf(
            "PC/manifest.json" to
                """{"name":"Desktop Export","packageId":"com.StressLevelZero.BONELAB","gameVersion":"1.2974.57485"}""",
            "PC/readme.assetbundle" to "desktop",
            "PC/game.pdb" to "symbols"
        )
        val analysis = analyzer.analyze(zip, bonelab)

        assertEquals(ModPackageType.BONELAB_NATIVE_CONTENT, analysis.packageType)
        assertFalse(analysis.installable)
        assertTrue(analysis.plan.preconditions.any { it.code == "PC_ONLY_PAYLOAD" })
        zip.delete()
    }

    @Test
    fun mixedBonelabArchiveSelectsQuestBranchAndStripsNestedModsWrappers() {
        val zip = zipOfBytes(
            "PC/Desktop.exe" to pcExePayload(),
            "PC/Native.dll" to managedPeCliPayload(),
            "PC/Native.so" to pcElfPayload(),
            "Quest/Mods/Mods/Author.Mod/manifest.json" to
                """{"name":"Author Mod","packageId":"com.StressLevelZero.BONELAB","gameVersion":"1.2974.57485"}"""
                    .toByteArray(),
            "Quest/Mods/Mods/Author.Mod/content.assetbundle" to "quest".toByteArray()
        )
        val analysis = analyzer.analyze(zip, bonelab)

        assertTrue(analysis.installable)
        assertEquals(
            "/sdcard/Android/data/com.StressLevelZero.BONELAB/files/Mods/Author.Mod/content.assetbundle",
            analysis.plan.mappings.single {
                it.destinationPath.endsWith("/Author.Mod/content.assetbundle")
            }.destinationPath
        )
        zip.delete()
    }

    @Test
    fun mixedBonelabArchiveDeniesQuestNativeBinaryAfterIgnoringPcBinarySubset() {
        val zip = zipOfBytes(
            "PC/Desktop.exe" to pcExePayload(),
            "PC/Native.dll" to managedPeCliPayload(),
            "PC/Native.so" to pcElfPayload(),
            "Quest/Mods/Mods/Author.Mod/manifest.json" to
                """{"name":"Author Mod","packageId":"com.StressLevelZero.BONELAB","gameVersion":"1.2974.57485"}"""
                    .toByteArray(),
            "Quest/Mods/Mods/Author.Mod/content.assetbundle" to "quest".toByteArray(),
            "Quest/Mods/Mods/Author.Mod/native.so" to "not-an-elf".toByteArray()
        )
        val analysis = analyzer.analyze(zip, bonelab)

        assertEquals(ModInstallOutcome.UNSUPPORTED, analysis.outcome)
        assertTrue(analysis.plan.preconditions.any {
            it.code == "NATIVE_PAYLOAD_REQUIRES_LOADER_ROOT"
        })
        zip.delete()
    }

    @Test
    fun androidAssetbundleLayoutIsNotForcedThroughBonelabHeuristic() {
        val zip = zipOf(
            "Android/data/com.other.game/files/Mods/Author.Mod/content.assetbundle" to "asset"
        )
        val selected = InstalledQuestApp("com.other.game", "1")
        val analysis = analyzer.analyze(zip, selected)

        assertEquals(ModPackageType.ANDROID_DATA_LAYOUT, analysis.packageType)
        assertEquals(ModInstallOutcome.DIRECT_INSTALL_READY, analysis.outcome)
        assertEquals(
            "/sdcard/Android/data/com.other.game/files/Mods/Author.Mod/content.assetbundle",
            analysis.plan.mappings.single().destinationPath
        )
        zip.delete()
    }

    @Test
    fun bonelabCodeModFailsClosedUntilLemonOrMelonLoaderEvidence() {
        val zip = zipOfBytes("Author.Mod/Example.dll" to managedPeCliPayload())
        val withoutLoader = analyzer.analyze(zip, bonelab)

        assertEquals(ModPackageType.BONELAB_CODE_MOD, withoutLoader.packageType)
        assertEquals(ModInstallOutcome.REQUIRES_MOD_LOADER, withoutLoader.outcome)
        assertTrue(withoutLoader.plan.preconditions.any { it.code == "MOD_LOADER_UNKNOWN" })

        val detection = ModLoaderDetection(
            bonelab.packageName,
            mapOf(
                ModLoaderKind.LEMON_LOADER to ModLoaderEvidence(
                    ModLoaderKind.LEMON_LOADER,
                    ModLoaderStatus.DETECTED,
                    listOf("test evidence")
                )
            )
        )
        val withLoader = analyzer.analyze(zip, bonelab, detection)
        assertEquals(ModInstallOutcome.DIRECT_INSTALL_READY, withLoader.outcome)
        assertTrue(withLoader.installable)
        zip.delete()
    }

    @Test
    fun qmodHonorsManifestLoaderAndQuestLoaderDestination() {
        val zip = zipOf(
            "mod.json" to """
                {"_QPVersion":"1.2.0","name":"Loader mod","id":"loader-mod",
                 "author":"NFVR","version":"1.0.0",
                 "packageId":"com.StressLevelZero.BONELAB","packageVersion":"1.2974.57485",
                 "modloader":"QuestLoader","modFiles":["lib/mod.dat"],
                 "libraryFiles":["libdep.dat"]}
            """.trimIndent(),
            "lib/mod.dat" to "mod",
            "libdep.dat" to "dep"
        )
        val detection = ModLoaderDetection(
            bonelab.packageName,
            mapOf(ModLoaderKind.QUEST_LOADER to ModLoaderEvidence(
                ModLoaderKind.QUEST_LOADER,
                ModLoaderStatus.DETECTED
            ))
        )
        val analysis = analyzer.analyze(zip, bonelab, detection)

        assertEquals(ModInstallOutcome.DIRECT_INSTALL_READY, analysis.outcome)
        assertTrue(analysis.plan.mappings.all {
            it.destinationPath.startsWith("/sdcard/Android/data/com.StressLevelZero.BONELAB/files/")
        })
        assertTrue(analysis.plan.mappings.any { it.destinationPath.endsWith("/mods/mod.dat") })
        assertTrue(analysis.plan.mappings.any { it.destinationPath.endsWith("/libs/libdep.dat") })
        zip.delete()
    }

    @Test
    fun qmodDependenciesAndUnsupportedCopySemanticsAreRequirementsNotIgnored() {
        val zip = zipOf(
            "mod.json" to """
                {"_QPVersion":"1.2.0","name":"Needs dependency","id":"needs-dependency",
                 "author":"NFVR","version":"1.0.0","packageId":"com.StressLevelZero.BONELAB",
                 "packageVersion":"1.2974.57485","dependencies":[{"id":"other","version":"1"}],
                 "modFiles":["mod.so"]}
            """.trimIndent(),
            "mod.so" to "mod"
        )
        val detection = ModLoaderDetection(
            bonelab.packageName,
            mapOf(ModLoaderKind.QUEST_LOADER to ModLoaderEvidence(
                ModLoaderKind.QUEST_LOADER,
                ModLoaderStatus.DETECTED
            ))
        )
        val analysis = analyzer.analyze(zip, bonelab, detection)

        assertFalse(analysis.installable)
        assertTrue(analysis.plan.preconditions.any { it.code == "DEPENDENCIES_REQUIREMENT" })
        zip.delete()
    }

    @Test
    fun qmodDoesNotClaimUnsupportedLemonLoaderDestinationSemantics() {
        val zip = zipOf(
            "mod.json" to """
                {"_QPVersion":"1.2.0","name":"Unsupported","id":"unsupported",
                 "author":"NFVR","version":"1.0.0","packageId":"com.StressLevelZero.BONELAB",
                 "modloader":"LemonLoader","modFiles":["mod.so"]}
            """.trimIndent(),
            "mod.so" to "mod"
        )
        val analysis = analyzer.analyze(zip, bonelab)

        assertEquals(ModInstallOutcome.UNSUPPORTED, analysis.outcome)
        assertTrue(analysis.plan.preconditions.any { it.code == "UNSUPPORTED_MOD_LOADER" })
        zip.delete()
    }

    @Test
    fun legacyQmodDefaultsToQuestLoaderAndInvalidNativeBytesRemainDenied() {
        val zip = zipOf(
            "mod.json" to """{"packageId":"com.StressLevelZero.BONELAB","modFiles":["native.so"]}""",
            "native.so" to "not-an-elf"
        )
        val withoutEvidence = analyzer.analyze(zip, bonelab)
        assertEquals(ModInstallOutcome.UNSUPPORTED, withoutEvidence.outcome)
        assertTrue(withoutEvidence.plan.preconditions.any { it.code == "MOD_LOADER_UNKNOWN" })

        val detection = ModLoaderDetection(
            bonelab.packageName,
            mapOf(ModLoaderKind.QUEST_LOADER to ModLoaderEvidence(
                ModLoaderKind.QUEST_LOADER,
                ModLoaderStatus.DETECTED
            ))
        )
        val withEvidence = analyzer.analyze(zip, bonelab, detection)
        assertEquals(ModInstallOutcome.UNSUPPORTED, withEvidence.outcome)
        assertTrue(withEvidence.plan.preconditions.any {
            it.code == "NATIVE_PAYLOAD_REQUIRES_LOADER_ROOT"
        })
        zip.delete()
    }

    @Test
    fun qmodAllowsOnlyValidatedArm64ElfUnderLoaderRoot() {
        val zip = zipOfBytes(
            "mod.json" to """
                {"_QPVersion":"1.2.0","name":"Native","id":"native",
                 "author":"NFVR","version":"1.0.0",
                 "packageId":"com.StressLevelZero.BONELAB",
                 "modloader":"QuestLoader","modFiles":["lib.so"]}
            """.trimIndent().toByteArray(),
            "lib.so" to arm64ElfPayload()
        )
        val detection = ModLoaderDetection(
            bonelab.packageName,
            mapOf(ModLoaderKind.QUEST_LOADER to ModLoaderEvidence(
                ModLoaderKind.QUEST_LOADER,
                ModLoaderStatus.DETECTED
            ))
        )
        val analysis = analyzer.analyze(zip, bonelab, detection)
        assertEquals(ModInstallOutcome.DIRECT_INSTALL_READY, analysis.outcome)
        assertTrue(analysis.plan.mappings.single().destinationPath.endsWith("/files/mods/lib.so"))
        zip.delete()
    }

    @Test
    fun legacyQmodLateFilesAreRejectedWithoutExplicitLoaderSemantics() {
        val zip = zipOf(
            "mod.json" to """
                {"name":"Late","id":"late","author":"NFVR","version":"1.0.0",
                 "packageId":"com.StressLevelZero.BONELAB","lateModFiles":["late.dat"]}
            """.trimIndent(),
            "late.dat" to "late"
        )
        val detection = ModLoaderDetection(
            bonelab.packageName,
            mapOf(ModLoaderKind.QUEST_LOADER to ModLoaderEvidence(
                ModLoaderKind.QUEST_LOADER,
                ModLoaderStatus.DETECTED
            ))
        )
        val analysis = analyzer.analyze(zip, bonelab, detection)
        assertEquals(ModInstallOutcome.UNSUPPORTED, analysis.outcome)
        assertTrue(analysis.plan.preconditions.any {
            it.code == "UNSUPPORTED_LATE_MOD_FILES"
        })
        zip.delete()
    }

    @Test
    fun genericNativePayloadIsDefaultDenied() {
        val zip = zipOf("Mods/unknown.so" to "not-an-elf")
        val analysis = analyzer.analyze(zip, bonelab)
        assertTrue(analysis.plan.preconditions.any {
            it.code == "NATIVE_PAYLOAD_REQUIRES_LOADER_ROOT"
        })
        zip.delete()
    }

    @Test
    fun qmodFileCopiesUseAuthoritativeNameFieldAndSafeManifestDestination() {
        val zip = zipOf(
            "mod.json" to """
                {"_QPVersion":"1.2.0","name":"Copy","id":"copy","author":"NFVR",
                 "version":"1.0.0","packageId":"com.StressLevelZero.BONELAB",
                 "packageVersion":"1.2974.57485",
                 "fileCopies":[{"name":"cover.png",
                 "destination":"/sdcard/ModData/com.StressLevelZero.BONELAB/cover.png"}]}
            """.trimIndent(),
            "cover.png" to "cover"
        )
        val analysis = analyzer.analyze(zip, bonelab)
        assertTrue(analysis.installable)
        assertEquals(
            "/sdcard/ModData/com.StressLevelZero.BONELAB/cover.png",
            analysis.plan.mappings.single().destinationPath
        )
        zip.delete()
    }

    @Test
    fun qmodLoaderRootsDoNotRequireRegisteredGameProfile() {
        val app = InstalledQuestApp("com.unknown.game", "1")
        val zip = zipOf(
            "mod.json" to """
                {"packageId":"com.unknown.game","modloader":"QuestLoader",
                 "modFiles":["mod.dat"]}
            """.trimIndent(),
            "mod.dat" to "mod"
        )
        val detection = ModLoaderDetection(
            app.packageName,
            mapOf(ModLoaderKind.QUEST_LOADER to ModLoaderEvidence(
                ModLoaderKind.QUEST_LOADER,
                ModLoaderStatus.DETECTED
            ))
        )
        val analysis = analyzer.analyze(zip, app, detection)

        assertTrue(analysis.installable)
        assertEquals(
            "/sdcard/Android/data/com.unknown.game/files/mods/mod.dat",
            analysis.plan.mappings.single().destinationPath
        )
        zip.delete()
    }

    @Test
    fun qmodLateFilesRequireScotland2EvenWhenQuestLoaderIsDetected() {
        val app = InstalledQuestApp("com.unknown.game", "1")
        val zip = zipOf(
            "mod.json" to """
                {"packageId":"com.unknown.game","modloader":"QuestLoader",
                 "lateModFiles":["late.dat"]}
            """.trimIndent(),
            "late.dat" to "late"
        )
        val detection = ModLoaderDetection(
            app.packageName,
            mapOf(ModLoaderKind.QUEST_LOADER to ModLoaderEvidence(
                ModLoaderKind.QUEST_LOADER,
                ModLoaderStatus.DETECTED
            ))
        )
        val analysis = analyzer.analyze(zip, app, detection)

        assertEquals(ModInstallOutcome.UNSUPPORTED, analysis.outcome)
        assertTrue(analysis.plan.preconditions.any {
            it.code == "UNSUPPORTED_LATE_MOD_FILES"
        })
        zip.delete()
    }

    @Test
    fun androidDataLayoutBindsToSelectedPackage() {
        val zip = zipOf(
            "Android/data/com.example.game/files/Mods/one.dat" to "one"
        )
        val selected = InstalledQuestApp("com.example.game", "1")
        val analysis = analyzer.analyze(zip, selected)

        assertEquals(ModPackageType.ANDROID_DATA_LAYOUT, analysis.packageType)
        assertEquals(ModInstallOutcome.DIRECT_INSTALL_READY, analysis.outcome)
        assertEquals(
            "/sdcard/Android/data/com.example.game/files/Mods/one.dat",
            analysis.plan.mappings.single().destinationPath
        )
        assertFalse(analyzer.analyze(zip, bonelab).installable)
        zip.delete()
    }

    @Test
    fun androidObbLayoutRejectsCrossPackagePayload() {
        val zip = zipOf(
            "Android/obb/com.example.game/main.1.com.example.game.obb" to "obb"
        )
        val analysis = analyzer.analyze(zip, bonelab)

        assertEquals(ModPackageType.ANDROID_OBB_LAYOUT, analysis.packageType)
        assertEquals(ModInstallOutcome.UNSUPPORTED, analysis.outcome)
        assertTrue(analysis.plan.preconditions.any { it.code == "TARGET_PACKAGE_MISMATCH" })
        zip.delete()
    }

    @Test
    fun knownBeatSaberProfileRequiresReadOnlyLoaderEvidence() {
        val app = InstalledQuestApp("com.beatgames.beatsaber", "1.0")
        val zip = zipOf("Mods/song.dat" to "song")
        val withoutLoader = analyzer.analyze(zip, app)

        assertEquals(ModPackageType.KNOWN_GAME_PROFILE, withoutLoader.packageType)
        assertEquals(ModInstallOutcome.REQUIRES_MOD_LOADER, withoutLoader.outcome)
        assertTrue(withoutLoader.plan.destinationRoot!!.startsWith("/sdcard/ModData/"))

        val detection = ModLoaderDetection(
            app.packageName,
            mapOf(ModLoaderKind.SCOTLAND2 to ModLoaderEvidence(
                ModLoaderKind.SCOTLAND2,
                ModLoaderStatus.DETECTED
            ))
        )
        val ready = analyzer.analyze(zip, app, detection)
        assertEquals(ModInstallOutcome.DIRECT_INSTALL_READY, ready.outcome)
        zip.delete()
    }

    @Test
    fun virtualStumpIsBuiltInInformationalAndHasNoUrlWorkflow() {
        val zip = zipOf(
            "package.json" to """
                {"pcFileName":"map.bundle","androidFileName":"map.android",
                 "customMapSupportVersion":"1","initialScenes":["Scene"],
                 "availableGameModes":["infection"],"url":"https://example.invalid"}
            """.trimIndent(),
            "map.android" to "asset"
        )
        val analysis = analyzer.analyze(zip, bonelab)

        assertEquals(ModInstallOutcome.BUILT_IN_GAME_CONTENT, analysis.outcome)
        assertFalse(analysis.isExternalWorkflow)
        assertEquals(null, analysis.externalActionUrl)
        zip.delete()
    }

    @Test
    fun archivePathNormalizationRejectsDotAndInternalParentAsTraversal() {
        assertEquals("Author.Mod/file", ModArchivePath.normalize("./Author.Mod/file"))
        assertEquals(
            "Author.Mod/file",
            ModArchivePath.normalize("Author.Mod/../Author.Mod/file")
        )

        val safeZip = zipOf(
            "./Author.Mod/" to "",
            "Author.Mod/../Author.Mod/pallet.json" to
                """{"name":"Author Mod","pallet":"avatar","version":"1.0","files":["avatar.assetbundle"]}""",
            "Author.Mod/avatar.assetbundle" to "asset"
        )
        val safeAnalysis = analyzer.analyze(safeZip, bonelab)
        assertEquals(ModInstallOutcome.UNSAFE_ARCHIVE, safeAnalysis.outcome)
        safeZip.delete()

        val zip = zipOf("../outside.file" to "blocked")
        val analysis = analyzer.analyze(zip, bonelab)
        assertEquals(ModInstallOutcome.UNSAFE_ARCHIVE, analysis.outcome)
        assertFalse(analysis.recognized)
        zip.delete()
    }

    @Test
    fun archivePathNormalizationRejectsAbsoluteDriveUncAndNullNames() {
        val bad = listOf("/absolute.file", "C:\\absolute.file", "\\\\server\\share\\file")
        for (name in bad) {
            val analysis = analyzer.analyze(zipOf(name to "blocked"), bonelab)
            assertEquals(ModInstallOutcome.UNSAFE_ARCHIVE, analysis.outcome, name)
        }
        // ZipOutputStream itself permits a NUL in the name; the analyzer must
        // diagnose it before any package strategy is selected.
        val analysis = analyzer.analyze(zipOf("bad\u0000.file" to "blocked"), bonelab)
        assertEquals(ModInstallOutcome.UNSAFE_ARCHIVE, analysis.outcome)
    }

    @Test
    fun normalizedCollisionsAreRejectedRatherThanOverwritten() {
        val zip = zipOf(
            "Author.Mod/file" to "one",
            "Author.Mod/../Author.Mod/file" to "two"
        )
        val analysis = analyzer.analyze(zip, bonelab)
        assertEquals(ModInstallOutcome.UNSAFE_ARCHIVE, analysis.outcome)
        assertTrue(
            analysis.message.contains("traversal", ignoreCase = true) ||
                analysis.diagnostics.any { it.contains("traversal", ignoreCase = true) }
        )
        zip.delete()
    }

    @Test
    fun staticLoaderEvidenceReportsRequestedState() {
        val app = InstalledQuestApp("com.example.game", "1")
        val detection = ModLoaderDetection(
            app.packageName,
            mapOf(ModLoaderKind.SCOTLAND2 to ModLoaderEvidence(
                ModLoaderKind.SCOTLAND2,
                ModLoaderStatus.DETECTED
            ))
        )
        val detector = StaticModLoaderDetector(detection)
        assertEquals(
            ModLoaderStatus.DETECTED,
            detector.detect("SERIAL", app).statusFor(setOf(ModLoaderKind.SCOTLAND2))
        )
        assertEquals(
            ModLoaderStatus.UNKNOWN,
            detector.detect("SERIAL", app).statusFor(setOf(ModLoaderKind.QUEST_LOADER))
        )
    }

    @Test
    fun adbLoaderDetectorFailsClosedWithoutAuthenticatedArtifactReader() {
        val app = InstalledQuestApp("com.example.game", "1")
        val adb = LoaderEvidenceAdb()
        val detection = AdbModLoaderDetector(adb, null).detect("SERIAL", app)

        assertTrue(detection.evidence.values.all { it.status == ModLoaderStatus.UNKNOWN })
        assertTrue(adb.commands.isEmpty())
    }

    @Test
    fun authenticatedQuestPatcherTagDetectsQuestLoader() {
        val apk = zipOf(
            "AndroidManifest.xml" to """<manifest package="com.example.game"/>""",
            "modded.json" to
                """{"patcherName":"QuestPatcher","patcherVersion":"2.0.0",
                   "modloaderName":"QuestLoader","modloaderVersion":"1.2974.57485"}"""
        )
        val app = InstalledQuestApp("com.example.game", "1")
        val adb = ArtifactAdb(apk, "/data/app/~~foo==/com.example.game-bar==/base.apk")
        val detection = AdbAuthenticatedModLoaderArtifactVerifier(adb)
            .verify("SERIAL", app)

        assertEquals(ModLoaderStatus.DETECTED, detection?.statusFor(setOf(ModLoaderKind.QUEST_LOADER)))
        assertEquals(ModLoaderStatus.NOT_DETECTED, detection?.statusFor(setOf(ModLoaderKind.SCOTLAND2)))
        assertEquals(ModLoaderStatus.UNKNOWN, detection?.statusFor(setOf(ModLoaderKind.LEMON_LOADER)))
        assertTrue(adb.pullCalls == 1)
        apk.delete()
    }

    @Test
    fun artifactVerifierRejectsOversizedWrongPackageAndUnsafeRemotePaths() {
        val apk = zipOf(
            "AndroidManifest.xml" to """<manifest package="com.example.game"/>""",
            "modded.json" to
                """{"patcherName":"QuestPatcher","patcherVersion":"2.0.0",
                   "modloaderName":"Scotland2","modloaderVersion":"0.6.0"}"""
        )
        val app = InstalledQuestApp("com.example.game", "1")
        assertNull(
            AdbAuthenticatedModLoaderArtifactVerifier(
                ArtifactAdb(apk, "/data/app/com.example.game/base.apk"),
                maxApkBytes = 4L
            ).verify("SERIAL", app)
        )
        assertNull(
            AdbAuthenticatedModLoaderArtifactVerifier(
                ArtifactAdb(apk, "/data/app/~~foo==/com.other.game-bar==/base.apk"),
            ).verify("SERIAL", app)
        )
        assertNull(
            AdbAuthenticatedModLoaderArtifactVerifier(
                ArtifactAdb(apk, "/data/app/../com.example.game/base.apk"),
            ).verify("SERIAL", app)
        )
        apk.delete()
    }

    @Test
    fun executorRechecksLoaderBeforeWritingCodeMod() {
        runBlocking {
            val zip = zipOfBytes("Author.Mod/Example.dll" to managedPeCliPayload())
            val detected = ModLoaderDetection(
                bonelab.packageName,
                mapOf(ModLoaderKind.LEMON_LOADER to ModLoaderEvidence(
                    ModLoaderKind.LEMON_LOADER,
                    ModLoaderStatus.DETECTED
                ))
            )
            val analysis = analyzer.analyze(zip, bonelab, detected)
            assertTrue(analysis.installable)

            val adb = RecordingAdb()
            val detector = object : ModLoaderDetector {
                var calls = 0
                override fun detect(serial: String, app: InstalledQuestApp): ModLoaderDetection {
                    calls++
                    val status = if (calls == 1) {
                        ModLoaderStatus.DETECTED
                    } else {
                        ModLoaderStatus.NOT_DETECTED
                    }
                    return ModLoaderDetection(
                        app.packageName,
                        mapOf(ModLoaderKind.LEMON_LOADER to ModLoaderEvidence(
                            ModLoaderKind.LEMON_LOADER,
                            status
                        ))
                    )
                }
            }
            val result = ModsManager(
                adb,
                detector,
                apkEvidenceRefresher = testApkEvidence()
            )
                .executeInstallPlan("SERIAL", zip, analysis.plan.bindToDevice("SERIAL"))

            assertFalse(result.success)
            assertEquals(0, adb.pushCalls)
            assertTrue(detector.calls >= 2)
            zip.delete()
        }
    }

    @Test
    fun executorRejectsTamperedSecurityProjectionBeforeAnyPush() {
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
            val tampered = analysis.plan.bindToDevice("SERIAL").copy(
                strategy = ModInstallStrategy.NONE,
                destinationRoot = "/sdcard/Android/data/com.StressLevelZero.BONELAB/files"
            )
            val adb = RecordingAdb()
            val result = ModsManager(
                adb,
                apkEvidenceRefresher = testApkEvidence()
            )
                .executeInstallPlan("SERIAL", zip, tampered)

            assertFalse(result.success)
            assertEquals(0, adb.pushCalls)
            zip.delete()
        }
    }

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("nfvr-engine-test-", ".zip")
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

    private fun zipOfBytes(vararg entries: Pair<String, ByteArray>): File {
        val file = File.createTempFile("nfvr-engine-bytes-test-", ".zip")
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content)
                output.closeEntry()
            }
        }
        return file
    }

    private fun managedPeCliPayload(): ByteArray {
        val bytes = ByteArray(512)
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        bytes[0x3c] = 0x80.toByte()
        bytes[0x80] = 'P'.code.toByte()
        bytes[0x81] = 'E'.code.toByte()
        bytes[0x98] = 0x0b.toByte()
        bytes[0x99] = 0x02.toByte()
        bytes[0x178] = 0x01.toByte()
        bytes[0x17c] = 0x01.toByte()
        return bytes
    }

    private fun arm64ElfPayload(): ByteArray = ByteArray(64).also {
        it[0] = 0x7f
        it[1] = 'E'.code.toByte()
        it[2] = 'L'.code.toByte()
        it[3] = 'F'.code.toByte()
        it[4] = 2
        it[5] = 1
        it[18] = 0xb7.toByte()
        it[19] = 0
    }

    private fun pcExePayload(): ByteArray = ByteArray(64).also {
        it[0] = 'M'.code.toByte()
        it[1] = 'Z'.code.toByte()
    }

    private fun pcElfPayload(): ByteArray = ByteArray(64).also {
        it[0] = 0x7f
        it[1] = 'E'.code.toByte()
        it[2] = 'L'.code.toByte()
        it[3] = 'F'.code.toByte()
        it[4] = 2
        it[5] = 1
        it[18] = 0x3e
        it[19] = 0
    }

    private class RecordingAdb : AdbClient(BundledAdb(HostOs.LINUX)) {
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

    private class LoaderEvidenceAdb : AdbClient(BundledAdb(HostOs.LINUX)) {
        val commands = mutableListOf<List<String>>()

        override fun shell(serial: String, vararg args: String): CmdResult {
            commands += args.toList()
            return when {
                args.firstOrNull() == "dumpsys" ->
                    CmdResult(0, "QuestLoader package metadata\n", "")
                args.firstOrNull() == "pm" ->
                    CmdResult(0, "package:/data/app/base.apk=com.example.game\n", "")
                args.lastOrNull()?.contains("/Modloader") == true ->
                    CmdResult(0, "early_mods libs\n", "")
                args.lastOrNull()?.contains("/files/Mods") == true ->
                    CmdResult(0, "LemonLoader\n", "")
                else -> CmdResult(0, "files\n", "")
            }
        }
    }

    private class ArtifactAdb(
        private val apk: File,
        private val remotePath: String
    ) : AdbClient(BundledAdb(HostOs.LINUX)) {
        var pullCalls = 0

        override fun shell(serial: String, vararg args: String): CmdResult =
            when (args.firstOrNull()) {
                "pm" -> CmdResult(0, "package:$remotePath\n", "")
                "stat" -> CmdResult(0, "${apk.length()}\n", "")
                else -> CmdResult(1, "", "unsupported test command")
            }

        override fun pullReadOnly(serial: String, remotePath: String, localFile: File): CmdResult {
            pullCalls++
            apk.copyTo(localFile, overwrite = true)
            return CmdResult(0, "", "")
        }
    }
}
