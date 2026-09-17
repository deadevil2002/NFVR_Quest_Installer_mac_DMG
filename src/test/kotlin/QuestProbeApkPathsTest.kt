import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuestProbeApkPathsTest {
    @Test
    fun `normalizes realistic pm output with noise duplicates and CRLF`() {
        val result = QuestProbeApkPaths.parse(
            "warning: ignored\r\n" +
                " package:/data/app/~~abc==/base.apk \r\n" +
                "package:/data/app/~~abc==/split_config.arm64_v8a.apk\r\n" +
                "package:/data/app/~~abc==/base.apk\r\n" +
                "shell@quest:/ $ \r\n"
        )

        assertEquals(
            listOf(
                "/data/app/~~abc==/base.apk",
                "/data/app/~~abc==/split_config.arm64_v8a.apk"
            ),
            result.paths
        )
        assertEquals(5, result.diagnostics.rawLineCount)
        assertEquals(3, result.diagnostics.validApkLineCount)
        assertEquals(2, result.diagnostics.uniqueApkCount)
        assertEquals(128, result.diagnostics.limit)
        assertTrue(result.accepted)
    }

    @Test
    fun `rejects traversal shell noise and non apk paths`() {
        val result = QuestProbeApkPaths.parse(
            """
            package:/data/app/game/../base.apk
            package:/data/app/game/base.apk;rm
            package:/data/app/game/base.zip
            package:/data/app/game/base.apk
            package:package:/data/app/game/other.apk
            """.trimIndent()
        )

        assertEquals(listOf("/data/app/game/base.apk"), result.paths)
        assertEquals(1, result.diagnostics.validApkLineCount)
        assertTrue(result.accepted)
    }

    @Test
    fun `reports zero separately and enforces limit after deduplication`() {
        val empty = QuestProbeApkPaths.parse("warning\r\n")
        assertFalse(empty.accepted)
        assertTrue(empty.diagnostics.zeroApkCount)
        assertFalse(empty.diagnostics.limitExceeded)

        val under = (1..128).map { "package:/data/app/~~x==/split_$it.apk" }
        val over = under + "package:/data/app/~~x==/split_129.apk"
        assertTrue(QuestProbeApkPaths.parse(under.joinToString("\n")).accepted)
        val result = QuestProbeApkPaths.parse(over.joinToString("\n"))
        assertFalse(result.accepted)
        assertEquals(129, result.diagnostics.uniqueApkCount)
        assertTrue(result.diagnostics.limitExceeded)
    }

    @Test
    fun `direct transport normalization accepts bare paths but remains defensive`() {
        val result = QuestProbeApkPaths.normalizeDirect(
            listOf(
                " /data/app/~~abc+_= @/base.apk ".replace(" ", ""),
                "noise",
                "package:/data/app/~~abc+_=@/split_feature.apk"
            )
        )
        assertEquals(
            listOf(
                "/data/app/~~abc+_=@/base.apk",
                "/data/app/~~abc+_=@/split_feature.apk"
            ),
            result.paths
        )
    }
}