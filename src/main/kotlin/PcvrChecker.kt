import java.io.InputStream
import java.math.BigInteger
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.io.IOException
import java.io.FileNotFoundException
import java.util.Locale
import java.util.Base64
import java.util.concurrent.TimeUnit

enum class PcvrCheckStatus {
    PASS, WARN, FAIL, UNKNOWN
}

const val META_PCVR_REQUIREMENTS_URL = "https://www.meta.com/help/quest/140991407990979/"

data class PcvrCheckResult(
    val title: String,
    val status: PcvrCheckStatus,
    val explanation: String,
    val solution: String,
    val details: String = "",
    /** The value detected by the probe, kept separate from the human explanation. */
    val detected: String = "",
    /** The minimum/recommended value used when evaluating this component. */
    val minimum: String = ""
)

/*
 * The first fields are kept source compatible with the original checker.  A
 * false value in one of those fields is not necessarily a negative result:
 * the corresponding *Known field says whether Windows supplied an answer.
 */
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
    val wifiType: String = "",
    val windowsBuild: String = "",
    val windowsArchitecture: String = "",
    val cpuLogicalProcessors: Int = 0,
    val cpuArchitecture: String = "",
    val availableRamGb: Double = 0.0,
    val systemDriveFreeGb: Double = 0.0,
    val systemDriveFreePercent: Double = 0.0,
    val gpuKnown: Boolean = true,
    val questLinkKnown: Boolean = true,
    val questServiceStatus: String = "",
    val steamKnown: Boolean = true,
    val steamvrKnown: Boolean = true,
    val openXrKnown: Boolean = true,
    val usb3Known: Boolean = true,
    val usbControllerDetails: String = "",
    val networkKnown: Boolean = true,
    val networkAdapterName: String = "",
    val networkLinkSpeedMbps: Double = 0.0,
    val gpuVramKnown: Boolean = true,
    val gpuAdapterDetails: String = ""
)

data class PcvrGpuData(
    val name: String,
    val adapterRamBytes: Long? = null,
    val driverVersion: String? = null
)

/**
 * Parse the stable, read-only CSV shape emitted by nvidia-smi.  The parser is
 * intentionally independent of Windows and of ProcessBuilder so it can be
 * tested with captured output.  nvidia-smi reports memory in MiB for this
 * query; a unit suffix is also accepted for older/local wrappers.
 */
fun parseNvidiaSmiOutput(lines: List<String>): List<PcvrGpuData> {
    return lines.mapNotNull { raw ->
        val line = raw.removePrefix("\uFEFF").trim()
        if (line.isBlank() || line.startsWith("name,", true)) return@mapNotNull null
        val fields = line.split(',')
        if (fields.size < 3) return@mapNotNull null
        val name = fields[0].trim().trim('"').takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val memory = parseNvidiaSmiMemoryBytes(fields[1].trim()) ?: return@mapNotNull null
        val driver = fields.drop(2).joinToString(",").trim().trim('"').ifBlank { null }
        PcvrGpuData(name, memory, driver)
    }
}

fun parseNvidiaSmi(lines: List<String>): List<PcvrGpuData> = parseNvidiaSmiOutput(lines)

private fun parseNvidiaSmiMemoryBytes(value: String): Long? {
    val text = value.trim().replace(",", "")
    val number = Regex("""\d+(?:\.\d+)?""").find(text)?.value?.toDoubleOrNull() ?: return null
    val multiplier = when {
        text.contains("gib", true) -> BYTES_PER_GIB
        text.contains("gb", true) -> 1_000_000_000.0
        text.contains("kib", true) -> 1024.0
        else -> BYTES_PER_MIB
    }
    return (number * multiplier).toLong().takeIf { it > 0L }
}

private const val BYTES_PER_MIB = 1024.0 * 1024.0
private const val BYTES_PER_GIB = 1024.0 * 1024.0 * 1024.0

/** Extract ProcessorNameString from reg.exe output without depending on Windows APIs. */
fun parseCpuRegistryProcessorName(lines: List<String>): String? {
    val valuePattern = Regex(
        """^\s*ProcessorNameString\s+(?:REG_\w+)\s+(.+?)\s*$""",
        RegexOption.IGNORE_CASE
    )
    return lines.asSequence()
        .mapNotNull { valuePattern.find(it)?.groupValues?.getOrNull(1)?.trim() }
        .firstOrNull { it.isNotBlank() }
}

/** Alias with a protocol-oriented name useful to callers and tests. */
fun parseCpuRegistryOutput(lines: List<String>): String? = parseCpuRegistryProcessorName(lines)

/**
 * The probe deliberately has no platform types.  This makes its parser useful
 * for tests and, more importantly, keeps command failures distinguishable
 * from a real "not installed" answer.
 */
data class PcvrProbeData(
    val osCaption: String? = null,
    val osBuild: String? = null,
    val osArchitecture: String? = null,
    val cpuName: String? = null,
    val cpuCores: Int? = null,
    val cpuLogicalProcessors: Int? = null,
    val cpuArchitecture: String? = null,
    val totalRamBytes: Long? = null,
    val availableRamKb: Long? = null,
    val gpuName: String? = null,
    val gpuVramBytes: Long? = null,
    val gpuDriver: String? = null,
    val driveFreeBytes: Long? = null,
    val driveSizeBytes: Long? = null,
    val questInstalled: Boolean? = null,
    val questPath: String? = null,
    val ovrService: String? = null,
    val steamInstalled: Boolean? = null,
    val steamvrInstalled: Boolean? = null,
    val openXrRuntime: String? = null,
    val usbControllers: String? = null,
    val networkName: String? = null,
    val networkDescription: String? = null,
    val networkLinkSpeed: String? = null,
    val gpus: List<PcvrGpuData> = emptyList()
)

/**
 * Parses the deliberately simple KEY<TAB>VALUE protocol emitted by the
 * PowerShell probe.  KEY=VALUE is accepted too, which is convenient for
 * focused tests and for copying output from a console.
 */
fun parsePcvrProbeOutput(lines: List<String>): PcvrProbeData {
    val values = linkedMapOf<String, String>()
    val gpus = mutableListOf<PcvrGpuData>()
    lines.forEach { raw ->
        val line = raw.removePrefix("\uFEFF").trim()
        if (line.isEmpty()) return@forEach
        val fields = if ('\t' in line) line.split('\t')
        else line.split('=', limit = 2)
        if (fields.size >= 2 && fields[0].trim().equals("GPU", true)) {
            val name = fields[1].trim()
            if (name.isNotBlank()) {
                gpus += PcvrGpuData(
                    name = name,
                    adapterRamBytes = parsePcvrByteCount(fields.getOrNull(2)?.trim()),
                    driverVersion = fields.getOrNull(3)?.trim()?.ifBlank { null }
                )
            }
            return@forEach
        }
        val split = if ('\t' in line) line.split('\t', limit = 2)
        else line.split('=', limit = 2)
        if (split.size == 2 && split[0].trim().matches(Regex("[A-Z0-9_]+"))) {
            val value = split[1].trim()
            if (value.isNotEmpty() && !value.equals("__UNKNOWN__", true)) {
                values[split[0].trim()] = value
            }
        }
    }
    fun s(key: String) = values[key]
    fun i(key: String) = s(key)?.toIntOrNull()
    fun l(key: String) = s(key)?.toLongOrNull()
    fun b(key: String) = s(key)?.let {
        when (it.lowercase(Locale.ROOT)) {
            "true", "yes", "1" -> true
            "false", "no", "0" -> false
            else -> null
        }
    }
    return PcvrProbeData(
        osCaption = s("OS_CAPTION"), osBuild = s("OS_BUILD"),
        osArchitecture = s("OS_ARCH"), cpuName = s("CPU_NAME"),
        cpuCores = i("CPU_CORES"), cpuLogicalProcessors = i("CPU_LOGICAL"),
        cpuArchitecture = s("CPU_ARCH"), totalRamBytes = l("RAM_TOTAL_BYTES"),
        availableRamKb = l("RAM_AVAILABLE_KB"), gpuName = s("GPU_NAME"),
        gpuVramBytes = l("GPU_VRAM_BYTES"), gpuDriver = s("GPU_DRIVER"),
        driveFreeBytes = l("DRIVE_FREE_BYTES"), driveSizeBytes = l("DRIVE_SIZE_BYTES"),
        questInstalled = b("QUEST_INSTALLED"), questPath = s("QUEST_PATH"),
        ovrService = s("OVR_SERVICE"), steamInstalled = b("STEAM_INSTALLED"),
        steamvrInstalled = b("STEAMVR_INSTALLED"), openXrRuntime = s("OPENXR_RUNTIME"),
        usbControllers = s("USB_CONTROLLERS"), networkName = s("NET_NAME"),
        networkDescription = s("NET_DESCRIPTION"), networkLinkSpeed = s("NET_LINK_SPEED"),
        gpus = gpus
    )
}

