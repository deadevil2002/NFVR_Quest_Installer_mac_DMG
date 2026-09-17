import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuestPreparationProbeEvidenceTest {
    private class Transport(
        private val bytes: ByteArray,
        private val pullWorks: Boolean = true,
        private val streamWorks: Boolean = false,
        private val loaderFile: String? = null,
        private val loaderText: String? = null
    ) : RestrictedQuestTransport {
        private val path = "/data/app/com.AnotherAxiom.GorillaTag-1/base.apk"
        override fun devices() = "List of devices attached\nquest\tdevice\n"
        override fun getprop(serial: String, name: String) = when (name) {
            "ro.product.model" -> "Quest 3"
            "ro.build.version.release" -> "13"
            "ro.product.cpu.abi", "ro.product.cpu.arch" -> "arm64-v8a"
            "ro.product.cpu.abilist" -> "arm64-v8a"
            else -> ""
        }
        override fun packageList(serial: String) = "package:com.AnotherAxiom.GorillaTag"
        override fun packageInfo(serial: String, packageId: String) =
            "versionName=1.1.145 versionCode=29679"
        override fun packagePaths(serial: String, packageId: String) = listOf(path)
        override fun stat(serial: String, path: String) = bytes.size.toLong()
        override fun list(serial: String, path: String) = emptyList<RemoteEntry>()
        override fun listResult(serial: String, path: String): RestrictedListResult {
            val entries = if (loaderFile != null && path.endsWith("/Modloader")) {
                listOf(RemoteEntry("$path/$loaderFile", 64))
            } else emptyList()
            return RestrictedListResult(true, entries, exists = true)
        }
        override fun readText(serial: String, remotePath: String, maxBytes: Int, cancelled: () -> Boolean): String? =
            loaderText?.take(maxBytes)
        override fun pull(serial: String, remotePath: String, local: File): Boolean {
            if (!pullWorks) return false
            local.writeBytes(bytes)
            return true
        }
        override fun streamReadOnly(serial: String, remotePath: String, local: File, maxBytes: Long): Boolean {
            if (!streamWorks || bytes.size.toLong() > maxBytes) return false
            local.writeBytes(bytes)
            return true
        }
    }

    private fun archive(withManifest: Boolean = true): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            if (withManifest) {
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write("manifest".toByteArray())
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libil2cpp.so"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
    }.toByteArray()

    private fun probe(transport: RestrictedQuestTransport) =
        QuestPreparationProbe(transport, createTempDir(prefix = "probe-evidence-")).probe()
            .games.single()

    @Test
    fun `pull failure is exact stage and retains apk inventory`() {
        val game = probe(Transport(archive(), pullWorks = false))
        assertEquals("APK_PULL", game.apkInspectionStage)
        assertEquals("APK_PULL_FAILED", game.apkInspectionFailureCode)
        assertEquals(1, game.apkInventory.size)
    }

    @Test
    fun `stream fallback completes when pull fails`() {
        val game = probe(Transport(archive(), pullWorks = false, streamWorks = true))
        assertNull(game.apkInspectionFailureCode)
        assertEquals(1, game.apkInventory.size)
        assertEquals(QuestProbeState.COMPLETE, game.probeState)
    }

    @Test
    fun `invalid zip and missing manifest preserve distinct stages`() {
        val invalid = probe(Transport("not an apk".toByteArray()))
        assertEquals("APK_ZIP_OPEN", invalid.apkInspectionStage)
        assertEquals("APK_ZIP_INVALID", invalid.apkInspectionFailureCode)

        val noManifest = probe(Transport(archive(withManifest = false)))
        assertEquals("MANIFEST_EXTRACT", noManifest.apkInspectionStage)
        assertEquals("APK_MANIFEST_READ_FAILED", noManifest.apkInspectionFailureCode)
    }

    @Test
    fun `dumpsys fallback is package scoped and records source`() {
        val result = QuestProbeApkPaths.fromDumpsys(
            "com.beatgames.beatsaber",
            """
            codePath=/data/app/~~hash==/com.beatgames.beatsaber-abc
            sourceDir=/data/app/~~hash==/com.beatgames.beatsaber-abc/base.apk
            splitSourceDirs=/data/app/~~hash==/com.beatgames.beatsaber-abc/split_config.arm64_v8a.apk,/data/app/unrelated/base.apk
            """.trimIndent()
        )
        assertEquals(
            listOf(
                "/data/app/~~hash==/com.beatgames.beatsaber-abc/base.apk",
                "/data/app/~~hash==/com.beatgames.beatsaber-abc/split_config.arm64_v8a.apk"
            ),
            result.paths
        )
        assertEquals(QuestProbeApkPathSource.DUMPSYS_SOURCE_DIR, result.source)
    }

    @Test
    fun `loader directory alone is not recognized`() {
        val game = probe(Transport(archive(), loaderFile = null))
        assertEquals(ProbeLoader.UNKNOWN, game.loader)
        assertEquals(ProbePreparationState.POSSIBLY_PATCHED, game.preparationState)
    }

    @Test
    fun `concrete loader metadata is recognized`() {
        val game = probe(
            Transport(
                archive(),
                loaderFile = "loader.json",
                loaderText = """{"loader":"scotland2","version":"1"}"""
            )
        )
        assertEquals(ProbeLoader.SCOTLAND2, game.loader)
    }
}