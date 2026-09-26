package ai.kairo.gallery.settings.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.kairo.gallery.settings.IntentSpec
import ai.kairo.gallery.settings.SettingsResponse

private val EXAMPLES = listOf(
    "My eyes hurt at night",
    "Make the text bigger",
    "Reduce brightness",
    "Turn on the flashlight",
    "Volume up",
    "Do not disturb on",
    "Turn on Wi-Fi",
    "How do I turn on dark mode?",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsChatScreen(vm: SettingsChatViewModel = viewModel()) {
    val context = LocalContext.current
    val messages by vm.messages.collectAsState()
    val busy by vm.busy.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        vm.launchRequests.collect { context.launch(it) }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let(vm::send)
    }
    val startVoice = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "What should I change?")
        try {
            speech.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "Voice input isn't available", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings Assistant") },
                navigationIcon = {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.padding(start = 16.dp))
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            Box(Modifier.weight(1f)) {
                if (messages.isEmpty()) {
                    EmptyState(onExample = vm::send)
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(messages, key = { it.id }) { message ->
                            if (message.fromUser) UserBubble(message.text)
                            else AssistantMessage(message, vm, onLaunch = { context.launch(it) })
                        }
                        if (busy) item { CircularProgressIndicator(Modifier.size(24.dp)) }
                    }
                }
            }
            InputBar(enabled = !busy, onSend = vm::send, onMic = startVoice)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyState(onExample: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("What would you like to change?", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Ask for a setting, or just describe a problem. Everything stays on your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            EXAMPLES.forEach { AssistChip(onClick = { onExample(it) }, label = { Text(it) }) }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(
                text,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun AssistantMessage(
    message: ChatMessage,
    vm: SettingsChatViewModel,
    onLaunch: (IntentSpec) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
        modifier = Modifier.widthIn(max = 340.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val r = message.response) {
                is SettingsResponse.Done -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(r.text, modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false))
                        r.undo?.let { token -> TextButton(onClick = { vm.undo(token) }) { Text("Undo") } }
                    }
                }

                is SettingsResponse.Suggestions -> {
                    Text(r.text)
                    r.items.forEach { s ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.title, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        s.reason,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                FilledTonalButton(
                                    onClick = { vm.apply(s.settingId, s.action) },
                                    modifier = Modifier.padding(start = 8.dp),
                                ) { Text(s.buttonLabel) }
                            }
                        }
                    }
                }

                is SettingsResponse.Guide -> {
                    Text(r.text)
                    r.steps.forEachIndexed { i, step ->
                        Text("${i + 1}. $step", style = MaterialTheme.typography.bodyMedium)
                    }
                    r.open?.let { spec -> Button(onClick = { onLaunch(spec) }) { Text("Take me there") } }
                }

                is SettingsResponse.OpenPanel -> {
                    Text(r.text)
                    OutlinedButton(onClick = { onLaunch(r.open) }) { Text("Open again") }
                }

                is SettingsResponse.NeedsPermission -> {
                    Text(r.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onLaunch(r.grant) }) { Text("Grant") }
                        OutlinedButton(onClick = { vm.apply(r.retrySettingId, r.retryAction) }) { Text("Try again") }
                    }
                }

                is SettingsResponse.Info, null -> Text(message.text)
            }
        }
    }
}

@Composable
private fun InputBar(enabled: Boolean, onSend: (String) -> Unit, onMic: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Ask or describe a problem…") },
            shape = RoundedCornerShape(24.dp),
            maxLines = 3,
            modifier = Modifier.weight(1f),
        )
        if (text.isBlank()) {
            IconButton(onClick = onMic, enabled = enabled) {
                Icon(Icons.Default.Mic, contentDescription = "Speak")
            }
        } else {
            IconButton(
                onClick = {
                    onSend(text)
                    text = ""
                },
                enabled = enabled,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

private fun Context.launch(spec: IntentSpec) {
    for (action in listOf(spec.action) + spec.fallbacks) {
        val intent = Intent(action).apply {
            if (spec.withPackageUri) data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(intent)
            return
        } catch (e: ActivityNotFoundException) {
            // try the next one
        }
    }
    Toast.makeText(this, "This screen isn't available on this phone", Toast.LENGTH_SHORT).show()
}
