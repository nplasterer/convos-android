package com.naomiplasterer.convos.ui.conversation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Dialog that contains the Hold to Explode button and confirmation.
 * Only shown to the group creator.
 */
@Composable
fun ExplodeDialog(
    explodeState: ExplodeState,
    onExplode: () -> Unit,
    onDismiss: () -> Unit,
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Dialog(
        onDismissRequest = {
            // Only allow dismissal if not currently exploding
            if (explodeState != ExplodeState.EXPLODING) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = explodeState != ExplodeState.EXPLODING,
            dismissOnClickOutside = explodeState != ExplodeState.EXPLODING
        )
    ) {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Title
                Text(
                    text = "Explode Conversation",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Warning text
                Text(
                    text = "This action cannot be undone!",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Description
                Text(
                    text = "All messages and members will be permanently deleted after 30 seconds. There will be no record that this conversation ever existed.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(bottom = 24.dp)
                )

                // Explode button - constrained width to prevent expansion
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    ExplodeButton(
                        state = explodeState,
                        onExplode = onExplode,
                        modifier = Modifier.widthIn(max = 280.dp),
                        holdDuration = 2000L // Longer hold duration for safety
                    )
                }

                // Info button
                TextButton(
                    onClick = onInfoClick,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(
                        text = "Learn more about exploding conversations",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Cancel button (only show if not exploding)
                if (explodeState != ExplodeState.EXPLODING && explodeState != ExplodeState.EXPLODED) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(
                            text = "Cancel",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Success message for exploded state
                if (explodeState == ExplodeState.EXPLODED) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFF6B35).copy(alpha = 0.1f)
                        )
                    ) {
                        Text(
                            text = "💥 Conversation will explode in 30 seconds!",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFFFF6B35),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}