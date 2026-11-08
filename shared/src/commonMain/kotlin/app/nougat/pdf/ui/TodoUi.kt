package app.nougat.pdf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.nougat.pdf.i18n.Str
import app.nougat.pdf.i18n.tr
import app.nougat.pdf.viewer.ViewerState

/** « Ce que je dois faire » : le resume en bref et les choses a faire, avec les dates. */
@Composable
fun TodoDialog(state: ViewerState) {
    val todo = state.todo
    val digest = todo.digest
    val muted = LocalNougatPalette.current.muted
    AlertDialog(
        onDismissRequest = state::closeTodo,
        icon = { Icon(NougatIcons.Todo, contentDescription = null) },
        title = { Text(tr(Str.Todo)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    todo.working -> {
                        Text(tr(Str.TodoReading), style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    todo.noText -> Text(tr(Str.TodoNoText), style = MaterialTheme.typography.bodyMedium)
                    digest != null -> {
                        if (digest.summary.isNotBlank()) {
                            Text(tr(Str.TodoSummary), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(digest.summary, style = MaterialTheme.typography.bodyLarge)
                        }
                        if (!digest.fromAi) Text(tr(Str.TodoNoAi), style = MaterialTheme.typography.bodySmall, color = muted)
                        todo.aiError?.let { Text(tr(Str.TodoAiFailed, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        Text(tr(Str.TodoTasks), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        if (digest.tasks.isEmpty()) Text(tr(Str.TodoNothing), style = MaterialTheme.typography.bodyMedium, color = muted)
                        for (task in digest.tasks) {
                            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                                Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            task.date?.let(state::todoDate) ?: tr(Str.TodoNoDate),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (task.date != null) MaterialTheme.colorScheme.primary else muted,
                                        )
                                        Text(task.what, style = MaterialTheme.typography.bodyMedium)
                                    }
                                    if (task.date != null) {
                                        IconButton(onClick = { state.addTodoToCalendar(task) }) {
                                            Icon(Icons.Filled.DateRange, contentDescription = tr(Str.TodoCalendar), modifier = Modifier.size(22.dp))
                                        }
                                    }
                                }
                            }
                        }
                        if (digest.tasks.any { it.date != null }) Text(tr(Str.TodoCheck), style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = state::closeTodo) { Text(tr(Str.Close)) } },
        dismissButton = {
            if (digest != null && (digest.summary.isNotBlank() || digest.tasks.isNotEmpty())) {
                TextButton(onClick = state::copyTodo) { Text(tr(Str.TodoCopy)) }
            }
        },
    )
}
