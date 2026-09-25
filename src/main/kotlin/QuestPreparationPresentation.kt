import java.util.Locale

/**
 * Customer-facing copy for Quest preparation.
 *
 * The preparation engine deliberately keeps machine-readable stage names,
 * blocker codes, and evidence details.  None of those values are suitable for
 * the default Arabic UI, so this file is the single translation boundary for
 * preparation presentation.
 */

internal fun questPreparationStageTitle(stage: QuestPreparationStage): String = when (stage) {
    QuestPreparationStage.SELECT_GAME -> "تحديد اللعبة"
    QuestPreparationStage.CAPTURE_IDENTITY -> "تسجيل الحزمة والإصدار"
    QuestPreparationStage.INVENTORY_APKS -> "حصر ملفات APK"
    QuestPreparationStage.INSPECT_APK -> "فحص ملفات APK"
    QuestPreparationStage.CHECK_PROFILE -> "مطابقة اللعبة والإصدار"
    QuestPreparationStage.CREATE_BACKUP -> "إنشاء نسخة محلية"
    QuestPreparationStage.VERIFY_BACKUP -> "التحقق من النسخة"
    QuestPreparationStage.READY_FOR_MOD_INSTALL -> "تقرير الجاهزية"
}

internal fun questPreparationStageStateLabel(state: QuestStageState): String = when (state) {
    QuestStageState.COMPLETE -> "مكتمل"
    QuestStageState.BLOCKED -> "متوقف"
    QuestStageState.NOT_STARTED -> "لم يبدأ"
}

/**
 * This is intentionally a short, stable explanation.  Engine detail belongs
 * in the explicit diagnostics section, not in the normal customer workflow.
 */
internal fun questPreparationStageSummary(result: QuestPreparationStageResult): String {
    val verb = when (result.state) {
        QuestStageState.COMPLETE -> when (result.stage) {
            QuestPreparationStage.SELECT_GAME -> "تم تحديد اللعبة المثبتة."
            QuestPreparationStage.CAPTURE_IDENTITY -> "تم تسجيل هوية الحزمة وإصدارها."
            QuestPreparationStage.INVENTORY_APKS -> "اكتمل حصر ملفات APK المثبتة للقراءة فقط."
            QuestPreparationStage.INSPECT_APK -> "اكتمل الفحص المحدود لملفات APK."
            QuestPreparationStage.CHECK_PROFILE -> "تطابقت أدلة اللعبة مع الملف المحدد."
            QuestPreparationStage.CREATE_BACKUP -> "تم إنشاء نسخة APK محلية."
            QuestPreparationStage.VERIFY_BACKUP -> "تم التحقق من سلامة النسخة المحلية."
            QuestPreparationStage.READY_FOR_MOD_INSTALL -> "اكتملت أدلة الجاهزية المطلوبة."
        }
        QuestStageState.BLOCKED -> when (result.stage) {
            QuestPreparationStage.SELECT_GAME -> "لم يتم تحديد لعبة مثبتة."
            QuestPreparationStage.CAPTURE_IDENTITY -> "تعذر إثبات هوية الحزمة والإصدار."
            QuestPreparationStage.INVENTORY_APKS -> "لم يكتمل حصر ملفات APK."
            QuestPreparationStage.INSPECT_APK -> "لم يكتمل فحص ملفات APK."
            QuestPreparationStage.CHECK_PROFILE -> "لا يوجد تطابق موثوق للعبة والإصدار."
            QuestPreparationStage.CREATE_BACKUP -> "لم تُعتمد نسخة APK محلية."
            QuestPreparationStage.VERIFY_BACKUP -> "لم تثبت سلامة النسخة المحلية."
            QuestPreparationStage.READY_FOR_MOD_INSTALL -> "لا يمكن إعلان الجاهزية بهذه الأدلة."
        }
        QuestStageState.NOT_STARTED -> when (result.stage) {
            QuestPreparationStage.SELECT_GAME -> "بانتظار تحديد اللعبة."
            QuestPreparationStage.CAPTURE_IDENTITY -> "بانتظار تسجيل هوية الحزمة."
            QuestPreparationStage.INVENTORY_APKS -> "بانتظار حصر ملفات APK."
            QuestPreparationStage.INSPECT_APK -> "بانتظار فحص ملفات APK."
            QuestPreparationStage.CHECK_PROFILE -> "بانتظار مطابقة الإصدار."
            QuestPreparationStage.CREATE_BACKUP -> "بانتظار إنشاء النسخة المحلية."
            QuestPreparationStage.VERIFY_BACKUP -> "بانتظار التحقق من النسخة."
            QuestPreparationStage.READY_FOR_MOD_INSTALL -> "بانتظار تقرير الجاهزية."
        }
    }
    return verb
}

