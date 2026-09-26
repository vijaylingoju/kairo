package ai.kairo.gallery.ui.gallery

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Four-point "AI" sparkle in the gradient colours. Gently pulses and turns when [animated]. */
@Composable
fun AiSparkle(modifier: Modifier = Modifier, size: Dp = 20.dp, animated: Boolean = true) {
    val colors = Kairo.colors.ai
    val t = rememberInfiniteTransition(label = "sparkle")
    val pulse by t.animateFloat(
        initialValue = 0.85f, targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse",
    )
    val turn by t.animateFloat(
        initialValue = 0f, targetValue = 90f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "turn",
    )
    Canvas(modifier.size(size)) {
        val s = if (animated) pulse else 1f
        val r = if (animated) turn else 0f
        rotate(r) {
            scale(s) {
                val w = this.size.width
                val h = this.size.height
                val main = star(Offset(w * 0.42f, h * 0.55f), w * 0.40f)
                drawPath(main, Brush.linearGradient(colors.take(3), Offset.Zero, Offset(w, h)))
                val small = star(Offset(w * 0.80f, h * 0.20f), w * 0.17f)
                drawPath(small, Brush.linearGradient(colors.drop(2).take(2), Offset.Zero, Offset(w, h)))
            }
        }
    }
}

/** Classic four-point sparkle ✦: long points on the axes, a narrow waist on the diagonals. */
private fun star(c: Offset, r: Float): Path = Path().apply {
    val inner = r * 0.26f
    for (i in 0 until 8) {
        val angle = Math.toRadians(i * 45.0 - 90.0)
        val radius = if (i % 2 == 0) r else inner
        val x = c.x + (kotlin.math.cos(angle) * radius).toFloat()
        val y = c.y + (kotlin.math.sin(angle) * radius).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/**
 * Rotating rainbow-gradient border: the "AI is here" glow around the search box.
 * [active] makes it brighter and faster (while Kairo is thinking).
 */
fun Modifier.aiGlow(shape: Shape, active: Boolean, width: Dp = 2.dp): Modifier = composed {
    val colors = Kairo.colors.ai
    val t = rememberInfiniteTransition(label = "glow")
    val angle by t.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (active) 1400 else 4200, easing = LinearEasing)), label = "angle",
    )
    val alpha = if (active) 1f else 0.55f
    drawWithContent {
        drawContent()
        // A gradient whose direction turns with [angle]: the colours travel around the border.
        val outline = shape.createOutline(size, layoutDirection, this)
        val rad = Math.toRadians(angle.toDouble())
        val dx = (kotlin.math.cos(rad) * size.width).toFloat()
        val dy = (kotlin.math.sin(rad) * size.height).toFloat()
        val brush = Brush.linearGradient(
            colors.map { it.copy(alpha = alpha) },
            start = Offset(center.x - dx, center.y - dy),
            end = Offset(center.x + dx, center.y + dy),
        )
        drawOutline(outline, brush, style = Stroke(width.toPx()))
    }
}

/** Moving highlight for placeholders while results load. */
fun Modifier.shimmer(base: Color, highlight: Color): Modifier = composed {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "x",
    )
    drawWithContent {
        val w = size.width
        drawRect(
            Brush.linearGradient(
                listOf(base, highlight, base),
                start = Offset(w * x - w * 0.5f, 0f),
                end = Offset(w * x + w * 0.5f, size.height),
            )
        )
    }
}

/** Sparkle + a line of text that cycles through what Kairo is doing. */
@Composable
fun AiThinking(messages: List<String>, modifier: Modifier = Modifier) {
    var i by remember { mutableIntStateOf(0) }
    LaunchedEffect(messages) {
        while (true) {
            delay(1100)
            i = (i + 1) % messages.size
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        AiSparkle(size = 22.dp)
        Spacer(Modifier.width(10.dp))
        AnimatedContent(
            targetState = messages[i % messages.size],
            transitionSpec = {
                (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
            },
            label = "thinking",
        ) { msg ->
            Text(msg, style = MaterialTheme.typography.bodyLarge, color = Kairo.colors.textSecondary)
        }
    }
}
