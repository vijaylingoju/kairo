package ai.kairo.gallery.assistant.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.kairo.gallery.assistant.AssistantReply
import ai.kairo.gallery.assistant.AssistantSession
import ai.kairo.gallery.assistant.ChatMessage
import ai.kairo.gallery.assistant.Route
import ai.kairo.gallery.data.IndexedImage
import ai.kairo.gallery.search.SearchResult
import ai.kairo.gallery.settings.IntentSpec
import ai.kairo.gallery.settings.SettingsResponse
import ai.kairo.gallery.ui.gallery.AnswerCard
import ai.kairo.gallery.ui.gallery.Kairo
import ai.kairo.gallery.ui.gallery.Thumb
import coil.compose.AsyncImage

/** Thumbnails shown in a photo reply; "See all" opens the rest in the gallery's search screen. */
private const val THUMBS = 4

/**
 * One reply from Kairo. The Kairo tab (and later the floating ball) use these same cards, so every button
 * works the same wherever the answer appears.
 */
@Composable
fun ReplyCard(
    message: ChatMessage,
    session: AssistantSession,
    onLaunch: (IntentSpec) -> Unit,
    onOpenPhotos: (SearchResult) -> Unit,
    onOpenPhoto: (List<IndexedImage>, Int) -> Unit,
) {
    val c = Kairo.colors
    val reply = message.reply
    CompositionLocalProvider(LocalContentColor provides c.text) {
        Column(
            Modifier
                .widthIn(max = 360.dp)
                // Photo rows share the card's width; text replies stay as narrow as their text.
                .then(if (reply is AssistantReply.Photos) Modifier.fillMaxWidth() else Modifier)
                .clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
                .background(c.surface)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (reply) {
                is AssistantReply.Photos -> PhotosReply(reply, onOpenPhotos, onOpenPhoto)
                is AssistantReply.Settings -> SettingsReply(reply.response, session, onLaunch)
                is AssistantReply.AskWhich -> {
                    Text(reply.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { session.choose(reply.query, Route.GALLERY) }) { Text("Search photos") }
                        FilledTonalButton(onClick = { session.choose(reply.query, Route.SETTINGS) }) { Text("Phone settings") }
                    }
                }
                null -> Text(message.text)
            }
        }
    }
}

@Composable
private fun PhotosReply(
    reply: AssistantReply.Photos,
    onOpenPhotos: (SearchResult) -> Unit,
    onOpenPhoto: (List<IndexedImage>, Int) -> Unit,
) {
    val c = Kairo.colors
    val r = reply.result
    val answer = r.answer
    if (answer != null) AnswerCard(r, answer) { img -> onOpenPhoto(listOf(img), 0) } else Text(reply.text)
    if (r.items.isNotEmpty()) {
        val shown = r.items.take(THUMBS)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            shown.forEachIndexed { i, p ->
                Box(Modifier.weight(1f)) { Thumb(p, corner = 8.dp) { onOpenPhoto(r.items, i) } }
            }
            repeat(THUMBS - shown.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    if (r.items.size > THUMBS) {
        Text(
            "See all ${r.items.size} photos  ›",
            style = MaterialTheme.typography.labelLarge, color = c.accent, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onOpenPhotos(r) }.padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun SettingsReply(r: SettingsResponse, session: AssistantSession, onLaunch: (IntentSpec) -> Unit) {
    val c = Kairo.colors
    when (r) {
        is SettingsResponse.Done -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = c.accent, modifier = Modifier.size(20.dp))
                Text(r.text, modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false))
                r.undo?.let { token -> TextButton(onClick = { session.undo(token) }) { Text("Undo") } }
            }
        }

        is SettingsResponse.Suggestions -> {
            Text(r.text)
            if (r.rows.isNotEmpty()) FactRows(r.rows)
            r.items.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.background).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, style = MaterialTheme.typography.titleSmall)
                        Text(s.reason, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                    }
                    FilledTonalButton(
                        onClick = { session.apply(s.settingId, s.action, s.value) },
                        modifier = Modifier.padding(start = 8.dp),
                    ) { Text(s.buttonLabel) }
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

        is SettingsResponse.Facts -> {
            Text(r.text)
            FactRows(r.rows)
            r.open?.let { spec -> FilledTonalButton(onClick = { onLaunch(spec) }) { Text(r.openLabel) } }
        }

        is SettingsResponse.ChoosePhoto -> {
            Text(r.text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                r.photoUris.forEach { uri ->
                    // Phone-shaped, so the user sees roughly what the screen will show.
                    AsyncImage(
                        model = Uri.parse(uri),
                        contentDescription = "Use this photo as wallpaper",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 96.dp, height = 208.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { session.setWallpaper(uri, r.screen) },
                    )
                }
            }
        }

        is SettingsResponse.OpenPanel -> {
            Text(r.text)
            OutlinedButton(onClick = { onLaunch(r.open) }) { Text("Open again") }
        }

        is SettingsResponse.NeedsPermission -> {
            Text(r.text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onLaunch(r.grant) }) { Text("Grant") }
                OutlinedButton(onClick = { session.apply(r.retrySettingId, r.retryAction) }) { Text("Try again") }
            }
        }

        is SettingsResponse.Info -> Text(r.text)
    }
}

/** Label on the left, value on the right: phone facts, or what the checkup looked at. */
@Composable
private fun FactRows(rows: List<Pair<String, String>>) {
    val c = Kairo.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.background).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEach { (label, value) ->
            Row {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                    modifier = Modifier.weight(0.4f),
                )
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(0.6f),
                )
            }
        }
    }
}

internal fun Context.launch(spec: IntentSpec) {
    val pkg = spec.targetPackage ?: packageName
    for (action in listOf(spec.action) + spec.fallbacks) {
        // With a package: that app's own page first, then the general screen if this phone has no per-app page.
        for (withPackage in if (spec.withPackageUri) listOf(true, false) else listOf(false)) {
            val intent = Intent(action).apply {
                if (withPackage) data = Uri.parse("package:$pkg")
                spec.targetPackage?.let { putExtra(Intent.EXTRA_PACKAGE_NAME, it) }
                // From the floating ball (a service) there's no Activity task to open it in.
                if (this@launch !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // try the next one
            } catch (e: SecurityException) {
                // another brand's screen that exists here but isn't open to other apps: try the next one
            }
        }
    }
    Toast.makeText(this, "This screen isn't available on this phone", Toast.LENGTH_SHORT).show()
}
