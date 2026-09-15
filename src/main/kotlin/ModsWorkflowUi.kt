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
import androidx.compose.ui.platform.LocalLayoutDirection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ModsWorkflowUi(
    connected: Boolean,
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
    onOpenExternalUrl: (String) -> Unit = {},
    logText: String,
    modifier: Modifier = Modifier
) {
    val filteredApps = remember(installedApps, searchFilter) {
        val query = searchFilter.trim().lowercase()
        installedApps.filter {
            query.isBlank() ||
                (it.displayName ?: "").lowercase().contains(query) ||
                it.packageName.lowercase().contains(query)
        }
    }
    var appPickerRequested by remember(selectedApp) { mutableStateOf(selectedApp == null) }
    val currentStep = when {
        selectedApp == null -> 1
        selectedZipFilename.isNullOrBlank() -> 2
        analysis == null -> 3
        analysis.isExternalWorkflow || installing || executionProgress != null -> 5
        else -> 4
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item { Header(connected, scanning, onRefresh, onCancelScan) }
                if (scanning || scanProgress != null) {
                    item { ScanStatus(scanProgress, scanning) }
                }
                item { WorkflowRail(connected, selectedApp != null, !selectedZipFilename.isNullOrBlank(), analysis != null, installing || executionProgress?.phase == ModsManager.ModInstallPhase.COMPLETED) }
                item { StepTitle("01", "اختر اللعبة من النظارة", "قائمة الألعاب المثبتة من Quest — بدون إدخال مسارات يدوية") }
                if (selectedApp == null || appPickerRequested) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                SearchBox(searchFilter, onSearchFilterChange)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = showAllApps, onCheckedChange = onShowAllAppsChange)
                                    Text("إظهار تطبيقات النظام والخدمات الداخلية", style = MaterialTheme.typography.labelMedium)
                                }
                                when {
                                    !connected && filteredApps.isEmpty() -> EmptyState("النظارة غير متصلة", "اشبك Quest ووافق على USB Debugging، ثم حدّث الاتصال.", Icons.Default.Info)
                                    scanning && filteredApps.isEmpty() -> LoadingApps()
                                    filteredApps.isEmpty() -> EmptyState("لا توجد ألعاب مطابقة", "جرّب كلمة بحث أخرى أو حدّث قائمة التطبيقات.", Icons.Default.Search)
                                    else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        filteredApps.forEach { app ->
                                            AppRow(app, app == selectedApp) {
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
                } else {
                    item {
                        SelectedAppCard(
                            selectedApp,
                            onChange = {
                                appPickerRequested = true
                                onChangeSelectedApp()
                            },
                            onClear = {
                                appPickerRequested = true
                                onClearSelectedApp()
                            }
                        )
                    }
                }
                item { StepTitle("02", "اختر حزمة المود", "يتم فحص ملف ZIP آمنًا قبل لمس أي ملف على النظارة") }
                if (currentStep == 2) item { ZipCard(selectedZipFilename, onChooseFile, selectedApp) }
                else if (!selectedZipFilename.isNullOrBlank()) item { CompletedSummaryCard("حزمة المود", selectedZipFilename) }
                item { StepTitle("03", "حلّل الحزمة", "تحقق من النوع والتوافق قبل إعداد خطة النقل") }
                if (currentStep == 3) item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = onAnalyze,
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
                else if (analysis != null) item { CompletedSummaryCard("التحليل", if (analysis.isExternalWorkflow) "مسار خارجي مدعوم" else "اكتمل فحص الحزمة") }
                item { StepTitle("04", "راجع خطة التثبيت", "لا يبدأ النقل إلا بعد فحص النوع والتوافق والوجهات") }
                if (currentStep == 4 && analysis != null) {
                    item { AnalysisCard(analysis) }
                    item {
                        Button(
                            onClick = onInstall,
                            enabled = analysis.installable && analysis.compatibility.compatible && !analysis.installPlan.hasBlockingPreconditions && !installing,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary)
                        ) {
                            Icon(Icons.Default.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text("اعتماد الخطة وبدء التثبيت")
                        }
                    }
                }
                item { StepTitle("05", "الإجراء", "تثبيت متحقق أو فتح مسار خارجي في المتصفح") }
                if (currentStep == 5 && analysis != null) item {
                    if (analysis.isExternalWorkflow) {
                        ExternalWorkflowCard(analysis, onOpenExternalUrl)
                    } else {
                        Button(
                            onClick = onInstall,
                            enabled = analysis.installable && analysis.compatibility.compatible && !analysis.installPlan.hasBlockingPreconditions && !installing,
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
                item { LogCard(logText) }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable private fun Header(
    connected: Boolean,
    scanning: Boolean,
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
            OutlinedButton(onClick = cancelScan) { Text("إلغاء الفحص") }
        }
        IconButton(onClick = refresh, enabled = !scanning) { Icon(Icons.Default.Refresh, "تحديث") }
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

@Composable private fun SearchBox(value: String, change: (String) -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            BasicTextField(value, change, Modifier.weight(1f), textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), singleLine = true, decorationBox = { inner ->
                if (value.isBlank()) Text("ابحث باسم اللعبة أو package id", color = MaterialTheme.colorScheme.onSurfaceVariant)
                inner()
            })
        }
    }
}

@Composable private fun AppRow(app: InstalledQuestApp, selected: Boolean, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null) {
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
            OutlinedButton(onClick = onChange) { Text("تغيير") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = onClear) { Text("مسح") }
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
    selectedApp: InstalledQuestApp?
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
                OutlinedButton(onClick = onChooseFile) { Text("اختيار ملف") }
            }
            if (selectedApp != null) StatusNote("الهدف المحدد: ${selectedApp.displayName ?: selectedApp.packageName}", Icons.Default.CheckCircle, MaterialTheme.colorScheme.tertiary)
        }
    }
}

