import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.platform.win32.Guid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume

internal sealed interface IsolatedPickerOutput {
    data object Cancelled : IsolatedPickerOutput
    data class Selected(val path: String) : IsolatedPickerOutput
    data class Invalid(val diagnostic: String) : IsolatedPickerOutput
}

internal fun parseIsolatedPickerOutput(output: String): IsolatedPickerOutput {
    val lines = output.lineSequence()
        .map(String::trim)
        .map { it.removePrefix("\uFEFF") }
        .filter(String::isNotBlank)
        .toList()
    return when {
        lines.isEmpty() -> IsolatedPickerOutput.Cancelled
        lines.size != 1 -> IsolatedPickerOutput.Invalid("expected one selected path")
        '\u0000' in lines.single() -> IsolatedPickerOutput.Invalid("selected path contained NUL")
        else -> IsolatedPickerOutput.Selected(lines.single())
    }
}

/**
 * The native boundary is deliberately tiny.  The workflow and validation
 * around it are testable without creating a COM dialog, while this adapter is
 * the only code that knows about the Windows vtables.
 */
internal sealed interface NativePickerOutput {
    data class Selected(val path: String) : NativePickerOutput
    data object Cancelled : NativePickerOutput
    data class Failed(val hresult: Int?, val diagnostic: String) : NativePickerOutput
}

internal interface WindowsNativePickerAdapter {
    fun choose(
        mode: DesktopChooserMode,
        initialDirectory: File?,
        title: String,
        ownerHwnd: Long?
    ): NativePickerOutput
}

internal const val FOS_NOCHANGEDIR = 0x00000008
internal const val FOS_PICKFOLDERS = 0x00000020
internal const val FOS_FORCEFILESYSTEM = 0x00000040
internal const val FOS_PATHMUSTEXIST = 0x00000800
internal const val FOS_FILEMUSTEXIST = 0x00001000
private const val CLSCTX_INPROC_SERVER = 0x1
private const val COINIT_APARTMENTTHREADED = 0x2
private const val SIGDN_FILESYSPATH = -2147123200
private const val HRESULT_CANCELLED = -2147023673

private val CLSID_FILE_OPEN_DIALOG = Guid.GUID("{DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7}")
private val IID_FILE_OPEN_DIALOG = Guid.GUID("{D57C7288-D4AD-4768-BE02-9D969532D960}")
private val IID_SHELL_ITEM = Guid.GUID("{43826D1E-E718-42EE-BC55-A1E261C37BFE}")

internal fun pickerOptions(mode: DesktopChooserMode): Int = when (mode) {
    DesktopChooserMode.DIRECTORY ->
        FOS_PICKFOLDERS or FOS_FORCEFILESYSTEM or FOS_PATHMUSTEXIST or FOS_NOCHANGEDIR
    DesktopChooserMode.FILES ->
        FOS_FORCEFILESYSTEM or FOS_PATHMUSTEXIST or FOS_NOCHANGEDIR or FOS_FILEMUSTEXIST
}

private interface Ole32Native : com.sun.jna.win32.StdCallLibrary {
    fun CoInitializeEx(reserved: Pointer?, coInit: Int): Int
    fun CoUninitialize()
    fun CoCreateInstance(
        clsid: Guid.GUID,
        outer: Pointer?,
        context: Int,
        iid: Guid.GUID,
        result: PointerByReference
    ): Int
    fun CoTaskMemFree(pointer: Pointer?)
}

private interface Shell32Native : com.sun.jna.win32.StdCallLibrary {
    fun SHCreateItemFromParsingName(
        path: WString,
        bindContext: Pointer?,
        iid: Guid.GUID,
        result: PointerByReference
    ): Int
}

private object WindowsNativeLibraries {
    val ole32: Ole32Native = Native.load("ole32", Ole32Native::class.java)
    val shell32: Shell32Native = Native.load("shell32", Shell32Native::class.java)
}

private class ComdlgFilterSpec(name: Pointer, pattern: Pointer) : com.sun.jna.Structure() {
    @JvmField var pszName: Pointer = name
    @JvmField var pszSpec: Pointer = pattern

    override fun getFieldOrder(): List<String> = listOf("pszName", "pszSpec")
}

