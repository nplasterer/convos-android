package com.naomiplasterer.convos.ui.conversation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil.compose.AsyncImage
import com.naomiplasterer.convos.R
import com.naomiplasterer.convos.domain.model.Conversation
import com.naomiplasterer.convos.domain.model.Message
import com.naomiplasterer.convos.domain.model.MessageContent
import com.naomiplasterer.convos.domain.model.MessageStatus
import com.naomiplasterer.convos.ui.theme.ConvosTheme
import com.naomiplasterer.convos.ui.theme.CornerRadius
import com.naomiplasterer.convos.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    viewModel: ConversationViewModel = hiltViewModel(),
    onBackClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val messageText by viewModel.messageText.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val explodeState by viewModel.explodeState.collectAsState()
    val showExplodeInfo by viewModel.showExplodeInfo.collectAsState()
    val remainingTime by viewModel.remainingTime.collectAsState()

    var showMenu by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showEditInfoDialog by remember { mutableStateOf(false) }
    var showMyInfoDialog by remember { mutableStateOf(false) }
    var showExplodeDialog by remember { mutableStateOf(false) }

    // Helper to check if conversation should show delete prompt (only 1 member)
    val shouldPromptDelete = remember(uiState) {
        when (val state = uiState) {
            is ConversationUiState.Success -> {
                val memberCount = state.conversation.members.size
                val shouldPrompt = state.conversation.members.isNotEmpty() && memberCount == 1
                android.util.Log.d("ConversationScreen", "Delete check: members.size=$memberCount, isEmpty=${state.conversation.members.isEmpty()}, shouldPrompt=$shouldPrompt")
                // Only show dialog if members have been loaded AND there's exactly 1 member (creator alone)
                shouldPrompt
            }
            else -> false
        }
    }

    // Handle back press with delete prompt for single-member conversations
    val handleBackPress = {
        if (shouldPromptDelete) {
            showDeleteDialog = true
        } else {
            onBackClick()
        }
    }

    // Intercept system back button
    BackHandler {
        handleBackPress()
    }

    // TODO: Implement image attachment functionality
    // val imagePickerLauncher = rememberLauncherForActivityResult(
    //     contract = ActivityResultContracts.GetContent()
    // ) { uri: Uri? ->
    //     uri?.let { viewModel.sendImageAttachment(it) }
    // }

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
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(Spacing.step2x),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ConversationImageAvatar(
                                    imageUrl = state.conversation.imageUrl,
                                    conversationName = state.conversation.name
                                )
                                Text(
                                    text = state.conversation.name ?: "Untitled",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = handleBackPress) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back"
                                )
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
                                        text = { Text("Edit Convo") },
                                        onClick = {
                                            showMenu = false
                                            viewModel.startEditingInfo()
                                            showEditInfoDialog = true
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Edit, contentDescription = null)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("My Info") },
                                        onClick = {
                                            showMenu = false
                                            showMyInfoDialog = true
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Person, contentDescription = null)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Share Invite") },
                                        onClick = {
                                            showMenu = false
                                            showShareDialog = true
                                        },
                                        leadingIcon = {
                                            Icon(Icons.Default.Share, contentDescription = null)
                                        }
                                    )
                                    // Show explode option only for group creator
                                    if (state.conversation.creatorInboxId == state.conversation.inboxId &&
                                        state.conversation.expiresAt == null) {
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    "Explode Conversation",
                                                    color = Color(0xFFFF6B35)
                                                )
                                            },
                                            onClick = {
                                                showMenu = false
                                                showExplodeDialog = true
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = ImageVector.vectorResource(R.drawable.ic_explode),
                                                    contentDescription = null,
                                                    tint = Color(0xFFFF6B35)
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    )
                }

                else -> {
                    TopAppBar(
                        title = { Text("Conversation") },
                        navigationIcon = {
                            IconButton(onClick = handleBackPress) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back"
                                )
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
                    conversation = state.conversation,
                    messages = state.messages,
                    currentInboxId = state.conversation.inboxId,
                    messageText = messageText,
                    onMessageTextChange = viewModel::updateMessageText,
                    onSendClick = viewModel::sendMessage,
                    isSending = isSending,
                    remainingTime = remainingTime,
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

    if (showShareDialog) {
        when (uiState) {
            is ConversationUiState.Success -> {
                val inviteCode by viewModel.inviteCode.collectAsState()
                ConversationShareDialog(
                    inviteCode = inviteCode,
                    onDismiss = { showShareDialog = false },
                    onGenerateInvite = { viewModel.generateInviteCode() }
                )
            }

            else -> {}
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Conversation?") },
            text = {
                Text("You're the only member of this conversation. Would you like to delete it or keep it?")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteConversation()
                        showDeleteDialog = false
                        onBackClick()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onBackClick()
                }) {
                    Text("Keep")
                }
            }
        )
    }

    if (showEditInfoDialog) {
        ConversationInfoEditDialog(
            viewModel = viewModel,
            onDismiss = { showEditInfoDialog = false }
        )
    }

    if (showMyInfoDialog) {
        when (val state = uiState) {
            is ConversationUiState.Success -> {
                MyInfoDialog(
                    conversationId = state.conversation.id,
                    inboxId = state.conversation.inboxId,
                    onDismiss = { showMyInfoDialog = false }
                )
            }
            else -> {}
        }
    }

    if (showExplodeInfo) {
        ExplodeInfoBottomSheet(
            onDismiss = { viewModel.hideExplodeInfoSheet() }
        )
    }

    if (showExplodeDialog) {
        ExplodeDialog(
            explodeState = explodeState,
            onExplode = {
                viewModel.explodeConversation(
                    onSuccess = {
                        showExplodeDialog = false
                        onBackClick()
                    }
                )
            },
            onDismiss = {
                showExplodeDialog = false
            },
            onInfoClick = {
                showExplodeDialog = false
                viewModel.showExplodeInfoSheet()
            }
        )

        // Auto-close dialog after successful explosion
        LaunchedEffect(explodeState) {
            if (explodeState == ExplodeState.EXPLODED) {
                kotlinx.coroutines.delay(2000) // Show success message for 2 seconds
                showExplodeDialog = false
            }
        }
    }
}

