package com.simple.imgcomparer

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size

class MainActivity : ComponentActivity() {
    private val vm: GalleryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { Root(vm) } }
    }
}

internal val Bg = Color(0xFF0E0E11)
internal val Surface1 = Color(0xFF1A1A20)
internal val Accent = Color(0xFF7C9CFF)

@Composable
internal fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Accent, background = Bg, surface = Surface1, onSurface = Color.White,
    ),
    content = content,
)

private fun storagePermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
}

private fun needsAllFilesAccess() =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()

@Composable
private fun Root(vm: GalleryViewModel) {
    val ctx = LocalContext.current
    fun hasPerms() = storagePermissions().all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }
    var granted by remember { mutableStateOf(hasPerms()) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = it.values.all { ok -> ok }
    }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(storagePermissions()) }

    // Jednokratno: "Pristup svim fajlovima" → brisanje bez sistemske potvrde
    var askAllFiles by remember { mutableStateOf(false) }
    var askedAllFiles by rememberSaveable { mutableStateOf(false) }
    if (granted) LifecycleResumeEffect(Unit) {
        vm.refresh(); vm.autoScan()
        if (needsAllFilesAccess() && !askedAllFiles) askAllFiles = true
        onPauseOrDispose { }
    }
    if (askAllFiles) AlertDialog(
        onDismissRequest = { askAllFiles = false; askedAllFiles = true },
        title = { Text("Brisanje bez potvrde") },
        text = { Text("Da bi brisanje radilo odmah, bez Android dijaloga, dozvoli \"Pristup svim fajlovima\" za ovu aplikaciju.") },
        confirmButton = {
            TextButton(onClick = {
                askAllFiles = false; askedAllFiles = true
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                )
            }) { Text("Dozvoli") }
        },
        dismissButton = { TextButton(onClick = { askAllFiles = false; askedAllFiles = true }) { Text("Kasnije") } },
    )

    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == android.app.Activity.RESULT_OK) vm.commitPendingDelete() else vm.pendingDelete = null
    }
    LaunchedEffect(vm.pendingDelete) {
        vm.pendingDelete?.let { deleteLauncher.launch(IntentSenderRequest.Builder(it).build()) }
    }

    BackHandler(enabled = vm.screen !is Screen.Gallery || vm.compareMode) { vm.back() }

    Surface(Modifier.fillMaxSize(), color = Bg) {
        if (!granted) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Potrebna je dozvola za slike", fontSize = 18.sp)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { permLauncher.launch(storagePermissions()) }) { Text("Dozvoli") }
            }
        } else when (val s = vm.screen) {
            is Screen.Gallery -> GalleryScreen(vm)
            is Screen.Viewer -> ViewerScreen(vm, s.index)
            is Screen.Compare -> CompareScreen(vm)
            is Screen.Pairs -> PairsScreen(vm)
        }
    }
}

// ───────────────────────── Galerija ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun GalleryScreen(vm: GalleryViewModel) {
    val items = vm.visible
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (vm.compareMode) "Odaberi slike" else "Galerija", fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                if (vm.compareMode) IconButton({ vm.exitCompareMode() }) { Icon(Icons.Default.Close, null) }
            },
            actions = {
                if (!vm.compareMode) {
                    IconButton({ vm.openPairs() }) { Icon(Icons.Default.AutoAwesome, "Parovi") }
                    IconButton({ vm.enterCompareMode() }) { Icon(Icons.Default.Compare, "Compare") }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = Color.White,
                actionIconContentColor = Color.White, navigationIconContentColor = Color.White),
            windowInsets = WindowInsets.statusBars,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { FolderChip("Sve", vm.folder == null) { vm.selectFolder(null) } }
            items(vm.folders) { f -> FolderChip(f, vm.folder == f) { vm.selectFolder(f) } }
        }
        Spacer(Modifier.height(8.dp))

        Box(Modifier.weight(1f)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(items, key = { _, m -> m.id }) { index, m ->
                    val selIndex = vm.selected.indexOfFirst { it.id == m.id }
                    Box(
                        Modifier.aspectRatio(1f).background(Surface1)
                            .combinedClickable(
                                onClick = { if (vm.compareMode) vm.toggle(m) else vm.openMedia(m) },
                                onLongClick = { if (!vm.compareMode) { vm.enterCompareMode(); vm.toggle(m) } else vm.makeMain(m) },
                            )
                    ) {
                        Thumb(m, Modifier.fillMaxSize())
                        vm.groupOfAnime(m)?.takeIf { it.reals.isNotEmpty() }?.let { grp ->
                            Badge("+${grp.reals.size}", Modifier.align(Alignment.BottomEnd).padding(6.dp).testTag("groupBadge")
                                .clickable { if (!vm.compareMode) vm.openGroup(grp) })
                        }
                        if (selIndex >= 0) {
                            Box(Modifier.fillMaxSize().background(Accent.copy(alpha = 0.25f))
                                .border(3.dp, Accent))
                            Badge(if (selIndex == 0) "A" else "B$selIndex", Modifier.align(Alignment.TopStart).padding(6.dp))
                        }
                    }
                }
            }
            if (vm.loading && items.isEmpty()) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }

        if (vm.compareMode) SelectionBar(vm)
        else Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
