import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.SwingUtilities

@Composable
fun PcvrReadinessTab(
    state: PcvrReadinessState,
    onRunChecks: (Boolean) -> Unit
) {
    val checks = state.checks
    val systemInfo = state.systemInfo
    val running = state.running

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "فحص جاهزية PCVR",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "تحقق من متطلبات Quest Link قبل تشغيل الألعاب.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "المتطلبات الرسمية من Meta: $META_PCVR_REQUIREMENTS_URL",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (checks.isNotEmpty()) {
                            StatusBadge(
                                label = when {
                                    checks.any { it.status == PcvrCheckStatus.FAIL } -> "يحتاج إجراء"
                                    PcvrChecker.isReady(checks) -> "جاهز مبدئيًا"
                                    checks.any { it.status == PcvrCheckStatus.UNKNOWN } -> "غير مكتمل"
                                    checks.any { it.status == PcvrCheckStatus.WARN } -> "تحذيرات"
                                    else -> "جاهز مبدئيًا"
                                },
                                color = overallColor(checks)
                            )
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (running) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(
                                            if (checks.isEmpty()) MaterialTheme.colorScheme.outline
                                            else overallColor(checks),
                                            MaterialTheme.shapes.extraSmall
                                        )
                                )
                            }
                            Text(
                                state.message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (running) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(enabled = !running, onClick = { onRunChecks(false) }) {
                            Text("بدء الفحص السريع")
                        }
                        OutlinedButton(enabled = !running, onClick = { onRunChecks(true) }) {
                            Text("فحص متقدم")
                        }
                        OutlinedButton(
                            enabled = !running && checks.isNotEmpty() && systemInfo != null,
                            onClick = {
                                val report = PcvrChecker.generateReport(checks, systemInfo!!)
                                Toolkit.getDefaultToolkit().systemClipboard
                                    .setContents(StringSelection(report), null)
                            }
                        ) {
                            Text("نسخ التقرير")
                        }
                    }
                }
            }

            systemInfo?.let { info ->
                SystemSummary(info)
            }

            if (checks.isNotEmpty()) {
                Text(
                    "نتائج الفحص  •  ${checks.size} عناصر",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            checks.forEach { check ->
                CheckResultCard(check)
            }
        }
    }
}

@Composable
private fun StatusBadge(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.16f),
        contentColor = color,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun overallColor(checks: List<PcvrCheckResult>): Color =
    when {
        checks.any { it.status == PcvrCheckStatus.FAIL } -> Color(0xFFFF7777)
        PcvrChecker.isReady(checks) -> Color(0xFF72D39A)
        checks.any { it.status == PcvrCheckStatus.WARN } -> Color(0xFFFFC857)
        checks.any { it.status == PcvrCheckStatus.UNKNOWN } -> Color(0xFFB8C4D0)
        else -> Color(0xFF72D39A)
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SystemSummary(info: PcvrSystemInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "بيانات الجهاز المكتشفة",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                InfoPill("النظام", info.windowsVersion)
                InfoPill(
                    "المعالج",
                    "${if (info.cpuCores > 0) "${info.cpuCores} نواة" else "الأنوية غير معروفة"} • " +
                        "${if (info.cpuLogicalProcessors > 0) "${info.cpuLogicalProcessors} منطقية" else "المنطقية غير معروفة"} • " +
                        info.cpuName
                )
                InfoPill(
                    "الذاكرة",
                    if (info.totalRamGb > 0.0) "${"%.1f".format(info.totalRamGb)} GB" else "غير معروفة"
                )
                InfoPill("البطاقة", info.gpuName)
                if (info.gpuVramKnown) InfoPill("ذاكرة الرسوميات", "${"%.1f".format(info.gpuVramGb)} GB")
                InfoPill("Quest Link", if (info.questLinkInstalled) "مثبّت" else "غير مثبّت")
                InfoPill("SteamVR", if (info.steamvrInstalled) "مثبّت" else "غير مثبّت")
            }
        }
    }
}

@Composable
private fun InfoPill(label: String, value: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.border(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
            MaterialTheme.shapes.small
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                value.ifBlank { "غير معروف" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun CheckResultCard(check: PcvrCheckResult) {
    val accent = when (check.status) {
        PcvrCheckStatus.PASS -> Color(0xFF72D39A)
        PcvrCheckStatus.WARN -> Color(0xFFFFC857)
        PcvrCheckStatus.FAIL -> Color(0xFFFF7777)
        PcvrCheckStatus.UNKNOWN -> Color(0xFFB8C4D0)
    }
    val statusLabel = when (check.status) {
        PcvrCheckStatus.PASS -> "PASS • مستوفى"
        PcvrCheckStatus.WARN -> "WARN • يحتاج انتباه"
        PcvrCheckStatus.FAIL -> "FAIL • غير مستوفى"
        PcvrCheckStatus.UNKNOWN -> "UNKNOWN • تعذر التحقق"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(accent)
            )
            Column(
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        check.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    StatusBadge(statusLabel, accent)
                }
                Text(
                    check.explanation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (check.details.isNotBlank()) {
                    Text(
                        "القيمة المكتشفة: ${check.details}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (check.detected.isNotBlank() || check.minimum.isNotBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "المكتشف: ${check.detected.ifBlank { "غير معروف" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "الحد الأدنى: ${check.minimum.ifBlank { "غير معروف" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    "التوصية: ${check.solution}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}