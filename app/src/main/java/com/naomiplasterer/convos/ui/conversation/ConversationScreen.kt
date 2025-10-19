package com.naomiplasterer.convos.ui.conversation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.naomiplasterer.convos.domain.model.Message
import com.naomiplasterer.convos.domain.model.MessageContent
import com.naomiplasterer.convos.ui.theme.CornerRadius
import com.naomiplasterer.convos.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    viewModel: ConversationViewModel = hiltViewModel(),
    onBackClick: () -> Unit = {},
    onInfoClick: () -> Unit = {},
    onEditClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val messageText by viewModel.messageText.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.syncMessages()
        viewModel.markAsRead()
    }

    Scaffold(
        topBar = {
            when (val state = uiState) {
                is ConversationUiState.Success -> {
                    TopAppBar(
                        title = {
                            Text(
                                text = state.conversation.name ?: "Unnamed Conversation",
                                style = MaterialTheme.typography.titleMedium
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onBackClick) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Edit Conversation") },
                                        onClick = {
                                            showMenu = false
                                            onEditClick()
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Edit, contentDescription = null)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Profile") },
                                        onClick = {
                                            showMenu = false
                                            onInfoClick()
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Info, contentDescription = null)
                                        }
                                    )
                                }
                            }
                        }
                    )
                }
                else -> {
                    TopAppBar(
                        title = { Text("Conversation") },
                        navigationIcon = {
                            IconButton(onClick = onBackClick) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    )
                }
            }
        }
    ) { paddingValues ->
        when (val state = uiState) {
            is ConversationUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            is ConversationUiState.Success -> {
                ConversationContent(
                    messages = state.messages,
                    currentInboxId = state.conversation.inboxId,
                    messageText = messageText,
                    onMessageTextChange = viewModel::updateMessageText,
                    onSendClick = viewModel::sendMessage,
                    isSending = isSending,
                    onAddReaction = viewModel::addReaction,
                    modifier = Modifier.padding(paddingValues)
                )
            }
            is ConversationUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationContent(
    messages: List<Message>,
    currentInboxId: String,
    messageText: String,
    onMessageTextChange: (String) -> Unit,
    onSendClick: () -> Unit,
    isSending: Boolean,
    modifier: Modifier = Modifier,
    onAddReaction: (String, String) -> Unit = { _, _ -> }
) {
    Column(
        modifier = modifier.fillMaxSize()
    ) {
        val listState = rememberLazyListState()

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(Spacing.step4x)
        ) {
            items(
                items = messages.reversed(),
                key = { it.id }
            ) { message ->
                MessageBubble(
                    message = message,
                    isOwn = message.senderInboxId == currentInboxId,
                    onAddReaction = { emoji -> onAddReaction(message.id, emoji) }
                )
                Spacer(modifier = Modifier.height(Spacing.step2x))
            }
        }

        MessageInput(
            text = messageText,
            onTextChange = onMessageTextChange,
            onSendClick = onSendClick,
            enabled = !isSending
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    isOwn: Boolean,
    modifier: Modifier = Modifier,
    onAddReaction: (String) -> Unit = {}
) {
    val text = when (val content = message.content) {
        is MessageContent.Text -> content.text
        is MessageContent.Emoji -> content.emoji
        is MessageContent.Attachment -> "📎 ${content.url}"
        is MessageContent.Update -> content.details
    }

    var showReactionPicker by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start
    ) {
        Column(
            horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start
        ) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = CornerRadius.medium,
                    topEnd = CornerRadius.medium,
                    bottomStart = if (isOwn) CornerRadius.medium else 4.dp,
                    bottomEnd = if (isOwn) 4.dp else CornerRadius.medium
                ),
                color = if (isOwn) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showReactionPicker = true }
                    )
            ) {
            Column(
                modifier = Modifier.padding(Spacing.step3x)
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isOwn) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Spacer(modifier = Modifier.height(Spacing.stepX))
                Text(
                    text = formatMessageTime(message.sentAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOwn) {
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

            if (showReactionPicker) {
                Card(
                    modifier = Modifier.padding(top = Spacing.step2x),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(Spacing.step2x),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.step2x)
                    ) {
                        val commonEmojis = listOf("👍", "❤️", "😂", "😮", "😢", "🎉")
                        commonEmojis.forEach { emoji ->
                            TextButton(
                                onClick = {
                                    onAddReaction(emoji)
                                    showReactionPicker = false
                                }
                            ) {
                                Text(
                                    text = emoji,
                                    style = MaterialTheme.typography.headlineSmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSendClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.step2x),
            horizontalArrangement = Arrangement.spacedBy(Spacing.step2x),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                enabled = enabled,
                maxLines = 4,
                shape = RoundedCornerShape(CornerRadius.medium)
            )

            IconButton(
                onClick = onSendClick,
                enabled = enabled && text.isNotBlank(),
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (enabled && text.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    }
                )
            }
        }
    }
}

private fun formatMessageTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