private object JnaWindowsNativePickerAdapter : WindowsNativePickerAdapter {
    override fun choose(
        mode: DesktopChooserMode,
        initialDirectory: File?,
        title: String,
        ownerHwnd: Long?
    ): NativePickerOutput {
        var initialized = false
        var dialog: Pointer? = null
        var initialShellItem: Pointer? = null
        try {
            val initResult = WindowsNativeLibraries.ole32.CoInitializeEx(
                Pointer.NULL,
                COINIT_APARTMENTTHREADED
            )
            if (initResult < 0) {
                return NativePickerOutput.Failed(initResult, "CoInitializeEx failed")
            }
            initialized = true

            val created = PointerByReference()
            val createResult = WindowsNativeLibraries.ole32.CoCreateInstance(
                CLSID_FILE_OPEN_DIALOG,
                null,
                CLSCTX_INPROC_SERVER,
                IID_FILE_OPEN_DIALOG,
                created
            )
            if (createResult < 0) {
                return NativePickerOutput.Failed(createResult, "CoCreateInstance(IFileOpenDialog) failed")
            }
            dialog = created.value
                ?: return NativePickerOutput.Failed(null, "IFileOpenDialog returned a null pointer")

            val options = IntByReference()
            var result = invokeHresult(dialog, 10, options)
            if (result < 0) return NativePickerOutput.Failed(result, "IFileOpenDialog.GetOptions failed")
            val requiredFlags = pickerOptions(mode)
            // Do not inherit dialog defaults: these are the exact modern picker
            // flags used by this application for each operation.
            result = invokeHresult(dialog, 9, requiredFlags)
            if (result < 0) return NativePickerOutput.Failed(result, "IFileOpenDialog.SetOptions failed")

            if (mode == DesktopChooserMode.FILES) {
                val name = wideStringMemory("ZIP archives (*.zip)")
                val pattern = wideStringMemory("*.zip")
                val filter = ComdlgFilterSpec(name, pattern)
                filter.write()
                result = invokeHresult(dialog, 4, 1, filter.pointer)
                if (result < 0) return NativePickerOutput.Failed(result, "IFileOpenDialog.SetFileTypes failed")
                result = invokeHresult(dialog, 5, 1)
                if (result < 0) return NativePickerOutput.Failed(result, "IFileOpenDialog.SetFileTypeIndex failed")
            }

            val initial = initialDirectory
                ?.let(::normalizePickerPath)
                ?.takeIf { it.isDirectory }
            if (initial != null) {
                val shellItem = PointerByReference()
                result = WindowsNativeLibraries.shell32.SHCreateItemFromParsingName(
                    WString(initial.absolutePath),
                    null,
                    IID_SHELL_ITEM,
                    shellItem
                )
                if (result >= 0) {
                    initialShellItem = shellItem.value
                    if (initialShellItem != null) {
                        // SetFolder is vtable slot 12 on IFileOpenDialog.
                        result = invokeHresult(dialog, 12, initialShellItem)
                        if (result < 0) {
                            return NativePickerOutput.Failed(result, "IFileOpenDialog.SetFolder failed")
                        }
                    }
                } else {
                    // An unavailable initial folder must not prevent the user
                    // from choosing another valid folder.
                    DiagnosticLogger.info(
                        "native picker initial folder unavailable: mode=$mode hresult=${formatHresult(result)}"
                    )
                }
            }

            result = invokeHresult(dialog, 17, WString(title.take(200)))
            if (result < 0) return NativePickerOutput.Failed(result, "IFileOpenDialog.SetTitle failed")
            val owner = ownerHwnd
                ?.takeIf { it != 0L }
                ?.let(Pointer::createConstant)
            result = invokeHresult(dialog, 3, owner)
            if (result == HRESULT_CANCELLED) return NativePickerOutput.Cancelled
            if (result < 0) return NativePickerOutput.Failed(result, "IFileOpenDialog.Show failed")

            val shellResult = PointerByReference()
            result = invokeHresult(dialog, 20, shellResult)
            if (result < 0) return NativePickerOutput.Failed(result, "IFileOpenDialog.GetResult failed")
            val shell = shellResult.value
                ?: return NativePickerOutput.Failed(null, "IFileOpenDialog returned no result")
            try {
                val pathMemory = PointerByReference()
                result = invokeHresult(shell, 5, SIGDN_FILESYSPATH, pathMemory)
                if (result < 0) return NativePickerOutput.Failed(result, "IShellItem.GetDisplayName failed")
                val pathPointer = pathMemory.value
                    ?: return NativePickerOutput.Failed(null, "IShellItem returned no filesystem path")
                try {
                    return NativePickerOutput.Selected(pathPointer.getWideString(0))
                } finally {
                    WindowsNativeLibraries.ole32.CoTaskMemFree(pathPointer)
                }
            } finally {
                releaseComPointer(shell)
            }
        } catch (error: Throwable) {
            return NativePickerOutput.Failed(
                null,
                "${error::class.java.simpleName}: ${error.message.orEmpty()}"
            )
        } finally {
            initialShellItem?.let(::releaseComPointer)
            dialog?.let(::releaseComPointer)
            if (initialized) WindowsNativeLibraries.ole32.CoUninitialize()
        }
    }

