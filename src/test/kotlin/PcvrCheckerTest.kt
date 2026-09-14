import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PcvrCheckerTest {
    @Test
    fun parsesCimProbeWithoutWmicFormattingAssumptions() {
        val data = parsePcvrProbeOutput(
            listOf(
                "OS_CAPTION\tMicrosoft Windows 11 Pro",
                "OS_BUILD=22631",
                "OS_ARCH\t64-bit",
                "CPU_NAME\tAMD Ryzen 7 7800X3D",
                "CPU_CORES\t8",
                "CPU_LOGICAL\t16",
                "CPU_ARCH\t9",
                "RAM_TOTAL_BYTES\t34359738368",
                "RAM_AVAILABLE_KB\t16000000",
                "GPU_NAME\tNVIDIA GeForce RTX 4070",
                "GPU_VRAM_BYTES\t12884901888",
                "GPU_DRIVER\t551.23",
                "DRIVE_FREE_BYTES\t500000000000",
                "DRIVE_SIZE_BYTES\t1000000000000",
                "QUEST_INSTALLED\ttrue",
                "OVR_SERVICE\tRunning",
                "STEAM_INSTALLED\ttrue",
                "STEAMVR_INSTALLED\tfalse",
                "OPENXR_RUNTIME\tC:\\\\Program Files\\\\SteamVR\\\\steamxr_win64.json",
                "USB_CONTROLLERS\tUSB 3.0 eXtensible Host Controller",
                "NET_NAME\tEthernet",
                "NET_DESCRIPTION\tIntel Ethernet",
                "NET_LINK_SPEED\t1 Gbps"
            )
        )

        assertEquals("Microsoft Windows 11 Pro", data.osCaption)
        assertEquals(8, data.cpuCores)
        assertEquals(16, data.cpuLogicalProcessors)
        assertEquals(34359738368L, data.totalRamBytes)
        assertEquals(true, data.questInstalled)
        assertEquals(false, data.steamvrInstalled)
        assertEquals("Running", data.ovrService)
        assertEquals("1 Gbps", data.networkLinkSpeed)
    }

    @Test
    fun unknownProbeValuesRemainUnknownAndDoNotBecomeReady() {
        val data = parsePcvrProbeOutput(listOf("OS_CAPTION\t__UNKNOWN__", "GPU_NAME\t"))
        assertEquals(null, data.osCaption)
        assertEquals(null, data.gpuName)
        assertFalse(PcvrChecker.isReady(listOf(
            PcvrCheckResult("GPU", PcvrCheckStatus.UNKNOWN, "", "")
        )))
        assertTrue(PcvrChecker.isReady(listOf(
            PcvrCheckResult("RAM", PcvrCheckStatus.PASS, "", "")
        )))
    }

    @Test
    fun selectsDiscreteAdapterFromHybridAndIgnoresRemoteDisplay() {
        val adapters = listOf(
            PcvrGpuData("Intel(R) UHD Graphics 770", 1_073_741_824L, "31.0"),
            PcvrGpuData("Microsoft Remote Display Adapter", null, null),
            PcvrGpuData("NVIDIA GeForce RTX 4070", 4_294_967_295L, "551.23")
        )

        val selected = selectPcvrGpu(adapters)

        assertEquals("NVIDIA GeForce RTX 4070", selected?.name)
        assertEquals(4_294_967_295L, selected?.adapterRamBytes)
    }

    @Test
    fun parsesAndRanksMultipleGpuRecordsWithoutUsingFirstOnly() {
        val data = parsePcvrProbeOutput(
            listOf(
                "GPU\tIntel Iris Xe Graphics\t1073741824\t31.0.101",
                "GPU\tAMD Radeon RX 6800\t8589934592\t24.1.1",
                "GPU\tVMware SVGA 3D\t\t"
            )
        )

        assertEquals(3, data.gpus.size)
        assertEquals("AMD Radeon RX 6800", selectPcvrGpu(data.gpus)?.name)
    }

    @Test
    fun unreliableAdapterRamDoesNotFailLikelyDiscreteGpu() {
        val result = PcvrChecker.evaluateGpu(
            PcvrSystemInfo(
                windowsVersion = "Windows 11 build 22631",
                is64Bit = true,
                totalRamGb = 32.0,
                cpuName = "CPU",
                cpuCores = 8,
                gpuName = "NVIDIA GeForce RTX 4090",
                gpuVramGb = 0.0,
                gpuDriverVersion = "551.23",
                questLinkInstalled = true,
                steamInstalled = true,
                steamvrInstalled = true,
                openXrRuntime = "runtime.json",
                usb3Available = true,
                networkType = "Ethernet",
                gpuVramKnown = false
            )
        )

        assertEquals(PcvrCheckStatus.WARN, result.status)
    }
}