private fun FolderChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        shape = RoundedCornerShape(50),
    )
}

@Composable
internal fun Badge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(Accent).padding(horizontal = 7.dp, vertical = 2.dp)
    ) { Text(text, color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}

/** Mali thumbnail – Coil dekodira samo 256px pa je listanje glatko. */
@Composable
internal fun Thumb(m: Media, modifier: Modifier = Modifier, px: Int = 256) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(m.uri).size(px).crossfade(false).build(),
        contentDescription = m.name, contentScale = ContentScale.Crop, modifier = modifier,
    )
}

/** Donja traka iznad navigation bara: sličice slijeva → desno, kanta skroz desno. */
@Composable
internal fun SelectionBar(vm: GalleryViewModel) {
    Surface(color = Surface1, tonalElevation = 6.dp, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp).height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                itemsIndexed(vm.selected, key = { _, m -> m.id }) { i, m ->
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))
                        .border(if (i == 0) BorderStroke(2.dp, Accent) else BorderStroke(0.dp, Color.Transparent), RoundedCornerShape(10.dp))
                        .clickable { vm.makeMain(m) }) {
                        Thumb(m, Modifier.fillMaxSize(), 128)
                        Badge(if (i == 0) "A" else "B$i", Modifier.align(Alignment.BottomStart).padding(2.dp))
                    }
                }
            }
            if (vm.selected.size >= 2) {
                FilledTonalButton(onClick = { vm.goCompare() }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Icon(Icons.Default.Compare, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Uporedi")
                }
                Spacer(Modifier.width(4.dp))
            }
            IconButton(onClick = { vm.deleteBs() }, enabled = vm.selected.size >= 2, modifier = Modifier.testTag("delete")) {
                Icon(Icons.Default.Delete, "Obriši B", tint = if (vm.selected.size >= 2) Color(0xFFFF6B6B) else Color.Gray)
            }
        }
    }
}

// ───────────────────────── Obični pregled ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ViewerScreen(vm: GalleryViewModel, start: Int) {
    val items = vm.viewerList
    if (items.isEmpty()) { vm.back(); return }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, items.lastIndex)) { items.size }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(items.getOrNull(pager.currentPage)?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp) },
            navigationIcon = { IconButton({ vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            actions = {
                val grp = items.getOrNull(pager.currentPage)?.let { vm.groupFor(it) }
                if (grp != null) IconButton({ vm.openGroup(grp) }, Modifier.testTag("viewerCompare")) { Icon(Icons.Default.Compare, "Compare") }
                IconButton({ items.getOrNull(pager.currentPage)?.let { vm.delete(listOf(it)) } }) { Icon(Icons.Default.Delete, "Obriši") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = Color.White,
                actionIconContentColor = Color.White, navigationIconContentColor = Color.White),
        )
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), key = { items[it].id }) { page ->
            AsyncImage(items[page].uri, items[page].name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

// ───────────────────────── Compare ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompareScreen(vm: GalleryViewModel) {
    val main = vm.selected.firstOrNull()
    val others = vm.selected.drop(1)
    if (main == null || others.isEmpty()) { vm.back(); return }
    var otherIdx by remember { mutableIntStateOf(0) }
    if (otherIdx > others.lastIndex) otherIdx = others.lastIndex
    val other = others[otherIdx]

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Compare", fontWeight = FontWeight.SemiBold) },
            navigationIcon = { IconButton({ vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = Color.White,
                navigationIconContentColor = Color.White),
        )
        BeforeAfter(main, other, Modifier.weight(1f).fillMaxWidth())

        // traka: sličice za poređenje (B1, B2…) slijeva, kanta desno
        Surface(color = Surface1, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp).height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).border(2.dp, Accent, RoundedCornerShape(10.dp))) {
                    Thumb(main, Modifier.fillMaxSize(), 128)
                    Badge("A", Modifier.align(Alignment.BottomStart).padding(2.dp))
                }
                Spacer(Modifier.width(8.dp))
                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(others, key = { _, m -> m.id }) { i, m ->
                        Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))
                            .border(if (i == otherIdx) 2.dp else 0.dp, Color.White, RoundedCornerShape(10.dp))
                            .clickable { otherIdx = i }) {
                            Thumb(m, Modifier.fillMaxSize(), 128)
                            Badge("B${i + 1}", Modifier.align(Alignment.BottomStart).padding(2.dp))
                        }
                    }
                }
                IconButton({ vm.delete(listOf(other)) }, Modifier.testTag("delete")) { Icon(Icons.Default.Delete, "Obriši", tint = Color(0xFFFF6B6B)) }
            }
        }
    }
}

