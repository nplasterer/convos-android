package com.naomiplasterer.convos.data.repository

import android.content.Context
import android.util.Log
import com.naomiplasterer.convos.data.settings.QuicknameSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "QuicknameRepository"

@Singleton
class QuicknameRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val _quicknameSettings = MutableStateFlow<QuicknameSettings?>(null)
    val quicknameSettings: StateFlow<QuicknameSettings?> = _quicknameSettings.asStateFlow()

    init {
        loadQuicknameSettings()
    }

    fun loadQuicknameSettings() {
        val settings = QuicknameSettings.load(context)
        _quicknameSettings.value = if (settings.isEmpty) null else settings
        Log.d(TAG, "Loaded Quickname settings: ${_quicknameSettings.value}")
    }

    fun saveQuicknameSettings(displayName: String) {
        val settings = QuicknameSettings(displayName = displayName)
        QuicknameSettings.save(context, settings)
        _quicknameSettings.value = if (settings.isEmpty) null else settings
        Log.d(TAG, "Saved Quickname settings: $settings")
    }

    fun deleteQuicknameSettings() {
        QuicknameSettings.delete(context)
        _quicknameSettings.value = null
        Log.d(TAG, "Deleted Quickname settings")
    }

    fun hasQuickname(): Boolean {
        return _quicknameSettings.value != null
    }
}
