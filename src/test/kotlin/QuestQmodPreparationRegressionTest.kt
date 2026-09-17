import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Focused QMOD intake regressions.  These tests deliberately stop at the
 * analyzer boundary: preparation/readiness evidence is supplied as the
 * read-only loader detection input and is never invented by package analysis.
 */
class QuestQmodPreparationRegressionTest {
    private val gorilla = InstalledQuestApp(
        "com.AnotherAxiom.GorillaTag", "1.0.0", 100L
    )
    private val bonelab = InstalledQuestApp(
        "com.StressLevelZero.BONELAB", "1.2.3", 123L
    )

    @Test
    fun stockGorillaQmodRequiresPreparedLoaderEvidence() {
        val archive = qmod(
            """{"_QPVersion":"1.2.0","name":"Stock","id":"stock","author":"NFVR",
                "version":"1.0.0","packageId":"${gorilla.packageName}",
                "modloader":"QuestLoader","modFiles":["mod.dat"]}""",
            "mod.dat" to "payload"
        )

        val analysis = ModPackageAnalyzer().analyze(archive, gorilla)

        assertEquals(ModInstallOutcome.REQUIRES_MOD_LOADER, analysis.outcome)
        assertFalse(analysis.installable)
        assertTrue(analysis.plan.preconditions.any { it.code == "MOD_LOADER_UNKNOWN" })
        assertEquals(ModWorkflowRoute.LOADER_CODE_MOD, routeModWorkflow(analysis).route)
        assertTrue(modsPreparationRequired(analysis))
        assertTrue(modsPreparationIsLoaderPanel(analysis))
        archive.delete()
    }

    @Test
    fun preparedCompatibleGorillaQmodMapsDirectlyToQuestLoaderMods() {
        val archive = qmod(
            """{"_QPVersion":"1.2.0","name":"Prepared","id":"prepared","author":"NFVR",
                "version":"1.0.0","packageId":"${gorilla.packageName}",
                "modloader":"QuestLoader","modFiles":["mod.dat"],
                "libraryFiles":["lib.dat"]}""",
            "mod.dat" to "mod",
            "lib.dat" to "library"
        )
        val analysis = ModPackageAnalyzer().analyze(archive, gorilla, questLoaderEvidence())

        assertTrue(analysis.installable)
        assertEquals(
            "/sdcard/Android/data/${gorilla.packageName}/files/mods/mod.dat",
            analysis.plan.mappings.single { it.sourcePath == "mod.dat" }.destinationPath
        )
        assertEquals(
            "/sdcard/Android/data/${gorilla.packageName}/files/libs/lib.dat",
            analysis.plan.mappings.single { it.sourcePath == "lib.dat" }.destinationPath
        )
        archive.delete()
    }

    @Test
    fun gorillaFileCopiesStillRequireQuestLoaderEvidenceWithoutModloaderField() {
        val archive = qmod(
            """{"_QPVersion":"1.2.0","name":"Copy","id":"copy","author":"NFVR",
                "version":"1.0.0","packageId":"${gorilla.packageName}",
                "fileCopies":[{"name":"mod.dat",
                "destination":"/sdcard/Android/data/${gorilla.packageName}/files/mods/mod.dat"}]}""",
            "mod.dat" to "payload"
        )

        val analysis = ModPackageAnalyzer().analyze(archive, gorilla)

        assertFalse(analysis.installable)
        assertTrue(analysis.plan.preconditions.any {
            it.code == "MOD_LOADER_UNKNOWN"
        })
        val prepared = ModPackageAnalyzer().analyze(archive, gorilla, questLoaderEvidence())
        assertTrue(prepared.installable)
        assertEquals(
            "/sdcard/Android/data/${gorilla.packageName}/files/mods/mod.dat",
            prepared.plan.mappings.single().destinationPath
        )
        archive.delete()
    }