/**
 * AdapterRAM is exposed by some WMI providers as a signed UInt32, while
 * other providers print an unsigned number or a decimal string.  Never let a
 * malformed/overflowing value turn into a negative VRAM amount.
 */
private fun parsePcvrByteCount(value: String?): Long? {
    val text = value?.trim()?.removeSuffix("L")?.removeSuffix("l").orEmpty()
    if (text.isBlank() || text.equals("__UNKNOWN__", true)) return null
    return runCatching {
        val number = if (text.startsWith("0x", true)) {
            BigInteger(text.substring(2), 16)
        } else {
            BigInteger(text)
        }
        if (number.signum() < 0 || number > BigInteger.valueOf(Long.MAX_VALUE)) null
        else number.toLong().takeIf { it > 0L }
    }.getOrNull()
}

/**
 * Prefer an actual discrete adapter over iGPU, remote desktop, and virtual
 * display adapters.  The score uses the model name rather than AdapterRAM:
 * the latter is a uint32 in many Windows providers and is not safe for
 * comparing modern cards.
 */
fun selectPcvrGpu(adapters: List<PcvrGpuData>): PcvrGpuData? {
    if (adapters.isEmpty()) return null
    return adapters.maxWithOrNull(
        compareBy<PcvrGpuData> { gpuClassScore(it.name) }
            .thenBy { gpuModelScore(it.name) }
    )
}

private fun gpuClassScore(name: String): Int {
    val n = name.lowercase(Locale.ROOT)
    if (n.contains("remote") || n.contains("virtual") || n.contains("vmware") ||
        n.contains("virtualbox") || n.contains("displaylink") ||
        n.contains("microsoft basic") || n.contains("basic render")) return 0
    if (n.contains("rtx") || n.contains("geforce") || n.contains("quadro") ||
        n.contains("tesla") || n.contains("radeon rx") || n.contains("radeon pro") ||
        n.contains("firepro") || n.contains("instinct") || n.contains("intel arc")) return 3
    if (n.contains("intel hd") || n.contains("intel uhd") || n.contains("intel iris") ||
        n.contains("radeon graphics") || n.contains("vega")) return 1
    return 2
}

private fun gpuModelScore(name: String): Int {
    val n = name.lowercase(Locale.ROOT)
    val model = Regex("""(?:rtx|geforce|rx|arc)\s*([0-9]{3,4})""")
        .find(n)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
    return model
}

object PcvrChecker {
    private const val UNKNOWN = "غير معروف"
    private const val POWERSHELL_TIMEOUT_MS = 20_000L
    private const val NVIDIA_SMI_TIMEOUT_MS = 4_000L
    private const val BYTES_PER_GB = 1024.0 * 1024.0 * 1024.0

    suspend fun runQuickChecks(): Pair<List<PcvrCheckResult>, PcvrSystemInfo> =
        runChecks(advanced = false)

    suspend fun runAdvancedChecks(): Pair<List<PcvrCheckResult>, PcvrSystemInfo> =
        runChecks(advanced = true)

    private fun runChecks(advanced: Boolean): Pair<List<PcvrCheckResult>, PcvrSystemInfo> {
        val info = gatherSystemInfo(advanced)
        val results = evaluateRequirements(info).toMutableList()
        if (advanced) results += checkNetwork(info)
        return results.map { withRequirements(it, info) } to info
    }

    /** Evaluate a supplied snapshot without probing the host again. */
    fun evaluateRequirements(info: PcvrSystemInfo): List<PcvrCheckResult> =
        listOf(
            checkWindowsVersion(info), checkRam(info), checkCpu(info), checkGpu(info),
            checkQuestLink(info), checkSteam(info), checkSteamvr(info), checkOpenXr(info),
            checkUsb3(info), checkSystemDrive(info)
        ).map { withRequirements(it, info) }

    private fun gatherSystemInfo(advanced: Boolean = false): PcvrSystemInfo {
        val windows = System.getProperty("os.name", "").contains("win", ignoreCase = true)
        val data = if (windows) {
            val command = runPowerShell(PROBE_SCRIPT)
            // A failed command can still have useful section output before a
            // provider failed.  With no stdout at all, discard it and rely
            // exclusively on the independent fallbacks.
            val primary = if (command.stdout.isEmpty() &&
                (command.timedOut || command.exitCode != 0)
            ) {
                PcvrProbeData()
            } else {
                parsePcvrProbeOutput(command.stdout)
            }
            mergePcvrProbeData(
                primary,
                collectPcvrFallbacks()
            )
        } else {
            PcvrProbeData()
        }
        val networkName = data.networkName.orEmpty()
        val networkDescription = data.networkDescription.orEmpty()
        val networkType = when {
            networkName.contains("ethernet", true) || networkDescription.contains("ethernet", true) ->
                "Ethernet"
            networkName.contains("wi-fi", true) || networkName.contains("wifi", true) ||
                networkDescription.contains("wireless", true) -> "Wi-Fi"
            networkName.isNotBlank() -> networkName
            else -> UNKNOWN
        }
        val usb = data.usbControllers
        val usb3 = usb?.let { Regex("(?i)usb\\s*[3.]|xhci|superspeed").containsMatchIn(it) } ?: false
        val openXr = data.openXrRuntime ?: UNKNOWN
        /*
         * nvidia-smi is a read-only, bounded query and its memory value is
         * reported by the NVIDIA driver rather than the frequently truncated
         * Win32_VideoController.AdapterRAM field.  Use it as the complete
         * NVIDIA adapter list whenever it returns valid rows.
         */
        val nvidiaSmiGpus = if (windows) queryNvidiaSmi() else emptyList()
        val gpuAdapters = nvidiaSmiGpus.ifEmpty {
            data.gpus.ifEmpty {
                listOfNotNull(data.gpuName?.let {
                    PcvrGpuData(it, data.gpuVramBytes, data.gpuDriver)
                })
            }
        }
        val selectedGpu = selectPcvrGpu(gpuAdapters)
        val selectedGpuRam = selectedGpu?.adapterRamBytes ?: data.gpuVramBytes
        val selectedGpuDriver = selectedGpu?.driverVersion ?: data.gpuDriver
        val gpuVramKnown = selectedGpuRam?.let(::isReliableGpuVram) == true &&
            (nvidiaSmiGpus.isNotEmpty() || !isSuspiciousModernGpuVram(
                selectedGpu?.name.orEmpty(), selectedGpuRam
            ))
        val freeGb = data.driveFreeBytes?.toDouble()?.div(BYTES_PER_GB) ?: 0.0
        val drivePercent = if (data.driveFreeBytes != null && data.driveSizeBytes != null &&
            data.driveSizeBytes > 0L
        ) data.driveFreeBytes.toDouble() * 100.0 / data.driveSizeBytes else 0.0
        return PcvrSystemInfo(
            windowsVersion = listOfNotNull(data.osCaption, data.osBuild?.let { "build $it" })
                .joinToString(" ").ifBlank { UNKNOWN },
            is64Bit = data.osArchitecture?.contains("64", true) == true,
            totalRamGb = data.totalRamBytes?.toDouble()?.div(BYTES_PER_GB) ?: 0.0,
            cpuName = data.cpuName ?: UNKNOWN, cpuCores = data.cpuCores?.takeIf { it > 0 } ?: 0,
            gpuName = selectedGpu?.name ?: UNKNOWN,
            gpuVramGb = if (gpuVramKnown) selectedGpuRam!!.toDouble().div(BYTES_PER_GB) else 0.0,
            gpuDriverVersion = selectedGpuDriver ?: UNKNOWN,
            questLinkInstalled = data.questInstalled == true,
            steamInstalled = data.steamInstalled == true,
            steamvrInstalled = data.steamvrInstalled == true,
            openXrRuntime = openXr, usb3Available = usb3, networkType = networkType,
            wifiType = if (networkType == "Wi-Fi") networkDescription else "",
            windowsBuild = data.osBuild.orEmpty(), windowsArchitecture = data.osArchitecture.orEmpty(),
            cpuLogicalProcessors = data.cpuLogicalProcessors?.takeIf { it > 0 } ?: 0,
            cpuArchitecture = data.cpuArchitecture?.let(::cpuArchitectureName).orEmpty(),
            availableRamGb = data.availableRamKb?.toDouble()?.div(1024.0 * 1024.0) ?: 0.0,
            systemDriveFreeGb = freeGb, systemDriveFreePercent = drivePercent,
            gpuKnown = selectedGpu != null, questLinkKnown = data.questInstalled != null,
            questServiceStatus = data.ovrService.orEmpty(), steamKnown = data.steamInstalled != null,
            steamvrKnown = data.steamvrInstalled != null, openXrKnown = data.openXrRuntime != null,
            usb3Known = usb != null, usbControllerDetails = usb.orEmpty(),
            networkKnown = networkName.isNotBlank(), networkAdapterName = networkName,
            networkLinkSpeedMbps = parseLinkSpeedMbps(data.networkLinkSpeed),
            gpuVramKnown = gpuVramKnown,
            gpuAdapterDetails = gpuAdapters.joinToString("; ") { it.name }
        )
    }

