package com.naomiplasterer.convos.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naomiplasterer.convos.data.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sessionManager: SessionManager
) : ViewModel() {

    fun deleteAllData() {
        viewModelScope.launch {
            try {
                sessionManager.deleteAllSessions()
                Timber.d("All app data deleted")
            } catch (e: Exception) {
                Timber.e(e, "Failed to delete all data")
            }
        }
    }
}