    @Test
    fun qmodRejectsWrongPackageVersionAndLoader() {
        val wrongPackage = qmod(
            """{"_QPVersion":"1.2.0","name":"Wrong","id":"wrong","author":"NFVR",
                "version":"1.0.0","packageId":"com.example.other","packageVersion":"1.0.0",
                "modloader":"QuestLoader","modFiles":["mod.dat"]}""",
            "mod.dat" to "payload"
        )
        val wrongPackageAnalysis = ModPackageAnalyzer().analyze(wrongPackage, gorilla)
        assertTrue(wrongPackageAnalysis.plan.preconditions.any {
            it.code == "TARGET_PACKAGE_MISMATCH"
        })
        wrongPackage.delete()

        val wrongVersion = qmod(
            """{"_QPVersion":"1.2.0","name":"Wrong version","id":"wrong-version","author":"NFVR",
                "version":"1.0.0","packageId":"${gorilla.packageName}","packageVersion":"9.9.9",
                "modloader":"QuestLoader","modFiles":["mod.dat"]}""",
            "mod.dat" to "payload"
        )
        val wrongVersionAnalysis = ModPackageAnalyzer().analyze(
            wrongVersion, gorilla, questLoaderEvidence()
        )
        assertFalse(wrongVersionAnalysis.installable)
        assertTrue(wrongVersionAnalysis.plan.preconditions.any {
            it.code == "GAME_VERSION_UNSUPPORTED"
        })
        wrongVersion.delete()

        val wrongLoader = qmod(
            """{"_QPVersion":"1.2.0","name":"Wrong loader","id":"wrong-loader","author":"NFVR",
                "version":"1.0.0","packageId":"${gorilla.packageName}",
                "modloader":"LemonLoader","modFiles":["mod.dat"]}""",
            "mod.dat" to "payload"
        )
        val wrongLoaderAnalysis = ModPackageAnalyzer().analyze(wrongLoader, gorilla)
        assertFalse(wrongLoaderAnalysis.installable)
        assertTrue(wrongLoaderAnalysis.plan.preconditions.any {
            it.code == "UNSUPPORTED_MOD_LOADER"
        })
        wrongLoader.delete()
    }

    @Test
    fun virtualStumpIsBuiltInContentAndHasNoCopyDestination() {
        val archive = zipOf(
            "package.json" to """{"packageId":"$GORILLA_TAG_VIRTUAL_STUMP_PACKAGE_ID",
                "pcFileName":"map.bundle","androidFileName":"map.android"}""".replace("\n", ""),
            "map.android" to "content"
        )

        val analysis = ModPackageAnalyzer().analyze(archive, gorilla)

        assertEquals(ModPackageType.GORILLA_TAG_VIRTUAL_STUMP, analysis.packageType)
        assertEquals(ModInstallOutcome.BUILT_IN_GAME_CONTENT, analysis.outcome)
        assertFalse(analysis.installable)
        assertEquals(null, analysis.plan.destinationRoot)
        assertTrue(analysis.plan.mappings.isEmpty())
        archive.delete()
    }

    @Test
    fun qmodRequiresExactlyOneRootManifestAndRejectsUnsafeZipMetadata() {
        val duplicate = zipOf(
            "mod.json" to """{"packageId":"${bonelab.packageName}","modFiles":["a.dat"]}""",
            "a.dat" to "a",
            "A.dat" to "b"
        )
        val duplicateAnalysis = ModPackageAnalyzer().analyze(duplicate, bonelab)
        assertEquals(ModInstallOutcome.UNSAFE_ARCHIVE, duplicateAnalysis.outcome)
        duplicate.delete()

        val multipleManifests = zipOf(
            "mod.json" to """{"packageId":"${bonelab.packageName}","modFiles":["a.dat"]}""",
            "nfvr-mod.json" to """{"schemaVersion":1,"targetPackageId":"${bonelab.packageName},
                "files":[{"source":"a.dat","destination":"a.dat"}]}""".replace("\n", ""),
            "a.dat" to "a"
        )
        assertEquals(
            ModInstallOutcome.UNSAFE_ARCHIVE,
            ModPackageAnalyzer().analyze(multipleManifests, bonelab).outcome
        )
        multipleManifests.delete()

        val traversal = zipOf("../mod.json" to "{}")
        assertEquals(
            ModInstallOutcome.UNSAFE_ARCHIVE,
            ModPackageAnalyzer().analyze(traversal, bonelab).outcome
        )
        traversal.delete()

        for (alias in listOf("./mod.json", "dir/../mod.json")) {
            val aliased = zipOf(
                alias to """{"packageId":"${bonelab.packageName}","modFiles":["a.dat"]}""",
                "a.dat" to "a"
            )
            assertEquals(
                ModInstallOutcome.UNSAFE_ARCHIVE,
                ModPackageAnalyzer().analyze(aliased, bonelab).outcome
            )
            aliased.delete()
        }

        val bom = zipOfBytes(
            "mod.json" to byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) +
                """{"packageId":"${bonelab.packageName}","modFiles":["a.dat"]}""".toByteArray(),
            "a.dat" to "a".toByteArray()
        )
        val bomAnalysis = ModPackageAnalyzer().analyze(bom, bonelab)
        assertEquals(ModInstallOutcome.UNSAFE_ARCHIVE, bomAnalysis.outcome)
        assertTrue(bomAnalysis.message.contains("BOM", ignoreCase = true))
        bom.delete()