    private fun isReliableGpuVram(bytes: Long): Boolean =
        // Win32_VideoController.AdapterRAM is a UInt32 in common providers;
        // 0xffffffff is its overflow/sentinel value.  Do not reject genuine
        // 8/12/16GB values supplied by a provider that exposes UInt64.
        bytes > 0L && bytes <= 256L * 1024L * 1024L * 1024L && bytes != 0xFFFF_FFFFL

    private fun isSuspiciousModernGpuVram(name: String, bytes: Long): Boolean {
        if (bytes > 4L * 1024L * 1024L * 1024L) return false
        val n = name.lowercase(Locale.ROOT)
        return (n.contains("nvidia") && Regex("""\b(?:rtx|geforce)\s*(?:30|40|50)\d{2}""").containsMatchIn(n)) ||
            (n.contains("radeon") && Regex("""\brx\s*(?:5|6|7)\d{3}""").containsMatchIn(n))
    }

    private fun queryNvidiaSmi(): List<PcvrGpuData> {
        val result = runCatching {
            runPcvrCommand(
                listOf(
                    "nvidia-smi",
                    "--query-gpu=name,memory.total,driver_version",
                    "--format=csv,noheader,nounits"
                ),
                NVIDIA_SMI_TIMEOUT_MS
            )
        }.getOrNull() ?: return emptyList()
        if (result.timedOut || result.exitCode != 0) return emptyList()
        return parseNvidiaSmiOutput(result.stdout)
    }

    /**
     * A command result intentionally keeps stdout, stderr, exit status and
     * timeout separate.  The old implementation merged stderr into stdout,
     * making a provider error look like an empty successful probe.
     */
    internal data class PcvrCommandResult(
        val stdout: List<String>,
        val stderr: List<String>,
        val exitCode: Int?,
        val timedOut: Boolean
    )

    internal data class PcvrPresenceResult(
        val present: Boolean?,
        val evidence: String? = null
    )

    private fun runPowerShell(script: String): PcvrCommandResult {
        val executable = if (System.getenv("SystemRoot").isNullOrBlank()) "powershell.exe"
        else "${System.getenv("SystemRoot")}\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"
        /*
         * EncodedCommand is UTF-16LE by PowerShell convention.  Passing the
         * script as one ProcessBuilder argument is already shell-safe, but
         * encoding it also protects paths containing quotes, ampersands, or
         * Arabic text from PowerShell's command-line parser.
         */
        val encoded = Base64.getEncoder().encodeToString(
            script.toByteArray(StandardCharsets.UTF_16LE)
        )
        return runCatching {
            runPcvrCommand(
                listOf(
                    executable, "-NoLogo", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded
                ),
                POWERSHELL_TIMEOUT_MS
            )
        }.getOrElse {
            PcvrCommandResult(emptyList(), listOf(it.javaClass.simpleName), null, false)
        }
    }