/**
 * A (before) lijevo od klizača, B (after) desno.
 * 1 prst: povuci klizač lijevo/desno (ili dodir). 2 prsta: pinch-zoom + pan, isti za A i B.
 */
@Composable
internal fun BeforeAfter(before: Media, after: Media, modifier: Modifier = Modifier) {
    var frac by remember { mutableFloatStateOf(0.5f) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val slop = LocalViewConfiguration.current.touchSlop
    val ctx = LocalContext.current
    fun req(m: Media) = ImageRequest.Builder(ctx).data(m.uri).size(Size(3072, 3072)).build()

    Box(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black)
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var multi = false
                    var dragging = false
                    do {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.count { it.pressed }
                        if (pressed >= 2) {
                            multi = true
                            val c = ev.calculateCentroid()
                            val newScale = (scale * ev.calculateZoom()).coerceIn(1f, 8f)
                            val z = newScale / scale
                            val center = Offset(boxSize.width / 2f, boxSize.height / 2f)
                            val t = (c - center) - (c - center - offset) * z + ev.calculatePan()
                            val maxX = (newScale - 1f) * boxSize.width / 2f
                            val maxY = (newScale - 1f) * boxSize.height / 2f
                            scale = newScale
                            offset = if (newScale <= 1f) Offset.Zero
                            else Offset(t.x.coerceIn(-maxX, maxX), t.y.coerceIn(-maxY, maxY))
                            ev.changes.forEach { it.consume() }
                        } else if (!multi) {
                            val ch = ev.changes.firstOrNull() ?: continue
                            if (!dragging && (ch.position - down.position).getDistance() > slop) dragging = true
                            if ((dragging || !ch.pressed) && boxSize.width > 0) {
                                frac = (ch.position.x / boxSize.width).coerceIn(0f, 1f)
                            }
                            if (dragging) ch.consume()
                        }
                    } while (ev.changes.any { it.pressed })
                }
            }
    ) {
        val zoomLayer: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit = {
            scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
        }
        Box(Modifier.fillMaxSize().graphicsLayer(block = zoomLayer)) {
            AsyncImage(req(after), after.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        Box(
            Modifier.fillMaxSize().drawWithContent {
                clipRect(left = 0f, top = 0f, right = size.width * frac, bottom = size.height) {
                    this@drawWithContent.drawContent()
                }
            }
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer(block = zoomLayer)) {
                AsyncImage(req(before), before.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
        // linija + ručka (ostaju u ekranskim koordinatama, ne zumiraju se)
        val xDp = with(LocalDensity.current) { (boxSize.width * frac).toDp() }
        Box(Modifier.testTag("slider").offset(x = xDp - 1.dp).width(2.dp).fillMaxHeight().background(Color.White))
        Box(
            Modifier.offset(x = xDp - 22.dp).align(Alignment.CenterStart).size(44.dp)
                .clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.DragHandle, null, tint = Color.Black, modifier = Modifier.rotate(90f)) }

        Badge("A · PRIJE", Modifier.align(Alignment.TopStart).padding(12.dp))
        Badge("B · POSLIJE", Modifier.align(Alignment.TopEnd).padding(12.dp))
        if (scale > 1.01f) {
            Badge(
                "%.1f× · reset".format(scale),
                Modifier.align(Alignment.TopCenter).padding(12.dp).testTag("zoom")
                    .clickable { scale = 1f; offset = Offset.Zero },
            )
        }
    }
}
