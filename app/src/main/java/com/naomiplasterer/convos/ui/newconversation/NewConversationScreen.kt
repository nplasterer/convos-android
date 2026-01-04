package com.naomiplasterer.convos.ui.newconversation

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.naomiplasterer.convos.ui.onboarding.OnboardingView
import com.naomiplasterer.convos.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewConversationScreen(
    viewModel: NewConversationViewModel = hiltViewModel(),
    onBackClick: () -> Unit = {},
    onConversationJoined: (String) -> Unit = {},
    initialMode: NewConversationMode = NewConversationMode.SCAN,
    initialInviteCode: String? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val inviteCode by viewModel.inviteCode.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.setMode(initialMode)
        initialInviteCode?.let {
            viewModel.updateInviteCode(it)
            viewModel.joinConversation(it)
        }
    }

    LaunchedEffect(uiState) {
        if (uiState is NewConversationUiState.Success) {
            val conversationId = (uiState as NewConversationUiState.Success).conversationId
            onConversationJoined(conversationId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (mode) {
                            NewConversationMode.CREATE -> "New Conversation"
                            NewConversationMode.SCAN -> "Join Conversation"
                            NewConversationMode.MANUAL -> "Join Conversation"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (mode != NewConversationMode.CREATE) {
                        TextButton(
                            onClick = {
                                viewModel.setMode(
                                    if (mode == NewConversationMode.SCAN) {
                                        NewConversationMode.MANUAL
                                    } else {
                                        NewConversationMode.SCAN
                                    }
                                )
                            }
                        ) {
                            Text(if (mode == NewConversationMode.SCAN) "Manual" else "Scan")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (mode) {
                NewConversationMode.CREATE -> {
                    CreateConversationView(
                        isCreating = uiState is NewConversationUiState.Creating,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                NewConversationMode.SCAN -> {
                    QRScannerView(
                        onQRCodeScanned = viewModel::onQRCodeScanned,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                NewConversationMode.MANUAL -> {
                    ManualEntryView(
                        inviteCode = inviteCode,
                        onInviteCodeChange = viewModel::updateInviteCode,
                        onJoinClick = { viewModel.joinConversation() },
                        isLoading = uiState is NewConversationUiState.Joining,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Show onboarding UI if needed
            AnimatedVisibility(
                visible = viewModel.onboardingCoordinator.showOnboardingView,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeIn(),
                exit = slideOutVertically(
                    targetOffsetY = { it }
                ) + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    OnboardingView(
                        coordinator = viewModel.onboardingCoordinator,
                        onUseQuickname = { profile, imageUri ->
                            // TODO: Apply quickname to conversation
                        },
                        onPresentProfileSettings = {
                            // TODO: Navigate to profile settings
                        }
                    )
                }
            }

            // Show loading/waiting overlay for QR scanner mode
            when (val state = uiState) {
                is NewConversationUiState.Joining -> {
                    LoadingOverlay(
                        message = "Joining conversation...",
                        modifier = Modifier.fillMaxSize()
                    )
                }
                is NewConversationUiState.WaitingForApproval -> {
                    WaitingForApprovalOverlay(
                        message = state.message,
                        conversationName = state.conversationName,
                        conversationImageURL = state.conversationImageURL,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> {}
            }

            AnimatedVisibility(
                visible = uiState is NewConversationUiState.Error,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(Spacing.step4x),
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ) + fadeIn(),
                exit = slideOutVertically(
                    targetOffsetY = { it }
                ) + fadeOut()
            ) {
                if (uiState is NewConversationUiState.Error) {
                    val errorState = uiState as NewConversationUiState.Error
                    ErrorSnackbar(
                        title = errorState.error.title,
                        message = errorState.error.description,
                        onDismiss = { viewModel.resetState() }
                    )
                }
            }
        }
    }
}

@Composable
private fun CreateConversationView(
    isCreating: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        if (isCreating) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.step6x),
                modifier = Modifier.padding(Spacing.step6x)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "Creating conversation...",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun QRScannerView(
    onQRCodeScanned: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    Box(modifier = modifier) {
        QRScanner(
            onQRCodeScanned = onQRCodeScanned,
            modifier = Modifier.fillMaxSize()
        )

        // Paste button in bottom-right corner
        FloatingActionButton(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipData = clipboard.primaryClip
                val text = clipData?.getItemAt(0)?.text?.toString()
                if (text != null) {
                    onQRCodeScanned(text)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Spacing.step6x),
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Icon(
                Icons.Default.ContentPaste,
                contentDescription = "Paste from clipboard"
            )
        }
    }
}

@Composable
private fun ManualEntryView(
    inviteCode: String,
    onInviteCodeChange: (String) -> Unit,
    onJoinClick: () -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(Spacing.step6x),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.step6x)
    ) {
        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "Enter Invite Code",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )

        Text(
            text = "Enter the conversation invite code to join",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        OutlinedTextField(
            value = inviteCode,
            onValueChange = onInviteCodeChange,
            label = { Text("Invite Code") },
            placeholder = { Text("abc123...") },
            singleLine = true,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = onJoinClick,
            enabled = !isLoading && inviteCode.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("Join Conversation")
            }
        }

        Spacer(modifier = Modifier.weight(2f))
    }
}

@Composable
private fun LoadingOverlay(
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .then(Modifier.padding(0.dp)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(Spacing.step6x)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(Spacing.step6x))

                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun WaitingForApprovalOverlay(
    message: String,
    conversationName: String?,
    conversationImageURL: String?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(Spacing.step8x)
            ) {
                // Show conversation image if available
                if (conversationImageURL != null) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        coil.compose.AsyncImage(
                            model = conversationImageURL,
                            contentDescription = "Conversation image",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.step6x))
                }

                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(Spacing.step6x))

                if (conversationName != null) {
                    Text(
                        text = conversationName,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                        ),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(Spacing.step3x))
                }

                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(Spacing.step4x))

                Text(
                    text = "This convo will appear on your home screen after approval",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Spacing.step6x)
                )
            }
        }
    }
}

@Composable
private fun ErrorSnackbar(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.step4x),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(Spacing.stepX))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}
