@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

import androidx.compose.foundation.background
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.application
import kotlinx.coroutines.*
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.awt.Desktop
import java.net.URI
import javax.swing.SwingUtilities
import java.nio.file.Files
import java.text.DecimalFormat
import javax.swing.JFileChooser
import javax.swing.UIManager
import kotlin.system.exitProcess

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.io.StringWriter
import java.io.PrintWriter
import kotlin.math.roundToInt

enum class HostOs { WINDOWS, MAC, LINUX }

private data class GameEntry(
    val folder: File,
    val apk: File,
    val obbDirs: List<File>,
    val requiredBytes: Long
)

data class CmdResult(val exit: Int, val out: String, val err: String)


private fun jsonEscape(s: String): String {
    val sb = StringBuilder(s.length + 16)
    for (ch in s) {
        when (ch) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

private fun jsonStr(v: String?): String = if (v == null) "null" else "\"${jsonEscape(v)}\""

private object SupportReporter {
    @Volatile private var lastTitle: String? = null
    @Volatile private var lastDetails: String? = null
    @Volatile private var lastContext: String? = null
    @Volatile private var lastAt: Long = 0L

    fun hasReport(): Boolean = !lastTitle.isNullOrBlank()

    fun record(title: String, details: String, context: String? = null) {
        lastTitle = title.take(200)
        lastDetails = details.take(80_000)
        lastContext = context?.take(2_000)
        lastAt = System.currentTimeMillis()
    }

    fun summary(): String {
        val title = lastTitle ?: "—"
        return "آخر خطأ: $title"
    }

    fun buildPayload(licenseKey: String?, deviceHash: String?): String {
        val os = "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
        val java = System.getProperty("java.version")
        val app = "NFVR Quest Installer"

        return "{" +
            "\"app\":" + jsonStr(app) + "," +
            "\"os\":" + jsonStr(os) + "," +
            "\"java\":" + jsonStr(java) + "," +
            "\"at\":" + lastAt + "," +
            "\"license_key\":" + jsonStr(licenseKey) + "," +
            "\"device_hash\":" + jsonStr(deviceHash) + "," +
            "\"title\":" + jsonStr(lastTitle) + "," +
            "\"details\":" + jsonStr(lastDetails) + "," +
            "\"context\":" + jsonStr(lastContext) +
        "}"
    }

    fun send(reportUrl: String, jsonBody: String, timeoutMs: Int = 25_000): Pair<Boolean, String> {
        return try {
            val conn = (URL(reportUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val bytes = jsonBody.toByteArray(StandardCharsets.UTF_8)
            conn.outputStream.use { it.write(bytes) }

            val code = conn.responseCode
            val resp = try {
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.readText().orEmpty()
            } catch (_: Throwable) { "" }

            if (code in 200..299) true to (resp.ifBlank { "OK" })
            else false to ("HTTP $code " + resp.take(700))
        } catch (e: Throwable) {
            false to (e.message ?: e::class.java.simpleName)
        }
    }
}

// ====== LOCAL LICENSE STORE (to avoid calling private LicenseManager.activate) ======
private data class UiLicenseState(
    val fingerprint: String,
    val isActivated: Boolean,
    val boundLicenseKey: String?
)

private object LocalLicenseStore {
    private fun file(): File {
        val dir = File(System.getProperty("user.home"), ".nfvr_quest_installer")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "license.properties")
    }

    fun load(fingerprint: String): UiLicenseState {
        val f = file()
        if (!f.exists()) {
            return UiLicenseState(
                fingerprint = fingerprint,
                isActivated = false,
                boundLicenseKey = null
            )
        }

        return runCatching {
            val p = java.util.Properties()
            f.inputStream().use { p.load(it) }

            val savedFp = (p.getProperty("fingerprint") ?: "").trim()
            val key = (p.getProperty("license_key") ?: "").trim()

            val ok = savedFp.isNotBlank() && savedFp == fingerprint && key.isNotBlank()

            UiLicenseState(
                fingerprint = fingerprint,
                isActivated = ok,
                boundLicenseKey = if (ok) key else null
            )
        }.getOrElse {
            UiLicenseState(
                fingerprint = fingerprint,
                isActivated = false,
                boundLicenseKey = null
            )
        }
    }

    fun saveActivated(fingerprint: String, licenseKey: String): Pair<Boolean, String?> {
        return runCatching {
            val f = file()
            val p = java.util.Properties()
            p["fingerprint"] = fingerprint
            p["license_key"] = licenseKey.trim()

            f.outputStream().use { p.store(it, "NFVR Quest Installer - License") }
            true to null
        }.getOrElse { false to (it.message ?: "Unknown") }
    }

    fun clear() {
        runCatching { file().delete() }
    }
}
// ================================================================================


// ====== ONLINE LICENSE CONFIG ======
private const val LICENSE_API_BASE_URL = "https://nfvr-license-api.isaudi-official.workers.dev"
private const val SUPPORT_REPORT_URL = "${LICENSE_API_BASE_URL}/client/report"
private const val LICENSE_PRODUCT_CODE = "NFVR_INSTALLER"
// ===================================

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

private fun estimatePushTimeoutMs(sizeBytes: Long): Long {
    // قاعدة عملية: 10 دقائق لكل 1GB + حد أدنى 30 دقيقة وحد أعلى 6 ساعات
    val gb = (sizeBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)).coerceAtLeast(0.1)
    val minutes = (gb * 10.0).toLong().coerceAtLeast(30L)
    val ms = minutes * 60_000L
    return ms.coerceAtMost(6L * 60L * 60_000L)
}

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
        msg.contains("no such file") || msg.contains("not found") ->
            "تعذر تشغيل ADB. تأكد أن البرنامج مكتمل ولم تُحذف ملفات منه."
        msg.contains("permission") ->
            "تعذر الوصول للصلاحيات. شغّل البرنامج كمسؤول إذا لزم."
        else ->
            "صار خطأ غير متوقع. جرّب إعادة تشغيل البرنامج وفصل/إعادة توصيل السلك."
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
        } catch (_: Throwable) {
        }
    }
}

