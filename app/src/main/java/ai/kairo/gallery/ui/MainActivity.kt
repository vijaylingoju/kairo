package ai.kairo.gallery.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ai.kairo.gallery.assistant.ball.BallService
import ai.kairo.gallery.data.IndexedImage
import ai.kairo.gallery.data.Prefs
import ai.kairo.gallery.index.Indexer
import ai.kairo.gallery.ui.gallery.GalleryApp
import ai.kairo.gallery.ui.gallery.KairoTheme
import coil.compose.AsyncImage

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // The user-facing gallery is the default; the wrench icon opens this developer screen.
            var devMode by rememberSaveable { mutableStateOf(false) }
            // Opened by the floating ball: show the gallery, which then goes where the ball asked.
            val request by AppNavigation.pending.collectAsState()
            LaunchedEffect(request) { if (request != null) devMode = false }
            MaterialTheme {
                val vm: MainViewModel = viewModel()
                var hasPerm by remember {
                    mutableStateOf(
                        checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
                            PackageManager.PERMISSION_GRANTED
                    )
                }
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { granted ->
                    hasPerm = granted[Manifest.permission.READ_MEDIA_IMAGES] == true
                }
                // Index on app open and right after permission is granted.
                LaunchedEffect(hasPerm) { if (hasPerm) vm.indexNow(force = false) }
                val onGrant = {
                    launcher.launch(
                        arrayOf(
                            Manifest.permission.READ_MEDIA_IMAGES,
                            Manifest.permission.ACCESS_MEDIA_LOCATION,
                            Manifest.permission.POST_NOTIFICATIONS,
                        )
                    )
                }
                if (devMode) {
                    BackHandler { devMode = false }
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
                        KairoScreen(vm = vm, hasPerm = hasPerm, onGrant = onGrant, onClose = { devMode = false })
                    }
                } else {
                    KairoTheme {
                        GalleryApp(vm = vm, hasPerm = hasPerm, onGrant = onGrant, onOpenDev = { devMode = true })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        BallService.appVisible.value = true
        // Also covers coming back from the "Display over other apps" screen.
        BallService.startIfEnabled(this)
    }

    override fun onPause() {
        BallService.appVisible.value = false
        super.onPause()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KairoScreen(vm: MainViewModel, hasPerm: Boolean, onGrant: () -> Unit, onClose: () -> Unit = {}) {
    val status by vm.indexStatus.collectAsState()
    val model by vm.modelState.collectAsState()
    val clip by vm.clipState.collectAsState()
    val allImages by vm.allImages.collectAsState()
    val result by vm.result.collectAsState()
    val searching by vm.searching.collectAsState()
    val watch by vm.watchScreenshots.collectAsState()

    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<IndexedImage?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val ballOn by BallService.running.collectAsState()
    var keepReady by remember { mutableStateOf(Prefs.keepModelReady(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kairo · Developer") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to gallery")
                    }
                },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
        ) {
            Text(model, style = MaterialTheme.typography.bodySmall)
            Text(clip, style = MaterialTheme.typography.bodySmall)
            Text(statusLine(status), style = MaterialTheme.typography.bodySmall)
            if (status.running) {
                LinearProgressIndicator(
                    progress = { if (status.total == 0) 0f else status.done.toFloat() / status.total },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }

            if (!hasPerm) {
                Button(onClick = onGrant, modifier = Modifier.padding(vertical = 8.dp)) {
                    Text("Grant photo access (choose Allow all)")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                OutlinedButton(onClick = { vm.indexNow(false) }) { Text("Index now") }
                OutlinedButton(onClick = { vm.indexNow(true) }) { Text("Re-index all") }
            }
            TextButton(onClick = { confirmClear = true }) {
                Text("Clear all data & start fresh", color = MaterialTheme.colorScheme.error)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = watch, onCheckedChange = { vm.setWatchScreenshots(it) })
                Spacer(Modifier.width(8.dp))
                Text("Also index new screenshots", style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = ballOn, onCheckedChange = { on ->
                    Prefs.setBallEnabled(context, on)
                    when {
                        !on -> BallService.stop(context)
                        BallService.canShow(context) -> BallService.startIfEnabled(context)
                        else -> BallService.askPermission(context)
                    }
                })
                Spacer(Modifier.width(8.dp))
                Text("Floating ball over other apps", style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = keepReady, onCheckedChange = { on ->
                    Prefs.setKeepModelReady(context, on)
                    keepReady = on
                })
                Spacer(Modifier.width(8.dp))
                Text("Keep Kairo ready (Gemma stays loaded; for demos)", style = MaterialTheme.typography.bodyMedium)
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Ask your gallery… e.g. my PAN number") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(query) }),
                trailingIcon = { TextButton(onClick = { vm.search(query) }) { Text("Go") } },
            )
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))

            result?.let { r ->
                r.answer?.let { AnswerCard(it) }
                val f = r.filter
                Text(
                    "Filter → categories=${f.categories.ifEmpty { setOf("any") }.joinToString()} " +
                        "keywords=${f.keywords.ifEmpty { listOf("-") }.joinToString()} " +
                        "field=${f.wantedField ?: "-"} • ${if (f.usedLlm) "Gemma" else "rules"} • " +
                        (r.topSim?.let { "visual top ${"%.2f".format(it)}" + (f.visual?.let { v -> " \"$v\"" } ?: "") + " • " } ?: "") +
                        "${r.items.size} results • ${r.tookMs} ms",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                TextButton(onClick = { query = ""; vm.clearSearch() }) { Text("Clear search") }
            }

            val shown = result?.items ?: allImages
            if (shown.isEmpty()) {
                Text(
                    if (result != null) "No matching images." else "No images indexed yet. Add images to Pictures/Kairo.",
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f).padding(top = 8.dp),
            ) {
                items(shown, key = { it.mediaId }) { img ->
                    Thumb(img) { selected = img }
                }
            }
        }
    }

    selected?.let { img -> DetailDialog(img) { selected = null } }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all data?") },
            text = {
                Text(
                    "Deletes the whole search index (categories, text, fields and visual fingerprints) " +
                        "and indexes every photo again from scratch.\n\nYour photos and the AI model files are not touched."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    query = ""
                    selected = null
                    vm.clearAllAndReindex()
                }) { Text("Clear & re-index", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

private fun statusLine(s: Indexer.Status): String =
    if (s.running) "${s.message} ${s.done}/${s.total} • ${s.current}" + (if (s.lastMs > 0) " • last ${s.lastMs} ms" else "")
    else s.message

@Composable
private fun AnswerCard(text: String) {
    val ctx = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                val value = text.substringAfter(": ").substringBefore(" • ")
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("Kairo", value))
                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
            }) { Text("Copy") }
        }
    }
}

