package com.naomiplasterer.convos.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naomiplasterer.convos.data.quickname.QuicknameSettingsManager
import com.naomiplasterer.convos.data.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import android.util.Log
import javax.inject.Inject

private const val TAG = "SettingsViewModel"

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val quicknameSettingsManager: QuicknameSettingsManager
) : ViewModel() {

    val quicknameDisplayName: StateFlow<String> = quicknameSettingsManager.displayName
    val quicknameImagePath: StateFlow<String?> = quicknameSettingsManager.profileImagePath

    fun deleteAllData() {
        viewModelScope.launch {
            try {
                sessionManager.deleteAllSessions()
                quicknameSettingsManager.delete()
                Log.d(TAG, "All app data deleted")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete all data", e)
            }
        }
    }
}
