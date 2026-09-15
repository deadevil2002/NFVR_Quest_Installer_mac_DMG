import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.time.Instant
import java.util.zip.ZipFile

/**
 * Small, side-effect-free pieces of the desktop workflow.  Keeping these
 * decisions outside Compose makes the Windows client deterministic and easy to
 * test without a Quest attached.
 */
enum class AdbDeviceState {
    DEVICE,
    OFFLINE,
    UNAUTHORIZED,
    UNKNOWN
}

data class QuestBatteryInfo(
    val percentage: Int?,
    val charging: Boolean?,
    val source: String?
)

/**
 * Parses the small, stable portion of `dumpsys battery`.  Input is bounded so
 * an unexpectedly verbose shell response can never become a UI payload.
 */
fun parseQuestBatteryDump(raw: String, maxChars: Int = 16_384): QuestBatteryInfo {
    val text = raw.take(maxChars)
    fun value(name: String): String? =
        Regex("""(?im)^\s*$name\s*:\s*(.+?)\s*$""").find(text)?.groupValues?.getOrNull(1)?.trim()
    val level = value("level")?.toIntOrNull()?.coerceIn(0, 100)
    val status = value("status")?.lowercase()
    val ac = value("AC powered")?.equals("true", ignoreCase = true) == true
    val usb = value("USB powered")?.equals("true", ignoreCase = true) == true
    val wireless = value("Wireless powered")?.equals("true", ignoreCase = true) == true
    val charging = when {
        status == "charging" || ac || usb || wireless -> true
        status == "discharging" || status == "not charging" || status == "full" -> false
        else -> null
    }
    val source = when {
        ac -> "AC"
        usb -> "USB"
        wireless -> "لاسلكي"
        else -> null
    }
    return QuestBatteryInfo(level, charging, source)
}

fun safeQuestOsVersion(raw: String, maxChars: Int = 4_096): String? {
    val candidate = raw.take(maxChars).lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() && it.length <= 160 }
        ?.replace(Regex("""[\r\n\t]"""), " ")
        ?.trim()
    return candidate?.takeIf { it.matches(Regex("""[A-Za-z0-9 ._:/()\-+]+""")) }
}

enum class DeviceGuidanceState { AUTHORIZED, UNAUTHORIZED, OFFLINE, NO_DEVICE }

fun deviceGuidanceState(rows: List<AdbDeviceRow>, authorized: Boolean): DeviceGuidanceState =
    when {
        authorized -> DeviceGuidanceState.AUTHORIZED
        rows.isEmpty() -> DeviceGuidanceState.NO_DEVICE
        rows.any { it.state == AdbDeviceState.UNAUTHORIZED } -> DeviceGuidanceState.UNAUTHORIZED
        rows.any { it.state == AdbDeviceState.OFFLINE } -> DeviceGuidanceState.OFFLINE
        else -> DeviceGuidanceState.UNAUTHORIZED
    }

fun maskLicenseKey(key: String?): String {
    val value = key?.trim().orEmpty()
    if (value.isBlank()) return "—"
    if (value.length <= 8) return "•".repeat(value.length)
    return value.take(4) + "•".repeat((value.length - 8).coerceAtLeast(4)) + value.takeLast(4)
}

data class InstallProgressState(
    val measurable: Boolean,
    val currentItem: String?,
    val completed: Int,
    val total: Int?,
    /**
     * Queue/install context is deliberately part of the game progress model.
     * Mod execution has its own progress model and must never overwrite this.
     */
    val gameName: String? = null,
    val phase: GameInstallPhase? = null,
    val percentOverride: Int? = null
) {
    val percent: Int?
        get() = if (percentOverride != null && measurable) {
            percentOverride.coerceIn(0, 100)
        } else if (measurable && total != null && total > 0) {
            (completed.toDouble() / total * 100.0).toInt().coerceIn(0, 100)
        } else null
}

