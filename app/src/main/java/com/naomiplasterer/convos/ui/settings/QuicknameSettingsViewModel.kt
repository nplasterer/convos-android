package com.naomiplasterer.convos.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naomiplasterer.convos.data.repository.QuicknameRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "QuicknameSettingsViewModel"

data class QuicknameSettingsUiState(
    val displayName: String = "",
    val hasChanges: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

@HiltViewModel
class QuicknameSettingsViewModel @Inject constructor(
    private val quicknameRepository: QuicknameRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(QuicknameSettingsUiState())
    val uiState: StateFlow<QuicknameSettingsUiState> = _uiState.asStateFlow()

    private var initialDisplayName: String = ""

    init {
        loadQuickname()
    }

    private fun loadQuickname() {
        viewModelScope.launch {
            quicknameRepository.quicknameSettings.collect { settings ->
                if (settings != null) {
                    initialDisplayName = settings.displayName
                    _uiState.value = _uiState.value.copy(
                        displayName = settings.displayName,
                        hasChanges = false
                    )
                    Log.d(TAG, "Loaded Quickname: ${settings.displayName}")
                } else {
                    initialDisplayName = ""
                    _uiState.value = _uiState.value.copy(
                        displayName = "",
                        hasChanges = false
                    )
                    Log.d(TAG, "No Quickname found")
                }
            }
        }
    }

    fun updateDisplayName(name: String) {
        _uiState.value = _uiState.value.copy(
            displayName = name,
            hasChanges = name != initialDisplayName
        )
    }

    fun saveQuickname() {
        val state = _uiState.value

        if (!state.hasChanges) {
            _uiState.value = state.copy(successMessage = "No changes to save")
            return
        }

        if (state.displayName.isBlank()) {
            _uiState.value = state.copy(errorMessage = "Please enter a name")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(
                isSaving = true,
                errorMessage = null,
                successMessage = null
            )

            try {
                Log.d(TAG, "Saving Quickname: name=${state.displayName}")

                quicknameRepository.saveQuicknameSettings(displayName = state.displayName)

                initialDisplayName = state.displayName

                _uiState.value = state.copy(
                    isSaving = false,
                    hasChanges = false,
                    successMessage = "Quickname saved!"
                )

                Log.d(TAG, "Quickname saved successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to save Quickname", e)
                _uiState.value = state.copy(
                    isSaving = false,
                    errorMessage = e.message ?: "Failed to save Quickname"
                )
            }
        }
    }

    fun deleteQuickname() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Deleting Quickname")
                quicknameRepository.deleteQuicknameSettings()

                initialDisplayName = ""

                _uiState.value = QuicknameSettingsUiState(
                    successMessage = "Quickname deleted"
                )

                Log.d(TAG, "Quickname deleted successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete Quickname", e)
                _uiState.value = _uiState.value.copy(
                    errorMessage = e.message ?: "Failed to delete Quickname"
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
