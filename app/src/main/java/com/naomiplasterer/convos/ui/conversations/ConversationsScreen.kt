package com.naomiplasterer.convos.ui.conversations

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.naomiplasterer.convos.domain.model.Conversation
import com.naomiplasterer.convos.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    viewModel: ConversationsViewModel = hiltViewModel(),
    onConversationClick: (String) -> Unit = {},
    onNewConversationClick: () -> Unit = {},
    onScanConversationClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val hasCreatedMoreThanOneConvo by viewModel.hasCreatedMoreThanOneConvo.collectAsState()

    // Don't auto-navigate - let the user see the empty state UI

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Convos") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
            if (uiState is ConversationsUiState.Success || uiState is ConversationsUiState.Empty) {
                BottomAppBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp
                ) {
                    Spacer(modifier = Modifier.weight(1f))

                    IconButton(onClick = onScanConversationClick) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Scan"
                        )
                    }

                    IconButton(onClick = onNewConversationClick) {
                        Icon(
                            imageVector = Icons.Default.Create,
                            contentDescription = "Compose"
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val state = uiState) {
                is ConversationsUiState.Loading -> {
                    LoadingState()
                }
                is ConversationsUiState.NoSession -> {
                    // Show the "pop a convo" empty state UI for no session too
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        ConversationsListEmptyCTA(
                            onStartConvo = onNewConversationClick,
                            onJoinConvo = onScanConversationClick
                        )
                    }
                }
                is ConversationsUiState.Empty -> {
                    // Show the "pop a convo" empty state UI
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        ConversationsListEmptyCTA(
                            onStartConvo = onNewConversationClick,
                            onJoinConvo = onScanConversationClick
                        )
                    }
                }
                is ConversationsUiState.Success -> {
                    val shouldShowCTA = state.conversations.size == 1 && !hasCreatedMoreThanOneConvo

                    ConversationsList(
                        conversations = state.conversations,
                        showCTA = shouldShowCTA,
                        onStartConvo = onNewConversationClick,
                        onJoinConvo = onScanConversationClick,
                        onConversationClick = {
                            viewModel.selectConversation(it.id)
                            onConversationClick(it.id)
                        },
                        onDeleteClick = { viewModel.deleteConversation(it.id) },
                        onRefresh = { viewModel.syncConversations() }
                    )
                }
                is ConversationsUiState.Syncing -> {
                    val shouldShowCTA = state.conversations.size == 1 && !hasCreatedMoreThanOneConvo

                    ConversationsList(
                        conversations = state.conversations,
                        showCTA = shouldShowCTA,
                        onStartConvo = onNewConversationClick,
                        onJoinConvo = onScanConversationClick,
                        onConversationClick = {
                            viewModel.selectConversation(it.id)
                            onConversationClick(it.id)
                        },
                        onDeleteClick = { viewModel.deleteConversation(it.id) },
                        onRefresh = { viewModel.syncConversations() },
                        isSyncing = true
                    )
                }
                is ConversationsUiState.Error -> {
                    ErrorState(
                        message = state.message,
                        onRetry = { viewModel.syncConversations() }
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationsList(
    conversations: List<Conversation>,
    onConversationClick: (Conversation) -> Unit,
    onDeleteClick: (Conversation) -> Unit,
    onRefresh: () -> Unit,
    showCTA: Boolean = false,
    onStartConvo: () -> Unit = {},
    onJoinConvo: () -> Unit = {},
    isSyncing: Boolean = false
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (isSyncing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = if (showCTA) 0.dp else Spacing.step2x)
        ) {
            if (showCTA) {
                item {
                    ConversationsListEmptyCTA(
                        onStartConvo = onStartConvo,
                        onJoinConvo = onJoinConvo
                    )
                }
            }

            items(
                items = conversations,
                key = { it.id }
            ) { conversation ->
                ConversationListItem(
                    conversation = conversation,
                    onClick = { onConversationClick(conversation) },
                    onDeleteClick = { onDeleteClick(conversation) }
                )
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun NoSessionState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.step4x)
        ) {
            Text(
                text = "No Active Session",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Please create or select an account to continue",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}


@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.step4x),
            modifier = Modifier.padding(Spacing.step6x)
        ) {
            Text(
                text = "Error",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}
