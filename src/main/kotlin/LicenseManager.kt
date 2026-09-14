import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class LicenseState(
    val isActivated: Boolean,
    val fingerprint: String,
    val boundLicenseKey: String?
)

data class ActivationResult(
    val success: Boolean,
    val message: String
)

object LicenseManager {

    private const val APP_FOLDER = "NFVRQuestInstaller"
    private const val ACTIVATION_FILE = "activation.dat"

    // Offline + client-side = مو حماية “مستحيل كسرها”، بس تمنع العبث البسيط وتربط التفعيل بجهاز واحد.
    private const val HMAC_KEY = "NFVR_OFFLINE_LICENSE_V1__CHANGE_ME_LATER"

    private fun appDataDir(): File {
        val os = System.getProperty("os.name").lowercase()
        val home = System.getProperty("user.home") ?: "."
        return when {
            os.contains("win") -> {
                val appdata = System.getenv("APPDATA") ?: home
                File(appdata, APP_FOLDER)
            }
            os.contains("mac") -> File(File(home, "Library/Application Support"), APP_FOLDER)
            else -> File(File(home, ".config"), APP_FOLDER)
        }
    }

    private fun activationFile(): File = File(appDataDir(), ACTIVATION_FILE)

    /** بصمة الجهاز اللي نربط عليها التفعيل (ثابتة قدر الإمكان) */
    fun fingerprint(): String = computeFingerprint()

    fun loadState(): LicenseState {
        val fp = computeFingerprint()
        val f = activationFile()
        if (!f.exists()) {
            return LicenseState(isActivated = false, fingerprint = fp, boundLicenseKey = null)
        }

        return try {
            val raw = f.readText().trim()
            // Format: licenseKey|fingerprint|sig
            val parts = raw.split("|")
            if (parts.size != 3) return LicenseState(false, fp, null)

            val key = parts[0]
            val storedFp = parts[1]
            val sig = parts[2]

            val expected = hmac("${key}|${storedFp}")
            val ok = constantTimeEquals(sig, expected) && storedFp == fp

            LicenseState(isActivated = ok, fingerprint = fp, boundLicenseKey = if (ok) key else null)
        } catch (_: Throwable) {
            LicenseState(isActivated = false, fingerprint = fp, boundLicenseKey = null)
        }
    }

    /** يحفظ التفعيل محلياً بعد نجاح التفعيل أونلاين */
    fun saveActivationLocally(licenseKey: String): ActivationResult = activate(licenseKey)

    private fun activate(licenseKey: String): ActivationResult {
        val key = licenseKey.trim()
        if (key.isBlank()) return ActivationResult(false, "License Key فارغ")

        val fp = computeFingerprint()
        val dir = appDataDir()
        if (!dir.exists()) dir.mkdirs()

        val payload = "${key}|${fp}"
        val sig = hmac(payload)
        val out = "${key}|${fp}|${sig}"

        return try {
            activationFile().writeText(out)
            ActivationResult(true, "Activated")
        } catch (e: Throwable) {
            ActivationResult(false, "فشل الحفظ: ${e.message}")
        }
    }

    fun requestTransferCode(boundLicenseKey: String?): String {
        val key = (boundLicenseKey ?: "").trim()
        val fp = computeFingerprint()
        if (key.isBlank()) return "No license key found."

        val payload = "TRANSFER|${key}|${fp}"
        val sig = hmac(payload)
        return "${payload}|${sig}"
    }

    private fun computeFingerprint(): String {
        val os = System.getProperty("os.name") ?: ""
        val arch = System.getProperty("os.arch") ?: ""
        val user = System.getProperty("user.name") ?: ""
        val home = System.getProperty("user.home") ?: ""

        // مهم: لا نستخدم freeSpace لأنه يتغير ويكسر التفعيل.
        val roots = File.listRoots()?.joinToString(",") { r ->
            val total = runCatching { r.totalSpace }.getOrNull() ?: 0L
            "${r.absolutePath}:${total}"
        } ?: ""

        val raw = listOf(os, arch, user, home, roots).joinToString("|")
        return sha256Hex(raw).take(24).chunked(4).joinToString("-")
    }

    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hmac(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(HMAC_KEY.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val out = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
        return out.joinToString("") { "%02x".format(it) }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) {
            r = r or (a[i].code xor b[i].code)
        }
        return r == 0
    }
}
