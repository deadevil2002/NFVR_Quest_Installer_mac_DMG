import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.time.Instant
import java.util.Locale

/**
 * Focus must be cleared before a conditional Mods subtree is changed.  Keeping
 * the ordering in a small pure helper makes it difficult for a future click
 * handler to accidentally update state first (which can leave Desktop
 * Compose with an ActiveParent that has no focused child).
 */
internal fun clearFocusBeforeModsTransition(
    clearFocus: () -> Unit,
    transition: () -> Unit
) {
    clearFocus()
    transition()
}

internal enum class ModsUiStatusTone {
    SUCCESS,
    INFO,
    WARNING,
    ERROR
}

internal data class ModsUiOutcome(
    val tone: ModsUiStatusTone,
    val title: String,
    val action: String,
    val changedFiles: String = "لم يتم تغيير أي ملف على النظارة."
)

/**
 * Keep the customer copy actionable without exposing analyzer enum names.
 * The archive may be recognized while still being blocked for a precise,
 * security-relevant reason (PC payload, loader, version, collision, etc.).
 */
internal fun modCustomerFailureReason(analysis: ModPackageAnalysis): String {
    if (analysis.outcome == ModInstallOutcome.BUILT_IN_GAME_CONTENT) {
        return "تم التعرف على الحزمة، لكن عقد الاستيراد الموثق والآمن للعبة غير متاح في NFVR؛ لم يتم نقل أي ملف."
    }
    val blocked = analysis.installPlan.preconditions
        .filterNot { it.satisfied }
        .firstOrNull {
            it.code == "NOMAD_GAME_VERSION_UNVERIFIED" ||
                it.code == "GAME_VERSION_UNSUPPORTED" ||
                it.code == "GAME_VERSION_CODE_UNSUPPORTED"
        }
        ?: analysis.installPlan.preconditions.firstOrNull { !it.satisfied }
    val code = blocked?.code?.uppercase(Locale.ROOT).orEmpty()
    return when {
        code == "PC_ONLY_PAYLOAD" ->
            "هذا الملف مود PCVR وليس Quest؛ لم يتم نقل أي ملف."
        code == "APK_PATCH_REQUIRED" ->
            "تحتاج هذه الحزمة إلى تجهيز APK آمن قبل التثبيت؛ لم يتم تنفيذ أي تعديل."
        code == "NOMAD_GAME_VERSION_UNVERIFIED" ->
            Regex("""GameVersion '([^']+)'.*?selected game version '([^']+)'""")
                .find(blocked?.message.orEmpty())
                ?.let {
                    "إصدار Framework المعلن ${it.groupValues[1]} لا يطابق إصدار اللعبة المثبت ${it.groupValues[2]}؛ لم يتم نقل أي ملف."
                }
                ?: "تعذر إثبات توافق إصدار Framework مع إصدار اللعبة المثبت؛ لم يتم نقل أي ملف."
        code == "GAME_VERSION_UNSUPPORTED" || code == "GAME_VERSION_CODE_UNSUPPORTED" ->
            blocked?.let(::modPreconditionDetail)
                ?: "إصدار اللعبة المثبت لا يطابق الإصدار المطلوب لهذه الحزمة."
        code.contains("LOADER") || code.contains("NATIVE_PAYLOAD") ->
            "هذا المود يحتاج محمّلًا متوافقًا وأدلة قراءة مكتملة قبل النقل."
        code.contains("VERSION") ->
            "هذا المود يحتاج إصدار لعبة مختلفًا عن الإصدار المثبت."
        code.contains("TARGET_PACKAGE") ->
            "الحزمة موجهة إلى لعبة أخرى؛ اختر اللعبة المطابقة."
        code.contains("DIRECTORY") || code.contains("DESTINATION") ->
            "لم يتم إثبات مجلد مود آمن ومعتمد لهذه اللعبة."
        code.contains("DEPEND") ->
            "الحزمة تعلن اعتماديات لم يتم التحقق منها؛ لم يتم تنزيل أي اعتماد تلقائيًا."
        code.contains("UNSAFE") || code.contains("CORRUPT") ||
            code.contains("MALFORMED") || code.contains("TRAVERSAL") ->
            "الأرشيف غير آمن أو تالف؛ لم يتم نقل أي ملف."
        code == "UNKNOWN_FORMAT" || code == "UNSUPPORTED_FORMAT" ||
            analysis.packageType == ModPackageType.UNKNOWN || !analysis.recognized ->
            "لم يتعرف NFVR على بنية الحزمة الآمنة؛ راجع نوع الملفات والبنية الداخلية."
        blocked != null ->
            "تعذر تجهيز الحزمة: ${customerPreconditionMessage(blocked.code)}"
        analysis.installable ->
            "الحزمة جاهزة للتثبيت الآمن."
        else ->
            "تعذر تجهيز خطة نقل آمنة لهذه الحزمة؛ لم يتم تغيير أي ملف."
    }
}

private fun modPreconditionDetail(precondition: ModInstallPrecondition): String? {
    val message = precondition.message
    return when (precondition.code.uppercase(Locale.ROOT)) {
        "NOMAD_GAME_VERSION_UNVERIFIED" ->
            Regex("""GameVersion '([^']+)'.*?selected game version '([^']+)'""")
                .find(message)
                ?.let {
                    "Framework المعلن ${it.groupValues[1]} لا يطابق إصدار اللعبة المثبت ${it.groupValues[2]}."
                }
                ?: "تعذر إثبات توافق Framework مع إصدار اللعبة."
        "MOD_DESTINATION_REQUIRED" ->
            "يجب أن يكون مجلد المحتوى المعتمد موجودًا مسبقًا؛ لن ينشئ NFVR الجذر."
        "UNRELATED_ARCHIVE_CONTENT" ->
            "يحتوي الأرشيف ملفات خارج جذر المود المثبت بالأدلة."
        "NESTED_ARCHIVE" ->
            "يوجد أرشيف متداخل ولم يتم فكّه أو تثبيته تلقائيًا."
        "GAME_VERSION_UNSUPPORTED", "GAME_VERSION_CODE_UNSUPPORTED" ->
            "إصدار اللعبة المثبت خارج الإصدارات المتوافقة الموثقة."
        else -> null
    }
}

/**
 * Maps the engine's explicit outcome to presentation only.  The precondition
 * fallback keeps an unsafe archive red even if an older analysis object did
 * not populate the outcome field.
 */
internal fun modsUiOutcome(analysis: ModPackageAnalysis): ModsUiOutcome {
    val blocked = analysis.installPlan.preconditions.filterNot { it.satisfied }
    val codes = blocked.map { it.code.uppercase(Locale.ROOT) }
    val hasUnsafeOrCorruptArchive = codes.any { code ->
        code == "INVALID_ARCHIVE" ||
            code == "UNSAFE_ARCHIVE" ||
            code == "CORRUPT_ARCHIVE" ||
            code == "MALFORMED_ARCHIVE" ||
            code.contains("UNSAFE") ||
            code.contains("DANGEROUS_PAYLOAD") ||
            code.contains("TRAVERSAL")
    }
    val requiresLoader = analysis.outcome == ModInstallOutcome.REQUIRES_MOD_LOADER ||
        codes.any { it.contains("LOADER") } ||
        blocked.any {
            Regex("(?i)\\b(loader|modloader|محمّل|محمل)\\b").containsMatchIn(it.message)
        }
    val builtInContent = analysis.outcome == ModInstallOutcome.BUILT_IN_GAME_CONTENT ||
        analysis.isBuiltInGameContent ||
        analysis.externalWorkflow != null

    return when {
        analysis.outcome == ModInstallOutcome.UNSAFE_ARCHIVE || hasUnsafeOrCorruptArchive -> ModsUiOutcome(
            ModsUiStatusTone.ERROR,
            "أرشيف غير آمن أو تالف",
            "لم يتم نقل أي ملف"
        )
        builtInContent -> ModsUiOutcome(
            ModsUiStatusTone.INFO,
            "تم التعرف على نوع المود",
            "تم التعرف على الحزمة، لكن عقد الاستيراد الموثق والآمن غير متاح في NFVR؛ لم يتم نقل أي ملف."
        )
        analysis.outcome == ModInstallOutcome.APK_PATCH_REQUIRED -> ModsUiOutcome(
            ModsUiStatusTone.WARNING,
            "تحتاج اللعبة إلى تجهيز نظام المودات",
            "لا يوجد إجراء تصحيح متاح؛ جهّز اللعبة بأداة آمنة معتمدة ثم أعد التحليل"
        )
        requiresLoader -> ModsUiOutcome(
            ModsUiStatusTone.WARNING,
            "يتطلب محمّل مودات",
            "جهّز محمّل المودات المطلوب ثم أعد التحليل"
        )
        analysis.outcome == ModInstallOutcome.DIRECT_INSTALL_READY -> ModsUiOutcome(
            ModsUiStatusTone.SUCCESS,
            "جاهز للتثبيت",
            "استخراج → نقل → تحقق",
            "لم يتم تغيير أي ملف بعد؛ يبدأ النقل فقط بعد اعتماد الخطة"
        )
        analysis.outcome == ModInstallOutcome.UNSUPPORTED ||
            analysis.packageType == ModPackageType.UNKNOWN ||
            !analysis.recognized -> ModsUiOutcome(
            ModsUiStatusTone.WARNING,
            "تعذر تجهيز التثبيت",
            modCustomerFailureReason(analysis),
            "لم يتم نقل أي ملف"
        )
        else -> ModsUiOutcome(
            ModsUiStatusTone.WARNING,
            "تحتاج الخطة إلى متطلبات",
            modCustomerFailureReason(analysis),
            "لم يتم نقل أي ملف"
        )
    }
}

