package com.simple.imgcomparer

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simple.imgcomparer.pairing.*
import java.io.File

private fun Item.media() = Media(-1, Uri.fromFile(File(path)), name, "", path)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PairsScreen(vm: GalleryViewModel) {
    var pick by remember { mutableStateOf(false) }
    val res = vm.pairing
    val confident = res?.groups?.filter { it.confidence != Confidence.REVIEW }.orEmpty()
    val review = res?.groups?.filter { it.confidence == Confidence.REVIEW }.orEmpty()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Parovi anime ↔ realizam", fontWeight = FontWeight.SemiBold) },
            navigationIcon = { IconButton({ vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg, titleContentColor = Color.White, navigationIconContentColor = Color.White),
            windowInsets = WindowInsets.statusBars,
        )
        Surface(Modifier.padding(horizontal = 12.dp), shape = RoundedCornerShape(16.dp), color = Surface1) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Folder", fontSize = 12.sp, color = Color.Gray)
                Text(vm.pairsFolder ?: "nije odabran", maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("folderPath"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton({ pick = true }) { Text("Odaberi folder") }
                    Button({ vm.manualScan() }, enabled = vm.pairsFolder != null && !vm.scanning, modifier = Modifier.testTag("scan")) { Text("Skeniraj") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(vm.autoApply, { vm.toggleAutoApply(it) }, modifier = Modifier.testTag("auto"))
                    Spacer(Modifier.width(10.dp))
                    Text("Automatski preimenuj sigurne parove", fontSize = 14.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (vm.scanning) {
            val (d, t) = vm.scanProgress
            LinearProgressIndicator(progress = { if (t == 0) 0f else d.toFloat() / t }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            Text("Skeniram $d / $t", Modifier.padding(12.dp), fontSize = 13.sp, color = Color.Gray)
        } else if (res != null) {
            Text(
                "${res.groups.size} grupa • ${review.size} za provjeru • ${res.orphans.sumOf { it.size }} rezultata bez izvora • ${res.ungrouped.size} samostalnih",
                Modifier.padding(horizontal = 14.dp, vertical = 4.dp).testTag("summary"), fontSize = 13.sp, color = Color.Gray,
            )
        }
        vm.pairsMessage?.let { Text(it, Modifier.padding(horizontal = 14.dp).testTag("message"), color = Accent, fontSize = 13.sp) }

        val plan = remember(res, vm.pairsFolder) {
            res?.let { r -> Namer.plan(r.groups, vm.pairsFolder?.let { File(it).listFiles()?.toList() }.orEmpty()) }.orEmpty()
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (res != null) {
                items(res.groups, key = { it.source.path }) { g -> GroupCard(vm, g, plan) }
                if (res.orphans.isNotEmpty()) item {
                    Text("Rezultati bez izvora u folderu", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    res.orphans.forEach { o ->
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                            items(o) { Thumb(it.media(), Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), 128) }
                        }
                    }
                }
            }
        }
        Surface(color = Surface1, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ vm.applyGroups(confident) }, enabled = confident.isNotEmpty() && !vm.scanning, modifier = Modifier.weight(1f).testTag("applyAll")) {
                    Text("Primijeni sigurne (${confident.size})")
                }
                OutlinedButton({ vm.undoLast() }, enabled = vm.canUndo, modifier = Modifier.testTag("undo")) { Text("Vrati zadnje") }
            }
        }
    }
    if (pick) AlertDialog(
        onDismissRequest = { pick = false },
        title = { Text("Odaberi folder") },
        text = {
            LazyColumn { items(vm.folderPaths) { path ->
                Text(path, Modifier.fillMaxWidth().clickable { vm.choosePairsFolder(path); pick = false }.padding(vertical = 10.dp), fontSize = 13.sp)
            } }
        },
        confirmButton = { TextButton({ pick = false }) { Text("Zatvori") } },
    )
}

@Composable
private fun GroupCard(vm: GalleryViewModel, g: Group, plan: List<RenameOp>) {
    val paths = g.members.map { it.path }.toSet()
    val names = plan.filter { it.from.path in paths }.joinToString("\n") { "${it.from.name} → ${it.to.name}" }
    Surface(shape = RoundedCornerShape(16.dp), color = Surface1, modifier = Modifier.fillMaxWidth().testTag("group")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(10.dp))) {
                    Thumb(g.source.media(), Modifier.fillMaxSize(), 160); Badge("A", Modifier.align(Alignment.BottomStart).padding(3.dp))
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(g.results.size) { i ->
                        Box(Modifier.size(72.dp).clip(RoundedCornerShape(10.dp))) {
                            Thumb(g.results[i].media(), Modifier.fillMaxSize(), 160); Badge("B${i + 1}", Modifier.align(Alignment.BottomStart).padding(3.dp))
                        }
                    }
                }
            }
            val tag = when (g.confidence) {
                Confidence.EXACT -> "Tačno (metadata)"; Confidence.VISUAL -> "Vizuelno"; Confidence.REVIEW -> "Provjeri"
            }
            Text("$tag · ${g.note}", fontSize = 12.sp, color = if (g.confidence == Confidence.REVIEW) Color(0xFFFFB86B) else Color.Gray)
            if (names.isNotEmpty()) Text(names, fontSize = 11.sp, color = Color.Gray)
            if (g.confidence == Confidence.REVIEW) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ vm.swapSource(g) }, modifier = Modifier.testTag("swap")) { Text("Zamijeni anime") }
                Button({ vm.applyOne(g) }, modifier = Modifier.testTag("applyOne")) { Text("Primijeni") }
            }
        }
    }
}
