import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.SwingUtilities

@Composable
fun PcvrReadinessTab() {
    var checks by remember { mutableStateOf<List<PcvrCheckResult>>(emptyList()) }
    var systemInfo by remember { mutableStateOf<PcvrSystemInfo?>(null) }
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("اضغط بدء الفحص للتحقق من جاهزية الكمبيوتر لـ PCVR.") }
    val scope = rememberCoroutineScope()

    fun runChecks(advanced: Boolean) {
        running = true
        message = "جاري فحص الكمبيوتر..."
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (advanced) PcvrChecker.runAdvancedChecks() else PcvrChecker.runQuickChecks()
            }.onSuccess { (newChecks, newSystemInfo) ->
                SwingUtilities.invokeLater {
                    checks = newChecks
                    systemInfo = newSystemInfo
                    message = if (PcvrChecker.isReady(newChecks)) {
                        "الكمبيوتر جاهز مبدئيًا لتشغيل PCVR."
                    } else {
                        "اكتمل الفحص. راجع العناصر التي تحتاج إلى معالجة."
                    }
                    running = false
                }
            }.onFailure {
                DiagnosticLogger.error("فشل فحص جاهزية PCVR", it)
                SwingUtilities.invokeLater {
                    message = "تعذر إكمال فحص PCVR."
                    running = false
                }
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("فحص جاهزية PCVR", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(message)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !running, onClick = { runChecks(false) }) {
                        Text("بدء الفحص السريع")
                    }
                    OutlinedButton(enabled = !running, onClick = { runChecks(true) }) {
                        Text("فحص متقدم")
                    }
                    OutlinedButton(
                        enabled = !running && checks.isNotEmpty() && systemInfo != null,
                        onClick = {
                            val report = PcvrChecker.generateReport(checks, systemInfo!!)
                            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(report), null)
                            message = "تم نسخ تقرير PCVR."
                        }
                    ) {
                        Text("نسخ التقرير")
                    }
                }
                if (running) CircularProgressIndicator()
            }
        }

        checks.forEach { check ->
            val background = when (check.status) {
                PcvrCheckStatus.PASS -> Color(0xFFE8F5E8)
                PcvrCheckStatus.WARN -> Color(0xFFFFF8E1)
                PcvrCheckStatus.FAIL -> Color(0xFFFFEBEE)
                PcvrCheckStatus.UNKNOWN -> Color(0xFFF3F4F6)
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.fillMaxWidth().background(background).padding(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(check.title, fontWeight = FontWeight.SemiBold)
                        Text(check.explanation, style = MaterialTheme.typography.bodySmall)
                        Text("الإجراء المقترح: ${check.solution}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}