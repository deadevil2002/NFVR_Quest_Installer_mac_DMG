import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

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
     *   "product_code": "NFVR_INSTALLER"
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

        val jsonBody = """
            {
              "license_key": "${escapeJson(licenseKey.trim())}",
              "device_hash": "${escapeJson(deviceHash.trim())}",
              "product_code": "${escapeJson(productCode.trim())}"
            }
        """.trimIndent()

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
        val error = extractJsonValue(body, "error")
        if (!error.isNullOrBlank()) return OnlineActivationResponse(false, error)

        val status = extractJsonValue(body, "status") ?: ""
        val msg = extractJsonValue(body, "message") ?: "HTTP $code"

        val ok = (code in 200..299) && status.isNotBlank() && status != "error"
        return OnlineActivationResponse(ok, msg)
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

    private fun extractJsonValue(json: String, key: String): String? {
        // بسيط وكافي لردودك الحالية
        val regex = Regex(""""$key"\s*:\s*"([^"]*)"""")
        return regex.find(json)?.groupValues?.getOrNull(1)
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
