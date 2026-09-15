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
}