    private fun releaseComPointer(pointer: Pointer) {
        runCatching { invokeHresult(pointer, 2) }
    }

    private fun wideStringMemory(value: String): Memory {
        val bytes = (value + "\u0000").toByteArray(Charsets.UTF_16LE)
        return Memory(bytes.size.toLong()).also { it.write(0L, bytes, 0, bytes.size) }
    }

    private fun invokeHresult(pointer: Pointer, slot: Int, vararg args: Any?): Int {
        val vtable = pointer.getPointer(0L)
            ?: error("COM object has no vtable")
        val functionPointer = vtable.getPointer(slot.toLong() * Native.POINTER_SIZE)
            ?: error("COM vtable slot $slot is null")
        val function = Function.getFunction(
            functionPointer,
            Function.ALT_CONVENTION
        )
        return function.invoke(Int::class.java, comInvocationArguments(pointer, args)) as Int
    }
}

/**
 * COM vtable methods are not ordinary C functions: every call receives the
 * interface pointer as its first argument.  Keeping assembly separate makes
 * this ABI rule unit-testable without loading a Windows COM library.
 */
internal fun comInvocationArguments(
    thisPointer: Pointer,
    args: Array<out Any?>
): Array<Any?> = arrayOf(thisPointer, *args)

/**
 * The gate is released by the executor wrapper, not by the waiting coroutine.
 * Therefore cancelling the waiting coroutine cannot permit another native
 * dialog while the previous modal Show call is still running.
 */
internal class PickerTaskCoordinator(
    private val executor: Executor,
    private val gate: DesktopChooserGate = DesktopChooserGate()
) {
    fun tryAcquire(): Boolean = gate.tryAcquire()

    fun submitAfterAcquire(task: () -> Unit, completed: () -> Unit): Boolean {
        return try {
            executor.execute {
                try {
                    task()
                } finally {
                    gate.release()
                    runCatching { completed() }
                }
            }
            true
        } catch (error: Throwable) {
            gate.release()
            runCatching { completed() }
            throw error
        }
    }

    fun isOpen(): Boolean = gate.isOpen()
}

internal fun normalizePickerPath(path: String): File? = runCatching {
    File(path).toPath().toAbsolutePath().normalize().toFile()
}.getOrNull()

internal fun normalizePickerPath(file: File): File? =
    normalizePickerPath(file.path)

internal fun pickerSelectionError(mode: DesktopChooserMode, file: File): String? {
    val normalized = normalizePickerPath(file)
        ?: return "selected path could not be normalized"
    return when (mode) {
        DesktopChooserMode.DIRECTORY ->
            if (normalized.isDirectory && normalized.canRead()) null
            else "selected path is not a readable directory"
        DesktopChooserMode.FILES ->
            if (normalized.isFile && normalized.canRead() &&
                normalized.name.endsWith(".zip", ignoreCase = true)
            ) null
            else "selected path is not a readable ZIP file"
    }
}

internal fun formatHresult(hresult: Int?): String =
    hresult?.let { "HRESULT=0x%08X".format(it) } ?: "HRESULT=unknown"

internal fun mapNativePickerOutput(
    mode: DesktopChooserMode,
    native: NativePickerOutput
): DesktopChooserResult = when (native) {
    is NativePickerOutput.Selected -> {
        val selected = normalizePickerPath(native.path)
        val validationError = selected?.let { pickerSelectionError(mode, it) }
        if (selected == null || validationError != null) {
            DesktopChooserResult.Failed(
                when (mode) {
                    DesktopChooserMode.DIRECTORY -> "اختر مجلدًا موجودًا وقابلًا للقراءة."
                    DesktopChooserMode.FILES -> "اختر ملف ZIP موجودًا وقابلًا للقراءة."
                },
                "native picker invalid ${mode.name.lowercase()} output: " +
                    (validationError ?: "selected path could not be normalized")
            )
        } else {
            DesktopChooserResult.Selected(selected)
        }
    }
    NativePickerOutput.Cancelled -> DesktopChooserResult.Cancelled
    is NativePickerOutput.Failed -> DesktopChooserResult.Failed(
        "تعذر فتح نافذة الاختيار. حاول مرة أخرى.",
        "native picker failed: type=${mode.name.lowercase()} operation=select " +
            "${formatHresult(native.hresult)} ${native.diagnostic}"
    )
}

