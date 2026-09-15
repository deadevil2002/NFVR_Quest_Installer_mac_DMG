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
import java.text.SimpleDateFormat
import java.util.Date
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
    val action: String
)

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
            "محتوى مخصص مدمج في اللعبة",
            "يُدار بواسطة نظام المحتوى المخصص المدمج في اللعبة"
        )
        requiresLoader -> ModsUiOutcome(
            ModsUiStatusTone.WARNING,
            "يتطلب محمّل مودات",
            "جهّز محمّل المودات المطلوب ثم أعد التحليل"
        )
        analysis.outcome == ModInstallOutcome.DIRECT_INSTALL_READY -> ModsUiOutcome(
            ModsUiStatusTone.SUCCESS,
            "جاهز للتثبيت",
            "استخراج → نقل → تحقق"
        )
        analysis.outcome == ModInstallOutcome.UNSUPPORTED ||
            analysis.packageType == ModPackageType.UNKNOWN ||
            !analysis.recognized -> ModsUiOutcome(
            ModsUiStatusTone.WARNING,
            "غير مدعوم حاليًا",
            "لا توجد وجهة آمنة معروفة — لم يتم نقل أي ملف"
        )
        else -> ModsUiOutcome(
            ModsUiStatusTone.WARNING,
            "تحتاج الخطة إلى متطلبات",
            "لا يمكن التثبيت بهذه الخطة"
        )
    }
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
    onChooseFile: () -> Unit,
    analyzing: Boolean,
    analysis: ModPackageAnalysis?,
    onAnalyze: () -> Unit,
    installing: Boolean,
    executionProgress: ModsManager.ModExecutionProgress?,
    onInstall: () -> Unit,
    // Kept for source compatibility with the host screen.  It is intentionally
    // not invoked; built-in content is informational in this UI.
    onOpenExternalUrl: (String) -> Unit = {},
    logText: String,
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
    val filteredApps = remember(installedApps, searchFilter) {
        val query = searchFilter.trim().lowercase()
        installedApps.filter {
            query.isBlank() ||
                (it.displayName ?: "").lowercase().contains(query) ||
                it.packageName.lowercase().contains(query)
        }
    }
    val editingEnabled = modsUiControlsEnabled(installing) && !pickerOpen
    var appPickerRequested by remember(selectedApp) { mutableStateOf(selectedApp == null) }
    val currentStep = when {
        selectedApp == null -> 1
        selectedZipFilename.isNullOrBlank() -> 2
        analysis == null -> 3
        analysis.isBuiltInGameContent || analysis.isExternalWorkflow ||
            installing || executionProgress != null -> 5
        else -> 4
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp),
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
                item { WorkflowRail(connected, selectedApp != null, !selectedZipFilename.isNullOrBlank(), analysis != null, installing || executionProgress?.phase == ModsManager.ModInstallPhase.COMPLETED) }
                item { StepTitle("01", "اختر اللعبة من النظارة", "قائمة الألعاب المثبتة من Quest — بدون إدخال مسارات يدوية") }
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
                                    Text("إظهار تطبيقات النظام والخدمات الداخلية", style = MaterialTheme.typography.labelMedium)
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
                item { StepTitle("02", "اختر حزمة المود", "يتم فحص ملف ZIP آمنًا قبل لمس أي ملف على النظارة") }
                if (currentStep == 2) {
                    item {
                        ZipCard(
                            selectedZipFilename,
                            onChooseFile = {
                                clearFocusBeforeModsTransition(
                                    { focusManager.clearFocus(force = true) },
                                    onChooseFile
                                )
                            },
                            selectedApp = selectedApp,
                            enabled = editingEnabled
                        )
                    }
                }
                else if (!selectedZipFilename.isNullOrBlank()) item { CompletedSummaryCard("حزمة المود", selectedZipFilename) }
                item { StepTitle("03", "حلّل الحزمة", "تحقق من النوع والتوافق قبل إعداد خطة النقل") }
                if (currentStep == 3) item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                clearFocusBeforeModsTransition(
                                    { focusManager.clearFocus(force = true) },
                                    onAnalyze
                                )
                            },
                            enabled = connected && selectedApp != null && !selectedZipFilename.isNullOrBlank() && !analyzing && !installing,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            if (analyzing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Search, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (analyzing) "جارٍ تحليل الحزمة…" else "تحليل الحزمة")
                        }
                        if (analysis != null) Text("التحليل مكتمل", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelLarge)
                    }
                }
                else if (analysis != null) item {
                    CompletedSummaryCard(
                        "التحليل",
                        if (analysis.isBuiltInGameContent || analysis.isExternalWorkflow) {
                            "محتوى تديره اللعبة"
                        } else {
                            "اكتمل فحص الحزمة"
                        }
                    )
                }
                item { StepTitle("04", "راجع خطة التثبيت", "لا يبدأ النقل إلا بعد فحص النوع والتوافق والوجهات") }
                if (currentStep == 4 && analysis != null) {
                    item { AnalysisCard(analysis) }
                    item {
                        Button(
                            onClick = {
                                clearFocusBeforeModsTransition(
                                    { focusManager.clearFocus(force = true) },
                                    onInstall
                                )
                            },
                            enabled = analysis.outcome == ModInstallOutcome.DIRECT_INSTALL_READY &&
                                analysis.installable &&
                                analysis.compatibility.compatible &&
                                !analysis.installPlan.hasBlockingPreconditions &&
                                !installing,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary)
                        ) {
                            Icon(Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text("اعتماد الخطة وبدء التثبيت")
                        }
                    }
                }
                item { StepTitle("05", "الإجراء", "تثبيت متحقق أو محتوى تديره اللعبة") }
                if (currentStep == 5 && analysis != null) item {
                    if (analysis.isBuiltInGameContent || analysis.isExternalWorkflow) {
                        BuiltInContentCard(analysis)
                    } else {
                        Button(
                            onClick = {
                                clearFocusBeforeModsTransition(
                                    { focusManager.clearFocus(force = true) },
                                    onInstall
                                )
                            },
                            enabled = analysis.outcome == ModInstallOutcome.DIRECT_INSTALL_READY &&
                                analysis.installable &&
                                analysis.compatibility.compatible &&
                                !analysis.installPlan.hasBlockingPreconditions &&
                                !installing,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary)
                        ) {
                            if (installing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (installing) "جارٍ التثبيت والتحقق…" else "تثبيت المود بأمان")
                        }
                    }
                }
                if (installing || executionProgress != null) item { ProgressCard(executionProgress) }
                item { LogCard(sanitizeModsLogText(logText)) }
                item { Spacer(Modifier.height(20.dp)) }
            }
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
            Text("تركيب المودات بثقة", style = MaterialTheme.typography.displaySmall)
            Text(if (connected) "النظارة جاهزة للعمل. اختر لعبة للبدء." else "اتصل بنظارة Quest لفتح سير العمل.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            if (value != null) {
                val total = value.total
                val count = value.apps.size
                Text(
                    if (total == null) "تمت معالجة ${value.processed} تطبيقًا — المكتشف: $count"
                    else "${value.processed} / $total — المكتشف: $count" +
                        (value.percent?.let { " — $it%" } ?: ""),
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    "آخر تحديث: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(value.lastUpdatedMillis))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

@Composable private fun WorkflowRail(connected: Boolean, app: Boolean, zip: Boolean, reviewed: Boolean, complete: Boolean) {
    val steps = listOf(
        "01 اللعبة" to app,
        "02 مود ZIP" to zip,
        "03 التحليل" to reviewed,
        "04 الخطة" to reviewed,
        "05 الإجراء" to complete
    )
    Row(Modifier.horizontalScroll(rememberScrollState()).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { index, pair ->
            val active = pair.second || (index == 0 && connected)
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(112.dp)) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(if (pair.second) MaterialTheme.colorScheme.tertiary else if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    if (pair.second) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onTertiary, modifier = Modifier.size(17.dp))
                    else Text("${index + 1}", color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                }
                Text(pair.first, style = MaterialTheme.typography.labelSmall, color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (index < steps.lastIndex) HorizontalDivider(Modifier.width(34.dp), color = MaterialTheme.colorScheme.outlineVariant)
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
                Text("${app.packageName} · ${app.versionName ?: "إصدار غير معروف"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    onChooseFile: () -> Unit,
    selectedApp: InstalledQuestApp?,
    enabled: Boolean
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f)) {
                    Text(filename ?: "لم يتم اختيار ملف ZIP", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (filename == null) "اختر حزمة مود من جهاز الكمبيوتر" else "الحزمة جاهزة للتحليل", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton(onClick = onChooseFile, enabled = enabled) { Text("اختيار ملف") }
            }
            if (selectedApp != null) StatusNote("الهدف المحدد: ${selectedApp.displayName ?: selectedApp.packageName}", Icons.Default.CheckCircle, MaterialTheme.colorScheme.tertiary)
        }
    }
}

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
                sanitizeModsUiText(analysis.message),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            StatusNote(outcome.action, Icons.Default.Info, tone)
            StatusNote(
                "لا توجد وجهة نقل مباشرة آمنة لهذا النوع؛ لم يتم نقل أي ملف.",
                Icons.Default.CheckCircle,
                tone
            )
        }
    }
}