fun formatInstallProgress(state: InstallProgressState): String = buildString {
    state.gameName?.takeIf { it.isNotBlank() }?.let { append(it) }
    state.currentItem?.takeIf { it.isNotBlank() }?.let {
        if (isNotEmpty()) append(" — ")
        append(it)
    }
    if (state.total != null && state.total > 0) {
        if (isNotEmpty()) append(" — ")
        append("${state.completed.coerceIn(0, state.total)} / ${state.total}")
    }
    state.phase?.let {
        if (isNotEmpty()) append(" — ")
        append(it.name)
    }
    state.percent?.let {
        if (isNotEmpty()) append(" — ")
        append("$it%")
    }
    if (isEmpty()) append("جاهز")
}

/**
 * A chooser is a user-initiated, modal desktop operation.  Keeping the gate
 * as a tiny pure state holder makes the "one chooser at a time" rule
 * deterministic without coupling it to Swing or Compose.
 */
class DesktopChooserGate {
    private var open = false

    @Synchronized
    fun tryAcquire(): Boolean {
        if (open) return false
        open = true
        return true
    }

    @Synchronized
    fun release() {
        open = false
    }

    @Synchronized
    fun isOpen(): Boolean = open
}

enum class DesktopChooserMode {
    FILES,
    DIRECTORY
}

sealed interface DesktopChooserResult {
    data class Selected(val file: File) : DesktopChooserResult
    data object Cancelled : DesktopChooserResult
    data object Busy : DesktopChooserResult
    data class Failed(
        val userMessage: String,
        val technicalMessage: String
    ) : DesktopChooserResult
}

/**
 * OS file locking is per-user/session by virtue of living under user.home.
 * The channel remains open for the lifetime of the returned guard.
 */
class NfvrSingleInstanceLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    val file: File
) : AutoCloseable {
    @Volatile private var closed = false

    override fun close() {
        if (closed) return
        synchronized(this) {
            if (closed) return
            closed = true
            runCatching { lock.release() }
            runCatching { channel.close() }
        }
    }

    companion object {
        fun tryAcquire(file: File): NfvrSingleInstanceLock? {
            file.parentFile?.mkdirs()
            val channel = try {
                RandomAccessFile(file, "rw").channel
            } catch (_: Throwable) {
                return null
            }
            return try {
                val fileLock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (fileLock == null) {
                    channel.close()
                    null
                } else {
                    NfvrSingleInstanceLock(channel, fileLock, file)
                }
            } catch (_: Throwable) {
                runCatching { channel.close() }
                null
            }
        }

        fun forCurrentUser(): NfvrSingleInstanceLock? =
            tryAcquire(File(UserDataPaths.root, "nfvr-instance.lock"))
    }
}

data class AdbDeviceRow(
    val serial: String,
    val state: AdbDeviceState,
    val details: String = ""
)

data class AdbSelection(
    val rows: List<AdbDeviceRow>,
    val selectedSerial: String?,
    val message: String
)

/**
 * A scan is intentionally represented as a stream of immutable snapshots.
 * `total` is nullable because ADB may not have supplied the package list yet;
 * callers must not manufacture a percentage until it is known.
 */
