import java.io.File
import java.lang.management.ManagementFactory
import java.text.SimpleDateFormat
import java.util.*

enum class PcvrCheckStatus {
    PASS, WARN, FAIL, UNKNOWN
}

data class PcvrCheckResult(
    val title: String,
    val status: PcvrCheckStatus,
    val explanation: String,
    val solution: String,
    val details: String = ""
)

data class PcvrSystemInfo(
    val windowsVersion: String,
    val is64Bit: Boolean,
    val totalRamGb: Double,
    val cpuName: String,
    val cpuCores: Int,
    val gpuName: String,
    val gpuVramGb: Double,
    val gpuDriverVersion: String,
    val questLinkInstalled: Boolean,
    val steamInstalled: Boolean,
    val steamvrInstalled: Boolean,
    val openXrRuntime: String,
    val usb3Available: Boolean,
    val networkType: String,
    val wifiType: String = ""
)

object PcvrChecker {

    suspend fun runQuickChecks(): Pair<List<PcvrCheckResult>, PcvrSystemInfo> {
        val results = mutableListOf<PcvrCheckResult>()
        val systemInfo = gatherSystemInfo()

        results.add(checkWindowsVersion(systemInfo))
        results.add(checkRam(systemInfo))
        results.add(checkCpu(systemInfo))
        results.add(checkGpu(systemInfo))
        results.add(checkQuestLink(systemInfo))
        results.add(checkSteam(systemInfo))
        results.add(checkSteamvr(systemInfo))
        results.add(checkOpenXr(systemInfo))
        results.add(checkUsb3(systemInfo))

        return results to systemInfo
    }

    suspend fun runAdvancedChecks(): Pair<List<PcvrCheckResult>, PcvrSystemInfo> {
        val results = mutableListOf<PcvrCheckResult>()
        val systemInfo = gatherSystemInfo(advanced = true)

        results.add(checkWindowsVersion(systemInfo))
        results.add(checkRam(systemInfo))
        results.add(checkCpu(systemInfo))
        results.add(checkGpu(systemInfo))
        results.add(checkQuestLink(systemInfo))
        results.add(checkSteam(systemInfo))
        results.add(checkSteamvr(systemInfo))
        results.add(checkOpenXr(systemInfo))
        results.add(checkUsb3(systemInfo))
        results.add(checkNetwork(systemInfo))

        return results to systemInfo
    }

    private fun gatherSystemInfo(advanced: Boolean = false): PcvrSystemInfo {
        val osName = System.getProperty("os.name", "Unknown")
        val osVersion = System.getProperty("os.version", "Unknown")
        val osArch = System.getProperty("os.arch", "Unknown")

        val is64Bit = osArch.contains("64") || System.getProperty("sun.arch.data.model") == "64"

        val runtime = Runtime.getRuntime()
        val totalRamBytes = runtime.totalMemory()
        val totalRamGb = (totalRamBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)) * (runtime.availableProcessors() * 2.0)

        val cpuName = getCpuName()
        val cpuCores = runtime.availableProcessors()

        val gpuInfo = getGpuInfo()
        val gpuName = gpuInfo.first
        val gpuVramGb = gpuInfo.second
        val gpuDriverVersion = getGpuDriverVersion()

        val questLinkInstalled = checkProgramInstalled("Oculus")
        val steamInstalled = checkProgramInstalled("Steam")
        val steamvrInstalled = checkProgramInstalled("SteamVR")

        val openXrRuntime = getOpenXrRuntime()

        val usb3Available = checkUsb3Available()

        val networkInfo = if (advanced) getNetworkInfo() else Pair("", "")
        val networkType = networkInfo.first
        val wifiType = networkInfo.second

