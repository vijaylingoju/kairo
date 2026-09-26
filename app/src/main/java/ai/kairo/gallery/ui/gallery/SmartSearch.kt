package ai.kairo.gallery.ui.gallery

import ai.kairo.gallery.data.IndexedImage
import ai.kairo.gallery.search.SearchResult
import ai.kairo.gallery.ui.MainViewModel
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import ai.kairo.gallery.voice.VoiceState
import ai.kairo.gallery.voice.VoiceVocabulary
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun SmartSearchScreen(
    vm: MainViewModel,
    startWithVoice: Boolean,
    onBack: () -> Unit,
    onOpenPhoto: (List<IndexedImage>, Int) -> Unit,
) {
    val c = Kairo.colors
    val ctx = LocalContext.current
    val result by vm.result.collectAsState()
    val searching by vm.searching.collectAsState()
    val recent by vm.recent.collectAsState()
    val photos by vm.allImages.collectAsState()
    val vocab = remember(photos) { VoiceVocabulary.fromGallery(photos) }
    val model by vm.modelState.collectAsState()
    var query by rememberSaveable { mutableStateOf(result?.query ?: "") }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }

    fun run(q: String) {
        if (q.isBlank()) return
        query = q
        keyboard?.hide()
        vm.search(q)
    }

    // --- Voice search: speech -> text -> search ---
    val voice = rememberVoiceRecognizer()
    val voiceState by voice.state.collectAsState()
    val voiceOnDevice = voice.onDevice
    var voiceOpen by rememberSaveable { mutableStateOf(false) }
    fun listen() {
        keyboard?.hide()
        voiceOpen = true
        voice.start(vocab.phrases)
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else Toast.makeText(ctx, "Allow the microphone to search by voice", Toast.LENGTH_SHORT).show()
    }
    fun startVoice() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun closeVoice() {
        voice.cancel()
        voiceOpen = false
    }
    // Final words -> fill the box and search, then close the sheet.
    LaunchedEffect(voiceState) {
        val done = voiceState as? VoiceState.Done ?: return@LaunchedEffect
        delay(350)  // let the user see what was heard
        voiceOpen = false
        voice.reset()
        // Fix sound-alike mistakes with the gallery's own words ("hero modi" -> "irumudi").
        val fixed = vocab.correct(done.text)
        if (fixed != done.text) Log.i("KairoVoice", "Corrected: \"${done.text}\" -> \"$fixed\"")
        run(fixed)
    }
    LaunchedEffect(Unit) {
        // Opened from the home mic: start listening straight away.
        if (startWithVoice && result == null) startVoice()
        if (!startWithVoice && result == null) focus.requestFocus()
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding().imePadding()) {
        // Search field with the AI glow (brighter while Kairo is thinking).
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.clearSearch(); onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.text)
            }
            val shape = RoundedCornerShape(26.dp)
            Row(
                Modifier.weight(1f).height(52.dp).clip(shape).background(c.surface)
                    .aiGlow(shape, active = searching, width = if (searching) 2.5.dp else 1.5.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AiSparkle(size = 20.dp, animated = searching || query.isEmpty())
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Ask about your photos…", color = c.textSecondary, style = MaterialTheme.typography.bodyLarge)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { run(query) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = ""; vm.clearSearch(); focus.requestFocus() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear", tint = c.textSecondary, modifier = Modifier.size(18.dp))
                    }
                }
                IconButton(onClick = ::startVoice, modifier = Modifier.size(36.dp)) {
                    MicIcon(c.accent, size = 22.dp)
                }
            }
        }

        val state = when {
            searching -> "thinking"
            result != null -> "results"
            else -> "idle"
        }
        AnimatedContent(targetState = state, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "search") { s ->
            when (s) {
                "thinking" -> Thinking()
                "results" -> result?.let { Results(it, onOpenPhoto) }
                else -> Suggestions(recent, modelReady = "ready" in model, onPick = ::run, onClearRecent = vm::clearRecent)
            }
        }
    }

    if (voiceOpen) {
        BackHandler { closeVoice() }
        VoiceSheet(
            state = voiceState,
            onDevice = voiceOnDevice,
            onOrbTap = voice::stop,
            onRetry = { voice.start(vocab.phrases) },
            onClose = ::closeVoice,
        )
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Suggestions(recent: List<String>, modelReady: Boolean, onPick: (String) -> Unit, onClearRecent: () -> Unit) {
    val c = Kairo.colors
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (recent.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)) {
                Text("Recent", style = MaterialTheme.typography.titleMedium, color = c.text, modifier = Modifier.weight(1f))
                Text("Clear", style = MaterialTheme.typography.labelLarge, color = c.accent, modifier = Modifier.clickable(onClick = onClearRecent).padding(8.dp))
            }
            recent.forEach { r ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(r) }.padding(vertical = 10.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(r, style = MaterialTheme.typography.bodyLarge, color = c.text)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp)) {
            AiSparkle(size = 16.dp, animated = false)
            Spacer(Modifier.width(6.dp))
            Text("Try asking", style = MaterialTheme.typography.titleMedium, color = c.text)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SEARCH_SUGGESTIONS.forEach { s ->
                Text(
                    s, style = MaterialTheme.typography.labelLarge, color = c.text,
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(c.surface)
                        .border(1.dp, c.divider, RoundedCornerShape(18.dp))
                        .clickable { onPick(s) }.padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (modelReady) "Search runs entirely on this phone" else "AI warming up · basic search works now",
                style = MaterialTheme.typography.labelMedium, color = c.textSecondary,
            )
        }
    }
}