data class InstalledAppsScanProgress(
    val apps: List<InstalledQuestApp>,
    val processed: Int,
    val total: Int?,
    val lastUpdatedMillis: Long = System.currentTimeMillis(),
    val completed: Boolean = false,
    val cancelled: Boolean = false,
    val error: String? = null
) {
    val percent: Int?
        get() = total?.takeIf { it > 0 }?.let {
            ((processed.toDouble() / it.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        }
}

/**
 * `pm list packages -3` already excludes most platform packages, but Meta's
 * store/runtime and a few OEM services can still be reported as third party.
 * Keep this list deliberately conservative: an unknown-source application is
 * retained unless it is clearly an internal platform/runtime package.
 */
fun isQuestInternalPackage(packageName: String): Boolean {
    val value = packageName.trim().lowercase()
    if (value.isBlank()) return false
    val exact = setOf(
        "android",
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.android.providers.settings",
        "com.facebook.appmanager",
        "com.facebook.services",
        "com.facebook.system",
        "com.oculus.accountscenter",
        "com.oculus.horizon",
        "com.oculus.shellenv",
        "com.oculus.store",
        "com.oculus.vrshell",
        "com.meta.quest.services"
    )
    if (value in exact) return true
    val internalPrefixes = listOf(
        "android.",
        "com.android.",
        "com.google.android.",
        "com.oculus.systemux.",
        "com.oculus.shellenv.",
        "com.oculus.vrshell.",
        "com.qualcomm."
    )
    return internalPrefixes.any(value::startsWith)
}

fun shouldIncludeQuestPackage(packageName: String, showAll: Boolean = false): Boolean =
    showAll || !isQuestInternalPackage(packageName)

fun mergeInstalledAppSnapshot(
    previous: List<InstalledQuestApp>,
    incoming: List<InstalledQuestApp>
): List<InstalledQuestApp> {
    val oldByPackage = previous.associateBy { it.packageName }
    val stableIncoming = incoming.map { candidate ->
        val old = oldByPackage[candidate.packageName]
        if (old != null &&
            old.versionName == candidate.versionName &&
            old.versionCode == candidate.versionCode &&
            old.displayName == candidate.displayName &&
            old.apkPath == candidate.apkPath &&
            old.thirdParty == candidate.thirdParty
        ) old else candidate
    }
    val byPackage = stableIncoming.associateBy { it.packageName }
    // Existing rows retain their established order. New packages use the
    // scanner's deterministic order and are appended without reshuffling the
    // selection under the user's pointer.
    return previous.mapNotNull { byPackage[it.packageName] } +
        stableIncoming.filter { it.packageName !in oldByPackage }
}

fun stableSelectedPackage(
    selectedPackageName: String?,
    apps: List<InstalledQuestApp>
): InstalledQuestApp? =
    selectedPackageName?.let { packageId -> apps.firstOrNull { it.packageName == packageId } }

fun resolveScanSelection(
    capturedPackageName: String?,
    currentPackageName: String?,
    apps: List<InstalledQuestApp>
): InstalledQuestApp? =
    stableSelectedPackage(currentPackageName ?: capturedPackageName, apps)

fun parseAdbDeviceRows(devicesOutput: String): List<AdbDeviceRow> {
    val lines = devicesOutput.lines().map(String::trim)
    val header = lines.indexOfFirst { it.startsWith("List of devices") }
    if (header < 0) return emptyList()
    return lines.drop(header + 1)
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 2 || parts[0].equals("*", ignoreCase = false)) return@mapNotNull null
            val state = when (parts[1].lowercase()) {
                "device" -> AdbDeviceState.DEVICE
                "offline" -> AdbDeviceState.OFFLINE
                "unauthorized" -> AdbDeviceState.UNAUTHORIZED
                else -> AdbDeviceState.UNKNOWN
            }
            AdbDeviceRow(parts[0], state, parts.drop(2).joinToString(" "))
        }
        .toList()
}

fun selectAdbDevice(devicesOutput: String): AdbSelection {
    val rows = parseAdbDeviceRows(devicesOutput)
    val authorized = rows.filter { it.state == AdbDeviceState.DEVICE }
    val selected = authorized.singleOrNull()?.serial
    val message = when {
        rows.isEmpty() -> "لم يتم العثور على جهاز ADB."
        authorized.size > 1 -> "تم العثور على أكثر من جهاز مصرح به؛ افصل الأجهزة الزائدة أو اختر جهازًا صراحة."
        selected != null -> "الجهاز مصرح وجاهز."
        rows.any { it.state == AdbDeviceState.UNAUTHORIZED } ->
            "الجهاز يحتاج موافقة USB Debugging داخل النظارة."
        rows.any { it.state == AdbDeviceState.OFFLINE } ->
            "الجهاز ظاهر لكنه غير متصل؛ أعد توصيل السلك."
        else -> "حالة جهاز ADB غير معروفة."
    }
    return AdbSelection(rows, selected, message)
}

