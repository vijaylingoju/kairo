package ai.kairo.gallery.assistant.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.kairo.gallery.assistant.AssistantSession
import ai.kairo.gallery.data.IndexedImage
import ai.kairo.gallery.search.SearchResult
import ai.kairo.gallery.ui.gallery.AiSparkle
import ai.kairo.gallery.ui.gallery.AiThinking
import ai.kairo.gallery.ui.gallery.Kairo
import ai.kairo.gallery.ui.gallery.MicIcon
import ai.kairo.gallery.ui.gallery.aiGlow

private val EXAMPLES = listOf(
    "My PAN number",
    "Movie tickets",
    "Dog photos",
    "Reduce brightness",
    "My eyes hurt at night",
    "Turn on Wi-Fi",
    "Is my phone up to date?",
    "How much storage is left?",
    "Set a dog photo as my wallpaper",
    "My phone is slow",
    "How much screen time today?",
    "My grandma can't read the screen",
)

private val THINKING = listOf("Understanding your request…", "Working on this phone…", "Almost there…")

/**
 * The Kairo tab: one chat for photos and settings ([AssistantSession] decides which one answers).
 * Photo answers show a few thumbnails here; "See all" opens the gallery's search screen with the same result.
 */
@Composable
fun AssistantChat(
    onOpenPhotos: (SearchResult) -> Unit,
    onOpenPhoto: (List<IndexedImage>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val session = remember { AssistantSession.get(context) }
    val messages by session.messages.collectAsState()
    val busy by session.busy.collectAsState()
    // The list is reversed (item 0 = newest, at the bottom), so it stays pinned to the latest message when the
    // keyboard opens or closes, and when the user comes back to the tab.
    val listState = rememberLazyListState()
    val c = Kairo.colors

    LaunchedEffect(Unit) {
        session.launchRequests.collect { context.launch(it) }
    }
    LaunchedEffect(messages.size, busy) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(session::send)
    }
    val startVoice = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Kairo")
        try {
            speech.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Voice input isn't available", Toast.LENGTH_SHORT).show()
        }
    }

    CompositionLocalProvider(LocalContentColor provides c.text) {
        Column(modifier.fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f)) {
                if (messages.isEmpty()) {
                    EmptyState(onExample = session::send)
                } else {
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (busy) item(key = "thinking") { AiThinking(THINKING, Modifier.padding(vertical = 4.dp)) }
                        items(messages.asReversed(), key = { it.id }) { message ->
                            if (message.fromUser) UserBubble(message.text)
                            else ReplyCard(message, session, onLaunch = { context.launch(it) }, onOpenPhotos, onOpenPhoto)
                        }
                    }
                }
            }
            InputBar(busy = busy, onSend = session::send, onMic = startVoice)
        }
    }
}

/** "Clear" for the Kairo tab's title row; hidden while the chat is empty or Kairo is answering. */
@Composable
fun ClearChatButton() {
    val context = LocalContext.current
    val session = remember { AssistantSession.get(context) }
    val messages by session.messages.collectAsState()
    val busy by session.busy.collectAsState()
    if (messages.isNotEmpty() && !busy) {
        Text(
            "Clear",
            style = MaterialTheme.typography.labelLarge,
            color = Kairo.colors.accent,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = session::clear).padding(8.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(onExample: (String) -> Unit) {
    val c = Kairo.colors
    // Centered when the examples fit; scrolls when they don't (small screens, big text).
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AiSparkle(size = 44.dp)
            Spacer(Modifier.height(14.dp))
            Text("Ask Kairo", style = MaterialTheme.typography.titleLarge, color = c.text)
            Text(
                "Find anything in your photos, or change a setting. Everything stays on this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EXAMPLES.forEach { s ->
                    val shape = RoundedCornerShape(18.dp)
                    Text(
                        s, style = MaterialTheme.typography.labelLarge, color = c.text,
                        modifier = Modifier.clip(shape).background(c.surface).border(1.dp, c.divider, shape)
                            .clickable { onExample(s) }.padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text,
            color = Color.White,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                .background(Kairo.colors.accent)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** Same pill as the gallery's search box; it glows while Kairo is answering. */
@Composable
private fun InputBar(busy: Boolean, onSend: (String) -> Unit, onMic: () -> Unit) {
    val c = Kairo.colors
    var text by rememberSaveable { mutableStateOf("") }
    fun send() {
        if (text.isBlank() || busy) return
        onSend(text)
        text = ""
    }
    val shape = RoundedCornerShape(26.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(c.surface)
            .aiGlow(shape, active = busy, width = if (busy) 2.5.dp else 1.5.dp)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AiSparkle(size = 20.dp, animated = busy)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
            if (text.isEmpty()) Text("Ask Kairo…", color = c.textSecondary, style = MaterialTheme.typography.bodyLarge)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                maxLines = 4,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (text.isBlank()) {
            IconButton(onClick = onMic, enabled = !busy) { MicIcon(c.accent, size = 22.dp) }
        } else {
            IconButton(onClick = { send() }, enabled = !busy) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = c.accent)
            }
        }
    }
}