    private fun runPcvrCommand(command: List<String>, timeoutMs: Long): PcvrCommandResult {
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        OwnedProcessRegistry.add(process)
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()
        val outThread = readPcvrStream(process.inputStream, stdout)
        val errThread = readPcvrStream(process.errorStream, stderr)
        var timedOut = false
        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                timedOut = true
                process.destroy()
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
                process.waitFor(2, TimeUnit.SECONDS)
            }
            outThread.join(2_000)
            errThread.join(2_000)
            return PcvrCommandResult(
                stdout.toList(), stderr.toList(),
                runCatching { process.exitValue() }.getOrNull(), timedOut
            )
        } finally {
            if (process.isAlive) process.destroyForcibly()
            outThread.join(500)
            errThread.join(500)
            OwnedProcessRegistry.remove(process)
        }
    }

    private fun readPcvrStream(stream: InputStream, destination: MutableList<String>): Thread {
        val thread = Thread {
            runCatching {
                stream.bufferedReader().useLines { lines ->
                    lines.take(4_000).forEach { line ->
                        synchronized(destination) {
                            if (destination.size < 4_000) destination += line
                        }
                    }
                }
            }
        }
        thread.isDaemon = true
        thread.start()
        return thread
    }

    /**
     * Fallbacks deliberately use only read-only, user-accessible sources.
     * A failed PowerShell provider therefore cannot turn an unavailable
     * section into "not installed".
     */
    internal fun mergePcvrProbeData(primary: PcvrProbeData, fallback: PcvrProbeData): PcvrProbeData =
        PcvrProbeData(
            osCaption = primary.osCaption ?: fallback.osCaption,
            osBuild = primary.osBuild ?: fallback.osBuild,
            osArchitecture = primary.osArchitecture ?: fallback.osArchitecture,
            cpuName = primary.cpuName ?: fallback.cpuName,
            cpuCores = primary.cpuCores ?: fallback.cpuCores,
            cpuLogicalProcessors = primary.cpuLogicalProcessors ?: fallback.cpuLogicalProcessors,
            cpuArchitecture = primary.cpuArchitecture ?: fallback.cpuArchitecture,
            totalRamBytes = primary.totalRamBytes ?: fallback.totalRamBytes,
            availableRamKb = primary.availableRamKb ?: fallback.availableRamKb,
            gpuName = primary.gpuName ?: fallback.gpuName,
            gpuVramBytes = primary.gpuVramBytes ?: fallback.gpuVramBytes,
            gpuDriver = primary.gpuDriver ?: fallback.gpuDriver,
            driveFreeBytes = primary.driveFreeBytes ?: fallback.driveFreeBytes,
            driveSizeBytes = primary.driveSizeBytes ?: fallback.driveSizeBytes,
            questInstalled = primary.questInstalled ?: fallback.questInstalled,
            questPath = primary.questPath ?: fallback.questPath,
            ovrService = primary.ovrService ?: fallback.ovrService,
            steamInstalled = primary.steamInstalled ?: fallback.steamInstalled,
            steamvrInstalled = primary.steamvrInstalled ?: fallback.steamvrInstalled,
            openXrRuntime = primary.openXrRuntime ?: fallback.openXrRuntime,
            usbControllers = primary.usbControllers ?: fallback.usbControllers,
            networkName = primary.networkName ?: fallback.networkName,
            networkDescription = primary.networkDescription ?: fallback.networkDescription,
            networkLinkSpeed = primary.networkLinkSpeed ?: fallback.networkLinkSpeed,
            gpus = if (primary.gpus.isNotEmpty()) primary.gpus else fallback.gpus
        )

    private fun collectPcvrFallbacks(): PcvrProbeData {
        val root = runCatching {
            val drive = System.getenv("SystemDrive")?.trim()?.takeIf { it.isNotBlank() } ?: "C:"
            Path.of(if (drive.endsWith("\\") || drive.endsWith("/")) drive else "$drive\\")
        }.getOrNull()
        val store = root?.let { runCatching { Files.getFileStore(it) }.getOrNull() }
        val logical = Runtime.getRuntime().availableProcessors().takeIf { it > 0 }
        val arch = System.getProperty("os.arch").orEmpty().ifBlank { null }
        /*
         * PROCESSOR_IDENTIFIER is often generic on branded laptops.  The
         * registry value is a read-only Windows fallback and contains the
         * actual model string when CIM is unavailable.
         */
        val registryCpuName = registryValue(
            "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0",
            "ProcessorNameString"
        )
        val cpuName = registryCpuName?.trim()?.ifBlank { null }
            ?: System.getenv("PROCESSOR_IDENTIFIER")?.trim()?.ifBlank { null }

        val questCandidates = listOfNotNull(
            System.getenv("ProgramFiles")?.let { Path.of(it, "Oculus") },
            System.getenv("ProgramFiles(x86)")?.let { Path.of(it, "Oculus") },
            System.getenv("ProgramFiles")?.let { Path.of(it, "Meta Quest Link") },
            System.getenv("LOCALAPPDATA")?.let { Path.of(it, "Oculus") }
        )
        val questRegistryKeys = listOf(
            "HKLM\\SOFTWARE\\Meta\\Oculus",
            "HKLM\\SOFTWARE\\WOW6432Node\\Oculus VR, LLC\\Oculus",
            "HKLM\\SOFTWARE\\WOW6432Node\\Meta\\Oculus"
        )
        val questRegistry = questRegistryKeys.map(::registryPresence)
        val questPaths = questCandidates.map { it to pathPresence(it) }
        val questPath = questPaths.firstOrNull { it.second == true }?.first

        val steamRoots = mutableListOf<Path>()
        registryValue("HKCU\\SOFTWARE\\Valve\\Steam", "SteamPath")?.let { steamRoots.add(Path.of(it)) }
        registryValue("HKLM\\SOFTWARE\\Valve\\Steam", "InstallPath")?.let { steamRoots.add(Path.of(it)) }
        listOfNotNull(
            System.getenv("ProgramFiles")?.let { Path.of(it, "Steam") },
            System.getenv("ProgramFiles(x86)")?.let { Path.of(it, "Steam") }
        ).forEach { steamRoots.add(it) }
        val steamKeys = listOf(
            "HKCU\\SOFTWARE\\Valve\\Steam",
            "HKLM\\SOFTWARE\\Valve\\Steam",
            "HKLM\\SOFTWARE\\WOW6432Node\\Valve\\Steam"
        ).map(::registryPresence)
        val steamPaths = steamRoots.map { it to pathPresence(it) }
        val steamRoot = steamPaths.firstOrNull { it.second == true }?.first
        val steamVrRegistry = listOf(
            "HKCU\\SOFTWARE\\Valve\\Steam\\Apps\\250820",
            "HKLM\\SOFTWARE\\Valve\\Steam\\Apps\\250820"
        ).map(::registryPresence)
        val steamVrPaths = steamRoots.map {
            it.resolve("steamapps").resolve("common").resolve("SteamVR") to
                pathPresence(it.resolve("steamapps").resolve("common").resolve("SteamVR"))
        }
        val staticSteamVrPaths = listOfNotNull(
            System.getenv("ProgramFiles")?.let { Path.of(it, "Steam", "steamapps", "common", "SteamVR") },
            System.getenv("ProgramFiles(x86)")?.let { Path.of(it, "Steam", "steamapps", "common", "SteamVR") }
        ).map { it to pathPresence(it) }

        val openXr = listOf(
            "HKCU\\SOFTWARE\\Khronos\\OpenXR\\1",
            "HKLM\\SOFTWARE\\Khronos\\OpenXR\\1",
            "HKLM\\SOFTWARE\\WOW6432Node\\Khronos\\OpenXR\\1"
        ).firstNotNullOfOrNull { registryValue(it, "ActiveRuntime") }

        val service = queryOvrService()
        val network = findPcvrNetwork()
        return PcvrProbeData(
            osCaption = listOfNotNull(
                System.getProperty("os.name")?.takeIf { it.isNotBlank() },
                System.getProperty("os.version")?.takeIf { it.isNotBlank() }
            ).joinToString(" ").ifBlank { null },
            osArchitecture = arch,
            cpuName = cpuName,
            cpuLogicalProcessors = logical,
            cpuArchitecture = arch,
            driveFreeBytes = store?.let { runCatching { it.usableSpace }.getOrNull() },
            driveSizeBytes = store?.let { runCatching { it.totalSpace }.getOrNull() },
            questInstalled = combinePcvrPresence(
                questRegistry.map { it.present } + questPaths.map { it.second }
            ),
            questPath = questPath?.toString() ?: questRegistry.firstOrNull { it.present == true }?.evidence,
            ovrService = service,
            steamInstalled = combinePcvrPresence(
                steamKeys.map { it.present } + steamPaths.map { it.second }
            ),
            steamvrInstalled = combinePcvrPresence(
                steamVrRegistry.map { it.present } +
                    steamVrPaths.map { it.second } + staticSteamVrPaths.map { it.second }
            ),
            openXrRuntime = openXr,
            networkName = network?.first,
            networkDescription = network?.second
        )
    }

    internal fun combinePcvrPresence(probes: List<Boolean?>): Boolean? {
        if (probes.isEmpty()) return null
        if (probes.any { it == true }) return true
        return if (probes.all { it == false }) false else null
    }

    private fun pathPresence(path: Path): Boolean? =
        try {
            Files.readAttributes(path, BasicFileAttributes::class.java)
            true
        } catch (_: NoSuchFileException) {
            false
        } catch (_: FileNotFoundException) {
            false
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

    internal fun registryPresence(result: PcvrCommandResult?): PcvrPresenceResult {
        if (result == null || result.timedOut || result.exitCode == null) {
            return PcvrPresenceResult(null)
        }
        if (result.exitCode == 0) {
            return PcvrPresenceResult(true)
        }
        val output = (result.stdout + result.stderr).joinToString(" ")
        val notFound = output.contains("unable to find the specified registry", true) ||
            output.contains("specified registry key or value", true) ||
            output.contains("specified key or value", true)
        return if (notFound) PcvrPresenceResult(false) else PcvrPresenceResult(null)
    }

    private fun registryPresence(key: String): PcvrPresenceResult {
        val result = runRegistryCommand(listOf("reg.exe", "query", key))
        return registryPresence(result).let {
            if (it.present == true) it.copy(evidence = key) else it
        }
    }

    private fun registryValue(key: String, value: String): String? {
        val attempts = listOf(
            listOf("reg.exe", "query", key, "/v", value, "/reg:64"),
            listOf("reg.exe", "query", key, "/v", value, "/reg:32"),
            listOf("reg.exe", "query", key, "/v", value)
        )
        for (command in attempts) {
            val result = runRegistryCommand(command) ?: continue
            if (result.exitCode != 0) continue
            result.stdout.forEach { line ->
                val match = Regex("""^\s*$value\s+\S+\s+(.+?)\s*$""", RegexOption.IGNORE_CASE)
                    .find(line)
                if (match != null) return match.groupValues[1].trim().takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private fun runRegistryCommand(command: List<String>): PcvrCommandResult? =
        runCatching { runPcvrCommand(command, 3_000) }.getOrNull()

    private fun queryOvrService(): String? {
        val result = runRegistryCommand(listOf("sc.exe", "query", "OVRService")) ?: return null
        if (result.exitCode != 0) {
            val absent = result.stdout.any {
                it.contains("1060") || it.contains("does not exist", true)
            }
            return if (absent) "NotInstalled" else null
        }
        val state = result.stdout.firstNotNullOfOrNull { line ->
            Regex("""STATE\s*:\s*\d+\s+(\w+)""", RegexOption.IGNORE_CASE)
                .find(line)?.groupValues?.getOrNull(1)
        }
        return state ?: "Unknown"
    }

    private fun findPcvrNetwork(): Pair<String, String>? {
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        }.getOrDefault(emptyList())
        val candidate = interfaces.firstOrNull {
            runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false)
        } ?: return null
        val name = candidate.displayName?.ifBlank { null } ?: candidate.name?.ifBlank { null }
            ?: return null
        val description = when {
            name.contains("wi-fi", true) || name.contains("wifi", true) ||
                name.contains("wireless", true) -> "Wi-Fi"
            name.contains("ethernet", true) || name.contains("lan", true) -> "Ethernet"
            else -> name
        }
        return name to description
    }

    private fun parseLinkSpeedMbps(value: String?): Double {
        if (value.isNullOrBlank()) return 0.0
        val number = Regex("""[\d.]+""").find(value)?.value?.toDoubleOrNull() ?: return 0.0
        return when {
            value.contains("Gb", true) -> number * 1000.0
            value.contains("Kb", true) -> number / 1000.0
            else -> number
        }
    }

    private fun cpuArchitectureName(value: String): String = when (value.trim()) {
        "0" -> UNKNOWN
        "1" -> "Other"
        "2" -> "x86"
        "3" -> "MIPS"
        "5" -> "ARM"
        "6" -> "Itanium"
        "9" -> "x64"
        "12" -> "ARM64"
        else -> value
    }

    private fun checkWindowsVersion(info: PcvrSystemInfo) = when {
        !System.getProperty("os.name", "").contains("win", true) ->
            result("نظام التشغيل", PcvrCheckStatus.FAIL, "نظام التشغيل الحالي: ${info.windowsVersion}",
                "PCVR يتطلب نظام Windows 10 أو 11 فقط.")
        info.windowsVersion == UNKNOWN ->
            result("نظام التشغيل", PcvrCheckStatus.UNKNOWN, "تعذر قراءة إصدار Windows.",
                "تعذر الحصول على هذه المعلومة من مصادر Windows المتاحة؛ تحقق من الإصدار يدويًا.")
        !info.is64Bit && info.windowsArchitecture.isNotBlank() ->
            result("نظام التشغيل", PcvrCheckStatus.FAIL, "نسخة Windows ليست 64-bit: ${info.windowsArchitecture}",
                "استخدم Windows 10 أو 11 بنواة 64-bit.")
        info.windowsVersion.contains("Windows 10", true) || info.windowsVersion.contains("Windows 11", true) ->
            result("نظام التشغيل", PcvrCheckStatus.PASS, "نظام التشغيل: ${info.windowsVersion}",
                "إصدار مدعوم مبدئيًا.")
        else -> result("نظام التشغيل", PcvrCheckStatus.WARN, "نظام التشغيل: ${info.windowsVersion}",
            "تحقق من توافق إصدار Windows مع برنامج النظارة.")
    }

    private fun checkRam(info: PcvrSystemInfo) = when {
        info.totalRamGb <= 0.0 -> result("ذاكرة الوصول العشوائي (RAM)", PcvrCheckStatus.UNKNOWN,
            "تعذر قراءة الذاكرة الفعلية من Windows.", "تحقق من حجم الذاكرة من إعدادات Windows ثم أعد الفحص.")
        info.totalRamGb >= 16.0 -> result("ذاكرة الوصول العشوائي (RAM)", PcvrCheckStatus.PASS,
            ramExplanation(info), "تتجاوز الحد الأدنى 8GB وتناسب معظم ألعاب PCVR.")
        info.totalRamGb >= 8.0 -> result("ذاكرة الوصول العشوائي (RAM)", PcvrCheckStatus.WARN,
            ramExplanation(info), "تستوفي الحد الأدنى 8GB؛ يوصى بـ 16GB للألعاب الثقيلة.")
        else -> result("ذاكرة الوصول العشوائي (RAM)", PcvrCheckStatus.FAIL,
            ramExplanation(info), "الحد الأدنى لـ Meta Quest Link هو 8GB.")
    }

    private fun ramExplanation(info: PcvrSystemInfo): String =
        "الذاكرة الفعلية: ${"%.1f".format(Locale.US, info.totalRamGb)} GB" +
            if (info.availableRamGb > 0.0)
                "، المتاح وقت الفحص: ${"%.1f".format(Locale.US, info.availableRamGb)} GB" else ""

    private fun checkCpu(info: PcvrSystemInfo) = when {
        info.cpuName == UNKNOWN || info.cpuCores <= 0 ->
            result("المعالج (CPU)", PcvrCheckStatus.UNKNOWN, "تعذر قراءة طراز أو أنوية المعالج.",
                "تحقق من طراز المعالج والأنوية في إعدادات Windows ثم أعد الفحص.")
        info.cpuCores < 4 -> result("المعالج (CPU)", PcvrCheckStatus.FAIL,
            cpuExplanation(info), "يُنصح بأربعة أنوية فعلية على الأقل لـ PCVR.")
        info.cpuArchitecture.isBlank() || info.cpuArchitecture == UNKNOWN ->
            result("المعالج (CPU)", PcvrCheckStatus.WARN,
            cpuExplanation(info), "تعذر التحقق من معمارية المعالج؛ تحقق من دعم AVX2 يدويًا.")
        else -> result("المعالج (CPU)", PcvrCheckStatus.PASS, cpuExplanation(info),
            "المعالج والأنوية المقروءة مناسبة مبدئيًا؛ يلزم دعم AVX2 للأداء الأفضل.")
    }

    private fun cpuExplanation(info: PcvrSystemInfo): String =
        "${info.cpuName} (${info.cpuCores} أنوية، ${info.cpuLogicalProcessors} معالجًا منطقيًا، " +
            "${info.cpuArchitecture.ifBlank { UNKNOWN }})"

    private fun checkGpu(info: PcvrSystemInfo) = evaluateGpu(info)

    internal fun evaluateGpu(info: PcvrSystemInfo) = when {
        !info.gpuKnown -> result("كارت الشاشة (GPU)", PcvrCheckStatus.UNKNOWN,
            "تعذر قراءة محول الرسوميات من Windows.", "تحقق من محول PCVR الفعلي في إعدادات الرسوميات ثم أعد الفحص.")
        isRemoteGpuName(info.gpuName) -> result("كارت الشاشة (GPU)", PcvrCheckStatus.UNKNOWN,
            "المحول المقروء يبدو Remote/Virtual أو Display-only: ${info.gpuName}.",
            "أعد الفحص على سطح المكتب المحلي أو تحقق من محول PCVR الفعلي.")
        isIntegratedGpuName(info.gpuName) ->
            result("كارت الشاشة (GPU)", PcvrCheckStatus.FAIL, "${gpuExplanation(info)} (مدمج)",
                "PCVR يتطلب عادةً كارت شاشة منفصل.")
        info.gpuVramKnown && isSuspiciousModernGpuVram(
            info.gpuName, (info.gpuVramGb * BYTES_PER_GB).toLong()
        ) -> result("كارت الشاشة (GPU)", PcvrCheckStatus.WARN,
            "${info.gpuName} (قيمة CIM/AdapterRAM متناقضة: ${"%.1f".format(Locale.US, info.gpuVramGb)} GB).",
            "تعذر الوثوق بـ AdapterRAM؛ تحقق من VRAM عبر تعريف NVIDIA أو nvidia-smi.")
        !info.gpuVramKnown -> result("كارت الشاشة (GPU)", PcvrCheckStatus.WARN,
            "${info.gpuName} (تعذر الوثوق بقيمة AdapterRAM؛ قد تكون VRAM أكبر من 4GB).",
            "تحقق من VRAM في تعريف NVIDIA/AMD/Intel أو أداة الشركة؛ لم يتم اعتبارها فشلًا.")
        isKnownSupportedGpuFamily(info.gpuName) ->
            result("كارت الشاشة (GPU)", PcvrCheckStatus.PASS, gpuExplanation(info),
                "ينتمي المحول إلى عائلة مدعومة لـ Meta Quest Link؛ راجع قائمة Meta الحالية للطراز الدقيق.")
        else -> result("كارت الشاشة (GPU)", PcvrCheckStatus.WARN, gpuExplanation(info),
            "لا تكفي سعة VRAM وحدها للحكم؛ تحقق من توافق الطراز في قائمة Meta Quest Link الحالية.")
    }

    private fun isKnownSupportedGpuFamily(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        // The acceptance evidence and Meta guidance explicitly establish the
        // RTX 4000 series as compatible. Other families contain model-specific
        // exceptions, so keep them WARN unless a maintained compatibility
        // table positively identifies the exact model.
        return Regex("""\brtx\s*4\d{3}""").containsMatchIn(n)
    }

    private fun isRemoteGpuName(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n.contains("remote") || n.contains("virtual") || n.contains("vmware") ||
            n.contains("virtualbox") || n.contains("displaylink") ||
            n.contains("microsoft basic") || n.contains("basic render")
    }

    private fun isIntegratedGpuName(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n.contains("intel hd") || n.contains("intel uhd") || n.contains("intel iris") ||
            (n.contains("intel") && n.contains("graphics") && !n.contains("arc")) ||
            (n.contains("amd") && n.contains("radeon graphics") &&
                !n.contains("radeon rx") && !n.contains("radeon pro")) ||
            (n.contains("amd") && n.contains("vega") && !n.contains("radeon pro"))
    }

    private fun gpuExplanation(info: PcvrSystemInfo): String =
        "${info.gpuName} (VRAM: ${
            if (info.gpuVramKnown) "${"%.1f".format(Locale.US, info.gpuVramGb)} GB" else UNKNOWN
        }، " +
            "driver: ${info.gpuDriverVersion})"

    private fun checkQuestLink(info: PcvrSystemInfo) = when {
        !info.questLinkKnown -> result("Meta Quest Link", PcvrCheckStatus.UNKNOWN,
            "تعذر التحقق من تثبيت Meta Quest Link.", "أعد الفحص ثم تحقق من تثبيت البرنامج.")
        !info.questLinkInstalled -> result("Meta Quest Link", PcvrCheckStatus.FAIL, "غير مثبت",
            "يرجى تثبيت Meta Quest Link من: https://www.meta.com/quest/setup/")
        info.questServiceStatus.isBlank() ||
            info.questServiceStatus.equals("NotInstalled", true) ||
            info.questServiceStatus.equals("Stopped", true) ||
            info.questServiceStatus.equals("Disabled", true) ->
            result("Meta Quest Link", PcvrCheckStatus.WARN,
                "مثبت، لكن حالة OVRService: ${info.questServiceStatus.ifBlank { UNKNOWN }}.",
                "شغّل خدمة OVRService ثم أعد الفحص.")
        else -> result("Meta Quest Link", PcvrCheckStatus.PASS,
            "مثبت على الجهاز؛ خدمة OVR: ${info.questServiceStatus}",
            "لازم لتوصيل النظارة بالسلك.")
    }

    private fun checkSteam(info: PcvrSystemInfo) = softwareResult(
        "Steam", info.steamKnown, info.steamInstalled,
        "يرجى تثبيت Steam من: https://store.steampowered.com/"
    )

    private fun checkSteamvr(info: PcvrSystemInfo) = softwareResult(
        "SteamVR", info.steamvrKnown, info.steamvrInstalled,
        "يرجى تثبيت SteamVR من Steam (Search: SteamVR)"
    )

    private fun softwareResult(title: String, known: Boolean, installed: Boolean, solution: String,
                               detail: String = "") = when {
        !known -> result(title, PcvrCheckStatus.UNKNOWN, "تعذر التحقق من التثبيت.", solution)
        installed -> result(title, PcvrCheckStatus.PASS,
            "مثبت على الجهاز" + if (detail.isNotBlank()) "؛ خدمة OVR: $detail" else "", solution)
        else -> result(title, PcvrCheckStatus.FAIL, "غير مثبت", solution)
    }

    private fun checkOpenXr(info: PcvrSystemInfo) = when {
        !info.openXrKnown -> result("OpenXR Active Runtime", PcvrCheckStatus.UNKNOWN,
            "تعذر قراءة ActiveRuntime من سجل Windows.", "عيّن runtime من إعدادات SteamVR أو Meta Quest Link.")
        else -> result("OpenXR Active Runtime", PcvrCheckStatus.PASS,
            "مهيأ: ${info.openXrRuntime}", "مطلوب للألعاب المدعومة بـ OpenXR.", info.openXrRuntime)
    }

    private fun checkUsb3(info: PcvrSystemInfo) = when {
        !info.usb3Known -> result("USB 3.x", PcvrCheckStatus.UNKNOWN,
            "تعذر قراءة متحكمات USB.", "أعد الفحص ثم تحقق من توصيل النظارة بمنفذ USB 3.x.")
        info.usb3Available -> result("USB 3.x", PcvrCheckStatus.PASS,
            "تم العثور على متحكم USB 3.x: ${info.usbControllerDetails}",
            "استخدم منفذ USB 3.x مباشرًا.")
        else -> result("USB 3.x", PcvrCheckStatus.WARN,
            "لم يتم التعرف على متحكم USB 3.x (${info.usbControllerDetails}).",
            "تحقق من منفذ USB 3.x وكابل Link.")
    }

    private fun checkSystemDrive(info: PcvrSystemInfo) = when {
        info.systemDriveFreeGb <= 0.0 -> result("مساحة قرص النظام", PcvrCheckStatus.UNKNOWN,
            "تعذر قراءة المساحة الحرة على قرص Windows.", "تأكد من تشغيل الفحص على Windows ثم أعد المحاولة.")
        info.systemDriveFreeGb < 10.0 -> result("مساحة قرص النظام", PcvrCheckStatus.FAIL,
            "المساحة الحرة على قرص Windows: ${"%.1f".format(Locale.US, info.systemDriveFreeGb)} GB.",
            "حرر 10GB على الأقل، ويفضل مساحة أكبر لتثبيت ألعاب PCVR.")
        info.systemDriveFreeGb < 20.0 -> result("مساحة قرص النظام", PcvrCheckStatus.WARN,
            "المساحة الحرة على قرص Windows: ${"%.1f".format(Locale.US, info.systemDriveFreeGb)} GB.",
            "يفضل توفير 20GB أو أكثر للتحديثات وملفات shader.")
        else -> result("مساحة قرص النظام", PcvrCheckStatus.PASS,
            "المساحة الحرة على قرص Windows: ${"%.1f".format(Locale.US, info.systemDriveFreeGb)} GB.",
            "المساحة المقروءة مناسبة مبدئيًا.")
    }

    private fun checkNetwork(info: PcvrSystemInfo) = when {
        !info.networkKnown -> result("نوع الاتصال", PcvrCheckStatus.UNKNOWN,
            "تعذر قراءة محول شبكة نشط.", "اتصل بشبكة مستقرة ثم أعد الفحص.")
        info.networkType == "Ethernet" -> result("نوع الاتصال", PcvrCheckStatus.PASS,
            "Ethernet: ${info.networkAdapterName} (${linkText(info.networkLinkSpeedMbps)})",
            "الاتصال السلكي أفضل لتجربة PCVR.")
        info.networkType == "Wi-Fi" -> result("نوع الاتصال", PcvrCheckStatus.WARN,
            "Wi-Fi: ${info.networkAdapterName} (${linkText(info.networkLinkSpeedMbps)})",
            "Ethernet أفضل، أو استخدم Wi-Fi 5GHz/6GHz مستقرًا.")
        else -> result("نوع الاتصال", PcvrCheckStatus.WARN,
            "${info.networkType}: ${info.networkAdapterName}", "تحقق من استقرار الاتصال.")
    }

    private fun linkText(mbps: Double) =
        if (mbps > 0.0) "${"%.0f".format(Locale.US, mbps)} Mbps" else "سرعة غير معروفة"

    /**
     * Keep the probe's detected value and the requirement visible as separate
     * fields.  This prevents a localized explanation from being mistaken for
     * a measured value and gives the UI/report one consistent data model.
     */
    private fun withRequirements(
        check: PcvrCheckResult,
        info: PcvrSystemInfo
    ): PcvrCheckResult {
        val values = when (check.title) {
            "نظام التشغيل" ->
                (info.windowsVersion to "Windows 10/11 64-bit")
            "ذاكرة الوصول العشوائي (RAM)" ->
                ("${"%.1f".format(Locale.US, info.totalRamGb)} GB" to "8 GB minimum (16 GB recommended)")
            "المعالج (CPU)" ->
                (cpuExplanation(info) to "4 physical cores; x64/ARM64")
            "كارت الشاشة (GPU)" ->
                (gpuExplanation(info) to "GPU supported by the current Meta Quest Link compatibility list")
            "Meta Quest Link" ->
                ((if (!info.questLinkKnown) UNKNOWN else if (info.questLinkInstalled) "Installed" else "Not installed")
                    to "Meta Quest Link installed")
            "Steam" ->
                ((if (!info.steamKnown) UNKNOWN else if (info.steamInstalled) "Installed" else "Not installed")
                    to "Steam installed")
            "SteamVR" ->
                ((if (!info.steamvrKnown) UNKNOWN else if (info.steamvrInstalled) "Installed" else "Not installed")
                    to "SteamVR installed")
            "OpenXR Active Runtime" ->
                ((if (info.openXrKnown) info.openXrRuntime else UNKNOWN) to "An active OpenXR runtime")
            "USB 3.x" ->
                ((if (info.usb3Known && info.usb3Available) "Detected" else UNKNOWN) to "USB 3.x controller")
            "مساحة قرص النظام" ->
                ("${"%.1f".format(Locale.US, info.systemDriveFreeGb)} GB free" to "10 GB free")
            "نوع الاتصال" ->
                ((if (info.networkKnown) info.networkType else UNKNOWN) to "Stable Ethernet or Wi-Fi")
            else -> (check.detected to check.minimum)
        }
        return check.copy(
            detected = check.detected.ifBlank { values.first },
            minimum = check.minimum.ifBlank { values.second }
        )
    }

    private fun result(title: String, status: PcvrCheckStatus, explanation: String,
                       solution: String, details: String = "") =
        PcvrCheckResult(title, status, explanation, solution, details)

    fun generateReport(
        checks: List<PcvrCheckResult>,
        systemInfo: PcvrSystemInfo,
        timeFormatter: NfvrTimeFormatter = NfvrTimeFormatter()
    ): String {
        val sb = StringBuilder()
        val timestamp = timeFormatter.formatDateTime(timeFormatter.now())
        sb.appendLine("=".repeat(60))
        sb.appendLine("تقرير فحص جاهزية PCVR")
        sb.appendLine("وقت الفحص: $timestamp")
        sb.appendLine("=".repeat(60)).appendLine()
        sb.appendLine("معلومات النظام:")
        sb.appendLine("- نظام التشغيل: ${systemInfo.windowsVersion}")
        sb.appendLine("- معمارية Windows: ${systemInfo.windowsArchitecture.ifBlank { UNKNOWN }}")
        sb.appendLine("- 64-bit: ${if (systemInfo.windowsArchitecture.isBlank()) UNKNOWN else if (systemInfo.is64Bit) "نعم" else "لا"}")
        sb.appendLine("- الذاكرة الفعلية: ${"%.1f".format(Locale.US, systemInfo.totalRamGb)} GB")
        sb.appendLine("- الذاكرة المتاحة: ${"%.1f".format(Locale.US, systemInfo.availableRamGb)} GB")
        sb.appendLine("- المعالج: ${systemInfo.cpuName} (${systemInfo.cpuCores} أنوية، ${systemInfo.cpuLogicalProcessors} منطقية)")
        sb.appendLine("- كارت الشاشة: ${systemInfo.gpuName} (VRAM: ${
            if (systemInfo.gpuVramKnown) "${"%.1f".format(Locale.US, systemInfo.gpuVramGb)} GB" else UNKNOWN
        })")
        sb.appendLine("- إصدار تعريف GPU: ${systemInfo.gpuDriverVersion}")
        sb.appendLine("- مساحة قرص Windows الحرة: ${"%.1f".format(Locale.US, systemInfo.systemDriveFreeGb)} GB")
        sb.appendLine("- Meta Quest Link: ${if (!systemInfo.questLinkKnown) UNKNOWN else if (systemInfo.questLinkInstalled) "مثبت" else "غير مثبت"}")
        sb.appendLine("- OVRService: ${systemInfo.questServiceStatus.ifBlank { UNKNOWN }}")
        sb.appendLine("- Steam: ${if (!systemInfo.steamKnown) UNKNOWN else if (systemInfo.steamInstalled) "مثبت" else "غير مثبت"}")
        sb.appendLine("- SteamVR: ${if (!systemInfo.steamvrKnown) UNKNOWN else if (systemInfo.steamvrInstalled) "مثبت" else "غير مثبت"}")
        sb.appendLine("- OpenXR Runtime: ${if (systemInfo.openXrKnown) systemInfo.openXrRuntime else UNKNOWN}")
        sb.appendLine("- USB controllers: ${if (systemInfo.usb3Known) systemInfo.usbControllerDetails else UNKNOWN}")
        sb.appendLine("- نوع الاتصال: ${if (systemInfo.networkKnown) systemInfo.networkType else UNKNOWN}")
        if (systemInfo.networkLinkSpeedMbps > 0) sb.appendLine("- سرعة الرابط: ${linkText(systemInfo.networkLinkSpeedMbps)}")
        sb.appendLine().appendLine("نتائج الفحص:").appendLine("-".repeat(60))
        checks.forEach {
            val symbol = when (it.status) {
                PcvrCheckStatus.PASS -> "✅"
                PcvrCheckStatus.WARN -> "⚠️"
                PcvrCheckStatus.FAIL -> "❌"
                PcvrCheckStatus.UNKNOWN -> "❓"
            }
            sb.appendLine("$symbol ${it.title}")
            if (it.detected.isNotBlank() || it.minimum.isNotBlank()) {
                sb.appendLine("   detected: ${it.detected.ifBlank { UNKNOWN }}")
                sb.appendLine("   minimum: ${it.minimum.ifBlank { UNKNOWN }}")
            }
            sb.appendLine("   التفاصيل: ${it.explanation}")
            sb.appendLine("   الحل: ${it.solution}").appendLine()
        }
        sb.appendLine("=".repeat(60))
        sb.appendLine("إخلاء مسؤولية: الأداء يختلف حسب اللعبة وإعدادات الرسوميات، ولا يثبت هذا الفحص جاهزية كل لعبة.")
        sb.appendLine("=".repeat(60))
        return sb.toString()
    }

    /**
     * UNKNOWN means that a probe could not answer; it is not evidence that the
     * machine is unsuitable.  A known FAIL is the only blocking result.  Keep
     * UNKNOWN-only/empty lists non-ready so an empty result cannot look healthy.
     */
    fun isReady(checks: List<PcvrCheckResult>): Boolean =
        checks.isNotEmpty() &&
            checks.any { it.status == PcvrCheckStatus.PASS } &&
            checks.none { it.status == PcvrCheckStatus.FAIL }

    private val PROBE_SCRIPT = """
@@ErrorActionPreference = 'SilentlyContinue'
function Emit([string]@@k, @@v) {
  if (@@null -ne @@v) {
    @@s = [string]@@v
    @@s = @@s.Replace("`t", " ").Replace("`r", " ").Replace("`n", " ")
    Write-Output (@@k + "`t" + @@s)
  }
}
function TryCim([string]@@class) {
  try { return @(Get-CimInstance -ClassName @@class -ErrorAction Stop) }
  catch { return @() }
}
@@os = @(TryCim 'Win32_OperatingSystem') | Select-Object -First 1
Emit OS_CAPTION @@os.Caption
Emit OS_BUILD @@os.BuildNumber
Emit OS_ARCH @@os.OSArchitecture
@@cpus = TryCim 'Win32_Processor'
if (@@cpus.Count -gt 0) {
  Emit CPU_NAME @@cpus[0].Name
  @@coreCount = ((@@cpus | Where-Object { @@_.NumberOfCores -as [int] -gt 0 } |
    Measure-Object NumberOfCores -Sum).Sum)
  if (@@coreCount -gt 0) { Emit CPU_CORES @@coreCount }
  @@logicalCount = ((@@cpus | Where-Object { @@_.NumberOfLogicalProcessors -as [int] -gt 0 } |
    Measure-Object NumberOfLogicalProcessors -Sum).Sum)
  if (@@logicalCount -gt 0) { Emit CPU_LOGICAL @@logicalCount }
  Emit CPU_ARCH @@cpus[0].Architecture
}
@@computer = @(TryCim 'Win32_ComputerSystem') | Select-Object -First 1
Emit RAM_TOTAL_BYTES @@computer.TotalPhysicalMemory
Emit RAM_AVAILABLE_KB @@os.FreePhysicalMemory
@@gpus = TryCim 'Win32_VideoController'
function EmitGpu(@@g) {
  @@name = ([string]@@g.Name).Replace("`t", " ").Replace("`r", " ").Replace("`n", " ")
  @@ram = ([string]@@g.AdapterRAM).Replace("`t", " ")
  @@driver = ([string]@@g.DriverVersion).Replace("`t", " ")
  Write-Output ("GPU`t" + @@name + "`t" + @@ram + "`t" + @@driver)
}
@@gpus | ForEach-Object { EmitGpu @@_ }
@@drive = TryCim 'Win32_LogicalDisk' | Where-Object DeviceID -eq @@env:SystemDrive |
  Select-Object -First 1
Emit DRIVE_FREE_BYTES @@drive.FreeSpace
Emit DRIVE_SIZE_BYTES @@drive.Size
@@questPaths = @(
  'HKLM:\SOFTWARE\Meta\Oculus',
  'HKLM:\SOFTWARE\WOW6432Node\Oculus VR, LLC\Oculus',
  'HKLM:\SOFTWARE\WOW6432Node\Meta\Oculus',
  "@@env:ProgramFiles\Oculus",
  "@@{env:ProgramFiles(x86)}\Oculus",
  "@@env:LOCALAPPDATA\Oculus"
)
@@quest = @@questPaths | Where-Object { Test-Path @@_ } | Select-Object -First 1
if (@@quest) { Emit QUEST_INSTALLED @@true; Emit QUEST_PATH @@quest }
try {
  @@ovr = Get-Service -Name OVRService -ErrorAction Stop
  if (@@ovr) { Emit OVR_SERVICE @@ovr.Status }
} catch {
  # A missing service is resolved by the read-only sc.exe fallback.  Do not
  # call a provider error "NotInstalled" here.
}
@@steamPaths = @(
  'HKCU:\SOFTWARE\Valve\Steam',
  'HKLM:\SOFTWARE\Valve\Steam',
  'HKLM:\SOFTWARE\WOW6432Node\Valve\Steam',
  "@@env:ProgramFiles\Steam\steam.exe",
  "@@{env:ProgramFiles(x86)}\Steam\steam.exe"
)
@@steam = @@steamPaths | Where-Object { Test-Path @@_ } | Select-Object -First 1
if (@@steam) { Emit STEAM_INSTALLED @@true }
@@steamVrPaths = @(
  'HKCU:\SOFTWARE\Valve\Steam\Apps\250820',
  'HKLM:\SOFTWARE\Valve\Steam\Apps\250820',
  "@@{env:ProgramFiles(x86)}\Steam\steamapps\common\SteamVR",
  "@@env:ProgramFiles\Steam\steamapps\common\SteamVR"
)
@@steamvr = @@steamVrPaths | Where-Object { Test-Path @@_ } | Select-Object -First 1
if (@@steamvr) { Emit STEAMVR_INSTALLED @@true }
@@xrPaths = @(
  'HKCU:\SOFTWARE\Khronos\OpenXR\1',
  'HKLM:\SOFTWARE\Khronos\OpenXR\1',
  'HKLM:\SOFTWARE\WOW6432Node\Khronos\OpenXR\1'
)
foreach (@@p in @@xrPaths) {
  @@xr = Get-ItemProperty -Path @@p -Name ActiveRuntime -ErrorAction SilentlyContinue
  if (@@xr.ActiveRuntime) { Emit OPENXR_RUNTIME @@xr.ActiveRuntime; break }
}
@@controllers = @(TryCim 'Win32_USBController' | Select-Object -ExpandProperty Name)
if (@@controllers.Count -gt 0) { Emit USB_CONTROLLERS (@@controllers -join '; ') }
@@adapter = @(Get-NetAdapter -ErrorAction SilentlyContinue | Where-Object Status -eq 'Up' | Select-Object -First 1)
if (@@adapter.Count -gt 0) {
  Emit NET_NAME @@adapter[0].Name
  Emit NET_DESCRIPTION @@adapter[0].InterfaceDescription
  Emit NET_LINK_SPEED @@adapter[0].LinkSpeed
}
""".replace("@@", "$")
}