@Composable
private fun ConversationContent(
    conversation: Conversation,
    messages: List<Message>,
    currentInboxId: String,
    messageText: String,
    onMessageTextChange: (String) -> Unit,
    onSendClick: () -> Unit,
    isSending: Boolean,
    remainingTime: String?,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding() // Push content up when keyboard appears
    ) {
        val listState = rememberLazyListState()

        // Auto-scroll to newest message when list grows
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                // Scroll to index 0 (newest message at bottom due to reverseLayout)
                listState.animateScrollToItem(index = 0)
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            // Dismiss keyboard when tapping on message list
                            focusManager.clearFocus()
                        }
                    )
                },
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = Spacing.step4x, vertical = Spacing.step2x)
        ) {
            // Messages are already sorted DESC (newest first) from DB
            // reverseLayout=true displays them bottom-up (newest at bottom)
            // So we don't need to reverse them again
            itemsIndexed(
                items = messages,
                key = { _, message -> message.id }
            ) { index, message ->
                val previousMessage = messages.getOrNull(index - 1)
                val nextMessage = messages.getOrNull(index + 1)

                // With reverseLayout=true:
                // - index 0 = newest (bottom of screen)
                // - previousMessage (index-1) = visually BELOW (newer)
                // - nextMessage (index+1) = visually ABOVE (older)

                // isFirstInGroup = visually at BOTTOM of group (newest in group) - show avatar & timestamp
                val isFirstInGroup = previousMessage == null ||
                        previousMessage.senderInboxId != message.senderInboxId ||
                        (message.sentAt - previousMessage.sentAt) > 5 * 60 * 1000 // 5 minutes

                // isLastInGroup = visually at TOP of group (oldest in group) - show name
                val isLastInGroup = nextMessage == null ||
                        nextMessage.senderInboxId != message.senderInboxId ||
                        (nextMessage.sentAt - message.sentAt) > 5 * 60 * 1000 // 5 minutes

                val sender = conversation.members.find { it.inboxId == message.senderInboxId }
                val senderName = sender?.displayName ?: "Somebody"
                val senderAvatarUrl = sender?.imageUrl

                MessageBubble(
                    message = message,
                    isOwn = message.senderInboxId == currentInboxId,
                    showAvatar = isFirstInGroup && message.senderInboxId != currentInboxId,
                    showSenderName = isLastInGroup && message.senderInboxId != currentInboxId,
                    showTimestamp = isFirstInGroup,
                    senderName = senderName,
                    senderAvatarUrl = senderAvatarUrl
                )

                // Android-native spacing: tight within group, larger between groups
                val spacerHeight = if (isLastInGroup) 12.dp else 2.dp
                Spacer(modifier = Modifier.height(spacerHeight))
            }
        }

        // Show countdown timer if conversation is set to explode
        if (conversation.expiresAt != null && conversation.expiresAt > System.currentTimeMillis()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "This conversation will explode in:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = remainingTime ?: "Soon",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
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

@Composable
private fun MessageBubble(
    message: Message,
    isOwn: Boolean,
    modifier: Modifier = Modifier,
    showAvatar: Boolean = false,
    showSenderName: Boolean = false,
    showTimestamp: Boolean = true,
    senderName: String? = null,
    senderAvatarUrl: String? = null
) {
    // Handle system update messages separately
    if (message.content is MessageContent.Update) {
        SystemUpdateMessage(
            message = message,
            modifier = modifier
        )
        return
    }

    val text = when (val content = message.content) {
        is MessageContent.Text -> content.text
        is MessageContent.Emoji -> content.emoji
        is MessageContent.Attachment -> "📎 ${content.url}"
        is MessageContent.Update -> content.details  // This case is handled above, but kept for completeness
    }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // Sender name above message group (only for first message in group)
        if (!isOwn && showSenderName && senderName != null) {
            Text(
                text = senderName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(
                    start = 32.dp + Spacing.step2x + Spacing.step2x, // Account for avatar width + padding
                    bottom = Spacing.stepHalf
                )
            )
        }

        Column(
            horizontalAlignment = if (isOwn) Alignment.End else Alignment.Start,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Row for avatar + message bubble (not including timestamp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (isOwn) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar shown beside last message in group (centered with message bubble only)
                if (!isOwn && showAvatar) {
                    MessageAvatar(
                        avatarUrl = senderAvatarUrl,
                        name = senderName ?: "U",
                        modifier = Modifier.padding(end = Spacing.step2x)
                    )
                } else if (!isOwn) {
                    // Spacer to maintain alignment for subsequent messages
                    Spacer(modifier = Modifier.width(32.dp + Spacing.step2x))
                }

                // Message bubble with Android-native rounded corners
                Surface(
                    shape = RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 20.dp,
                        bottomStart = if (isOwn) 20.dp else 4.dp,
                        bottomEnd = if (isOwn) 4.dp else 20.dp
                    ),
                    color = if (isOwn) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isOwn) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(
                            horizontal = 12.dp,
                            vertical = 8.dp
                        )
                    )
                }
            }

            // Timestamp and status below bubble (Android pattern) - only on last message in group
            if (showTimestamp) {
                Row(
                    modifier = Modifier.padding(
                        start = if (isOwn) 0.dp else (32.dp + Spacing.step2x + Spacing.step2x), // Account for avatar + spacing
                        end = if (isOwn) Spacing.step2x else 0.dp,
                        top = Spacing.stepHalf
                    ),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.stepX),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatMessageTime(message.sentAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )

                    // Status indicator for own messages
                    if (isOwn) {
                        when (message.status) {
                            MessageStatus.SENDING -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(10.dp),
                                    strokeWidth = 1.dp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }

                            MessageStatus.SENT, MessageStatus.DELIVERED -> {
                                Text(
                                    text = "✓",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }

                            MessageStatus.FAILED -> {
                                Text(
                                    text = "!",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
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
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.step4x, vertical = Spacing.step2x),
            horizontalArrangement = Arrangement.spacedBy(Spacing.step2x),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                enabled = true, // Always keep TextField enabled so keyboard doesn't dismiss
                maxLines = 4,
                shape = RoundedCornerShape(CornerRadius.medium),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.None
                )
            )

            IconButton(
                onClick = onSendClick,
                enabled = enabled && text.isNotBlank(), // Only disable send button, not TextField
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

@Composable
private fun SystemUpdateMessage(
    message: Message,
    modifier: Modifier = Modifier
) {
    val content = message.content as MessageContent.Update

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.step2x),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = Spacing.step4x)
        ) {
            Text(
                text = content.details,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(
                    horizontal = Spacing.step3x,
                    vertical = Spacing.step2x
                )
            )
        }
    }
}

