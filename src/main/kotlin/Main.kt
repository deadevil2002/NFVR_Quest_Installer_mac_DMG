@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.text.DecimalFormat
import javax.swing.JFileChooser
import javax.swing.UIManager
import kotlin.system.exitProcess

enum class HostOs { WINDOWS, MAC, LINUX }

private data class GameEntry(
    val folder: File,
    val apk: File,
    val obbDirs: List<File>,
    val requiredBytes: Long
)

data class CmdResult(val exit: Int, val out: String, val err: String)

private fun detectOs(): HostOs {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("win") -> HostOs.WINDOWS
        os.contains("mac") -> HostOs.MAC
        else -> HostOs.LINUX
    }
}

private fun formatGb(gb: Double): String {
    val df = DecimalFormat("#,##0.0")
    return "${df.format(gb)} GB"
}

private fun bytesToGb(bytes: Long): Double =
    bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)

private fun dirSizeBytes(dir: File): Long {
    if (!dir.exists()) return 0
    var total = 0L
    dir.walkTopDown().forEach { f ->
        if (f.isFile) total += f.length()
    }
    return total
}

private fun safeUiMsg(e: Throwable): String {
    val msg = (e.message ?: "").lowercase()
    return when {
        msg.contains("no such file") || msg.contains("not found") -> "تعذر تشغيل ADB. تأكد أن البرنامج مكتمل ولم تُحذف ملفات منه."
        msg.contains("permission") -> "تعذر الوصول للصلاحيات. شغّل البرنامج كمسؤول إذا لزم."
        else -> "صار خطأ غير متوقع. جرّب إعادة تشغيل البرنامج وفصل/إعادة توصيل السلك."
    }
}

class BundledAdb(private val host: HostOs) {
    private var adbPath: File? = null

    fun ensureReady(): File {
        if (adbPath != null && adbPath!!.exists()) return adbPath!!

        val tempDir = Files.createTempDirectory("NFVR_ADB_").toFile()
        tempDir.deleteOnExit()

        when (host) {
            HostOs.WINDOWS -> {
                copyRes("/adb/win/adb.exe", File(tempDir, "adb.exe"))
                copyRes("/adb/win/AdbWinApi.dll", File(tempDir, "AdbWinApi.dll"))
                copyRes("/adb/win/AdbWinUsbApi.dll", File(tempDir, "AdbWinUsbApi.dll"))
                adbPath = File(tempDir, "adb.exe")
            }
            HostOs.MAC -> {
                copyRes("/adb/macos/adb", File(tempDir, "adb"))
                val adb = File(tempDir, "adb")
                adb.setExecutable(true)
                adbPath = adb
            }
            HostOs.LINUX -> error("نسخة لينكس غير مضافة حالياً.")
        }
        return adbPath!!
    }

    private fun copyRes(resPath: String, outFile: File) {
        val stream = javaClass.getResourceAsStream(resPath) ?: error("ملف ناقص داخل البرنامج.")
        outFile.outputStream().use { os -> stream.use { it.copyTo(os) } }
        outFile.deleteOnExit()
    }

    fun killServerSilently() {
        try {
            val adb = ensureReady()
            runProcess(listOf(adb.absolutePath, "kill-server"), workDir = adb.parentFile)
        } catch (_: Throwable) {}
    }
}

private fun runProcess(cmd: List<String>, workDir: File? = null, timeoutMs: Long = 120_000): CmdResult {
    val pb = ProcessBuilder(cmd)
    if (workDir != null) pb.directory(workDir)
    val p = pb.start()

    val out = StringBuilder()
    val err = StringBuilder()

    val tOut = Thread { p.inputStream.bufferedReader().useLines { it.forEach { line -> out.appendLine(line) } } }
    val tErr = Thread { p.errorStream.bufferedReader().useLines { it.forEach { line -> err.appendLine(line) } } }
    tOut.start(); tErr.start()

    val finished = p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    if (!finished) {
        p.destroyForcibly()
        return CmdResult(124, out.toString(), "Timeout")
    }
    tOut.join(); tErr.join()

    return CmdResult(p.exitValue(), out.toString(), err.toString())
}

class AdbClient(private val bundled: BundledAdb) {
    private fun adbBase(): Pair<File, File> {
        val adb = bundled.ensureReady()
        return adb to adb.parentFile
    }

    fun killServer() = bundled.killServerSilently()

