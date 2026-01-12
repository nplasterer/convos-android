package com.naomiplasterer.convos.ui.conversation

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.naomiplasterer.convos.R
import com.naomiplasterer.convos.ui.theme.ConvosTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * State for the explode button
 */
enum class ExplodeState {
    READY,      // Button is ready to be pressed
    EXPLODING,  // Button is being held down
    EXPLODED,   // Explosion has been triggered
    ERROR       // An error occurred
}

/**
 * A button that requires holding to confirm an action (exploding the conversation).
 * Inspired by the iOS implementation but adapted for Jetpack Compose.
 */
@Composable
fun ExplodeButton(
    state: ExplodeState,
    onExplode: () -> Unit,
    modifier: Modifier = Modifier,
    holdDuration: Long = 1500L,
    readyText: String = "Hold to Explode",
    explodingText: String = "Exploding...",
    errorText: String = "Something went wrong"
) {
    var isPressed by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val hapticFeedback = LocalHapticFeedback.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Animation values
    val buttonScale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.95f
            state == ExplodeState.EXPLODED -> 1.1f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "buttonScale"
    )

    val iconRotation by animateFloatAsState(
        targetValue = if (state == ExplodeState.EXPLODING) 360f else 0f,
        animationSpec = if (state == ExplodeState.EXPLODING) {
            tween(durationMillis = holdDuration.toInt(), easing = LinearEasing)
        } else {
            tween(durationMillis = 300)
        },
        label = "iconRotation"
    )

    val iconScale by animateFloatAsState(
        targetValue = when (state) {
            ExplodeState.EXPLODING -> 1.1f
            ExplodeState.EXPLODED -> 2.5f
            else -> 1f
        },
        animationSpec = spring(),
        label = "iconScale"
    )

    val iconBlur by animateFloatAsState(
        targetValue = if (state == ExplodeState.EXPLODED) 12f else 0f,
        animationSpec = tween(600),
        label = "iconBlur"
    )

    val iconAlpha by animateFloatAsState(
        targetValue = if (state == ExplodeState.EXPLODED) 0f else 1f,
        animationSpec = tween(600),
        label = "iconAlpha"
    )

    val backgroundColor by animateColorAsState(
        targetValue = when (state) {
            ExplodeState.ERROR -> MaterialTheme.colorScheme.error
            else -> Color(0xFFFF6B35) // Orange color
        },
        animationSpec = tween(300),
        label = "backgroundColor"
    )

    // Progress animation when holding
    LaunchedEffect(isPressed) {
        if (isPressed && state == ExplodeState.READY) {
            progress = 0f
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(holdDuration.toInt(), easing = LinearEasing)
            ) { value, _ ->
                if (isPressed) {
                    progress = value
                    if (value >= 1f) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        performStrongVibration(context)
                        onExplode()
                    }
                }
            }
        } else {
            // Animate back to 0 when released early
            animate(
                initialValue = progress,
                targetValue = 0f,
                animationSpec = tween(180, easing = FastOutSlowInEasing)
            ) { value, _ ->
                progress = value
            }
        }
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = backgroundColor,
        modifier = modifier
            .scale(buttonScale)
            .pointerInput(state) {
                detectTapGestures(
                    onPress = {
                        if (state == ExplodeState.READY) {
                            isPressed = true
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)

                            // Wait for release or completion
                            tryAwaitRelease()

                            // Released before completion
                            if (progress < 1f) {
                                isPressed = false
                            }
                        }
                    }
                )
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
        ) {
            // Progress indicator background - fills from left to right behind content
            if (progress > 0f && state != ExplodeState.EXPLODED) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .matchParentSize()
                        .background(Color.Black.copy(alpha = 0.15f))
                )
            }

            // Content with padding
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp, horizontal = 20.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                    // Progress circle indicator (left side)
                    if (progress > 0f && state != ExplodeState.EXPLODED) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .padding(end = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                progress = progress,
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                                trackColor = Color.White.copy(alpha = 0.3f)
                            )
                            Box(
                                modifier = Modifier
                                    .size((16 * progress).dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Explode icon
                    if (state != ExplodeState.ERROR) {
                        Icon(
                            imageVector = ImageVector.vectorResource(R.drawable.ic_explode),
                            contentDescription = "Explode",
                            tint = Color.White,
                            modifier = Modifier
                                .size(24.dp)
                                .rotate(iconRotation)
                                .scale(iconScale)
                                .blur(iconBlur.dp)
                                .alpha(iconAlpha)
                        )

                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    // Text
                    val displayText = when (state) {
                        ExplodeState.READY -> readyText
                        ExplodeState.EXPLODING, ExplodeState.EXPLODED -> explodingText
                        ExplodeState.ERROR -> errorText
                    }

                    if (state == ExplodeState.EXPLODED) {
                        ShatteringText(
                            text = displayText,
                            isExploded = true
                        )
                    } else {
                        Text(
                            text = displayText,
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp
                        )
                    }
            }
        }
    }
}

