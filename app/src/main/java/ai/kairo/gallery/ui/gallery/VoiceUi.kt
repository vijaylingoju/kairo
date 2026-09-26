package ai.kairo.gallery.ui.gallery

import ai.kairo.gallery.voice.VoiceRecognizer
import ai.kairo.gallery.voice.VoiceState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One recognizer per screen; released when the screen goes away. */
@Composable
fun rememberVoiceRecognizer(): VoiceRecognizer {
    val ctx = LocalContext.current
    val voice = remember { VoiceRecognizer(ctx.applicationContext) }
    DisposableEffect(voice) { onDispose { voice.release() } }
    return voice
}

/** Hand-drawn microphone (material-icons-core has no mic). */
@Composable
fun MicIcon(tint: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size).semantics { contentDescription = "Voice search" }) {
        val s = this.size.width
        val w = s * 0.09f
        // Capsule
        drawRoundRect(
            tint, topLeft = Offset(s * 0.36f, s * 0.08f), size = Size(s * 0.28f, s * 0.50f),
            cornerRadius = CornerRadius(s * 0.14f),
        )
        // Cradle
        drawArc(
            tint, startAngle = 0f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(s * 0.22f, s * 0.24f), size = Size(s * 0.56f, s * 0.48f),
            style = Stroke(w, cap = StrokeCap.Round),
        )
        // Stem + base
        drawLine(tint, Offset(s * 0.5f, s * 0.72f), Offset(s * 0.5f, s * 0.88f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(tint, Offset(s * 0.34f, s * 0.90f), Offset(s * 0.66f, s * 0.90f), strokeWidth = w, cap = StrokeCap.Round)
    }
}

/**
 * Listening sheet (English): a gradient orb that breathes with the voice level, live transcript and an
 * honest privacy line. Tap the orb to finish early; the result is searched automatically.
 */
@Composable
fun VoiceSheet(
    state: VoiceState,
    onDevice: Boolean,
    onOrbTap: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val c = Kairo.colors
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(c.background)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}  // keep taps inside
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Voice search", style = MaterialTheme.typography.titleMedium, color = c.text, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = c.textSecondary) }
            }

            Spacer(Modifier.height(18.dp))
            val listening = state as? VoiceState.Listening
            VoiceOrb(level = listening?.level ?: 0f, active = listening != null, onTap = if (listening != null) onOrbTap else onRetry)
            Spacer(Modifier.height(18.dp))

            // Transcript / status
            AnimatedContent(
                targetState = state,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                contentKey = { it::class },
                label = "voice",
            ) { s ->
                Column(Modifier.fillMaxWidth().heightIn(min = 76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (s) {
                        is VoiceState.Listening -> {
                            if (s.partial.isNotBlank()) {
                                Text(s.partial, style = MaterialTheme.typography.titleLarge, color = c.text, textAlign = TextAlign.Center)
                            } else {
                                Text(if (s.speaking) "Listening…" else "Speak now", style = MaterialTheme.typography.titleLarge, color = c.text)
                                Spacer(Modifier.height(4.dp))
                                Text("Try “movie tickets” or “what's my PNR”", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
                            }
                        }
                        is VoiceState.Done -> Text(s.text, style = MaterialTheme.typography.titleLarge, color = c.text, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        is VoiceState.Error -> {
                            Text(s.message, style = MaterialTheme.typography.bodyLarge, color = c.text, textAlign = TextAlign.Center)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Tap to try again", style = MaterialTheme.typography.labelLarge, color = c.accent,
                                modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onRetry).padding(8.dp),
                            )
                        }
                        VoiceState.Idle -> Text("Tap the mic to speak", style = MaterialTheme.typography.titleLarge, color = c.textSecondary)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (onDevice) "Speech is recognised on this phone" else "Speech handled by Google speech services (offline when available)",
                    style = MaterialTheme.typography.labelMedium, color = c.textSecondary, textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

/** Gradient orb with a mic; rings expand with the voice level, colours slowly rotate. */
@Composable
private fun VoiceOrb(level: Float, active: Boolean, onTap: () -> Unit) {
    val c = Kairo.colors
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(5000, easing = LinearEasing)), label = "spin")
    val breathe by t.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "breathe")
    val voice by animateFloatAsState(if (active) level else 0f, spring(stiffness = 300f), label = "level")

    Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
        // Voice rings
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val base = r * 0.52f
            for (i in 0..2) {
                val grow = base + r * (0.12f + 0.16f * i) * (0.35f + voice)
                drawCircle(
                    Brush.sweepGradient(c.ai),
                    radius = grow.coerceAtMost(r),
                    alpha = if (active) (0.22f - i * 0.06f) * (0.5f + voice) else 0.08f,
                )
            }
        }
        // Core orb
        Box(
            Modifier.size(96.dp).scale(if (active) breathe + voice * 0.12f else 1f).clip(CircleShape)
                .clickable(onClick = onTap),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                rotate(spin) { drawCircle(Brush.sweepGradient(c.ai)) }
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent)))
            }
            MicIcon(Color.White, size = 36.dp)
        }
    }
}
