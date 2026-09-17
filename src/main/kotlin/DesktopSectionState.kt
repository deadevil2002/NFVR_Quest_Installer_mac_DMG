import kotlinx.coroutines.Job

/**
 * The three desktop workflows deliberately have different state containers.
 * DeviceIdentity is the only operational fact shared by sections.
 */
data class SafeDeviceIdentity(
    val serial: String?,
    val model: String? = null,
    val authorized: Boolean = false
)

data class GameInstallState(
    val progress: InstallProgressState = InstallProgressState(false, null, 0, null),
    val operation: GameInstallPhase = GameInstallPhase.IDLE,
    val selectedFolder: String? = null,
    val result: String? = null,
    val job: Job? = null,
    val cancellationRequested: Boolean = false
)

data class QuestModsState(
    val progress: ModsManager.ModExecutionProgress? = null,
    val operation: String? = null,
    val selectedGame: InstalledQuestApp? = null,
    val selectedZip: String? = null,
    val result: ModPackageAnalysis? = null,
    val job: Job? = null,
    val cancellationRequested: Boolean = false,
    val device: SafeDeviceIdentity = SafeDeviceIdentity(null)
)

data class PcvrReadinessState(
    val checks: List<PcvrCheckResult> = emptyList(),
    val systemInfo: PcvrSystemInfo? = null,
    val running: Boolean = false,
    val message: String = "اضغط بدء الفحص للتحقق من جاهزية الكمبيوتر لـ PCVR.",
    val progress: Float? = null,
    val operation: String? = null,
    val selection: String? = null,
    val result: Any? = null,
    val job: Job? = null,
    val cancellationRequested: Boolean = false
)

enum class DesktopPickerSection { GAME_INSTALL, QUEST_MODS }

/**
 * Native dialogs are process-global, but their busy state is section-owned:
 * an attempted picker can report busy without disabling another section.
 */
class DesktopWorkflowController {
    var gamePickerOpen: Boolean = false
        private set
    var modPickerOpen: Boolean = false
        private set

    fun beginPicker(section: DesktopPickerSection): Boolean {
        when (section) {
            DesktopPickerSection.GAME_INSTALL -> if (gamePickerOpen) return false
            DesktopPickerSection.QUEST_MODS -> if (modPickerOpen) return false
        }
        when (section) {
            DesktopPickerSection.GAME_INSTALL -> gamePickerOpen = true
            DesktopPickerSection.QUEST_MODS -> modPickerOpen = true
        }
        return true
    }

    fun finishPicker(section: DesktopPickerSection) {
        when (section) {
            DesktopPickerSection.GAME_INSTALL -> gamePickerOpen = false
            DesktopPickerSection.QUEST_MODS -> modPickerOpen = false
        }
    }

    var gameInstallState: GameInstallState = GameInstallState()
        private set
    var questModsState: QuestModsState = QuestModsState()
        private set
    var pcvrState: PcvrReadinessState = PcvrReadinessState()
        private set

    fun startGameInstall(folder: String?) {
        gameInstallState = gameInstallState.copy(
            operation = GameInstallPhase.PREFLIGHT,
            selectedFolder = folder,
            cancellationRequested = false
        )
    }

    fun gameInstallSucceeded(result: String? = null) {
        gameInstallState = gameInstallState.copy(
            operation = GameInstallPhase.COMPLETED,
            result = result,
            cancellationRequested = false
        )
    }

    fun gameInstallFailed(message: String) {
        gameInstallState = gameInstallState.copy(operation = GameInstallPhase.FAILED, result = message)
    }

    fun startQuestMods(game: InstalledQuestApp?, zip: String?, device: SafeDeviceIdentity) {
        questModsState = questModsState.copy(
            operation = "analyzing",
            selectedGame = game,
            selectedZip = zip,
            device = device,
            cancellationRequested = false
        )
    }

    fun questModsSucceeded(result: ModPackageAnalysis) {
        questModsState = questModsState.copy(operation = "complete", result = result)
    }

    fun questModsFailed(message: String) {
        questModsState = questModsState.copy(operation = "error", result = null)
    }

    fun questModsStale(message: String) {
        questModsState = questModsState.copy(operation = "stale", result = null)
    }

    fun startPcvr(advanced: Boolean) {
        pcvrState = pcvrState.copy(
            running = true,
            operation = if (advanced) "advanced" else "quick",
            message = "جاري فحص الكمبيوتر..."
        )
    }

    fun pcvrSucceeded(checks: List<PcvrCheckResult>, info: PcvrSystemInfo?, message: String) {
        pcvrState = pcvrState.copy(
            checks = checks, systemInfo = info, running = false, progress = 1f,
            result = checks, message = message, job = null
        )
    }

    fun pcvrFailed(message: String) {
        pcvrState = pcvrState.copy(running = false, message = message, job = null)
    }

    fun pcvrJob(job: Job?) {
        pcvrState = pcvrState.copy(job = job)
    }
}

/**
 * A small, UI-independent analysis entry point.  Callers provide the current
 * snapshot after selecting a Quest app and archive; a late result is rejected
 * rather than being allowed to overwrite a newer selection.
 */
data class QuestModAnalysisRequest(
    val serial: String,
    val app: InstalledQuestApp,
    val archive: java.io.File,
    val archiveSha256: String,
    val onProgress: (String) -> Unit = {}
)

sealed class QuestModAnalysisResult {
    data class Success(val analysis: ModPackageAnalysis) : QuestModAnalysisResult()
    data class Error(val message: String) : QuestModAnalysisResult()
    data class Stale(val message: String = "تم تجاهل نتيجة تحليل قديمة.") : QuestModAnalysisResult()
}

class QuestModAnalysisController(
    private val analyzer: suspend (QuestModAnalysisRequest) -> ModPackageAnalysis
) {
    suspend fun analyze(
        request: QuestModAnalysisRequest,
        isCurrent: () -> Boolean
    ): QuestModAnalysisResult {
        return try {
            val result = analyzer(request)
            if (!isCurrent()) QuestModAnalysisResult.Stale()
            else QuestModAnalysisResult.Success(result)
        } catch (error: Throwable) {
            QuestModAnalysisResult.Error(error.message ?: "تعذر تحليل الحزمة.")
        }
    }
}