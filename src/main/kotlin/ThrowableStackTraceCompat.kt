import java.io.PrintWriter
import java.io.StringWriter

/**
 * Compatibility for older Kotlin stdlib where Throwable.stackTraceToString() may not exist.
 * This provides the same name so existing calls compile without changing Main.kt.
 */
fun Throwable.stackTraceToString(): String {
    val sw = StringWriter()
    this.printStackTrace(PrintWriter(sw))
    return sw.toString()
}