internal data class ModsWorkflowSemantics(
    val analysisCompleted: Boolean,
    val planReviewed: Boolean,
    val installCompleted: Boolean
)

internal fun modsWorkflowSemantics(
    analysis: ModPackageAnalysis?,
    operationBound: Boolean,
    destinationConfirmed: Boolean,
    installSucceeded: Boolean
): ModsWorkflowSemantics {
    val reviewed = analysis != null &&
        analysis.outcome == ModInstallOutcome.DIRECT_INSTALL_READY &&
        analysis.installable &&
        analysis.compatibility.compatible &&
        operationBound &&
        (!genericDestinationNeedsConfirmation(analysis) || destinationConfirmed) &&
        !analysis.installPlan.hasBlockingPreconditions
    return ModsWorkflowSemantics(
        analysisCompleted = analysis != null,
        planReviewed = reviewed,
        installCompleted = reviewed && installSucceeded
    )
}

internal fun modInstallUnavailableReason(
    analysis: ModPackageAnalysis,
    operationBound: Boolean,
    destinationConfirmed: Boolean
): String? = when {
    analysis.outcome == ModInstallOutcome.BUILT_IN_GAME_CONTENT ->
        "تم التعرف على الحزمة، لكن عقد الاستيراد الموثق والآمن غير متاح في NFVR؛ لم يتم نقل أي ملف."
    analysis.outcome == ModInstallOutcome.APK_PATCH_REQUIRED ->
        "تحتاج اللعبة إلى Patch وتجهيز نظام المودات أولًا؛ لا توجد أداة تصحيح مفعّلة في NFVR."
    analysis.outcome == ModInstallOutcome.REQUIRES_MOD_LOADER ->
        analysis.installPlan.loaderRequirement
            ?.let(::customerLoaderRequirementMessage)
            ?: "تحتاج الحزمة إلى محمّل مودات متوافق قبل التثبيت."
    analysis.outcome == ModInstallOutcome.UNSAFE_ARCHIVE ->
        "الحزمة غير آمنة أو تالفة؛ لم يتم نقل أي ملف."
    analysis.outcome == ModInstallOutcome.UNSUPPORTED ||
        analysis.packageType == ModPackageType.UNKNOWN ||
        !analysis.recognized ->
        modCustomerFailureReason(analysis)
    !analysis.installable || !analysis.compatibility.compatible ->
        "الخطة غير متوافقة مع اللعبة المحددة."
    analysis.installPlan.hasBlockingPreconditions ->
        "لم تكتمل متطلبات التثبيت الموضحة في الخطة."
    !operationBound ->
        "تغيّرت هوية الجهاز أو اللعبة أو الحزمة؛ أعد التحليل قبل التثبيت."
    genericDestinationNeedsConfirmation(analysis) && !destinationConfirmed ->
        "أكد الوجهة المقترحة قبل بدء التثبيت."
    else -> null
}

internal fun modsUiControlsEnabled(installing: Boolean): Boolean = !installing

internal fun sanitizeModsUiText(value: String): String {
    if (value.isBlank()) return value
    val withoutUrls = value.replace(
        Regex("(?i)\\b(?:https?://|www\\.)[^\\s<>]+"),
        ""
    )
    val withoutDomains = withoutUrls.replace(
        Regex("(?i)\\b(?:[a-z0-9-]+\\.)+(?:com|net|org|io|gg|dev|app)(?:/[^\\s<>]*)?"),
        ""
    )
    return withoutDomains
        .replace(Regex("(?i)\\bmod\\s*\\.\\s*io\\b"), "")
        .replace(Regex("(?i)\\b(?:browser|website|web\\s+page|open|visit|browse|external)\\b"), "")
        .replace(
            Regex("المتصفح|الموقع|صفحة\\s+(?:الويب|الإنترنت)|فتح\\s+(?:الرابط|صفحة)|افتح|اذهب\\s+إلى"),
            ""
        )
        .replace(Regex("\\s{2,}"), " ")
        .trim()
}

internal fun sanitizeModsLogText(value: String): String =
    value.lineSequence()
        .filterNot { line ->
            Regex(
                "(?i)(https?://|www\\.|\\bmod\\s*\\.\\s*io\\b|\\b(?:browser|website|web\\s+page|external)\\b|المتصفح|الموقع|صفحة\\s+(?:الويب|الإنترنت)|فتح\\s+(?:الرابط|صفحة)|رابط\\s+خارجي|إجراء\\s+خارجي)"
            ).containsMatchIn(line)
        }
        .map(::sanitizeModsUiText)
        .filter { it.isNotBlank() }
        .joinToString("\n")

