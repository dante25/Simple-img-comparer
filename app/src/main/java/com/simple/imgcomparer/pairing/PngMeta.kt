package com.simple.imgcomparer.pairing

import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.InflaterInputStream

/** Šta ComfyUI/RunningHub upiše u PNG: koje su slike učitane kao ulaz. */
data class PromptInfo(
    /** SHA-256 ulaznih slika (LoadImage čvorovi koji NISU "referenca"). */
    val sourceHashes: Set<String>,
    /** SHA-256 referenci (npr. "EDIT slika 2 | druga referenca") – ne smatraju se izvorom. */
    val refHashes: Set<String>,
)

object PngMeta {
    private val PNG_SIG = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private val HASH = Regex("^([0-9a-fA-F]{64})\\.[A-Za-z0-9]+$")
    private val REF_TITLE = Regex("(?i)(referenc|picture\\s*2|slika\\s*2|\\bref\\b)")

    /** null = nije PNG ili nema ComfyUI `prompt`. Čita samo chunk-ove, ne dekodira sliku. */
    fun read(file: File): PromptInfo? {
        if (!file.name.endsWith(".png", ignoreCase = true)) return null
        return try {
            file.inputStream().buffered().use { readPrompt(it) }?.let(::parsePrompt)
        } catch (e: Exception) { null }
    }

    private fun readPrompt(raw: InputStream): String? {
        val inp = DataInputStream(raw)
        val sig = ByteArray(8); inp.readFully(sig)
        if (!sig.contentEquals(PNG_SIG)) return null
        while (true) {
            val len = inp.readInt()
            val type = ByteArray(4).also { inp.readFully(it) }.toString(Charsets.ISO_8859_1)
            if (type == "IEND") return null
            if (type == "tEXt" || type == "iTXt" || type == "zTXt") {
                if (len > 8_000_000) { inp.skipFully(len + 4L); continue }
                val data = ByteArray(len).also { inp.readFully(it) }
                inp.skipFully(4) // CRC
                val k = data.indexOf(0)
                if (k < 0 || String(data, 0, k, Charsets.ISO_8859_1) != "prompt") continue
                return when (type) {
                    "tEXt" -> String(data, k + 1, len - k - 1, Charsets.UTF_8)
                    "zTXt" -> InflaterInputStream(data.inputStream(k + 2, len - k - 2)).readBytes().toString(Charsets.UTF_8)
                    else -> { // iTXt: flag, method, lang\0, translated\0, text
                        val compressed = data[k + 1].toInt() != 0
                        var p = k + 3
                        repeat(2) { while (p < len && data[p].toInt() != 0) p++; p++ }
                        if (compressed) InflaterInputStream(data.inputStream(p, len - p)).readBytes().toString(Charsets.UTF_8)
                        else String(data, p, len - p, Charsets.UTF_8)
                    }
                }
            } else inp.skipFully(len + 4L)
        }
    }

    private fun ByteArray.inputStream(off: Int, len: Int) = java.io.ByteArrayInputStream(this, off, len)
    private fun DataInputStream.skipFully(n: Long) {
        var left = n
        while (left > 0) { val s = skip(left); if (s <= 0) { if (read() < 0) throw java.io.EOFException(); left-- } else left -= s }
    }

    internal fun parsePrompt(json: String): PromptInfo? {
        val root = JSONObject(json)
        val src = mutableSetOf<String>(); val ref = mutableSetOf<String>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val node = root.optJSONObject(keys.next()) ?: continue
            if (node.optString("class_type") != "LoadImage") continue
            val image = node.optJSONObject("inputs")?.optString("image") ?: continue
            val h = HASH.find(image)?.groupValues?.get(1)?.lowercase() ?: continue
            val title = node.optJSONObject("_meta")?.optString("title").orEmpty()
            if (REF_TITLE.containsMatchIn(title)) ref += h else src += h
        }
        return PromptInfo(src, ref)
    }
}
