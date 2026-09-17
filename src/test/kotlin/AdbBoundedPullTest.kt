import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class AdbBoundedPullTest {
    @Test
    fun boundedPullKillsLiveWriterAndDeletesPartialFile() {
        val output = File.createTempFile("nfvr-bounded-pull-", ".apk")
        val escaped = output.absolutePath.replace("'", "'\\''")
        try {
            val result = runBoundedPullProcess(
                cmd = listOf(
                    "sh",
                    "-c",
                    "while true; do head -c 4096 /dev/zero >> '$escaped'; done"
                ),
                outputFile = output,
                maxBytes = 16 * 1024L,
                timeoutMs = 10_000
            )
            assertNotEquals(0, result.exit)
            assertFalse(output.exists(), "bounded pull must remove partial output")
        } finally {
            output.delete()
        }
    }
}