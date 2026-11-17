package com.simple.imgcomparer

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Media(
    val id: Long,
    val uri: Uri,
    val name: String,
    val folder: String,
)

/** Skenira slike jednim MediaStore upitom (bez hodanja po fajl-sistemu) – zato je brzo. */
open class MediaRepository(private val resolver: ContentResolver) {

    open suspend fun loadImages(): List<Media> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val out = ArrayList<Media>()
        resolver.query(collection, projection, null, null, sort)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val folderCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            out.ensureCapacity(c.count)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                out += Media(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    name = c.getString(nameCol) ?: "",
                    folder = c.getString(folderCol) ?: "Ostalo",
                )
            }
        }
        out
    }
}