internal fun questPreparationBlockerLabel(blocker: QuestPreparationBlocker): String =
    when (blocker.code.uppercase(Locale.ROOT)) {
        "APK_INVENTORY_INCOMPLETE" -> "لم تكتمل قراءة ملفات APK المثبتة."
        "UNSUPPORTED_GAME_VERSION" -> "إصدار اللعبة غير مدعوم للتحضير الموثوق."
        "APK_INSPECTION_REQUIRED" -> "يلزم فحص ملفات APK قبل تقرير الجاهزية."
        "APK_INSPECTION_FAILED" -> "تعذر التحقق من بنية ملفات APK."
        "APK_EVIDENCE_UNBOUND" -> "أدلة APK لا تطابق الملفات المقروءة."
        "ABI_NOT_PROFILED" -> "معمارية ملفات اللعبة خارج الملف المدعوم."
        "ENGINE_EVIDENCE_MISSING" -> "لم تكتمل أدلة محرك اللعبة."
        "RESOURCE_HASH_MISMATCH" -> "موارد اللعبة لا تطابق الإصدار المحدد."
        else -> "تعذر إثبات أحد متطلبات الجاهزية."
    }

internal fun questPreparationResultLabel(report: QuestPreparationReport?): String = when {
    report == null -> "لم يبدأ فحص الجاهزية."
    report.readyForModInstall -> "اكتملت أدلة الجاهزية."
    report.blockers.isNotEmpty() -> "الجاهزية متوقفة بسبب متطلبات غير مكتملة."
    !report.backup.integrityVerified -> "لم تثبت سلامة النسخة المحلية."
    else -> "لم تثبت الجاهزية للتثبيت."
}

internal fun questPreparationBackupLabel(backup: QuestBackupStatus?): String = when {
    backup == null -> "لم تبدأ نسخة APK المحلية."
    backup.integrityVerified -> "نسخة APK المحلية سليمة."
    else -> "لا توجد نسخة APK محلية متحققة."
}

internal fun questPreparationSelectedPackageLabel(app: InstalledQuestApp?): String =
    app?.let {
        val version = it.versionName?.takeIf(String::isNotBlank) ?: "إصدار غير معروف"
        "الحزمة المحددة: ${it.packageName} · الإصدار $version"
    } ?: "لم يتم تحديد حزمة لعبة."

/**
 * Raw values are intentionally returned only for an explicitly expanded
 * diagnostics view.  Callers must not render this value as normal status copy.
 */
internal fun questPreparationDiagnostics(report: QuestPreparationReport?): List<String> {
    if (report == null) return emptyList()
    return buildList {
        report.stages.forEach { stage ->
            if (stage.detail.isNotBlank()) {
                add("${stage.stage.name}: ${stage.detail}")
            }
        }
        report.blockers.forEach { blocker ->
            add("${blocker.code}: ${blocker.detail} [scope=${blocker.scope}]")
        }
        report.backup.reason?.takeIf(String::isNotBlank)?.let { add("BACKUP: $it") }
    }.distinct()
}

internal fun questPreparationActionEnabled(
    connected: Boolean,
    installing: Boolean,
    analyzing: Boolean,
    pickerOpen: Boolean,
    busy: Boolean
): Boolean = connected && !installing && !analyzing && !pickerOpen && !busy

/**
 * The preparation card is evidence collection, not a package installer.
 * Only a direct-ready analysis may participate in the existing install path.
 */
internal fun modsInstallActionVisible(analysis: ModPackageAnalysis?): Boolean =
    analysis?.outcome == ModInstallOutcome.DIRECT_INSTALL_READY

/**
 * The advanced Mods diagnostics area is collapsed by default so the normal
 * install flow stays short.  A named constant keeps the default covered by
 * regression tests.
 */
internal const val ADVANCED_MODS_SECTION_DEFAULT_EXPANDED = false

/**
 * Single authoritative state for the Mods install call-to-action.
 *
 * The CTA must never depend on a numeric workflow stage: the rail advances
 * to INSTALL exactly when the plan becomes reviewed, which previously hid
 * the button at the moment the customer needed it.  READY is derived only
 * from the real installation conditions.
 */
internal enum class ModInstallActionState {
    HIDDEN,
    READY,
    INSTALLING,
    VERIFYING,
    SUCCESS,
    FAILED
}

