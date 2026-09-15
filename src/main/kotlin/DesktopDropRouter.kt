import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.awt.Component
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/**
 * Compose Desktop's content is hosted by an AWT component.  Installing one
 * exact DropTarget on that component gives Windows Explorer's real file-list
 * payload to the same workflow callbacks used by the native picker; no second
 * archive analyzer or game-folder implementation is introduced.
 */
class DesktopDropRouter(
    private val onDrop: (DesktopDropTarget, List<File>) -> Unit,
    private val onRejected: (String) -> Unit = {}
) {
    constructor(dropHandler: (DesktopDropTarget, List<File>) -> Unit) : this(dropHandler, {})

    private val regions = linkedMapOf<DesktopDropTarget, Rect>()
    private val _hovered = MutableStateFlow<DesktopDropTarget?>(null)
    val hovered: StateFlow<DesktopDropTarget?> = _hovered

    @Synchronized
    fun setRegion(target: DesktopDropTarget, bounds: Rect) {
        regions[target] = bounds
    }

    @Synchronized
    fun clearRegion(target: DesktopDropTarget) {
        regions.remove(target)
        if (_hovered.value == target) _hovered.value = null
    }

    @Synchronized
    fun targetAt(x: Int, y: Int): DesktopDropTarget? =
        regions.entries.firstOrNull { (_, bounds) ->
            bounds.contains(Offset(x.toFloat(), y.toFloat()))
        }?.key

    fun inspectFiles(target: DesktopDropTarget, files: List<File>): Boolean =
        files.size == 1 && files.single().let { file ->
            when (target) {
                DesktopDropTarget.MOD_PACKAGE ->
                    file.isFile && file.name.endsWith(".zip", ignoreCase = true)
                DesktopDropTarget.GAME_FOLDER -> file.isDirectory
            }
        }

    internal fun hover(target: DesktopDropTarget?) {
        _hovered.value = target
    }

    internal fun dispatch(target: DesktopDropTarget?, files: List<File>): Boolean {
        if (target == null) {
            onRejected("تم رفض السحب: أسقط الملف داخل منطقة ZIP أو مجلد اللعبة المحددة.")
            return false
        }
        if (!inspectFiles(target, files)) {
            val message = when {
                files.isEmpty() ->
                    "تم رفض السحب: لم يتم العثور على ملف صالح."
                files.size > 1 ->
                    "تم رفض السحب: اسحب ملفًا واحدًا فقط في كل مرة."
                target == DesktopDropTarget.MOD_PACKAGE ->
                    "تم رفض السحب: منطقة المودات تقبل ملف ZIP واحدًا فقط."
                else ->
                    "تم رفض السحب: منطقة الألعاب تقبل مجلد لعبة واحدًا فقط."
            }
            onRejected(message)
            return false
        }
        onDrop(target, files)
        return true
    }
}

private fun extractFiles(event: java.awt.datatransfer.Transferable): List<File>? =
    runCatching {
        if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return@runCatching null
        @Suppress("UNCHECKED_CAST")
        (event.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
            ?.filterIsInstance<File>()
            ?.filter { it.exists() }
    }.getOrNull()

private fun findDropComponent(window: Window): Component? {
    fun descend(component: Component): Component? {
        val container = component as? java.awt.Container ?: return null
        if (component !== window &&
            component::class.java.name.contains("Compose", ignoreCase = true)
        ) return component
        return container.components.asSequence().mapNotNull(::descend).firstOrNull()
    }
    val root: Component = (window as? javax.swing.JFrame)?.contentPane ?: window
    return descend(root) ?: root
}

/**
 * Installs/removes the AWT DropTarget exactly once for this window.  Regions
 * are Compose-root bounds and the target is attached to the Compose host, so
 * an accidental drop outside either highlighted area is rejected visibly.
 */
@Composable
fun DesktopDropTargetHost(
    window: Window,
    router: DesktopDropRouter,
    content: @Composable () -> Unit
) {
    val hovered by router.hovered.collectAsState()
    DisposableEffect(window, router) {
        var component: Component? = null
        var target: DropTarget? = null
        val disposed = AtomicBoolean(false)
        val listener = object : DropTargetAdapter() {
            override fun dragEnter(event: DropTargetDragEvent) {
                val files = extractFiles(event.transferable)
                val targetAt = router.targetAt(event.location.x, event.location.y)
                if (targetAt != null) {
                    event.acceptDrag(DnDConstants.ACTION_COPY)
                    router.hover(
                        targetAt.takeIf { files != null && router.inspectFiles(it, files) }
                    )
                } else {
                    event.rejectDrag()
                    router.hover(null)
                }
            }

            override fun dragOver(event: DropTargetDragEvent) {
                val files = extractFiles(event.transferable)
                val targetAt = router.targetAt(event.location.x, event.location.y)
                if (targetAt != null) {
                    event.acceptDrag(DnDConstants.ACTION_COPY)
                    router.hover(
                        targetAt.takeIf { files != null && router.inspectFiles(it, files) }
                    )
                } else {
                    event.rejectDrag()
                    router.hover(null)
                }
            }

            override fun dragExit(event: DropTargetEvent) {
                router.hover(null)
            }

            override fun drop(event: DropTargetDropEvent) {
                val files = extractFiles(event.transferable).orEmpty()
                val targetAt = router.targetAt(event.location.x, event.location.y)
                val accepted = router.dispatch(targetAt, files)
                if (accepted) {
                    event.acceptDrop(DnDConstants.ACTION_COPY)
                    event.dropComplete(true)
                } else {
                    event.rejectDrop()
                    event.dropComplete(false)
                }
                router.hover(null)
            }
        }
        // Compose may not have created its AWT peer at the instant this effect
        // runs; defer the lookup to the EDT without blocking Compose.
        SwingUtilities.invokeLater {
            if (disposed.get()) return@invokeLater
            component = findDropComponent(window)
            component?.let {
                target = DropTarget(it, DnDConstants.ACTION_COPY, listener, true)
            }
        }
        onDispose {
            disposed.set(true)
            router.hover(null)
            SwingUtilities.invokeLater {
                target?.let { installed ->
                    if (component?.dropTarget === installed) component?.dropTarget = null
                    installed.isActive = false
                }
            }
        }
    }
    content()
    // Reading the value keeps the host recomposed for callers that place
    // visual drop highlights at this level; children use the same StateFlow.
    @Suppress("UNUSED_VARIABLE")
    val ignored = hovered
}

fun Modifier.registerDesktopDropTarget(
    router: DesktopDropRouter,
    target: DesktopDropTarget
): Modifier = onGloballyPositioned { coordinates ->
    router.setRegion(target, coordinates.boundsInRoot())
}