private fun runProcess(cmd: List<String>, workDir: File? = null, timeoutMs: Long = 120_000): CmdResult {
    val pb = ProcessBuilder(cmd)
    if (workDir != null) pb.directory(workDir)
    val p = pb.start()

    val out = StringBuilder()
    val err = StringBuilder()

    val tOut =
        Thread { p.inputStream.bufferedReader().useLines { it.forEach { line -> out.appendLine(line) } } }
    val tErr =
        Thread { p.errorStream.bufferedReader().useLines { it.forEach { line -> err.appendLine(line) } } }
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



/**
 * Push مع Progress حقيقي (حسب حجم الملفات).
 * - ما فيه أي إرسال تلقائي: هذا فقط للحساب داخل البرنامج.
 * - يعتمد على push() الحالي (فيه timeout ديناميكي + retry).
 */
fun pushWithProgress(
    serial: String,
    from: File,
    toDevicePath: String,
    onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit
): CmdResult {
    if (!from.exists()) return CmdResult(1, "", "Source not found: ${from.absolutePath}")

    val total = dirSizeBytes(from).coerceAtLeast(1L)
    var copied = 0L

    fun tick(add: Long) {
        copied = (copied + add).coerceAtMost(total)
        onProgress(copied, total)
    }

    // بداية
    onProgress(0L, total)

    if (from.isFile) {
        val r = push(serial, from, toDevicePath)
        if (r.exit == 0) tick(from.length())
        return r
    }

    // مجلد: ندفع ملف-ملف عشان نطلع نسبة حقيقية
    val files = from.walkTopDown().filter { it.isFile }.toList()
    val base = toDevicePath.trimEnd('/')

    // تأكد المجلد الهدف موجود
    val mkBase = shell(serial, "mkdir", "-p", base)
    if (mkBase.exit != 0) return mkBase

    for (f in files) {
        val rel = f.relativeTo(from).invariantSeparatorsPath
        val remoteFile = (base + "/" + rel).replace("//", "/")
        val remoteParent = remoteFile.substringBeforeLast('/', missingDelimiterValue = base)

        val mk = shell(serial, "mkdir", "-p", remoteParent)
        if (mk.exit != 0) return mk

        val r = push(serial, f, remoteFile)
        if (r.exit != 0) return r

        tick(f.length())
    }

    return CmdResult(0, "OK", "")
}
    fun shell(serial: String, vararg args: String): CmdResult {
        val (adb, dir) = adbBase()
        return runProcess(listOf(adb.absolutePath, "-s", serial, "shell", *args), workDir = dir)
    }

    fun installApk(serial: String, apk: File): CmdResult {
        val (adb, dir) = adbBase()
        return runProcess(
            listOf(adb.absolutePath, "-s", serial, "install", "-r", apk.absolutePath),
            workDir = dir,
            timeoutMs = 20 * 60_000
        )
    }

    fun push(serial: String, from: File, toDevicePath: String): CmdResult {
    val (adb, dir) = adbBase()

    val sizeBytes = dirSizeBytes(from)
    val timeoutMs = estimatePushTimeoutMs(sizeBytes)

    fun doPush(): CmdResult {
        return runProcess(
            listOf(adb.absolutePath, "-s", serial, "push", from.absolutePath, toDevicePath),
            workDir = dir,
            timeoutMs = timeoutMs
        )
    }

    // محاولة 1
    var r = doPush()
    if (r.exit == 0) return r

    // Retry 1: restart adb server
    bundled.killServerSilently()
    runProcess(listOf(adb.absolutePath, "start-server"), workDir = dir, timeoutMs = 60_000)
    r = doPush()
    if (r.exit == 0) return r

    // Retry 2: restart adb server مرة ثانية (للعمليات الثقيلة)
    bundled.killServerSilently()
    runProcess(listOf(adb.absolutePath, "start-server"), workDir = dir, timeoutMs = 60_000)
    return doPush()
}

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

private fun copyToClipboard(text: String) {
    runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
    }
}

