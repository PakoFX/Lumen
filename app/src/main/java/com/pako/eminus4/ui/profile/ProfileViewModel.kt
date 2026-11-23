package com.pako.eminus4.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pako.eminus4.data.model.UserProfile
import com.pako.eminus4.data.repository.CredentialRepository
import com.pako.eminus4.data.repository.CredentialRepository.PhotoResult
import com.pako.eminus4.data.repository.ProfileRepository
import com.pako.eminus4.domain.ProfileCard
import com.pako.eminus4.domain.ProfileCardBuilder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(val card: ProfileCard? = null, val reloadingPhoto: Boolean = false)

sealed interface ProfileEvent {
    data object PhotoUpdated : ProfileEvent
    data object NoPhoto : ProfileEvent
    /** Hay que entrar otra vez a MiUV para traer la foto. */
    data object NeedsLogin : ProfileEvent
}

class ProfileViewModel(
    private val profiles: ProfileRepository,
    private val credentials: CredentialRepository,
    private val clock: () -> Long = System::currentTimeMillis
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    private val _events = Channel<ProfileEvent>(Channel.BUFFERED)
    val events: Flow<ProfileEvent> = _events.receiveAsFlow()

    private var lastApiLoadAt = 0L

    /**
     * Al volver a la pestaña o a la app (p. ej. tras renovar la sesión o sincronizar con MiUV):
     * la copia del teléfono siempre se repinta; Eminus como mucho cada 30 s.
     */
    fun refresh() {
        show(profiles.cached())
        if (clock() - lastApiLoadAt <= API_MIN_INTERVAL_MS) return
        lastApiLoadAt = clock()
        viewModelScope.launch {
            profiles.refresh()
                .onSuccess(::show)
                .onFailure { lastApiLoadAt = 0L }   // ya se ve la copia; reintentar al volver
        }
    }

    /** Tras editar la credencial a mano. */
    fun redraw() = show(profiles.cached())

    /** Vuelve a traer la foto oficial de MiUV. */
    fun reloadPhoto() {
        if (_state.value.reloadingPhoto) return
        _state.update { it.copy(reloadingPhoto = true) }
        viewModelScope.launch {
            val result = credentials.reloadPhoto()
            _state.update { it.copy(reloadingPhoto = false) }
            when (result) {
                PhotoResult.OK -> { show(profiles.cached()); _events.send(ProfileEvent.PhotoUpdated) }
                PhotoResult.NO_PHOTO -> _events.send(ProfileEvent.NoPhoto)
                else -> _events.send(ProfileEvent.NeedsLogin)
            }
        }
    }

    private fun show(profile: UserProfile) {
        val result = ProfileCardBuilder.build(profile, profiles.savedCredential())
        result.credentialToSave?.let(profiles::saveCredential)
        _state.update { it.copy(card = result.card) }
    }

    private companion object {
        const val API_MIN_INTERVAL_MS = 30_000L
    }
}
