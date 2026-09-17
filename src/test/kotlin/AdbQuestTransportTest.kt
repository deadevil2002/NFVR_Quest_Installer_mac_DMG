import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File

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

    @Test
    fun `cmd package path is used when pm returns no accepted path`() {
        val fake = object : AdbClient(BundledAdb(HostOs.LINUX)) {
            val calls = mutableListOf<List<String>>()
            override fun shell(serial: String, vararg args: String): CmdResult {
                calls += args.toList()
                return when {
                    args.take(2) == listOf("pm", "path") ->
                        CmdResult(0, "package:warning\n", "")
                    args.take(3) == listOf("cmd", "package", "path") ->
                        CmdResult(
                            0,
                            "package:/data/app/~~hash==/com.example-abc/base.apk\n",
                            ""
                        )
                    else -> CmdResult(1, "", "not part of fixture")
                }
            }
        }
        val result = AdbClient.AdbRestrictedQuestTransport(fake)
            .packagePathResult("serial", "com.example")

        assertEquals(QuestProbeApkPathSource.CMD_PACKAGE_PATH, result.source)
        assertEquals(
            listOf("/data/app/~~hash==/com.example-abc/base.apk"),
            result.paths
        )
        assertTrue(fake.calls.any { it.take(3) == listOf("cmd", "package", "path") })
    }

    @Test
    fun `dumpsys codePath fallback rejects unrelated install paths`() {
        val fake = object : AdbClient(BundledAdb(HostOs.LINUX)) {
            override fun shell(serial: String, vararg args: String): CmdResult =
                when {
                    args.take(2) == listOf("pm", "path") -> CmdResult(0, "", "")
                    args.take(3) == listOf("cmd", "package", "path") -> CmdResult(0, "", "")
                    args.take(2) == listOf("dumpsys", "package") -> CmdResult(
                        0,
                        """
                            codePath=/data/app/~~hash==/com.other-abc
                            sourceDir=/data/app/~~hash==/com.other-abc/base.apk
                        """.trimIndent(),
                        ""
                    )
                    else -> CmdResult(1, "", "not part of fixture")
                }
        }
        val result = AdbClient.AdbRestrictedQuestTransport(fake)
            .packagePathResult("serial", "com.example")
        assertTrue(result.paths.isEmpty())
    }

    @Test
    fun `pull falls back to bounded binary exec out without text conversion`() {
        val expected = byteArrayOf(0, 1, 2, 0x7f, 0x00, 0xff.toByte())
        val fake = object : AdbClient(BundledAdb(HostOs.LINUX)) {
            var streamed = false
            override fun pullReadOnly(serial: String, remotePath: String, localFile: File): CmdResult =
                CmdResult(1, "", "pull denied")

            override fun streamReadOnly(
                serial: String,
                remotePath: String,
                localFile: File,
                maxBytes: Long
            ): CmdResult {
                streamed = true
                localFile.writeBytes(expected)
                return CmdResult(0, "", "")
            }
        }
        val local = File.createTempFile("nfvr-apk-", ".apk")
        try {
            val transport = AdbClient.AdbRestrictedQuestTransport(fake)
            assertTrue(
                transport.pull(
                    "serial",
                    "/data/app/~~hash==/com.example-abc/base.apk",
                    local
                )
            )
            assertTrue(fake.streamed)
            assertContentEquals(expected, local.readBytes())
        } finally {
            local.delete()
        }
    }
}