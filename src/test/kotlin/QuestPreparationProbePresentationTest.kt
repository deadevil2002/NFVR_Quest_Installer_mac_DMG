import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class QuestPreparationProbePresentationTest {
    @Test
    fun `found discovery with warning remains found and is partial`() {
        val discovery = QuestProbeDiscovery(
            "Gorilla Tag",
            ProbeTargetState.FOUND,
            listOf(QuestProbeCandidate("com.AnotherAxiom.GorillaTag", null, emptyList()))
        )
        val game = QuestProbeGame(
            "Gorilla Tag",
            "com.AnotherAxiom.GorillaTag",
            warnings = listOf("APK count exceeds safe limit")
        )
        val row = questProbeRowPresentation(discovery, game)
        assertEquals("تم العثور على اللعبة", row.discoveryLabel)
        assertEquals(QuestProbeRowState.PARTIAL, row.probeState)
        assertNotEquals("NOT_FOUND", row.discoveryLabel)
    }

    @Test
    fun `cancelled missing evidence is separate from discovery`() {
        val discovery = QuestProbeDiscovery(
            "Beat Saber",
            ProbeTargetState.FOUND,
            listOf(QuestProbeCandidate("com.beatgames.beatsaber", null, emptyList()))
        )
        val row = questProbeRowPresentation(discovery, game = null, cancelled = true)
        assertEquals("تم العثور على اللعبة", row.discoveryLabel)
        assertEquals(QuestProbeRowState.CANCELLED, row.probeState)
    }

    @Test
    fun `running and not started never render complete or count as completed`() {
        val discovery = QuestProbeDiscovery(
            "BONELAB",
            ProbeTargetState.FOUND,
            listOf(QuestProbeCandidate("com.StressLevelZero.BONELAB", null, emptyList()))
        )
        val running = QuestProbeGame(
            "BONELAB", "com.StressLevelZero.BONELAB",
            probeState = QuestProbeState.RUNNING
        )
        val notStarted = running.copy(probeState = QuestProbeState.NOT_STARTED)
        assertEquals(QuestProbeRowState.PROBING, questProbeRowPresentation(discovery, running).probeState)
        assertEquals(QuestProbeRowState.NOT_FOUND, questProbeRowPresentation(discovery, notStarted).probeState)
        assertTrue(questProbeRowPresentation(discovery, running).probeLabel.contains("جارٍ"))
        val ui = QuestPreparationProbeUiState(completed = listOf(running, notStarted))
        assertEquals(0, questProbeCompletedCount(ui))
        assertEquals(0f, questProbeProgress(ui))
    }

    @Test
    fun `retry replaces only target even when cancelled and stale same device`() {
        val device = QuestProbeDevice("same", null, null, null, emptyList(), null, true)
        val old = QuestPreparationProbeReport(
            device = device,
            discoveries = emptyList(),
            games = listOf(
                QuestProbeGame("Gorilla Tag", "com.AnotherAxiom.GorillaTag", probeState = QuestProbeState.COMPLETE),
                QuestProbeGame("Beat Saber", "com.beatgames.beatsaber", probeState = QuestProbeState.COMPLETE)
            )
        )
        val retry = QuestPreparationProbeReport(
            device = device,
            discoveries = emptyList(),
            games = listOf(
                QuestProbeGame("Gorilla Tag", "com.AnotherAxiom.GorillaTag", probeState = QuestProbeState.CANCELLED)
            ),
            cancelled = true,
            stale = true
        )
        val merged = mergeQuestProbeReports(old, retry, "Gorilla Tag", sameDevice = true)
        assertEquals(
            listOf("com.beatgames.beatsaber", "com.AnotherAxiom.GorillaTag"),
            merged.games.map { it.packageId }
        )
        val newDevice = mergeQuestProbeReports(old, retry.copy(device = device.copy(serial = "new")), "Gorilla Tag", sameDevice = false)
        assertEquals(listOf("com.AnotherAxiom.GorillaTag"), newDevice.games.map { it.packageId })
    }
}