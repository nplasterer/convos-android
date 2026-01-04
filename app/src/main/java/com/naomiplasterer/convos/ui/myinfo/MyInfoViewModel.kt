package com.naomiplasterer.convos.ui.myinfo

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.naomiplasterer.convos.data.quickname.QuicknameSettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MyInfoViewModel @Inject constructor(
    private val quicknameSettingsManager: QuicknameSettingsManager
) : ViewModel() {

    private val _displayName = MutableStateFlow(quicknameSettingsManager.displayName.value)
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    private val _profileImagePath = MutableStateFlow(quicknameSettingsManager.profileImagePath.value)
    val profileImagePath: StateFlow<String?> = _profileImagePath.asStateFlow()

    init {
        viewModelScope.launch {
            quicknameSettingsManager.displayName.collect { name ->
                _displayName.value = name
            }
        }
        viewModelScope.launch {
            quicknameSettingsManager.profileImagePath.collect { path ->
                _profileImagePath.value = path
            }
        }
    }

    fun updateDisplayName(name: String) {
        _displayName.value = name
        quicknameSettingsManager.updateDisplayName(name)
    }

    fun updateProfileImage(uri: Uri?) {
        quicknameSettingsManager.updateProfileImage(uri)
    }

    fun save() {
        quicknameSettingsManager.save()
    }
}