@Composable
private fun Thinking() {
    val c = Kairo.colors
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        AiThinking(
            listOf("Understanding your question…", "Looking through your photos…", "Matching on this phone…", "Almost there…"),
            modifier = Modifier.padding(vertical = 14.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            userScrollEnabled = false,
        ) {
            items(9) {
                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).shimmer(c.surface, c.surfaceHigh))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Results(r: SearchResult, onOpenPhoto: (List<IndexedImage>, Int) -> Unit) {
    val c = Kairo.colors
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        r.answer?.let { answer ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                AnswerCard(r, answer) { img -> onOpenPhoto(listOf(img), 0) }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Understood(r) }
        if (r.items.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { NoResults(r.query) }
        } else {
            items(r.items, key = { it.mediaId }) { p ->
                Box(Modifier.clip(RoundedCornerShape(8.dp))) {
                    Thumb(p, corner = 8.dp) { onOpenPhoto(r.items, r.items.indexOf(p)) }
                }
            }
        }
    }
}

/** Big answer with Copy. ID numbers stay masked until tapped. Also shown in the Kairo chat. */
@Composable
internal fun AnswerCard(r: SearchResult, answer: String, onOpenSource: (IndexedImage) -> Unit) {
    val c = Kairo.colors
    val ctx = LocalContext.current
    val label = answer.substringBefore(": ")
    val value = answer.substringAfter(": ").substringBefore(" • ")
    val extra = answer.substringAfter(" • ", "").takeIf { " • " in answer }
    val isId = r.filter.wantedField == "id_number"
    var revealed by remember(answer) { mutableStateOf(!isId) }
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp).clip(shape)
            .background(Brush.linearGradient(c.ai.take(3).map { it.copy(alpha = 0.10f) }))
            .border(1.5.dp, Brush.linearGradient(c.ai.take(3)), shape)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AiSparkle(size = 16.dp, animated = false)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = c.textSecondary)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (revealed) value else maskId(value),
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 26.sp), color = c.text,
                modifier = Modifier.weight(1f).clickable(enabled = isId) { revealed = !revealed },
            )
            Text(
                "Copy", style = MaterialTheme.typography.labelLarge, color = c.accent, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(c.background)
                    .clickable {
                        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Kairo", value))
                        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                    }.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        extra?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary) }
        if (isId) {
            Text(if (revealed) "Tap to hide" else "Tap the number to reveal", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
        }
        r.answerImage?.let { img ->
            Spacer(Modifier.height(8.dp))
            Text(
                "From your ${prettyCategory(img.category).lowercase()} · ${SimpleDateFormat("d MMM", Locale.getDefault()).format(img.dateTaken)}  ›",
                style = MaterialTheme.typography.labelMedium, color = c.accent,
                modifier = Modifier.clickable { onOpenSource(img) },
            )
        }
    }
}

/** "Understood as" chips: shows how Kairo read the question (and makes the AI step visible). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Understood(r: SearchResult) {
    val c = Kairo.colors
    val f = r.filter
    val chips = buildList {
        f.categories.forEach { add(prettyCategory(it)) }
        f.wantedField?.let { add(prettyField(it, f.categories.firstOrNull() ?: "")) }
        if (f.dateFromMs != null || f.dateToMs != null) {
            val fmt = SimpleDateFormat("d MMM", Locale.getDefault())
            add("${f.dateFromMs?.let(fmt::format) ?: "…"} – ${f.dateToMs?.let(fmt::format) ?: "now"}")
        }
        // Gemma's visual phrase says it best; raw keywords only when there is none.
        if (f.visual != null) add("looks like: ${f.visual}") else f.keywords.take(3).forEach { add("“$it”") }
    }
    Column(Modifier.padding(bottom = 10.dp)) {
        if (chips.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 2.dp, top = 5.dp)) {
                    AiSparkle(size = 14.dp, animated = false)
                    Spacer(Modifier.width(4.dp))
                    Text("Understood", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                }
                chips.forEach { chip ->
                    Text(
                        chip, style = MaterialTheme.typography.labelMedium, color = c.text,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.surface).padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        val secs = if (r.tookMs < 1000) "${r.tookMs} ms" else "%.1f s".format(r.tookMs / 1000f)
        Text(
            "${r.items.size} ${if (r.items.size == 1) "photo" else "photos"} · $secs · on this phone",
            style = MaterialTheme.typography.labelMedium, color = c.textSecondary,
        )
    }
}

@Composable
private fun NoResults(query: String) {
    val c = Kairo.colors
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AiSparkle(size = 44.dp, animated = false)
        Spacer(Modifier.height(14.dp))
        Text("No photos match “$query”", style = MaterialTheme.typography.titleMedium, color = c.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text("Try other words, or describe what's in the photo.", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, textAlign = TextAlign.Center)
    }
}
