package app.nougat.pdf.assistant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Etat de l'assistant dans l'interface. */
class AssistantUiState {
    var open by mutableStateOf(false)
    var running by mutableStateOf(false)
    var input by mutableStateOf("")
    var showSettings by mutableStateOf(false)
    val items = mutableStateListOf<ChatItem>()
}
