package ai.kairo.gallery.ui.gallery

import ai.kairo.gallery.assistant.ui.AssistantChat
import ai.kairo.gallery.assistant.ui.ClearChatButton
import ai.kairo.gallery.data.IndexedImage
import ai.kairo.gallery.index.Indexer
import ai.kairo.gallery.search.SearchResult
import ai.kairo.gallery.ui.AppNavigation
import ai.kairo.gallery.ui.MainViewModel
import ai.kairo.gallery.ui.OpenRequest
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** Screens inside the user-facing gallery. */
sealed interface GalleryRoute {
    data object Home : GalleryRoute
    data class Search(val voice: Boolean = false) : GalleryRoute
    data class Album(val key: String) : GalleryRoute
    data class Viewer(val photos: List<IndexedImage>, val start: Int) : GalleryRoute
}

/**
 * The demo / user UI: an OriginOS-style gallery with smart search on top, and the Kairo chat as a third tab.
 * Indexing keeps running in the background (WorkManager); this screen only observes it.
 */
@Composable
fun GalleryApp(vm: MainViewModel, hasPerm: Boolean, onGrant: () -> Unit, onOpenDev: () -> Unit) {
    val stack = remember { mutableStateListOf<GalleryRoute>(GalleryRoute.Home) }
    fun push(r: GalleryRoute) { stack.add(r) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.size > 1) { pop() }

    // Out here, not in HomeScreen: coming back from a photo, album or search keeps the tab the user was on.
    var tab by rememberSaveable { mutableIntStateOf(TAB_PHOTOS) }
    val photos by vm.allImages.collectAsState()
    val c = Kairo.colors

    // Opened from the floating ball: back from there lands on the Kairo tab, where the conversation is.
    val request by AppNavigation.pending.collectAsState()
    LaunchedEffect(request) {
        val r = request ?: return@LaunchedEffect
        AppNavigation.pending.value = null
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
        tab = TAB_KAIRO
        when (r) {
            OpenRequest.KairoTab -> Unit
            is OpenRequest.Photos -> {
                vm.showResult(r.result)
                push(GalleryRoute.Search())
            }
            is OpenRequest.Photo -> push(GalleryRoute.Viewer(r.photos, r.start))
        }
    }

    Box(Modifier.fillMaxSize().background(c.background)) {
        AnimatedContent(
            targetState = stack.last(),
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "route",
        ) { route ->
            when (route) {
                GalleryRoute.Home -> HomeScreen(
                    vm = vm, photos = photos, hasPerm = hasPerm, onGrant = onGrant, onOpenDev = onOpenDev,
                    tab = tab, onTab = { tab = it },
                    onSearch = { push(GalleryRoute.Search()) },
                    onVoice = { push(GalleryRoute.Search(voice = true)) },
                    onOpenAlbum = { push(GalleryRoute.Album(it)) },
                    onOpenPhoto = { list, i -> push(GalleryRoute.Viewer(list, i)) },
                    onOpenPhotos = { result ->
                        vm.showResult(result)
                        push(GalleryRoute.Search())
                    },
                )
                is GalleryRoute.Search -> SmartSearchScreen(
                    vm = vm, startWithVoice = route.voice, onBack = { pop() },
                    onOpenPhoto = { list, i -> push(GalleryRoute.Viewer(list, i)) },
                )
                is GalleryRoute.Album -> AlbumScreen(
                    album = SMART_ALBUMS.first { it.key == route.key }, photos = photos, onBack = { pop() },
                    onOpenPhoto = { list, i -> push(GalleryRoute.Viewer(list, i)) },
                )
                is GalleryRoute.Viewer -> PhotoViewer(photos = route.photos, start = route.start, onBack = { pop() })
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------
// Home: header + (Photos | Albums: search pill + grid) or (Kairo: chat) + bottom tabs
// ---------------------------------------------------------------------------------------------------

private const val TAB_PHOTOS = 0
private const val TAB_ALBUMS = 1
private const val TAB_KAIRO = 2

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeScreen(
    vm: MainViewModel,
    photos: List<IndexedImage>,
    hasPerm: Boolean,
    onGrant: () -> Unit,
    onOpenDev: () -> Unit,
    tab: Int,
    onTab: (Int) -> Unit,
    onSearch: () -> Unit,
    onVoice: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenPhoto: (List<IndexedImage>, Int) -> Unit,
    onOpenPhotos: (SearchResult) -> Unit,
) {
    val status by vm.indexStatus.collectAsState()
    val c = Kairo.colors

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // Title row with the small developer-mode icon.
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (tab) {
                    TAB_PHOTOS -> "Photos"
                    TAB_ALBUMS -> "Albums"
                    else -> "Kairo"
                },
                style = MaterialTheme.typography.headlineLarge, color = c.text, modifier = Modifier.weight(1f),
            )
            if (tab == TAB_KAIRO) ClearChatButton()
            IconButton(onClick = onOpenDev) {
                Icon(Icons.Filled.Build, contentDescription = "Developer view", tint = c.textSecondary, modifier = Modifier.size(18.dp).alpha(0.6f))
            }
        }
        if (tab == TAB_KAIRO) {
            // Works without photo access: settings don't need it.
            AssistantChat(onOpenPhotos = onOpenPhotos, onOpenPhoto = onOpenPhoto, modifier = Modifier.weight(1f))
        } else {
            SearchPill(onClick = onSearch, onVoice = onVoice, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            IndexingPill(status)

            Box(Modifier.weight(1f)) {
                when {
                    !hasPerm -> PermissionCard(onGrant)
                    photos.isEmpty() -> EmptyGallery()
                    tab == TAB_PHOTOS -> PhotosGrid(photos, onOpenPhoto)
                    else -> AlbumsGrid(photos, onOpenAlbum)
                }
            }
        }
        // While typing to Kairo the keyboard takes the tabs' place.
        if (!(tab == TAB_KAIRO && WindowInsets.isImeVisible)) BottomTabs(tab, onTab)
    }
}

@Composable
private fun SearchPill(onClick: () -> Unit, onVoice: () -> Unit, modifier: Modifier = Modifier) {
    val c = Kairo.colors
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(shape)
            .background(c.surface)
            .aiGlow(shape, active = false, width = 1.5.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AiSparkle(size = 20.dp)
        Spacer(Modifier.width(10.dp))
        Text("Search photos, tickets, IDs…", color = c.textSecondary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onVoice) { MicIcon(c.accent, size = 22.dp) }
    }
}

/** Small animated pill while Kairo works in the background; hidden when idle. */
@Composable
private fun IndexingPill(status: Indexer.Status) {
    val c = Kairo.colors
    AnimatedVisibility(visible = status.running, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val label = if (status.message.startsWith("Visual")) "Scanning your photos" else "Understanding your photos"
        val progress by animateFloatAsState(if (status.total == 0) 0f else status.done.toFloat() / status.total, label = "p")
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AiSparkle(size = 14.dp)
                Spacer(Modifier.width(6.dp))
                Text("$label · ${status.done} of ${status.total}", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(c.surfaceHigh)) {
                Box(Modifier.fillMaxWidth(progress).height(3.dp).clip(CircleShape).background(Brush.horizontalGradient(c.ai.take(3))))
            }
        }
    }
}

@Composable
private fun PhotosGrid(photos: List<IndexedImage>, onOpenPhoto: (List<IndexedImage>, Int) -> Unit) {
    val groups = remember(photos) { groupByDay(photos) }
    val ordered = remember(groups) { groups.flatMap { it.photos } }
    val c = Kairo.colors
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        contentPadding = PaddingValues(bottom = 12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        for (g in groups) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "h-${g.label}") {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
                    Text(g.label, style = MaterialTheme.typography.titleMedium, color = c.text)
                    Spacer(Modifier.width(8.dp))
                    Text("${g.photos.size}", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
                }
            }
            items(g.photos, key = { it.mediaId }) { p ->
                Thumb(p, corner = 2.dp) { onOpenPhoto(ordered, ordered.indexOf(p)) }
            }
        }
    }
}

