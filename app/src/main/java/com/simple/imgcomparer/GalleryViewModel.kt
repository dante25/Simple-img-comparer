package com.simple.imgcomparer

import android.app.Application
import android.app.RecoverableSecurityException
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
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
}

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    private val resolver = app.contentResolver
    private val repo = MediaRepository(resolver)

    var all by mutableStateOf<List<Media>>(emptyList()); private set
    var loading by mutableStateOf(false); private set
    var folder by mutableStateOf<String?>(null); private set      // null = sve
    var screen by mutableStateOf<Screen>(Screen.Gallery); private set
    var compareMode by mutableStateOf(false); private set

    /** Prva = glavna slika, ostale = slike za poređenje. */
    val selected = mutableStateListOf<Media>()

    /** Activity ovo čita i pokreće sistemski dijalog za brisanje. */
    var pendingDelete by mutableStateOf<IntentSender?>(null)

    val folders: List<String> get() = all.map { it.folder }.distinct().sorted()
    val visible: List<Media> get() = folder?.let { f -> all.filter { it.folder == f } } ?: all

    fun refresh() {
        viewModelScope.launch {
            loading = true
            all = repo.loadImages()
            // izbaci selektirane koje više ne postoje
            selected.removeAll { s -> all.none { it.id == s.id } }
            loading = false
        }
    }

    fun setFolder(f: String?) { folder = f }

    fun open(index: Int) { screen = Screen.Viewer(index) }
    fun goCompare() { if (selected.size >= 2) screen = Screen.Compare }
    fun back(): Boolean = when {
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

    fun deleteSelected() = delete(selected.map { it.uri })

    fun delete(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pendingDelete = MediaStore.createDeleteRequest(resolver, uris).intentSender
            } else {
                try {
                    withContext(Dispatchers.IO) { uris.forEach { resolver.delete(it, null, null) } }
                    onDeleted()
                } catch (e: SecurityException) {
                    if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                        pendingDelete = e.userAction.actionIntent.intentSender
                    }
                }
            }
        }
    }

    fun onDeleted() {
        pendingDelete = null
        selected.clear()
        if (screen is Screen.Compare) screen = Screen.Gallery
        refresh()
    }
}
