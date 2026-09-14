import java.io.*
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class GameInfo(
    val name: String,
    val packageName: String,
    val modPath: String,
    val notes: String
)

private val SUPPORTED_GAMES = listOf(
    GameInfo(
        name = "BONELAB",
        packageName = "com.StressLevelZero.BONELAB",
        modPath = "/sdcard/Android/data/com.StressLevelZero.BONELAB/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Blade & Sorcery: Nomad",
        packageName = "com.WarpFrog.BNS",
        modPath = "/sdcard/Android/data/com.WarpFrog.BNS/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Beat Saber",
        packageName = "com.beatgames.beatsaber",
        modPath = "/sdcard/ModData/com.beatgames.beatsaber/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Contractors",
        packageName = "com.CavemanStudio.Contractors",
        modPath = "/sdcard/Android/data/com.CavemanStudio.Contractors/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Pavlov Shack",
        packageName = "com.vankrupt.pavlovshack",
        modPath = "/sdcard/Android/data/com.vankrupt.pavlovshack/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Into the Radius",
        packageName = "CMGames.IntotheRadius",
        modPath = "/sdcard/Android/data/CMGames.IntotheRadius/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "The Walking Dead: Saints & Sinners",
        packageName = "com.skydancedev.saintsandsinners",
        modPath = "/sdcard/Android/data/com.skydancedev.saintsandsinners/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "BONEWORKS",
        packageName = "com.StressLevelZero.BONEWORKS",
        modPath = "/sdcard/Android/data/com.StressLevelZero.BONEWORKS/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "GORN",
        packageName = "com.FreeLives.GORN",
        modPath = "/sdcard/Android/data/com.FreeLives.GORN/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Job Simulator",
        packageName = "com.OwlchemyLabs.JobSimulator",
        modPath = "/sdcard/Android/data/com.OwlchemyLabs.JobSimulator/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Vacation Simulator",
        packageName = "com.OwlchemyLabs.VacationSimulator",
        modPath = "/sdcard/Android/data/com.OwlchemyLabs.VacationSimulator/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Hard Bullet",
        packageName = "com.Carbon.Studio.HardBullet",
        modPath = "/sdcard/Android/data/com.Carbon.Studio.HardBullet/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Hellsplit: Arena",
        packageName = "com.Ruthless.HellsplitArena",
        modPath = "/sdcard/Android/data/com.Ruthless.HellsplitArena/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Ancient Dungeon",
        packageName = "com.Ermir.AncientDungeon",
        modPath = "/sdcard/Android/data/com.Ermir.AncientDungeon/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Swordsman VR",
        packageName = "com.DigitalMiracleGames.SwordsmanVR",
        modPath = "/sdcard/Android/data/com.DigitalMiracleGames.SwordsmanVR/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Onward",
        packageName = "com.DownpourInteractive.Onward",
        modPath = "/sdcard/Android/data/com.DownpourInteractive.Onward/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Arizona Sunshine",
        packageName = "com.Virtuamix.ArizonaSunshine",
        modPath = "/sdcard/Android/data/com.Virtuamix.ArizonaSunshine/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Zero Caliber VR",
        packageName = "com.EmptyClipStudios.ZeroCaliber",
        modPath = "/sdcard/Android/data/com.EmptyClipStudios.ZeroCaliber/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Compound",
        packageName = "com.FrownTown.Compound",
        modPath = "/sdcard/Android/data/com.FrownTown.Compound/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Boneworks: Experimental Branch",
        packageName = "com.StressLevelZero.BONEWORKS.Experimental",
        modPath = "/sdcard/Android/data/com.StressLevelZero.BONEWORKS.Experimental/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Blade & Sorcery",
        packageName = "com.WarpFrog.BladeAndSorcery",
        modPath = "/sdcard/Android/data/com.WarpFrog.BladeAndSorcery/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Half-Life: Alyx",
        packageName = "com.Valve.HalfLifeAlyx",
        modPath = "/sdcard/Android/data/com.Valve.HalfLifeAlyx/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Skyrim VR",
        packageName = "com.Bethesda.SkyrimVR",
        modPath = "/sdcard/Android/data/com.Bethesda.SkyrimVR/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Fallout 4 VR",
        packageName = "com.Bethesda.Fallout4VR",
        modPath = "/sdcard/Android/data/com.Bethesda.Fallout4VR/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Assetto Corsa",
        packageName = "com.Kunos.AssettoCorsa",
        modPath = "/sdcard/Android/data/com.Kunos.AssettoCorsa/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Project Cars 2",
        packageName = "com.SlightlyMadStudios.ProjectCars2",
        modPath = "/sdcard/Android/data/com.SlightlyMadStudios.ProjectCars2/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Risk of Rain 2 VR",
        packageName = "com.HopooGames.RiskOfRain2VR",
        modPath = "/sdcard/Android/data/com.HopooGames.RiskOfRain2VR/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Valheim VR",
        packageName = "com.IronGate.ValheimVR",
        modPath = "/sdcard/Android/data/com.IronGate.ValheimVR/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Lethal Company VR",
        packageName = "com.ZeekerssGames.LethalCompanyVR",
        modPath = "/sdcard/Android/data/com.ZeekerssGames.LethalCompanyVR/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "No Man's Sky",
        packageName = "com.HelloGames.NoMansSky",
        modPath = "/sdcard/Android/data/com.HelloGames.NoMansSky/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    )
)

data class ModInstallResult(
    val success: Boolean,
    val message: String,
    val extractedFiles: List<String> = emptyList(),
    val extractedRoot: File? = null
)

class ModsManager(private val adbClient: AdbClient) {
    companion object {
        private const val MAX_ZIP_ENTRIES = 5_000
        private const val MAX_ENTRY_BYTES = 512L * 1024L * 1024L
        private const val MAX_TOTAL_EXTRACTED_BYTES = 2L * 1024L * 1024L * 1024L
    }
    
    fun getSupportedGames(): List<GameInfo> = SUPPORTED_GAMES
    
    suspend fun getInstalledGamePackages(serial: String): List<String> {
        val result = adbClient.shell(serial, "pm", "list", "packages", "-3")
        if (result.exit != 0) return emptyList()
        
        return result.out.lines()
            .map { it.replace("package:", "").trim() }
            .filter { it.isNotBlank() }
    }
    
    suspend fun testPathExists(serial: String, path: String): Boolean {
        val result = adbClient.shell(serial, "test", "-d", path)
        return result.exit == 0
    }
    
    suspend fun createModPath(serial: String, path: String): CmdResult {
        return adbClient.shell(serial, "mkdir", "-p", path)
    }
    
    suspend fun getGameModPath(serial: String, gameInfo: GameInfo): String? {
        val installedPackages = getInstalledGamePackages(serial)
        if (!installedPackages.contains(gameInfo.packageName)) {
            return null
        }
        
        return gameInfo.modPath
    }
    
    fun extractModZip(zipFile: File): ModInstallResult {
        val extractedFiles = mutableListOf<String>()
        var tempDir: File? = null

        try {
            tempDir = Files.createTempDirectory("NFVR_Mod_").toFile()
            tempDir.deleteOnExit()
            val rootPath = tempDir.canonicalFile.toPath()
            var entryCount = 0
            var totalExtracted = 0L

            ZipInputStream(zipFile.inputStream().buffered()).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    entryCount++
                    require(entryCount <= MAX_ZIP_ENTRIES) { "ملف المود يحتوي على عدد ملفات أكبر من الحد المسموح." }
                    require(entry.name.isNotBlank()) { "اسم ملف غير صالح داخل ZIP." }
                    require(!entry.name.contains('\u0000')) { "اسم ملف غير صالح داخل ZIP." }

                    val outputPath = rootPath.resolve(entry.name.replace('\\', '/')).normalize()
                    require(outputPath.startsWith(rootPath)) { "تم رفض مسار غير آمن داخل ملف ZIP." }
                    val filePath = outputPath.toFile()

                    if (entry.isDirectory) {
                        filePath.mkdirs()
                    } else {
                        filePath.parentFile?.mkdirs()
                        var entryBytes = 0L
                        filePath.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = zipIn.read(buffer)
                                if (read < 0) break
                                entryBytes += read
                                totalExtracted += read
                                require(entryBytes <= MAX_ENTRY_BYTES) { "أحد ملفات المود أكبر من الحد المسموح." }
                                require(totalExtracted <= MAX_TOTAL_EXTRACTED_BYTES) { "حجم ملفات المود بعد الفك أكبر من الحد المسموح." }
                                output.write(buffer, 0, read)
                            }
                        }
                        extractedFiles.add(filePath.absolutePath)
                        filePath.deleteOnExit()
                    }

                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
            
            return ModInstallResult(
                success = true,
                message = "تم فك ضغط المود في: ${tempDir.absolutePath}",
                extractedFiles = extractedFiles,
                extractedRoot = tempDir
            )
            
        } catch (e: Exception) {
            tempDir?.deleteRecursively()
            DiagnosticLogger.error("فشل فك ضغط ملف مود ${zipFile.name}", e)
            return ModInstallResult(
                success = false,
                message = "فشل فك الضغط: ${e.message}"
            )
        }
    }
    
    suspend fun installModToQuest(
    serial: String,
    extractedModDir: File,
    targetPath: String,
    onProgress: (String) -> Unit = {},
    onBytesProgress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
): ModInstallResult {
    try {
        if (!AndroidPathValidator.isSafe(targetPath)) {
            return ModInstallResult(false, "مسار المودات غير صالح أو غير آمن.")
        }
        onProgress("التحقق من وجود مسار المودات...")
        if (!testPathExists(serial, targetPath)) {
            onProgress("إنشاء مجلد المودات...")
            val createResult = createModPath(serial, targetPath)
            if (createResult.exit != 0) {
                return ModInstallResult(
                    success = false,
                    message = "فشل إنشاء مجلد المودات: ${createResult.err}"
                )
            }
        }

        if (!extractedModDir.exists()) {
            return ModInstallResult(
                success = false,
                message = "مجلد/ملف المود غير موجود"
            )
        }

        onProgress("بدء نسخ ملفات المود...")
        val pushResult = adbClient.pushWithProgress(
            serial = serial,
            from = extractedModDir,
            toDevicePath = targetPath
        ) { copied, total ->
            onBytesProgress(copied, total)
        }

        if (pushResult.exit != 0) {
            return ModInstallResult(
                success = false,
                message = "فشل نسخ ملفات المود: ${pushResult.err.ifBlank { pushResult.out }}"
            )
        }

        return ModInstallResult(
            success = true,
            message = "تم تثبيت المود بنجاح!"
        )
    } catch (e: Exception) {
        return ModInstallResult(
            success = false,
            message = "خطأ غير متوقع: ${e.message}"
        )
    }
}
    
    fun getModZipInfo(zipFile: File): Pair<Boolean, String> {
        try {
            val entries = mutableListOf<String>()
            var hasValidStructure = false
            var entryCount = 0
            
            ZipInputStream(zipFile.inputStream().buffered()).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    entryCount++
                    if (entryCount > MAX_ZIP_ENTRIES || entry.name.length > 1_000) {
                        return Pair(false, "ملف ZIP أكبر من حدود الفحص الآمن.")
                    }
                    if (!entry.name.startsWith("__MACOSX/")) {
                        entries.add(entry.name)
                        
                        if (entry.name.endsWith(".so") || 
                            entry.name.endsWith(".dll") || 
                            entry.name.endsWith(".json") ||
                            entry.name.contains("mod.json")) {
                            hasValidStructure = true
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
            
            val info = buildString {
                appendLine("الملفات الموجودة:")
                entries.take(10).forEach { appendLine("  - $it") }
                if (entries.size > 10) {
                    appendLine("  ... و ${entries.size - 10} ملفات أخرى")
                }
            }
            
            return Pair(hasValidStructure || entries.isNotEmpty(), info)
            
        } catch (e: Exception) {
            return Pair(false, "فشل قراءة الملف: ${e.message}")
        }
    }
}