@Composable
private fun MessageAvatar(
    avatarUrl: String?,
    name: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.size(32.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        // Show image if URL is valid, otherwise show initials
        if (!avatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = "Avatar for $name",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun ConversationImageAvatar(
    imageUrl: String?,
    conversationName: String?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.size(32.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        // Show image if URL is valid, otherwise show initials
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = "Conversation image",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = (conversationName?.take(1) ?: "U").uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

// ============================================================================
// Compose Previews
// ============================================================================

@Preview(name = "Message Bubble - Own", showBackground = true)
@Composable
private fun PreviewMessageBubbleOwn() {
    ConvosTheme {
        Surface {
            MessageBubble(
                message = Message(
                    id = "1",
                    conversationId = "conv1",
                    senderInboxId = "inbox1",
                    content = MessageContent.Text("Hey! How are you doing today? 👋"),
                    status = MessageStatus.SENT,
                    sentAt = System.currentTimeMillis(),
                    deliveredAt = null
                ),
                isOwn = true,
                showAvatar = false,
                showSenderName = false,
                modifier = Modifier.padding(Spacing.step4x)
            )
        }
    }
}

@Preview(name = "Message Bubble - Other", showBackground = true)
@Composable
private fun PreviewMessageBubbleOther() {
    ConvosTheme {
        Surface {
            MessageBubble(
                message = Message(
                    id = "2",
                    conversationId = "conv1",
                    senderInboxId = "inbox2",
                    content = MessageContent.Text("I'm doing great! Thanks for asking 😊"),
                    status = MessageStatus.SENT,
                    sentAt = System.currentTimeMillis(),
                    deliveredAt = null
                ),
                isOwn = false,
                showAvatar = true,
                showSenderName = true,
                senderName = "Alice",
                senderAvatarUrl = null,
                modifier = Modifier.padding(Spacing.step4x)
            )
        }
    }
}


@Preview(name = "Message Input", showBackground = true)
@Composable
private fun PreviewMessageInput() {
    ConvosTheme {
        MessageInput(
            text = "Type a message...",
            onTextChange = {},
            onSendClick = {},
            enabled = true
        )
    }
}

@Preview(name = "Conversation Content", showBackground = true, heightDp = 600)
@Composable
private fun PreviewConversationContent() {
    ConvosTheme {
        ConversationContent(
            conversation = Conversation(
                id = "conv1",
                inboxId = "inbox1",
                clientId = "client1",
                topic = "topic1",
                creatorInboxId = "inbox1",
                inviteTag = null,
                consent = com.naomiplasterer.convos.domain.model.ConsentState.ALLOWED,
                kind = com.naomiplasterer.convos.domain.model.ConversationKind.GROUP,
                name = "Test Group",
                description = null,
                imageUrl = null,
                createdAt = System.currentTimeMillis(),
                lastMessageAt = null,
                expiresAt = null,
                members = emptyList()
            ),
            messages = listOf(
                Message(
                    id = "1",
                    conversationId = "conv1",
                    senderInboxId = "inbox1",
                    content = MessageContent.Text("Hey there!"),
                    status = MessageStatus.SENT,
                    sentAt = System.currentTimeMillis() - 60000,
                    deliveredAt = null,
                ),
                Message(
                    id = "2",
                    conversationId = "conv1",
                    senderInboxId = "inbox2",
                    content = MessageContent.Text("Hi! How can I help you?"),
                    status = MessageStatus.SENT,
                    sentAt = System.currentTimeMillis() - 30000,
                    deliveredAt = null,
                ),
                Message(
                    id = "3",
                    conversationId = "conv1",
                    senderInboxId = "inbox1",
                    content = MessageContent.Text("Just testing the new message bubbles! They look great 🎉"),
                    status = MessageStatus.SENT,
                    sentAt = System.currentTimeMillis(),
                    deliveredAt = null
                )
            ),
            currentInboxId = "inbox1",
            messageText = "",
            onMessageTextChange = {},
            onSendClick = {},
            isSending = false,
            remainingTime = null
        )
    }
}