internal fun resolveModInstallActionState(
    analysis: ModPackageAnalysis?,
    operationBound: Boolean,
    destinationConfirmed: Boolean,
    installing: Boolean,
    executionProgress: ModsManager.ModExecutionProgress?,
    installSucceeded: Boolean,
    updateConfirmed: Boolean = false
): ModInstallActionState {
    if (installSucceeded && analysis != null) return ModInstallActionState.SUCCESS
    if (installing && executionProgress?.phase == ModsManager.ModInstallPhase.VERIFYING) {
        return ModInstallActionState.VERIFYING
    }
    if (installing) return ModInstallActionState.INSTALLING
    if (executionProgress?.phase == ModsManager.ModInstallPhase.FAILED) {
        return ModInstallActionState.FAILED
    }
    if (analysis == null) return ModInstallActionState.HIDDEN
    val ready = analysis.outcome == ModInstallOutcome.DIRECT_INSTALL_READY &&
        analysis.installable &&
        analysis.compatibility.compatible &&
        operationBound &&
        !analysis.installPlan.hasBlockingPreconditions &&
        (!genericDestinationNeedsConfirmation(analysis) || destinationConfirmed)
    // An existing install owns the destination: the normal install action
    // stays hidden and the review card owns the decision UX.  Only an
    // explicitly confirmed proven-newer update re-exposes it.
    // Nomad has no staged-update execution yet, so any existing Nomad
    // install keeps the action hidden with an explanatory decision.
    val nomadDecision = analysis.nomadAssessment
        ?.takeIf { it.relation != InstalledModRelation.ABSENT }
    if (nomadDecision != null) return ModInstallActionState.HIDDEN
    val assessment = analysis.installedModAssessment
    if (assessment != null && assessment.relation != InstalledModRelation.ABSENT) {
        return if (assessment.relation == InstalledModRelation.NEWER_THAN_INSTALLED &&
            updateConfirmed && ready
        ) {
            ModInstallActionState.READY
        } else {
            ModInstallActionState.HIDDEN
        }
    }
    return if (ready) ModInstallActionState.READY else ModInstallActionState.HIDDEN
}

/**
 * Customer status line for the install area.  The ready line is only ever
 * produced together with [ModInstallActionState.READY]; a ready message
 * without a reachable action is a UI bug.
 */
internal fun modInstallCtaStatusLine(
    state: ModInstallActionState,
    analysis: ModPackageAnalysis?
): String? {
    return when (state) {
        ModInstallActionState.READY -> if (analysis != null) {
            "الخطة جاهزة — ${analysis.installPlan.totalFiles} ملف · ${modCompactBytes(analysis.installPlan.totalBytes)}"
        } else null
        ModInstallActionState.INSTALLING -> "جارٍ تثبيت المود…"
        ModInstallActionState.VERIFYING -> "جارٍ التحقق من الملفات على النظارة…"
        ModInstallActionState.SUCCESS -> "تم تثبيت المود بنجاح"
        ModInstallActionState.FAILED -> if (analysis == null) {
            "تعذر تثبيت المود."
        } else {
            modInstallUnavailableReason(
                analysis,
                operationBound = false,
                destinationConfirmed = false
            ) ?: "تعذر تثبيت المود."
        }
        ModInstallActionState.HIDDEN -> null
    }
}

/**
 * Whether the sticky bar shows the immediate STARTING feedback (click
 * accepted, operation spinning up) instead of the READY button or real
 * progress.  Never fakes transfer progress.
 */
internal fun modInstallStartingVisible(
    actionState: ModInstallActionState,
    installing: Boolean,
    starting: Boolean
): Boolean = starting && !installing && actionState == ModInstallActionState.READY

internal fun modCompactBytes(value: Long): String = when {
    value >= 1024L * 1024L -> "${value / (1024L * 1024L)} MB"
    value >= 1024L -> "${value / 1024L} KB"
    else -> "$value B"
}

/**
 * Short customer destination such as "Mods/FXDux.JsonPack/".
 * Never exposes a blank or heuristic root.
 */
