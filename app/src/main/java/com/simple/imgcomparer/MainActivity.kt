package com.simple.imgcomparer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

private val Bg = Color(0xFF0E0E11)
private val Surface1 = Color(0xFF1A1A20)
private val Accent = Color(0xFF7C9CFF)

@Composable
private fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Accent, background = Bg, surface = Surface1, onSurface = Color.White,
    ),
    content = content,
)

private fun storagePermission() =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
    else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
private fun Root(vm: GalleryViewModel) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, storagePermission()) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(storagePermission()) }
    if (granted) LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose { } }

    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == android.app.Activity.RESULT_OK) vm.onDeleted() else vm.pendingDelete = null
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
                Button(onClick = { permLauncher.launch(storagePermission()) }) { Text("Dozvoli") }
            }
        } else when (val s = vm.screen) {
            is Screen.Gallery -> GalleryScreen(vm)
            is Screen.Viewer -> ViewerScreen(vm, s.index)
            is Screen.Compare -> CompareScreen(vm)
        }
    }
}

// ───────────────────────── Galerija ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun GalleryScreen(vm: GalleryViewModel) {
    val items = vm.visible
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (vm.compareMode) "Odaberi slike" else "Galerija", fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                if (vm.compareMode) IconButton({ vm.exitCompareMode() }) { Icon(Icons.Default.Close, null) }
            },
            actions = {
                if (!vm.compareMode) IconButton({ vm.enterCompareMode() }) { Icon(Icons.Default.Compare, "Compare") }
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
                                onClick = { if (vm.compareMode) vm.toggle(m) else vm.open(index) },
                                onLongClick = { if (!vm.compareMode) { vm.enterCompareMode(); vm.toggle(m) } else vm.makeMain(m) },
                            )
                    ) {
                        Thumb(m, Modifier.fillMaxSize())
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
private fun Badge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(Accent).padding(horizontal = 7.dp, vertical = 2.dp)
    ) { Text(text, color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}

/** Mali thumbnail – Coil dekodira samo 256px pa je listanje glatko. */
@Composable
private fun Thumb(m: Media, modifier: Modifier = Modifier, px: Int = 256) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(m.uri).size(px).crossfade(false).build(),
        contentDescription = m.name, contentScale = ContentScale.Crop, modifier = modifier,
    )
}

/** Donja traka iznad navigation bara: sličice slijeva → desno, kanta skroz desno. */
@Composable
private fun SelectionBar(vm: GalleryViewModel) {
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
            IconButton(onClick = { vm.deleteSelected() }, enabled = vm.selected.isNotEmpty()) {
                Icon(Icons.Default.Delete, "Obriši", tint = if (vm.selected.isNotEmpty()) Color(0xFFFF6B6B) else Color.Gray)
            }
        }
    }
}

// ───────────────────────── Obični pregled ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerScreen(vm: GalleryViewModel, start: Int) {
    val items = vm.visible
    if (items.isEmpty()) { vm.back(); return }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, items.lastIndex)) { items.size }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(items.getOrNull(pager.currentPage)?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp) },
            navigationIcon = { IconButton({ vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            actions = { IconButton({ items.getOrNull(pager.currentPage)?.let { vm.delete(listOf(it.uri)) } }) { Icon(Icons.Default.Delete, "Obriši") } },
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
private fun CompareScreen(vm: GalleryViewModel) {
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
                IconButton({ vm.deleteSelected() }) { Icon(Icons.Default.Delete, "Obriši", tint = Color(0xFFFF6B6B)) }
            }
        }
    }
}

/** A (before) je lijevo od klizača, B (after) desno. Povuci lijevo/desno. */
@Composable
private fun BeforeAfter(before: Media, after: Media, modifier: Modifier = Modifier) {
    var frac by remember { mutableFloatStateOf(0.5f) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val ctx = LocalContext.current
    fun req(m: Media) = ImageRequest.Builder(ctx).data(m.uri).size(Size(2048, 2048)).build()

    Box(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black)
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    if (boxSize.width > 0) frac = (change.position.x / boxSize.width).coerceIn(0f, 1f)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { if (boxSize.width > 0) frac = (it.x / boxSize.width).coerceIn(0f, 1f) }
            }
    ) {
        AsyncImage(req(after), after.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        AsyncImage(
            req(before), before.name,
            Modifier.fillMaxSize().drawWithContent {
                clipRect(left = 0f, top = 0f, right = size.width * frac, bottom = size.height) {
                    this@drawWithContent.drawContent()
                }
            },
            contentScale = ContentScale.Fit,
        )
        // linija + ručka
        val xDp = with(LocalDensity.current) { (boxSize.width * frac).toDp() }
        Box(Modifier.offset(x = xDp - 1.dp).width(2.dp).fillMaxHeight().background(Color.White))
        Box(
            Modifier.offset(x = xDp - 22.dp).align(Alignment.CenterStart).size(44.dp)
                .clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.DragHandle, null, tint = Color.Black, modifier = Modifier.rotate(90f)) }

        Badge("A · PRIJE", Modifier.align(Alignment.TopStart).padding(12.dp))
        Badge("B · POSLIJE", Modifier.align(Alignment.TopEnd).padding(12.dp))
    }
}
