import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopWorkflowModelsTest {
    @Test
    fun utcInstantUsesInjectedNonSaudiZoneAndRollsDateCorrectly() {
        val formatter = NfvrTimeFormatter(
            Clock.fixed(Instant.parse("2024-01-01T23:30:00Z"), ZoneOffset.UTC),
            ZoneId.of("America/Los_Angeles")
        )
        assertEquals("15:30:00", formatter.formatTime(formatter.now()))
        val local = formatter.formatDateTime(formatter.now())
        assertTrue(local.contains("2024"))
        assertTrue(local.contains("1"))

        val rollover = formatLocalDateTime(
            Instant.parse("2024-01-01T23:30:00Z"),
            ZoneId.of("Asia/Tokyo")
        )
        val utcDate = formatLocalDateTime(
            Instant.parse("2024-01-01T23:30:00Z"),
            ZoneOffset.UTC
        )
        assertNotEquals(utcDate, rollover)
    }

    @Test
    fun cacheNeverReturnsDifferentSerialSnapshot() {
        val cache = DeviceSerialCache<List<String>>()
        cache.put("quest-a", listOf("a"))
        cache.put("quest-b", listOf("b"))
        assertEquals(listOf("a"), cache.get("quest-a")?.value)
        assertEquals(listOf("b"), cache.get("quest-b")?.value)
        assertNull(cache.get("quest-c"))
    }

    @Test
    fun serialGenerationRejectsStaleCallbacksAfterDisconnectAndChange() {
        val state = DeviceSerialGeneration("quest-a")
        val old = assertNotNull(state.capture())
        assertTrue(state.switchTo(null).changed)
        assertFalse(state.isCurrent(old))
        state.switchTo("quest-b")
        val current = assertNotNull(state.capture())
        assertTrue(state.isCurrent(current))
        assertFalse(state.isCurrent(old))
    }

    @Test
    fun genericDestinationRequiresAnExplicitConfirmationSignal() {
        val app = InstalledQuestApp("com.example.game", versionName = "1")
        val plan = ModInstallPlan(
            installable = true,
            outcome = ModInstallOutcome.DIRECT_INSTALL_READY,
            strategy = ModInstallStrategy.NONE,
            destinationRoot = "/sdcard/Mods",
            preconditions = listOf(
                ModInstallPrecondition("GENERIC_EXISTING_MOD_FOLDER", "confirm destination")
            ),
            reviewedApp = app
        )
        val analysis = ModPackageAnalysis(
            ModPackageType.GENERIC_DATA,
            recognized = true,
            message = "candidate",
            compatibility = ModCompatibility(true),
            installPlan = plan
        )
        assertTrue(genericDestinationNeedsConfirmation(analysis))
    }

    @Test
    fun destinationCheckboxWorkflowUsesImmutablePlanConfirmation() {
        val archive = ModArchiveIdentity("archive.zip", 1L, 2L, "hash")
        val plan = ModInstallPlan(
            installable = false,
            outcome = ModInstallOutcome.UNSUPPORTED,
            destinationRoot = "/sdcard/Mods",
            mappings = listOf(ModFileMapping("payload.dat", "/sdcard/Mods/payload.dat")),
            preconditions = listOf(
                ModInstallPrecondition(
                    "EXPLICIT_CONFIRMATION_REQUIRED",
                    "confirm",
                    satisfied = false
                )
            ),
            strategy = ModInstallStrategy.GENERIC_EXISTING_DIRECTORY_COPY,
            archiveIdentity = archive,
            confirmation = ModInstallConfirmation("token", "/sdcard/Mods", "inferred")
        )
        val app = InstalledQuestApp("com.example.game", versionName = "1")
        val analysis = ModPackageAnalysis(
            ModPackageType.GENERIC_DATA,
            recognized = true,
            message = "candidate",
            compatibility = ModCompatibility(true),
            installPlan = plan.copy(reviewedApp = app)
        )
        assertTrue(genericDestinationNeedsConfirmation(analysis))
        val confirmed = analysis.installPlan.confirmDestination("token")
        assertTrue(confirmed !== analysis.installPlan)
        assertTrue(confirmed.installable)
        assertFalse(genericDestinationNeedsConfirmation(analysis.copy(installPlan = confirmed)))
        assertFalse(analysis.installPlan.preconditions.single().satisfied)
    }

    @Test
    fun dropRouterAcceptsOnlyTheCorrectInputForEachArea() {
        val root = Files.createTempDirectory("nfvr-drop-test-").toFile()
        try {
            val zip = File(root, "mod.zip").also { it.writeBytes(byteArrayOf(1)) }
            val folder = File(root, "game").also { it.mkdirs() }
            val rejected = mutableListOf<String>()
            var accepted = 0
            val router = DesktopDropRouter(
                onDrop = { _, _ -> accepted += 1 },
                onRejected = { rejected += it }
            )
            assertTrue(router.inspectFiles(DesktopDropTarget.MOD_PACKAGE, listOf(zip)))
            assertFalse(router.inspectFiles(DesktopDropTarget.MOD_PACKAGE, listOf(folder)))
            assertTrue(router.inspectFiles(DesktopDropTarget.GAME_FOLDER, listOf(folder)))
            assertFalse(router.inspectFiles(DesktopDropTarget.GAME_FOLDER, listOf(zip)))
            assertTrue(router.dispatch(DesktopDropTarget.MOD_PACKAGE, listOf(zip)))
            assertFalse(router.dispatch(DesktopDropTarget.MOD_PACKAGE, listOf(folder)))
            assertFalse(router.dispatch(DesktopDropTarget.MOD_PACKAGE, listOf(zip, zip)))
            assertFalse(router.dispatch(DesktopDropTarget.GAME_FOLDER, listOf(zip)))
            assertFalse(router.dispatch(null, listOf(zip)))
            assertEquals(1, accepted)
            assertEquals(4, rejected.size)
            assertTrue(rejected.all { it.contains("تم رفض السحب") })
        } finally {
            root.deleteRecursively()
        }
    }
}