    fun startServer(): CmdResult {
        val (adb, dir) = adbBase()
        return runProcess(listOf(adb.absolutePath, "start-server"), workDir = dir)
    }

    fun devices(): CmdResult {
        val (adb, dir) = adbBase()
        return runProcess(listOf(adb.absolutePath, "devices", "-l"), workDir = dir)
    }

    fun shell(serial: String, vararg args: String): CmdResult {
        val (adb, dir) = adbBase()
        return runProcess(listOf(adb.absolutePath, "-s", serial, "shell", *args), workDir = dir)
    }

    fun installApk(serial: String, apk: File): CmdResult {
        val (adb, dir) = adbBase()
        return runProcess(listOf(adb.absolutePath, "-s", serial, "install", "-r", apk.absolutePath), workDir = dir, timeoutMs = 20 * 60_000)
    }

    fun push(serial: String, from: File, toDevicePath: String): CmdResult {
        val (adb, dir) = adbBase()
        return runProcess(listOf(adb.absolutePath, "-s", serial, "push", from.absolutePath, toDevicePath), workDir = dir, timeoutMs = 30 * 60_000)
    }

    // ✅ Restart Quest
    fun reboot(serial: String): CmdResult {
        val (adb, dir) = adbBase()
        return runProcess(listOf(adb.absolutePath, "-s", serial, "reboot"), workDir = dir, timeoutMs = 30_000)
    }
}

private fun parseFirstDevice(devicesOutput: String): Pair<String, Boolean>? {
    val lines = devicesOutput.lines().map { it.trim() }.filter { it.isNotBlank() }
    val dataLines = lines.dropWhile { !it.startsWith("List of devices") }.drop(1)

    for (ln in dataLines) {
        val parts = ln.split(Regex("\\s+"))
        if (parts.isEmpty()) continue
        val serial = parts[0]
        val state = parts.getOrNull(1) ?: continue
        return when (state) {
            "device" -> serial to true
            "unauthorized" -> serial to false
            else -> null
        }
    }
    return null
}

private fun parseDfToGb(dfOutput: String): Pair<Double, Double>? {
    val lines = dfOutput.lines().map { it.trim() }.filter { it.isNotBlank() }
    if (lines.size < 2) return null
    val data = lines.last()
    val parts = data.split(Regex("\\s+"))
    if (parts.size < 5) return null
    val totalKb = parts.getOrNull(1)?.toLongOrNull() ?: return null
    val availKb = parts.getOrNull(3)?.toLongOrNull() ?: return null
    val totalGb = totalKb.toDouble() / (1024.0 * 1024.0)
    val freeGb = availKb.toDouble() / (1024.0 * 1024.0)
    return totalGb to freeGb
}

private fun scanGameFolder(folder: File): GameEntry? {
    if (!folder.exists() || !folder.isDirectory) return null
    val apks = folder.listFiles()?.filter { it.isFile && it.name.lowercase().endsWith(".apk") } ?: emptyList()
    if (apks.isEmpty()) return null
    val apk = apks.first()

    val dirs = folder.listFiles()?.filter { it.isDirectory } ?: emptyList()
    val obbDirs = dirs.filterNot {
        val n = it.name.lowercase()
        n == "usb_driver" || n == "drivers" || n.contains("platform-tools")
    }

    val required = apk.length() + obbDirs.sumOf { dirSizeBytes(it) }
    return GameEntry(folder = folder, apk = apk, obbDirs = obbDirs, requiredBytes = required)
}

private fun chooseFolder(): File? {
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    val fc = JFileChooser()
    fc.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    fc.isAcceptAllFileFilterUsed = false
    fc.dialogTitle = "اختر مجلد اللعبة"
    val result = fc.showOpenDialog(null)
    return if (result == JFileChooser.APPROVE_OPTION) fc.selectedFile else null
}

private fun hardExitApp() {
    try { java.awt.Window.getWindows().forEach { it.dispose() } } catch (_: Throwable) {}
    exitProcess(0)
}

