package com.naomiplasterer.convos.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.naomiplasterer.convos.ui.theme.Spacing

@Composable
fun ConversationInfoEditDialog(
    viewModel: ConversationViewModel,
    onDismiss: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val editingName by viewModel.editingConversationName.collectAsState()
    val isSavingInfo by viewModel.isSavingInfo.collectAsState()
    val saveInfoError by viewModel.saveInfoError.collectAsState()
    val isEditingInfo by viewModel.isEditingInfo.collectAsState()

    LaunchedEffect(isEditingInfo) {
        if (!isEditingInfo) {
            onDismiss()
        }
    }

    if (uiState !is ConversationUiState.Success) {
        return
    }

    val conversation = (uiState as ConversationUiState.Success).conversation

    AlertDialog(
        onDismissRequest = {
            if (!isSavingInfo) {
                viewModel.cancelEditingInfo()
            }
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Edit Conversation",
                    style = MaterialTheme.typography.titleLarge
                )
                if (!isSavingInfo) {
                    IconButton(
                        onClick = { viewModel.cancelEditingInfo() },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close"
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.step4x)
            ) {
                ConversationImageDisplay(
                    imageUrl = conversation.imageUrl,
                    conversationName = editingName.ifEmpty { conversation.name },
                    modifier = Modifier.size(120.dp)
                )

                OutlinedTextField(
                    value = editingName,
                    onValueChange = viewModel::updateEditingName,
                    label = { Text("Conversation name") },
                    placeholder = { Text("Untitled") },
                    singleLine = true,
                    enabled = !isSavingInfo,
                    modifier = Modifier.fillMaxWidth()
                )

                if (saveInfoError != null) {
                    Text(
                        text = saveInfoError ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.saveConversationInfo() },
                enabled = !isSavingInfo
            ) {
                if (isSavingInfo) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                }
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = { viewModel.cancelEditingInfo() },
                enabled = !isSavingInfo
            ) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ConversationImageDisplay(
    imageUrl: String?,
    conversationName: String?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = if (imageUrl != null) {
            Color.Transparent
        } else {
            MaterialTheme.colorScheme.primaryContainer
        }
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = "Conversation image",
                modifier = Modifier.clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = (conversationName?.take(1) ?: "U").uppercase(),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}
