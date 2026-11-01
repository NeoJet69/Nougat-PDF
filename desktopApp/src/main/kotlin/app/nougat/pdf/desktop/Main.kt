package app.nougat.pdf.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.nougat.pdf.engine.DesktopPdfEngine
import app.nougat.pdf.ui.NougatApp
import app.nougat.pdf.ui.NougatIcons
import app.nougat.pdf.ui.PlatformInfo
import app.nougat.pdf.viewer.ViewerState
import java.awt.FileDialog
import java.awt.Frame
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import javax.swing.SwingUtilities

fun main(args: Array<String>) = application {
    val scope = rememberCoroutineScope()
    val state = remember { ViewerState(DesktopPdfEngine(), scope) }
    // La fenetre, pour y rattacher la boite de dialogue « Ouvrir » appelee par Ctrl+O.
    var frame by remember { mutableStateOf<Frame?>(null) }
    val openDialog = { frame?.let(::chooseFile)?.let(state::openFile) }

    // Double-clic sur un PDF dans l'Explorateur Windows : le fichier arrive ici.
    LaunchedEffect(Unit) {
        args.firstOrNull()?.let { File(it) }?.takeIf { it.isFile }?.let { state.openFile(it) }
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = state.session?.let { "${it.name} – Nougat PDF" } ?: "Nougat PDF",
        icon = rememberVectorPainter(NougatIcons.NougatLogo),
        state = rememberWindowState(width = 1100.dp, height = 820.dp),
        onPreviewKeyEvent = { handleShortcut(it, state) { openDialog() } },
    ) {
        LaunchedEffect(window) {
            frame = window
            installDropTarget(window, state)
        }
        NougatApp(
            state = state,
            platform = PlatformInfo(isDesktop = true),
            onOpenFile = { openDialog() },
        )
    }
}

private fun ViewerState.openFile(file: File) = open(file.absolutePath, file.name)

/** La fenetre « Ouvrir » native de Windows, limitee aux PDF. */
private fun chooseFile(parent: Frame): File? {
    val dialog = FileDialog(parent, "Ouvrir un PDF", FileDialog.LOAD).apply {
        file = "*.pdf"
        setFilenameFilter { _, name -> name.lowercase().endsWith(".pdf") }
        isVisible = true
    }
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}

/** Raccourcis clavier. Renvoie true si la touche a ete utilisee. */
private fun handleShortcut(event: KeyEvent, state: ViewerState, openDialog: () -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val session = state.session
    if (event.isCtrlPressed) {
        when (event.key) {
            Key.O -> { openDialog(); return true }
            Key.W -> { if (session != null) { state.closeDocument(); return true } }
            Key.F -> { session?.openSearch(); return session != null }
            Key.Equals, Key.Plus, Key.NumPadAdd -> { session?.zoomIn(); return session != null }
            Key.Minus, Key.NumPadSubtract -> { session?.zoomOut(); return session != null }
            Key.Zero, Key.NumPad0 -> { session?.resetZoom(); return session != null }
            Key.Home -> { session?.goToPage(0); return session != null }
            Key.MoveEnd -> { session?.let { it.goToPage(it.pageCount - 1) }; return session != null }
        }
        return false
    }
    if (session == null) return false
    return when (event.key) {
        Key.F3 -> { if (session.hits.isNotEmpty()) session.nextHit(); true }
        Key.PageDown -> { session.nextPage(); true }
        Key.PageUp -> { session.previousPage(); true }
        else -> false
    }
}

/** Glisser un fichier PDF sur la fenetre pour l'ouvrir. */
private fun installDropTarget(window: java.awt.Window, state: ViewerState) {
    val target = (window as? javax.swing.JFrame)?.contentPane ?: window
    target.dropTarget = DropTarget(target, object : DropTargetAdapter() {
        override fun drop(event: DropTargetDropEvent) {
            event.acceptDrop(DnDConstants.ACTION_COPY)
            val files = runCatching {
                @Suppress("UNCHECKED_CAST")
                event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
            }.getOrNull().orEmpty()
            val pdf = files.firstOrNull { it.name.lowercase().endsWith(".pdf") }
            event.dropComplete(pdf != null)
            if (pdf != null) SwingUtilities.invokeLater { state.openFile(pdf) }
        }
    })
}
