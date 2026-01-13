package com.naomiplasterer.convos.ui.conversation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naomiplasterer.convos.R
import com.naomiplasterer.convos.ui.theme.ConvosTheme

/**
 * Bottom sheet that explains the exploding conversations feature to users.
 * Shown when users first interact with the explode feature or tap an info icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplodeInfoBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier
    ) {
        ExplodeInfoContent(
            onGotIt = onDismiss
        )
    }
}

@Composable
private fun ExplodeInfoContent(
    onGotIt: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Icon
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_explode),
            contentDescription = null,
            tint = Color(0xFFFF6B35),
            modifier = Modifier
                .size(64.dp)
                .padding(bottom = 16.dp)
        )

        // Tagline
        Text(
            text = "Real life is off the record.™",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Title
        Text(
            text = "Exploding Convos",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Description
        Text(
            text = "Messages and Members are destroyed forever, and there's no record that the convo ever happened.",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // Features list
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            FeatureRow(
                title = "Complete Deletion",
                description = "All messages are permanently deleted"
            )
            FeatureRow(
                title = "No Trace",
                description = "Conversation disappears from all devices"
            )
            FeatureRow(
                title = "Automatic",
                description = "Happens after the timer expires"
            )
        }

        // Got it button
        Button(
            onClick = onGotIt,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            ),
            shape = MaterialTheme.shapes.large
        ) {
            Text(
                text = "Got it",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun FeatureRow(
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // Bullet point
        Text(
            text = "•",
            fontSize = 20.sp,
            color = Color(0xFFFF6B35),
            modifier = Modifier.padding(end = 12.dp)
        )

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ExplodeInfoContentPreview() {
    ConvosTheme {
        Surface {
            ExplodeInfoContent(
                onGotIt = {}
            )
        }
    }
}