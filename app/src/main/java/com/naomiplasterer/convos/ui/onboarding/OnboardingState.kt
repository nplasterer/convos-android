package com.naomiplasterer.convos.ui.onboarding

import android.net.Uri

data class QuicknameProfile(
    val displayName: String = "",
    val avatarUri: Uri? = null
)

sealed class OnboardingState {
    object Idle : OnboardingState()

    object Started : OnboardingState()

    data class SetupQuickname(val autoDismiss: Boolean) : OnboardingState()

    object SettingUpQuickname : OnboardingState()

    data class SaveAsQuickname(val profile: QuicknameProfile) : OnboardingState()

    object QuicknameLearnMore : OnboardingState()

    object PresentingProfileSettings : OnboardingState()

    object SavedAsQuicknameSuccess : OnboardingState()

    data class AddQuickname(
        val settings: QuicknameSettings,
        val profileImageUri: Uri?
    ) : OnboardingState()

    companion object {
        const val ADD_QUICKNAME_VIEW_DURATION = 8000L
        const val SETUP_QUICKNAME_VIEW_DURATION = 8000L
        const val USE_AS_QUICKNAME_VIEW_DURATION = 8000L
        const val SAVED_AS_QUICKNAME_SUCCESS_DURATION = 3000L
        const val WAITING_FOR_INVITE_ACCEPTANCE_DELAY = 3000L
    }

    val autodismissDuration: Long?
        get() = when (this) {
            is SetupQuickname -> if (autoDismiss) SETUP_QUICKNAME_VIEW_DURATION else null
            is AddQuickname -> ADD_QUICKNAME_VIEW_DURATION
            is SaveAsQuickname -> USE_AS_QUICKNAME_VIEW_DURATION
            is SavedAsQuicknameSuccess -> SAVED_AS_QUICKNAME_SUCCESS_DURATION
            else -> null
        }
}

data class QuicknameSettings(
    val profile: QuicknameProfile,
    val isDefault: Boolean = true
) {
    companion object {
        fun current(): QuicknameSettings {
            return QuicknameSettings(
                profile = QuicknameProfile(),
                isDefault = true
            )
        }
    }

    val profileImage: Uri?
        get() = profile.avatarUri
}
