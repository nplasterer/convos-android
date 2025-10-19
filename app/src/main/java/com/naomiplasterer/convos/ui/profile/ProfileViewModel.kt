package com.naomiplasterer.convos.ui.profile

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naomiplasterer.convos.data.local.dao.ProfileDao
import com.naomiplasterer.convos.data.local.entity.ProfileEntity
import com.naomiplasterer.convos.data.session.SessionManager
import com.naomiplasterer.convos.data.session.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profileDao: ProfileDao,
    private val sessionManager: SessionManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val conversationId: String = checkNotNull(savedStateHandle["conversationId"])

    private val _uiState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private val _displayName = MutableStateFlow("")
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    private val _imageUri = MutableStateFlow<String?>(null)
    val imageUri: StateFlow<String?> = _imageUri.asStateFlow()

    private val _useForQuickname = MutableStateFlow(false)
    val useForQuickname: StateFlow<Boolean> = _useForQuickname.asStateFlow()

    init {
        loadProfile()
    }

    private fun loadProfile() {
        viewModelScope.launch {
            try {
                val sessionState = sessionManager.sessionState.value
                if (sessionState !is SessionState.Active) {
                    _uiState.value = ProfileUiState.Error("No active session")
                    return@launch
                }

                val inboxId = sessionState.inboxId
                val profile = profileDao.getProfile(inboxId, conversationId).firstOrNull()

                if (profile != null) {
                    _displayName.value = profile.displayName ?: ""
                    _imageUri.value = profile.avatarUrl
                    _useForQuickname.value = false
                    _uiState.value = ProfileUiState.Success
                } else {
                    _displayName.value = ""
                    _imageUri.value = null
                    _useForQuickname.value = false
                    _uiState.value = ProfileUiState.Success
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load profile")
                _uiState.value = ProfileUiState.Error(e.message ?: "Failed to load profile")
            }
        }
    }

    fun updateDisplayName(name: String) {
        _displayName.value = name
    }

    fun updateImageUri(uri: Uri?) {
        _imageUri.value = uri?.toString()
    }

    fun updateUseForQuickname(use: Boolean) {
        _useForQuickname.value = use
    }

    fun saveProfile() {
        viewModelScope.launch {
            try {
                val sessionState = sessionManager.sessionState.value
                if (sessionState !is SessionState.Active) {
                    _uiState.value = ProfileUiState.Error("No active session")
                    return@launch
                }

                val inboxId = sessionState.inboxId

                val profile = ProfileEntity(
                    inboxId = inboxId,
                    conversationId = conversationId,
                    displayName = _displayName.value.ifBlank { null },
                    avatarUrl = _imageUri.value
                )

                profileDao.insert(profile)
                Timber.d("Profile saved successfully")
            } catch (e: Exception) {
                Timber.e(e, "Failed to save profile")
                _uiState.value = ProfileUiState.Error(e.message ?: "Failed to save profile")
            }
        }
    }
}

sealed class ProfileUiState {
    object Loading : ProfileUiState()
    object Success : ProfileUiState()
    data class Error(val message: String) : ProfileUiState()
}