@Composable
@Suppress("UNUSED_PARAMETER")
fun ModsWorkflowUi(
    connected: Boolean,
    pickerOpen: Boolean = false,
    pickerStatus: String? = null,
    deviceSerial: String? = null,
    deviceModel: String? = null,
    installedApps: List<InstalledQuestApp>,
    scanning: Boolean,
    searchFilter: String,
    onSearchFilterChange: (String) -> Unit,
    selectedApp: InstalledQuestApp?,
    onRefresh: () -> Unit,
    onCancelScan: () -> Unit = {},
    onCancelActiveScanOnSelection: () -> Unit = onCancelScan,
    scanProgress: InstalledAppsScanProgress? = null,
    showAllApps: Boolean = false,
    onShowAllAppsChange: (Boolean) -> Unit = {},
    onSelectApp: (InstalledQuestApp) -> Unit,
    onChangeSelectedApp: () -> Unit = {},
    onClearSelectedApp: () -> Unit = {},
    selectedZipFilename: String?,
    selectedZipSizeBytes: Long? = null,
    selectedZipSha256: String? = null,
    onChooseFile: () -> Unit,
    dropRouter: DesktopDropRouter? = null,
    analyzing: Boolean,
    analysis: ModPackageAnalysis?,
    onAnalyze: () -> Unit,
    analysisStatus: String? = null,
    installing: Boolean,
    executionProgress: ModsManager.ModExecutionProgress?,
    operationBound: Boolean = false,
    installSucceeded: Boolean = false,
    onInstall: () -> Unit,
     questPreparation: QuestPreparationUiState? = null,
     onQuestPreparationAction: () -> Unit = {},
     questProbe: QuestPreparationProbeUiState = QuestPreparationProbeUiState(),
     onQuestProbeScanAll: () -> Unit = {},
     onQuestProbeScan: (String) -> Unit = {},
     onQuestProbeCancel: () -> Unit = {},
     onQuestProbeExport: () -> Unit = {},
    // Kept for source compatibility with the host screen.  It is intentionally
    // not invoked; built-in content is informational in this UI.
    onOpenExternalUrl: (String) -> Unit = {},
    logText: String,
    modSupport: ModSupportUiState? = null,
    onCopyDiagnostics: () -> Unit = {},
    onDestinationConfirmed: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val modsTransitionScope = rememberCoroutineScope()
    fun clearFocusBeforeModsTransition(
        clearFocus: () -> Unit,
        transition: () -> Unit
    ) {
        modsTransitionScope.launch(Dispatchers.Main.immediate) {
            clearFocus()
            yield()
            transition()
        }
    }
    val orderedApps = remember(installedApps) { orderSupportedAppsFirst(installedApps) }
    val filteredApps = remember(orderedApps, searchFilter) {
        val query = searchFilter.trim().lowercase()
        orderedApps.filter {
            query.isBlank() ||
                (it.displayName ?: "").lowercase().contains(query) ||
                it.packageName.lowercase().contains(query)
        }
    }
    val editingEnabled = modsUiControlsEnabled(installing) && !pickerOpen
    var appPickerRequested by remember(selectedApp) { mutableStateOf(selectedApp == null) }
    var genericDestinationConfirmed by remember(analysis) {
        mutableStateOf(analysis?.let { !genericDestinationNeedsConfirmation(it) } ?: false)
    }
    val workflowSemantics = modsWorkflowSemantics(
        analysis,
        operationBound,
        genericDestinationConfirmed,
        installSucceeded
    )
    // Authoritative install action: derived ONLY from real installation
    // conditions.  Never from a numeric workflow stage.
    val installActionState = resolveModInstallActionState(
        analysis = analysis,
        operationBound = operationBound,
        destinationConfirmed = genericDestinationConfirmed,
        installing = installing,
        executionProgress = executionProgress,
        installSucceeded = installSucceeded
    )
    // Auto-analysis fires once per distinct (archive, game) selection so a
    // newly picked mod is inspected without a separate manual step.
    var autoAnalyzedKey by remember { mutableStateOf<String?>(null) }
    val selectionKey = listOfNotNull(
        selectedZipFilename?.trim()?.takeIf { it.isNotBlank() },
        selectedZipSha256?.trim()?.takeIf { it.isNotBlank() },
        selectedApp?.packageName
    ).joinToString("|")
    var advancedExpanded by remember { mutableStateOf(ADVANCED_MODS_SECTION_DEFAULT_EXPANDED) }
    var allFilesExpanded by remember(analysis) { mutableStateOf(false) }
    LaunchedEffect(
        connected, selectionKey, analyzing, installing, installSucceeded, pickerOpen
    ) {
        if (!connected || selectedApp == null || selectedZipFilename.isNullOrBlank() ||
            analyzing || installing || installSucceeded || pickerOpen
        ) return@LaunchedEffect
        if (autoAnalyzedKey == selectionKey) return@LaunchedEffect
        if (modSelectionMatchesAnalysis(selectedApp, selectedZipSha256, analysis)) {
            autoAnalyzedKey = selectionKey
            return@LaunchedEffect
        }
        autoAnalyzedKey = selectionKey
        focusManager.clearFocus(force = true)
        yield()
        onAnalyze()
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                item {
                    Header(
                        connected = connected,
                        scanning = scanning,
                        enabled = editingEnabled,
                        refresh = {
                            clearFocusBeforeModsTransition(
                                { focusManager.clearFocus(force = true) },
                                onRefresh
                            )
                        },
                        cancelScan = {
                            clearFocusBeforeModsTransition(
                                { focusManager.clearFocus(force = true) },
                                onCancelScan
                            )
                        }
                    )
                }
                if (scanning || scanProgress != null) {
                    item { ScanStatus(scanProgress, scanning) }
                }
                if (deviceSerial != null || deviceModel != null) {
                    item {
                        StatusNote(
                            "الفحص الحالي: ${deviceModel?.takeIf { it.isNotBlank() } ?: "Quest"}" +
                                " · ${shortSerial(deviceSerial)}",
                            Icons.Default.Info,
                            MaterialTheme.colorScheme.primary
                        )
                    }
                }
                item { StepTitle("1", "اختر اللعبة", "الألعاب المدعومة أولًا — ثم جميع التطبيقات عند الحاجة") }
                if (selectedApp == null || appPickerRequested) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                SearchBox(searchFilter, onSearchFilterChange, enabled = editingEnabled)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = showAllApps,
                                        enabled = editingEnabled,
                                        onCheckedChange = { value ->
                                            clearFocusBeforeModsTransition(
                                                { focusManager.clearFocus(force = true) }
                                            ) {
                                                onShowAllAppsChange(value)
                                            }
                                        }
                                    )
                                    Text("عرض جميع التطبيقات", style = MaterialTheme.typography.labelMedium)
                                }
                                when {
                                    !connected && filteredApps.isEmpty() -> EmptyState("النظارة غير متصلة", "اشبك Quest ووافق على USB Debugging، ثم حدّث الاتصال.", Icons.Default.Info)
                                    scanning && filteredApps.isEmpty() -> LoadingApps()
                                    filteredApps.isEmpty() -> EmptyState("لا توجد ألعاب مطابقة", "جرّب كلمة بحث أخرى أو حدّث قائمة التطبيقات.", Icons.Default.Search)
                                    else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        filteredApps.forEach { app ->
                                            AppRow(app, app == selectedApp, enabled = editingEnabled) {
                                                clearFocusBeforeModsTransition(
                                                    { focusManager.clearFocus(force = true) }
                                                ) {
                                                    if (scanning) onCancelActiveScanOnSelection()
                                                    appPickerRequested = false
                                                    onSelectApp(app)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    item {
                        SelectedAppCard(
                            selectedApp,
                            enabled = editingEnabled,
                            onChange = {
                                clearFocusBeforeModsTransition(
                                    { focusManager.clearFocus(force = true) }
                                ) {
                                    appPickerRequested = true
                                    onChangeSelectedApp()
                                }
                            },
                            onClear = {
                                clearFocusBeforeModsTransition(
                                    { focusManager.clearFocus(force = true) }
                                ) {
                                    appPickerRequested = true
                                    onClearSelectedApp()
                                }
                            }
                        )
                    }
                }
                item { StepTitle("2", "اختر ملف المود", "ZIP أو QMOD — يبدأ الفحص تلقائيًا بعد الاختيار") }
                item {
                    ZipCard(
                        selectedZipFilename,
                        selectedZipSizeBytes,
                        selectedZipSha256,
                        pickerStatus,
                        onChooseFile = {
                            clearFocusBeforeModsTransition(
                                { focusManager.clearFocus(force = true) },
                                onChooseFile
                            )
                        },
                        selectedApp = selectedApp,
                        enabled = editingEnabled,
                        dropRouter = dropRouter
                    )
                }
                if (analyzing) {
                    item {
                        StatusNote(
                            "جارٍ فحص المود…",
                            Icons.Default.Info,
                            MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (analysis != null && !analyzing && !installing && !installSucceeded) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                onClick = {
                                    clearFocusBeforeModsTransition(
                                        { focusManager.clearFocus(force = true) },
                                        onAnalyze
                                    )
                                },
                                enabled = connected && selectedApp != null &&
                                    !selectedZipFilename.isNullOrBlank()
                            ) {
                                Icon(Icons.Default.Refresh, null)
                                Spacer(Modifier.width(8.dp))
                                Text("إعادة التحليل")
                            }
                        }
                    }
                }
                analysisStatus?.takeIf { it.isNotBlank() }?.let { status ->
                    item {
                        StatusNote(
                            status,
                            if (analyzing) Icons.Default.Info else Icons.Default.Warning,
                            if (analyzing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    }
                }
                item { StepTitle("3", "مراجعة", "ملخص واحد — التفاصيل التقنية في الأسفل") }
                if (analysis != null) {
                    item {
                        CompactReviewCard(
                            analysis = analysis,
                            operationBound = operationBound,
                            destinationConfirmed = genericDestinationConfirmed,
                            onDestinationConfirmationChanged = {
                                genericDestinationConfirmed = it
                                onDestinationConfirmed(it)
                            },
                            allFilesExpanded = allFilesExpanded,
                            onAllFilesExpandedChange = { allFilesExpanded = it }
                        )
                    }
                }
                if (selectedApp != null &&
                    questPreparation != null &&
                    modsPreparationPanelVisible(analysis)
                ) {
                    item {
                        QuestPreparationCard(
                            selectedApp = selectedApp,
                            state = questPreparation,
                            loaderRequired = modsPreparationIsLoaderPanel(analysis),
                            loaderRequirement = analysis?.installPlan?.loaderRequirement,
                            loaderPreconditions = analysis?.installPlan?.preconditions.orEmpty(),
                            actionEnabled = questPreparationActionEnabled(
                                connected = connected,
                                installing = installing,
                                analyzing = analyzing,
                                pickerOpen = pickerOpen,
                                busy = questPreparation.busy
                            ) && !modsPreparationIsLoaderPanel(analysis),
                            onAction = onQuestPreparationAction
                        )
                    }
                }
                item { StepTitle("4", "تثبيت", "النقل يبدأ فقط من زر التثبيت — والنجاح فقط بعد التحقق البعيد") }
                if (installing || executionProgress != null) item { ProgressCard(executionProgress) }
                if (analysis != null && workflowSemantics.installCompleted) {
                    item { InstallationSuccessCard() }
                }
                if (installActionState == ModInstallActionState.FAILED && analysis != null) {
                    item {
                        ModInstallFailureCard(
                            reason = modInstallUnavailableReason(
                                analysis,
                                operationBound,
                                genericDestinationConfirmed
                            ) ?: executionProgress?.message?.takeIf { it.isNotBlank() }
                            ?: "تعذر تثبيت المود.",
                            onRetry = {
                                clearFocusBeforeModsTransition(
                                    { focusManager.clearFocus(force = true) },
                                    onAnalyze
                                )
                            },
                            onShowDetails = { advancedExpanded = true }
                        )
                    }
                }
                item {
                    AdvancedModsSection(
                        expanded = advancedExpanded,
                        onExpandedChange = { advancedExpanded = it },
                        analysis = analysis,
                        onCopyDiagnostics = onCopyDiagnostics,
                        destinationConfirmed = genericDestinationConfirmed,
                        onDestinationConfirmationChanged = {
                            genericDestinationConfirmed = it
                            onDestinationConfirmed(it)
                        },
                        questProbe = questProbe,
                        connected = connected,
                        probeEnabled = !installing && !analyzing && !pickerOpen,
                        onQuestProbeScanAll = onQuestProbeScanAll,
                        onQuestProbeScan = onQuestProbeScan,
                        onQuestProbeCancel = onQuestProbeCancel,
                        onQuestProbeExport = onQuestProbeExport,
                        modSupport = modSupport,
                        logText = logText
                    )
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
            StickyModInstallBar(
                state = installActionState,
                analysis = analysis,
                executionProgress = executionProgress,
                enabled = editingEnabled,
                onInstall = {
                    clearFocusBeforeModsTransition(
                        { focusManager.clearFocus(force = true) },
                        onInstall
                    )
                },
                onChooseAnother = {
                    clearFocusBeforeModsTransition(
                        { focusManager.clearFocus(force = true) },
                        onChooseFile
                    )
                }
            )
        }
    }
}
}

@Composable
private fun LoaderPreparationCard(
    selectedApp: InstalledQuestApp,
    requirement: ModLoaderRequirement?,
    preconditions: List<ModInstallPrecondition>
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("متطلبات محمّل المودات", style = MaterialTheme.typography.titleMedium)
            Text(
                questPreparationSelectedPackageLabel(selectedApp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            LoaderRequirementDetails(
                requirement,
                preconditions.filter(::isLoaderPrecondition)
            )
            preconditions.filterNot { it.satisfied }.filter(::isLoaderPrecondition)
                .forEach {
                    StatusNote(
                        customerPreconditionMessage(it.code),
                        Icons.Default.Warning,
                        warningColor()
                    )
                }
            StatusNote(
                "لا توجد في NFVR أداة تنفيذية معتمدة لتجهيز هذا المحمّل؛ لم يتم نقل أي ملف.",
                Icons.Default.Info,
                warningColor()
            )
        }
    }
}

@Composable
private fun QuestPreparationCard(
    selectedApp: InstalledQuestApp,
    state: QuestPreparationUiState,
    loaderRequired: Boolean = false,
    loaderRequirement: ModLoaderRequirement? = null,
    loaderPreconditions: List<ModInstallPrecondition> = emptyList(),
    actionEnabled: Boolean,
    onAction: () -> Unit
) {
    if (loaderRequired) {
        LoaderPreparationCard(
            selectedApp = selectedApp,
            requirement = loaderRequirement,
            preconditions = loaderPreconditions
        )
        return
    }
    val report = state.report
    val rows = questPreparationStageRows(report)
    val blocked = report?.blockers.orEmpty()
    val backup = report?.backup
    var diagnosticsExpanded by remember { mutableStateOf(false) }
    val actionLabel = when {
        loaderRequired -> null
        state.busy -> "جارٍ الفحص…"
        report == null -> "فحص جاهزية APK — قراءة فقط"
        backup?.integrityVerified != true -> "إنشاء نسخة APK محلية والتحقق منها"
        else -> "إعادة فحص نسخة APK المحلية"
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "تجهيز APK مطلوب للعبة",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                questPreparationSelectedPackageLabel(report?.app ?: selectedApp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "الفحص والنسخ محليان فقط. النسخة الاحتياطية تشمل APK الأساسي وملفات APK المجزأة فقط؛ لا تشمل OBB أو بيانات التطبيق.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            StatusNote(
                "لا ينفّذ NFVR تصحيح APK أو توقيعًا أو إلغاء تثبيت أو إعادة تثبيت، ولا يوفّر rollback تنفيذيًا.",
                Icons.Default.Info,
                MaterialTheme.colorScheme.primary
            )
            StatusNote(
                questPreparationResultLabel(report),
                if (report?.readyForModInstall == true) Icons.Default.CheckCircle else Icons.Default.Info,
                if (report?.readyForModInstall == true) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            rows.forEach { row ->
                val color = when (row.state) {
                    QuestStageState.COMPLETE -> MaterialTheme.colorScheme.tertiary
                    QuestStageState.BLOCKED -> MaterialTheme.colorScheme.error
                    QuestStageState.NOT_STARTED -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        if (row.state == QuestStageState.COMPLETE) "✓" else "•",
                        color = color,
                        fontWeight = FontWeight.Bold
                    )
                    Column(Modifier.weight(1f)) {
                         Row(
                             Modifier.fillMaxWidth(),
                             horizontalArrangement = Arrangement.SpaceBetween,
                             verticalAlignment = Alignment.CenterVertically
                         ) {
                             Text(
                                 questPreparationStageTitle(row.stage),
                                 style = MaterialTheme.typography.labelLarge,
                                 color = color
                             )
                             Text(
                                 questPreparationStageStateLabel(row.state),
                                 style = MaterialTheme.typography.labelSmall,
                                 color = color
                             )
                         }
                         Text(
                             questPreparationStageSummary(row),
                             style = MaterialTheme.typography.bodySmall
                         )
                    }
                }
            }
            if (!loaderRequired) backup?.let {
                StatusNote(
                     questPreparationBackupLabel(it),
                    Icons.Default.Info,
                    if (it.integrityVerified) MaterialTheme.colorScheme.tertiary else warningColor()
                )
            }
             blocked.take(3).forEach { blocker ->
                StatusNote(
                     questPreparationBlockerLabel(blocker),
                    Icons.Default.Warning,
                    MaterialTheme.colorScheme.error
                )
            }
             if (state.busy) {
                 Text("جارٍ جمع أدلة القراءة والتحقق المحلي…", style = MaterialTheme.typography.bodySmall)
             }
             OutlinedButton(
                 onClick = { diagnosticsExpanded = !diagnosticsExpanded },
                 enabled = questPreparationDiagnostics(report).isNotEmpty()
             ) {
                 Text(if (diagnosticsExpanded) "إخفاء التفاصيل التقنية" else "عرض التفاصيل التقنية")
             }
             if (diagnosticsExpanded) {
                 SelectionContainer {
                     Text(
                         questPreparationDiagnostics(report).joinToString("\n"),
                         fontFamily = FontFamily.Monospace,
                         fontSize = 12.sp,
                         color = MaterialTheme.colorScheme.onSurfaceVariant
                     )
                 }
             }
             actionLabel?.let { label -> Button(onClick = onAction, enabled = actionEnabled) {
                if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text(label)
            } }
        }
    }
}