fun translateAdbFailure(raw: String): String {
    val value = raw.lowercase()
    return when {
        value.contains("unauthorized") || value.contains("authoriz") ->
            "النظارة لم توافق على USB Debugging. افتح شاشة النظارة ووافق على الرسالة."
        value.contains("offline") ->
            "النظارة ظهرت غير متصلة. افصل السلك، افتح قفل النظارة، ثم أعد التوصيل."
        value.contains("install_failed_insufficient_storage") ||
            value.contains("no space left") || value.contains("insufficient storage") ->
            "لا توجد مساحة كافية على النظارة. أفرغ مساحة ثم أعد المحاولة."
        value.contains("version downgrade") ->
            "الإصدار الموجود على النظارة أحدث؛ احذف النسخة الحالية أو استخدم APK أحدث."
        value.contains("update incompatible") || value.contains("signatures do not match") ->
            "توقيع APK لا يطابق النسخة المثبتة؛ احذف النسخة الحالية فقط إذا كنت متأكدًا من المصدر."
        value.contains("parse_failed") || value.contains("bad manifest") ->
            "ملف APK غير صالح أو أن Manifest الخاص به لا يمكن قراءته."
        value.contains("install_failed_version_downgrade") ->
            "الإصدار الموجود على النظارة أحدث من ملف APK المحدد. استخدم إصدارًا أحدث أو أزل الإصدار الحالي بعد حفظ بياناتك."
        value.contains("install_failed_update_incompatible") || value.contains("signature") ->
            "توقيع ملف APK لا يطابق النسخة المثبتة. استخدم حزمة من المصدر نفسه أو أزل النسخة الحالية بعد حفظ بياناتك."
        value.contains("install_failed_already_exists") ->
            "الحزمة موجودة بالفعل ولا تقبل هذا النوع من التحديث."
        value.contains("install_failed_invalid_apk") || value.contains("install_parse_failed") ->
            "ملف APK غير صالح أو تعذر قراءة بيانات الحزمة. أعد تنزيل اللعبة من مصدر موثوق."
        value.contains("timeout") || value.contains("timed out") ->
            "انتهت مهلة عملية ADB. تأكد من ثبات الكابل واتصال النظارة ثم أعد المحاولة."
        value.contains("closed") || value.contains("connection reset") || value.contains("connection lost") ->
            "انقطع الاتصال بالنظارة أثناء العملية. أعد توصيل كابل البيانات وانتظر عودة الاتصال."
        value.contains("more than one device") ->
            "يوجد أكثر من جهاز متصل. اترك نظارة واحدة فقط ثم أعد المحاولة."
        value.contains("cannot stat") || value.contains("no such file") || value.contains("not found") ->
            "ملف أو مجلد مطلوب غير موجود. راجع مجلد اللعبة والملفات ثم أعد المحاولة."
        value.contains("permission denied") || value.contains("permission") ->
            "تعذر الوصول للملف. تحقق من صلاحيات المجلد وشغّل البرنامج بصلاحية مناسبة."
        value.contains("failed to connect") || value.contains("device not found") ->
            "تعذر الاتصال بـ ADB. افحص السلك وإعداد USB Debugging."
        else -> "تعذر تنفيذ أمر ADB. راجع المخرجات الخام أدناه واتبع خطوات الإعداد."
    }
}

fun boundedRawCommandOutput(stdout: String, stderr: String, maxChars: Int = 12_000): String {
    val raw = buildString {
        appendLine("stdout:")
        appendLine(stdout.trim())
        appendLine("stderr:")
        append(stderr.trim())
    }
    return if (raw.length <= maxChars) raw else raw.take(maxChars) + "\n[… تم اختصار المخرجات …]"
}

enum class GameInstallPhase {
    IDLE,
    PREFLIGHT,
    INSTALLING_APK,
    TRANSFERRING_OBB,
    VERIFYING,
    PAUSED,
    COMPLETED,
    FAILED,
    RESTARTING
}

enum class InstallPhaseTransition {
    BEGIN_PREFLIGHT,
    STORAGE_CHECK_STARTED,
    STORAGE_AVAILABLE,
    STORAGE_INSUFFICIENT,
    BEGIN_APK_INSTALL,
    BEGIN_OBB_TRANSFER,
    BEGIN_VERIFICATION,
    VERIFIED,
    FAILED
}

