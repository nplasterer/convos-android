package com.naomiplasterer.convos.ui.newconversation

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.naomiplasterer.convos.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewConversationScreen(
    viewModel: NewConversationViewModel = hiltViewModel(),
    onBackClick: () -> Unit = {},
    onConversationJoined: (String) -> Unit = {},
    initialMode: NewConversationMode = NewConversationMode.SCAN
) {
    val uiState by viewModel.uiState.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val inviteCode by viewModel.inviteCode.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.setMode(initialMode)
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

            if (uiState is NewConversationUiState.Error) {
                ErrorSnackbar(
                    message = (uiState as NewConversationUiState.Error).message,
                    onDismiss = { viewModel.resetState() },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(Spacing.step4x)
                )
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
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.step6x),
            modifier = Modifier.padding(Spacing.step6x)
        ) {
            Icon(
                imageVector = Icons.Filled.Add, // Placeholder - would use QR scanner icon
                contentDescription = null,
                modifier = Modifier.size(120.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "Scan QR Code",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Position the QR code within the frame to scan",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Camera permission required",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
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
private fun ErrorSnackbar(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.step4x),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}
