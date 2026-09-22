import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for the install CTA state machine.
 *
 * The install action must derive ONLY from real installation conditions
 * (analysis outcome, binding, confirmations, execution state) and never
 * from a numeric workflow stage.  A READY plan at ANY stage — review or
 * install — must expose the CTA, and a ready message must never appear
 * without a reachable action.
 */
class ModInstallActionStateTest {
    private val bonelab = InstalledQuestApp(
        ModPackageAnalyzer.BONELAB_PACKAGE_ID,
        "1.2974.57485",
        2974L
    )

    private fun palletArchive(): File = zipOf(
        "ProbePallet/pallet.json" to
            """{"name":"Probe","packageId":"com.StressLevelZero.BONELAB","gameVersion":"1.2974.57485","version":"1.0.0"}""",
        "ProbePallet/content.assetbundle" to "bundle"
    )

    private fun qmodArchive(withDependencies: Boolean): File {
        val deps = if (withDependencies) {
            ""","dependencies":[{"id":"beatsaber-hook","version":"^6.0.0"}]"""
        } else ""
        return zipOf(
            "mod.json" to
                """{"_QPVersion":"1.2.0","id":"probe","name":"Probe","author":"NFVR","version":"1.0.0","packageId":"com.beatgames.beatsaber","modloader":"Scotland2","modFiles":["probe.dat"]$deps}""",
            "probe.dat" to "payload"
        )
    }

    private fun scotland2Detection() = ModLoaderDetection(
        "com.beatgames.beatsaber",
        mapOf(
            ModLoaderKind.SCOTLAND2 to ModLoaderEvidence(
                ModLoaderKind.SCOTLAND2, ModLoaderStatus.DETECTED, listOf("filesystem evidence")
            )
        )
    )

    private fun readyAnalysis(): ModPackageAnalysis {
        val archive = palletArchive()
        try {
            val analysis = ModPackageAnalyzer().analyze(archive, bonelab)
            assertTrue(analysis.installable, analysis.message)
            assertEquals(ModInstallOutcome.DIRECT_INSTALL_READY, analysis.outcome)
            return analysis
        } finally {
            archive.delete()
        }
    }

    @Test
    fun directReadyPlanExposesCtaWithoutAnyStage() {
        val analysis = readyAnalysis()
        // The resolver accepts no stage argument by construction; a reviewed
        // plan (the former step-07 case) exposes READY exactly like review.
        val reviewed = resolveModInstallActionState(
            analysis, operationBound = true, destinationConfirmed = true,
            installing = false, executionProgress = null, installSucceeded = false
        )
        assertEquals(ModInstallActionState.READY, reviewed)
    }

    @Test
    fun ctaHiddenUntilOperationBound() {
        val analysis = readyAnalysis()
        assertEquals(
            ModInstallActionState.HIDDEN,
            resolveModInstallActionState(
                analysis, operationBound = false, destinationConfirmed = true,
                installing = false, executionProgress = null, installSucceeded = false
            )
        )
    }

    @Test
    fun ctaHiddenUntilDestinationConfirmed() {
        val archive = zipOf("data.bin" to "payload")
        try {
            val discovery = ModDirectoryDiscovery(
                serial = "SERIAL",
                packageId = bonelab.packageName,
                candidates = listOf(
                    ModDirectoryCandidate(
                        packageId = bonelab.packageName,
                        path = "/sdcard/Android/data/${bonelab.packageName}/files/Mods",
                        exists = true,
                        source = "probe"
                    )
                )
            )
            val analysis = ModPackageAnalyzer().analyze(archive, bonelab, null, discovery)
            // Generic proposals always require explicit confirmation.
            assertTrue(genericDestinationNeedsConfirmation(analysis))
            assertEquals(
                ModInstallActionState.HIDDEN,
                resolveModInstallActionState(
                    analysis, operationBound = true, destinationConfirmed = false,
                    installing = false, executionProgress = null, installSucceeded = false
                )
            )
        } finally {
            archive.delete()
        }
    }

    @Test
    fun blockedPlansNeverExposeCta() {
        val archive = palletArchive()
        try {
            val nomad = InstalledQuestApp(ModPackageAnalyzer.NOMAD_PACKAGE_ID, "1.3.1", 260730005L)
            val mismatched = ModPackageAnalyzer().analyze(archive, nomad)
            assertFalse(mismatched.installable)
            assertEquals(
                ModInstallActionState.HIDDEN,
                resolveModInstallActionState(
                    mismatched, operationBound = true, destinationConfirmed = true,
                    installing = false, executionProgress = null, installSucceeded = false
                )
            )
        } finally {
            archive.delete()
        }
        val qmod = qmodArchive(withDependencies = true)
        try {
            val beatsaber = InstalledQuestApp("com.beatgames.beatsaber", "1.40.8_7379", 1716L)
            val blocked = ModPackageAnalyzer().analyze(qmod, beatsaber, scotland2Detection())
            assertTrue(blocked.installPlan.preconditions.any { it.code == "DEPENDENCIES_REQUIREMENT" })
            assertEquals(
                ModInstallActionState.HIDDEN,
                resolveModInstallActionState(
                    blocked, operationBound = true, destinationConfirmed = true,
                    installing = false, executionProgress = null, installSucceeded = false
                )
            )
        } finally {
            qmod.delete()
        }
    }