internal fun modCompactDestination(analysis: ModPackageAnalysis): String? {
    val plan = analysis.installPlan
    val packageId = plan.targetPackageId?.trim().orEmpty()
    // Prefer the first mapped file: it names the actual mod folder (for
    // example "Mods/<Name>/"), while the plan root is only the package dir.
    val anchor = plan.mappings.firstOrNull()?.destinationPath
        ?: proposedDestination(analysis)
        ?: return null
    var tail = anchor.trim().trimEnd('/')
    if (packageId.isNotBlank()) {
        val index = tail.indexOf(packageId)
        if (index >= 0) tail = tail.substring(index + packageId.length).trimStart('/')
    }
    // Drop the Android container segment so customers see the mod-relative
    // tail such as "Mods/<Name>/" instead of "files/Mods/<Name>/".
    val segments = tail.split('/').filter { it.isNotBlank() }.toMutableList()
    if (segments.size > 1 && segments.first().equals("files", ignoreCase = true)) {
        segments.removeAt(0)
    }
    // A trailing file name is not a destination level.
    if (segments.size > 1 && segments.last().contains('.')) {
        segments.removeAt(segments.lastIndex)
    }
    if (segments.isEmpty()) return null
    return segments.take(2).joinToString("/") + "/"
}

/**
 * File mappings collapsed by default: at most the first [limit] entries
 * unless [expanded] is true.  The full list always remains available to
 * the expanded diagnostics view.
 */
internal fun modFileMappingsPreview(
    mappings: List<ModFileMapping>,
    expanded: Boolean,
    limit: Int = 5
): List<ModFileMapping> = if (expanded || mappings.size <= limit) mappings else mappings.take(limit)

/**
 * Supported/profiled games first (registry order), then every other
 * application alphabetically.  Pure ordering for the customer picker.
 */
internal fun orderSupportedAppsFirst(apps: List<InstalledQuestApp>): List<InstalledQuestApp> {
    val order = GameModProfileRegistry.profiles.map { it.packageId }
    return apps.sortedWith(
        compareBy<InstalledQuestApp> {
            val index = order.indexOf(it.packageName)
            if (index < 0) Int.MAX_VALUE else index
        }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName ?: it.packageName }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.packageName }
    )
}

/**
 * Compact readiness badge for the selected-game card.  Customer summary
 * only; evidence details stay in the advanced section.
 */
internal fun modGameReadinessBadge(app: InstalledQuestApp): String = when (app.packageName) {
    "com.StressLevelZero.BONELAB" ->
        "جاهز لمودات المحتوى · الإصدار ${app.versionName ?: "؟"}"
    "com.beatgames.beatsaber" ->
        "Scotland2 · الإصدار ${app.versionName ?: "؟"}"
    "com.AnotherAxiom.GorillaTag" ->
        "Virtual Stump مكتشف · الإصدار ${app.versionName ?: "؟"}"
    "com.Warpfrog.BladeAndSorcery" ->
        "Nomad · الإصدار ${app.versionName ?: "؟"}"
    "com.vankrupt.pavlov" ->
        "محتوى mod.io تديره اللعبة · الإصدار ${app.versionName ?: "؟"}"
    else -> "الإصدار ${app.versionName ?: "؟"}"
}

/**
 * Archive/selection staleness for auto-analysis.  Compares only the stable
 * selection identity (package + version + archive hash), never volatile
 * APK evidence that a refresh may attach mid-flow.
 */
internal fun modSelectionMatchesAnalysis(
    selectedApp: InstalledQuestApp?,
    selectedZipSha256: String?,
    analysis: ModPackageAnalysis?
): Boolean {
    if (selectedApp == null || analysis == null) return false
    val reviewed = analysis.installPlan.reviewedApp ?: return false
    return reviewed.packageName == selectedApp.packageName &&
        reviewed.versionName == selectedApp.versionName &&
        reviewed.versionCode == selectedApp.versionCode &&
        (selectedZipSha256.isNullOrBlank() ||
            analysis.installPlan.archiveIdentity?.sha256 == selectedZipSha256)
}

internal enum class ModsOverallStage {
    GAME,
    PACKAGE,
    ANALYSIS,
    READINESS,
    PREPARATION,
    REVIEW,
    INSTALL,
    VERIFY
}

internal fun modsOverallStageTitle(stage: ModsOverallStage): String = when (stage) {
    ModsOverallStage.GAME -> "اللعبة"
    ModsOverallStage.PACKAGE -> "الحزمة"
    ModsOverallStage.ANALYSIS -> "التحليل"
    ModsOverallStage.READINESS -> "الجاهزية"
    ModsOverallStage.PREPARATION -> "التحضير"
    ModsOverallStage.REVIEW -> "المراجعة"
    ModsOverallStage.INSTALL -> "التثبيت"
    ModsOverallStage.VERIFY -> "التحقق"
}

internal fun modsOverallStageNumber(stage: ModsOverallStage): String =
    (stage.ordinal + 1).toString().padStart(2, '0')

internal fun modsPreparationRequired(analysis: ModPackageAnalysis?): Boolean =
    analysis?.let { routeModWorkflow(it).preparationRequired } == true

