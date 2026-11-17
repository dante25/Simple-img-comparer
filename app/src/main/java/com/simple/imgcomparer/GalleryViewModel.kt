package com.simple.imgcomparer

import android.app.Application
import android.app.RecoverableSecurityException
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.media.MediaScannerConnection
import android.os.Environment
import com.simple.imgcomparer.pairing.*
import java.io.File
import android.provider.MediaStore
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object Gallery : Screen
    data class Viewer(val index: Int) : Screen
    data object Compare : Screen
    data object Pairs : Screen
}

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    private val resolver = app.contentResolver
    internal var repo: MediaRepository = MediaRepository(resolver)

    var all by mutableStateOf<List<Media>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var folder by mutableStateOf<String?>(null); private set      // null = sve
    var screen by mutableStateOf<Screen>(Screen.Gallery); private set
    var compareMode by mutableStateOf(false); private set

    /** Prva = glavna slika, ostale = slike za poređenje. */
    val selected = mutableStateListOf<Media>()

    /** Activity ovo čita i pokreće sistemski dijalog za brisanje. */
    var pendingDelete by mutableStateOf<IntentSender?>(null)

    private val foldersState = derivedStateOf { all.map { it.folder }.distinct().sorted() }
    val folders: List<String> get() = foldersState.value

    /** NNNN_anime + NNNN_real_k u istom folderu = jedna grupa (anime + varijante realizma). */
    class PairGroup(val anime: Media, val reals: List<Media>)
    private fun dirOf(m: Media) = m.path?.substringBeforeLast('/') ?: m.folder
    private fun groupKey(m: Media): String? {
        val n = (Namer.ANIME.matchEntire(m.name) ?: Namer.REAL.matchEntire(m.name))?.groupValues?.get(1) ?: return null
        return dirOf(m) + "#" + n
    }

    /** Indeks grupa se gradi JEDNOM po učitavanju galerije (O(N)), ne po pločici – inače se app zamrzne. */
    private class GroupIndex(val groups: Map<String, PairGroup>, val hidden: Set<Long>)
    private val index = derivedStateOf {
        val animes = HashMap<String, Media>(); val reals = HashMap<String, MutableList<Media>>()
        val only = pairsFolder
        for (m in all) {
            if (only == null || m.path == null || dirOf(m) != only) continue   // sve izvan definisanog foldera = obična galerija
            if (Namer.ANIME.matches(m.name)) groupKey(m)?.let { animes[it] = m }
            else if (Namer.REAL.matches(m.name)) groupKey(m)?.let { reals.getOrPut(it) { mutableListOf() }.add(m) }
        }
        val groups = HashMap<String, PairGroup>(animes.size); val hidden = HashSet<Long>()
        for ((k, a) in animes) {
            val rs = reals[k].orEmpty().sortedBy { Namer.REAL.matchEntire(it.name)!!.groupValues[2].toInt() }
            groups[k] = PairGroup(a, rs); rs.forEach { hidden += it.id }
        }
        GroupIndex(groups, hidden)
    }
    val pairGroups: Map<String, PairGroup> get() = index.value.groups
    fun groupOfAnime(m: Media): PairGroup? = if (Namer.ANIME.matches(m.name)) groupKey(m)?.let { index.value.groups[it] } else null

    /** Varijante realizma se u mreži skrivaju iza svoje anime slike. */
    private val visibleState = derivedStateOf {
        val hidden = index.value.hidden
        (folder?.let { f -> all.filter { it.folder == f } } ?: all).filterNot { it.id in hidden }
    }
    val visible: List<Media> get() = visibleState.value

    /** Redoslijed u klasičnom pregledu: slika, pa odmah njene varijante realizma. */
    private val viewerState = derivedStateOf {
        val groups = index.value.groups
        visibleState.value.flatMap { m -> listOf(m) + (if (Namer.ANIME.matches(m.name)) groupKey(m)?.let { groups[it] }?.reals.orEmpty() else emptyList()) }
    }
    val viewerList: List<Media> get() = viewerState.value
    fun groupFor(m: Media): PairGroup? = groupKey(m)?.let { index.value.groups[it] }?.takeIf { it.reals.isNotEmpty() }
    fun openMedia(m: Media) { open(viewerList.indexOfFirst { it.id == m.id }.coerceAtLeast(0)) }

    fun openGroup(g: PairGroup) {
        selected.clear(); selected.add(g.anime); selected.addAll(g.reals)
        compareMode = false
        if (selected.size >= 2) screen = Screen.Compare
    }

    fun refresh() {
        viewModelScope.launch {
            loading = true
            all = repo.loadImages()
            // izbaci selektirane koje više ne postoje
            selected.removeAll { s -> all.none { it.id == s.id } }
            loading = false
        }
    }

    fun selectFolder(f: String?) { folder = f }

    fun open(index: Int) { screen = Screen.Viewer(index) }
    fun goCompare() { if (selected.size >= 2) screen = Screen.Compare }
    fun openPairs() { screen = Screen.Pairs }
    fun back(): Boolean = when {
        screen is Screen.Compare && !compareMode -> { selected.clear(); screen = Screen.Gallery; true }
        screen !is Screen.Gallery -> { screen = Screen.Gallery; true }
        compareMode -> { exitCompareMode(); true }
        else -> false
    }

    fun enterCompareMode() { compareMode = true }
    fun exitCompareMode() { compareMode = false; selected.clear() }

    fun toggle(m: Media) {
        val i = selected.indexOfFirst { it.id == m.id }
        if (i >= 0) selected.removeAt(i) else selected.add(m)
    }

    fun makeMain(m: Media) {
        val i = selected.indexOfFirst { it.id == m.id }
        if (i > 0) { selected.removeAt(i); selected.add(0, m) }
    }

    /** Prave se samo B slike brišu iz compare moda; A (selected[0]) se nikad ne briše odavde. */
    fun deleteBs() = delete(selected.drop(1))

    /** true = brisanje bez sistemskog dijaloga (dozvola "svi fajlovi" ili stari Android). */
    internal var silentDelete: () -> Boolean = {
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())
    }

    private var pendingIds: Set<Long> = emptySet()
    fun commitPendingDelete() = onDeleted(pendingIds)

    fun delete(items: List<Media>) {
        if (items.isEmpty()) return
        val uris = items.map { it.uri }
        val ids = items.map { it.id }.toSet()
        viewModelScope.launch {
            if (silentDelete()) {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { uris.forEach { resolver.delete(it, null, null) } }.isSuccess
                }
                if (ok) { onDeleted(ids); return@launch }
            }
            pendingIds = ids
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pendingDelete = try {
                    MediaStore.createDeleteRequest(resolver, uris).intentSender
                } catch (e: Exception) { null }
            } else {
                try {
                    withContext(Dispatchers.IO) { uris.forEach { resolver.delete(it, null, null) } }
                    onDeleted(ids)
                } catch (e: SecurityException) {
                    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                        pendingDelete = e.userAction.actionIntent.intentSender
                    }
                }
            }
        }
    }

    fun onDeleted(ids: Set<Long>) {
        pendingDelete = null
        selected.removeAll { it.id in ids }
        if (screen is Screen.Compare && selected.size < 2) screen = Screen.Gallery
        refresh()
    }

    // ───────────── automatsko uparivanje anime ↔ realizam ─────────────
    private val prefs = app.getSharedPreferences("prefs", android.content.Context.MODE_PRIVATE)
    private val scanner = Scanner(File(app.filesDir, "fp_cache.json"))
    private val renamer = Renamer(File(app.filesDir, "rename_log.json"))

    var pairsFolder by mutableStateOf(prefs.getString("pairsFolder", null)); private set
    var autoApply by mutableStateOf(prefs.getBoolean("autoApply", true)); private set
    var scanning by mutableStateOf(false); private set
    var scanProgress by mutableStateOf(0 to 0); private set
    var pairing by mutableStateOf<PairingResult?>(null); private set
    var pairsMessage by mutableStateOf<String?>(null); private set
    var canUndo by mutableStateOf(renamer.hasUndo()); private set

    /** Folderi (apsolutne putanje) u kojima ima slika – za izbor foldera. */
    private val folderPathsState = derivedStateOf { all.mapNotNull { it.path?.substringBeforeLast('/', "")?.ifEmpty { null } }.distinct().sorted() }
    val folderPaths: List<String> get() = folderPathsState.value

    /** Samo postavi folder (bez skeniranja). Grupisanje u galeriji važi isključivo za ovaj folder. */
    internal fun usePairsFolder(path: String) { prefs.edit().putString("pairsFolder", path).apply(); pairsFolder = path; pairing = null }
    fun choosePairsFolder(path: String) { usePairsFolder(path); scanPairs() }
    fun toggleAutoApply(on: Boolean) { prefs.edit().putBoolean("autoApply", on).apply(); autoApply = on }

    fun manualScan() { pairsMessage = null; scanPairs() }

    fun autoScan() { if (pairsFolder != null && !scanning) scanPairs() }

    fun scanPairs() {
        val dir = pairsFolder?.let(::File)?.takeIf { it.isDirectory } ?: run { pairsMessage = "Folder ne postoji"; return }
        if (scanning) return
        viewModelScope.launch {
            scanning = true
            try {
                val items = scanner.scan(dir) { d, t -> scanProgress = d to t }
                val r = withContext(Dispatchers.Default) { Pairer.pair(items) }
                pairing = r
                if (autoApply) applyGroups(r.groups.filter { it.confidence != Confidence.REVIEW }, quiet = true)
            } catch (e: Exception) { pairsMessage = "Skeniranje nije uspjelo: ${e.message}" }
            finally { scanning = false }
        }
    }

    fun applyGroups(groups: List<Group>, quiet: Boolean = false) {
        val dir = pairsFolder?.let(::File) ?: return
        viewModelScope.launch {
            val ops = Namer.plan(groups, dir.listFiles()?.toList().orEmpty())
            if (ops.isEmpty()) { if (!quiet) pairsMessage = "Nema ničega za preimenovati"; return@launch }
            val out = withContext(Dispatchers.IO) { renamer.apply(ops) }
            if (out.error != null) { pairsMessage = out.error; return@launch }
            canUndo = renamer.hasUndo()
            pairsMessage = "Preimenovano ${out.done} fajlova"
            MediaScannerConnection.scanFile(getApplication(), ops.flatMap { listOf(it.from.path, it.to.path) }.toTypedArray(), null) { _, _ -> refresh() }
            refresh()
            pairing = null; scanning = false; scanPairs()
        }
    }

    fun applyOne(g: Group) = applyGroups(listOf(g))

    /** Za grupe "provjeri": prebaci ulogu izvora na sljedeći član. */
    fun swapSource(g: Group) {
        val r = pairing ?: return
        val m = g.members; val next = m[(m.indexOfFirst { it.path == g.source.path } + 1) % m.size]
        val swapped = Group(next, m.filter { it !== next }.sortedWith(compareBy({ it.modified }, { it.name })), Confidence.REVIEW, g.note)
        pairing = PairingResult(r.groups.map { if (it === g) swapped else it }, r.orphans, r.ungrouped)
    }

    fun undoLast() {
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { renamer.undoLast() }
            canUndo = renamer.hasUndo()
            pairsMessage = "Vraćeno $n fajlova"
            val old = pairsFolder
            // da auto-primjena ne vrati odmah isto, privremeno je ugasi za ovaj sken
            val prev = autoApply; autoApply = false
            MediaScannerConnection.scanFile(getApplication(), old?.let { File(it).listFiles()?.map { f -> f.path }?.toTypedArray() } ?: emptyArray(), null) { _, _ -> refresh() }
            refresh(); pairing = null; scanPairs()
            // vrati prethodno stanje prekidača kad sken završi
            launch { while (scanning) kotlinx.coroutines.delay(100); autoApply = prev }
        }
    }
}
