package app.nougat.pdf.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.nougat.pdf.engine.NPoint
import app.nougat.pdf.viewer.InkColors
import app.nougat.pdf.viewer.Signature
import kotlin.math.max
import kotlin.math.min
import app.nougat.pdf.i18n.Str
import app.nougat.pdf.i18n.tr

/** Ecrire ou modifier un texte. Renvoie (texte, taille, couleur) ; un texte vide supprime. */
@Composable
fun TextDialog(
    initialText: String,
    initialSize: Float,
    initialColor: Int,
    isNew: Boolean,
    onDone: (text: String, size: Float, color: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    var size by remember { mutableStateOf(initialSize) }
    var color by remember { mutableStateOf(initialColor) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(if (isNew) Str.AddText else Str.EditTextTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(tr(Str.YourText)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Text(tr(Str.Size), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((label, value) in listOf(tr(Str.SizeSmall) to 10f, tr(Str.SizeNormal) to 14f, tr(Str.SizeLarge) to 20f, tr(Str.SizeTitle) to 28f)) {
                        FilterChip(selected = size == value, onClick = { size = value }, label = { Text(label) })
                    }
                }
                Text(tr(Str.Color), style = MaterialTheme.typography.labelLarge)
                ColorRow(InkColors.pen, color) { color = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(text.trimEnd(), size, color) }) { Text(tr(if (isNew) Str.Add else Str.Ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(Str.Cancel)) } },
    )
}

@Composable
fun ColorRow(colors: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (c in colors) {
            val isSelected = c == selected
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                    )
                    .clickable { onSelect(c) },
            )
        }
    }
}

/** Dessiner sa signature au doigt ou a la souris. */
@Composable
fun SignatureDialog(onDone: (Signature) -> Unit, onDismiss: () -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    val current = remember { mutableStateListOf<Offset>() }
    val ink = Color(InkColors.Blue)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(Str.YourSignature)) },
        text = {
            Column {
                Text(tr(Str.SignInBox), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(2.5f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium),
                ) {
                    Canvas(
                        Modifier.matchParentSizeCompat().pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { current.clear(); current.add(it) },
                                onDrag = { change, _ -> change.consume(); current.add(change.position) },
                                onDragEnd = { if (current.isNotEmpty()) strokes.add(current.toList()); current.clear() },
                                onDragCancel = { current.clear() },
                            )
                        },
                    ) {
                        // Ligne de signature
                        drawLine(Color(0xFFBDBDBD), Offset(size.width * 0.08f, size.height * 0.78f), Offset(size.width * 0.92f, size.height * 0.78f), 1.5f)
                        for (s in strokes + listOf(current.toList())) {
                            if (s.isEmpty()) continue
                            val path = Path().apply {
                                moveTo(s[0].x, s[0].y)
                                for (p in s.drop(1)) lineTo(p.x, p.y)
                            }
                            drawPath(path, ink, style = Stroke(width = 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = strokes.isNotEmpty(),
                onClick = { normalize(strokes)?.let(onDone) },
            ) { Text(tr(Str.Use)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { strokes.clear() }) { Text(tr(Str.Clear)) }
                TextButton(onClick = onDismiss) { Text(tr(Str.Cancel)) }
            }
        },
    )
}

/** Recadre les traits sur leur encombrement : la signature ne garde que l'utile. */
private fun normalize(strokes: List<List<Offset>>): Signature? {
    val all = strokes.flatten()
    if (all.isEmpty()) return null
    val pad = 4f
    val left = all.minOf { it.x } - pad
    val top = all.minOf { it.y } - pad
    val right = all.maxOf { it.x } + pad
    val bottom = all.maxOf { it.y } + pad
    val w = max(right - left, 1f)
    val h = max(bottom - top, 1f)
    return Signature(
        strokes = strokes.map { s -> s.map { NPoint((it.x - left) / w, (it.y - top) / h) } },
        aspectRatio = min(max(w / h, 0.5f), 12f),
    )
}

/** Proteger le document par un mot de passe. */
@Composable
fun SetPasswordDialog(onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val mismatch = second.isNotEmpty() && first != second
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(Str.ProtectWithPassword)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr(Str.PasswordWarning))
                OutlinedTextField(
                    value = first, onValueChange = { first = it }, label = { Text(tr(Str.Password)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = second, onValueChange = { second = it }, label = { Text(tr(Str.Confirm)) }, singleLine = true,
                    isError = mismatch,
                    supportingText = if (mismatch) ({ Text(tr(Str.PasswordsDiffer)) }) else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = first.isNotEmpty() && first == second, onClick = { onDone(first) }) { Text(tr(Str.Protect)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(Str.Cancel)) } },
    )
}

/** « Enregistrer les modifications ? » : true, false, ou null pour annuler. */
@Composable
fun ConfirmSaveDialog(name: String, onAnswer: (Boolean?) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(null) },
        title = { Text(tr(Str.SaveChangesTitle)) },
        text = { Text(tr(Str.SaveChangesText, name)) },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text(tr(Str.Save)) } },
        dismissButton = {
            Row(Modifier.padding(end = 4.dp)) {
                TextButton(onClick = { onAnswer(null) }) { Text(tr(Str.Cancel)) }
                TextButton(onClick = { onAnswer(false) }) {
                    Text(tr(Str.DontSave), color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}

private fun Modifier.matchParentSizeCompat(): Modifier = this.then(Modifier.fillMaxWidth().aspectRatio(2.5f))