internal fun modsPreparationPanelVisible(analysis: ModPackageAnalysis?): Boolean =
    modsPreparationRequired(analysis)

internal fun modsPreparationIsLoaderPanel(analysis: ModPackageAnalysis?): Boolean =
    analysis?.let { routeModWorkflow(it).route == ModWorkflowRoute.LOADER_CODE_MOD } == true

internal data class ModsPreparationPresentation(
    val loaderOnly: Boolean,
    val apkEvidenceVisible: Boolean,
    val backupVisible: Boolean,
    val remediationActionVisible: Boolean,
    val title: String,
    val subtitle: String
)

internal fun modsPreparationPresentation(
    analysis: ModPackageAnalysis?
): ModsPreparationPresentation? {
    val route = analysis?.let { routeModWorkflow(it).route } ?: return null
    return when (route) {
        ModWorkflowRoute.LOADER_CODE_MOD -> ModsPreparationPresentation(
            loaderOnly = true,
            apkEvidenceVisible = false,
            backupVisible = false,
            remediationActionVisible = false,
            title = "تجهيز محمّل المودات",
            subtitle = "تحقق من هوية المحمّل وأدلته قبل نقل مود الكود"
        )
        ModWorkflowRoute.APK_PATCH_REQUIRED -> ModsPreparationPresentation(
            loaderOnly = false,
            apkEvidenceVisible = true,
            backupVisible = true,
            remediationActionVisible = true,
            title = "تجهيز APK مطلوب",
            subtitle = "جمع أدلة APK المطلوبة؛ لا يغيّر اللعبة"
        )
        else -> null
    }
}

internal fun modsReadinessComplete(analysis: ModPackageAnalysis?): Boolean =
    analysis?.let {
        it.outcome == ModInstallOutcome.DIRECT_INSTALL_READY &&
            it.installable &&
            it.compatibility.compatible &&
            !it.installPlan.hasBlockingPreconditions
    } == true

internal fun modsAnalysisCompletionLabel(analysis: ModPackageAnalysis): String =
    if (analysis.isBuiltInGameContent || analysis.isExternalWorkflow) {
        "محتوى تديره اللعبة"
    } else {
        "اكتمل فحص الحزمة"
    }

internal data class ModsOverallWorkflowState(
    val currentStage: ModsOverallStage,
    val complete: Map<ModsOverallStage, Boolean>,
    val skipped: Set<ModsOverallStage> = emptySet()
)

internal fun modsOverallWorkflowState(
    selectedApp: InstalledQuestApp?,
    selectedZipFilename: String?,
    analysis: ModPackageAnalysis?,
    semantics: ModsWorkflowSemantics,
    preparation: QuestPreparationUiState?,
    installing: Boolean
): ModsOverallWorkflowState {
    val preparationRequired = modsPreparationRequired(analysis)
    val preparationComplete = when {
        analysis == null -> false
        !preparationRequired -> true
        else -> preparation?.report?.readyForModInstall == true
    }
    val readinessComplete = modsReadinessComplete(analysis)
    val current = when {
        selectedApp == null -> ModsOverallStage.GAME
        selectedZipFilename.isNullOrBlank() -> ModsOverallStage.PACKAGE
        analysis == null -> ModsOverallStage.ANALYSIS
        !readinessComplete && preparationRequired &&
            preparation?.report != null &&
            !preparation.report.readyForModInstall -> ModsOverallStage.PREPARATION
        !readinessComplete -> ModsOverallStage.READINESS
        !semantics.planReviewed -> ModsOverallStage.REVIEW
        !semantics.installCompleted || installing -> ModsOverallStage.INSTALL
        else -> ModsOverallStage.VERIFY
    }
    return ModsOverallWorkflowState(
        currentStage = current,
        complete = mapOf(
            ModsOverallStage.GAME to (selectedApp != null),
            ModsOverallStage.PACKAGE to !selectedZipFilename.isNullOrBlank(),
            ModsOverallStage.ANALYSIS to (analysis != null),
            ModsOverallStage.READINESS to readinessComplete,
            ModsOverallStage.PREPARATION to preparationComplete,
            ModsOverallStage.REVIEW to semantics.planReviewed,
            ModsOverallStage.INSTALL to semantics.installCompleted,
            ModsOverallStage.VERIFY to semantics.installCompleted
        ),
        skipped = if (analysis != null && !preparationRequired) {
            setOf(ModsOverallStage.PREPARATION)
        } else {
            emptySet()
        }
    )
}