import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuestPreparationProbeTest {
    private class FakeTransport(private val packages: String, private val files: Map<String, ByteArray>,
        private val rowOnlyDevices: Boolean = false, private val failPathFor: Set<String> = emptySet(),
        private val sizeOverrides: Map<String, Long> = emptyMap(),
        private val deviceOutput: (() -> String)? = null) :
        RestrictedQuestTransport {
        val calls = mutableListOf<String>()
        override fun devices() = deviceOutput?.invoke()
            ?: if (rowOnlyDevices) "quest-1\tdevice\n" else "List of devices attached\nquest-1\tdevice\n"
        override fun getprop(serial: String, name: String): String {
            calls += "$serial getprop $name"
            return mapOf("ro.product.model" to "Quest 3", "ro.build.version.release" to "13",
                "ro.product.cpu.abi" to "arm64-v8a", "ro.product.cpu.abilist" to "arm64-v8a,armeabi-v7a",
                "ro.product.cpu.arch" to "arm64").getValue(name)
        }
        override fun packageList(serial: String): String { calls += "$serial pm list packages"; return packages }
        override fun packageInfo(serial: String, packageId: String): String {
            calls += "$serial dumpsys package $packageId"
            return "versionName=1.2.3 versionCode=12 firstInstallTime=2025-01-01 10:20:30 lastUpdateTime=2025-02-01 11:22:33"
        }
        override fun packagePaths(serial: String, packageId: String): List<String> {
            calls += "$serial pm path $packageId"
            check(packageId !in failPathFor) { "simulated APK path failure" }
            return files.keys.filter { it.contains(packageId) }
        }
        override fun stat(serial: String, path: String): Long? {
            calls += "$serial stat $path"
            return sizeOverrides[path] ?: files[path]?.size?.toLong()
        }
        override fun list(serial: String, path: String): List<RemoteEntry> {
            calls += "$serial ls $path"
            return emptyList()
        }
        override fun listResult(serial: String, path: String): RestrictedListResult {
            val entries = list(serial, path)
            val exists = path == "/sdcard/Android/data/com.StressLevelZero.BONELAB" ||
                path == "/sdcard/Android/data/com.StressLevelZero.BONELAB/files/Mods"
            return RestrictedListResult(success = true, entries = entries, exists = exists)
        }
        override fun pull(serial: String, remotePath: String, local: File): Boolean {
            calls += "$serial pull $remotePath"
            local.writeBytes(files.getValue(remotePath)); return true
        }
    }

    private fun apk(packageId: String, il2cpp: Boolean): Pair<String, ByteArray> {
        val path = "/data/app/$packageId-1/base.apk"
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write("raw".toByteArray()); z.closeEntry()
                z.putNextEntry(ZipEntry("lib/arm64-v8a/${if (il2cpp) "libil2cpp.so" else "libUE4.so"}"))
                z.write(byteArrayOf(0x7f, 0x45, 0x4c, 0x46)); z.closeEntry()
                z.putNextEntry(ZipEntry("lib/arm64-v8a/libunity.so")); z.write(byteArrayOf(1)); z.closeEntry()
            }
        }.toByteArray()
        return path to bytes
    }

    @Test fun `discovers known games and dynamically finds nomad`() {
        val entries = listOf(apk("com.AnotherAxiom.GorillaTag", true), apk("com.beatgames.beatsaber", false),
            apk("com.StressLevelZero.BONELAB", false), apk("com.example.nomad", false))
        val fake = FakeTransport(entries.joinToString("\n") {
            "package:${it.first}" + if (it.first.endsWith("nomad")) " label=Blade & Sorcery" else ""
        }, entries.toMap())
        val report = QuestPreparationProbe(fake).probe()
        assertEquals(4, report.games.size)
        assertEquals("com.example.nomad", report.games.single { it.displayName.startsWith("Blade") }.packageId)
        assertEquals(ProbeEngine.UNITY_IL2CPP, report.games.first().engine.engine)
        assertTrue(fake.calls.all { it.startsWith("quest-1 ") || it == "quest-1" })
        assertFalse(fake.calls.any { Regex("""\b(push|install|uninstall|rm|mkdir|mv|cp|clear)\b""").containsMatchIn(it) })
    }

    @Test fun `accepts normalized row-only device output`() {
        val fake = FakeTransport("", emptyMap(), rowOnlyDevices = true)
        val report = QuestPreparationProbe(fake).probe()
        assertEquals("quest-1", report.device.serial)
    }

    @Test fun `missing and ambiguous targets do not trigger deep inspection`() {
        val (path, bytes) = apk("com.example.nomad.one", false)
        val fake = FakeTransport("package:$path label=Blade and Sorcery\npackage:com.example.nomad.two label=Blade and Sorcery",
            mapOf(path to bytes))
        val report = QuestPreparationProbe(fake).probe()
        assertEquals(ProbeTargetState.NOT_FOUND, report.discoveries.first().state)
        assertEquals(ProbeTargetState.AMBIGUOUS, report.discoveries.last().state)
        assertTrue(report.games.isEmpty())
        assertTrue(fake.calls.none { "pm path" in it || " pull " in it })
    }

    @Test fun `pulled APKs are cleaned up and BONELAB content is independent`() {
        val (path, bytes) = apk("com.StressLevelZero.BONELAB", false)
        val root = createTempDir(prefix = "probe-test-")
        try {
            val fake = FakeTransport("package:com.StressLevelZero.BONELAB", mapOf(path to bytes))
            val game = QuestPreparationProbe(fake, root).probe().games.single()
            assertEquals("READY", game.contentModState)
            assertEquals(ProbeEngine.UNREAL_ENGINE, game.engine.engine)
            assertTrue(root.listFiles().orEmpty().none { it.exists() })
        } finally { root.deleteRecursively() }
    }

    @Test fun `real sized Gorilla APK passes remote safety stage without allocating it`() {
        val (path, bytes) = apk("com.AnotherAxiom.GorillaTag", true)
        val fake = FakeTransport(
            "package:com.AnotherAxiom.GorillaTag",
            mapOf(path to bytes),
            sizeOverrides = mapOf(path to 570_253_943L)
        )

        val game = QuestPreparationProbe(fake, createTempDir(prefix = "probe-large-stat-")).probe().games.single()

        assertEquals(570_253_943L, game.apkInventory.single().sizeBytes)
        assertTrue(game.apkInspectionFailureCode != "APK_REMOTE_SIZE_LIMIT_EXCEEDED")
        assertTrue(fake.calls.any { it.endsWith("stat $path") })
    }

    @Test fun `APK over two GiB is rejected before transfer`() {
        val (path, bytes) = apk("com.AnotherAxiom.GorillaTag", true)
        val fake = FakeTransport(
            "package:com.AnotherAxiom.GorillaTag",
            mapOf(path to bytes),
            sizeOverrides = mapOf(path to QuestPreparationProbe.MAX_APK_BYTES + 1L)
        )

        val game = QuestPreparationProbe(fake, createTempDir(prefix = "probe-over-limit-")).probe().games.single()

        assertEquals("APK_REMOTE_SIZE_VERIFY", game.apkInspectionStage)
        assertEquals("APK_REMOTE_SIZE_LIMIT_EXCEEDED", game.apkInspectionFailureCode)
        assertTrue(fake.calls.none { it.endsWith("pull $path") })
    }

    @Test fun `one APK failure preserves metadata and does not abort later found games`() {
        val entries = listOf(
            apk("com.AnotherAxiom.GorillaTag", true),
            apk("com.beatgames.beatsaber", false),
            apk("com.StressLevelZero.BONELAB", false),
            apk("com.example.nomad", false)
        )
        val fake = FakeTransport(
            entries.joinToString("\n") { "package:${it.first}" },
            entries.toMap(),
            failPathFor = setOf("com.AnotherAxiom.GorillaTag")
        )

        val report = QuestPreparationProbe(fake).probe()
        assertEquals(4, report.games.size)
        val failed = report.games.first()
        assertEquals(ProbeTargetState.FOUND, failed.discoveryState)
        assertEquals(QuestProbeState.PARTIAL, failed.probeState)
        assertEquals("1.2.3", failed.versionName)
        assertEquals("2025-01-01 10:20:30", failed.firstInstallTime)
        assertEquals("2025-02-01 11:22:33", failed.lastUpdateTime)
        assertEquals(3, report.games.drop(1).count { it.probeState == QuestProbeState.COMPLETE })
    }

    @Test fun `individual scan only probes selected found game`() {
        val entries = listOf(
            apk("com.AnotherAxiom.GorillaTag", true),
            apk("com.beatgames.beatsaber", false),
            apk("com.StressLevelZero.BONELAB", false)
        )
        val fake = FakeTransport(entries.joinToString("\n") { "package:${it.first}" }, entries.toMap())
        val report = QuestPreparationProbe(fake).probe(targetGame = "bonelab")
        assertEquals(1, report.games.size)
        assertEquals("BONELAB", report.games.single().displayName)
        assertTrue(fake.calls.none { "com.beatgames.beatsaber" in it || "GorillaTag" in it })
    }

    @Test fun `cancellation retains completed result and marks remaining placeholders`() {
        val entries = listOf(
            apk("com.AnotherAxiom.GorillaTag", true),
            apk("com.beatgames.beatsaber", false),
            apk("com.StressLevelZero.BONELAB", false),
            apk("com.example.nomad", false)
        )
        val fake = FakeTransport(entries.joinToString("\n") { "package:${it.first}" }, entries.toMap())
        var cancel = false
        val report = QuestPreparationProbe(fake).probe(cancelled = {
            cancel
        }, onGame = { game ->
            if (game.displayName == "Gorilla Tag" && game.probeState == QuestProbeState.COMPLETE) {
                cancel = true
            }
        })
        assertTrue(report.cancelled)
        assertEquals(QuestProbeState.COMPLETE, report.games.first().probeState)
        assertTrue(report.games.drop(1).all { it.probeState == QuestProbeState.CANCELLED })
    }

    @Test fun `stale device retains completed result and marks remaining stale`() {
        val entries = listOf(
            apk("com.AnotherAxiom.GorillaTag", true),
            apk("com.beatgames.beatsaber", false),
            apk("com.StressLevelZero.BONELAB", false),
            apk("com.example.nomad", false)
        )
        var stale = false
        val fake = FakeTransport(
            entries.joinToString("\n") { "package:${it.first}" },
            entries.toMap(),
            deviceOutput = { if (stale) "other-quest\tdevice\n" else "quest-1\tdevice\n" }
        )
        val report = QuestPreparationProbe(fake).probe(onGame = { game ->
            if (game.displayName == "Gorilla Tag" && game.probeState == QuestProbeState.COMPLETE) {
                stale = true
            }
        })
        assertTrue(report.stale)
        assertEquals(QuestProbeState.COMPLETE, report.games.first().probeState)
        assertTrue(report.games.drop(1).all { it.probeState == QuestProbeState.STALE })
    }
}