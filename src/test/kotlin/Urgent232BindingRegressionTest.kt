import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression coverage for the 2.3.1 Gorilla device-binding failure.  The
 * policy tests are intentionally pure: ModsManager uses this exact policy
 * after its read-only device inspection, so classifications can be displayed
 * without being converted into operation bindings.
 */
class Urgent232BindingRegressionTest {
    private val gorilla = InstalledQuestApp(
        packageName = "com.AnotherAxiom.GorillaTag",
        versionName = "1.0.0",
        versionCode = 100L
    )
    private val bonelab = InstalledQuestApp(
        packageName = "com.StressLevelZero.BONELAB",
        versionName = "1.2.3",
        versionCode = 123L
    )

    @Test
    fun A_unsupportedSelectedGorillaRemainsDisplayableAndUnbound() {
        val archive = zipOf("unsupported.txt" to "not a mod")
        val analysis = ModPackageAnalyzer().analyze(archive, gorilla)

        assertEquals(ModInstallOutcome.UNSUPPORTED, analysis.outcome)
        assertEquals(gorilla, analysis.plan.reviewedApp)
        assertEquals(gorilla.packageName, analysis.plan.targetPackageId)
        assertNull(ModOperationBindingPolicy.bindExecutable(analysis.plan, "QUEST-A"))
        archive.delete()
    }

    @Test
    fun B_gorillaNativePayloadRequiresPatchAndPreservesIdentity() {
        val archive = zipOf("lib/feature.so" to "native payload")
        val analysis = ModPackageAnalyzer().analyze(archive, gorilla)

        assertEquals(ModInstallOutcome.APK_PATCH_REQUIRED, analysis.outcome)
        assertEquals(gorilla, analysis.plan.reviewedApp)
        assertEquals(gorilla.packageName, analysis.plan.targetPackageId)
        assertNotNull(analysis.plan.archiveIdentity)
        assertNull(analysis.plan.tryBindToDevice("QUEST-A"))
        archive.delete()
    }

    @Test
    fun C_unsafeArchivePreservesAppButNeverBinds() {
        val archive = zipOf("../escape.txt" to "unsafe")
        val analysis = ModPackageAnalyzer().analyze(archive, gorilla)

        assertEquals(ModInstallOutcome.UNSAFE_ARCHIVE, analysis.outcome)
        assertEquals(gorilla, analysis.plan.reviewedApp)
        assertEquals(gorilla.packageName, analysis.plan.targetPackageId)
        assertNull(analysis.plan.archiveIdentity)
        assertNull(analysis.plan.tryBindToDevice("QUEST-A"))
        archive.delete()
    }

    @Test
    fun D_unknownArchiveIsVisibleAndRetainsIdentity() {
        val archive = zipOf("readme.txt" to "unknown")
        val analysis = ModPackageAnalyzer().analyze(archive, gorilla)

        assertFalse(analysis.recognized)
        assertEquals(ModPackageType.UNKNOWN, analysis.packageType)
        assertEquals(gorilla, analysis.plan.reviewedApp)
        assertEquals(gorilla.packageName, analysis.plan.targetPackageId)
        assertNotNull(analysis.plan.archiveIdentity)
        assertNull(analysis.plan.tryBindToDevice("QUEST-A"))
        archive.delete()
    }

    @Test
    fun E_validPlanBindsAllImmutableExecutionIdentity() {
        val archive = zipOf(
            "nfvr-mod.json" to """
                {"schemaVersion":1,"targetPackageId":"${bonelab.packageName}",
                 "files":[{"source":"payload.dat","destination":"payload.dat"}]}
            """.trimIndent(),
            "payload.dat" to "payload"
        )
        val analysis = ModPackageAnalyzer().analyze(archive, bonelab)
        val bound = assertNotNull(analysis.plan.tryBindToDevice("QUEST-A"))
        val binding = assertNotNull(bound.operationBinding)

        assertEquals("QUEST-A", binding.deviceSerial)
        assertEquals(bonelab.packageName, binding.packageId)
        assertEquals(bonelab.versionName, binding.gameVersion)
        assertEquals(analysis.plan.archiveSha256, binding.archiveSha256)
        assertEquals(bonelab.versionCode, binding.gameVersionCode)
        assertEquals(analysis.plan.archiveSha256, bound.archiveSha256)
        assertEquals(bound.analysisPlanId, binding.analysisPlanId)
        assertTrue(bound.analysisPlanId!!.isNotBlank())
        archive.delete()
    }

    @Test
    fun versionlessAppCannotBindButVersionCodeOnlyAppCan() {
        val archive = zipOf(
            "nfvr-mod.json" to """
                {"schemaVersion":1,"targetPackageId":"com.StressLevelZero.BONELAB",
                 "files":[{"source":"payload.dat","destination":"payload.dat"}]}
            """.trimIndent(),
            "payload.dat" to "payload"
        )
        val versionless = bonelab.copy(versionName = null, versionCode = null)
        val codeOnly = bonelab.copy(versionName = null)
        val noEvidence = ModPackageAnalyzer().analyze(archive, versionless)
        val codeEvidence = ModPackageAnalyzer().analyze(archive, codeOnly)

        assertNull(noEvidence.plan.tryBindToDevice("QUEST-A"))
        val bound = assertNotNull(codeEvidence.plan.tryBindToDevice("QUEST-A"))
        assertEquals(codeOnly.versionCode, bound.operationBinding?.gameVersionCode)
        assertTrue(modOperationBindingMatches(
            bound.operationBinding, "QUEST-A", codeOnly,
            bound.archiveSha256, bound.analysisPlanId
        ))
        archive.delete()
    }

