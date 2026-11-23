package com.pako.eminus4.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pako.eminus4.data.repository.FileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface FileViewerState {
    data object Loading : FileViewerState
    data class Ready(val file: File) : FileViewerState
    data class Failed(val message: String) : FileViewerState
}

class FileViewerViewModel(private val files: FileRepository) : ViewModel() {

    private val _state = MutableStateFlow<FileViewerState>(FileViewerState.Loading)
    val state: StateFlow<FileViewerState> = _state.asStateFlow()

    private var requested = false

    /** Se descarga una sola vez (al girar la pantalla no se vuelve a pedir). */
    fun load(url: String, fileName: String) {
        if (requested) return
        requested = true
        viewModelScope.launch {
            _state.value = files.get(url, fileName).fold(
                { FileViewerState.Ready(it) },
                { FileViewerState.Failed("No se pudo cargar el archivo: ${it.localizedMessage}") }
            )
        }
    }
}
