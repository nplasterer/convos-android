package com.naomiplasterer.convos.ui.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naomiplasterer.convos.data.local.dao.MemberProfileDao
import com.naomiplasterer.convos.data.repository.ProfileRepository
import com.naomiplasterer.convos.data.repository.ProfileUpdateResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "MyInfoViewModel"

data class MyInfoUiState(
    val conversationDisplayName: String? = null,
    val isSavingProfile: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val editingDisplayName: String = "",
    val isEditingProfile: Boolean = false
)

@HiltViewModel
class MyInfoViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val memberProfileDao: MemberProfileDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(MyInfoUiState())
    val uiState: StateFlow<MyInfoUiState> = _uiState.asStateFlow()

    private var conversationId: String? = null
    private var inboxId: String? = null

    fun initialize(conversationId: String, inboxId: String) {
        this.conversationId = conversationId
        this.inboxId = inboxId

        viewModelScope.launch {
            loadConversationProfile(conversationId, inboxId)
        }
    }

    private suspend fun loadConversationProfile(conversationId: String, inboxId: String) {
        try {
            val profile = memberProfileDao.getProfile(conversationId, inboxId)
            _uiState.value = _uiState.value.copy(
                conversationDisplayName = profile?.name,
                editingDisplayName = profile?.name ?: ""
            )
            Log.d(TAG, "Loaded conversation profile: ${profile?.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load conversation profile", e)
        }
    }

    fun startEditingProfile() {
        _uiState.value = _uiState.value.copy(
            isEditingProfile = true,
            editingDisplayName = _uiState.value.conversationDisplayName ?: ""
        )
    }

    fun updateEditingDisplayName(name: String) {
        _uiState.value = _uiState.value.copy(editingDisplayName = name)
    }

    fun cancelEditingProfile() {
        _uiState.value = _uiState.value.copy(
            isEditingProfile = false,
            editingDisplayName = _uiState.value.conversationDisplayName ?: ""
        )
    }

    fun saveProfile() {
        val convId = conversationId
        val inbox = inboxId

        if (convId == null || inbox == null) {
            _uiState.value = _uiState.value.copy(errorMessage = "Not initialized")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isSavingProfile = true,
                errorMessage = null,
                successMessage = null
            )

            try {
                val result = profileRepository.updateDisplayName(
                    conversationId = convId,
                    inboxId = inbox,
                    name = _uiState.value.editingDisplayName
                )

                when (result) {
                    is ProfileUpdateResult.Success -> {
                        _uiState.value = _uiState.value.copy(
                            conversationDisplayName = _uiState.value.editingDisplayName,
                            isSavingProfile = false,
                            isEditingProfile = false,
                            successMessage = "Profile updated!"
                        )
                        Log.d(TAG, "Profile saved successfully")
                    }
                    is ProfileUpdateResult.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isSavingProfile = false,
                            errorMessage = result.message
                        )
                        Log.e(TAG, "Failed to save profile: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving profile", e)
                _uiState.value = _uiState.value.copy(
                    isSavingProfile = false,
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
