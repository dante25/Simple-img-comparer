package com.simple.imgcomparer.pairing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Skenira jedan folder: listFiles + keširani potpisi, pa Pairer. Samo nove/izmijenjene slike se dekodiraju. */
class Scanner(private val cacheFile: File?) {

    private val exts = setOf("png", "jpg", "jpeg", "webp")

    suspend fun scan(dir: File, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): List<Item> =
        withContext(Dispatchers.Default) {
            val files = dir.listFiles { f -> f.isFile && f.extension.lowercase() in exts }?.sortedBy { it.name }.orEmpty()
            val cache = loadCache()
            val done = AtomicInteger()
            val gate = Semaphore(2) // 4096px dekodiranje je memorijski skupo
            val feats = coroutineScope {
                files.map { f ->
                    async {
                        gate.withPermit {
                            val key = "${f.path}|${f.length()}|${f.lastModified()}"
                            val r = cache[key] ?: Fingerprinter.compute(f)?.also { cache[key] = it }
                            onProgress(done.incrementAndGet(), files.size)
                            f to (key to r)
                        }
                    }
                }.awaitAll()
            }
            // sha računamo samo ako se ijedan fajl poziva na ulazne slike
            if (feats.any { it.second.second?.meta != null }) {
                coroutineScope {
                    feats.map { (f, kr) ->
                        async {
                            gate.withPermit {
                                val (key, r) = kr
                                if (r != null && r.sha == null) cache[key] = Features(r.sig, r.aspect, r.score, r.meta, Fingerprinter.sha256(f))
                            }
                        }
                    }.awaitAll()
                }
            }
            saveCache(cache, files.map { "${it.path}|${it.length()}|${it.lastModified()}" }.toSet())
            feats.mapNotNull { (f, kr) ->
                val r = cache[kr.first] ?: return@mapNotNull null
                Item(f.path, f.name, r.sha, r.meta, r.sig, r.aspect, r.score, f.lastModified())
            }
        }

    private fun loadCache(): MutableMap<String, Features> {
        val out = HashMap<String, Features>()
        val f = cacheFile?.takeIf { it.exists() } ?: return out
        try {
            val o = JSONObject(f.readText())
            for (k in o.keys()) {
                val e = o.getJSONObject(k)
                val sa = e.getJSONArray("s"); val sig = FloatArray(sa.length()) { sa.getDouble(it).toFloat() }
                val meta = if (e.optBoolean("m")) PromptInfo(e.strSet("src"), e.strSet("ref")) else null
                out[k] = Features(sig, e.getDouble("a").toFloat(), e.getDouble("c").toFloat(), meta, e.optString("h").ifEmpty { null })
            }
        } catch (_: Exception) { out.clear() }
        return out
    }

    private fun saveCache(cache: Map<String, Features>, keep: Set<String>) {
        val file = cacheFile ?: return
        try {
            val o = JSONObject()
            for ((k, v) in cache) {
                if (k !in keep) continue
                o.put(k, JSONObject().put("s", JSONArray(v.sig.map { it.toDouble() })).put("a", v.aspect.toDouble())
                    .put("c", v.score.toDouble()).put("m", v.meta != null)
                    .put("src", JSONArray(v.meta?.sourceHashes.orEmpty().toList()))
                    .put("ref", JSONArray(v.meta?.refHashes.orEmpty().toList()))
                    .put("h", v.sha ?: ""))
            }
            file.writeText(o.toString())
        } catch (_: Exception) { }
    }

    private fun JSONObject.strSet(k: String): Set<String> {
        val a = optJSONArray(k) ?: return emptySet(); return (0 until a.length()).map { a.getString(it) }.toSet()
    }
}
