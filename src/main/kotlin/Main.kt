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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.application
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.awt.Desktop
import java.net.URI
import javax.swing.SwingUtilities
import java.nio.file.Files
import java.text.DecimalFormat
import javax.swing.JOptionPane
import kotlin.system.exitProcess

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.io.StringWriter
import java.io.PrintWriter
import kotlin.math.roundToInt
import java.security.MessageDigest

enum class HostOs { WINDOWS, MAC, LINUX }

private data class GameEntry(
    val folder: File,
    val apk: File,
    val allowedObbFiles: List<AllowedObbFile>,
    val requiredBytes: Long,
    val packageName: String?
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
        DiagnosticLogger.error("$title — ${context.orEmpty()}")
    }

    fun summary(): String {
        val title = lastTitle ?: "—"
        return "آخر خطأ: $title"
    }

    fun buildPayload(licenseKey: String?, deviceHash: String?, diagnostics: String): String {
        val os = "${System.getProperty("os.name")} ${System.getProperty("os.version")}"
        val java = System.getProperty("java.version")
        val app = "NFVR Quest Installer"

        return "{" +
            "\"app\":" + jsonStr(app) + "," +
            "\"os\":" + jsonStr(os) + "," +
            "\"java\":" + jsonStr(java) + "," +
            "\"at\":" + lastAt + "," +
            "\"license_key\":" + jsonStr(licenseIdentifier(licenseKey)) + "," +
            "\"device_hash\":" + jsonStr(deviceHash) + "," +
            "\"title\":" + jsonStr(sanitize(lastTitle)) + "," +
            "\"details\":" + jsonStr(sanitize(lastDetails)) + "," +
            "\"context\":" + jsonStr(
                sanitize(buildString {
                    appendLine(lastContext.orEmpty())
                    appendLine("app_version=${AppInfo.version}")
                    appendLine("os_arch=${System.getProperty("os.arch")}")
                    append(diagnostics.takeLast(40_000))
                })
            ) +
        "}"
    }

    private fun licenseIdentifier(licenseKey: String?): String? {
        val value = licenseKey?.trim().orEmpty()
        if (value.isBlank()) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun sanitize(value: String?): String? {
        if (value == null) return null
        val home = System.getProperty("user.home").orEmpty()
        val withoutHome = if (home.isBlank()) value else value.replace(home, "[USER_HOME]", ignoreCase = true)
        return withoutHome
            .replace(Regex("""(?i)(license[_ -]?key\s*[=:]\s*)\S+"""), "$1[REDACTED]")
            .replace(Regex("""(?i)(السيريال\s*:\s*)\S+"""), "$1[REDACTED]")
            .replace(Regex("""(?i)(serial\s*[=:]\s*)\S+"""), "$1[REDACTED]")
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
                readBounded(stream, 64_000)
            } catch (_: Throwable) { "" }

            if (code in 200..299) true to (resp.ifBlank { "OK" })
            else false to ("HTTP $code " + resp.take(700))
        } catch (e: Throwable) {
            false to (e.message ?: e::class.java.simpleName)
        }
    }

    private fun readBounded(stream: java.io.InputStream?, maxBytes: Int): String {
        if (stream == null) return ""
        stream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (output.size() < maxBytes) {
                val allowed = minOf(buffer.size, maxBytes - output.size())
                val read = input.read(buffer, 0, allowed)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            return output.toString(StandardCharsets.UTF_8)
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
        // Keep this path stable: existing activated customers depend on this exact file.
        val dir = UserDataPaths.root
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
private const val LICENSE_PRODUCT_CODE = "NFVR_QUEST_INSTALLER"
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
    private var tempDir: File? = null

    fun ensureReady(): File {
        if (adbPath != null && adbPath!!.exists()) return adbPath!!

        val tempDir = Files.createTempDirectory("NFVR_ADB_").toFile()
        this.tempDir = tempDir
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

    fun cleanupOwnedResources() {
        OwnedProcessRegistry.destroyAll()
        runCatching { tempDir?.deleteRecursively() }
        adbPath = null
        tempDir = null
    }
}

private class BoundedTextCollector(private val maxChars: Int = 200_000) {
    private val value = StringBuilder()

    @Synchronized
    fun appendLine(line: String) {
        val remaining = maxChars - value.length
        if (remaining <= 0) return
        value.append(line.take((remaining - 1).coerceAtLeast(0))).append('\n')
    }

    @Synchronized
    override fun toString(): String = value.toString()
}

private fun runProcess(cmd: List<String>, workDir: File? = null, timeoutMs: Long = 120_000): CmdResult {
    val pb = ProcessBuilder(cmd)
    if (workDir != null) pb.directory(workDir)
    val p = pb.start()
    OwnedProcessRegistry.add(p)

    val out = BoundedTextCollector()
    val err = BoundedTextCollector()

    val tOut =
        Thread { p.inputStream.bufferedReader().useLines { it.forEach(out::appendLine) } }
    val tErr =
        Thread { p.errorStream.bufferedReader().useLines { it.forEach(err::appendLine) } }
    tOut.start(); tErr.start()

    return try {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (p.isAlive && System.nanoTime() < deadline) {
            p.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        if (p.isAlive) {
            p.destroyForcibly()
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            tOut.join(5_000)
            tErr.join(5_000)
            CmdResult(124, out.toString(), "Timeout")
        } else {
            tOut.join()
            tErr.join()
            CmdResult(p.exitValue(), out.toString(), err.toString())
        }
    } catch (interrupted: InterruptedException) {
        p.destroyForcibly()
        runCatching { p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) }
        Thread.currentThread().interrupt()
        throw interrupted
    } finally {
        if (p.isAlive) p.destroyForcibly()
        OwnedProcessRegistry.remove(p)
    }
}

open class AdbClient(private val bundled: BundledAdb) {
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
     * Pulls one known remote file without invoking a shell.  This is used for
     * read-only APK metadata inspection; the caller owns the temporary local
     * file and is responsible for deleting it.
     */
    open fun pullReadOnly(serial: String, remotePath: String, localFile: File): CmdResult {
        val destination = localFile.absoluteFile
        val parent = destination.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            return CmdResult(1, "", "Unable to create pull destination directory")
        }
        val (adb, dir) = adbBase()
        return runProcess(
            listOf(adb.absolutePath, "-s", serial, "pull", remotePath, destination.absolutePath),
            workDir = dir,
            timeoutMs = 20 * 60_000
        )
    }



/**
 * Push مع Progress حقيقي (حسب حجم الملفات).
 * - ما فيه أي إرسال تلقائي: هذا فقط للحساب داخل البرنامج.
 * - يعتمد على push() الحالي (فيه timeout ديناميكي + retry).
 */
open fun pushWithProgress(
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
    open fun shell(serial: String, vararg args: String): CmdResult {
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

private fun copyToClipboard(text: String) {
    runCatching {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
    }
}

private fun hardExitApp(cleanup: () -> Unit = {}) {
    runCatching { cleanup() }
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

private const val SECOND_INSTANCE_MESSAGE = "NFVR Quest Installer يعمل بالفعل."

private fun showSecondInstanceMessage() {
    val show: () -> Unit = {
        runCatching {
            JOptionPane.showMessageDialog(
                null,
                SECOND_INSTANCE_MESSAGE,
                "NFVR Quest Installer",
                JOptionPane.INFORMATION_MESSAGE
            )
        }
        Unit
    }
    if (SwingUtilities.isEventDispatchThread()) show() else runCatching {
        SwingUtilities.invokeAndWait(show)
    }
}

fun main() {
    val previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        DiagnosticLogger.error(
            "استثناء غير معالج في ${thread.name} أثناء تشغيل NFVR ${AppInfo.version}",
            error
        )
        if (previousUncaughtHandler != null) {
            previousUncaughtHandler.uncaughtException(thread, error)
        } else {
            error.printStackTrace()
        }
    }

    // Acquire before Compose creates any native windows. The lock is held by
    // the application closure and is released on normal composition disposal.
    val instanceLock = NfvrSingleInstanceLock.forCurrentUser()
    if (instanceLock == null) {
        showSecondInstanceMessage()
        return
    }
    try {
        application {
    val host = detectOs()
    val bundledAdb = remember { BundledAdb(host) }
    val adb = remember { AdbClient(bundledAdb) }
    val modsManager = remember { ModsManager(adb) }
    LaunchedEffect(Unit) {
        DiagnosticLogger.info("بدء تشغيل NFVR Quest Installer ${AppInfo.version}")
    }

    var selectedTab by remember { mutableStateOf(0) }

    var connectionText by remember { mutableStateOf("الاتصال: جاري الفحص...") }
    var hasAuthorizedDevice by remember { mutableStateOf(false) }
    var deviceText by remember { mutableStateOf("—") }
    var storageText by remember { mutableStateOf("—") }
    var osText by remember { mutableStateOf("—") }
    var batteryText by remember { mutableStateOf("—") }
    var deviceGuidance by remember { mutableStateOf(DeviceGuidanceState.NO_DEVICE) }

    var statusText by remember { mutableStateOf("الحالة: جاهز") }
    var warningText by remember { mutableStateOf<String?>(null) }

    var savedPaths by remember { mutableStateOf(PathPreferences()) }
    var folders by remember { mutableStateOf(emptyList<File>()) }
    var queue by remember { mutableStateOf<List<GameEntry>>(emptyList()) }
    var gamesLogAutoScroll by remember { mutableStateOf(true) }
    var gameToRemove by remember { mutableStateOf<GameEntry?>(null) }

    var isInstalling by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var progressLabel by remember { mutableStateOf("—") }
    var progressInfo by remember { mutableStateOf(InstallProgressState(false, null, 0, null)) }
    var installPhase by remember { mutableStateOf(GameInstallPhase.IDLE) }

    var logText by remember { mutableStateOf("") }
    var showRestart by remember { mutableStateOf(false) }

    var resumeIndex by remember { mutableStateOf(0) }
    var pausedBecauseDisconnected by remember { mutableStateOf(false) }

    var installedApps by remember { mutableStateOf<List<InstalledQuestApp>>(emptyList()) }
    var selectedApp by remember { mutableStateOf<InstalledQuestApp?>(null) }
    var appSearch by remember { mutableStateOf("") }
    var scanningApps by remember { mutableStateOf(false) }
    var appScanProgress by remember { mutableStateOf<InstalledAppsScanProgress?>(null) }
    var showAllQuestApps by remember { mutableStateOf(false) }
    var connectedDeviceSerial by remember { mutableStateOf<String?>(null) }
    var appScanJob by remember { mutableStateOf<Job?>(null) }
    var modZipFile by remember { mutableStateOf<File?>(null) }
    var lastModArchiveDirectory by remember { mutableStateOf<File?>(null) }
    var modAnalysis by remember { mutableStateOf<ModPackageAnalysis?>(null) }
    var analyzingMod by remember { mutableStateOf(false) }
    var isInstallingMod by remember { mutableStateOf(false) }
    var modExecutionProgress by remember { mutableStateOf<ModsManager.ModExecutionProgress?>(null) }
    var modLogText by remember { mutableStateOf("") }
    var modInstallDeviceLost by remember { mutableStateOf(false) }
    val activityHistory = remember { BoundedActivityHistory() }
    val uiScope = rememberCoroutineScope()
    val focusClearRegistry = remember { UiFocusClearRegistry() }
    val scanRequestCoalescer = remember { ScanRequestCoalescer() }
    val appScanMutex = remember { Mutex() }
    var appScanGeneration by remember { mutableStateOf(0L) }
    val installMutex = remember { Mutex() }
    val modOperationMutex = remember { Mutex() }
    var modOperationGeneration by remember { mutableStateOf(0L) }
    var activityEvents by remember { mutableStateOf<List<ActivityEvent>>(emptyList()) }

    // ===== LICENSE UI STATE =====
    var licenseKeyInput by remember { mutableStateOf("") }
    var isActivating by remember { mutableStateOf(false) }
    var activationUiMsg by remember { mutableStateOf<String?>(null) }
    // ============================

    val logScroll = rememberScrollState()

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

    fun appendLog(line: String, stdout: String = "", stderr: String = "") {
        logText += if (logText.isBlank()) line else "\n$line"
        if (logText.length > 100_000) logText = logText.takeLast(100_000)
        val event = ActivityEvent(
            phase = installPhase,
            message = line,
            stdout = stdout.take(12_000),
            stderr = stderr.take(12_000)
        )
        activityHistory.add(event)
        activityEvents = activityHistory.snapshot()
        uiScope.launch {
            withContext(Dispatchers.IO) {
                ActivityHistoryStore.append(event)
                DiagnosticLogger.info(line)
            }
        }
    }

    fun appendModLog(line: String) {
        modLogText += if (modLogText.isBlank()) line else "\n$line"
        if (modLogText.length > 100_000) modLogText = modLogText.takeLast(100_000)
        uiScope.launch {
            withContext(Dispatchers.IO) { DiagnosticLogger.info(line) }
        }
    }

    fun invalidateModOperation(clearFocus: Boolean = true) {
        // This state gate is only reached from Main-dispatched UI/device
        // callbacks. ADB workers report through Main before calling it.
        if (clearFocus) focusClearRegistry.clearBeforeUiMutation()
        modOperationGeneration += 1L
        modAnalysis = null
        if (!isInstallingMod) modExecutionProgress = null
    }

    fun sameModFileSnapshot(current: File?, captured: File): Boolean {
        if (current == null) return false
        val currentCanonical = runCatching { current.canonicalFile }.getOrNull() ?: return false
        val capturedCanonical = runCatching { captured.canonicalFile }.getOrNull() ?: return false
        return currentCanonical == capturedCanonical &&
            currentCanonical.isFile &&
            currentCanonical.length() == captured.length() &&
            currentCanonical.lastModified() == captured.lastModified()
    }

    fun sameInstalledAppSnapshot(current: InstalledQuestApp?, captured: InstalledQuestApp): Boolean =
        current != null &&
            current.packageName == captured.packageName &&
            current.versionName == captured.versionName &&
            current.versionCode == captured.versionCode &&
            current.apkPath == captured.apkPath

    fun modOperationStillCurrent(
        generation: Long,
        capturedFile: File,
        capturedApp: InstalledQuestApp,
        capturedSerial: String
    ): Boolean =
        generation == modOperationGeneration &&
            sameModFileSnapshot(modZipFile, capturedFile) &&
            sameInstalledAppSnapshot(selectedApp, capturedApp) &&
            connectedDeviceSerial == capturedSerial

    fun updateConnectedDeviceSerial(next: String?) {
        if (connectedDeviceSerial != next) {
            val lostDuringInstall = isInstallingMod &&
                connectedDeviceSerial != null &&
                connectedDeviceSerial != next
            focusClearRegistry.clearBeforeUiMutation()
            connectedDeviceSerial = next
            if (lostDuringInstall) {
                modInstallDeviceLost = true
                statusText = "الحالة: فقد الاتصال — انتظار إنهاء النقل الحالي"
                warningText = "انقطع اتصال النظارة أثناء نقل المود. لن يتم إخفاء العملية؛ أعد التوصيل وانتظر انتهائها."
                appendModLog("انقطع اتصال النظارة أثناء نقل المود؛ تم منع اعتماد النتيجة القديمة.")
            }
            invalidateModOperation(clearFocus = false)
        }
    }

    fun updateSelectedAppFromScan(next: InstalledQuestApp?) {
        if (isInstallingMod) return
        // Scanner progress callbacks are marshalled to Dispatchers.Main.immediate
        // before this helper is called; never clear Compose focus from ADB IO.
        focusClearRegistry.clearBeforeUiMutation()
        if (selectedApp != null &&
            (next == null || !sameInstalledAppSnapshot(selectedApp, next))
        ) {
            invalidateModOperation(clearFocus = false)
        }
        selectedApp = next
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

    suspend fun rebuildQueueOnIo() {
        val inspected = withContext(Dispatchers.IO) {
            folders.map { inspectGameFolder(it) }
        }
        queue = inspected.mapNotNull { inspection ->
            if (!inspection.installable || inspection.apk == null) return@mapNotNull null
            GameEntry(
                folder = inspection.folder,
                apk = inspection.apk,
                allowedObbFiles = inspection.allowedObbFiles,
                requiredBytes = inspection.requiredBytes,
                packageName = inspection.packageName
            )
        }
        inspected
            .filterNot { it.installable }
            .forEach { inspection ->
                appendLog(
                    "تم تجاهل مجلد ${inspection.folder.name}: " +
                        (inspection.reason ?: "لا يحتوي بنية لعبة قابلة للتثبيت.")
                )
            }
    }

    suspend fun getAuthorizedSerialOrNull(): String? {
        val dev = withContext(Dispatchers.IO) { adb.devices() }
        return selectAdbDevice(dev.out).selectedSerial
    }

    suspend fun getFreeGb(serial: String): Double? {
        val dfRes = withContext(Dispatchers.IO) { adb.shell(serial, "df", "-k", "/data") }
        val pair = parseDfToGb(dfRes.out) ?: return null
        return pair.second
    }

    suspend fun refreshInstalledApps() {
        if (!appScanMutex.tryLock()) return
        val generation = appScanGeneration + 1L
        appScanGeneration = generation
        val previousSnapshot = installedApps
        val selectedPackageName = selectedApp?.packageName
        scanningApps = true
        try {
            val serial = getAuthorizedSerialOrNull()
            if (serial == null) {
                // A transient disconnect is not evidence that the snapshot
                // changed. Keep both rows and package-id selection visible.
                return
            }
            var scanFailure: String? = null
            val scanned = withContext(Dispatchers.IO) {
                modsManager.scanInstalledQuestApps(
                    serial = serial,
                    showAll = showAllQuestApps
                ) { update ->
                    update.error?.let { scanFailure = it }
                    uiScope.launch(Dispatchers.Main.immediate) {
                        if (generation != appScanGeneration) return@launch
                        appScanProgress = update
                        if (update.apps.isNotEmpty()) {
                            installedApps = mergeInstalledAppSnapshot(
                                previousSnapshot,
                                (update.apps + previousSnapshot).distinctBy { it.packageName }
                            )
                            updateSelectedAppFromScan(resolveScanSelection(
                                capturedPackageName = selectedPackageName,
                                currentPackageName = selectedApp?.packageName,
                                apps = installedApps
                            ))
                        }
                    }
                }
            }
            if (generation == appScanGeneration) {
                if (scanFailure != null) {
                    appScanProgress = (appScanProgress
                        ?: InstalledAppsScanProgress(previousSnapshot, 0, null))
                        .copy(error = scanFailure)
                    return
                }
                val currentPackages = scanned.mapTo(mutableSetOf()) { it.packageName }
                val retainedPrevious = previousSnapshot.filter { it.packageName in currentPackages }
                installedApps = mergeInstalledAppSnapshot(retainedPrevious, scanned)
                updateSelectedAppFromScan(resolveScanSelection(
                    capturedPackageName = selectedPackageName,
                    currentPackageName = selectedApp?.packageName,
                    apps = installedApps
                ))
                appScanProgress = InstalledAppsScanProgress(
                    apps = installedApps,
                    processed = scanned.size,
                    total = scanned.size,
                    completed = true
                )
                appendModLog("تم فحص ${installedApps.size} تطبيقًا مثبتًا من مصادر المستخدم.")
            }
        } catch (e: CancellationException) {
            if (generation == appScanGeneration) {
                appScanProgress = (appScanProgress
                    ?: InstalledAppsScanProgress(previousSnapshot, 0, null))
                    .copy(cancelled = true)
            }
            throw e
        } catch (e: Exception) {
            if (generation == appScanGeneration) {
                appendModLog("تعذر فحص التطبيقات المثبتة: ${e.message ?: "خطأ غير معروف"}")
                appScanProgress = (appScanProgress
                    ?: InstalledAppsScanProgress(previousSnapshot, 0, null))
                    .copy(error = e.message ?: "خطأ غير معروف")
            }
            DiagnosticLogger.error("فشل فحص تطبيقات Quest المثبتة", e)
        } finally {
            // Invalidate callbacks queued by the scanner after either a
            // successful final snapshot, an error, or cancellation.
            if (generation == appScanGeneration) appScanGeneration += 1L
            scanningApps = false
            appScanMutex.unlock()
        }
    }

    fun cancelInstalledAppsScan(invalidatePendingCallbacks: Boolean = false) {
        // Keep the visible scanning state until the interruptible ADB process
        // has actually terminated and the scan coroutine reaches its catch/
        // finally block. This prevents reporting cancellation prematurely.
        // Always invalidate queued progress/finalization callbacks.  A normal
        // cancel must not allow a late ADB snapshot to replace a newer one.
        scanRequestCoalescer.cancel()
        appScanGeneration += 1L
        appScanJob?.cancel()
    }

    fun startInstalledAppsScan() {
        if (appScanJob?.isActive == true || scanningApps) {
            scanRequestCoalescer.requestWhileBusy()
            return
        }
        val job = uiScope.launch { refreshInstalledApps() }
        appScanJob = job
        job.invokeOnCompletion {
            uiScope.launch(Dispatchers.Main.immediate) {
                if (it is CancellationException) {
                    appScanProgress = (appScanProgress
                        ?: InstalledAppsScanProgress(installedApps, 0, null))
                        .copy(apps = installedApps, cancelled = true)
                }
                if (appScanJob === job) appScanJob = null
                if (scanRequestCoalescer.takePendingAfterCompletion()) {
                    startInstalledAppsScan()
                }
            }
        }
    }

    suspend fun analyzeSelectedMod() {
        if (!modOperationMutex.tryLock()) {
            appendModLog("هناك عملية مود أخرى قيد التنفيذ.")
            return
        }
        try {
            val file = modZipFile
            val app = selectedApp
            if (file == null) {
                appendModLog("اختر ملف المود قبل التحليل.")
                return
            }
            if (app == null) {
                appendModLog("اختر لعبة مثبتة قبل تحليل المود.")
                return
            }
            val requestGeneration = modOperationGeneration
            val serial = getAuthorizedSerialOrNull()
            if (serial == null) {
                appendModLog("لا يوجد جهاز مصرح به لتحليل المود.")
                return
            }
            if (requestGeneration != modOperationGeneration ||
                !sameModFileSnapshot(modZipFile, file) ||
                !sameInstalledAppSnapshot(selectedApp, app)
            ) {
                appendModLog("تم إلغاء التحليل لأن اختيار الحزمة أو اللعبة تغيّر.")
                return
            }
            if (connectedDeviceSerial != null && connectedDeviceSerial != serial) {
                appendModLog("تغيّر جهاز Quest أثناء تجهيز التحليل. أعد المحاولة.")
                return
            }
            // A scan may not have published the serial yet when the user
            // presses Analyze. Establish the snapshot without treating the
            // same device as a race.
            connectedDeviceSerial = serial
            val generation = ++modOperationGeneration
            analyzingMod = true
            modExecutionProgress = ModsManager.ModExecutionProgress(
                ModsManager.ModInstallPhase.ANALYZING,
                null,
                "تحليل بنية الحزمة وبياناتها"
            )
            val analysis = withContext(Dispatchers.IO) {
                modsManager.analyzeModPackage(serial, file, app)
            }
            val currentSerial = getAuthorizedSerialOrNull()
            if (currentSerial != serial ||
                !modOperationStillCurrent(generation, file, app, serial)
            ) {
                appendModLog("تم تجاهل نتيجة تحليل قديمة بعد تغيّر الحزمة أو اللعبة أو الجهاز.")
                return
            }
            modAnalysis = analysis
            appendModLog("تحليل الحزمة: ${analysis.packageType} — ${analysis.message}")
        } catch (e: Exception) {
            modAnalysis = null
            appendModLog("فشل تحليل الحزمة: ${e.message ?: "خطأ غير معروف"}")
            DiagnosticLogger.error("فشل تحليل حزمة مود ${modZipFile?.name.orEmpty()}", e)
        } finally {
            analyzingMod = false
            modExecutionProgress = null
            modOperationMutex.unlock()
        }
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
            withContext(Dispatchers.IO) { adb.startServer() }
            val dev = withContext(Dispatchers.IO) { adb.devices() }
            val selection = selectAdbDevice(dev.out)
            val rows = selection.rows
            if (rows.isEmpty()) {
                hasAuthorizedDevice = false
                deviceGuidance = DeviceGuidanceState.NO_DEVICE
                updateConnectedDeviceSerial(null)
                connectionText = "الاتصال: لا يوجد جهاز متصل"
                deviceText = "—"
                storageText = "—"
                osText = "—"
                batteryText = "—"
                pausedBecauseDisconnected = isInstalling
                if (isInstalling) {
                    installPhase = GameInstallPhase.PAUSED
                    statusText = "الحالة: توقف مؤقت — اشبك النظارة بالسلك"
                }
                return
            }

            val serial = selection.selectedSerial
            if (serial == null) {
                hasAuthorizedDevice = false
                deviceGuidance = deviceGuidanceState(rows, false)
                updateConnectedDeviceSerial(null)
                connectionText = "الاتصال: جهاز ADB يحتاج إجراء"
                deviceText = rows.joinToString("، ") { "${it.serial}: ${it.state.name.lowercase()}" }
                storageText = "—"
                osText = "—"
                batteryText = "—"
                statusText =
                    if (isInstalling) {
                        installPhase = GameInstallPhase.PAUSED
                        "الحالة: توقف مؤقت — ${selection.message}"
                    }
                    else "الحالة: ${selection.message}"
                return
            }

            hasAuthorizedDevice = true
            deviceGuidance = DeviceGuidanceState.AUTHORIZED
            updateConnectedDeviceSerial(serial)
            warningText = null

            connectionText = "الاتصال: مصرح وجاهز"
            val modelRes = withContext(Dispatchers.IO) {
                adb.shell(serial, "getprop", "ro.product.model")
            }
            val model = modelRes.out.trim().ifBlank { "Meta Quest" }
            val osRes = withContext(Dispatchers.IO) {
                adb.shell(serial, "getprop", "ro.build.version.release")
            }
            val osVersion = safeQuestOsVersion(osRes.out)

            val dfRes = withContext(Dispatchers.IO) {
                adb.shell(serial, "df", "-k", "/data")
            }
            val storage = parseDfToGb(dfRes.out)
            val batteryRes = withContext(Dispatchers.IO) {
                adb.shell(serial, "dumpsys", "battery")
            }
            val battery = parseQuestBatteryDump(batteryRes.out)

            deviceText = "الجهاز: ${model.take(80)}  |  السيريال: ${serial.take(120)}  |  الحالة: مصرح"
            storageText = storage?.let { (totalGb, freeGb) ->
                "المساحة: المتاح ${formatGb(freeGb)} من ${formatGb(totalGb)}"
            } ?: "المساحة: تعذر قراءتها بأمان"
            osText = "Android / Quest OS: ${osVersion ?: "غير متاح"}"
            batteryText = battery.percentage?.let { percentage ->
                val state = when (battery.charging) {
                    true -> "يشحن${battery.source?.let { " عبر $it" }.orEmpty()}"
                    false -> "غير موصول بالشحن"
                    null -> "حالة الشحن غير متاحة"
                }
                "البطارية: $percentage% — $state"
            } ?: "البطارية: غير متاحة"

            if (pausedBecauseDisconnected && isInstalling) {
                pausedBecauseDisconnected = false
                statusText = "الحالة: تم استرجاع الاتصال — جاهز للإكمال"
            } else if (!isInstalling) {
                statusText = "الحالة: جاهز"
            }
        } catch (e: Throwable) {
            hasAuthorizedDevice = false
            deviceGuidance = DeviceGuidanceState.NO_DEVICE
            updateConnectedDeviceSerial(null)
            connectionText = "الاتصال: ADB غير جاهز"
            deviceText = "—"
            storageText = "—"
            osText = "—"
            batteryText = "—"
            statusText = if (isInstalling) "الحالة: توقف مؤقت" else "الحالة: تعذر تشغيل ADB"
            warningText = "${safeUiMsg(e)}\n\n${translateAdbFailure(e.message.orEmpty())}"
            DiagnosticLogger.error("تعذر تحديث معلومات جهاز Meta Quest", e)
        }
    }

    suspend fun installQueue() {
        if (!installMutex.tryLock()) return
        if (queue.isEmpty()) {
            installPhase = GameInstallPhase.FAILED
            warningText = "ما فيه ألعاب جاهزة للتثبيت. أضف مجلد لعبة يحتوي APK."
            installMutex.unlock()
            return
        }

        isInstalling = true
        installPhase = transitionInstallPhase(
            GameInstallPhase.IDLE,
            InstallPhaseTransition.BEGIN_PREFLIGHT
        )
        showRestart = false
        warningText = null
        progress = 0f
        progressLabel = "بدء التثبيت..."
        progressInfo = InstallProgressState(
            measurable = false,
            currentItem = "الفحص المسبق",
            completed = 0,
            total = queue.size,
            phase = GameInstallPhase.PREFLIGHT
        )
        appendLog("==============================================")
        appendLog("بدء التثبيت — عدد الألعاب: ${queue.size}")
        appendLog("==============================================")

        try {
            withContext(Dispatchers.IO) { adb.startServer() }

            var i = resumeIndex
            while (i < queue.size) {
                val serial = getAuthorizedSerialOrNull()
                if (serial == null) {
                    pausedBecauseDisconnected = true
                    installPhase = GameInstallPhase.PAUSED
                    statusText = "الحالة: توقف مؤقت — اشبك النظارة/وافق داخل النظارة"
                    while (true) {
                        delay(1500)
                        if (getAuthorizedSerialOrNull() != null) break
                    }
                }

                val s2 = getAuthorizedSerialOrNull()!!
                val entry = queue[i]
                val localIssues = withContext(Dispatchers.IO) {
                    validateLocalPreflight(entry.apk, emptyList(), entry.packageName) +
                        validateAllowedObbFiles(
                            entry.folder,
                            entry.allowedObbFiles,
                            entry.packageName
                        ).map {
                            "ملف OBB غير صالح أو تغيّر منذ الفحص: $it"
                        }
                }
                if (localIssues.isNotEmpty()) {
                    installPhase = GameInstallPhase.FAILED
                    warningText = localIssues.joinToString("\n")
                    statusText = "الحالة: فشل الفحص المسبق"
                    progressLabel = "ملفات غير صالحة"
                    appendLog("فشل الفحص المسبق: ${localIssues.joinToString(" | ")}")
                    resumeIndex = i
                    return
                }
                installPhase = transitionInstallPhase(
                    installPhase,
                    InstallPhaseTransition.BEGIN_PREFLIGHT
                )
                val base = (i.toFloat() / queue.size.toFloat()).coerceIn(0f, 1f)
                val step = (1f / queue.size.toFloat()).coerceIn(0f, 1f)
                fun setProgressWithinGame(
                    p: Float,
                    item: String = entry.apk.name,
                    measurable: Boolean = true
                ) {
                    val v = (base + step * p.coerceIn(0f, 1f)).coerceIn(0f, 1f)
                    val phaseSnapshot = installPhase
                    uiScope.launch {
                        progress = v
                        progressInfo = InstallProgressState(
                            measurable = measurable,
                            currentItem = item,
                            completed = i + 1,
                            total = queue.size,
                            gameName = entry.folder.name,
                            phase = phaseSnapshot,
                            percentOverride = if (measurable) (v * 100f).roundToInt() else null
                        )
                    }
                }
                setProgressWithinGame(0f, measurable = false)

                installPhase = transitionInstallPhase(
                    installPhase,
                    InstallPhaseTransition.STORAGE_CHECK_STARTED
                )
                val freeGb = getFreeGb(s2)
                val requiredGb = bytesToGb(entry.requiredBytes)
                if (freeGb != null && freeGb < requiredGb) {
                    installPhase = transitionInstallPhase(
                        installPhase,
                        InstallPhaseTransition.STORAGE_INSUFFICIENT
                    )
                    warningText =
                        "تنبيه: المساحة غير كافية لهذه اللعبة. المطلوب تقريبًا ${formatGb(requiredGb)} والمتاح ${formatGb(freeGb)}."
                    statusText = "الحالة: أفرغ مساحة ثم اضغط (تثبيت الكل) للإكمال"
                    appendLog("مساحة غير كافية عند اللعبة: ${entry.folder.name}")
                    resumeIndex = i
                    isInstalling = false
                    progressLabel = "متوقف بسبب المساحة"
                    return
                }

                installPhase = transitionInstallPhase(
                    installPhase,
                    InstallPhaseTransition.STORAGE_AVAILABLE
                )
                progressLabel = "تثبيت APK: ${entry.apk.name}"
                statusText = "الحالة: جاري تثبيت ${i + 1} / ${queue.size}"
                progressInfo = InstallProgressState(
                    measurable = false,
                    currentItem = entry.apk.name,
                    completed = i + 1,
                    total = queue.size,
                    gameName = entry.folder.name,
                    phase = GameInstallPhase.INSTALLING_APK
                )

                appendLog("----------------------------------------------")
                appendLog("لعبة: ${entry.folder.name}")
                appendLog("APK: ${entry.apk.name}")

                val installRes = withContext(Dispatchers.IO) {
                    adb.installApk(s2, entry.apk)
                }
                appendLog(
                    installRes.out.trim().ifBlank { "تثبيت APK: (بدون مخرجات)" },
                    installRes.out,
                    installRes.err
                )
                if (installRes.exit != 0) {
                    installPhase = GameInstallPhase.FAILED
                    val raw = boundedRawCommandOutput(installRes.out, installRes.err)
                    warningText = "${translateAdbFailure(raw)}\n\n$raw"
                    statusText = "الحالة: فشل"
                    resumeIndex = i
                    isInstalling = false
                    progressLabel = "فشل عند APK"
                    return
                }

                setProgressWithinGame(0.25f)

                if (entry.allowedObbFiles.isNotEmpty()) {
                    val totalObbBytes = withContext(Dispatchers.IO) {
                        entry.allowedObbFiles.sumOf { it.sizeBytes }.coerceAtLeast(1L)
                    }
                    var copiedObbBytes = 0L
                    val obbTargetRoot = entry.allowedObbFiles
                        .first()
                        .remoteDirectory
                    val mkdirResult = withContext(Dispatchers.IO) {
                        adb.shell(s2, "mkdir", "-p", obbTargetRoot)
                    }
                    if (mkdirResult.exit != 0) {
                        installPhase = GameInstallPhase.FAILED
                        warningText = "تعذر إنشاء مجلد OBB للعبة."
                        statusText = "الحالة: فشل نسخ OBB"
                        appendLog("فشل إنشاء مجلد OBB", mkdirResult.out, mkdirResult.err)
                        resumeIndex = i
                        isInstalling = false
                        return
                    }
                    for (allowed in entry.allowedObbFiles) {
                        val obbFile = withContext(Dispatchers.IO) {
                            revalidateAllowedObbFile(entry.folder, allowed)
                        }
                        if (obbFile == null) {
                            installPhase = GameInstallPhase.FAILED
                            warningText = "تغيّر ملف OBB أثناء التثبيت: ${allowed.relativePath}. أعد فحص مجلد اللعبة."
                            statusText = "الحالة: فشل نسخ OBB"
                            appendLog("تم إيقاف التثبيت لأن ملف OBB تغيّر: ${allowed.relativePath}")
                            resumeIndex = i
                            isInstalling = false
                            return
                        }
                        installPhase = GameInstallPhase.TRANSFERRING_OBB
                        progressLabel = "نسخ ملف OBB: ${allowed.file.name}"
                        appendLog("نسخ OBB: ${allowed.relativePath}")
                        val target = "${allowed.remoteDirectory}/${allowed.remoteFileName}"

                        val pushRes = withContext(Dispatchers.IO) {
                            adb.pushWithProgress(s2, obbFile, target) { copied, _ ->
                            // تحديث نسبة حقيقية حسب حجم ملفات OBB
                            val overall = (copiedObbBytes + copied).coerceAtMost(totalObbBytes)
                            val frac = overall.toFloat() / totalObbBytes.toFloat()
                            setProgressWithinGame(
                                p = 0.25f + 0.70f * frac,
                                item = "OBB: ${allowed.file.name}"
                            )
                            }
                        }

                        appendLog(
                            pushRes.out.trim().ifBlank { "نسخ OBB: (بدون مخرجات)" },
                            pushRes.out,
                            pushRes.err
                        )

                        if (pushRes.exit != 0) {
                            val combined = (pushRes.out + "\n" + pushRes.err).lowercase()
                            if (combined.contains("no space left")) {
                                installPhase = GameInstallPhase.PAUSED
                                val raw = boundedRawCommandOutput(pushRes.out, pushRes.err)
                                warningText = "${translateAdbFailure(raw)}\n\n$raw"
                                statusText = "الحالة: متوقف بسبب المساحة"
                                resumeIndex = i
                                isInstalling = false
                                progressLabel = "متوقف بسبب المساحة"
                                return
                            }
                            installPhase = GameInstallPhase.FAILED
                            val raw = boundedRawCommandOutput(pushRes.out, pushRes.err)
                            warningText = "${translateAdbFailure(raw)}\n\n$raw"
                            statusText = "الحالة: فشل"
                            resumeIndex = i
                            isInstalling = false
                            progressLabel = "فشل عند OBB"
                            return
                        }

                        // نجاح النسخ — ثبّت التقدم لهذه اللعبة
                        copiedObbBytes = (copiedObbBytes + allowed.sizeBytes).coerceAtMost(totalObbBytes)
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
                progress = (i.toFloat() / queue.size.toFloat()).coerceAtMost(0.95f)
                progressInfo = InstallProgressState(
                    measurable = true,
                    currentItem = entry.folder.name,
                    completed = i,
                    total = queue.size,
                    phase = installPhase,
                    percentOverride = (progress * 100f).roundToInt()
                )
            }

            installPhase = transitionInstallPhase(
                installPhase,
                InstallPhaseTransition.BEGIN_VERIFICATION
            )
            progress = 0.98f
            progressInfo = InstallProgressState(
                measurable = true,
                currentItem = "التحقق من الملفات",
                completed = queue.size,
                total = queue.size,
                phase = GameInstallPhase.VERIFYING,
                percentOverride = 98
            )
            progressLabel = "التحقق من التثبيت والملفات..."
            val verifySerial = getAuthorizedSerialOrNull()
            if (verifySerial == null) {
                installPhase = GameInstallPhase.PAUSED
                warningText = "انقطع اتصال النظارة قبل التحقق. أعد توصيلها ثم اضغط (تثبيت الكل) للإكمال."
                statusText = "الحالة: توقف مؤقت — التحقق مطلوب"
                return
            }
            for (entry in queue) {
                val packageName = entry.packageName
                if (packageName.isNullOrBlank()) {
                    installPhase = transitionInstallPhase(
                        installPhase,
                        InstallPhaseTransition.FAILED
                    )
                    warningText = "تعذر التحقق من package الخاص بـ ${entry.apk.name}."
                    statusText = "الحالة: فشل التحقق"
                    return
                }
                val packageCheck = withContext(Dispatchers.IO) {
                    adb.shell(verifySerial, "pm", "path", packageName)
                }
                val packagePresent = packageCheck.exit == 0 &&
                    packageCheck.out.lineSequence().any { it.trim().startsWith("package:") }
                appendLog(
                    "التحقق من ${entry.folder.name} ($packageName): ${if (packagePresent) "تم" else "فشل"}",
                    packageCheck.out,
                    packageCheck.err
                )
                if (!packagePresent) {
                    installPhase = GameInstallPhase.FAILED
                    val raw = boundedRawCommandOutput(packageCheck.out, packageCheck.err)
                    warningText = "${translateAdbFailure(raw)}\n\n$raw"
                    statusText = "الحالة: فشل التحقق"
                    return
                }
                val expected = entry.allowedObbFiles.map { allowed ->
                    ExpectedObbFile(
                        remotePath = "${allowed.remoteDirectory}/${allowed.remoteFileName}",
                        sizeBytes = allowed.sizeBytes
                    )
                }
                if (entry.allowedObbFiles.isNotEmpty()) {
                    val actual = expected.mapNotNull { expectedFile ->
                        val check = withContext(Dispatchers.IO) {
                            adb.shell(verifySerial, "stat", "-c", "%s", expectedFile.remotePath)
                        }
                        check.out.trim().lines().lastOrNull()?.trim()?.toLongOrNull()?.let {
                            ActualObbFile(expectedFile.remotePath, it)
                        }
                    }
                    val verification = verifyExpectedObbFiles(expected, actual)
                    if (!verification.valid) {
                        installPhase = GameInstallPhase.FAILED
                        warningText = "تعذر التحقق من ملفات OBB للعبة ${entry.folder.name}.\n" +
                            "مفقود: ${verification.missing.joinToString(", ")}\n" +
                            "بحجم غير صحيح: ${verification.mismatched.joinToString(", ")}"
                        statusText = "الحالة: فشل التحقق"
                        return
                    }
                }
            }
            installPhase = transitionInstallPhase(
                installPhase,
                InstallPhaseTransition.VERIFIED
            )
            progress = 1f
            progressLabel = "اكتمل التثبيت"
            progressInfo = InstallProgressState(
                measurable = true,
                currentItem = "اكتمل التثبيت",
                completed = queue.size,
                total = queue.size,
                phase = GameInstallPhase.COMPLETED,
                percentOverride = 100
            )
            statusText = "الحالة: تم بنجاح"
            appendLog("==============================================")
            appendLog("اكتمل التثبيت بنجاح")
            appendLog("==============================================")

            withContext(Dispatchers.IO) { adb.killServer() }

            warningText = null
            showRestart = true
        } catch (e: Throwable) {
            SupportReporter.record(
                title = "خطأ أثناء التثبيت",
                details = e.stackTraceToString(),
                context = progressLabel
            )
            installPhase = GameInstallPhase.FAILED
            warningText = "${safeUiMsg(e)}\n\n${translateAdbFailure(e.message.orEmpty())}"
            statusText = "الحالة: تعذر الإكمال"
        } finally {
            isInstalling = false
            installMutex.unlock()
        }
    }

    fun restartQuestNow() {
        uiScope.launch {
            try {
                val serial = getAuthorizedSerialOrNull()
                if (serial == null) {
                    appendLog("تعذر إعادة تشغيل النظارة: لا يوجد جهاز مصرح.")
                    return@launch
                }
                installPhase = GameInstallPhase.RESTARTING
                statusText = "الحالة: جاري إعادة تشغيل النظارة..."
                progressLabel = "انتظار عودة اتصال ADB..."
                progressInfo = InstallProgressState(
                    measurable = false,
                    currentItem = "إعادة تشغيل النظارة",
                    completed = 0,
                    total = null,
                    phase = GameInstallPhase.RESTARTING
                )
                val result = withContext(Dispatchers.IO) { adb.reboot(serial) }
                appendLog("ADB reboot", result.out, result.err)
                if (result.exit != 0) {
                    val raw = boundedRawCommandOutput(result.out, result.err)
                    warningText = "${translateAdbFailure(raw)}\n\n$raw"
                    statusText = "الحالة: فشل إعادة التشغيل"
                    installPhase = GameInstallPhase.FAILED
                    return@launch
                }
                repeat(40) {
                    delay(1500)
                    if (getAuthorizedSerialOrNull() != null) {
                        hasAuthorizedDevice = true
                        statusText = "الحالة: عادت النظارة بعد إعادة التشغيل"
                        progressLabel = "جاهز"
                        installPhase = GameInstallPhase.IDLE
                        progressInfo = InstallProgressState(false, null, 0, null)
                        return@launch
                    }
                }
                statusText = "الحالة: ما زلنا ننتظر عودة النظارة"
                warningText = "تم إرسال أمر إعادة التشغيل، لكن النظارة لم تعد خلال المهلة. سيستمر الفحص تلقائيًا."
            } catch (e: Throwable) {
                warningText = "${safeUiMsg(e)}\n\n${translateAdbFailure(e.message.orEmpty())}"
                statusText = "الحالة: تعذر متابعة إعادة التشغيل"
                installPhase = GameInstallPhase.FAILED
            }
        }
    }

    suspend fun installSelectedMod() {
        if (!modOperationMutex.tryLock()) {
            appendModLog("هناك عملية مود أخرى قيد التنفيذ.")
            return
        }
        var operationGeneration: Long? = null
        try {
            val file = modZipFile
            val analysis = modAnalysis
            val app = selectedApp
            if (file == null || analysis == null || app == null) {
                appendModLog("اختر الحزمة ولعبة مثبتة وحللها قبل التثبيت.")
                return
            }
            if (!analysis.installPlan.installable || analysis.installPlan.hasBlockingPreconditions) {
                appendModLog("تم إيقاف التثبيت: الخطة غير متوافقة أو غير آمنة.")
                return
            }
            val requestGeneration = modOperationGeneration
            withContext(Dispatchers.IO) { adb.startServer() }
            val serial = getAuthorizedSerialOrNull()
            if (serial == null) {
                appendModLog("لا يوجد جهاز متصل أو مصرح به")
                return
            }
            if (requestGeneration != modOperationGeneration ||
                !sameModFileSnapshot(modZipFile, file) ||
                !sameInstalledAppSnapshot(selectedApp, app)
            ) {
                appendModLog("تم إيقاف التثبيت لأن اختيار الحزمة أو اللعبة تغيّر.")
                return
            }
            if (connectedDeviceSerial != null && connectedDeviceSerial != serial) {
                appendModLog("تغيّر جهاز Quest أثناء تجهيز التثبيت. أعد التحليل.")
                return
            }
            connectedDeviceSerial = serial
            val generation = ++modOperationGeneration
            operationGeneration = generation
            isInstallingMod = true
            modInstallDeviceLost = false
            modExecutionProgress = ModsManager.ModExecutionProgress(
                ModsManager.ModInstallPhase.PREPARING,
                null,
                "تنفيذ خطة تثبيت المود"
            )
            appendModLog("==============================================")
            appendModLog("بدء تنفيذ الخطة المعتمدة: ${file.name}")
            appendModLog("==============================================")
            if (!modOperationStillCurrent(generation, file, app, serial)) {
                appendModLog("تم إيقاف التثبيت لأن اختيار اللعبة أو الحزمة أو الجهاز تغيّر.")
                return
            }
            val installResult = withContext(Dispatchers.IO) {
                modsManager.executeInstallPlan(
                    serial = serial,
                    zipFile = file,
                    plan = analysis.installPlan
                ) { update ->
                    uiScope.launch {
                        if (generation == modOperationGeneration) {
                            modExecutionProgress = update
                        }
                    }
                }
            }
            val currentSerial = getAuthorizedSerialOrNull()
            if (currentSerial != serial ||
                !modOperationStillCurrent(generation, file, app, serial)
            ) {
                if (modInstallDeviceLost) {
                    appendModLog("انتهى النقل بعد فقد الاتصال؛ لم يتم اعتماد نتيجته.")
                    warningText = "فقد الاتصال أثناء نقل المود. أعد توصيل النظارة ثم أعد التحليل والتثبيت."
                    statusText = "الحالة: لم يتم اعتماد التثبيت"
                    modExecutionProgress = ModsManager.ModExecutionProgress(
                        ModsManager.ModInstallPhase.FAILED,
                        null,
                        "فقد الاتصال أثناء النقل؛ أعد تشغيل التثبيت بعد إعادة التحليل."
                    )
                } else {
                    appendModLog("تم تجاهل نتيجة تثبيت قديمة بعد تغيّر الحزمة أو اللعبة أو الجهاز.")
                }
                return
            }
            if (installResult.success) {
                appendModLog(installResult.message)
                appendModLog("==============================================")
                appendModLog("تم تثبيت المود والتحقق من الملفات بنجاح")
                appendModLog("==============================================")
            } else {
                appendModLog("فشل التثبيت: ${installResult.message}")
                modExecutionProgress = ModsManager.ModExecutionProgress(
                    ModsManager.ModInstallPhase.FAILED,
                    null,
                    installResult.message
                )
            }
        } catch (e: Exception) {
            if (operationGeneration == null || operationGeneration == modOperationGeneration) {
                appendModLog("خطأ غير متوقع: ${e.message}")
                modExecutionProgress = ModsManager.ModExecutionProgress(
                    ModsManager.ModInstallPhase.FAILED,
                    null,
                    "تعذر إكمال التثبيت"
                )
            } else {
                appendModLog("تم تجاهل خطأ من عملية تثبيت قديمة.")
            }
        } finally {
            isInstallingMod = false
            modOperationMutex.unlock()
        }
    }

    LaunchedEffect(logText) { logScroll.animateScrollTo(logScroll.maxValue) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { NfvrPathPreferences.load() }
        savedPaths = loaded
        folders = loaded.gameFolders.map(::File)
        lastModArchiveDirectory = loaded.modArchiveDirectory?.let(::File)
        rebuildQueueOnIo()
    }

    LaunchedEffect(Unit) {
        var lastScannedSerial: String? = null
        while (isActive) {
            refreshDeviceInfo()
            val serial = connectedDeviceSerial
            if (serial == null) {
                lastScannedSerial = null
            } else if (serial != lastScannedSerial) {
                lastScannedSerial = serial
                startInstalledAppsScan()
            }
            delay(2000)
        }
    }

    Window(
        onCloseRequest = {
            hardExitApp { bundledAdb.cleanupOwnedResources() }
        },
        title = "Near FutureVR - مثبت ألعاب Meta Quest",
        icon = painterResource("nfvr_logo.png")
    ) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            NfvrTheme {
                val pageScroll = rememberScrollState()
                val focusManager = LocalFocusManager.current
                DisposableEffect(focusManager) {
                    val registration = focusClearRegistry.register {
                        focusManager.clearFocus(force = true)
                    }
                    onDispose { registration.close() }
                }
                val fingerprint = remember { LicenseManager.fingerprint() }
                val licenseState = remember { mutableStateOf(LocalLicenseStore.load(fingerprint)) }
                val showSettings = remember { mutableStateOf(false) }

                Column(
                    // The mods workflow owns its LazyColumn scroll.  Applying
                    // another scroll container around it causes a nested
                    // scroll measurement crash on Compose Desktop.
                    modifier = Modifier.fillMaxSize().padding(16.dp).then(
                        if (selectedTab == 1) Modifier else Modifier.verticalScroll(pageScroll)
                    ),
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
                                    "الإصدار: ${AppInfo.version}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(Modifier.weight(1f))

                            IconButton(onClick = {
                               focusTransition(
                                   clearFocus = { focusManager.clearFocus(force = true) },
                                   transition = { showSettings.value = true }
                               )
                            }) {
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
                            if (deviceGuidance == DeviceGuidanceState.AUTHORIZED) {
                                Text(osText, style = MaterialTheme.typography.bodySmall)
                                Text(batteryText, style = MaterialTheme.typography.bodySmall)
                            } else {
                                val guidance = when (deviceGuidance) {
                                    DeviceGuidanceState.NO_DEVICE ->
                                        "لا يوجد جهاز. استخدم كابل USB بيانات وافتح قفل النظارة ثم أعد الفحص."
                                    DeviceGuidanceState.UNAUTHORIZED ->
                                        "الجهاز غير مصرح. وافق على USB Debugging داخل النظارة واختر Always allow."
                                    DeviceGuidanceState.OFFLINE ->
                                        "الجهاز غير متصل. افتح قفل النظارة وافصل السلك ثم أعد توصيله."
                                    DeviceGuidanceState.AUTHORIZED -> ""
                                }
                                Text(guidance, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            openUrl("https://developers.meta.com/horizon/documentation/native/android/mobile-device-setup/")
                                        }
                                    ) { Text("فتح دليل USB") }
                                    OutlinedButton(
                                        onClick = { uiScope.launch { refreshDeviceInfo() } }
                                    ) { Text("إعادة فحص الاتصال") }
                                }
                            }
                        }
                    }

                    // SETTINGS (LICENSE)
                    if (showSettings.value) {
                        DialogWindow(
                            onCloseRequest = {
                                focusTransition(
                                    clearFocus = { focusManager.clearFocus(force = true) },
                                    transition = { showSettings.value = false }
                                )
                            },
                            title = "الإعدادات",
                            state = rememberDialogState(size = DpSize(560.dp, 720.dp)),
                            resizable = false
                        ) {
                            Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                                val settingsScroll = rememberScrollState()
                                var revealLicenseKey by remember { mutableStateOf(false) }
                                Column(
                                    modifier = Modifier.padding(16.dp).verticalScroll(settingsScroll),
                                    verticalArrangement = Arrangement.spacedBy(9.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("الإعدادات", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.weight(1f))
                                        OutlinedButton(onClick = {
                                            focusTransition(
                                                clearFocus = { focusManager.clearFocus(force = true) },
                                                transition = { showSettings.value = false }
                                            )
                                        }) { Text("إغلاق") }
                                    }

                                    Divider()

                                    Text("عام", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "وضع داكن عالي التباين • اتجاه RTL • بيانات التطبيق محفوظة في مجلد مستخدم Windows.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Divider()

                                    Text("التحديثات", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    var updateStatus by remember { mutableStateOf<String?>(null) }
                                    var checkingUpdate by remember { mutableStateOf(false) }
                                    val updateScope = rememberCoroutineScope()
                                    Text("الإصدار الحالي: ${AppInfo.version}", style = MaterialTheme.typography.bodySmall)
                                    Button(
                                        enabled = !checkingUpdate,
                                        onClick = {
                                            checkingUpdate = true
                                            updateScope.launch {
                                                val message = withContext(Dispatchers.IO) {
                                                    when (val result = UpdateManager.check()) {
                                                    UpdateCheckResult.Unavailable -> "خدمة التحديث غير مهيأة حاليًا."
                                                    UpdateCheckResult.Current -> "لديك أحدث إصدار."
                                                    is UpdateCheckResult.Available ->
                                                        if (result.effectiveMandatory) {
                                                            "يتوفر تحديث مطلوب إلى الإصدار ${result.metadata.latestVersion}. سيتم تفعيل التنزيل بعد اعتماد خدمة التحديث."
                                                        } else {
                                                            "يتوفر الإصدار ${result.metadata.latestVersion}. سيتم تفعيل التنزيل بعد اعتماد خدمة التحديث."
                                                        }
                                                    is UpdateCheckResult.Error -> result.message
                                                    }
                                                }
                                                updateStatus = message
                                                checkingUpdate = false
                                            }
                                        }
                                    ) {
                                        Text(if (checkingUpdate) "جاري الفحص..." else "فحص التحديثات")
                                    }
                                    if (!updateStatus.isNullOrBlank()) {
                                        Text(updateStatus!!, style = MaterialTheme.typography.bodySmall)
                                    }

                                    Divider()

                                    Text("الترخيص", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                                    val fp = licenseState.value.fingerprint
                                    val boundKey = licenseState.value.boundLicenseKey

                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        shape = MaterialTheme.shapes.small
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(
                                                "الحالة: ${if (licenseState.value.isActivated) "مفعل" else "غير مفعل"}",
                                                fontWeight = FontWeight.SemiBold,
                                                modifier = Modifier.padding(start = 12.dp, top = 10.dp, end = 12.dp)
                                            )
                                            SelectionContainer {
                                                Text("بصمة الجهاز: $fp", style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.padding(horizontal = 12.dp))
                                            }
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    "المفتاح المرتبط: ${
                                                        if (revealLicenseKey) (boundKey ?: "—") else maskLicenseKey(boundKey)
                                                    }",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                TextButton(onClick = { revealLicenseKey = !revealLicenseKey }) {
                                                    Text(if (revealLicenseKey) "إخفاء" else "إظهار")
                                                }
                                            }

                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
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
Text("التشخيص والدعم", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
    Text(
        "لن يتم إرسال مفتاح الترخيص الكامل. يتضمن التقرير معلومات النظام وحالة الاتصال وسجلًا تشخيصيًا حديثًا بعد حجب المعرّفات المباشرة.",
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
                deviceHash = fp,
                diagnostics = buildString {
                    appendLine("connection=$connectionText")
                    appendLine("device=$deviceText")
                    appendLine("adb_state=${if (hasAuthorizedDevice) "authorized" else "not_authorized"}")
                    appendLine("operation=$statusText")
                    append(DiagnosticLogger.recent())
                }
            )
            scopeReport.launch {
                val result = withContext(Dispatchers.IO) {
                    SupportReporter.send(SUPPORT_REPORT_URL, payloadRaw)
                }
                sendingReport = false
                reportStatus = if (result.first) "تم إرسال التقرير بنجاح." else "فشل إرسال التقرير: ${result.second}"
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
                                            enabled = !isActivating,
                                            visualTransformation = PasswordVisualTransformation()
                                        )

                                        Button(
                                            onClick = {
                                                if (licenseKeyInput.trim().isBlank()) {
                                                    activationUiMsg = "أدخل مفتاح الترخيص"
                                                    return@Button
                                                }

                                                isActivating = true
                                                activationUiMsg = "جاري التفعيل..."

                                                uiScope.launch {
                                                    withContext(Dispatchers.IO) {
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

                                                             uiScope.launch {
                                                                if (ok) {
                                                                    activationUiMsg = "تم التفعيل بنجاح"
                                                                    licenseState.value = LocalLicenseStore.load(deviceHash)
                                                                    focusManager.clearFocus(force = true)
                                                                    showSettings.value = false
                                                                } else {
                                                                    activationUiMsg = "فشل حفظ التفعيل محليًا: ${msg ?: "غير معروف"}"
                                                                }
                                                                isActivating = false
                                                            }
                                                        } else {
                                                             uiScope.launch {
                                                                activationUiMsg = "فشل: ${result.message}"
                                                                isActivating = false
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                         uiScope.launch {
                                                            activationUiMsg = "فشل: ${e.message ?: "خطأ غير معروف"}"
                                                            isActivating = false
                                                        }
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
                                    Divider()
                                    Text("حول", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "NFVR Quest Installer — Near FutureVR\nالإصدار ${AppInfo.version}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
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
                                    enabled = !isActivating,
                                    visualTransformation = PasswordVisualTransformation()
                                )

                                Button(
                                    onClick = {
                                        focusTransition(
                                            clearFocus = { focusManager.clearFocus(force = true) },
                                            transition = { showSettings.value = true }
                                        )
                                    },
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
                            onClick = {
                                focusTransition(
                                    clearFocus = { focusManager.clearFocus(force = true) },
                                    transition = { selectedTab = 0 }
                                )
                            },
                            text = { Text("تثبيت الألعاب") },
                            icon = { Icon(Icons.Default.Settings, contentDescription = null) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = {
                                focusTransition(
                                    clearFocus = { focusManager.clearFocus(force = true) },
                                    transition = { selectedTab = 1 }
                                )
                            },
                            text = { Text("المودات") },
                            icon = { Icon(Icons.Default.Build, contentDescription = null) }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = {
                                focusTransition(
                                    clearFocus = { focusManager.clearFocus(force = true) },
                                    transition = { selectedTab = 2 }
                                )
                            },
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
                                                    focusManager.clearFocus(force = true)
                                                    uiScope.launch {
                                                        when (val chooserResult = WindowsIsolatedPicker.chooseFolder(
                                                            savedPaths.lastGameFolder?.let(::File)
                                                                ?: folders.lastOrNull()?.parentFile
                                                        )) {
                                                            is DesktopChooserResult.Selected -> {
                                                                val selected = chooserResult.file
                                                                val inspection = withContext(Dispatchers.IO) {
                                                                    inspectGameFolder(selected)
                                                                }
                                                                if (!inspection.installable || inspection.apk == null) {
                                                                    warningText = inspection.reason
                                                                        ?: "مجلد اللعبة لا يحتوي بنية قابلة للتثبيت."
                                                                    appendLog(
                                                                        "تم رفض مجلد اللعبة ${selected.name}: " +
                                                                            (inspection.reason ?: "بنية غير مدعومة")
                                                                    )
                                                                    return@launch
                                                                }
                                                                folders = (folders + inspection.folder).distinctBy { it.absolutePath }
                                                                savedPaths = savedPaths.copy(
                                                                    gameFolders = folders.map { it.absolutePath },
                                                                    lastGameFolder = inspection.folder.absolutePath
                                                                )
                                                                withContext(Dispatchers.IO) {
                                                                    NfvrPathPreferences.saveGameFolders(folders)
                                                                }
                                                                rebuildQueueOnIo()
                                                                warningText = null
                                                                appendLog("تمت إضافة مجلد اللعبة: ${selected.name}")
                                                            }
                                                            is DesktopChooserResult.Failed -> {
                                                                warningText = chooserResult.userMessage
                                                                appendLog("فشل اختيار مجلد اللعبة: ${chooserResult.technicalMessage}")
                                                            }
                                                            DesktopChooserResult.Busy ->
                                                                warningText = "نافذة اختيار أخرى مفتوحة. أغلقها ثم أعد المحاولة."
                                                            DesktopChooserResult.Cancelled -> Unit
                                                        }
                                                    }
                                                },
                                                enabled = hasAuthorizedDevice && !isInstalling
                                            ) { Text("إضافة مجلد") }

                                            Button(
                                                onClick = {
                                                    if (!requireDeviceOrWarn(::appendLog)) return@Button
                                                    uiScope.launch { installQueue() }
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
                                            // IDLE/paused/failed states stay still.  The
                                            // indeterminate animation is reserved for
                                            // genuinely active work with no measurable
                                            // byte count (preflight, APK install, restart).
                                            val indeterminate = isInstalling && (
                                                installPhase == GameInstallPhase.PREFLIGHT ||
                                                    installPhase == GameInstallPhase.INSTALLING_APK ||
                                                    installPhase == GameInstallPhase.RESTARTING
                                                )
                                            if (indeterminate) {
                                                LinearProgressIndicator(
                                                    modifier = Modifier.weight(1f).height(10.dp),
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            } else {
                                                LinearProgressIndicator(
                                                    progress = { progress.coerceIn(0f, 1f) },
                                                    modifier = Modifier.weight(1f).height(10.dp),
                                                    color = when (installPhase) {
                                                        GameInstallPhase.COMPLETED -> MaterialTheme.colorScheme.tertiary
                                                        GameInstallPhase.FAILED -> MaterialTheme.colorScheme.error
                                                        else -> MaterialTheme.colorScheme.primary
                                                    }
                                                )
                                            }
                                            Spacer(Modifier.width(10.dp))
                                            Text(
                                                text = formatInstallProgress(progressInfo),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }

                                        Text(progressLabel, style = MaterialTheme.typography.bodyMedium)

                                        if (!warningText.isNullOrBlank()) {
                                            Box(
                                                modifier = Modifier.fillMaxWidth()
                                                     .background(MaterialTheme.colorScheme.errorContainer)
                                                    .padding(10.dp)
                                            ) {
                                                Text(
                                                    warningText!!,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                     color = MaterialTheme.colorScheme.onErrorContainer
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
                                                            focusManager.clearFocus(force = true)
                                                            val target = g.folder.absolutePath
                                                             folders = folders.filterNot { it.absolutePath == target }
                                                            uiScope.launch {
                                                                withContext(Dispatchers.IO) {
                                                                    NfvrPathPreferences.saveGameFolders(folders)
                                                                }
                                                                rebuildQueueOnIo()
                                                            }
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
                                     Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                         Row(
                                             modifier = Modifier.fillMaxWidth(),
                                             verticalAlignment = Alignment.CenterVertically
                                         ) {
                                             Column(Modifier.weight(1f)) {
                                                 Text("سجل تثبيت الألعاب", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                                 Text(
                                                     "نشاط مباشر مع الوقت والمرحلة وسبب الفشل عند توفره",
                                                     style = MaterialTheme.typography.bodySmall,
                                                     color = MaterialTheme.colorScheme.onSurfaceVariant
                                                 )
                                             }
                                             OutlinedButton(
                                                 enabled = activityEvents.isNotEmpty(),
                                                 onClick = {
                                                     val details = activityEvents.joinToString("\n") {
                                                         "[${it.timestamp.substringAfter('T').take(8)}] ${it.phase}: ${it.message}" +
                                                             listOf(it.stdout, it.stderr)
                                                                 .filter(String::isNotBlank)
                                                                 .joinToString("\n", prefix = "\n")
                                                     }
                                                     copyToClipboard(details)
                                                 }
                                             ) { Text("نسخ التفاصيل") }
                                             Spacer(Modifier.width(8.dp))
                                             OutlinedButton(
                                                 enabled = !isInstalling && activityEvents.isNotEmpty(),
                                                 onClick = {
                                                     activityHistory.clear()
                                                     activityEvents = emptyList()
                                                     logText = ""
                                                     ActivityHistoryStore.clear()
                                                 }
                                             ) { Text("مسح السجل") }
                                         }
                                        Spacer(Modifier.height(8.dp))
                                        Box(
                                            modifier = Modifier.fillMaxWidth().height(260.dp)
                                                 .background(MaterialTheme.colorScheme.background)
                                                .padding(10.dp)
                                        ) {
                                             if (activityEvents.isEmpty()) {
                                                 Text(
                                                     "لا يوجد نشاط بعد. ستظهر خطوات الفحص والنقل والتثبيت هنا.",
                                                     style = MaterialTheme.typography.bodyMedium,
                                                     color = MaterialTheme.colorScheme.onSurfaceVariant
                                                 )
                                             } else {
                                                 Column(
                                                     modifier = Modifier.fillMaxWidth().verticalScroll(logScroll),
                                                     verticalArrangement = Arrangement.spacedBy(6.dp)
                                                 ) {
                                                     activityEvents.forEach { event ->
                                                         val accent = when (event.phase) {
                                                             GameInstallPhase.COMPLETED -> MaterialTheme.colorScheme.tertiary
                                                             GameInstallPhase.FAILED -> MaterialTheme.colorScheme.error
                                                             GameInstallPhase.PAUSED -> Color(0xFFFFC857)
                                                             else -> MaterialTheme.colorScheme.primary
                                                         }
                                                         Row(
                                                             modifier = Modifier.fillMaxWidth()
                                                                 .background(MaterialTheme.colorScheme.surfaceVariant)
                                                                 .padding(horizontal = 10.dp, vertical = 8.dp),
                                                             horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                         ) {
                                                             Text(
                                                                 event.timestamp.substringAfter('T').take(8),
                                                                 style = MaterialTheme.typography.labelSmall,
                                                                 color = accent
                                                             )
                                                             Column(Modifier.weight(1f)) {
                                                                 Text(
                                                                     event.message,
                                                                     style = MaterialTheme.typography.bodySmall,
                                                                     color = MaterialTheme.colorScheme.onSurface
                                                                 )
                                                                 if (event.stderr.isNotBlank()) {
                                                                     Text(
                                                                         event.stderr.take(500),
                                                                         style = MaterialTheme.typography.labelSmall,
                                                                         color = MaterialTheme.colorScheme.error
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

                            1 -> {
                                ModsWorkflowUi(
                                    connected = hasAuthorizedDevice,
                                    installedApps = installedApps,
                                    scanning = scanningApps,
                                    searchFilter = appSearch,
                                    onSearchFilterChange = { appSearch = it },
                                    selectedApp = selectedApp,
                                    onRefresh = {
                                        startInstalledAppsScan()
                                    },
                                    onCancelScan = {
                                       focusManager.clearFocus(force = true)
                                        cancelInstalledAppsScan()
                                    },
                                     onCancelActiveScanOnSelection = {
                                       focusManager.clearFocus(force = true)
                                         cancelInstalledAppsScan(invalidatePendingCallbacks = true)
                                     },
                                    scanProgress = appScanProgress,
                                    showAllApps = showAllQuestApps,
                                    onShowAllAppsChange = { value ->
                                        if (value != showAllQuestApps && !isInstallingMod) {
                                            focusManager.clearFocus(force = true)
                                            showAllQuestApps = value
                                            startInstalledAppsScan()
                                        } else if (isInstallingMod && value != showAllQuestApps) {
                                            appendModLog("لا يمكن تغيير اللعبة أثناء تثبيت المود.")
                                        }
                                    },
                                    onSelectApp = { app ->
                                        if (isInstallingMod) {
                                            appendModLog("لا يمكن تغيير اللعبة أثناء تثبيت المود.")
                                        } else {
                                            focusManager.clearFocus(force = true)
                                            invalidateModOperation()
                                            selectedApp = app
                                        }
                                    },
                                     onChangeSelectedApp = {
                                         // Keep the cached Quest app snapshot and search query while
                                         // reopening the picker.  The selected target is no longer
                                         // valid for the reviewed package, but the package itself
                                         // can be reused for the next game.
                                         if (isInstallingMod) {
                                             appendModLog("لا يمكن تغيير اللعبة أثناء تثبيت المود.")
                                         } else {
                                             focusManager.clearFocus(force = true)
                                             invalidateModOperation()
                                             selectedApp = null
                                         }
                                     },
                                     onClearSelectedApp = {
                                         // "مسح" starts the mod workflow over, without forcing a
                                         // rescan or losing the cached app list/search query.
                                         if (isInstallingMod) {
                                             appendModLog("لا يمكن تغيير اللعبة أو الحزمة أثناء تثبيت المود.")
                                         } else {
                                             focusManager.clearFocus(force = true)
                                             invalidateModOperation()
                                             selectedApp = null
                                             modZipFile = null
                                         }
                                     },
                                    selectedZipFilename = modZipFile?.name,
                                    onChooseFile = {
                                        if (isInstallingMod) {
                                            appendModLog("لا يمكن تغيير الحزمة أثناء تثبيت المود.")
                                        } else {
                                            focusManager.clearFocus(force = true)
                                            uiScope.launch {
                                            when (val chooserResult =
                                                WindowsIsolatedPicker.chooseZipFile(lastModArchiveDirectory)) {
                                                is DesktopChooserResult.Selected -> {
                                                    if (isInstallingMod) {
                                                        appendModLog("تم تجاهل اختيار حزمة وصل بعد بدء التثبيت.")
                                                    } else {
                                                        val file = chooserResult.file
                                                        invalidateModOperation()
                                                        modZipFile = file
                                                        lastModArchiveDirectory = file.parentFile
                                                        file.parentFile?.let { directory ->
                                                            withContext(Dispatchers.IO) {
                                                                NfvrPathPreferences.saveModArchiveDirectory(directory)
                                                            }
                                                        }
                                                        appendModLog("تم اختيار ملف المود: ${file.name}")
                                                    }
                                                }
                                                is DesktopChooserResult.Failed ->
                                                    appendModLog(chooserResult.userMessage)
                                                DesktopChooserResult.Busy ->
                                                    appendModLog("نافذة اختيار أخرى مفتوحة. أغلقها ثم أعد المحاولة.")
                                                DesktopChooserResult.Cancelled -> Unit
                                            }
                                            }
                                        }
                                    },
                                    analyzing = analyzingMod,
                                    analysis = modAnalysis,
                                    onAnalyze = {
                                        uiScope.launch { analyzeSelectedMod() }
                                    },
                                    installing = isInstallingMod,
                                    executionProgress = modExecutionProgress,
                                    onInstall = {
                                        uiScope.launch { installSelectedMod() }
                                    },
                                     onOpenExternalUrl = ::openUrl,
                                    logText = modLogText,
                                     modifier = Modifier.fillMaxWidth().weight(1f)
                                )
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
    } finally {
        instanceLock.close()
    }
}