    @Test
    fun readyQmodWithoutDependenciesExposesCta() {
        val qmod = qmodArchive(withDependencies = false)
        try {
            val beatsaber = InstalledQuestApp("com.beatgames.beatsaber", "1.40.8_7379", 1716L)
            val analysis = ModPackageAnalyzer().analyze(qmod, beatsaber, scotland2Detection())
            assertTrue(analysis.installable, analysis.message)
            assertEquals(
                ModInstallActionState.READY,
                resolveModInstallActionState(
                    analysis, operationBound = true, destinationConfirmed = true,
                    installing = false, executionProgress = null, installSucceeded = false
                )
            )
        } finally {
            qmod.delete()
        }
    }

    @Test
    fun executionStatesMapToInstallingVerifyingSuccessFailed() {
        val analysis = readyAnalysis()
        fun transferring() = ModsManager.ModExecutionProgress(
            ModsManager.ModInstallPhase.TRANSFERRING, 0.3, "نقل"
        )
        assertEquals(
            ModInstallActionState.INSTALLING,
            resolveModInstallActionState(
                analysis, true, true, installing = true,
                executionProgress = transferring(), installSucceeded = false
            )
        )
        assertEquals(
            ModInstallActionState.VERIFYING,
            resolveModInstallActionState(
                analysis, true, true, installing = true,
                executionProgress = ModsManager.ModExecutionProgress(
                    ModsManager.ModInstallPhase.VERIFYING, 0.96, "تحقق"
                ),
                installSucceeded = false
            )
        )
        assertEquals(
            ModInstallActionState.SUCCESS,
            resolveModInstallActionState(
                analysis, true, true, installing = false,
                executionProgress = ModsManager.ModExecutionProgress(
                    ModsManager.ModInstallPhase.COMPLETED, 1.0, "اكتمل"
                ),
                installSucceeded = true
            )
        )
        assertEquals(
            ModInstallActionState.FAILED,
            resolveModInstallActionState(
                analysis, true, true, installing = false,
                executionProgress = ModsManager.ModExecutionProgress(
                    ModsManager.ModInstallPhase.FAILED, null, "فشل"
                ),
                installSucceeded = false
            )
        )
    }

    @Test
    fun readyMessageNeverAppearsWithoutReachableAction() {
        val analysis = readyAnalysis()
        val state = resolveModInstallActionState(
            analysis, true, true, false, null, false
        )
        val line = modInstallCtaStatusLine(state, analysis)
        assertEquals(ModInstallActionState.READY, state)
        assertTrue(line != null && line.contains("الخطة جاهزة"))
        // Every non-ready state either shows progress/success/failure copy
        // or nothing at all — never a ready promise without the CTA.
        val hidden = modInstallCtaStatusLine(ModInstallActionState.HIDDEN, analysis)
        assertEquals(null, hidden)
    }

    @Test
    fun largePlansCollapseByDefaultAndExpandFully() {
        val mappings = (1..82).map { index ->
            ModFileMapping("src$index.bundle", "/sdcard/Android/data/x/files/Mods/M/d$index.bundle", sizeBytes = index.toLong())
        }
        val preview = modFileMappingsPreview(mappings, expanded = false)
        assertEquals(5, preview.size)
        assertEquals(mappings.take(5), preview)
        assertEquals(82, modFileMappingsPreview(mappings, expanded = true).size)
        assertEquals(3, modFileMappingsPreview(mappings.take(3), expanded = false).size)
    }

    @Test
    fun supportedGamesOrderBeforeOtherApplications() {
        val system = InstalledQuestApp("com.oculus.system", "1", 1L, "System")
        val beat = InstalledQuestApp("com.beatgames.beatsaber", "1.40.8_7379", 1716L, "Beat Saber")
        val bone = InstalledQuestApp(ModPackageAnalyzer.BONELAB_PACKAGE_ID, "1.2974.57485", 2974L, "BONELAB")
        val ordered = orderSupportedAppsFirst(listOf(system, beat, bone))
        assertEquals(ModPackageAnalyzer.BONELAB_PACKAGE_ID, ordered[0].packageName)
        assertEquals("com.beatgames.beatsaber", ordered[1].packageName)
        assertEquals("com.oculus.system", ordered[2].packageName)
    }

    @Test
    fun advancedSectionCollapsedByDefault() {
        assertFalse(ADVANCED_MODS_SECTION_DEFAULT_EXPANDED)
    }

    @Test
    fun compactDestinationStaysCustomerReadable() {
        val analysis = readyAnalysis()
        assertEquals("Mods/ProbePallet/", modCompactDestination(analysis))
    }

    @Test
    fun selectionMatchTracksArchiveAndGameOnly() {
        val archive = palletArchive()
        try {
            val first = ModPackageAnalyzer().analyze(archive, bonelab)
            assertTrue(modSelectionMatchesAnalysis(bonelab, first.installPlan.archiveIdentity?.sha256, first))
            assertFalse(modSelectionMatchesAnalysis(bonelab, "00".repeat(32), first))
            assertFalse(
                modSelectionMatchesAnalysis(
                    bonelab.copy(versionName = "9.9.9"), first.installPlan.archiveIdentity?.sha256, first
                )
            )
        } finally {
            archive.delete()
        }
    }

    @Test
    fun safetyGatesUnchangedForBlockedOutcomes() {
        val archive = zipOf("plugins/native.so" to "native")
        try {
            val gorilla = InstalledQuestApp("com.AnotherAxiom.GorillaTag", "1.1.146", 29954L)
            val analysis = ModPackageAnalyzer().analyze(archive, gorilla)
            assertEquals(ModInstallOutcome.APK_PATCH_REQUIRED, analysis.outcome)
            assertEquals(
                ModInstallActionState.HIDDEN,
                resolveModInstallActionState(
                    analysis, true, true, false, null, false
                )
            )
        } finally {
            archive.delete()
        }
    }

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("nfvr-cta-", ".zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }
}