@Composable
private fun QuestPreparationProbeSection(
    state: QuestPreparationProbeUiState,
    connected: Boolean,
    enabled: Boolean,
    onScanAll: () -> Unit,
    onScan: (String) -> Unit,
    onCancel: () -> Unit,
    onExport: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("فحص جاهزية الألعاب للمودات", style = MaterialTheme.typography.titleMedium)
            Text(
                "فحص قراءة فقط للألعاب الأربعة المستهدفة؛ لا يشمل تطبيقات أخرى ولا يغيّر ملفات النظارة.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.busy || state.completed.isNotEmpty()) {
                 Text("${questProbeCompletedCount(state)} / ${state.totalGames} ألعاب", style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(
                    progress = { questProbeProgress(state) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(questProbeStatus(state), style = MaterialTheme.typography.bodySmall)
            if (state.report != null) state.report.discoveries.forEach { discovery ->
                 // Discovery remains authoritative. Evidence warnings describe
                 // a partial/failed probe and must never overwrite FOUND with
                 // NOT_FOUND.
                 val completed = state.completed.firstOrNull { game ->
                     discovery.candidates.any { it.packageId == game.packageId }
                 }
                 val row = questProbeRowPresentation(
                     discovery = discovery,
                     game = completed,
                     busy = state.busy && state.currentGame.equals(discovery.game, true),
                     cancelled = state.cancelled
                 )
                 val tone = when (row.discoveryState) {
                     ProbeTargetState.FOUND -> MaterialTheme.colorScheme.tertiary
                     ProbeTargetState.AMBIGUOUS -> warningColor()
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(discovery.game, style = MaterialTheme.typography.labelLarge)
                        Text(
                             row.discoveryLabel +
                                 if (discovery.candidates.isEmpty()) "" else
                                     " · " + discovery.candidates.joinToString { it.packageId },
                            style = MaterialTheme.typography.bodySmall,
                            color = tone
                        )
                         Text(row.probeLabel, style = MaterialTheme.typography.bodySmall)
                         completed?.let { game ->
                             Text(
                                 questProbeGameReadinessLabel(game),
                                 style = MaterialTheme.typography.bodySmall,
                                 color = if (
                                     questProbeGameReadinessLabel(game) == "مودات المحتوى جاهزة للتثبيت"
                                 ) {
                                     MaterialTheme.colorScheme.tertiary
                                 } else {
                                     MaterialTheme.colorScheme.onSurfaceVariant
                                 }
                             )
                             if (game.loader != ProbeLoader.NONE) {
                                 Text(
                                     "دليل المحمّل: ${questProbeLoaderLabel(game.loader)}",
                                     style = MaterialTheme.typography.labelSmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant
                                 )
                             }
                         }
                        if (completed?.warnings?.isNotEmpty() == true) {
                             Text(
                                 questProbeFailureLabel(
                                     completed.apkInspectionFailureCode
                                         ?: completed.warnings.firstOrNull()
                                 ) ?: "تعذر إكمال بعض الأدلة؛ بقي اكتشاف اللعبة منفصلًا عن فحص APK.",
                                 style = MaterialTheme.typography.bodySmall,
                                 color = warningColor()
                             )
                        }
                    }
                    OutlinedButton(
                        onClick = { onScan(discovery.game) },
                        enabled = connected && enabled && !state.busy
                    ) { Text("فحص") }
                }
            } else questProbeTargetNames().forEach { game ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(game, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    OutlinedButton(onClick = { onScan(game) }, enabled = connected && enabled && !state.busy) {
                        Text("فحص")
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onScanAll, enabled = connected && enabled && !state.busy) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Search, null)
                    Spacer(Modifier.width(8.dp))
                    Text("فحص الكل")
                }
                if (state.busy) OutlinedButton(onClick = onCancel) { Text("إلغاء") }
                OutlinedButton(
                    onClick = onExport,
                    enabled = !state.busy && state.report != null && !state.stale
                ) { Text("تصدير التقرير") }
            }
            if (state.exportedJson != null && state.exportedText != null) {
                StatusNote(
                    "تم التصدير:\nJSON: ${state.exportedJson}\nTXT: ${state.exportedText}",
                    Icons.Default.CheckCircle,
                    MaterialTheme.colorScheme.tertiary
                )
            }
            if (state.stale) {
                StatusNote("تم تجاهل نتيجة مرتبطة بجهاز سابق.", Icons.Default.Warning, warningColor())
            }
            state.error?.let { StatusNote(it, Icons.Default.Warning, warningColor()) }
        }
    }
}

