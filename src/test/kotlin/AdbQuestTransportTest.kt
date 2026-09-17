import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdbQuestTransportTest {
    private class FakeAdb(private val pmPathOutput: String) :
        AdbClient(BundledAdb(HostOs.LINUX)) {
        override fun shell(serial: String, vararg args: String): CmdResult =
            if (args.take(2) == listOf("pm", "path")) {
                CmdResult(0, pmPathOutput, "")
            } else {
                CmdResult(1, "", "not part of this fixture")
            }
    }

    @Test
    fun `pm path accepts splits and install paths with tilde`() {
        val output = """
            warning: ignored
              package:/data/app/~~abc==/com.example/base.apk
            package:/data/app/~~abc==/com.example/split_config.arm64_v8a.apk
            package:/data/app/~~abc==/com.example/split_config.arm64_v8a.apk
            package:/data/app/~~abc==/com.example/split_config.en.apk
            shell$ package:/data/app/~~abc==/com.example/not-an-apk
        """.trimIndent().replace("\n", "\r\n")
        val transport = AdbClient.AdbRestrictedQuestTransport(FakeAdb(output))
        val original = transport.packagePathResult("serial", "com.example")
        assertEquals(6, original.diagnostics.rawLineCount)
        assertEquals(4, original.diagnostics.validApkLineCount)
        assertEquals(3, original.diagnostics.uniqueApkCount)
        assertEquals(128, original.diagnostics.limit)
        assertEquals(
            listOf(
                "/data/app/~~abc==/com.example/base.apk",
                "/data/app/~~abc==/com.example/split_config.arm64_v8a.apk",
                "/data/app/~~abc==/com.example/split_config.en.apk"
            ),
            transport.packagePaths("serial", "com.example")
        )
    }

    @Test
    fun `pm path ignores blank malformed and non-apk lines`() {
        val transport = AdbClient.AdbRestrictedQuestTransport(
            FakeAdb(
                "\npackage:\n package:/system/framework/framework.jar\n" +
                    "package:/data/app/com.example/base.apk.extra\nnoise\n"
            )
        )
        assertTrue(transport.packagePaths("serial", "com.example").isEmpty())
    }
}