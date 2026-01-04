package com.naomiplasterer.convos.data.quickname

data class QuicknameSettings(
    val displayName: String = "",
    val profileImagePath: String? = null
) {
    val isDefault: Boolean
        get() = displayName.isEmpty() && profileImagePath == null

    fun withDisplayName(name: String): QuicknameSettings {
        return copy(displayName = name)
    }

    fun withProfileImagePath(path: String?): QuicknameSettings {
        return copy(profileImagePath = path)
    }

    companion object {
        val DEFAULT = QuicknameSettings()
    }
}