/**
 * The storage gate is deliberately explicit: a failed/insufficient check
 * cannot accidentally fall through to an active transfer phase.
 */
fun transitionInstallPhase(
    current: GameInstallPhase,
    transition: InstallPhaseTransition
): GameInstallPhase = when (transition) {
    InstallPhaseTransition.BEGIN_PREFLIGHT,
    InstallPhaseTransition.STORAGE_CHECK_STARTED -> GameInstallPhase.PREFLIGHT
    InstallPhaseTransition.STORAGE_AVAILABLE,
    InstallPhaseTransition.BEGIN_APK_INSTALL -> GameInstallPhase.INSTALLING_APK
    InstallPhaseTransition.BEGIN_OBB_TRANSFER -> GameInstallPhase.TRANSFERRING_OBB
    InstallPhaseTransition.BEGIN_VERIFICATION -> GameInstallPhase.VERIFYING
    InstallPhaseTransition.STORAGE_INSUFFICIENT -> GameInstallPhase.PAUSED
    InstallPhaseTransition.VERIFIED -> GameInstallPhase.COMPLETED
    InstallPhaseTransition.FAILED -> GameInstallPhase.FAILED
}

private fun littleU16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

private fun littleI32(bytes: ByteArray, offset: Int): Int =
    littleU16(bytes, offset) or (littleU16(bytes, offset + 2) shl 16)

private fun readAxmString(bytes: ByteArray, offset: Int, utf8: Boolean): String {
    var at = offset
    if (utf8) {
        val first = bytes[at++].toInt() and 0xff
        if (first and 0x80 != 0) at++
        val second = bytes[at++].toInt() and 0xff
        if (second and 0x80 != 0) at++
        return bytes.copyOfRange(at, (at + second).coerceAtMost(bytes.size))
            .toString(Charsets.UTF_8)
    }
    val first = littleU16(bytes, at)
    at += if (first and 0x8000 != 0) 4 else 2
    val length = if (first and 0x8000 != 0) {
        littleU16(bytes, offset + 2) and 0x7fff
    } else first
    return bytes.copyOfRange(at, (at + length * 2).coerceAtMost(bytes.size))
        .toString(Charsets.UTF_16LE)
}

private fun readAxmStringPool(bytes: ByteArray, offset: Int): Pair<List<String>, Int>? {
    if (offset + 28 > bytes.size || littleU16(bytes, offset) != 0x0001) return null
    val headerSize = littleU16(bytes, offset + 2)
    val chunkSize = littleI32(bytes, offset + 4)
    val count = littleI32(bytes, offset + 8)
    val flags = littleI32(bytes, offset + 16)
    val stringsStart = littleI32(bytes, offset + 20)
    if (count < 0 || count > 100_000 || headerSize < 28 || chunkSize <= 0 ||
        offset + chunkSize > bytes.size || stringsStart < headerSize
    ) return null
    val offsets = (0 until count).map { littleI32(bytes, offset + headerSize + it * 4) }
    val utf8 = flags and 0x100 != 0
    val strings = offsets.map { stringOffset ->
        readAxmString(bytes, offset + stringsStart + stringOffset, utf8)
    }
    return strings to (offset + chunkSize)
}