private fun hardExitApp() {
    try {
        java.awt.Window.getWindows().forEach { it.dispose() }
    } catch (_: Throwable) {
    }
    exitProcess(0)
}



private fun runOnUi(block: () -> Unit) {
    try {
        if (java.awt.EventQueue.isDispatchThread()) {
            block()
        } else {
            javax.swing.SwingUtilities.invokeLater { block() }
        }
    } catch (_: Throwable) {
        // لو لأي سبب فشل التحويل للـ UI thread، نفّذ مباشرة كحل أخير
        block()
    }
}


fun main() = application {
    val host = detectOs()
    val bundledAdb = remember { BundledAdb(host) }
    val adb = remember { AdbClient(bundledAdb) }
    val modsManager = remember { ModsManager(adb) }

    var selectedTab by remember { mutableStateOf(0) }

    var connectionText by remember { mutableStateOf("الاتصال: جاري الفحص...") }
    var hasAuthorizedDevice by remember { mutableStateOf(false) }
    var deviceText by remember { mutableStateOf("—") }
    var storageText by remember { mutableStateOf("—") }

    var statusText by remember { mutableStateOf("الحالة: جاهز") }
    var warningText by remember { mutableStateOf<String?>(null) }

    var folders by remember { mutableStateOf(listOf<File>()) }
    var queue by remember { mutableStateOf(mutableStateListOf<GameEntry>()) }
    var gamesLogAutoScroll by remember { mutableStateOf(true) }
    var gameToRemove by remember { mutableStateOf<GameEntry?>(null) }

    var isInstalling by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var progressLabel by remember { mutableStateOf("—") }

    var logText by remember { mutableStateOf("") }
    var showRestart by remember { mutableStateOf(false) }

    var resumeIndex by remember { mutableStateOf(0) }
    var pausedBecauseDisconnected by remember { mutableStateOf(false) }

    var selectedGame by remember { mutableStateOf("") }
    var selectedGameInfo by remember { mutableStateOf<GameInfo?>(null) }
    var modZipFile by remember { mutableStateOf<File?>(null) }
    var isInstallingMod by remember { mutableStateOf(false) }
    var modProgress by remember { mutableStateOf(0f) }
    var modProgressLabel by remember { mutableStateOf("—") }
    var lastModPctLogged by remember { mutableStateOf(-1) }
    var modLogText by remember { mutableStateOf("") }
    var installedGames by remember { mutableStateOf<List<String>>(emptyList()) }
    var manualPath by remember { mutableStateOf("") }
    var isManualMode by remember { mutableStateOf(false) }

    // ===== LICENSE UI STATE =====
    var licenseKeyInput by remember { mutableStateOf("") }
    var isActivating by remember { mutableStateOf(false) }
    var activationUiMsg by remember { mutableStateOf<String?>(null) }
    // ============================

    val logScroll = rememberScrollState()
    val modLogScroll = rememberScrollState()

    var logoBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(Unit) {
        try {
            val stream = javaClass.getResourceAsStream("/nfvr_logo.png")
            if (stream != null) {
                logoBitmap = loadImageBitmap(stream)
            }
        } catch (_: Exception) {
        }
    }

    fun appendLog(line: String) {
        logText += if (logText.isBlank()) line else "\n$line"
    }

    fun appendModLog(line: String) {
        modLogText += if (modLogText.isBlank()) line else "\n$line"
    }

    fun openUrl(url: String) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(url))
            }
        } catch (_: Exception) {
            // ignore
        }
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

    fun requireDeviceOrWarn(write: (String) -> Unit): Boolean {
        if (!hasAuthorizedDevice) {
            write("الرجاء اشبك نظارة Meta Quest بالسلك ثم وافق على USB Debugging داخل النظارة قبل استخدام البرنامج.")
            return false
        }
        return true
    }

    suspend fun refreshDeviceInfo() {
        try {
            adb.startServer()
            val dev = adb.devices()
            val parsed = parseFirstDevice(dev.out)

            if (parsed == null) {
                hasAuthorizedDevice = false
                connectionText = "الاتصال: لا يوجد جهاز متصل"
                deviceText = "—"
                storageText = "—"
                pausedBecauseDisconnected = isInstalling
                if (isInstalling) statusText = "الحالة: توقف مؤقت — اشبك النظارة بالسلك"
                return
            }

            val (serial, authorized) = parsed
            if (!authorized) {
                hasAuthorizedDevice = false
                connectionText = "الاتصال: تم العثور على جهاز — يحتاج موافقة داخل النظارة"
                deviceText = "السيريال: $serial"
                storageText = "—"
                statusText =
                    if (isInstalling) "الحالة: توقف مؤقت — وافق على USB Debugging داخل النظارة"
                    else "الحالة: وافق على USB Debugging داخل النظارة (إذا ظهرت)"
                return
            }

            hasAuthorizedDevice = true
            warningText = null

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
            hasAuthorizedDevice = false
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


val base = (i.toFloat() / queue.size.toFloat()).coerceIn(0f, 1f)
val step = (1f / queue.size.toFloat()).coerceIn(0f, 1f)
fun setProgressWithinGame(p: Float) {
    val v = (base + step * p.coerceIn(0f, 1f)).coerceIn(0f, 1f)
    SwingUtilities.invokeLater { progress = v }
}
setProgressWithinGame(0f)

                val freeGb = getFreeGb(s2)
                val requiredGb = bytesToGb(entry.requiredBytes)
                if (freeGb < requiredGb) {
                    warningText =
                        "تنبيه: المساحة غير كافية لهذه اللعبة. المطلوب تقريبًا ${formatGb(requiredGb)} والمتاح ${formatGb(freeGb)}."
                    statusText = "الحالة: أفرغ مساحة ثم اضغط (تثبيت الكل) للإكمال"
                    appendLog("مساحة غير كافية عند اللعبة: ${entry.folder.name}")
                    resumeIndex = i
                    isInstalling = false
                    progressLabel = "متوقف بسبب المساحة"
                    return
                }

                progressLabel = "تثبيت APK: ${entry.apk.name}"
                statusText = "الحالة: جاري تثبيت ${i + 1} / ${queue.size}"

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

                setProgressWithinGame(0.25f)

                if (entry.obbDirs.isNotEmpty()) {
                    val totalObbBytes = entry.obbDirs.sumOf { dirSizeBytes(it) }.coerceAtLeast(1L)
                    var copiedObbBytes = 0L
                    for (dir in entry.obbDirs) {
                        progressLabel = "نسخ ملفات OBB: ${dir.name}"
                        appendLog("نسخ OBB: ${dir.name}")

                        val target = "/sdcard/Android/obb/${dir.name}"

                        val pushRes = adb.pushWithProgress(s2, dir, target) { copied, _ ->
                            // تحديث نسبة حقيقية حسب حجم ملفات OBB
                            val overall = (copiedObbBytes + copied).coerceAtMost(totalObbBytes)
                            val frac = overall.toFloat() / totalObbBytes.toFloat()
                            setProgressWithinGame(0.25f + 0.70f * frac)
                        }

                        appendLog(pushRes.out.trim().ifBlank { "نسخ OBB: (بدون مخرجات)" })

                        if (pushRes.exit != 0) {
                            val combined = (pushRes.out + "\n" + pushRes.err).lowercase()
                            if (combined.contains("no space left")) {
                                warningText =
                                    "تنبيه: نفدت المساحة أثناء نسخ ملفات اللعبة. أفرغ مساحة ثم اضغط (تثبيت الكل) للإكمال."
                                statusText = "الحالة: متوقف بسبب المساحة"
                                resumeIndex = i
                                isInstalling = false
                                progressLabel = "متوقف بسبب المساحة"
                                return
                            }
                            val err = pushRes.err.trim()
                            val extra = if (err.isNotEmpty()) "\nتفاصيل: $err" else ""
                            warningText = "فشل نسخ ملفات OBB للعبة الحالية. جرّب فصل/إعادة توصيل السلك ثم أكمل.$extra"
                            statusText = "الحالة: فشل"
                            resumeIndex = i
                            isInstalling = false
                            progressLabel = "فشل عند OBB"
                            return
                        }

                        // نجاح النسخ — ثبّت التقدم لهذه اللعبة
                        copiedObbBytes = (copiedObbBytes + dirSizeBytes(dir)).coerceAtMost(totalObbBytes)
                        setProgressWithinGame(0.25f + 0.70f * (copiedObbBytes.toFloat() / totalObbBytes.toFloat()))
                    }
                } else {
                    appendLog("لا يوجد مجلد OBB — سيتم الاكتفاء بتثبيت APK")
                }

                // نهاية مرحلة OBB لهذه اللعبة
                setProgressWithinGame(0.95f)

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
            SupportReporter.record(
                title = "خطأ أثناء التثبيت",
                details = e.stackTraceToString(),
                context = progressLabel
            )
            warningText = safeUiMsg(e)
            statusText = "الحالة: تعذر الإكمال"
        } finally {
            isInstalling = false
        }
    }

    fun restartQuestNow() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val serial = getAuthorizedSerialOrNull()
                if (serial != null) adb.reboot(serial)
            } catch (_: Throwable) {
            } finally {
                hardExitApp()
            }
        }
    }

    suspend fun installSelectedMod() {
        if (modZipFile == null) {
            appendModLog("لم يتم اختيار ملف مود")
            return
        }

        isInstallingMod = true
        modProgress = 0f
        lastModPctLogged = -1
        modProgressLabel = "بدء تثبيت المود..."
        appendModLog("==============================================")
        appendModLog("بدء تثبيت المود: ${modZipFile?.name}")
        appendModLog("==============================================")

        try {
            adb.startServer()

            val serial = getAuthorizedSerialOrNull()
            if (serial == null) {
                appendModLog("لا يوجد جهاز متصل أو مصرح به")
                return
            }

            modProgressLabel = "فك ضغط ملف المود..."
            modProgress = 0.1f
            appendModLog("فك ضغط ملف المود...")

            val extractResult = modsManager.extractModZip(modZipFile!!)
            if (!extractResult.success) {
                appendModLog("فشل فك الضغط: ${extractResult.message}")
                return
            }

            appendModLog("تم فك الضغط بنجاح")
            appendModLog("الملفات المستخرجة: ${extractResult.extractedFiles.size}")

            val extractedDir = File(extractResult.message.split(": ").lastOrNull() ?: "")
            if (!extractedDir.exists()) {
                appendModLog("لم يتم العثور على مجلد الملفات المستخرجة")
                return
            }

            val targetPath = if (isManualMode && manualPath.isNotBlank()) {
                manualPath.trim()
            } else {
                selectedGameInfo?.modPath ?: ""
            }

            if (targetPath.isBlank()) {
                appendModLog("لم يتم تحديد مسار التثبيت")
                return
            }

            modProgressLabel = "تثبيت المود في النظارة..."
            modProgress = 0.3f

            val installResult = modsManager.installModToQuest(
                serial = serial,
                extractedModDir = extractedDir,
                targetPath = targetPath
            ) { sentBytes, totalBytes ->
                val safeTotal = if (totalBytes <= 0L) 1L else totalBytes
                val ratio = (sentBytes.toDouble() / safeTotal.toDouble()).coerceIn(0.0, 1.0)
                modProgress = (0.3f + (ratio.toFloat() * 0.7f)).coerceIn(0f, 1f)
                val pct = (ratio * 100.0).toInt()
                if (pct != lastModPctLogged) {
                    lastModPctLogged = pct
                    modProgressLabel = "نسخ الملفات... $pct%"
                    appendModLog(modProgressLabel)
                }
            }

            if (installResult.success) {
                modProgress = 1f
                modProgressLabel = "اكتمل التثبيت"
                appendModLog(installResult.message)
                appendModLog("المسار المستهدف: $targetPath")
                appendModLog("==============================================")
                appendModLog("تم تثبيت المود بنجاح")
                appendModLog("==============================================")
            } else {
                appendModLog("فشل التثبيت: ${installResult.message}")
                modProgressLabel = "فشل التثبيت"
            }

        } catch (e: Exception) {
            appendModLog("خطأ غير متوقع: ${e.message}")
            modProgressLabel = "خطأ"
        } finally {
            isInstallingMod = false
        }
    }

    LaunchedEffect(logText) { logScroll.animateScrollTo(logScroll.maxValue) }
    LaunchedEffect(modLogText) { modLogScroll.animateScrollTo(modLogScroll.maxValue) }

    LaunchedEffect(Unit) {
        while (true) {
            refreshDeviceInfo()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val serial = getAuthorizedSerialOrNull()
                    if (serial != null) {
                        installedGames = modsManager.getInstalledGamePackages(serial)
                    }
                } catch (_: Exception) {
                }
            }

            delay(2000)
        }
    }

    Window(
        onCloseRequest = { hardExitApp() },
        title = "Near FutureVR - مثبت ألعاب Meta Quest",
        icon = painterResource("icon.png")
    ) {

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            MaterialTheme {
                val pageScroll = rememberScrollState()
                val fingerprint = remember { LicenseManager.fingerprint() }
                val licenseState = remember { mutableStateOf(LocalLicenseStore.load(fingerprint)) }
                val showSettings = remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(pageScroll),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // HEADER
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                "NFVR",
                                style = MaterialTheme.typography.headlineLarge,
                                modifier = Modifier.padding(end = 8.dp)
                            )

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
                                Text(
                                    "الإصدار: Version 2",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(Modifier.weight(1f))

                            IconButton(onClick = { showSettings.value = true }) {
                                Icon(Icons.Default.Settings, contentDescription = "الإعدادات")
                            }
                        }
                    }

                    // CONNECTION CARD
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(connectionText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(deviceText, style = MaterialTheme.typography.bodyMedium)
                            Text(storageText, style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    // SETTINGS (LICENSE)
                    if (showSettings.value) {
                        DialogWindow(
                            onCloseRequest = { showSettings.value = false },
                            title = "الإعدادات",
                            state = rememberDialogState(size = DpSize(560.dp, 720.dp)),
                            resizable = false
                        ) {
                            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("الإعدادات", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.weight(1f))
                                        OutlinedButton(onClick = { showSettings.value = false }) { Text("إغلاق") }
                                    }

                                    Divider()

                                    Text("الترخيص", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                                    val fp = licenseState.value.fingerprint
                                    val boundKey = licenseState.value.boundLicenseKey

                                    Box(
                                        modifier = Modifier.fillMaxWidth()
                                            .background(if (licenseState.value.isActivated) Color(0xFFE8F5E8) else Color(0xFFFFF0F0))
                                            .padding(12.dp)
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            SelectionContainer {
                                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    Text(
                                                        "الحالة: ${if (licenseState.value.isActivated) "مفعل" else "غير مفعل"}",
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Text("بصمة الجهاز: $fp", style = MaterialTheme.typography.bodySmall)
                                                    Text("المفتاح المرتبط: ${boundKey ?: "—"}", style = MaterialTheme.typography.bodySmall)
                                                }
                                            }

                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedButton(onClick = {
                                                    copyToClipboard(fp)
                                                    activationUiMsg = "تم نسخ البصمة"
                                                }) { Text("نسخ البصمة") }

                                                    OutlinedButton(
                                                        enabled = !boundKey.isNullOrBlank(),
                                                        onClick = {
                                                            copyToClipboard(boundKey ?: "")
                                                            activationUiMsg = "تم نسخ المفتاح"
                                                        }
                                                    ) { Text("نسخ المفتاح") }

                                                OutlinedButton(onClick = {
                                                    val text = "LicenseKey=${boundKey ?: ""}\nFingerprint=$fp\nProduct=$LICENSE_PRODUCT_CODE"
                                                    copyToClipboard(text)
                                                    activationUiMsg = "تم نسخ بيانات الدعم"
                                                }) { Text("نسخ للدعم") }
                                            }


Spacer(Modifier.height(14.dp))

// دعم: إرسال تقرير خطأ يدويًا (بزر فقط)
val canSendReport = SupportReporter.hasReport()
var reportStatus by remember { mutableStateOf<String?>(null) }
var sendingReport by remember { mutableStateOf(false) }
val scopeReport = rememberCoroutineScope()

Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
        text = if (canSendReport) SupportReporter.summary() else "ما فيه أخطاء محفوظة حاليًا.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Button(
        enabled = canSendReport && !sendingReport,
        onClick = {
            sendingReport = true
            reportStatus = null

            val payloadRaw = SupportReporter.buildPayload(
                licenseKey = boundKey,
                deviceHash = fp
            )
            val payload =
                if (payloadRaw.toByteArray(StandardCharsets.UTF_8).size > 200_000)
                    payloadRaw.take(180_000)
                else payloadRaw

            scopeReport.launch(Dispatchers.IO) {
                val (ok, msg) = SupportReporter.send(SUPPORT_REPORT_URL, payload)
                SwingUtilities.invokeLater {
                    sendingReport = false
                    reportStatus = if (ok) "تم إرسال التقرير بنجاح." else "فشل إرسال التقرير: $msg"
                }
            }
        }
    ) {
        Text(if (sendingReport) "جارٍ الإرسال..." else "مشاركة تقرير خطأ")
    }

    if (!reportStatus.isNullOrBlank()) {
        Text(reportStatus!!, style = MaterialTheme.typography.bodySmall)
    }
}
                                        }
                                    }

                                    if (!licenseState.value.isActivated) {
                                        OutlinedTextField(
                                            value = licenseKeyInput,
                                            onValueChange = { licenseKeyInput = it },
                                            label = { Text("مفتاح الترخيص") },
                                            modifier = Modifier.fillMaxWidth(),
                                            enabled = !isActivating
                                        )

                                        Button(
                                            onClick = {
                                                if (licenseKeyInput.trim().isBlank()) {
                                                    activationUiMsg = "أدخل مفتاح الترخيص"
                                                    return@Button
                                                }

                                                isActivating = true
                                                activationUiMsg = "جاري التفعيل..."

                                                CoroutineScope(Dispatchers.IO).launch {
                                                    val deviceHash = fp
                                                    val key = licenseKeyInput.trim()

                                                    try {
                                                        val result = OnlineLicenseApi.activate(
                                                            baseUrl = LICENSE_API_BASE_URL,
                                                            licenseKey = key,
                                                            deviceHash = deviceHash,
                                                            productCode = LICENSE_PRODUCT_CODE
                                                        )

                                                        if (result.ok) {
                                                            val saved = LocalLicenseStore.saveActivated(
                                                                fingerprint = deviceHash,
                                                                licenseKey = key
                                                            )
                                                            val ok = saved.first
                                                            val msg = saved.second

                                                            SwingUtilities.invokeLater {
                                                                if (ok) {
                                                                    activationUiMsg = "تم التفعيل بنجاح"
                                                                    licenseState.value = LocalLicenseStore.load(deviceHash)
                                                                    showSettings.value = false
                                                                } else {
                                                                    activationUiMsg = "فشل حفظ التفعيل محليًا: ${msg ?: "غير معروف"}"
                                                                }
                                                                isActivating = false
                                                            }
                                                        } else {
                                                            SwingUtilities.invokeLater {
                                                                activationUiMsg = "فشل: ${result.message}"
                                                                isActivating = false
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        SwingUtilities.invokeLater {
                                                            activationUiMsg = "فشل: ${e.message ?: "خطأ غير معروف"}"
                                                            isActivating = false
                                                        }
                                                    }
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            enabled = !isActivating
                                        ) {
                                            Text(if (isActivating) "جاري التفعيل..." else "تفعيل")
                                        }
                                    } else {
                                        Text(
                                            "التفعيل محفوظ محليًا. ما يحتاج سيرفر بكل تشغيل.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    if (!activationUiMsg.isNullOrBlank()) {
                                        Text(
                                            activationUiMsg ?: "",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ACTIVATION (ONLY WHEN NOT ACTIVATED)
                    if (!licenseState.value.isActivated) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("التفعيل", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                                OutlinedTextField(
                                    value = licenseKeyInput,
                                    onValueChange = { licenseKeyInput = it },
                                    label = { Text("مفتاح الترخيص") },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !isActivating
                                )

                                Button(
                                    onClick = { showSettings.value = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !isActivating
                                ) {
                                    Text(if (isActivating) "جاري التفعيل..." else "فتح صفحة التفعيل")
                                }

                                Text(
                                    "بعد التفعيل تختفي هذي البطاقة، وتقدر تشوف حالة الترخيص من أيقونة الإعدادات بالأعلى.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

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
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = { Text("فحص جاهزية PCVR") },
                            icon = { Icon(Icons.Default.Settings, contentDescription = null) }
                        )
                    }

                    // ===== GATE: ممنوع تشغيل أي شيء قبل التفعيل =====
                    if (!licenseState.value.isActivated) {

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    "البرنامج غير مفعل",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "فعّل الترخيص أول، بعدها تشتغل كل أزرار تثبيت الألعاب والمودات.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "بصمة جهازك: ${licenseState.value.fingerprint}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                    } else {

                        when (selectedTab) {
                            0 -> {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(
                                        modifier = Modifier.padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    if (!requireDeviceOrWarn(::appendLog)) return@Button
                                                    val f = chooseFolder()
                                                    if (f != null) {
                                                        folders = folders + f
                                                        rebuildQueue()
                                                        appendLog("تمت إضافة مجلد: ${f.absolutePath}")
                                                    }
                                                },
                                                enabled = hasAuthorizedDevice && !isInstalling
                                            ) { Text("إضافة مجلد") }

                                            Button(
                                                onClick = {
                                                    if (!requireDeviceOrWarn(::appendLog)) return@Button
                                                    CoroutineScope(Dispatchers.IO).launch { installQueue() }
                                                },
                                                enabled = hasAuthorizedDevice && !isInstalling && queue.isNotEmpty()
                                            ) { Text("تثبيت الكل") }

                                            if (showRestart) {
                                                OutlinedButton(
                                                    onClick = {
                                                        if (!requireDeviceOrWarn(::appendLog)) return@OutlinedButton
                                                        restartQuestNow()
                                                    },
                                                    enabled = hasAuthorizedDevice && !isInstalling
                                                ) { Text("Restart") }
                                            }
                                        }
                                    }
                                }

                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(statusText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            LinearProgressIndicator(
                                                progress = { progress },
                                                modifier = Modifier.weight(1f).height(10.dp),
                                                color = if (progress >= 1f) Color(0xFF1B5E20) else MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Text(
                                                text = "${(progress * 100f).toInt()}%",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }

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
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        "${idx + 1}) ${g.folder.name}  —  المطلوب تقريبًا: ${formatGb(requiredGb)}",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    IconButton(
                                                        onClick = {
                                                            if (isInstalling) return@IconButton
                                                            val target = g.folder.absolutePath
                                                            folders = folders.filterNot { it.absolutePath == target }
                                                            rebuildQueue()
                                                            appendLog("تم حذف اللعبة من القائمة: ${g.folder.name}")
                                                        },
                                                        enabled = !isInstalling
                                                    ) {
                                                        Icon(Icons.Default.Delete, contentDescription = "حذف")
                                                    }
                                                }
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
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("نظام المودات", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedButton(
                                                onClick = {
                                                    if (!requireDeviceOrWarn(::appendModLog)) return@OutlinedButton
                                                    isManualMode = !isManualMode
                                                },
                                                enabled = hasAuthorizedDevice && !isInstallingMod
                                            ) {
                                                Text(if (isManualMode) "اختيار من القائمة" else "مسار يدوي")
                                            }

                                            if (!isManualMode) {
                                                var expanded by remember { mutableStateOf(false) }
                                                Box {
                                                    OutlinedButton(
                                                        onClick = {
                                                            if (!requireDeviceOrWarn(::appendModLog)) return@OutlinedButton
                                                            expanded = true
                                                        },
                                                        enabled = hasAuthorizedDevice && !isInstallingMod,
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Text(if (selectedGameInfo != null) selectedGameInfo!!.name else "اختر لعبة")
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
                                                                            if (isInstalled) "مثبت" else "غير مثبت",
                                                                            style = MaterialTheme.typography.bodySmall,
                                                                            color = if (isInstalled) Color(0xFF1B5E20) else Color(0xFFB71C1C)
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

                                        if (!isManualMode && selectedGameInfo != null) {
                                            Box(
                                                modifier = Modifier.fillMaxWidth()
                                                    .background(Color(0xFFE3F2FD))
                                                    .padding(8.dp)
                                            ) {
                                                Column {
                                                    Text("المسار: ${selectedGameInfo!!.modPath}", style = MaterialTheme.typography.bodySmall)
                                                    Text("ملاحظات: ${selectedGameInfo!!.notes}", style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                        }

                                        // Beat Saber على Quest له خطوة تفعيل مودنق خاصة (Patch) قبل رفع المودات/الأغاني.
                                        if (!isManualMode && selectedGameInfo?.packageName == "com.beatgames.beatsaber") {
                                            Spacer(Modifier.height(8.dp))
                                            Box(
                                                modifier = Modifier.fillMaxWidth()
                                                    .background(Color(0xFFFFF3E0))
                                                    .padding(10.dp)
                                            ) {
                                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    Text("بيت سيبر (Quest) — طريقة خاصة", fontWeight = FontWeight.Bold)
                                                    Text(
                                                        "قبل ما ترفع مودات/أغاني، لازم تسوي Patch للعبة مرة وحدة باستخدام طريقة BSMG (ModsBeforeFriday). بعد ما يخلص، تقدر تستخدم هالصفحة لرفع ملفات المودات/الأغاني لمسار اللعبة.",
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                        Text("الخطوات المختصرة:", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                                        Text("1) شغّل بيت سيبر مرة وحدة ثم اقفلها.", style = MaterialTheme.typography.bodySmall)
                                                        Text("2) افتح أداة ModsBeforeFriday من المتصفح وثبّت المودنق (لا تفصل السلك لين يخلص).", style = MaterialTheme.typography.bodySmall)
                                                        Text("3) بعدها ارجع هنا وارفع المود ZIP أو أغانيك لمسار اللعبة.", style = MaterialTheme.typography.bodySmall)
                                                    }
                                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        OutlinedButton(onClick = { openUrl("https://mbf.bsquest.xyz/") }) {
                                                            Text("فتح ModsBeforeFriday")
                                                        }
                                                        OutlinedButton(onClick = { openUrl("https://bsmg.wiki/quest-modding.html") }) {
                                                            Text("فتح دليل BSMG")
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedButton(
                                                onClick = {
                                                    if (!requireDeviceOrWarn(::appendModLog)) return@OutlinedButton
                                                    val file = chooseModFile()
                                                    if (file != null) {
                                                        modZipFile = file
                                                        appendModLog("تم اختيار ملف المود: ${file.name}")
                                                    }
                                                },
                                                enabled = hasAuthorizedDevice && !isInstallingMod
                                            ) { Text("اختيار مود (ZIP)") }

                                            Button(
                                                onClick = {
                                                    if (!requireDeviceOrWarn(::appendModLog)) return@Button
                                                    CoroutineScope(Dispatchers.IO).launch { installSelectedMod() }
                                                },
                                                enabled = hasAuthorizedDevice &&
                                                        !isInstallingMod &&
                                                        modZipFile != null &&
                                                        ((isManualMode && manualPath.isNotBlank()) || selectedGameInfo != null)
                                            ) { Text("تثبيت المود") }
                                        }

                                        if (modZipFile != null) {
                                            val (isValid, info) = modsManager.getModZipInfo(modZipFile!!)
                                            Box(
                                                modifier = Modifier.fillMaxWidth()
                                                    .background(if (isValid) Color(0xFFE8F5E8) else Color(0xFFFFF0F0))
                                                    .padding(8.dp)
                                            ) {
                                                Column {
                                                    Text("ملف المود: ${modZipFile?.name}", style = MaterialTheme.typography.bodySmall)
                                                    Text(if (isValid) "ملف صالح" else "ملف قد لا يحتوي على مود", style = MaterialTheme.typography.bodySmall)
                                                    if (info.isNotBlank()) Text(info, style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                        }

                                        if (isInstallingMod) {
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                LinearProgressIndicator(
                                                    progress = { modProgress },
                                                    modifier = Modifier.fillMaxWidth().height(8.dp)
                                                )
                                                Text(modProgressLabel, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }

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
                            2 -> {
                                PcvrReadinessTab()
                            }

                        }
                    }
                    // ===== END GATE =====
                }
            }
        }
    }
}