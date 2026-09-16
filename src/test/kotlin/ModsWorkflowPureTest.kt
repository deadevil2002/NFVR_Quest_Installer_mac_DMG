import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModsWorkflowPureTest {
    @Test
    fun virtualStumpIsAnExplicitExternalWorkflow() {
        assertEquals(
            ModWorkflowKind.SUPPORTED_EXTERNAL_WORKFLOW,
            classifyModWorkflow(ModPackageType.GORILLA_TAG_VIRTUAL_STUMP)
        )
        assertTrue(isSupportedExternalWorkflow(ModPackageType.GORILLA_TAG_VIRTUAL_STUMP))
        assertFalse(isSupportedExternalWorkflow(ModPackageType.QMOD))
    }

    @Test
    fun warningCodesAreShownOnce() {
        val warnings = listOf(
            ModInstallPrecondition("TARGET", "first"),
            ModInstallPrecondition("TARGET", "same explanation"),
            ModInstallPrecondition("OTHER", "other")
        )
        assertEquals(listOf("TARGET", "OTHER"), deduplicateModWarnings(warnings).map { it.code })
    }

    @Test
    fun onlyHttpsModIoUrlsCrossTheBrowserBoundary() {
        assertEquals("https://mod.io/g/gorilla-tag/mod/42", validatedHttpsModIoUrl("https://mod.io/g/gorilla-tag/mod/42"))
        assertEquals("https://creator.mod.io/page", validatedHttpsModIoUrl("https://creator.mod.io/page"))
        assertNull(validatedHttpsModIoUrl("http://mod.io/g/gorilla-tag"))
        assertNull(validatedHttpsModIoUrl("https://example.com/g/gorilla-tag"))
        assertNull(validatedHttpsModIoUrl("https://mod.io.evil.example/page"))
        assertFalse(isValidatedHttpsModIoUrl(null))
    }

    @Test
    fun selectedAppCollapsesUntilChangeIsRequested() {
        val app = InstalledQuestApp("com.example.game", versionName = "1")
        assertTrue(shouldCollapseInstalledAppList(app, changeRequested = false))
        assertFalse(shouldCollapseInstalledAppList(app, changeRequested = true))
        assertFalse(shouldCollapseInstalledAppList(null, changeRequested = false))
    }

    @Test
    fun focusIsClearedBeforeAConditionalModsTransition() {
        val events = mutableListOf<String>()

        clearFocusBeforeModsTransition(
            clearFocus = { events += "clear-focus" },
            transition = { events += "change-selection" }
        )

        assertEquals(listOf("clear-focus", "change-selection"), events)
    }

    @Test
    fun targetAndRescanControlsAreLockedWhileInstalling() {
        assertFalse(modsUiControlsEnabled(installing = true))
        assertTrue(modsUiControlsEnabled(installing = false))
    }

    @Test
    fun legacyExternalLogTextIsNotExposedInModsUi() {
        val safe = sanitizeModsLogText(
            "تم تحليل الحزمة\nفتح https://mod.io/g/gorilla-tag في المتصفح\nاكتمل الفحص"
        )

        assertEquals("تم تحليل الحزمة\nاكتمل الفحص", safe)
    }

    @Test
    fun builtInContentTextDoesNotExposeSiteOrBrowserCopy() {
        val safe = sanitizeModsUiText(
            "Virtual Stump is managed by mod.io. Open the page in your browser."
        )

        assertFalse(safe.contains("mod.io", ignoreCase = true))
        assertFalse(safe.contains("browser", ignoreCase = true))
        assertFalse(safe.contains("open", ignoreCase = true))
    }

    @Test
    fun engineOutcomesKeepSafetyColorsDistinct() {
        assertEquals(
            ModsUiStatusTone.SUCCESS,
            modsUiOutcome(testAnalysis(ModInstallOutcome.DIRECT_INSTALL_READY, ModPackageType.QMOD)).tone
        )
        assertEquals(
            ModsUiStatusTone.WARNING,
            modsUiOutcome(testAnalysis(ModInstallOutcome.UNSUPPORTED)).tone
        )
        assertEquals(
            ModsUiStatusTone.WARNING,
            modsUiOutcome(testAnalysis(ModInstallOutcome.REQUIRES_MOD_LOADER, ModPackageType.BONELAB_CODE_MOD)).tone
        )
        assertEquals(
            ModsUiStatusTone.INFO,
            modsUiOutcome(
                testAnalysis(
                    ModInstallOutcome.BUILT_IN_GAME_CONTENT,
                    ModPackageType.GORILLA_TAG_VIRTUAL_STUMP
                )
            ).tone
        )
        assertEquals(
            ModsUiStatusTone.ERROR,
            modsUiOutcome(testAnalysis(ModInstallOutcome.UNSAFE_ARCHIVE)).tone
        )
    }

    @Test
    fun builtInAnalysisIsInformationalAndExplicitlyReportsNoFileChange() {
        val result = modsUiOutcome(
            testAnalysis(
                ModInstallOutcome.BUILT_IN_GAME_CONTENT,
                ModPackageType.GORILLA_TAG_VIRTUAL_STUMP
            )
        )
        assertEquals(ModsUiStatusTone.INFO, result.tone)
        assertTrue(result.action.contains("لا يحتاج"))
        assertTrue(result.changedFiles.contains("لم يتم تغيير"))
    }

    @Test
    fun analysisCompletionNeverCompletesReviewOrInstallation() {
        val builtIn = testAnalysis(
            ModInstallOutcome.BUILT_IN_GAME_CONTENT,
            ModPackageType.GORILLA_TAG_VIRTUAL_STUMP
        )
        val state = modsWorkflowSemantics(
            builtIn,
            operationBound = false,
            destinationConfirmed = false,
            installSucceeded = false
        )

        assertTrue(state.analysisCompleted)
        assertFalse(state.planReviewed)
        assertFalse(state.installCompleted)
        assertFalse(modsWorkflowSemantics(builtIn, false, false, true).installCompleted)
        assertTrue(modInstallUnavailableReason(builtIn, false, false)!!.contains("تديره اللعبة"))
    }

    @Test
    fun onlyBoundDirectReadyPlanEnablesReviewedState() {
        val ready = testAnalysis(ModInstallOutcome.DIRECT_INSTALL_READY, ModPackageType.QMOD)

        assertFalse(modsWorkflowSemantics(ready, false, true, false).planReviewed)
        assertFalse(modsWorkflowSemantics(ready, false, true, true).installCompleted)
        assertTrue(modsWorkflowSemantics(ready, true, true, false).planReviewed)
        assertFalse(modsWorkflowSemantics(ready, true, true, false).installCompleted)
        assertTrue(modsWorkflowSemantics(ready, true, true, true).installCompleted)
        assertTrue(modInstallUnavailableReason(ready, false, true)!!.contains("أعد التحليل"))
        assertNull(modInstallUnavailableReason(ready, true, true))
    }

    @Test
    fun patchAndLoaderResultsRemainLockedWithExactReasons() {
        val patch = testAnalysis(
            ModInstallOutcome.APK_PATCH_REQUIRED,
            ModPackageType.GENERIC_DATA
        )
        val loader = testAnalysis(
            ModInstallOutcome.REQUIRES_MOD_LOADER,
            ModPackageType.QMOD
        )

        assertFalse(modsWorkflowSemantics(patch, true, true, true).planReviewed)
        assertFalse(modsWorkflowSemantics(loader, true, true, true).planReviewed)
        assertTrue(modInstallUnavailableReason(patch, true, true)!!.contains("Patch"))
        assertTrue(modInstallUnavailableReason(loader, true, true)!!.isNotBlank())
    }

    private fun testAnalysis(
        outcome: ModInstallOutcome,
        packageType: ModPackageType = ModPackageType.UNKNOWN
    ): ModPackageAnalysis {
        val installable = outcome == ModInstallOutcome.DIRECT_INSTALL_READY
        return ModPackageAnalysis(
            packageType = packageType,
            recognized = packageType != ModPackageType.UNKNOWN,
            message = "test",
            compatibility = ModCompatibility(installable),
            installPlan = ModInstallPlan(
                installable = installable,
                outcome = outcome,
                packageType = packageType
            )
        )
    }
}