private fun parseBinaryAndroidManifest(bytes: ByteArray): String? {
    if (bytes.size < 8 || littleU16(bytes, 0) != 0x0003) return null
    var pool: List<String>? = null
    var at = 8
    while (at + 8 <= bytes.size) {
        val type = littleU16(bytes, at)
        val size = littleI32(bytes, at + 4)
        if (size < 8 || at + size > bytes.size) break
        if (type == 0x0001) {
            pool = readAxmStringPool(bytes, at)?.first
            break
        }
        at += size
    }
    val strings = pool ?: return null
    at = 8
    while (at + 8 <= bytes.size) {
        val type = littleU16(bytes, at)
        val size = littleI32(bytes, at + 4)
        if (size < 8 || at + size > bytes.size) break
        if (type == 0x0102 && at + 36 <= bytes.size) {
            val elementName = strings.getOrNull(littleI32(bytes, at + 20))
            val attrStart = littleU16(bytes, at + 24)
            val attrSize = littleU16(bytes, at + 26)
            val attrCount = littleU16(bytes, at + 28)
            val attributesBase = at + 16 + attrStart
            if (elementName == "manifest" && attrSize >= 20 &&
                attrCount <= 1_000 && attributesBase + attrCount * attrSize <= bytes.size
            ) {
                repeat(attrCount) { index ->
                    val base = attributesBase + index * attrSize
                    val name = strings.getOrNull(littleI32(bytes, base + 4))
                    if (name == "package") {
                        val raw = littleI32(bytes, base + 8)
                        val typedType = bytes.getOrNull(base + 15)?.toInt()?.and(0xff)
                        val typed = littleI32(bytes, base + 16)
                        val value = if (raw >= 0) strings.getOrNull(raw)
                        else if (typedType == 0x03) strings.getOrNull(typed) else null
                        return value?.takeIf { it.matches(Regex("[A-Za-z0-9_.]+")) }
                    }
                }
            }
        }
        at += size
    }
    return null
}

/** Reads only AndroidManifest.xml and never invokes an external tool. */
fun readApkPackageName(apk: File, maxManifestBytes: Int = 4 * 1024 * 1024): String? = runCatching {
    if (!apk.isFile || !apk.canRead()) return@runCatching null
    ZipFile(apk).use { zip ->
        val entry = zip.getEntry("AndroidManifest.xml") ?: return@runCatching null
        if (entry.size > maxManifestBytes) return@runCatching null
        val bytes = zip.getInputStream(entry).use { it.readNBytes(maxManifestBytes + 1) }
        if (bytes.size > maxManifestBytes) return@runCatching null
        val text = bytes.toString(Charsets.UTF_8)
        Regex("""<manifest\b[^>]*\bpackage\s*=\s*["']([^"']+)["']""")
            .find(text)?.groupValues?.getOrNull(1)?.takeIf { it.matches(Regex("[A-Za-z0-9_.]+")) }
            ?: parseBinaryAndroidManifest(bytes)
    }
}.getOrNull()

data class ExpectedObbFile(val remotePath: String, val sizeBytes: Long)
data class ActualObbFile(val remotePath: String, val sizeBytes: Long)
data class ObbVerification(
    val valid: Boolean,
    val missing: List<String> = emptyList(),
    val mismatched: List<String> = emptyList()
)

fun expectedObbFiles(localDirectory: File, remoteDirectory: String): List<ExpectedObbFile> {
    if (!localDirectory.isDirectory) return emptyList()
    val base = remoteDirectory.trimEnd('/')
    return localDirectory.walkTopDown().filter { it.isFile }.map {
        ExpectedObbFile(
            "$base/${it.relativeTo(localDirectory).invariantSeparatorsPath}",
            it.length()
        )
    }.toList()
}

fun verifyExpectedObbFiles(
    expected: List<ExpectedObbFile>,
    actual: List<ActualObbFile>
): ObbVerification {
    val actualByPath = actual.associateBy { it.remotePath }
    val missing = expected.filter { it.sizeBytes <= 0L || actualByPath[it.remotePath] == null }
        .map { it.remotePath }
    val mismatched = expected.filter { expectedFile ->
        val found = actualByPath[expectedFile.remotePath]
        found != null && (found.sizeBytes <= 0L || found.sizeBytes != expectedFile.sizeBytes)
    }.map { it.remotePath }
    return ObbVerification(missing.isEmpty() && mismatched.isEmpty(), missing, mismatched)
}

data class ActivityEvent(
    val timestamp: String = Instant.now().toString(),
    val phase: GameInstallPhase,
    val message: String,
    val stdout: String = "",
    val stderr: String = ""
)

class BoundedActivityHistory(private val maxEvents: Int = 200) {
    private val events = ArrayDeque<ActivityEvent>()