        val zip64 = File.createTempFile("nfvr-qmod-zip64-", ".zip")
        ZipOutputStream(zip64.outputStream()).use { output ->
            val manifest = ZipEntry("mod.json")
            // ZipOutputStream strips a manually supplied ZIP64 field.  Emit
            // a retained non-reserved field first, then patch its ID in both
            // local and central headers so the fixture contains real 0x0001
            // extra fields before the analyzer sees it.
            manifest.extra = byteArrayOf(
                0x34, 0x12, 0x10, 0x00,
                0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
            )
            output.putNextEntry(manifest)
            output.write("""{"packageId":"${bonelab.packageName}","modFiles":["a.dat"]}""".toByteArray())
            output.closeEntry()
            output.putNextEntry(ZipEntry("a.dat"))
            output.write("a".toByteArray())
            output.closeEntry()
        }
        val zip64Bytes = zip64.readBytes()
        val zip64Placeholder = byteArrayOf(0x34, 0x12, 0x10, 0x00)
        var patchedZip64Fields = 0
        for (offset in 0..zip64Bytes.size - zip64Placeholder.size) {
            if (zip64Placeholder.indices.all { index ->
                    zip64Bytes[offset + index] == zip64Placeholder[index]
                }
            ) {
                zip64Bytes[offset] = 0x01
                zip64Bytes[offset + 1] = 0x00
                patchedZip64Fields++
            }
        }
        assertEquals(2, patchedZip64Fields)
        zip64.writeBytes(zip64Bytes)
        assertEquals(
            ModInstallOutcome.UNSAFE_ARCHIVE,
            ModPackageAnalyzer().analyze(zip64, bonelab).outcome
        )
        zip64.delete()
    }

    @Test
    fun qmodSchemaAndDependenciesAreBlockingNotExecutableInstructions() {
        val unsupportedSchema = qmod(
            """{"_QPVersion":"9.9.9","name":"Schema","id":"schema","author":"NFVR",
                "version":"1.0.0","packageId":"${bonelab.packageName}",
                "modFiles":["mod.dat"]}""",
            "mod.dat" to "payload"
        )
        val schemaAnalysis = ModPackageAnalyzer().analyze(unsupportedSchema, bonelab)
        assertFalse(schemaAnalysis.installable)
        assertTrue(schemaAnalysis.plan.preconditions.any {
            it.code == "UNSUPPORTED_QMOD_VERSION"
        })
        unsupportedSchema.delete()

        val dependency = qmod(
            """{"_QPVersion":"1.2.0","name":"Dependency","id":"dependency","author":"NFVR",
                "version":"1.0.0","packageId":"${bonelab.packageName}",
                "dependencies":[{"id":"base","version":">=1.0.0"}],
                "modFiles":["mod.dat"]}""",
            "mod.dat" to "payload"
        )
        val dependencyAnalysis = ModPackageAnalyzer().analyze(dependency, bonelab)
        assertFalse(dependencyAnalysis.installable)
        assertTrue(dependencyAnalysis.plan.preconditions.any {
            it.code == "DEPENDENCIES_REQUIREMENT"
        })
        dependency.delete()

        val emptyDependencies = qmod(
            """{"_QPVersion":"1.2.0","name":"Empty","id":"empty","author":"NFVR",
                "version":"1.0.0","packageId":"${bonelab.packageName}",
                "dependencies":{},"modFiles":["mod.dat"]}""",
            "mod.dat" to "payload"
        )
        val emptyAnalysis = ModPackageAnalyzer().analyze(emptyDependencies, bonelab)
        assertTrue(emptyAnalysis.plan.preconditions.any {
            it.code == "INVALID_DEPENDENCY"
        })
        emptyDependencies.delete()

        val optional = qmod(
            """{"_QPVersion":"1.2.0","name":"Optional","id":"optional","author":"NFVR",
                "version":"1.0.0","packageId":"${bonelab.packageName}",
                "dependencies":[{"id":"base","version":"^1.0.0","required":false}],
                "modFiles":["mod.dat"]}""",
            "mod.dat" to "payload"
        )
        val optionalAnalysis = ModPackageAnalyzer().analyze(optional, bonelab)
        assertFalse(optionalAnalysis.installable)
        assertTrue(optionalAnalysis.plan.preconditions.any {
            it.code == "OPTIONAL_DEPENDENCIES_UNVERIFIED"
        })
        optional.delete()

        val numericPackageVersion = qmod(
            """{"_QPVersion":"1.2.0","name":"Numeric","id":"numeric","author":"NFVR",
                "version":"1.0.0","packageId":"${bonelab.packageName}",
                "packageVersion":123,"modFiles":["mod.dat"]}""",
            "mod.dat" to "payload"
        )
        val numericVersionAnalysis = ModPackageAnalyzer().analyze(numericPackageVersion, bonelab)
        assertTrue(numericVersionAnalysis.plan.preconditions.any {
            it.code == "INVALID_PACKAGE_VERSION"
        })
        numericPackageVersion.delete()

        val numericDependency = qmod(
            """{"_QPVersion":"1.2.0","name":"Numeric dependency","id":"numeric-dependency",
                "author":"NFVR","version":"1.0.0","packageId":"${bonelab.packageName}",
                "dependencies":[{"id":"base","version":7}],"modFiles":["mod.dat"]}""",
            "mod.dat" to "payload"
        )
        val numericDependencyAnalysis = ModPackageAnalyzer().analyze(numericDependency, bonelab)
        assertTrue(numericDependencyAnalysis.plan.preconditions.any {
            it.code == "INVALID_DEPENDENCY"
        })
        numericDependency.delete()

        val numericCopyName = qmod(
            """{"_QPVersion":"1.2.0","name":"Numeric copy","id":"numeric-copy",
                "author":"NFVR","version":"1.0.0","packageId":"${bonelab.packageName}",
                "fileCopies":[{"name":123,
                "destination":"/sdcard/ModData/${bonelab.packageName}/numeric.dat"}]}""",
            "123" to "payload"
        )
        val numericCopyAnalysis = ModPackageAnalyzer().analyze(numericCopyName, bonelab)
        assertFalse(numericCopyAnalysis.installable)
        assertTrue(numericCopyAnalysis.plan.preconditions.any {
            it.code == "INVALID_FILE_COPIES"
        })
        numericCopyName.delete()

        val wrongMetadataTypes = qmod(
            """{"_QPVersion":"1.2.0","name":"Wrong metadata","id":"wrong-metadata",
                "author":"NFVR","version":"1.0.0","packageId":"${bonelab.packageName}",
                "description":123,"coverImage":true,"porter":7,"isLibrary":"yes",
                "modFiles":["mod.dat"]}""",
            "mod.dat" to "payload"
        )
        val wrongMetadataAnalysis = ModPackageAnalyzer().analyze(wrongMetadataTypes, bonelab)
        assertFalse(wrongMetadataAnalysis.installable)
        assertTrue(
            wrongMetadataAnalysis.plan.preconditions.count {
                it.code == "INVALID_QMOD_FIELD"
            } >= 4
        )
        wrongMetadataTypes.delete()
    }

    @Test
    fun qmodFileCopiesAndScotland2PhasesKeepExplicitDestinations() {
        val copies = qmod(
            """{"_QPVersion":"1.2.0","name":"Copies","id":"copies","author":"NFVR",
                "version":"1.0.0","packageId":"${bonelab.packageName}",
                "fileCopies":[{"name":"cover.png",
                "destination":"/sdcard/ModData/${bonelab.packageName}/cover.png"}]}""",
            "cover.png" to "cover"
        )
        val copyAnalysis = ModPackageAnalyzer().analyze(copies, bonelab)
        assertTrue(copyAnalysis.installable)
        assertEquals(
            "/sdcard/ModData/${bonelab.packageName}/cover.png",
            copyAnalysis.plan.mappings.single().destinationPath
        )
        copies.delete()

        val phased = qmod(
            """{"_QPVersion":"1.2.0","name":"Phased","id":"phased","author":"NFVR",
                "version":"1.0.0","packageId":"${bonelab.packageName}",
                "modloader":"Scotland2","libraryFiles":["library.dat"],
                "modFiles":["early.dat"],"lateModFiles":["late.dat"]}""",
            "library.dat" to "library",
            "early.dat" to "early",
            "late.dat" to "late"
        )
        val phasedAnalysis = ModPackageAnalyzer().analyze(
            phased, bonelab, ModLoaderDetection(
                bonelab.packageName,
                mapOf(ModLoaderKind.SCOTLAND2 to ModLoaderEvidence(
                    ModLoaderKind.SCOTLAND2, ModLoaderStatus.DETECTED
                ))
            )
        )
        assertTrue(phasedAnalysis.installable)
        assertTrue(phasedAnalysis.plan.mappings.any {
            it.destinationPath.endsWith("/Modloader/libs/library.dat")
        })
        assertTrue(phasedAnalysis.plan.mappings.any {
            it.destinationPath.endsWith("/Modloader/early_mods/early.dat")
        })
        assertTrue(phasedAnalysis.plan.mappings.any {
            it.destinationPath.endsWith("/Modloader/mods/late.dat")
        })
        phased.delete()

        val reusedSource = qmod(
            """{"_QPVersion":"1.2.0","name":"Reuse","id":"reuse","author":"NFVR",
                "version":"1.0.0","packageId":"${bonelab.packageName}",
                "fileCopies":[{"name":"cover.png",
                "destination":"/sdcard/ModData/${bonelab.packageName}/one.png"},
                {"name":"cover.png",
                "destination":"/sdcard/ModData/${bonelab.packageName}/two.png"}]}""",
            "cover.png" to "cover"
        )
        val reusedAnalysis = ModPackageAnalyzer().analyze(reusedSource, bonelab)
        assertTrue(reusedAnalysis.installable)
        assertEquals(2, reusedAnalysis.plan.mappings.size)
        reusedSource.delete()
    }

    private fun questLoaderEvidence() = ModLoaderDetection(
        gorilla.packageName,
        mapOf(
            ModLoaderKind.QUEST_LOADER to ModLoaderEvidence(
                ModLoaderKind.QUEST_LOADER, ModLoaderStatus.DETECTED,
                listOf("authenticated preparation evidence")
            )
        )
    )

    private fun qmod(metadata: String, vararg payload: Pair<String, String>): File =
        zipOf("mod.json" to metadata.replace("\n", ""), *payload)

    private fun zipOf(vararg entries: Pair<String, String>): File =
        zipOfBytes(*entries.map { it.first to it.second.toByteArray() }.toTypedArray())

    private fun zipOfBytes(vararg entries: Pair<String, ByteArray>): File {
        val file = File.createTempFile("nfvr-qmod-regression-", ".zip")
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content)
                output.closeEntry()
            }
        }
        return file
    }
}