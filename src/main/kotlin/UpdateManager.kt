import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class UpdateMetadata(
    val latestVersion: String,
    val minimumVersion: String?,
    val mandatory: Boolean,
    val downloadUrl: String,
    val sha256: String,
    val releaseNotes: String
)

sealed class UpdateCheckResult {
    data class Available(
        val metadata: UpdateMetadata,
        val effectiveMandatory: Boolean
    ) : UpdateCheckResult()
    data object Current : UpdateCheckResult()
    data object Unavailable : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

object UpdateManager {
    // Intentionally blank until the production update service contract is deployed.
    private const val UPDATE_ENDPOINT = ""
    private const val MAX_INSTALLER_BYTES = 1_000_000_000L
    private const val MAX_REDIRECTS = 5
    private val supportedInstallerExtensions = setOf("msi", "exe")
    private val ownedUpdateRoots = ConcurrentHashMap.newKeySet<String>()

    fun check(currentVersion: String = AppInfo.version): UpdateCheckResult {
        if (UPDATE_ENDPOINT.isBlank()) return UpdateCheckResult.Unavailable
        if (!isHttpsUrl(UPDATE_ENDPOINT)) return UpdateCheckResult.Error("رابط خدمة التحديث غير آمن.")

        return runCatching {
            val connection = (URL(UPDATE_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/json")
            }
            val code = connection.responseCode
            if (code !in 200..299) return UpdateCheckResult.Error("فشل فحص التحديث: HTTP $code")
            val metadata = parseMetadata(readBounded(connection.inputStream, 256_000))
            val belowMinimum = metadata.minimumVersion?.let {
                compareVersions(currentVersion, it) < 0
            } ?: false
            if (compareVersions(metadata.latestVersion, currentVersion) > 0 || belowMinimum) {
                UpdateCheckResult.Available(metadata, metadata.mandatory || belowMinimum)
            } else {
                UpdateCheckResult.Current
            }
        }.getOrElse { UpdateCheckResult.Error(it.message ?: "تعذر فحص التحديث.") }
    }

    fun parseMetadata(json: String): UpdateMetadata {
        val data = JSONObject(json)
        val downloadUrl = data.getString("downloadUrl").trim()
        val sha256 = data.getString("sha256").trim().lowercase()
        require(isHttpsUrl(downloadUrl)) { "رابط التنزيل يجب أن يستخدم HTTPS." }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "بصمة SHA-256 مفقودة أو غير صالحة." }
        require(isSupportedInstaller(downloadUrl)) { "نوع ملف التحديث غير مدعوم." }

        val latestVersion = data.getString("latestVersion").trim()
        val minimumVersion = data.optString("minimumVersion").trim().ifBlank { null }
        compareVersions(latestVersion, latestVersion)
        minimumVersion?.let { compareVersions(it, it) }

        return UpdateMetadata(
            latestVersion = latestVersion,
            minimumVersion = minimumVersion,
            mandatory = data.optBoolean("mandatory", false),
            downloadUrl = downloadUrl,
            sha256 = sha256,
            releaseNotes = data.optString("releaseNotes").take(20_000)
        )
    }

