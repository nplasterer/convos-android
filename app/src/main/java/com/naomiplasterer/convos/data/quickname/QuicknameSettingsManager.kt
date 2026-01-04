package com.naomiplasterer.convos.data.quickname

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "QuicknameSettingsManager"
private const val PREFS_NAME = "quickname_settings"
private const val KEY_DISPLAY_NAME = "display_name"
private const val KEY_PROFILE_IMAGE_PATH = "profile_image_path"
private const val PROFILE_IMAGE_FILENAME = "quickname_profile_image.jpg"

@Singleton
class QuicknameSettingsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<QuicknameSettings> = _settings.asStateFlow()

    private val _displayName = MutableStateFlow(_settings.value.displayName)
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    private val _profileImagePath = MutableStateFlow(_settings.value.profileImagePath)
    val profileImagePath: StateFlow<String?> = _profileImagePath.asStateFlow()

    fun updateDisplayName(name: String) {
        _displayName.value = name
        _settings.value = _settings.value.withDisplayName(name)
    }

    fun updateProfileImage(uri: Uri?) {
        if (uri == null) {
            deleteProfileImage()
            _profileImagePath.value = null
            _settings.value = _settings.value.withProfileImagePath(null)
            return
        }

        try {
            val imagePath = saveProfileImageFromUri(uri)
            _profileImagePath.value = imagePath
            _settings.value = _settings.value.withProfileImagePath(imagePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save profile image", e)
        }
    }

    fun save() {
        val currentSettings = _settings.value

        if (currentSettings.isDefault) {
            delete()
            return
        }

        prefs.edit()
            .putString(KEY_DISPLAY_NAME, currentSettings.displayName)
            .putString(KEY_PROFILE_IMAGE_PATH, currentSettings.profileImagePath)
            .apply()

        Log.d(TAG, "Quickname settings saved")
    }

    fun delete() {
        prefs.edit().clear().apply()
        deleteProfileImage()
        _displayName.value = ""
        _profileImagePath.value = null
        _settings.value = QuicknameSettings.DEFAULT
        Log.d(TAG, "Quickname settings deleted")
    }

    private fun loadSettings(): QuicknameSettings {
        val displayName = prefs.getString(KEY_DISPLAY_NAME, "") ?: ""
        val imagePath = prefs.getString(KEY_PROFILE_IMAGE_PATH, null)

        val validImagePath = imagePath?.let { path ->
            if (File(path).exists()) path else null
        }

        return QuicknameSettings(
            displayName = displayName,
            profileImagePath = validImagePath
        )
    }

    private fun saveProfileImageFromUri(uri: Uri): String {
        val imageFile = File(context.filesDir, PROFILE_IMAGE_FILENAME)

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(imageFile).use { output ->
                input.copyTo(output)
            }
        }

        return imageFile.absolutePath
    }

    private fun deleteProfileImage() {
        val imageFile = File(context.filesDir, PROFILE_IMAGE_FILENAME)
        if (imageFile.exists()) {
            imageFile.delete()
            Log.d(TAG, "Profile image deleted")
        }
    }
}
