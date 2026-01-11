package com.naomiplasterer.convos.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.naomiplasterer.convos.domain.model.Member

private const val TAG = "QuicknameSettings"
private const val PREFS_NAME = "quickname_settings"
private const val KEY_DISPLAY_NAME = "display_name"

data class QuicknameSettings(
    val displayName: String = ""
) {

    val isEmpty: Boolean
        get() = displayName.isBlank()

    fun toMemberWithoutInboxId(): Member {
        return Member(
            inboxId = "",
            addresses = emptyList(),
            permissionLevel = com.naomiplasterer.convos.domain.model.PermissionLevel.MEMBER,
            consentState = com.naomiplasterer.convos.domain.model.ConsentState.ALLOWED,
            addedAt = System.currentTimeMillis(),
            name = displayName.ifBlank { null },
            imageUrl = null
        )
    }

    companion object {
        private fun getPrefs(context: Context): SharedPreferences {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }

        fun load(context: Context): QuicknameSettings {
            val prefs = getPrefs(context)
            val displayName = prefs.getString(KEY_DISPLAY_NAME, "") ?: ""

            return QuicknameSettings(displayName = displayName)
        }

        fun save(context: Context, settings: QuicknameSettings) {
            if (settings.isEmpty) {
                delete(context)
                return
            }

            val prefs = getPrefs(context)
            prefs.edit()
                .putString(KEY_DISPLAY_NAME, settings.displayName)
                .apply()

            Log.d(TAG, "Saved Quickname settings: displayName=${settings.displayName}")
        }

        fun delete(context: Context) {
            val prefs = getPrefs(context)
            prefs.edit().clear().apply()

            Log.d(TAG, "Deleted Quickname settings")
        }
    }
}