    @Test
    fun customerPresentationUsesArabicAndNeverInternalBindingText() {
        val archive = zipOf("lib/native.so" to "native")
        val analysis = ModPackageAnalyzer().analyze(archive, gorilla)
        val text = customerAnalysisMessage(analysis)

        assertTrue(text.contains("تصحيح"))
        assertFalse(text.contains("an installed app is required before device binding"))
        assertFalse(text.contains("APK_PATCH_REQUIRED"))
        assertEquals("حزمة بيانات عامة", customerPackageTypeMessage(ModPackageType.GENERIC_DATA))
        assertFalse(customerPackageTypeMessage(ModPackageType.GENERIC_DATA).contains("GENERIC_DATA"))
        val requirement = analysis.plan.loaderRequirement
        if (requirement != null) {
            val loaderText = customerLoaderRequirementMessage(requirement)
            assertFalse(loaderText.contains(requirement.message))
            assertTrue(loaderText.any { it in '\u0600'..'\u06ff' })
        }
        ModExecutionFailureKind.entries.forEach { kind ->
            val failureText = customerModExecutionFailureMessage(kind)
            assertTrue(failureText.any { it in '\u0600'..'\u06ff' })
            assertFalse(failureText.contains("stderr", ignoreCase = true))
            assertFalse(failureText.contains("Exception", ignoreCase = true))
        }
        archive.delete()
    }

    @Test
    fun confirmedPlanBindsOnlyWithFreshSelectionAndArchive() {
        val archive = zipOf("Mods/ordinary.dat" to "data")
        val discovery = ModDirectoryDiscovery(
            serial = "QUEST-A",
            packageId = bonelab.packageName,
            appVersion = bonelab.versionName,
            candidates = listOf(
                ModDirectoryCandidate(
                    bonelab.packageName,
                    "/sdcard/Android/data/${bonelab.packageName}/files/Mods",
                    true,
                    "read-only test"
                )
            )
        )
        val analysis = ModPackageAnalyzer().analyze(archive, bonelab, null, discovery)
        val token = assertNotNull(analysis.plan.confirmation).token
        val confirmed = analysis.copy(
            installPlan = analysis.plan
                .copy(reviewedDeviceSerial = discovery.serial)
                .confirmDestination(token)
        )
        val bound = assertNotNull(bindConfirmedModPlan(
            confirmed, "QUEST-A", bonelab, confirmed.plan.archiveSha256
        ))
        assertNotNull(bound.operationBinding)
        assertNotNull(bound.analysisPlanId)
        assertNull(bindConfirmedModPlan(
            confirmed, "QUEST-B", bonelab, confirmed.plan.archiveSha256
        ))
        assertNull(bindConfirmedModPlan(
            confirmed, "QUEST-A", bonelab.copy(versionName = "stale"), confirmed.plan.archiveSha256
        ))
        assertNull(bindConfirmedModPlan(confirmed, "QUEST-A", bonelab, "stale"))
        archive.delete()
    }

    @Test
    fun F_corruptArchiveReturnsArchiveErrorWithoutIdentityOrBinding() {
        val archive = File.createTempFile("nfvr-corrupt-", ".zip")
        archive.writeText("not a ZIP archive")
        val analysis = ModPackageAnalyzer().analyze(archive, gorilla)

        assertEquals(ModInstallOutcome.UNSAFE_ARCHIVE, analysis.outcome)
        assertTrue(analysis.message.contains("ZIP", ignoreCase = true) ||
            analysis.message.contains("archive", ignoreCase = true))
        assertEquals(gorilla, analysis.plan.reviewedApp)
        assertNull(analysis.plan.archiveIdentity)
        assertNull(analysis.plan.tryBindToDevice("QUEST-A"))
        archive.delete()
    }

    @Test
    fun G_noSelectedAppIsAVisiblePrerequisiteNotAnException() {
        val archive = zipOf(
            "nfvr-mod.json" to """
                {"schemaVersion":1,"targetPackageId":"${bonelab.packageName}",
                 "files":[{"source":"payload.dat","destination":"payload.dat"}]}
            """.trimIndent(),
            "payload.dat" to "payload"
        )
        val analysis = ModPackageAnalyzer().analyze(archive, null)

        assertTrue(analysis.plan.preconditions.any { it.code == "TARGET_APP_REQUIRED" })
        assertNull(analysis.plan.reviewedApp)
        assertNull(analysis.plan.tryBindToDevice("QUEST-A"))
        archive.delete()
    }

    @Test
    fun virtualStumpPreservesSelectedAppWithoutExecutableBinding() {
        val archive = zipOf(
            "package.json" to """{"packageId":"$GORILLA_TAG_VIRTUAL_STUMP_PACKAGE_ID"}"""
        )
        val analysis = ModPackageAnalyzer().analyze(archive, gorilla)

        assertEquals(ModInstallOutcome.BUILT_IN_GAME_CONTENT, analysis.outcome)
        assertEquals(gorilla, analysis.plan.reviewedApp)
        assertEquals(gorilla.packageName, analysis.plan.targetPackageId)
        assertNull(analysis.plan.tryBindToDevice("QUEST-A"))
        archive.delete()
    }

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("nfvr-urgent-232-", ".zip")
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