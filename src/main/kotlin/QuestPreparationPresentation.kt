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
    analysis?.outcome == ModInstallOutcome.APK_PATCH_REQUIRED

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