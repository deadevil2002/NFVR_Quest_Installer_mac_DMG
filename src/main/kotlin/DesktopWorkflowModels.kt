import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * All timestamps crossing into user-facing desktop UI go through this
 * formatter.  Storage remains an Instant/ISO value; only presentation applies
 * the host's current zone.  Supplying a Clock and ZoneId keeps rollover and
 * non-Windows test cases deterministic.
 */
class NfvrTimeFormatter(
    private val clock: Clock = Clock.systemDefaultZone(),
    val zoneId: ZoneId = ZoneId.systemDefault()
) {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault())
        .withZone(zoneId)
    private val dateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .withZone(zoneId)

    fun now(): Instant = clock.instant()

    fun formatTime(instant: Instant): String = timeFormatter.format(instant)

    fun formatDateTime(instant: Instant): String = dateTimeFormatter.format(instant)
}

fun formatLocalTime(instant: Instant, zoneId: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault()).withZone(zoneId).format(instant)

fun formatLocalDateTime(instant: Instant, zoneId: ZoneId = ZoneId.systemDefault()): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .withZone(zoneId)
        .format(instant)

/**
 * A device change is a hard workflow boundary.  A generation is incremented
 * even when the new serial is null so callbacks from a disconnect cannot
 * repopulate a later device view.
 */
class DeviceSerialGeneration(initialSerial: String? = null) {
    private var serial: String? = initialSerial
    private var generation: Long = 0

    @Synchronized
    fun switchTo(nextSerial: String?): DeviceSerialChange {
        val previous = serial
        if (previous == nextSerial) {
            return DeviceSerialChange(previous, nextSerial, generation, changed = false)
        }
        generation += 1
        serial = nextSerial
        return DeviceSerialChange(previous, nextSerial, generation, changed = true)
    }

    @Synchronized
    fun capture(): DeviceSerialToken? =
        serial?.let { DeviceSerialToken(it, generation) }

    @Synchronized
    fun isCurrent(token: DeviceSerialToken): Boolean =
        token.generation == generation && token.serial == serial
}

data class DeviceSerialToken(val serial: String, val generation: Long)

data class DeviceSerialChange(
    val previousSerial: String?,
    val currentSerial: String?,
    val generation: Long,
    val changed: Boolean
)

/**
 * Cache entries are deliberately keyed by serial rather than by list index or
 * "currently connected" slot.  A cached snapshot can be shown only for that
 * exact serial and is marked stale while a refresh is in flight.
 */
data class DeviceCachedSnapshot<T>(
    val serial: String,
    val value: T,
    val capturedAt: Instant,
    val stale: Boolean = false
)

class DeviceSerialCache<T> {
    private val values = linkedMapOf<String, DeviceCachedSnapshot<T>>()

    @Synchronized
    fun put(serial: String, value: T, capturedAt: Instant = Instant.now()) {
        val key = serial.trim()
        if (key.isNotEmpty()) values[key] = DeviceCachedSnapshot(key, value, capturedAt, stale = false)
    }

    @Synchronized
    fun get(serial: String): DeviceCachedSnapshot<T>? = values[serial.trim()]

    @Synchronized
    fun markStale(serial: String) {
        val key = serial.trim()
        values[key]?.let { values[key] = it.copy(stale = true) }
    }

    @Synchronized
    fun clear(serial: String) {
        values.remove(serial.trim())
    }

    @Synchronized
    fun clearAll() {
        values.clear()
    }
}

data class ModOperationBinding(
    val deviceSerial: String,
    val packageId: String,
    val gameVersion: String?,
    val archiveSha256: String,
    val analysisPlanId: String
)

fun sha256File(file: File): String {
    require(file.isFile && file.canRead()) { "archive is not a readable file" }
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

fun modAnalysisPlanId(
    serial: String,
    app: InstalledQuestApp,
    archiveSha256: String
): String {
    val material = listOf(
        serial,
        app.packageName,
        app.versionName.orEmpty(),
        app.versionCode?.toString().orEmpty(),
        archiveSha256
    ).joinToString("\u001f")
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(material.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

fun modOperationBindingMatches(
    binding: ModOperationBinding?,
    serial: String?,
    app: InstalledQuestApp?,
    archiveSha256: String?,
    planId: String?
): Boolean =
    binding != null &&
        serial == binding.deviceSerial &&
        app?.packageName == binding.packageId &&
        app.versionName == binding.gameVersion &&
        archiveSha256 == binding.archiveSha256 &&
        planId == binding.analysisPlanId

data class ModSupportUiState(
    val serial: String,
    val packageId: String,
    val profileName: String? = null,
    val loader: String = "جارٍ الفحص",
    val existingDirectory: String? = null,
    val status: String = "جارٍ فحص دعم المود — قراءة فقط",
    val stale: Boolean = false
)

fun genericDestinationNeedsConfirmation(analysis: ModPackageAnalysis): Boolean {
    return analysis.installPlan.requiresExplicitConfirmation ||
        analysis.installPlan.preconditions.any {
            if (it.satisfied) return@any false
            val code = it.code.uppercase(Locale.ROOT)
            code.contains("CONFIRM") || code.contains("INFERRED") || code.contains("GENERIC")
        }
}

/**
 * Destination displayed by the UI is always explicit.  This helper avoids
 * accidentally treating a blank/heuristic destination as an implicit write.
 */
fun proposedDestination(analysis: ModPackageAnalysis): String? =
    analysis.installPlan.destinationRoot?.takeIf { it.isNotBlank() }

fun safeDiagnosticText(value: String): String {
    val home = System.getProperty("user.home").orEmpty()
    val temp = System.getProperty("java.io.tmpdir").orEmpty()
    var text = value
    if (home.isNotBlank()) text = text.replace(home, "[USER_HOME]", ignoreCase = true)
    if (temp.isNotBlank()) text = text.replace(temp, "[TEMP]", ignoreCase = true)
    // Keep remote Quest paths (for example /sdcard/Android/data/...) useful,
    // but remove local profile/drive paths from copyable support output.
    text = text.replace(
        Regex("""(?i)\b[A-Z]:[\\/][^\\r\\n"']+"""),
        "[LOCAL_PATH]"
    )
    text = text.replace(
        Regex("""(?<![A-Za-z0-9_])/(?:Users|home|private/var|var/folders)/[^\\r\\n"']+"""),
        "[LOCAL_PATH]"
    )
    return text
}

enum class DesktopDropTarget {
    MOD_PACKAGE,
    GAME_FOLDER
}