    fun add(event: ActivityEvent) {
        if (events.size >= maxEvents) events.removeFirst()
        events.addLast(event)
    }

    fun snapshot(): List<ActivityEvent> = events.toList()

    fun clear() = events.clear()
}

object ActivityHistoryStore {
    private const val MAX_EVENTS = 200
    private val file: File get() = File(UserDataPaths.root, "activity-history.jsonl")

    @Synchronized
    fun append(event: ActivityEvent) {
        runCatching {
            UserDataPaths.root.mkdirs()
            fun escape(value: String): String =
                value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t")
                    .take(12_000)
            val line = """{"at":"${event.timestamp}","phase":"${event.phase}","message":"${escape(event.message)}","stdout":"${escape(event.stdout)}","stderr":"${escape(event.stderr)}"}"""
            val existing = if (file.exists()) file.readLines().takeLast(MAX_EVENTS - 1) else emptyList()
            file.writeText((existing + line).joinToString("\n") + "\n")
        }
    }

    @Synchronized
    fun clear() {
        runCatching {
            if (file.exists()) file.writeText("")
        }
    }
}

data class PathPreferences(
    val gameFolders: List<String> = emptyList(),
    val lastGameFolder: String? = null,
    val modArchiveDirectory: String? = null
)

object NfvrPathPreferences {
    private const val FILE_NAME = "paths.properties"

    private fun file(): File = File(UserDataPaths.root, FILE_NAME)

    fun load(): PathPreferences = runCatching {
        val properties = java.util.Properties()
        file().inputStream().use(properties::load)
        val games = (properties.getProperty("game_folders") ?: "")
            .split(File.pathSeparator)
            .map(String::trim)
            .filter { it.isNotBlank() }
            .map(::File)
            .filter { it.isDirectory }
            .map { it.absolutePath }
            .distinct()
        val lastGame = properties.getProperty("last_game_folder")
            ?.trim()
            ?.takeIf { it.isNotBlank() && File(it).isDirectory }
        val archive = properties.getProperty("mod_archive_directory")
            ?.trim()
            ?.takeIf { it.isNotBlank() && File(it).isDirectory }
        PathPreferences(games, lastGame, archive)
    }.getOrDefault(PathPreferences())

    fun saveGameFolders(folders: List<File>) {
        val paths = folders.map { it.absolutePath }.distinct()
        save(load().copy(
            gameFolders = paths,
            lastGameFolder = paths.lastOrNull()
        ))
    }

    fun saveModArchiveDirectory(directory: File) {
        if (directory.isDirectory) save(load().copy(modArchiveDirectory = directory.absolutePath))
    }

    private fun save(preferences: PathPreferences) {
        runCatching {
            UserDataPaths.root.mkdirs()
            java.util.Properties().apply {
                setProperty("game_folders", preferences.gameFolders.joinToString(File.pathSeparator))
                preferences.lastGameFolder?.let { setProperty("last_game_folder", it) }
                    ?: remove("last_game_folder")
                preferences.modArchiveDirectory?.let { setProperty("mod_archive_directory", it) }
                    ?: remove("mod_archive_directory")
            }.store(file().outputStream(), "NFVR user paths")
        }
    }
}

fun validateLocalPreflight(
    apk: File,
    dataDirectories: List<File>,
    packageName: String? = readApkPackageName(apk)
): List<String> = buildList {
    if (!apk.exists() || !apk.isFile) add("ملف APK غير موجود.")
    else {
        if (!apk.canRead()) add("ملف APK غير قابل للقراءة.")
        if (apk.length() <= 0L) add("ملف APK فارغ أو تالف.")
        if (packageName.isNullOrBlank()) add("تعذر قراءة هوية package من APK، لذلك لن يتم إعلان نجاح غير قابل للتحقق.")
    }
    dataDirectories.forEach { directory ->
        if (!directory.exists() || !directory.isDirectory) {
            add("مجلد البيانات غير موجود: ${directory.name}")
        } else if (directory.walkTopDown().any { it.isFile && it.length() <= 0L }) {
            add("يوجد ملف بيانات فارغ: ${directory.name}")
        }
    }
}