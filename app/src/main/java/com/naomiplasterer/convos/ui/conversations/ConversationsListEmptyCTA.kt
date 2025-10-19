package com.naomiplasterer.convos.ui.conversations

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naomiplasterer.convos.ui.theme.CornerRadius
import com.naomiplasterer.convos.ui.theme.Spacing

@Composable
fun ConversationsListEmptyCTA(
    onStartConvo: () -> Unit,
    onJoinConvo: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.step6x),
        verticalArrangement = Arrangement.spacedBy(Spacing.step4x)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(CornerRadius.mediumLarger)
                )
                .padding(40.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.step4x)
        ) {
            Text(
                text = "Pop-up private convos",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = "Chat instantly, with anybody.\nNo accounts. New you every time.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.step2x),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onStartConvo,
                    shape = RoundedCornerShape(CornerRadius.large),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Start a convo")
                }

                TextButton(onClick = onJoinConvo) {
                    Text(
                        text = "or join one",
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.step4x)
        ) {
            Row(
                modifier = Modifier.clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://xmtp.org"))
                    context.startActivity(intent)
                },
                horizontalArrangement = Arrangement.spacedBy(Spacing.stepX),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Secured by @XMTP",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            Row(
                modifier = Modifier.clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://convos.org/terms-and-privacy"))
                    context.startActivity(intent)
                },
                horizontalArrangement = Arrangement.spacedBy(Spacing.stepX),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Terms & Privacy Policy",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}