/** Square thumbnail. A tiny sparkle marks photos Kairo is still reading. */
@Composable
fun Thumb(p: IndexedImage, corner: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val c = Kairo.colors
    val shape = RoundedCornerShape(corner)
    Box(
        Modifier.aspectRatio(1f).clip(shape).background(c.surfaceHigh)
            .border(0.5.dp, c.divider, shape)  // keeps white documents from melting into the page
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = Uri.parse(p.uri), contentDescription = p.description,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )
        if (p.status == "pending") {
            Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                AiSparkle(size = 12.dp)
            }
        }
    }
}

@Composable
private fun AlbumsGrid(photos: List<IndexedImage>, onOpenAlbum: (String) -> Unit) {
    val albums = remember(photos) {
        SMART_ALBUMS.map { a -> a to photos.filter(a.matches).sortedByDescending { it.dateTaken } }
            .filter { (a, list) -> a.key == "all" || list.isNotEmpty() }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(albums, key = { it.first.key }) { (album, list) ->
            AlbumCard(album, list) { onOpenAlbum(album.key) }
        }
    }
}

@Composable
private fun AlbumCard(album: SmartAlbum, list: List<IndexedImage>, onClick: () -> Unit) {
    val c = Kairo.colors
    Column(Modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(c.surfaceHigh).border(0.5.dp, c.divider, RoundedCornerShape(16.dp))) {
            list.firstOrNull()?.let { cover ->
                AsyncImage(
                    model = Uri.parse(cover.uri), contentDescription = album.title, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().then(if (album.private) Modifier.blur(18.dp) else Modifier),
                )
            }
            if (album.private) {
                Box(Modifier.align(Alignment.Center).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Lock, contentDescription = "Private", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(album.title, style = MaterialTheme.typography.titleMedium, color = c.text)
        Text("${list.size}", style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
    }
}

@Composable
private fun AlbumScreen(
    album: SmartAlbum,
    photos: List<IndexedImage>,
    onBack: () -> Unit,
    onOpenPhoto: (List<IndexedImage>, Int) -> Unit,
) {
    val c = Kairo.colors
    val list = remember(photos, album) { photos.filter(album.matches).sortedByDescending { it.dateTaken } }
    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding()) {
        TopBar(title = album.title, subtitle = "${list.size} photos", onBack = onBack)
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(list, key = { it.mediaId }) { p -> Thumb(p, corner = 2.dp) { onOpenPhoto(list, list.indexOf(p)) } }
        }
    }
}

@Composable
fun TopBar(title: String, subtitle: String? = null, onBack: () -> Unit, tint: Color = Kairo.colors.text) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = tint)
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, color = tint)
            subtitle?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = tint.copy(alpha = 0.6f)) }
        }
    }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit) {
    val c = Kairo.colors
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AiSparkle(size = 64.dp)
        Spacer(Modifier.height(20.dp))
        Text("Your gallery, now searchable", style = MaterialTheme.typography.titleLarge, color = c.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Kairo finds photos by what's in them: tickets, IDs, food, pets. Everything stays on this phone.",
            style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant, shape = RoundedCornerShape(24.dp), colors = ButtonDefaults.buttonColors(containerColor = c.accent)) {
            Text("Allow photo access", modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text("Choose \"Allow all\"", style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
    }
}

@Composable
private fun EmptyGallery() {
    val c = Kairo.colors
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        AiSparkle(size = 48.dp)
        Spacer(Modifier.height(16.dp))
        Text("No photos yet", style = MaterialTheme.typography.titleLarge, color = c.text)
        Spacer(Modifier.height(6.dp))
        Text("Add photos to Pictures/Kairo. Kairo will read them in the background.", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, textAlign = TextAlign.Center)
    }
}

// ---------------------------------------------------------------------------------------------------
// Bottom tabs (hand-drawn icons: Photos = picture frame, Albums = 2x2 tiles, Kairo = sparkle)
// ---------------------------------------------------------------------------------------------------

@Composable
private fun BottomTabs(selected: Int, onSelect: (Int) -> Unit) {
    val c = Kairo.colors
    Row(
        Modifier.fillMaxWidth().background(c.background).navigationBarsPadding().padding(top = 6.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        TabItem("Photos", selected == TAB_PHOTOS, { tint -> PhotosIcon(tint) }) { onSelect(TAB_PHOTOS) }
        TabItem("Albums", selected == TAB_ALBUMS, { tint -> AlbumsIcon(tint) }) { onSelect(TAB_ALBUMS) }
        TabItem("Kairo", selected == TAB_KAIRO, { tint -> KairoIcon(tint) }) { onSelect(TAB_KAIRO) }
    }
}

@Composable
private fun TabItem(label: String, selected: Boolean, icon: @Composable (Color) -> Unit, onClick: () -> Unit) {
    val c = Kairo.colors
    val tint = if (selected) c.text else c.textSecondary
    Column(
        Modifier.clip(RoundedCornerShape(12.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon(tint)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun PhotosIcon(tint: Color) {
    Canvas(Modifier.size(24.dp)) {
        val s = size.width
        val stroke = Stroke(width = s * 0.08f)
        drawRoundRect(tint, topLeft = Offset(s * 0.12f, s * 0.18f), size = Size(s * 0.76f, s * 0.64f), cornerRadius = CornerRadius(s * 0.12f), style = stroke)
        drawCircle(tint, radius = s * 0.07f, center = Offset(s * 0.36f, s * 0.38f))
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(s * 0.18f, s * 0.74f); lineTo(s * 0.42f, s * 0.52f); lineTo(s * 0.56f, s * 0.64f)
            lineTo(s * 0.66f, s * 0.55f); lineTo(s * 0.84f, s * 0.74f)
        }
        drawPath(path, tint, style = Stroke(width = s * 0.07f))
    }
}

@Composable
private fun AlbumsIcon(tint: Color) {
    Canvas(Modifier.size(24.dp)) {
        val s = size.width
        val stroke = Stroke(width = s * 0.08f)
        val box = s * 0.32f
        for ((x, y) in listOf(0.14f to 0.14f, 0.54f to 0.14f, 0.14f to 0.54f, 0.54f to 0.54f)) {
            drawRoundRect(tint, topLeft = Offset(s * x, s * y), size = Size(box, box), cornerRadius = CornerRadius(s * 0.08f), style = stroke)
        }
    }
}

/** The AI sparkle's shape in the tab colour: a big four-point star and a small one. */
@Composable
private fun KairoIcon(tint: Color) {
    Canvas(Modifier.size(24.dp)) {
        val s = size.width
        fun star(cx: Float, cy: Float, r: Float) = androidx.compose.ui.graphics.Path().apply {
            val k = r * 0.18f  // how far the sides curve in towards the centre
            moveTo(cx, cy - r)
            quadraticTo(cx + k, cy - k, cx + r, cy)
            quadraticTo(cx + k, cy + k, cx, cy + r)
            quadraticTo(cx - k, cy + k, cx - r, cy)
            quadraticTo(cx - k, cy - k, cx, cy - r)
            close()
        }
        drawPath(star(s * 0.42f, s * 0.56f, s * 0.36f), tint, style = Stroke(width = s * 0.08f))
        drawPath(star(s * 0.80f, s * 0.20f, s * 0.14f), tint)
    }
}