/**
 * One native picker at a time.  Every COM call runs on this one daemon
 * executor thread, which initializes and uninitializes its own STA.
 */
object WindowsIsolatedPicker {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nfvr-native-picker-sta").apply { isDaemon = true }
    }
    private val coordinator = PickerTaskCoordinator(executor)

    @Volatile
    internal var adapter: WindowsNativePickerAdapter = JnaWindowsNativePickerAdapter

    suspend fun chooseFolder(
        initialDirectory: File? = null,
        title: String = "اختر مجلد اللعبة",
        ownerHwnd: Long? = null,
        onNativeTaskComplete: () -> Unit = {}
    ): DesktopChooserResult = choose(
        DesktopChooserMode.DIRECTORY,
        initialDirectory,
        title,
        ownerHwnd,
        onNativeTaskComplete
    )

    suspend fun chooseZipFile(
        initialDirectory: File? = null,
        title: String = "اختر ملف المود (ZIP)",
        ownerHwnd: Long? = null,
        onNativeTaskComplete: () -> Unit = {}
    ): DesktopChooserResult = choose(
        DesktopChooserMode.FILES,
        initialDirectory,
        title,
        ownerHwnd,
        onNativeTaskComplete
    )

    private suspend fun choose(
        mode: DesktopChooserMode,
        initialDirectory: File?,
        title: String,
        ownerHwnd: Long?,
        onNativeTaskComplete: () -> Unit
    ): DesktopChooserResult {
        if (!isWindows()) {
            runCatching { onNativeTaskComplete() }
            return DesktopChooserResult.Failed(
                "هذه النافذة الأصلية متاحة في إصدار Windows فقط.",
                "native picker unsupported host=${System.getProperty("os.name").orEmpty()}"
            )
        }
        if (!coordinator.tryAcquire()) {
            runCatching { onNativeTaskComplete() }
            DiagnosticLogger.info("native picker busy: mode=$mode")
            return DesktopChooserResult.Busy
        }
        return try {
            val native = executeOnSta(
                mode,
                initialDirectory,
                title,
                ownerHwnd,
                onNativeTaskComplete
            )
            val mapped = mapNativePickerOutput(mode, native)
            when (mapped) {
                is DesktopChooserResult.Selected -> DiagnosticLogger.info(
                    "native picker selected: type=${mode.name.lowercase()} " +
                        "operation=select name=${mapped.file.name.take(160)}"
                )
                DesktopChooserResult.Cancelled -> DiagnosticLogger.info(
                    "native picker cancelled: type=${mode.name.lowercase()} operation=select " +
                        formatHresult(HRESULT_CANCELLED)
                )
                is DesktopChooserResult.Failed -> DiagnosticLogger.error(mapped.technicalMessage)
                DesktopChooserResult.Busy -> Unit
            }
            mapped
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val technical = "native picker failed: type=${mode.name.lowercase()} " +
                "operation=select ${error::class.java.simpleName}: ${error.message.orEmpty()}"
            DiagnosticLogger.error(technical, error)
            DesktopChooserResult.Failed(
                "تعذر فتح نافذة الاختيار. حاول مرة أخرى.",
                technical
            )
        }
    }

    private suspend fun executeOnSta(
        mode: DesktopChooserMode,
        initialDirectory: File?,
        title: String,
        ownerHwnd: Long?,
        onNativeTaskComplete: () -> Unit
    ): NativePickerOutput = suspendCancellableCoroutine { continuation ->
        var result: NativePickerOutput? = null
        val completed: () -> Unit = {
            val delivered = result ?: NativePickerOutput.Failed(
                null,
                "native picker task completed without a result"
            )
            if (continuation.isActive) continuation.resume(delivered)
            runCatching { onNativeTaskComplete() }
            Unit
        }
        try {
            coordinator.submitAfterAcquire(
                task = {
                    result = try {
                        adapter.choose(mode, initialDirectory, title, ownerHwnd)
                    } catch (error: Throwable) {
                        NativePickerOutput.Failed(
                            null,
                            "${error::class.java.simpleName}: ${error.message.orEmpty()}"
                        )
                    }
                },
                completed = completed
            )
        } catch (error: Throwable) {
            if (continuation.isActive) {
                continuation.resume(
                    NativePickerOutput.Failed(
                        null,
                        "native picker executor rejected task: " +
                            "${error::class.java.simpleName}: ${error.message.orEmpty()}"
                    )
                )
            }
        }
        // Intentionally do not interrupt/cancel the executor task.  COM modal
        // Show owns the STA until the user dismisses it; cancellation only
        // suppresses delivery to this waiting coroutine.
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)
}