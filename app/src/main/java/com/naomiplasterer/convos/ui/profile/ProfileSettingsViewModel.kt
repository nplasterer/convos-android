package com.naomiplasterer.convos.ui.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naomiplasterer.convos.data.repository.ProfileRepository
import com.naomiplasterer.convos.data.repository.ProfileUpdateResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "ProfileSettingsViewModel"

data class ProfileSettingsState(
    val displayName: String = "",
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

@HiltViewModel
class ProfileSettingsViewModel @Inject constructor(
    private val profileRepository: ProfileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileSettingsState())
    val uiState: StateFlow<ProfileSettingsState> = _uiState.asStateFlow()

    private var conversationId: String? = null
    private var inboxId: String? = null

    fun initialize(conversationId: String, inboxId: String, currentName: String?) {
        this.conversationId = conversationId
        this.inboxId = inboxId
        _uiState.value = _uiState.value.copy(
            displayName = currentName ?: ""
        )
        Log.d(TAG, "Initialized with conversationId=$conversationId, inboxId=$inboxId, name=$currentName")
    }

    fun updateDisplayName(name: String) {
        _uiState.value = _uiState.value.copy(displayName = name)
    }

    fun saveProfile() {
        val state = _uiState.value
        val convId = conversationId
        val inbox = inboxId

        if (convId == null || inbox == null) {
            _uiState.value = state.copy(errorMessage = "Not initialized")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(
                isSaving = true,
                errorMessage = null,
                successMessage = null
            )

            try {
                Log.d(TAG, "Saving profile: name=${state.displayName}")

                val nameResult = profileRepository.updateDisplayName(
                    conversationId = convId,
                    inboxId = inbox,
                    name = state.displayName
                )

                when (nameResult) {
                    is ProfileUpdateResult.Success -> {
                        _uiState.value = _uiState.value.copy(
                            isSaving = false,
                            successMessage = "Profile updated successfully!"
                        )
                    }
                    is ProfileUpdateResult.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isSaving = false,
                            errorMessage = nameResult.message
                        )
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error saving profile", e)
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = e.message ?: "Failed to save profile"
                )
            }
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(
            errorMessage = null,
            successMessage = null
        )
    }
}
