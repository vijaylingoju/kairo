package ai.kairo.gallery.assistant.ball

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ai.kairo.gallery.assistant.AssistantReply
import ai.kairo.gallery.assistant.AssistantSession
import ai.kairo.gallery.assistant.ui.InputBar
import ai.kairo.gallery.assistant.ui.ReplyCard
import ai.kairo.gallery.assistant.ui.UserBubble
import ai.kairo.gallery.assistant.ui.launch
import ai.kairo.gallery.llm.Llm
import ai.kairo.gallery.settings.SettingsResponse
import ai.kairo.gallery.ui.OpenRequest
import ai.kairo.gallery.ui.gallery.AiSparkle
import ai.kairo.gallery.ui.gallery.AiThinking
import ai.kairo.gallery.ui.gallery.Kairo
import ai.kairo.gallery.ui.gallery.star
import kotlinx.coroutines.delay

private val THINKING = listOf("Understanding your request…", "Working on this phone…", "Almost there…")
private val WAKING = listOf("Waking up Kairo…", "Loading the AI on this phone…")

/** How long "Brightness lowered · Undo" stays before the dialog gets out of the way. */
private const val DONE_CLOSE_MS = 3_500L

/**
 * The ball itself: the Kairo sparkle on a dark disc with the AI-gradient ring.
 * Deliberately static (not [AiSparkle]): it's on screen all day, and an animation would redraw every frame.
 */
@Composable
internal fun BallFace() {
    val c = Kairo.colors
    Box(Modifier.fillMaxSize().padding(5.dp).shadow(6.dp, CircleShape).clip(CircleShape).background(Color(0xE6181A1F))) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val ring = 2.dp.toPx()
            drawCircle(Brush.sweepGradient(c.ai, center), radius = w / 2 - ring / 2, style = Stroke(ring))
            drawPath(star(Offset(w * 0.46f, w * 0.54f), w * 0.27f), Brush.linearGradient(c.ai.take(3), Offset.Zero, Offset(w, w)))
            drawPath(star(Offset(w * 0.69f, w * 0.31f), w * 0.11f), Brush.linearGradient(c.ai.drop(2).take(2), Offset.Zero, Offset(w, w)))
        }
    }
}

/**
 * What the ball opens, over whatever app is on screen: a box to ask Kairo, and the answer.
 * - Changed directly ("Brightness lowered · Undo"): shown briefly, then the dialog closes by itself.
 * - Something the user does next (Take me there, Grant, Apply, pick a wallpaper, photos or settings?): the same
 *   card as in the chat.
 * - A system panel (Wi-Fi, internet): opened right away.
 * - Something to read (phone info, storage, the checkup, screen time): continues in the Kairo tab.
 * - Photos: the answer and a few thumbnails here; "See all" or a photo opens the gallery.
 */
@Composable
internal fun BallDialog(onClose: () -> Unit, onOpen: (OpenRequest) -> Unit) {
    val context = LocalContext.current
    val session = remember { AssistantSession.get(context) }
    val messages by session.messages.collectAsState()
    val busy by session.busy.collectAsState()
    val model by Llm.state.collectAsState()
    val c = Kairo.colors

    // Only this visit's exchange, from the latest question on; earlier ones are in the Kairo tab. If the dialog
    // was closed while Kairo was still answering, that question shows again with its answer.
    val startId = remember {
        val list = session.messages.value
        if (session.busy.value) list.lastOrNull { it.fromUser }?.id ?: 0L else (list.lastOrNull()?.id ?: -1L) + 1
    }
    val visit = messages.filter { it.id >= startId }
    val shown = visit.drop(visit.indexOfLast { it.fromUser }.coerceAtLeast(0))

    LaunchedEffect(Unit) {
        session.launchRequests.collect {
            context.launch(it)
            onClose()
        }
    }
    val last = shown.lastOrNull()
    LaunchedEffect(last?.id) {
        val response = (last?.reply as? AssistantReply.Settings)?.response ?: return@LaunchedEffect
        when {
            response is SettingsResponse.Facts || (response is SettingsResponse.Suggestions && response.rows.isNotEmpty()) ->
                onOpen(OpenRequest.KairoTab)
            // Changed straight from the question (not after an Apply or Undo here): done, get out of the way.
            response is SettingsResponse.Done && shown.size == 2 -> {
                delay(DONE_CLOSE_MS)
                onClose()
            }
        }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(scroll) {
        // Keep the newest part of the answer in view as it grows (thumbnails load, a reply arrives).
        snapshotFlow { scroll.maxValue }.collect { scroll.animateScrollTo(it) }
    }

    Box(
        // A tap outside the card closes it, like a dialog.
        Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
    ) {
        CompositionLocalProvider(LocalContentColor provides c.text) {
            val shape = RoundedCornerShape(28.dp)
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp)
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .shadow(12.dp, shape)
                    .clip(shape)
                    .background(c.background)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}  // taps inside stay inside
                    .padding(top = 8.dp, bottom = 4.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    AiSparkle(size = 18.dp, animated = busy)
                    Spacer(Modifier.width(8.dp))
                    Text("Kairo", style = MaterialTheme.typography.titleMedium, color = c.text, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onOpen(OpenRequest.KairoTab) }) { Text("Open chat") }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = c.textSecondary)
                    }
                }
                if (shown.isNotEmpty() || busy) {
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(scroll).padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        shown.forEach { message ->
                            if (message.fromUser) {
                                UserBubble(message.text)
                            } else {
                                ReplyCard(
                                    message, session,
                                    onLaunch = { spec ->
                                        context.launch(spec)
                                        onClose()
                                    },
                                    onOpenPhotos = { onOpen(OpenRequest.Photos(it)) },
                                    onOpenPhoto = { list, i -> onOpen(OpenRequest.Photo(list, i)) },
                                )
                            }
                        }
                        if (busy) AiThinking(if (model.startsWith("Loading")) WAKING else THINKING, Modifier.padding(vertical = 4.dp))
                    }
                }
                InputBar(busy = busy, onSend = session::send, onMic = null, autoFocus = true)
            }
        }
    }
}