@Composable private fun AnalysisCard(analysis: ModPackageAnalysis) {
    val outcome = modsUiOutcome(analysis)
    val tone = outcomeColor(outcome.tone)
    val allWarnings = deduplicateModWarnings(analysis.installPlan.preconditions)
    val loaderPreconditions = allWarnings.filter(::isLoaderPrecondition)
    val warnings = allWarnings.filterNot(::isLoaderPrecondition)
    val displayedWarnings = allWarnings.map { it.message }.toSet()
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
                        sanitizeModsUiText(analysis.message),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TypeTag(displayModPackageType(analysis.packageType))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            InfoGrid(analysis)
            StatusNote(outcome.action, Icons.Default.Info, tone)
            if (analysis.installPlan.mappings.isNotEmpty()) {
                Text("خطة الملفات والوجهات", style = MaterialTheme.typography.titleMedium)
                analysis.installPlan.mappings.forEach { mapping ->
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
            }
            LoaderRequirementDetails(analysis.installPlan.loaderRequirement, loaderPreconditions)
            if (warnings.isNotEmpty()) {
                Text("المتطلبات والتنبيهات", style = MaterialTheme.typography.titleMedium)
                warnings.forEach { pre ->
                    StatusNote(
                        sanitizeModsUiText(pre.message),
                        if (pre.satisfied) Icons.Default.CheckCircle else Icons.Default.Warning,
                        if (pre.satisfied) MaterialTheme.colorScheme.tertiary else warningColor()
                    )
                }
            }
            analysis.compatibility.reasons
                .distinct()
                .filterNot(displayedWarnings::contains)
                .forEach { reason ->
                    StatusNote(sanitizeModsUiText(reason), Icons.Default.Warning, warningColor())
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
                    sanitizeModsUiText(precondition.message),
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

    requirement.message
        .takeIf { it.isNotBlank() }
        ?.let { StatusNote(sanitizeModsUiText(it), Icons.Default.Info, tint) }

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
        evidence.forEach { detail ->
            StatusNote(detail, Icons.Default.Info, tint)
        }
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
                    Text("${(fraction * 100).toInt()}% — ${progress?.kind?.name ?: "INSTALL"} / ${progress?.phase?.name ?: "PROCESSING"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    ModPackageType.BONELAB_CODE_MOD -> "BONELAB — Code Mod"
    ModPackageType.QMOD -> "QMOD"
    ModPackageType.GORILLA_TAG_VIRTUAL_STUMP -> "Virtual Stump — محتوى مدمج"
    ModPackageType.NFVR_MANIFEST -> "NFVR manifest"
    ModPackageType.ANDROID_DATA_LAYOUT -> "Android/data"
    ModPackageType.ANDROID_OBB_LAYOUT -> "Android/obb"
    ModPackageType.KNOWN_GAME_PROFILE -> "ملف تعريف لعبة معروف"
    ModPackageType.GENERIC_DATA -> "بيانات Android"
    ModPackageType.UNKNOWN -> "غير معروف"
}

private fun displayModOutcome(outcome: ModInstallOutcome): String = when (outcome) {
    ModInstallOutcome.DIRECT_INSTALL_READY -> "جاهز للتثبيت"
    ModInstallOutcome.REQUIRES_MOD_LOADER -> "يتطلب محمّل مودات"
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