/**
 * Text that shatters into pieces when exploded
 */
@Composable
private fun ShatteringText(
    text: String,
    isExploded: Boolean
) {
    Box {
        text.forEachIndexed { index, char ->
            val offsetX by animateFloatAsState(
                targetValue = if (isExploded) {
                    (index - text.length / 2f) * 15f + Random.nextInt(0, 11).toFloat()
                } else 0f,
                animationSpec = spring(
                    dampingRatio = 0.3f,
                    stiffness = Spring.StiffnessLow
                ),
                label = "offsetX_$index"
            )

            val offsetY by animateFloatAsState(
                targetValue = if (isExploded) {
                    Random.nextInt(-20, 21).toFloat()
                } else 0f,
                animationSpec = spring(
                    dampingRatio = 0.3f,
                    stiffness = Spring.StiffnessLow
                ),
                label = "offsetY_$index"
            )

            val rotation by animateFloatAsState(
                targetValue = if (isExploded) {
                    Random.nextInt(-45, 46).toFloat()
                } else 0f,
                animationSpec = spring(
                    dampingRatio = 0.3f,
                    stiffness = Spring.StiffnessLow
                ),
                label = "rotation_$index"
            )

            val scale by animateFloatAsState(
                targetValue = if (isExploded) {
                    2f + Random.nextFloat() * 2f  // Random between 2f and 4f
                } else 1f,
                animationSpec = spring(
                    dampingRatio = 0.3f,
                    stiffness = Spring.StiffnessLow
                ),
                label = "scale_$index"
            )

            val alpha by animateFloatAsState(
                targetValue = if (isExploded) 0f else 1f,
                animationSpec = tween(
                    durationMillis = 600,
                    delayMillis = index * 30
                ),
                label = "alpha_$index"
            )

            Text(
                text = char.toString(),
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                modifier = Modifier
                    .offset(x = offsetX.dp, y = offsetY.dp)
                    .rotate(rotation)
                    .scale(scale)
                    .alpha(alpha)
                    .blur(if (isExploded) 5.dp else 0.dp)
            )
        }
    }
}

/**
 * Perform a strong vibration for the explode action
 */
private fun performStrongVibration(context: Context) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(
            VibrationEffect.createOneShot(
                100,
                VibrationEffect.DEFAULT_AMPLITUDE
            )
        )
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(100)
    }
}

@Preview(showBackground = true)
@Composable
private fun ExplodeButtonPreview() {
    ConvosTheme {
        var state by remember { mutableStateOf(ExplodeState.READY) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            ExplodeButton(
                state = state,
                onExplode = {
                    state = ExplodeState.EXPLODING
                    // Simulate explosion
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Debug buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(onClick = { state = ExplodeState.READY }) {
                    Text("Ready")
                }
                Button(onClick = { state = ExplodeState.EXPLODING }) {
                    Text("Exploding")
                }
                Button(onClick = { state = ExplodeState.EXPLODED }) {
                    Text("Exploded")
                }
                Button(onClick = { state = ExplodeState.ERROR }) {
                    Text("Error")
                }
            }
        }
    }
}