    fun downloadVerifiedInstaller(metadata: UpdateMetadata): File {
        require(isHttpsUrl(metadata.downloadUrl)) { "رابط التنزيل يجب أن يستخدم HTTPS." }
        require(isSupportedInstaller(metadata.downloadUrl)) { "نوع ملف التحديث غير مدعوم." }
        require(metadata.sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "يتطلب التحديث بصمة SHA-256 صالحة." }

        val extension = URI(metadata.downloadUrl).path.substringAfterLast('.', "").lowercase()
        val updateDir = Files.createTempDirectory("NFVR_Update_").toFile()
        val target = File(updateDir, "NFVR_Quest_Installer_Update.$extension")
        try {
            val connection = openHttpsConnection(metadata.downloadUrl)
            val declaredSize = connection.contentLengthLong
            require(declaredSize in -1..MAX_INSTALLER_BYTES) { "حجم ملف التحديث أكبر من الحد المسموح." }
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_INSTALLER_BYTES) { "حجم ملف التحديث أكبر من الحد المسموح." }
                        output.write(buffer, 0, read)
                    }
                }
            }
            val actual = sha256(target)
            require(actual.equals(metadata.sha256, ignoreCase = true)) {
                "فشل التحقق من بصمة ملف التحديث."
            }
            ownedUpdateRoots.add(updateDir.canonicalPath)
            return target
        } catch (error: Throwable) {
            updateDir.deleteRecursively()
            throw error
        }
    }

    fun cleanupDownloadedInstaller(installer: File) {
        runCatching {
            val root = installer.parentFile?.canonicalFile ?: return
            val expectedFile = File(root, installer.name).canonicalFile
            if (expectedFile != installer.canonicalFile) return
            if (!root.name.startsWith("NFVR_Update_")) return
            if (!ownedUpdateRoots.remove(root.canonicalPath)) return
            root.deleteRecursively()
        }
    }

    fun compareVersions(left: String, right: String): Int {
        val a = SemanticVersion.parse(left)
        val b = SemanticVersion.parse(right)
        val size = maxOf(a.numbers.size, b.numbers.size)
        for (index in 0 until size) {
            val comparison = (a.numbers.getOrElse(index) { 0 }).compareTo(b.numbers.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        if (a.preRelease == null && b.preRelease != null) return 1
        if (a.preRelease != null && b.preRelease == null) return -1
        if (a.preRelease == null) return 0

        val sizePre = maxOf(a.preRelease.size, b.preRelease!!.size)
        for (index in 0 until sizePre) {
            val leftPart = a.preRelease.getOrNull(index) ?: return -1
            val rightPart = b.preRelease.getOrNull(index) ?: return 1
            val leftNumber = leftPart.toIntOrNull()
            val rightNumber = rightPart.toIntOrNull()
            val comparison = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> leftPart.compareTo(rightPart)
            }
            if (comparison != 0) return comparison
        }
        return 0
    }

    fun isHttpsUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)

    private fun isSupportedInstaller(value: String): Boolean {
        val extension = runCatching { URI(value).path.substringAfterLast('.', "").lowercase() }.getOrDefault("")
        return extension in supportedInstallerExtensions
    }

    private data class SemanticVersion(val numbers: List<Int>, val preRelease: List<String>?) {
        companion object {
            fun parse(value: String): SemanticVersion {
                val normalized = value.trim().removePrefix("v").substringBefore('+')
                require(normalized.matches(Regex("""\d+(\.\d+)*(-[0-9A-Za-z.-]+)?"""))) {
                    "إصدار غير صالح: $value"
                }
                val core = normalized.substringBefore('-')
                val suffix = normalized.substringAfter('-', "").ifBlank { null }
                return SemanticVersion(
                    numbers = core.split('.').map(String::toInt),
                    preRelease = suffix?.split('.')
                )
            }
        }
    }

    private fun openHttpsConnection(initialUrl: String): HttpURLConnection {
        var current = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            require(isHttpsUrl(current)) { "تم رفض تحويل التنزيل إلى رابط غير آمن." }
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("Accept", "application/octet-stream")
            }
            val code = connection.responseCode
            if (code !in 300..399) {
                require(code in 200..299) { "فشل تنزيل التحديث: HTTP $code" }
                return connection
            }
            require(redirectCount < MAX_REDIRECTS) { "عدد تحويلات رابط التحديث أكبر من الحد المسموح." }
            val location = connection.getHeaderField("Location") ?: error("تحويل تحديث بلا رابط.")
            current = URI(current).resolve(location).toString()
            connection.disconnect()
        }
        error("تعذر فتح رابط التحديث.")
    }

    private fun readBounded(input: java.io.InputStream, maxBytes: Int): String {
        input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (output.size() < maxBytes) {
                val allowed = minOf(buffer.size, maxBytes - output.size())
                val read = stream.read(buffer, 0, allowed)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            require(stream.read() < 0) { "استجابة خدمة التحديث أكبر من الحد المسموح." }
            return output.toString(Charsets.UTF_8)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}