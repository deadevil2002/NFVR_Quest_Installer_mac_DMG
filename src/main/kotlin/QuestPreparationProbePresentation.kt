/**
 * Presentation-only state and customer copy for the read-only multi-game probe.
 * Technical evidence remains available through the explicit diagnostics view.
 */
data class QuestPreparationProbeUiState(
    val report: QuestPreparationProbeReport? = null,
    val completed: List<QuestProbeGame> = emptyList(),
    val busy: Boolean = false,
    val cancelled: Boolean = false,
    val stale: Boolean = false,
    val currentGame: String? = null,
    val totalGames: Int = 4,
    val error: String? = null,
    val exportedJson: String? = null,
    val exportedText: String? = null
)

/**
 * Discovery is authoritative and deliberately independent from the quality of
 * the evidence collected afterwards.  In particular, a failed APK pull must
 * never turn a discovered game into NOT_FOUND.
 */
enum class QuestProbeRowState {
    FOUND, NOT_FOUND, AMBIGUOUS, PROBING, FAILED, CANCELLED, PARTIAL
}

data class QuestProbeRowPresentation(
    val game: String,
    val packageId: String? = null,
    val discoveryState: ProbeTargetState,
    val probeState: QuestProbeRowState,
    val discoveryLabel: String,
    val probeLabel: String
)

/**
 * Merge an individual retry into the last snapshot. The latest target row
 * wins, including a cancelled/failed row; unrelated rows survive only when
 * the device serial is unchanged.
 */
internal fun mergeQuestProbeReports(
    previous: QuestPreparationProbeReport?,
    latest: QuestPreparationProbeReport,
    target: String?,
    sameDevice: Boolean
): QuestPreparationProbeReport {
    if (previous == null || target == null || !sameDevice) return latest
    val targetRows = latest.games.filter {
        it.displayName.equals(target, true) || it.packageId.equals(target, true)
    }
    val targetPackages = targetRows.map { it.packageId }.toSet()
    val merged = previous.games.filterNot {
        it.packageId in targetPackages || it.displayName.equals(target, true)
    } + latest.games
    return latest.copy(games = merged.distinctBy { it.packageId })
}

internal fun questProbeDiscoveryLabel(state: ProbeTargetState): String = when (state) {
    ProbeTargetState.FOUND -> "تم العثور على اللعبة"
    ProbeTargetState.NOT_FOUND -> "لم يتم العثور على اللعبة"
    ProbeTargetState.AMBIGUOUS -> "تم العثور على أكثر من نسخة"
}

internal fun questProbeStateLabel(state: ProbeTargetState): String =
    questProbeDiscoveryLabel(state)

internal fun questProbeProbeState(
    discovery: QuestProbeDiscovery,
    game: QuestProbeGame?,
    busy: Boolean,
    cancelled: Boolean
): QuestProbeRowState = when {
    cancelled && game == null -> QuestProbeRowState.CANCELLED
    busy && (game == null || game.probeState in setOf(QuestProbeState.NOT_STARTED, QuestProbeState.RUNNING)) ->
        QuestProbeRowState.PROBING
    game == null && discovery.state == ProbeTargetState.FOUND -> QuestProbeRowState.FAILED
    game == null -> when (discovery.state) {
        ProbeTargetState.FOUND -> QuestProbeRowState.FAILED
        ProbeTargetState.NOT_FOUND -> QuestProbeRowState.NOT_FOUND
        ProbeTargetState.AMBIGUOUS -> QuestProbeRowState.AMBIGUOUS
    }
    game.probeState == QuestProbeState.CANCELLED || game.probeState == QuestProbeState.STALE ->
        QuestProbeRowState.CANCELLED
    game.probeState == QuestProbeState.FAILED -> QuestProbeRowState.FAILED
    game.probeState == QuestProbeState.PARTIAL || game.warnings.isNotEmpty() ->
        QuestProbeRowState.PARTIAL
    game.probeState == QuestProbeState.RUNNING -> QuestProbeRowState.PROBING
    game.probeState == QuestProbeState.NOT_STARTED -> QuestProbeRowState.NOT_FOUND
    else -> QuestProbeRowState.FOUND
}

internal fun questProbeProbeLabel(state: QuestProbeRowState): String = when (state) {
    QuestProbeRowState.FOUND -> "الفحص: مكتمل"
    QuestProbeRowState.PARTIAL -> "فحص الأدلة: جزئي"
    QuestProbeRowState.FAILED -> "الفحص: فشل"
    QuestProbeRowState.CANCELLED -> "الفحص: أُلغي"
    QuestProbeRowState.PROBING -> "جارٍ الفحص…"
    QuestProbeRowState.NOT_FOUND -> "لم يبدأ الفحص"
    QuestProbeRowState.AMBIGUOUS -> "يلزم اختيار النسخة الصحيحة"
}

internal fun questProbeRowPresentation(
    discovery: QuestProbeDiscovery,
    game: QuestProbeGame? = null,
    busy: Boolean = false,
    cancelled: Boolean = false
): QuestProbeRowPresentation {
    val probeState = questProbeProbeState(discovery, game, busy, cancelled)
    return QuestProbeRowPresentation(
        game = discovery.game,
        packageId = game?.packageId ?: discovery.candidates.singleOrNull()?.packageId,
        discoveryState = discovery.state,
        probeState = probeState,
        discoveryLabel = questProbeDiscoveryLabel(discovery.state),
        probeLabel = questProbeProbeLabel(probeState)
    )
}

internal fun questProbeTargetNames(): List<String> =
    listOf("Gorilla Tag", "Beat Saber", "BONELAB", "Blade & Sorcery: Nomad")

internal fun questProbeProgress(state: QuestPreparationProbeUiState): Float =
    (questProbeCompletedCount(state).toFloat() / state.totalGames.coerceAtLeast(1)).coerceIn(0f, 1f)

internal fun questProbeCompletedCount(state: QuestPreparationProbeUiState): Int =
    state.completed.count {
        it.probeState in setOf(
            QuestProbeState.COMPLETE,
            QuestProbeState.PARTIAL,
            QuestProbeState.FAILED
        )
    }

internal fun questProbeStatus(state: QuestPreparationProbeUiState): String = when {
    state.busy && state.currentGame != null -> "جارٍ فحص ${state.currentGame}…"
    state.cancelled -> "تم إلغاء الفحص — تم الاحتفاظ بالنتائج المكتملة."
    state.stale -> "اكتمل الفحص لكن تغيّر الجهاز؛ النتائج قديمة."
    state.error != null -> "تعذر إكمال فحص بعض الألعاب."
    state.report != null -> "اكتمل فحص جاهزية الألعاب."
    else -> "لم يبدأ فحص جاهزية الألعاب."
}