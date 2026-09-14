import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONObject

data class OnlineActivationResponse(
    val ok: Boolean,
    val message: String
)

object OnlineLicenseApi {

    /**
     * يرسل POST لنفس الـ Worker endpoint:
     * Body:
     * {
     *   "license_key": "...",
     *   "device_hash": "...",
     *   "product_code": "NFVR_QUEST_INSTALLER"
     * }
     */
    fun activate(baseUrl: String, licenseKey: String, deviceHash: String, productCode: String): OnlineActivationResponse {
        val endpoint = baseUrl.trimEnd('/') + "/"
        val url = URL(endpoint)

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        val jsonBody = JSONObject()
            .put("license_key", licenseKey.trim())
            .put("device_hash", deviceHash.trim())
            .put("product_code", productCode.trim())
            .toString()

        conn.outputStream.use { os ->
            val bytes = jsonBody.toByteArray(StandardCharsets.UTF_8)
            os.write(bytes)
        }

        val code = conn.responseCode
        val body = readAll(code in 200..299, conn)

        // Worker عندك يرجع:
        // {"status":"ok","message":"License valid"}
        // أو {"status":"activated","message":"License activated successfully"}
        // أو {"error":"License already used on another device"}
        return parseResponse(code, body)
    }

    private fun readAll(success: Boolean, conn: HttpURLConnection): String {
        val stream = if (success) conn.inputStream else conn.errorStream
        if (stream == null) return ""
        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { br ->
            val sb = StringBuilder()
            var line: String?
            while (true) {
                line = br.readLine() ?: break
                sb.append(line)
            }
            return sb.toString()
        }
    }

    fun parseResponse(code: Int, body: String): OnlineActivationResponse {
        val json = runCatching { JSONObject(body) }.getOrElse {
            return OnlineActivationResponse(false, "استجابة غير صالحة من خادم الترخيص")
        }
        val error = json.optString("error").trim()
        if (error.isNotBlank()) return OnlineActivationResponse(false, error)

        val status = json.optString("status").trim().lowercase()
        val message = json.optString("message").trim().ifBlank { "HTTP $code" }
        val knownSuccess = status == "ok" || status == "activated"
        return OnlineActivationResponse(code in 200..299 && knownSuccess, message)
    }
}