        return PcvrSystemInfo(
            windowsVersion = "$osName $osVersion",
            is64Bit = is64Bit,
            totalRamGb = totalRamGb,
            cpuName = cpuName,
            cpuCores = cpuCores,
            gpuName = gpuName,
            gpuVramGb = gpuVramGb,
            gpuDriverVersion = gpuDriverVersion,
            questLinkInstalled = questLinkInstalled,
            steamInstalled = steamInstalled,
            steamvrInstalled = steamvrInstalled,
            openXrRuntime = openXrRuntime,
            usb3Available = usb3Available,
            networkType = networkType,
            wifiType = wifiType
        )
    }

    private fun getCpuName(): String {
        return try {
            val osName = System.getProperty("os.name").lowercase()
            if (osName.contains("win")) {
                val command = arrayOf("wmic", "cpu", "get", "name")
                val output = runOwnedCommand(command.toList())
                output.firstOrNull { it.isNotBlank() && !it.contains("Name") }?.trim() ?: "CPU غير معروف"
            } else {
                "CPU غير معروف"
            }
        } catch (e: Exception) {
            "CPU غير معروف"
        }
    }

    private fun getGpuInfo(): Pair<String, Double> {
        return try {
            val osName = System.getProperty("os.name").lowercase()
            if (osName.contains("win")) {
                val command = arrayOf("wmic", "path", "win32_VideoController", "get", "name,AdapterRAM")
                val output = runOwnedCommand(command.toList())
                
                val gpuLine = output.firstOrNull { it.isNotBlank() && !it.contains("AdapterRAM") }
                if (gpuLine != null) {
                    val parts = gpuLine.trim().split(Regex("\\s+"))
                    val vramBytes = parts.lastOrNull()?.toLongOrNull() ?: 0L
                    val vramGb = vramBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
                    val gpuName = parts.dropLast(1).joinToString(" ").trim()
                    gpuName to vramGb
                } else {
                    "GPU غير معروف" to 0.0
                }
            } else {
                "GPU غير معروف" to 0.0
            }
        } catch (e: Exception) {
            "GPU غير معروف" to 0.0
        }
    }

    private fun getGpuDriverVersion(): String {
        return try {
            val osName = System.getProperty("os.name").lowercase()
            if (osName.contains("win")) {
                val command = arrayOf("wmic", "path", "win32_VideoController", "get", "DriverVersion")
                val output = runOwnedCommand(command.toList())
                output.firstOrNull { it.isNotBlank() && !it.contains("DriverVersion") }?.trim() ?: "غير معروف"
            } else {
                "غير معروف"
            }
        } catch (e: Exception) {
            "غير معروف"
        }
    }

    private fun checkProgramInstalled(programName: String): Boolean {
        return try {
            val osName = System.getProperty("os.name").lowercase()
            if (osName.contains("win")) {
                val programFiles = listOf(
                    System.getenv("ProgramFiles"),
                    System.getenv("ProgramFiles(x86)"),
                    System.getenv("LOCALAPPDATA")
                ).filterNotNull().distinct()

                programFiles.any { dir ->
                    File(dir).listFiles()?.any { 
                        it.isDirectory && it.name.lowercase().contains(programName.lowercase()) 
                    } == true
                }
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun getOpenXrRuntime(): String {
        return try {
            val osName = System.getProperty("os.name").lowercase()
            if (osName.contains("win")) {
                val command = arrayOf("cmd", "/c", "reg", "query", "HKLM\\SOFTWARE\\Khronos\\OpenXR\\1", "/v", "ActiveRuntime")
                val output = runOwnedCommand(command.toList())
                val line = output.firstOrNull { it.contains("ActiveRuntime") }
                
                if (line != null) {
                    val parts = line.split("REG_SZ")
                    if (parts.size > 1) {
                        parts[1].trim()
                    } else {
                        val command2 = arrayOf("cmd", "/c", "reg", "query", "HKCU\\SOFTWARE\\Khronos\\OpenXR\\1", "/v", "ActiveRuntime")
                        val output2 = runOwnedCommand(command2.toList())
                        val line2 = output2.firstOrNull { it.contains("ActiveRuntime") }
                        val parts2 = line2?.split("REG_SZ")
                        if (parts2 != null && parts2.size > 1) {
                            parts2[1].trim()
                        } else {
                            "غير مهيأ"
                        }
                    }
                } else {
                    "غير مهيأ"
                }
            } else {
                "غير مهيأ"
            }
        } catch (e: Exception) {
            "غير مهيأ"
        }
    }

    private fun checkUsb3Available(): Boolean {
        return try {
            val osName = System.getProperty("os.name").lowercase()
            if (osName.contains("win")) {
                val command = arrayOf("wmic", "path", "win32_USBController", "get", "Name")
                val output = runOwnedCommand(command.toList())
                output.any { it.lowercase().contains("usb") && (it.lowercase().contains("3.0") || it.lowercase().contains("3.1") || it.lowercase().contains("3.2")) }
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun getNetworkInfo(): Pair<String, String> {
        return try {
            val osName = System.getProperty("os.name").lowercase()
            if (osName.contains("win")) {
                val command = arrayOf("netsh", "interface", "show", "interface")
                val output = runOwnedCommand(command.toList())
                
                val ethernetLine = output.firstOrNull { it.lowercase().contains("ethernet") && it.contains("connected") }
                val wifiLine = output.firstOrNull { it.lowercase().contains("wi-fi") && it.contains("connected") }
                
                when {
                    ethernetLine != null -> "Ethernet" to ""
                    wifiLine != null -> "Wi-Fi" to ""
                    else -> "غير محدد" to ""
                }
            } else {
                "غير محدد" to ""
            }
        } catch (e: Exception) {
            "غير محدد" to ""
        }
    }

    private fun checkWindowsVersion(info: PcvrSystemInfo): PcvrCheckResult {
        val osName = System.getProperty("os.name").lowercase()
        return when {
            !osName.contains("win") -> PcvrCheckResult(
                title = "نظام التشغيل",
                status = PcvrCheckStatus.FAIL,
                explanation = "نظام التشغيل الحالي: ${info.windowsVersion}",
                solution = "PCVR يتطلب نظام Windows 10 أو 11 فقط."
            )
            osName.contains("windows 11") || osName.contains("windows 10") -> PcvrCheckResult(
                title = "نظام التشغيل",
                status = PcvrCheckStatus.PASS,
                explanation = "نظام التشغيل: ${info.windowsVersion}",
                solution = "مطلوب للتجربة المثلى."
            )
            else -> PcvrCheckResult(
                title = "نظام التشغيل",
                status = PcvrCheckStatus.FAIL,
                explanation = "نظام التشغيل: ${info.windowsVersion}",
                solution = "يرجى الترقية إلى Windows 10 أو 11 لاستخدام PCVR."
            )
        }
    }

    private fun checkRam(info: PcvrSystemInfo): PcvrCheckResult {
        return when {
            info.totalRamGb >= 16.0 -> PcvrCheckResult(
                title = "ذاكرة الوصول العشوائي (RAM)",
                status = PcvrCheckStatus.PASS,
                explanation = "الذاكرة: ${String.format("%.1f", info.totalRamGb)} GB",
                solution = "كافية لتشغيل معظم ألعاب PCVR."
            )
            info.totalRamGb >= 12.0 -> PcvrCheckResult(
                title = "ذاكرة الوصول العشوائي (RAM)",
                status = PcvrCheckStatus.WARN,
                explanation = "الذاكرة: ${String.format("%.1f", info.totalRamGb)} GB",
                solution = "قد تواجه صعوبة في بعض الألعاب الثقيلة. يُنصح بترقية الذاكرة إلى 16GB."
            )
            else -> PcvrCheckResult(
                title = "ذاكرة الوصول العشوائي (RAM)",
                status = PcvrCheckStatus.FAIL,
                explanation = "الذاكرة: ${String.format("%.1f", info.totalRamGb)} GB",
                solution = "ذاكرة غير كافية. PCVR يتطلب على الأقل 16GB من الذاكرة."
            )
        }
    }

    private fun checkCpu(info: PcvrSystemInfo): PcvrCheckResult {
        return PcvrCheckResult(
            title = "المعالج (CPU)",
            status = PcvrCheckStatus.PASS,
            explanation = "المعالج: ${info.cpuName} (${info.cpuCores} أنوية)",
            solution = "يجب أن يكون حديثًا ويدعم AVX2 للأداء الأفضل.",
            details = info.cpuName
        )
    }

    private fun checkGpu(info: PcvrSystemInfo): PcvrCheckResult {
        val isIntegrated = info.gpuName.lowercase().contains("intel") && 
                           (info.gpuName.lowercase().contains("hd graphics") || 
                            info.gpuName.lowercase().contains("uhd graphics") ||
                            info.gpuName.lowercase().contains("iris"))
        
        return when {
            isIntegrated -> PcvrCheckResult(
                title = "كارت الشاشة (GPU)",
                status = PcvrCheckStatus.FAIL,
                explanation = "كارت الشاشة: ${info.gpuName} (مدمج)",
                solution = "PCVR يتطلب كارت شاشة منفصل (dedicated GPU) مثل NVIDIA RTX أو AMD Radeon."
            )
            info.gpuVramGb < 4.0 -> PcvrCheckResult(
                title = "كارت الشاشة (GPU)",
                status = PcvrCheckStatus.WARN,
                explanation = "كارت الشاشة: ${info.gpuName} (VRAM: ${String.format("%.1f", info.gpuVramGb)} GB)",
                solution = "الذاكرة منخفضة. يُنصح بكارت شاشة بذاكرة 6GB على الأقل."
            )
            else -> PcvrCheckResult(
                title = "كارت الشاشة (GPU)",
                status = PcvrCheckStatus.PASS,
                explanation = "كارت الشاشة: ${info.gpuName} (VRAM: ${String.format("%.1f", info.gpuVramGb)} GB)",
                solution = "مناسب لتجربة PCVR.",
                details = info.gpuName
            )
        }
    }

    private fun checkQuestLink(info: PcvrSystemInfo): PcvrCheckResult {
        return if (info.questLinkInstalled) {
            PcvrCheckResult(
                title = "Meta Quest Link",
                status = PcvrCheckStatus.PASS,
                explanation = "مثبت على الجهاز",
                solution = "لازم لتوصيل النظارة بالسلك."
            )
        } else {
            PcvrCheckResult(
                title = "Meta Quest Link",
                status = PcvrCheckStatus.FAIL,
                explanation = "غير مثبت",
                solution = "يرجى تثبيت Meta Quest Link من: https://www.meta.com/quest/setup/"
            )
        }
    }

    private fun checkSteam(info: PcvrSystemInfo): PcvrCheckResult {
        return if (info.steamInstalled) {
            PcvrCheckResult(
                title = "Steam",
                status = PcvrCheckStatus.PASS,
                explanation = "مثبت على الجهاز",
                solution = "لازم للعب ألعاب PCVR."
            )
        } else {
            PcvrCheckResult(
                title = "Steam",
                status = PcvrCheckStatus.FAIL,
                explanation = "غير مثبت",
                solution = "يرجى تثبيت Steam من: https://store.steampowered.com/"
            )
        }
    }

    private fun checkSteamvr(info: PcvrSystemInfo): PcvrCheckResult {
        return if (info.steamvrInstalled) {
            PcvrCheckResult(
                title = "SteamVR",
                status = PcvrCheckStatus.PASS,
                explanation = "مثبت على الجهاز",
                solution = "لازم لتشغيل ألعاب VR."
            )
        } else {
            PcvrCheckResult(
                title = "SteamVR",
                status = PcvrCheckStatus.FAIL,
                explanation = "غير مثبت",
                solution = "يرجى تثبيت SteamVR من Steam (Search: SteamVR)"
            )
        }
    }

    private fun checkOpenXr(info: PcvrSystemInfo): PcvrCheckResult {
        return if (info.openXrRuntime.isNotBlank() && info.openXrRuntime != "غير مهيأ") {
            PcvrCheckResult(
                title = "OpenXR Active Runtime",
                status = PcvrCheckStatus.PASS,
                explanation = "مهيأ: ${info.openXrRuntime}",
                solution = "مطلوب للألعاب المدعومة بـ OpenXR.",
                details = info.openXrRuntime
            )
        } else {
            PcvrCheckResult(
                title = "OpenXR Active Runtime",
                status = PcvrCheckStatus.WARN,
                explanation = "غير مهيأ",
                solution = "يرجى فتح SteamVR أو Meta Quest Link وتفعيل OpenXR من الإعدادات."
            )
        }
    }

    private fun checkUsb3(info: PcvrSystemInfo): PcvrCheckResult {
        return if (info.usb3Available) {
            PcvrCheckResult(
                title = "USB 3.x",
                status = PcvrCheckStatus.PASS,
                explanation = "مدعوم على الجهاز",
                solution = "مطلوب لتوصيل النظارة بالسكر الصحيح."
            )
        } else {
            PcvrCheckResult(
                title = "USB 3.x",
                status = PcvrCheckStatus.WARN,
                explanation = "لم يتم اكتشاف USB 3.x",
                solution = "يرجى التأكد من توصيل النظارة بمنفذ USB 3.0 أو أعلى."
            )
        }
    }

    private fun checkNetwork(info: PcvrSystemInfo): PcvrCheckResult {
        return when {
            info.networkType == "Ethernet" -> PcvrCheckResult(
                title = "نوع الاتصال",
                status = PcvrCheckStatus.PASS,
                explanation = "Ethernet (سلكي)",
                solution = "الأفضل لتجربة PCVR."
            )
            info.networkType == "Wi-Fi" -> PcvrCheckResult(
                title = "نوع الاتصال",
                status = PcvrCheckStatus.WARN,
                explanation = "Wi-Fi (لاسلكي)",
                solution = "Ethernet (السلكي) أفضل من Wi-Fi لتجربة PCVR."
            )
            else -> PcvrCheckResult(
                title = "نوع الاتصال",
                status = PcvrCheckStatus.UNKNOWN,
                explanation = "غير محدد",
                solution = "Ethernet (السلكي) أفضل من Wi-Fi لتجربة PCVR."
            )
        }
    }

    fun generateReport(checks: List<PcvrCheckResult>, systemInfo: PcvrSystemInfo): String {
        val sb = StringBuilder()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        sb.appendLine("=".repeat(60))
        sb.appendLine("تقرير فحص جاهزية PCVR")
        sb.appendLine("وقت الفحص: $timestamp")
        sb.appendLine("=".repeat(60))
        sb.appendLine()

        sb.appendLine("معلومات النظام:")
        sb.appendLine("- نظام التشغيل: ${systemInfo.windowsVersion}")
        sb.appendLine("- 64-bit: ${if (systemInfo.is64Bit) "نعم" else "لا"}")
        sb.appendLine("- الذاكرة: ${String.format("%.1f", systemInfo.totalRamGb)} GB")
        sb.appendLine("- المعالج: ${systemInfo.cpuName} (${systemInfo.cpuCores} أنوية)")
        sb.appendLine("- كارت الشاشة: ${systemInfo.gpuName} (VRAM: ${String.format("%.1f", systemInfo.gpuVramGb)} GB)")
        sb.appendLine("- إصدار التعريف: ${systemInfo.gpuDriverVersion}")
        sb.appendLine("- Meta Quest Link: ${if (systemInfo.questLinkInstalled) "مثبت" else "غير مثبت"}")
        sb.appendLine("- Steam: ${if (systemInfo.steamInstalled) "مثبت" else "غير مثبت"}")
        sb.appendLine("- SteamVR: ${if (systemInfo.steamvrInstalled) "مثبت" else "غير مثبت"}")
        sb.appendLine("- OpenXR Runtime: ${systemInfo.openXrRuntime}")
        sb.appendLine("- USB 3.x: ${if (systemInfo.usb3Available) "مدعوم" else "غير مدعوم"}")
        sb.appendLine("- نوع الاتصال: ${systemInfo.networkType}")
        sb.appendLine()

        sb.appendLine("نتائج الفحص:")
        sb.appendLine("-".repeat(60))
        checks.forEach { check ->
            val statusSymbol = when (check.status) {
                PcvrCheckStatus.PASS -> "✅"
                PcvrCheckStatus.WARN -> "⚠️"
                PcvrCheckStatus.FAIL -> "❌"
                PcvrCheckStatus.UNKNOWN -> "❓"
            }
            sb.appendLine("$statusSymbol ${check.title}")
            sb.appendLine("   التفاصيل: ${check.explanation}")
            sb.appendLine("   الحل: ${check.solution}")
            sb.appendLine()
        }

        sb.appendLine("=".repeat(60))
        sb.appendLine("إخلاء مسؤولية: الأداء يختلف حسب اللعبة وإعدادات الرسوميات.")
        sb.appendLine("=".repeat(60))

        return sb.toString()
    }

    fun isReady(checks: List<PcvrCheckResult>): Boolean {
        return checks.all { it.status != PcvrCheckStatus.FAIL }
    }
}
