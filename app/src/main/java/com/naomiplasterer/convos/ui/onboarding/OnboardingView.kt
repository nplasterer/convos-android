package com.naomiplasterer.convos.ui.onboarding

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.naomiplasterer.convos.ui.profile.ProfileEditorDialog
import com.naomiplasterer.convos.ui.theme.Spacing

@Composable
fun OnboardingView(
    coordinator: OnboardingCoordinator,
    onUseQuickname: (QuicknameProfile, Uri?) -> Unit,
    onPresentProfileSettings: () -> Unit
) {
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.step3x)
    ) {
        AnimatedVisibility(
            visible = coordinator.isWaitingForInviteAcceptance,
            enter = slideInVertically(
                initialOffsetY = { -it },
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            ) + fadeIn(),
            exit = slideOutVertically(
                targetOffsetY = { -it }
            ) + fadeOut()
        ) {
            InviteAcceptedView()
        }

        AnimatedContent(
            targetState = coordinator.state,
            transitionSpec = {
                slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(300)
                ) + fadeIn(animationSpec = tween(300)) togetherWith
                slideOutVertically(
                    targetOffsetY = { -it },
                    animationSpec = tween(300)
                ) + fadeOut(animationSpec = tween(300))
            },
            label = "onboarding_state"
        ) { state ->
            when (state) {
            is OnboardingState.Idle,
            is OnboardingState.Started,
            is OnboardingState.SettingUpQuickname,
            is OnboardingState.QuicknameLearnMore,
            is OnboardingState.PresentingProfileSettings -> {
                // Empty - these states don't show UI
            }

            is OnboardingState.SetupQuickname -> {
                SetupQuicknameView(
                    autoDismiss = state.autoDismiss,
                    onTapToSetup = { coordinator.didTapProfilePhoto() }
                )
            }

            is OnboardingState.SaveAsQuickname -> {
                UseAsQuicknameView(
                    profile = state.profile,
                    onLearnMore = { coordinator.presentWhatIsQuickname() }
                )
            }

            is OnboardingState.SavedAsQuicknameSuccess -> {
                SetupQuicknameSuccessView()
            }

            is OnboardingState.AddQuickname -> {
                AddQuicknameView(
                    profile = state.settings.profile,
                    profileImage = state.profileImageUri,
                    onUseProfile = { profile, image ->
                        onUseQuickname(profile, image)
                        scope.launch {
                            coordinator.didSelectQuickname()
                        }
                    }
                )
            }
            }
        }
    }

    if (coordinator.state is OnboardingState.QuicknameLearnMore) {
        WhatIsQuicknameDialog(
            onDismiss = { coordinator.onContinueFromWhatIsQuickname() },
            onContinue = {
                onPresentProfileSettings()
                coordinator.onContinueFromWhatIsQuickname()
            }
        )
    }

    // Show profile editor for SettingUpQuickname and PresentingProfileSettings states
    if (coordinator.state is OnboardingState.SettingUpQuickname ||
        coordinator.state is OnboardingState.PresentingProfileSettings) {
        val showSaveAsQuickname = coordinator.state is OnboardingState.PresentingProfileSettings
        ProfileEditorDialog(
            initialProfile = QuicknameProfile(),
            onSave = { profile, imageUri ->
                val isSavingAsQuickname = showSaveAsQuickname
                coordinator.handleDisplayNameEndedEditing(
                    profile = profile,
                    didChangeProfile = profile.displayName.isNotEmpty(),
                    isSavingAsQuickname = isSavingAsQuickname
                )
            },
            onDismiss = {
                scope.launch {
                    coordinator.setupQuicknameDidAutoDismiss()
                }
            },
            showSaveAsQuickname = showSaveAsQuickname
        )
    }
}

@Composable
fun InviteAcceptedView() {
    val infiniteTransition = rememberInfiniteTransition(label = "check_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "check_scale"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.step4x),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.step4x),
            horizontalArrangement = Arrangement.spacedBy(Spacing.step3x),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }

            Text(
                text = "Invite accepted!",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
fun SetupQuicknameView(
    autoDismiss: Boolean,
    onTapToSetup: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.step4x)
            .clickable { onTapToSetup() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.step6x),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.step4x)
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "?",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = "Tap to change your ID",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                textAlign = TextAlign.Center
            )

            Text(
                text = if (autoDismiss) {
                    "Choose a name and avatar for this conversation"
                } else {
                    "Set up your profile to get started"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun UseAsQuicknameView(
    profile: QuicknameProfile,
    onLearnMore: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.step4x),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.step6x),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.step4x)
        ) {
            Text(
                text = "Use as Quickname in new convos?",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                textAlign = TextAlign.Center
            )

            Text(
                text = profile.displayName.ifEmpty { "Your profile" },
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )

            TextButton(onClick = onLearnMore) {
                Text("Learn more about Quickname")
            }
        }
    }
}

@Composable
fun SetupQuicknameSuccessView() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.step4x),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.step4x),
            horizontalArrangement = Arrangement.spacedBy(Spacing.step3x),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(4.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )

            Text(
                text = "Quickname saved!",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
fun AddQuicknameView(
    profile: QuicknameProfile,
    profileImage: Uri?,
    onUseProfile: (QuicknameProfile, Uri?) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Spacing.step4x)
            .clickable { onUseProfile(profile, profileImage) },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.step4x),
            horizontalArrangement = Arrangement.spacedBy(Spacing.step4x),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (profileImage != null) {
                AsyncImage(
                    model = profileImage,
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = profile.displayName.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = "Tap to chat as",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = profile.displayName.ifEmpty { "Your profile" },
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
    }
}

@Composable
fun WhatIsQuicknameDialog(
    onDismiss: () -> Unit,
    onContinue: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("What is Quickname?")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.step3x)) {
                Text("Your Quickname is a reusable profile that you can use across multiple conversations.")
                Text("When you set a Quickname, you can quickly start new conversations without having to enter your name and avatar each time.")
                Text("Each conversation still has its own separate profile, but Quickname gives you a convenient starting point.")
            }
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text("Continue")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    )
}