fun main() = application {
    val host = detectOs()
    val bundledAdb = remember { BundledAdb(host) }
    val adb = remember { AdbClient(bundledAdb) }
    val modsManager = remember { ModsManager(adb) }

    // Tab Selection
    var selectedTab by remember { mutableStateOf(0) }

    // APK Installer State
    var connectionText by remember { mutableStateOf("الاتصال: جاري الفحص...") }
    var deviceText by remember { mutableStateOf("—") }
    var storageText by remember { mutableStateOf("—") }

    var statusText by remember { mutableStateOf("الحالة: جاهز") }
    var warningText by remember { mutableStateOf<String?>(null) }

    var folders by remember { mutableStateOf(listOf<File>()) }
    var queue by remember { mutableStateOf(listOf<GameEntry>()) }

    var isInstalling by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var progressLabel by remember { mutableStateOf("—") }

    var logText by remember { mutableStateOf("") }
    var showRestart by remember { mutableStateOf(false) }

    var resumeIndex by remember { mutableStateOf(0) }
    var pausedBecauseDisconnected by remember { mutableStateOf(false) }

    // Mods State
    var selectedGame by remember { mutableStateOf("") }
    var selectedGameInfo by remember { mutableStateOf<GameInfo?>(null) }
    var modZipFile by remember { mutableStateOf<File?>(null) }
    var isInstallingMod by remember { mutableStateOf(false) }
    var modProgress by remember { mutableStateOf(0f) }
    var modProgressLabel by remember { mutableStateOf("—") }
    var modLogText by remember { mutableStateOf("") }
    var installedGames by remember { mutableStateOf<List<String>>(emptyList()) }
    var manualPath by remember { mutableStateOf("") }
    var isManualMode by remember { mutableStateOf(false) }

    // ✅ Autoscroll states
    val logScroll = rememberScrollState()
    val modLogScroll = rememberScrollState()

    // Load logo
    var logoBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    
    LaunchedEffect(Unit) {
        try {
            val stream = javaClass.getResourceAsStream("/nfvr_logo.png")
            if (stream != null) {
                logoBitmap = loadImageBitmap(stream)
            }
        } catch (_: Exception) { }
    }

    fun appendLog(line: String) {
        logText += if (logText.isBlank()) line else "\n$line"
    }

    fun appendModLog(line: String) {
        modLogText += if (modLogText.isBlank()) line else "\n$line"
    }

    fun rebuildQueue() {
        queue = folders.mapNotNull { scanGameFolder(it) }
    }

    fun chooseModFile(): File? {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        val fc = JFileChooser()
        fc.fileSelectionMode = JFileChooser.FILES_ONLY
        fc.isAcceptAllFileFilterUsed = false
        fc.fileFilter = javax.swing.filechooser.FileNameExtensionFilter("ZIP Files", "zip")
        fc.dialogTitle = "اختر ملف المود (ZIP)"
        val result = fc.showOpenDialog(null)
        return if (result == JFileChooser.APPROVE_OPTION) fc.selectedFile else null
    }

    suspend fun getAuthorizedSerialOrNull(): String? {
        val dev = adb.devices()
        val parsed = parseFirstDevice(dev.out) ?: return null
        val (serial, authorized) = parsed
        return if (authorized) serial else null
    }

    suspend fun getFreeGb(serial: String): Double {
        val dfRes = adb.shell(serial, "df", "-k", "/data")
        val pair = parseDfToGb(dfRes.out) ?: return 0.0
        return pair.second
    }

    suspend fun refreshDeviceInfo() {
        try {
            adb.startServer()
            val dev = adb.devices()
            val parsed = parseFirstDevice(dev.out)

            if (parsed == null) {
                connectionText = "الاتصال: لا يوجد جهاز متصل"
                deviceText = "—"
                storageText = "—"
                pausedBecauseDisconnected = isInstalling
                if (isInstalling) statusText = "الحالة: توقف مؤقت — اشبك النظارة بالسلك"
                return
            }

            val (serial, authorized) = parsed
            if (!authorized) {
                connectionText = "الاتصال: تم العثور على جهاز — يحتاج موافقة داخل النظارة"
                deviceText = "السيريال: $serial"
                storageText = "—"
                statusText = if (isInstalling) "الحالة: توقف مؤقت — وافق على USB Debugging داخل النظارة"
                else "الحالة: وافق على USB Debugging داخل النظارة (إذا ظهرت)"
                return
            }

            connectionText = "الاتصال: تم العثور على جهاز"
            val modelRes = adb.shell(serial, "getprop", "ro.product.model")
            val model = modelRes.out.trim().ifBlank { "Meta Quest" }

            val dfRes = adb.shell(serial, "df", "-k", "/data")
            val (totalGb, freeGb) = parseDfToGb(dfRes.out) ?: (0.0 to 0.0)

            deviceText = "الجهاز: $model  |  السيريال: $serial"
            storageText = "المساحة: المتاح ${formatGb(freeGb)} من ${formatGb(totalGb)}"

            if (pausedBecauseDisconnected && isInstalling) {
                pausedBecauseDisconnected = false
                statusText = "الحالة: تم استرجاع الاتصال — جاهز للإكمال"
            } else if (!isInstalling) {
                statusText = "الحالة: جاهز"
            }
        } catch (e: Throwable) {
            connectionText = "الاتصال: ADB غير جاهز"
            deviceText = "—"
            storageText = "—"
            statusText = if (isInstalling) "الحالة: توقف مؤقت" else "الحالة: تعذر تشغيل ADB"
            warningText = safeUiMsg(e)
        }
    }

    suspend fun installQueue() {
        if (queue.isEmpty()) {
            warningText = "ما فيه ألعاب جاهزة للتثبيت. أضف مجلد لعبة يحتوي APK."
            return
        }

        isInstalling = true
        showRestart = false
        warningText = null
        progress = 0f
        progressLabel = "بدء التثبيت..."
        appendLog("==============================================")
        appendLog("بدء التثبيت — عدد الألعاب: ${queue.size}")
        appendLog("==============================================")

        try {
            adb.startServer()

            var i = resumeIndex
            while (i < queue.size) {
                val serial = getAuthorizedSerialOrNull()
                if (serial == null) {
                    pausedBecauseDisconnected = true
                    statusText = "الحالة: توقف مؤقت — اشبك النظارة/وافق داخل النظارة"
                    while (true) {
                        delay(1500)
                        if (getAuthorizedSerialOrNull() != null) break
                    }
                }

                val s2 = getAuthorizedSerialOrNull()!!
                val entry = queue[i]

                val freeGb = getFreeGb(s2)
                val requiredGb = bytesToGb(entry.requiredBytes)
                if (freeGb < requiredGb) {
                    warningText = "تنبيه: المساحة غير كافية لهذه اللعبة. المطلوب تقريبًا ${formatGb(requiredGb)} والمتاح ${formatGb(freeGb)}."
                    statusText = "الحالة: أفرغ مساحة ثم اضغط (تثبيت الكل) للإكمال"
                    appendLog("❗ مساحة غير كافية عند اللعبة: ${entry.folder.name}")
                    resumeIndex = i
                    isInstalling = false
                    progressLabel = "متوقف بسبب المساحة"
                    return
                }

                progressLabel = "تثبيت APK: ${entry.apk.name}"
                statusText = "الحالة: جاري تثبيت ${i + 1} / ${queue.size}"
                progress = (i.toFloat() / queue.size.toFloat()).coerceIn(0f, 1f)

                appendLog("----------------------------------------------")
                appendLog("لعبة: ${entry.folder.name}")
                appendLog("APK: ${entry.apk.name}")

                val installRes = adb.installApk(s2, entry.apk)
                appendLog(installRes.out.trim().ifBlank { "تثبيت APK: (بدون مخرجات)" })
                if (installRes.exit != 0) {
                    warningText = "فشل تثبيت APK للعبة الحالية. تأكد من اتصال النظارة وصلاحيات USB Debugging."
                    statusText = "الحالة: فشل"
                    resumeIndex = i
                    isInstalling = false
                    progressLabel = "فشل عند APK"
                    return
                }

                if (entry.obbDirs.isNotEmpty()) {
                    for (dir in entry.obbDirs) {
                        progressLabel = "نسخ ملفات OBB: ${dir.name}"
                        appendLog("نسخ OBB: ${dir.name}")

                        val target = "/sdcard/Android/obb/${dir.name}"
                        val pushRes = adb.push(s2, dir, target)
                        appendLog(pushRes.out.trim().ifBlank { "نسخ OBB: (بدون مخرجات)" })

                        if (pushRes.exit != 0) {
                            val combined = (pushRes.out + "\n" + pushRes.err).lowercase()
                            if (combined.contains("no space left")) {
                                warningText = "تنبيه: نفدت المساحة أثناء نسخ ملفات اللعبة. أفرغ مساحة ثم اضغط (تثبيت الكل) للإكمال."
                                statusText = "الحالة: متوقف بسبب المساحة"
                                resumeIndex = i
                                isInstalling = false
                                progressLabel = "متوقف بسبب المساحة"
                                return
                            }
                            warningText = "فشل نسخ ملفات OBB للعبة الحالية. جرّب فصل/إعادة توصيل السلك ثم أكمل."
                            statusText = "الحالة: فشل"
                            resumeIndex = i
                            isInstalling = false
                            progressLabel = "فشل عند OBB"
                            return
                        }
                    }
                } else {
                    appendLog("لا يوجد مجلد OBB — سيتم الاكتفاء بتثبيت APK")
                }

                delay(1200)
                i++
                resumeIndex = i
                progress = (i.toFloat() / queue.size.toFloat()).coerceIn(0f, 1f)
            }

            progress = 1f
            progressLabel = "اكتمل التثبيت"
            statusText = "الحالة: تم بنجاح"
            appendLog("==============================================")
            appendLog("اكتمل التثبيت بنجاح")
            appendLog("==============================================")

            adb.killServer()

            warningText = "تمت العملية بنجاح.\nافصل السلك واستمتع باللعب.\nولا تنسانا من التقييم لتطوير خدماتنا لكم."
            showRestart = true
        } catch (e: Throwable) {
            warningText = safeUiMsg(e)
            statusText = "الحالة: تعذر الإكمال"
        } finally {
            isInstalling = false
        }
    }

    // ✅ Autoscroll to bottom whenever log changes
    LaunchedEffect(logText) {
        logScroll.animateScrollTo(logScroll.maxValue)
    }

    LaunchedEffect(modLogText) {
        modLogScroll.animateScrollTo(modLogScroll.maxValue)
    }

    LaunchedEffect(Unit) {
        while (true) {
            refreshDeviceInfo()
            
            // Refresh installed games
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val serial = getAuthorizedSerialOrNull()
                    if (serial != null) {
                        installedGames = modsManager.getInstalledGamePackages(serial)
                    }
                } catch (_: Exception) { }
            }
            
            delay(2000)
        }
    }

    // ✅ Restart Quest after finishing (button)
    fun restartQuestNow() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val serial = getAuthorizedSerialOrNull()
                if (serial != null) {
                    adb.reboot(serial)
                }
            } catch (_: Throwable) {
            } finally {
                hardExitApp()
            }
        }
    }

    suspend fun installSelectedMod() {
        if (modZipFile == null) {
            appendModLog("❌ لم يتم اختيار ملف مود")
            return
        }

        isInstallingMod = true
        modProgress = 0f
        modProgressLabel = "بدء تثبيت المود..."
        appendModLog("==============================================")
        appendModLog("بدء تثبيت المود: ${modZipFile?.name}")
        appendModLog("==============================================")

        try {
            adb.startServer()
            
            val serial = getAuthorizedSerialOrNull()
            if (serial == null) {
                appendModLog("❌ لا يوجد جهاز متصل أو مصرح به")
                return
            }

            modProgressLabel = "فك ضغط ملف المود..."
            modProgress = 0.1f
            appendModLog("فك ضغط ملف المود...")
            
            val extractResult = modsManager.extractModZip(modZipFile!!)
            if (!extractResult.success) {
                appendModLog("❌ فشل فك الضغط: ${extractResult.message}")
                return
            }
            
            appendModLog("✅ تم فك الضغط بنجاح")
            appendModLog("الملفات المستخرجة: ${extractResult.extractedFiles.size}")
            
            val extractedDir = File(extractResult.message.split(": ").lastOrNull() ?: "")
            if (!extractedDir.exists()) {
                appendModLog("❌ لم يتم العثور على مجلد الملفات المستخرجة")
                return
            }

            val targetPath = if (isManualMode && manualPath.isNotBlank()) {
                manualPath.trim()
            } else {
                selectedGameInfo?.modPath ?: ""
            }

            if (targetPath.isBlank()) {
                appendModLog("❌ لم يتم تحديد مسار التثبيت")
                return
            }

            modProgressLabel = "تثبيت المود في النظارة..."
            modProgress = 0.3f

            val installResult = modsManager.installModToQuest(
                serial = serial,
                extractedModDir = extractedDir,
                targetPath = targetPath
            ) { progress ->
                modProgressLabel = progress
                appendModLog("📋 $progress")
            }

            if (installResult.success) {
                modProgress = 1f
                modProgressLabel = "اكتمل التثبيت"
                appendModLog("✅ ${installResult.message}")
                appendModLog("📂 المسار المستهدف: $targetPath")
                appendModLog("==============================================")
                appendModLog("تم تثبيت المود بنجاح!")
                appendModLog("==============================================")
            } else {
                appendModLog("❌ فشل التثبيت: ${installResult.message}")
                modProgressLabel = "فشل التثبيت"
            }

        } catch (e: Exception) {
            appendModLog("❌ خطأ غير متوقع: ${e.message}")
            modProgressLabel = "خطأ"
        } finally {
            isInstallingMod = false
        }
    }

    Window(
        onCloseRequest = { hardExitApp() },
        title = "Near FutureVR - مثبت ألعاب Meta Quest"
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            MaterialTheme {
                val pageScroll = rememberScrollState()

                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(pageScroll),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Header with Logo
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (logoBitmap != null) {
                                // Simple logo display (we'll show app name if logo fails)
                                Text(
                                    "🎮",
                                    style = MaterialTheme.typography.headlineLarge,
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                            Column {
                                Text(
                                    "Near FutureVR",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "مثبت ألعاب ومودات Meta Quest",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Tabs
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("تثبيت الألعاب") },
                            icon = { Icon(Icons.Default.Settings, contentDescription = null) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("المودات") },
                            icon = { Icon(Icons.Default.Build, contentDescription = null) }
                        )
                    }

                    // Connection Status Card
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(connectionText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(deviceText, style = MaterialTheme.typography.bodyMedium)
                            Text(storageText, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    // Content based on selected tab
                    when (selectedTab) {
                        0 -> {
                            // APK Installer Tab
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Button(
                                        onClick = {
                                            val f = chooseFolder()
                                            if (f != null) {
                                                folders = folders + f
                                                rebuildQueue()
                                                appendLog("تمت إضافة مجلد: ${f.absolutePath}")
                                            }
                                        },
                                        enabled = !isInstalling
                                    ) { Text("إضافة مجلد لعبة") }

                                    OutlinedButton(
                                        onClick = {
                                            folders = emptyList()
                                            queue = emptyList()
                                            resumeIndex = 0
                                            appendLog("تم مسح القائمة")
                                        },
                                        enabled = !isInstalling
                                    ) { Text("مسح القائمة") }

                                    Button(
                                        onClick = { CoroutineScope(Dispatchers.IO).launch { installQueue() } },
                                        enabled = !isInstalling && queue.isNotEmpty()
                                    ) { Text("تثبيت الكل") }

                                    if (showRestart) {
                                        OutlinedButton(onClick = { restartQuestNow() }) { Text("Restart") }
                                    }
                                }
                            }

                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(statusText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                                    LinearProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.fillMaxWidth().height(10.dp)
                                    )

                                    Text(progressLabel, style = MaterialTheme.typography.bodyMedium)

                                    if (!warningText.isNullOrBlank()) {
                                        Box(
                                            modifier = Modifier.fillMaxWidth()
                                                .background(Color(0xFFFFF3CD))
                                                .padding(10.dp)
                                        ) {
                                            Text(
                                                warningText!!,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = Color(0xFF6B4E00)
                                            )
                                        }
                                    }
                                }
                            }

                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("قائمة الألعاب (بالترتيب)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    if (queue.isEmpty()) {
                                        Text("ما فيه ألعاب مكتشفة.", style = MaterialTheme.typography.bodyMedium)
                                    } else {
                                        queue.forEachIndexed { idx, g ->
                                            val requiredGb = bytesToGb(g.requiredBytes)
                                            Text(
                                                "${idx + 1}) ${g.folder.name}  —  المطلوب تقريبًا: ${formatGb(requiredGb)}",
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                    }
                                }
                            }

                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("سجل تثبيت الألعاب", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.height(8.dp))
                                    Box(
                                        modifier = Modifier.fillMaxWidth().height(260.dp)
                                            .background(Color(0xFFF6F6F6))
                                            .padding(10.dp)
                                    ) {
                                        Text(
                                            if (logText.isBlank()) "—" else logText,
                                            style = MaterialTheme.typography.bodySmall,
                                            textAlign = TextAlign.Start,
                                            modifier = Modifier.verticalScroll(logScroll)
                                        )
                                    }
                                }
                            }
                        }

                        1 -> {
                            // Mods Tab
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("نظام المودات", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    
                                    // Game Selection
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedButton(
                                            onClick = { isManualMode = !isManualMode }
                                        ) {
                                            Text(if (isManualMode) "اختيار من القائمة" else "مسار يدوي")
                                        }

                                        if (!isManualMode) {
                                            var expanded by remember { mutableStateOf(false) }
                                            Box {
                                                OutlinedButton(
                                                    onClick = { expanded = true },
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text(
                                                        if (selectedGameInfo != null) selectedGameInfo!!.name 
                                                        else "اختر لعبة"
                                                    )
                                                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                                }
                                                
                                                DropdownMenu(
                                                    expanded = expanded,
                                                    onDismissRequest = { expanded = false }
                                                ) {
                                                    modsManager.getSupportedGames().forEach { game ->
                                                        val isInstalled = installedGames.contains(game.packageName)
                                                        DropdownMenuItem(
                                                            text = {
                                                                Column {
                                                                    Text(game.name)
                                                                    Text(
                                                                        if (isInstalled) "✅ مثبت" else "❌ غير مثبت",
                                                                        style = MaterialTheme.typography.bodySmall,
                                                                        color = if (isInstalled) Color.Green else Color.Red
                                                                    )
                                                                }
                                                            },
                                                            onClick = {
                                                                selectedGame = game.name
                                                                selectedGameInfo = game
                                                                expanded = false
                                                                appendModLog("تم اختيار اللعبة: ${game.name}")
                                                            },
                                                            enabled = isInstalled
                                                        )
                                                    }
                                                }
                                            }
                                        } else {
                                            OutlinedTextField(
                                                value = manualPath,
                                                onValueChange = { manualPath = it },
                                                label = { Text("مسار المودات يدوي") },
                                                placeholder = { Text("/sdcard/ModData/...") },
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }

                                    // Selected game info
                                    if (!isManualMode && selectedGameInfo != null) {
                                        Box(
                                            modifier = Modifier.fillMaxWidth()
                                                .background(Color(0xFFE3F2FD))
                                                .padding(8.dp)
                                        ) {
                                            Column {
                                                Text("📂 المسار: ${selectedGameInfo!!.modPath}", style = MaterialTheme.typography.bodySmall)
                                                Text("💡 ملاحظات: ${selectedGameInfo!!.notes}", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }

                                    // Mod file selection
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                val file = chooseModFile()
                                                if (file != null) {
                                                    modZipFile = file
                                                    appendModLog("تم اختيار ملف المود: ${file.name}")
                                                }
                                            },
                                            enabled = !isInstallingMod
                                        ) { Text("اختيار مود (ZIP)") }

                                        Button(
                                            onClick = { 
                                                CoroutineScope(Dispatchers.IO).launch { 
                                                    installSelectedMod() 
                                                }
                                            },
                                            enabled = !isInstallingMod && modZipFile != null && 
                                                     ((isManualMode && manualPath.isNotBlank()) || selectedGameInfo != null)
                                        ) { Text("تثبيت المود") }
                                    }

                                    // Selected mod info
                                    if (modZipFile != null) {
                                        val (isValid, info) = modsManager.getModZipInfo(modZipFile!!)
                                        Box(
                                            modifier = Modifier.fillMaxWidth()
                                                .background(if (isValid) Color(0xFFE8F5E8) else Color(0xFFFFF0F0))
                                                .padding(8.dp)
                                        ) {
                                            Column {
                                                Text("📦 ملف المود: ${modZipFile?.name}", style = MaterialTheme.typography.bodySmall)
                                                Text(if (isValid) "✅ ملف صالح" else "⚠️ ملف قد لا يحتوي على مود", style = MaterialTheme.typography.bodySmall)
                                                if (info.isNotBlank()) {
                                                    Text(info, style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                        }
                                    }

                                    // Mod installation progress
                                    if (isInstallingMod) {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            LinearProgressIndicator(
                                                progress = { modProgress },
                                                modifier = Modifier.fillMaxWidth().height(8.dp)
                                            )
                                            Text(modProgressLabel, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }

                                    // Mod log
                                    Column {
                                        Text("سجل تثبيت المودات", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.height(4.dp))
                                        Box(
                                            modifier = Modifier.fillMaxWidth().height(200.dp)
                                                .background(Color(0xFFF8F9FA))
                                                .padding(8.dp)
                                        ) {
                                            Text(
                                                if (modLogText.isBlank()) "—" else modLogText,
                                                style = MaterialTheme.typography.bodySmall,
                                                textAlign = TextAlign.Start,
                                                modifier = Modifier.verticalScroll(modLogScroll)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
