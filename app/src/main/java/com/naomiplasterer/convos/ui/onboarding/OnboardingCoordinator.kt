package com.naomiplasterer.convos.ui.onboarding

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "OnboardingCoordinator"
private const val PREFS_NAME = "onboarding_prefs"
private const val KEY_HAS_SHOWN_QUICKNAME_EDITOR = "has_shown_quickname_editor"
private const val KEY_HAS_COMPLETED_ONBOARDING = "has_completed_onboarding"
private const val KEY_HAS_SEEN_ADD_AS_QUICKNAME = "has_seen_add_as_quickname"
private const val KEY_HAS_SET_QUICKNAME_PREFIX = "has_set_quickname_for_"

class OnboardingCoordinator(
    private val context: Context,
    private val scope: CoroutineScope
) {
    var state by mutableStateOf<OnboardingState>(OnboardingState.Idle)
        private set

    var isWaitingForInviteAcceptance by mutableStateOf(false)

    var shouldAnimateAvatarForQuicknameSetup by mutableStateOf(false)
        private set

    private var autodismissJob: Job? = null
    private var shouldShowQuicknameAfterFlow = false
    private var pendingConversationId: String? = null

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    val showOnboardingView: Boolean
        get() = inProgress || isWaitingForInviteAcceptance

    val inProgress: Boolean
        get() = state !is OnboardingState.Idle

    val isSettingUpQuickname: Boolean
        get() = state is OnboardingState.SettingUpQuickname ||
                state is OnboardingState.PresentingProfileSettings

    private var hasShownQuicknameEditor: Boolean
        get() = prefs.getBoolean(KEY_HAS_SHOWN_QUICKNAME_EDITOR, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_SHOWN_QUICKNAME_EDITOR, value).apply()

    private var hasCompletedOnboarding: Boolean
        get() = prefs.getBoolean(KEY_HAS_COMPLETED_ONBOARDING, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_COMPLETED_ONBOARDING, value).apply()

    private var hasSeenAddAsQuickname: Boolean
        get() = prefs.getBoolean(KEY_HAS_SEEN_ADD_AS_QUICKNAME, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_SEEN_ADD_AS_QUICKNAME, value).apply()

    private fun hasSetQuickname(conversationId: String): Boolean {
        return prefs.getBoolean(KEY_HAS_SET_QUICKNAME_PREFIX + conversationId, false)
    }

    private fun setHasSetQuickname(conversationId: String, value: Boolean) {
        prefs.edit().putBoolean(KEY_HAS_SET_QUICKNAME_PREFIX + conversationId, value).apply()
    }

    fun reset() {
        hasSeenAddAsQuickname = false
        hasCompletedOnboarding = false
        hasShownQuicknameEditor = false
    }

    fun resetForConversation(conversationId: String) {
        hasCompletedOnboarding = false
        hasShownQuicknameEditor = false
        state = OnboardingState.Idle
        setHasSetQuickname(conversationId, false)
    }

    suspend fun start(conversationId: String) {
        if (state !is OnboardingState.Idle) {
            return
        }

        state = OnboardingState.Started

        if (isWaitingForInviteAcceptance) {
            startQuicknameFlow(conversationId)
        } else {
            startQuicknameFlow(conversationId)
        }
    }

    private suspend fun startQuicknameFlow(conversationId: String) {
        val hasSetQuicknameForConversation = hasSetQuickname(conversationId)
        setHasSetQuickname(conversationId, true)

        val quicknameSettings = QuicknameSettings.current()

        when {
            !hasShownQuicknameEditor -> {
                shouldAnimateAvatarForQuicknameSetup = true
                state = OnboardingState.SetupQuickname(autoDismiss = false)
                handleStateChange()
            }
            quicknameSettings.isDefault && !hasSetQuicknameForConversation -> {
                shouldAnimateAvatarForQuicknameSetup = true
                state = OnboardingState.SetupQuickname(autoDismiss = true)
                handleStateChange()
            }
            !hasSetQuicknameForConversation -> {
                val profileImage = quicknameSettings.profileImage
                state = OnboardingState.AddQuickname(quicknameSettings, profileImage)
                handleStateChange()
            }
            else -> {
                complete()
            }
        }
    }

    suspend fun inviteWasAccepted(conversationId: String) {
        isWaitingForInviteAcceptance = false

        when (state) {
            is OnboardingState.Idle, is OnboardingState.Started -> {
                startQuicknameFlow(conversationId)
            }
            else -> {
                // Continue with current flow
            }
        }
    }

    fun didTapProfilePhoto() {
        if (state !is OnboardingState.SetupQuickname) {
            return
        }
        hasShownQuicknameEditor = true
        shouldAnimateAvatarForQuicknameSetup = false
        state = OnboardingState.SettingUpQuickname
        handleStateChange()
    }

    fun presentWhatIsQuickname() {
        state = OnboardingState.QuicknameLearnMore
        handleStateChange()
    }

    fun onContinueFromWhatIsQuickname() {
        state = OnboardingState.PresentingProfileSettings
        handleStateChange()
    }

    suspend fun setupQuicknameDidAutoDismiss() {
        hasShownQuicknameEditor = true
        shouldAnimateAvatarForQuicknameSetup = false
        complete()
    }

    suspend fun didSelectQuickname() {
        shouldAnimateAvatarForQuicknameSetup = false
        complete()
    }

    private suspend fun saveAsQuicknameDidAutodismiss() {
        complete()
    }

    fun successfullySavedAsQuickname() {
        if (state !is OnboardingState.PresentingProfileSettings) return
        state = OnboardingState.SavedAsQuicknameSuccess
        handleStateChange()
    }

    private suspend fun addQuicknameDidAutoDismiss() {
        complete()
    }

    fun handleDisplayNameEndedEditing(
        profile: QuicknameProfile,
        didChangeProfile: Boolean,
        isSavingAsQuickname: Boolean
    ) {
        if (isSavingAsQuickname) {
            successfullySavedAsQuickname()
        } else if (didChangeProfile) {
            if (state is OnboardingState.PresentingProfileSettings) return
            val hasDefaultQuickname = QuicknameSettings.current().isDefault
            if (!hasSeenAddAsQuickname && hasDefaultQuickname) {
                state = OnboardingState.SaveAsQuickname(profile)
                handleStateChange()
                hasSeenAddAsQuickname = true
            }
        }
    }

    suspend fun complete() {
        hasCompletedOnboarding = true
        state = OnboardingState.Idle
        handleStateChange()
    }

    suspend fun skip() {
        complete()
    }

    private fun handleStateChange() {
        autodismissJob?.cancel()
        startAutodismissIfNeeded()
    }

    private fun startAutodismissIfNeeded() {
        val duration = state.autodismissDuration ?: return
        val expectedState = state

        autodismissJob = scope.launch {
            delay(duration)

            if (state == expectedState) {
                when (state) {
                    is OnboardingState.SetupQuickname -> setupQuicknameDidAutoDismiss()
                    is OnboardingState.AddQuickname -> addQuicknameDidAutoDismiss()
                    is OnboardingState.SaveAsQuickname -> saveAsQuicknameDidAutodismiss()
                    is OnboardingState.SavedAsQuicknameSuccess -> complete()
                    else -> {}
                }
            }
        }
    }
}
