package com.naomiplasterer.convos.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naomiplasterer.convos.data.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import android.util.Log
import javax.inject.Inject

private const val TAG = "SettingsViewModel"

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sessionManager: SessionManager
) : ViewModel() {

    fun deleteAllData() {
        viewModelScope.launch {
            try {
                sessionManager.deleteAllSessions()
                Log.d(TAG, "All app data deleted")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete all data", e)
            }
        }
    }
}
