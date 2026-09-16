import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DesktopSectionStateTest {
    @Test
    fun sectionStateContainersDoNotShareOperationalValues(): Unit {
        val install = GameInstallState(selectedFolder = "game", result = "done")
        val mods = QuestModsState(selectedZip = "mod.zip", operation = "analyzing")
        val pcvr = PcvrReadinessState(operation = "scanning", result = "ready")

        assertEquals("game", install.selectedFolder)
        assertEquals("mod.zip", mods.selectedZip)
        assertEquals("scanning", pcvr.operation)
        // Updating one immutable section value cannot mutate another section.
        val updatedInstall = install.copy(progress = install.progress.copy(completed = 1))
        assertEquals("analyzing", mods.operation)
        assertEquals("ready", pcvr.result)
        assertEquals(1, updatedInstall.progress.completed)
    }

    @Test
    fun pickerBusyIsOwnedByAttemptedSectionOnly(): Unit {
        val controller = DesktopWorkflowController()
        assertEquals(true, controller.beginPicker(DesktopPickerSection.GAME_INSTALL))
        assertEquals(true, controller.beginPicker(DesktopPickerSection.QUEST_MODS))
        assertEquals(true, controller.modPickerOpen)
        controller.finishPicker(DesktopPickerSection.GAME_INSTALL)
        controller.finishPicker(DesktopPickerSection.QUEST_MODS)
    }

    @Test
    fun controllerTransitionsKeepOtherSectionsAndPcvrResultIndependent(): Unit {
        val controller = DesktopWorkflowController()
        controller.startPcvr(false)
        val running = controller.pcvrState
        controller.startGameInstall("game")
        controller.startQuestMods(null, "mod.zip", SafeDeviceIdentity("quest-a", authorized = true))
        assertEquals(running, controller.pcvrState)
        controller.pcvrSucceeded(emptyList(), null, "اكتمل")
        val result = controller.pcvrState
        controller.startGameInstall("other-game")
        controller.startQuestMods(null, "other.zip", SafeDeviceIdentity("quest-b"))
        assertEquals(result.checks, controller.pcvrState.checks)
        assertEquals(result.message, controller.pcvrState.message)
    }

    @Test
    fun questAnalysisTerminalTransitionsNeverRemainAnalyzing(): Unit {
        val controller = DesktopWorkflowController()
        controller.startQuestMods(null, null, SafeDeviceIdentity(null))
        controller.questModsFailed("missing prerequisites")
        assertEquals("error", controller.questModsState.operation)

        controller.startQuestMods(null, "archive.zip", SafeDeviceIdentity("serial-a"))
        controller.questModsFailed("analyzer error")
        assertEquals("error", controller.questModsState.operation)

        controller.startQuestMods(
            InstalledQuestApp("com.AnotherAxiom.GorillaTag", versionName = "1"),
            "archive.zip",
            SafeDeviceIdentity("serial-a")
        )
        controller.questModsStale("stale serial/app/archive")
        assertEquals("stale", controller.questModsState.operation)
    }

    @Test
    fun analysisControllerReportsErrors(): Unit = runBlocking {
        val request = QuestModAnalysisRequest(
            serial = "quest-a",
            app = InstalledQuestApp("com.AnotherAxiom.GorillaTag", versionName = "1"),
            archive = File("mod.zip"),
            archiveSha256 = "hash"
        )
        val error = QuestModAnalysisController {
            error("archive rejected")
        }.analyze(request) { true }
        assertIs<QuestModAnalysisResult.Error>(error)
    }
}