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

internal fun questProbeStateLabel(state: ProbeTargetState): String = when (state) {
    ProbeTargetState.FOUND -> "FOUND"
    ProbeTargetState.NOT_FOUND -> "NOT_FOUND"
    ProbeTargetState.AMBIGUOUS -> "AMBIGUOUS"
}

internal fun questProbeTargetNames(): List<String> =
    listOf("Gorilla Tag", "Beat Saber", "BONELAB", "Blade & Sorcery: Nomad")

internal fun questProbeProgress(state: QuestPreparationProbeUiState): Float =
    (state.completed.size.toFloat() / state.totalGames.coerceAtLeast(1)).coerceIn(0f, 1f)

internal fun questProbeStatus(state: QuestPreparationProbeUiState): String = when {
    state.busy && state.currentGame != null -> "جارٍ فحص ${state.currentGame}…"
    state.cancelled -> "تم إلغاء الفحص — تم الاحتفاظ بالنتائج المكتملة."
    state.stale -> "اكتمل الفحص لكن تغيّر الجهاز؛ النتائج قديمة."
    state.error != null -> "تعذر إكمال فحص بعض الألعاب."
    state.report != null -> "اكتمل فحص جاهزية الألعاب."
    else -> "لم يبدأ فحص جاهزية الألعاب."
}