@Composable private fun ExternalWorkflowCard(
    analysis: ModPackageAnalysis,
    onOpenUrl: (String) -> Unit
) {
    val workflow = analysis.externalWorkflow ?: return
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE0F7FA)),
        border = BorderStroke(1.dp, Color(0xFF26A6C9))
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, null, tint = Color(0xFF087EA4))
                Spacer(Modifier.width(10.dp))
                Text("مسار خارجي مدعوم — Gorilla Tag / mod.io", style = MaterialTheme.typography.titleMedium, color = Color(0xFF075B75))
            }
            Text(workflow.guidance, color = Color(0xFF075B75))
            OutlinedButton(onClick = { onOpenUrl(workflow.actionUrl) }) {
                Text("فتح صفحة mod.io في المتصفح")
            }
        }
    }
}

@Composable private fun AnalysisCard(analysis: ModPackageAnalysis) {
    val compatible = analysis.compatibility.compatible
    val warnings = deduplicateModWarnings(analysis.installPlan.preconditions)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), border = BorderStroke(1.dp, if (compatible) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (compatible) Icons.Default.CheckCircle else Icons.Default.Warning, null, tint = if (compatible) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (analysis.recognized) "حزمة معروفة وقابلة للفحص" else "تعذر التعرف على الحزمة", style = MaterialTheme.typography.titleMedium)
                    Text(analysis.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TypeTag(analysis.packageType.name)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            InfoGrid(analysis)
            if (analysis.installPlan.mappings.isNotEmpty()) {
                Text("خطة الملفات والوجهات", style = MaterialTheme.typography.titleMedium)
                analysis.installPlan.mappings.forEach { mapping ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(mapping.sourcePath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("← ${mapping.destinationPath}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (warnings.isNotEmpty()) {
                Text("المتطلبات والتنبيهات", style = MaterialTheme.typography.titleMedium)
                warnings.forEach { pre -> StatusNote(pre.message, if (pre.satisfied) Icons.Default.CheckCircle else Icons.Default.Warning, if (pre.satisfied) MaterialTheme.colorScheme.tertiary else Color(0xFFFFC266)) }
            }
            val displayedWarnings = warnings.map { it.message }.toSet()
            analysis.compatibility.reasons
                .distinct()
                .filterNot(displayedWarnings::contains)
                .forEach { reason -> StatusNote(reason, Icons.Default.Warning, Color(0xFFFFC266)) }
        }
    }
}

@Composable private fun InfoGrid(analysis: ModPackageAnalysis) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        InfoCell("النوع", analysis.packageType.name)
        InfoCell("الهدف", analysis.installPlan.targetPackageId ?: "غير محدد")
        InfoCell("الملفات", "${analysis.installPlan.totalFiles}")
        InfoCell("الحجم", bytes(analysis.installPlan.totalBytes))
    }
}

@Composable private fun InfoCell(label: String, value: String) {
    Column(Modifier.width(120.dp)) { Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable private fun ProgressCard(progress: ModsManager.ModExecutionProgress?) {
    val complete = progress?.phase == ModsManager.ModInstallPhase.COMPLETED
    Card(colors = CardDefaults.cardColors(containerColor = if (complete) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (complete) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.tertiary)
                else CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(progress?.message ?: "جارٍ تجهيز التثبيت…", style = MaterialTheme.typography.titleMedium)
            }
            val fraction = progress?.fraction
            if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else {
                LinearProgressIndicator({ fraction.toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                Text("${(fraction * 100).toInt()}% — ${progress?.kind?.name ?: "INSTALL"} / ${progress?.phase?.name ?: "PROCESSING"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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