@Composable
private fun Thumb(img: IndexedImage, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box {
            AsyncImage(
                model = Uri.parse(img.uri),
                contentDescription = img.description,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
        }
        Text(
            if (img.status == "pending") "reading…" else img.category.replace('_', ' '),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@Composable
private fun DetailDialog(img: IndexedImage, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(img.category.replace('_', ' ')) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AsyncImage(
                    model = Uri.parse(img.uri),
                    contentDescription = img.description,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                )
                Spacer(Modifier.padding(4.dp))
                Text(img.description, style = MaterialTheme.typography.bodyMedium)
                if (img.tags.isNotEmpty()) {
                    Text("Tags: ${img.tags.joinToString()}", style = MaterialTheme.typography.bodySmall)
                }
                img.fields.filterValues { it != null }.forEach { (k, v) ->
                    Text("$k: $v", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "Status: ${img.status} • ${img.indexMs} ms" + (img.error?.let { " • $it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                )
                if (img.lat != null && img.lng != null) {
                    Text("Location: ${img.lat}, ${img.lng}", style = MaterialTheme.typography.labelSmall)
                }
                Text("File: ${img.folder}${img.name}", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.padding(4.dp))
                Text("OCR text", style = MaterialTheme.typography.labelMedium)
                Text(img.ocrText.ifBlank { "(none)" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        },
    )
}