@Composable private fun Header(
    connected: Boolean,
    scanning: Boolean,
    enabled: Boolean,
    refresh: () -> Unit,
    cancelScan: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text("NFVR / QUEST INSTALLER", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text("تثبيت المودات", style = MaterialTheme.typography.displaySmall)
            Text(if (connected) "اختر اللعبة والمود، وNFVR يتولى الفحص والتثبيت." else "اتصل بنظارة Quest لفتح سير العمل.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatusPill(if (connected) "متصل" else "غير متصل", connected)
        if (scanning) {
            OutlinedButton(onClick = cancelScan, enabled = enabled) { Text("إلغاء الفحص") }
        }
        IconButton(onClick = refresh, enabled = enabled && !scanning) { Icon(Icons.Default.Refresh, "تحديث") }
    }
}

@Composable private fun ScanStatus(progress: InstalledAppsScanProgress?, scanning: Boolean) {
    val value = progress
    val complete = value?.completed == true && value.error == null && !value.cancelled
    Card(colors = CardDefaults.cardColors(containerColor = if (complete) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                when {
                    value?.cancelled == true -> "تم إلغاء فحص التطبيقات — تم الاحتفاظ بالنتائج الجزئية"
                    scanning -> "جارٍ فحص التطبيقات المثبتة…"
                    value?.error != null -> "تعذر تحديث فحص التطبيقات"
                    complete -> "اكتمل فحص التطبيقات"
                    else -> "جاهز لفحص التطبيقات"
                },
                style = MaterialTheme.typography.titleSmall,
                color = if (complete) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface
            )
            if (value?.deviceSerial != null) {
                Text(
                    "الجهاز: ${value.deviceModel?.takeIf { it.isNotBlank() } ?: "Quest"} · " +
                        shortSerial(value.deviceSerial),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (value != null) {
                val total = value.total
                val count = value.discoveredCount.coerceAtLeast(value.apps.size)
                Text(
                    if (total == null) "تمت معالجة ${value.processed} تطبيقًا — المكتشف: $count"
                    else "${value.processed} / $total — المكتشف: $count" +
                        (value.percent?.let { " — $it%" } ?: ""),
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    "آخر تحديث: ${formatLocalTime(Instant.ofEpochMilli(value.lastUpdatedMillis))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                value.status?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall)
                }
                if (scanning) {
                    if (value.total == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(
                        progress = { (value.percent?.toFloat()?.div(100f) ?: 0f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (scanning && value == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable private fun StepTitle(number: String, title: String, subtitle: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Text(number, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun SearchBox(value: String, change: (String) -> Unit, enabled: Boolean) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            BasicTextField(value, change, Modifier.weight(1f), enabled = enabled, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), singleLine = true, decorationBox = { inner ->
                if (value.isBlank()) Text("ابحث باسم اللعبة أو package id", color = MaterialTheme.colorScheme.onSurfaceVariant)
                inner()
            })
        }
    }
}

@Composable private fun AppRow(app: InstalledQuestApp, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) { Text((app.displayName ?: app.packageName).take(1).uppercase(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app.displayName ?: app.packageName, style = MaterialTheme.typography.titleMedium)
                Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(app.versionName ?: "إصدار غير معروف", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (selected) { Spacer(Modifier.width(10.dp)); Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.tertiary) }
        }
    }
}

@Composable private fun SelectedAppCard(
    app: InstalledQuestApp?,
    enabled: Boolean,
    onChange: () -> Unit,
    onClear: () -> Unit
) {
    if (app == null) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(app.displayName ?: app.packageName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(modGameReadinessBadge(app), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = onChange, enabled = enabled) { Text("تغيير") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = onClear, enabled = enabled) { Text("مسح") }
        }
    }
}

@Composable private fun CompletedSummaryCard(label: String, value: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("مكتمل", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable private fun ZipCard(
    filename: String?,
    sizeBytes: Long?,
    sha256: String?,
    pickerStatus: String?,
    onChooseFile: () -> Unit,
    selectedApp: InstalledQuestApp?,
    enabled: Boolean,
    dropRouter: DesktopDropRouter?
) {
    val hovered = dropRouter?.hovered?.collectAsState()?.value == DesktopDropTarget.MOD_PACKAGE
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(
            Modifier
                .registerDesktopDropTargetIf(dropRouter, DesktopDropTarget.MOD_PACKAGE)
                .padding(18.dp)
                .background(
                    if (hovered == true) MaterialTheme.colorScheme.primaryContainer
                    else Color.Transparent,
                    RoundedCornerShape(12.dp)
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f)) {
                     Text(filename ?: "لم يتم اختيار ملف ZIP أو QMOD", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                     Text(if (filename == null) "اختر حزمة مود من جهاز الكمبيوتر" else "الحزمة جاهزة للتحليل البنيوي", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = onChooseFile, enabled = enabled) { Text("اختيار ملف") }
            }
            Text(
                if (hovered == true) "أفلت ملف ZIP أو QMOD هنا للتحليل"
                else "أو اسحب ملف ZIP أو QMOD إلى هذه المنطقة",
                style = MaterialTheme.typography.labelMedium,
                color = if (hovered == true) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (sizeBytes != null || !sha256.isNullOrBlank()) {
                Text(
                    listOfNotNull(
                        sizeBytes?.let { bytes(it) },
                        sha256?.takeIf { it.isNotBlank() }?.let { "SHA-256: ${it.take(16)}…" }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            pickerStatus?.takeIf { it.isNotBlank() }?.let {
                StatusNote(it, Icons.Default.Info, MaterialTheme.colorScheme.primary)
            }
            if (selectedApp != null) StatusNote("الهدف المحدد: ${selectedApp.displayName ?: selectedApp.packageName}", Icons.Default.CheckCircle, MaterialTheme.colorScheme.tertiary)
        }
    }
}

private fun Modifier.registerDesktopDropTargetIf(
    router: DesktopDropRouter?,
    target: DesktopDropTarget
): Modifier = if (router == null) this else registerDesktopDropTarget(router, target)

@Composable private fun BuiltInContentCard(analysis: ModPackageAnalysis) {
    val outcome = modsUiOutcome(analysis)
    val tone = outcomeColor(outcome.tone)
    Card(
        colors = CardDefaults.cardColors(containerColor = tone.copy(alpha = 0.10f)),
        border = BorderStroke(1.dp, tone)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, tint = tone)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(outcome.title, style = MaterialTheme.typography.titleMedium, color = tone)
                    Text(
                        "النوع: ${displayModPackageType(analysis.packageType)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                modCustomerFailureReason(analysis),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            StatusNote(outcome.action, Icons.Default.Info, tone)
            StatusNote(
                "لم يتم تثبيت أي ملف على النظارة.",
                Icons.Default.CheckCircle,
                tone
            )
        }
    }
}

@Composable
private fun AnalysisResultCard(analysis: ModPackageAnalysis) {
    val outcome = modsUiOutcome(analysis)
    val tone = outcomeColor(outcome.tone)
    Card(
        colors = CardDefaults.cardColors(containerColor = tone.copy(alpha = 0.12f)),
        border = BorderStroke(2.dp, tone)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("نتيجة التحليل", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(outcome.title, style = MaterialTheme.typography.headlineSmall, color = tone)
            Text("النوع: ${displayModPackageType(analysis.packageType)}", style = MaterialTheme.typography.titleMedium)
             Text(modCustomerFailureReason(analysis), style = MaterialTheme.typography.bodyLarge)
            StatusNote(outcome.action, Icons.Default.Info, tone)
            StatusNote(outcome.changedFiles, Icons.Default.Info, tone)
        }
    }
}

@Composable
private fun InstallationSuccessCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("تم تثبيت المود بنجاح", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.tertiary)
            Text("تم نقل الملفات والتحقق منها على النظارة.", style = MaterialTheme.typography.bodyLarge)
            StatusNote("تم تغيير الملفات بعد اكتمال التحقق البعيد.", Icons.Default.CheckCircle, MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable private fun ModSupportCard(state: ModSupportUiState) {
    val tone = if (state.stale) warningColor() else MaterialTheme.colorScheme.primary
    Card(
        colors = CardDefaults.cardColors(containerColor = tone.copy(alpha = 0.08f)),
        border = BorderStroke(1.dp, tone.copy(alpha = 0.55f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("اكتشاف دعم المود", style = MaterialTheme.typography.titleMedium)
            Text(state.status, color = tone)
            Text(
                "الجهاز: ${shortSerial(state.serial)} · الحزمة: ${state.packageId}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "ملف اللعبة: ${state.profileName ?: "غير معروف"} · دليل القراءة: ${state.loader}",
                style = MaterialTheme.typography.bodySmall
            )
            state.existingDirectory?.let {
                Text("مجلد مودات مقروء: $it", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable private fun ReviewRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * One compact customer review card.  Summaries only — the full mapping,
 * hashes, and evidence stay in the advanced section.
 */
@Composable private fun CompactReviewCard(
    analysis: ModPackageAnalysis,
    operationBound: Boolean,
    destinationConfirmed: Boolean,
    onDestinationConfirmationChanged: (Boolean) -> Unit,
    allFilesExpanded: Boolean,
    onAllFilesExpandedChange: (Boolean) -> Unit
) {
    val outcome = modsUiOutcome(analysis)
    val tone = outcomeColor(outcome.tone)
    val plan = analysis.installPlan
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(2.dp, tone)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (outcome.tone == ModsUiStatusTone.SUCCESS) Icons.Default.CheckCircle
                    else if (outcome.tone == ModsUiStatusTone.ERROR) Icons.Default.Warning
                    else Icons.Default.Info,
                    null,
                    tint = tone
                )
                Spacer(Modifier.width(10.dp))
                Text(outcome.title, style = MaterialTheme.typography.titleLarge, color = tone)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ReviewRow("اللعبة", plan.reviewedApp?.displayName ?: plan.targetPackageId ?: "غير محدد")
            ReviewRow("نوع المود", displayModPackageType(analysis.packageType))
            ReviewRow("الحجم", modCompactBytes(plan.totalBytes))
            ReviewRow(
                "الملفات",
                if (plan.totalFiles == 1) "ملف واحد" else "${plan.totalFiles} ملفًا"
            )
            ReviewRow("الوجهة", modCompactDestination(analysis) ?: "غير معلنة")
            ReviewRow(
                "التوافق",
                if (analysis.compatibility.compatible) "متوافق" else "غير متوافق"
            )
            ReviewRow(
                "محمّل إضافي",
                plan.loaderRequirement?.requested
                    ?.sortedBy { it.displayName }
                    ?.joinToString(" أو ") { it.displayName }
                    ?.takeIf { it.isNotBlank() } ?: "غير مطلوب"
            )
            if (genericDestinationNeedsConfirmation(analysis) || plan.confirmation != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = destinationConfirmed,
                        onCheckedChange = onDestinationConfirmationChanged
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "أؤكد الوجهة المقترحة؛ لن تتم الكتابة قبل هذا التأكيد.",
                        color = warningColor(),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            if (plan.mappings.isNotEmpty()) {
                val total = plan.mappings.size
                Text(
                    if (total == 1) "ملف واحد سيتم تثبيته" else "$total ملفًا سيتم تثبيتها",
                    style = MaterialTheme.typography.labelLarge
                )
                modFileMappingsPreview(plan.mappings, allFilesExpanded).forEach { mapping ->
                    Text(
                        "← ${mapping.destinationPath.substringAfterLast('/')}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (total > 5) {
                    OutlinedButton(onClick = { onAllFilesExpandedChange(!allFilesExpanded) }) {
                        Text(if (allFilesExpanded) "إخفاء" else "عرض جميع الملفات ($total)")
                    }
                }
            }
            if (plan.hasBlockingPreconditions || !analysis.installable) {
                StatusNote(
                    modCustomerFailureReason(analysis),
                    Icons.Default.Warning,
                    outcomeColor(outcome.tone)
                )
            }
            modInstallUnavailableReason(analysis, operationBound, destinationConfirmed)?.let { reason ->
                if (outcome.tone != ModsUiStatusTone.SUCCESS) {
                    StatusNote(reason, Icons.Default.Info, outcomeColor(outcome.tone))
                }
            }
        }
    }
}

/**
 * Always-reachable bottom action bar.  Rendered from the authoritative
 * [ModInstallActionState] only — never from a numeric workflow stage.
 */
@Composable private fun StickyModInstallBar(
    state: ModInstallActionState,
    analysis: ModPackageAnalysis?,
    executionProgress: ModsManager.ModExecutionProgress?,
    enabled: Boolean,
    onInstall: () -> Unit,
    onChooseAnother: () -> Unit
) {
    if (state == ModInstallActionState.HIDDEN) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 28.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                ModInstallActionState.HIDDEN -> Unit
                ModInstallActionState.READY -> {
                    val plan = analysis?.installPlan
                    Text(
                        if (plan != null) "جاهز للتثبيت · ${plan.totalFiles} ملف · ${modCompactBytes(plan.totalBytes)}"
                        else "جاهز للتثبيت",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Button(
                        onClick = onInstall,
                        enabled = enabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text("تثبيت المود", style = MaterialTheme.typography.titleMedium)
                    }
                }
                ModInstallActionState.INSTALLING, ModInstallActionState.VERIFYING -> {
                    val fraction = executionProgress?.fraction
                    Text(
                        if (state == ModInstallActionState.VERIFYING) "جارٍ التحقق من الملفات على النظارة…"
                        else "جارٍ تثبيت المود…",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (fraction == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator({ fraction.toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                        Text(
                            "${(fraction * 100).toInt()}% · ${modInstallPhaseLabel(executionProgress?.phase)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                ModInstallActionState.SUCCESS -> {
                    Text("تم تثبيت المود بنجاح", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary)
                    OutlinedButton(onClick = onChooseAnother, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Text("تثبيت مود آخر")
                    }
                }
                ModInstallActionState.FAILED -> {
                    Text("تعذر تثبيت المود", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onChooseAnother, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Text("اختيار مود آخر")
                    }
                }
            }
        }
    }
}

@Composable private fun ModInstallFailureCard(
    reason: String,
    onRetry: () -> Unit,
    onShowDetails: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.error)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("تعذر تثبيت المود", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
            Text(reason, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRetry) { Text("إعادة المحاولة") }
                OutlinedButton(onClick = onShowDetails) { Text("عرض التفاصيل") }
            }
        }
    }
}

/**
 * Collapsed-by-default engineering area.  Preserves every diagnostic
 * capability (readiness scan, evidence, mappings, log, export) without
 * overwhelming the normal install flow.
 */
@Composable private fun AdvancedModsSection(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    analysis: ModPackageAnalysis?,
    onCopyDiagnostics: () -> Unit,
    destinationConfirmed: Boolean,
    onDestinationConfirmationChanged: (Boolean) -> Unit,
    questProbe: QuestPreparationProbeUiState,
    connected: Boolean,
    probeEnabled: Boolean,
    onQuestProbeScanAll: () -> Unit,
    onQuestProbeScan: (String) -> Unit,
    onQuestProbeCancel: () -> Unit,
    onQuestProbeExport: () -> Unit,
    modSupport: ModSupportUiState?,
    logText: String
) {
    var advancedFilesExpanded by remember(analysis) { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("التفاصيل والفحوصات المتقدمة", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { onExpandedChange(!expanded) }) {
                    Text(if (expanded) "إخفاء" else "عرض")
                }
            }
            if (!expanded) return@Column
            Text(
                "فحوصات متقدمة",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            QuestPreparationProbeSection(
                state = questProbe,
                connected = connected,
                enabled = probeEnabled,
                onScanAll = onQuestProbeScanAll,
                onScan = onQuestProbeScan,
                onCancel = onQuestProbeCancel,
                onExport = onQuestProbeExport
            )
            if (modSupport != null) {
                ModSupportCard(modSupport)
            }
            if (analysis != null) {
                AnalysisCard(
                    analysis,
                    onCopyDiagnostics = onCopyDiagnostics,
                    destinationConfirmed = destinationConfirmed,
                    onDestinationConfirmationChanged = onDestinationConfirmationChanged,
                    filesExpanded = advancedFilesExpanded,
                    onFilesExpandedChange = { advancedFilesExpanded = it }
                )
            }
            LogCard(sanitizeModsLogText(logText))
        }
    }
}

@Composable private fun AnalysisCard(
    analysis: ModPackageAnalysis,
    onCopyDiagnostics: () -> Unit,
    destinationConfirmed: Boolean,
    onDestinationConfirmationChanged: (Boolean) -> Unit,
    filesExpanded: Boolean = false,
    onFilesExpandedChange: (Boolean) -> Unit = {}
) {
    val outcome = modsUiOutcome(analysis)
    val tone = outcomeColor(outcome.tone)
    val allWarnings = deduplicateModWarnings(analysis.installPlan.preconditions)
    val loaderPreconditions = allWarnings.filter(::isLoaderPrecondition)
    val warnings = allWarnings.filterNot(::isLoaderPrecondition)
    val displayedWarnings = allWarnings.map { customerPreconditionMessage(it.code) }.toSet()
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, tone)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (outcome.tone == ModsUiStatusTone.ERROR) Icons.Default.Warning
                    else if (outcome.tone == ModsUiStatusTone.SUCCESS) Icons.Default.CheckCircle
                    else Icons.Default.Info,
                    null,
                    tint = tone
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(outcome.title, style = MaterialTheme.typography.titleMedium, color = tone)
                    Text(
                         modCustomerFailureReason(analysis),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TypeTag(displayModPackageType(analysis.packageType))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            InfoGrid(analysis)
            val archiveTree = analysis.archiveTree
            if (analysis.installPlan.dependencies.isNotEmpty() ||
                analysis.installPlan.optionalDependencies.isNotEmpty() ||
                archiveTree?.dependencyPaths?.isNotEmpty() == true ||
                archiveTree?.nestedArchivePaths?.isNotEmpty() == true
            ) {
                Text("اعتماديات وبنية الأرشيف", style = MaterialTheme.typography.titleMedium)
                analysis.installPlan.dependencies.forEach { dependency ->
                    StatusNote(
                        "اعتمادية مطلوبة: ${dependency.id}" +
                            (dependency.version?.let { " — الإصدار $it" } ?: "") +
                            " — يلزم التحقق قبل النقل.",
                        Icons.Default.Warning,
                        warningColor()
                    )
                }
                analysis.installPlan.optionalDependencies.forEach { dependency ->
                    StatusNote(
                        "اعتمادية اختيارية: ${dependency.id}" +
                            (dependency.version?.let { " — الإصدار $it" } ?: ""),
                        Icons.Default.Info,
                        MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                archiveTree?.dependencyPaths?.forEach { path ->
                    StatusNote(
                        "ملف اعتماد داخل الأرشيف: $path",
                        Icons.Default.Info,
                        MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                archiveTree?.nestedArchivePaths?.forEach { path ->
                    StatusNote(
                        "أرشيف متداخل لم يُفك تلقائيًا: $path",
                        Icons.Default.Warning,
                        warningColor()
                    )
                }
            }
            if (genericDestinationNeedsConfirmation(analysis) ||
                analysis.installPlan.confirmation != null
            ) {
                Checkbox(
                    checked = destinationConfirmed,
                    onCheckedChange = onDestinationConfirmationChanged
                )
                Text(
                    if (genericDestinationNeedsConfirmation(analysis)) {
                        "أؤكد الوجهة المقترحة الظاهرة أدناه؛ لن تتم الكتابة قبل هذا التأكيد."
                    } else {
                        "تم تأكيد الوجهة المقترحة لهذه الخطة."
                    },
                    color = warningColor(),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            StatusNote(outcome.action, Icons.Default.Info, tone)
            if (analysis.installPlan.mappings.isNotEmpty()) {
                val total = analysis.installPlan.mappings.size
                Text(
                    if (total == 1) "ملف واحد سيتم تثبيته"
                    else "$total ملفًا سيتم تثبيتها",
                    style = MaterialTheme.typography.titleMedium
                )
                modFileMappingsPreview(analysis.installPlan.mappings, filesExpanded).forEach { mapping ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(
                            mapping.sourcePath,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "← ${mapping.destinationPath}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (total > 5) {
                    OutlinedButton(onClick = { onFilesExpandedChange(!filesExpanded) }) {
                        Text(if (filesExpanded) "إخفاء القائمة الكاملة" else "عرض جميع الملفات ($total)")
                    }
                }
            }
            LoaderRequirementDetails(analysis.installPlan.loaderRequirement, loaderPreconditions)
            if (warnings.isNotEmpty()) {
                Text("المتطلبات والتنبيهات", style = MaterialTheme.typography.titleMedium)
                warnings.forEach { pre ->
                    StatusNote(
                        "${customerPreconditionMessage(pre.code)}${modPreconditionDetail(pre)?.let {
                            " — $it"
                        } ?: ""}",
                        if (pre.satisfied) Icons.Default.CheckCircle else Icons.Default.Warning,
                        if (pre.satisfied) MaterialTheme.colorScheme.tertiary else warningColor()
                    )
                }
            }
            analysis.compatibility.reasons
                .distinct()
                .filterNot(displayedWarnings::contains)
                .forEach { _ ->
                    StatusNote("لا يمكن متابعة التثبيت بهذه المتطلبات.", Icons.Default.Warning, warningColor())
                }
            OutlinedButton(onClick = onCopyDiagnostics) {
                Text("نسخ معلومات الفحص")
            }
        }
    }
}

private fun isLoaderPrecondition(precondition: ModInstallPrecondition): Boolean {
    val code = precondition.code.uppercase(Locale.ROOT)
    return code.contains("LOADER") ||
        Regex("(?i)\\b(loader|modloader|محمّل|محمل)\\b")
            .containsMatchIn(precondition.message)
}

private fun shortSerial(serial: String?): String {
    val value = serial?.trim().orEmpty()
    return when {
        value.isBlank() -> "السيريال غير متاح"
        value.length <= 12 -> value
        else -> "…${value.takeLast(10)}"
    }
}

@Composable
private fun LoaderRequirementDetails(
    requirement: ModLoaderRequirement?,
    legacyPreconditions: List<ModInstallPrecondition>
) {
    Text("متطلبات المحمّل وحالته", style = MaterialTheme.typography.titleMedium)
    if (requirement == null) {
        if (legacyPreconditions.isEmpty()) {
            StatusNote(
                "لم تُعلن الخطة متطلب محمّل مودات.",
                Icons.Default.Info,
                MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            legacyPreconditions.forEach { precondition ->
                StatusNote(
                    customerPreconditionMessage(precondition.code),
                    if (precondition.satisfied) Icons.Default.CheckCircle else Icons.Default.Warning,
                    if (precondition.satisfied) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        warningColor()
                    }
                )
            }
        }
        return
    }

    val requested = requirement.requested
        .sortedBy { it.displayName }
        .joinToString(" أو ") { it.displayName }
        .ifBlank { "غير محدد" }
    val (statusText, statusIcon, tint) = when (requirement.status) {
        ModLoaderStatus.DETECTED -> Triple(
            "تم الاكتشاف",
            Icons.Default.CheckCircle,
            MaterialTheme.colorScheme.tertiary
        )
        ModLoaderStatus.NOT_DETECTED -> Triple(
            "غير مكتشف",
            Icons.Default.Warning,
            warningColor()
        )
        ModLoaderStatus.UNKNOWN -> Triple(
            "غير معروف",
            Icons.Default.Info,
            warningColor()
        )
    }
    StatusNote("المطلوب: $requested", Icons.Default.Info, tint)
    StatusNote("الحالة: $statusText", statusIcon, tint)

    StatusNote(
        customerLoaderRequirementMessage(requirement),
        Icons.Default.Info,
        tint
    )

    val evidence = requirement.detection
        ?.let { detection ->
            requirement.requested
                .sortedBy { it.displayName }
                .flatMap { loader ->
                    detection.evidence[loader]?.evidence.orEmpty().map { detail ->
                        "${loader.displayName}: ${sanitizeModsUiText(detail)}"
                    }
                }
                .filter(String::isNotBlank)
                .distinct()
        }
        .orEmpty()
    if (evidence.isNotEmpty()) {
        Text("الدليل المقروء", style = MaterialTheme.typography.labelMedium)
        StatusNote(
            "تم التحقق من ${evidence.size} ${if (evidence.size == 1) "إشارة" else "إشارات"} محلية لحالة المحمّل. التفاصيل التقنية متاحة في التشخيصات.",
            Icons.Default.Info,
            tint
        )
    } else if (requirement.detection != null) {
        StatusNote(
            "تم فحص حالة المحمّل بأدلة قراءة فقط؛ لم يتوفر تفصيل إضافي.",
            Icons.Default.Info,
            tint
        )
    }
}

@Composable private fun InfoGrid(analysis: ModPackageAnalysis) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            InfoCell("النتيجة", displayModOutcome(analysis.outcome))
            InfoCell("النوع", displayModPackageType(analysis.packageType))
            InfoCell("الهدف", analysis.installPlan.targetPackageId ?: "غير محدد")
            InfoCell("الملفات", "${analysis.installPlan.totalFiles}")
            InfoCell("الحجم", bytes(analysis.installPlan.totalBytes))
        }
        analysis.installPlan.destinationRoot?.let { destination ->
            Column {
                Text(
                    "جذر الوجهة",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(destination, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            "التحقق: ${if (analysis.installPlan.mappings.isNotEmpty()) {
                "وجود الوجهة والملفات ومطابقة الحجم"
            } else {
                "لا توجد ملفات قابلة للتحقق"
            }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (analysis.installPlan.dependencies.isNotEmpty()) {
            Text(
                "الاعتماديات: " + analysis.installPlan.dependencies.joinToString { dependency ->
                    dependency.id + (dependency.version?.let { " ($it)" } ?: "")
                },
                style = MaterialTheme.typography.bodySmall,
                color = warningColor()
            )
        }
    }
}

@Composable private fun InfoCell(label: String, value: String) {
    Column(Modifier.width(120.dp)) { Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable private fun ProgressCard(progress: ModsManager.ModExecutionProgress?) {
    val complete = progress?.phase == ModsManager.ModInstallPhase.COMPLETED
    val failed = progress?.phase == ModsManager.ModInstallPhase.FAILED
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when {
                complete -> MaterialTheme.colorScheme.tertiaryContainer
                failed -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.primaryContainer
            }
        )
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (complete) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.tertiary)
                else if (failed) Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                else CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    sanitizeModsUiText(progress?.message ?: "جارٍ تجهيز التثبيت…"),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
            }
            val fraction = progress?.fraction
            if (!failed) {
                if (fraction == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator({ fraction.toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                    Text(
                        "${(fraction * 100).toInt()}% — ${modProgressKindLabel(progress?.kind)} / ${modInstallPhaseLabel(progress?.phase)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable private fun LogCard(logText: String) {
    var expanded by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("سجل العملية", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { expanded = !expanded }) { Text(if (expanded) "إخفاء" else "عرض السجل") }
            }
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                SelectionContainer { Text(logText.ifBlank { "لا توجد أحداث بعد. سيظهر السجل هنا أثناء التحليل والتثبيت." }, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable private fun StatusPill(text: String, good: Boolean) {
    Surface(shape = RoundedCornerShape(50), color = if (good) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(if (good) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline))
            Spacer(Modifier.width(7.dp)); Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable private fun TypeTag(text: String) {
    Surface(shape = RoundedCornerShape(7.dp), color = MaterialTheme.colorScheme.surfaceVariant) { Text(text, Modifier.padding(horizontal = 9.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
}

@Composable private fun StatusNote(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) { Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable private fun EmptyState(title: String, message: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun LoadingApps() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) { Surface(Modifier.fillMaxWidth().height(60.dp), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {} }
        Text("جارٍ قراءة التطبيقات من النظارة…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun bytes(value: Long): String = when {
    value >= 1024L * 1024L -> "${value / (1024L * 1024L)} MB"
    value >= 1024L -> "${value / 1024L} KB"
    else -> "$value B"
}

private fun displayModPackageType(type: ModPackageType): String = when (type) {
    ModPackageType.BONELAB_NATIVE_CONTENT -> "BONELAB — محتوى أصلي"
    ModPackageType.BONELAB_CODE_MOD -> "BONELAB — مود برمجي"
    ModPackageType.QMOD -> "QMOD"
    ModPackageType.GORILLA_TAG_VIRTUAL_STUMP -> "Virtual Stump — محتوى مدمج"
    ModPackageType.PAVLOV_UGC_CONTENT -> "Pavlov — محتوى mod.io مدمج"
    ModPackageType.NFVR_MANIFEST -> "NFVR manifest"
    ModPackageType.ANDROID_DATA_LAYOUT -> "Android/data"
    ModPackageType.ANDROID_OBB_LAYOUT -> "Android/obb"
    ModPackageType.KNOWN_GAME_PROFILE -> "ملف تعريف لعبة معروف"
    ModPackageType.GENERIC_DATA -> "بيانات Android"
    ModPackageType.UNKNOWN -> "غير معروف"
}

private fun modProgressKindLabel(kind: ModProgressKind?): String = when (kind) {
    ModProgressKind.SCAN -> "فحص"
    ModProgressKind.ANALYZE -> "تحليل"
    ModProgressKind.INSTALL -> "تثبيت"
    ModProgressKind.EXTERNAL -> "إجراء مدعوم"
    null -> "تثبيت"
}

private fun modInstallPhaseLabel(phase: ModsManager.ModInstallPhase?): String = when (phase) {
    ModsManager.ModInstallPhase.ANALYZING -> "تحليل الحزمة"
    ModsManager.ModInstallPhase.EXTRACTING -> "استخراج آمن"
    ModsManager.ModInstallPhase.VALIDATING -> "تحقق محلي"
    ModsManager.ModInstallPhase.PREPARING -> "تجهيز الوجهة"
    ModsManager.ModInstallPhase.TRANSFERRING -> "نقل الملفات"
    ModsManager.ModInstallPhase.VERIFYING -> "تحقق بعيد"
    ModsManager.ModInstallPhase.COMPLETED -> "اكتمل"
    ModsManager.ModInstallPhase.FAILED -> "فشل"
    null -> "جارٍ التنفيذ"
}

private fun displayModOutcome(outcome: ModInstallOutcome): String = when (outcome) {
    ModInstallOutcome.DIRECT_INSTALL_READY -> "جاهز للتثبيت"
    ModInstallOutcome.REQUIRES_MOD_LOADER -> "يتطلب محمّل مودات"
    ModInstallOutcome.APK_PATCH_REQUIRED -> "يتطلب تجهيز APK بمحمّل متوافق"
    ModInstallOutcome.BUILT_IN_GAME_CONTENT -> "محتوى مخصص مدمج في اللعبة"
    ModInstallOutcome.UNSUPPORTED -> "غير مدعوم"
    ModInstallOutcome.UNSAFE_ARCHIVE -> "أرشيف غير آمن"
}

private fun warningColor(): Color = Color(0xFFFFB547)

@Composable
private fun outcomeColor(tone: ModsUiStatusTone): Color = when (tone) {
    ModsUiStatusTone.SUCCESS -> MaterialTheme.colorScheme.tertiary
    ModsUiStatusTone.INFO -> Color(0xFF3B9DB3)
    ModsUiStatusTone.WARNING -> warningColor()
    ModsUiStatusTone.ERROR -> MaterialTheme.colorScheme.error
}
