package ai.kairo.gallery.ui.gallery

import ai.kairo.gallery.data.IndexedImage
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

@Composable
fun PhotoViewer(photos: List<IndexedImage>, start: Int, onBack: () -> Unit) {
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (photos.size - 1).coerceAtLeast(0))) { photos.size }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }
    val current = photos.getOrNull(pager.currentPage)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            AsyncImage(
                model = Uri.parse(photos[page].uri), contentDescription = photos[page].description,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().clickable { chrome = !chrome },
            )
        }

        // Top bar
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))).statusBarsPadding()) {
                current?.let { p ->
                    TopBar(title = shortDate(p.dateTaken).substringBefore(" ·"), subtitle = shortDate(p.dateTaken).substringAfter("· "), onBack = onBack, tint = Color.White)
                }
            }
        }

        // Bottom: description + info toggle
        AnimatedVisibility(chrome && !showInfo, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                    .navigationBarsPadding().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    current?.let { p ->
                        Text(prettyCategory(p.category), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f))
                        if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 2)
                    }
                }
                IconButton(onClick = { showInfo = true }) { Icon(Icons.Filled.Info, contentDescription = "Details", tint = Color.White) }
            }
        }

        // Details sheet
        AnimatedVisibility(
            showInfo && current != null,
            enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            current?.let { DetailsSheet(it) { showInfo = false } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsSheet(p: IndexedImage, onClose: () -> Unit) {
    val c = Kairo.colors
    val ctx = LocalContext.current
    var showText by remember(p.mediaId) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().heightIn(max = 520.dp)
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(c.background)
            .navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AiSparkle(size = 16.dp, animated = false)
            Spacer(Modifier.width(6.dp))
            Text(prettyCategory(p.category), style = MaterialTheme.typography.titleMedium, color = c.text, modifier = Modifier.weight(1f))
            Text("Close", style = MaterialTheme.typography.labelLarge, color = c.accent, modifier = Modifier.clickable(onClick = onClose).padding(6.dp))
        }
        if (p.description.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(p.description, style = MaterialTheme.typography.bodyLarge, color = c.text)
        }
        if (p.tags.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                p.tags.distinct().forEach { t ->
                    Text(t, style = MaterialTheme.typography.labelMedium, color = c.text,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.surface).padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }
        }
        val fields = p.fields.filterValues { !it.isNullOrBlank() }
        if (fields.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Column(Modifier.clip(RoundedCornerShape(16.dp)).background(c.surface).padding(horizontal = 14.dp, vertical = 6.dp)) {
                fields.forEach { (k, v) ->
                    var revealed by remember(p.mediaId, k) { mutableStateOf(k != "id_number") }
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable(enabled = k == "id_number") { revealed = !revealed }) {
                            Text(prettyField(k, p.category), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                            Text(if (revealed) v!! else maskId(v!!), style = MaterialTheme.typography.bodyLarge, color = c.text, fontWeight = FontWeight.Medium)
                        }
                        Text("Copy", style = MaterialTheme.typography.labelLarge, color = c.accent,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable {
                                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Kairo", v))
                                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                            }.padding(8.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(shortDate(p.dateTaken), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
        if (p.lat != null && p.lng != null) Text("%.4f, %.4f".format(p.lat, p.lng), style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
        Text(p.name, style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
        if (p.ocrText.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(if (showText) "Hide text in photo" else "Show text in photo", style = MaterialTheme.typography.labelLarge, color = c.accent,
                modifier = Modifier.clickable { showText = !showText }.padding(vertical = 4.dp))
            if (showText) Text(p.ocrText, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(8.dp))
    }
}
