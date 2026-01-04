package com.naomiplasterer.convos.ui.conversation

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.naomiplasterer.convos.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log

private const val TAG = "ConversationShareDialog"

@Composable
fun ConversationShareDialog(
    inviteCode: String?,
    conversationName: String?,
    conversationImageUrl: String?,
    onDismiss: () -> Unit,
    onGenerateInvite: () -> Unit
) {
    val context = LocalContext.current
    // Use the format that matches Android extraction: https://convos.app/i/{code}
    val inviteUrl = inviteCode?.let { "https://convos.app/i/$it" }
    var inviteError by remember { mutableStateOf<String?>(null) }
    var hasAppeared by remember { mutableStateOf(false) }

    // Generate invite when dialog opens
    LaunchedEffect(Unit) {
        Log.d(TAG, "Dialog opened, inviteCode is: ${if (inviteCode == null) "null" else "present"}")
        if (inviteCode == null) {
            Log.d(TAG, "Triggering invite generation")
            onGenerateInvite()
        }
        hasAppeared = true
    }

    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(inviteUrl) {
        inviteUrl?.let {
            Log.d(TAG, "Generating QR code for URL: $it")
            qrBitmap = generateQRCode(it)
            if (qrBitmap != null) {
                Log.d(TAG, "QR code generated successfully")
            } else {
                Log.e(TAG, "QR code generation returned null")
                inviteError = "Failed to generate QR code"
            }
        }
    }

    // Log state changes
    LaunchedEffect(inviteCode) {
        Log.d(TAG, "inviteCode changed: ${inviteCode?.take(20)}...")
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (hasAppeared) 0.7f else 0f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .width(340.dp)
                    .background(
                        Color.White,
                        RoundedCornerShape(24.dp)
                    )
                    .padding(Spacing.step8x),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.step5x)
            ) {
                Text(
                    text = "CONVOS CODE • SCAN TO JOIN",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = Spacing.step2x)
                )

                Box(
                    modifier = Modifier.size(220.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        qrBitmap != null -> {
                            // Show QR code
                            Image(
                                bitmap = qrBitmap!!.asImageBitmap(),
                                contentDescription = "QR Code for conversation",
                                modifier = Modifier.fillMaxSize()
                            )

                            // Optionally overlay conversation image in center of QR code
                            // (Commented out as it may interfere with QR code scanning)
                            /*
                            conversationImageUrl?.let { url ->
                                AsyncImage(
                                    model = url,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(50.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surface),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            */
                        }
                        inviteError != null -> {
                            // Show error
                            Text(
                                text = inviteError ?: "Error",
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                        }
                        else -> {
                            // Show loading spinner
                            CircularProgressIndicator()
                        }
                    }
                }

                Button(
                    onClick = {
                        inviteUrl?.let { url ->
                            val shareIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, url)
                                type = "text/plain"
                            }
                            context.startActivity(
                                Intent.createChooser(shareIntent, "Share conversation invite")
                            )
                        }
                    },
                    enabled = inviteUrl != null && inviteError == null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF6B35),
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        "Share Invite Link",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFF666666)
                    )
                ) {
                    Text(
                        "Close",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

private suspend fun generateQRCode(
    data: String,
    size: Int = 512
): Bitmap? = withContext(Dispatchers.Default) {
    try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, size, size)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (bitMatrix[x, y]) {
                    android.graphics.Color.BLACK
                } else {
                    android.graphics.Color.WHITE
                }
            }
        }

        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to generate QR code", e)
        null
    }
}
