package com.simple.imgcomparer.pairing

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class RenameOp(val from: File, val to: File)

/** Shema: 0001_anime.png, 0001_real_1.png, 0001_real_2.png … */
object Namer {
    val ANIME = Regex("^(\\d+)_anime\\.[^.]+$", RegexOption.IGNORE_CASE)
    val REAL = Regex("^(\\d+)_real_(\\d+)\\.[^.]+$", RegexOption.IGNORE_CASE)

    fun plan(groups: List<Group>, folderFiles: Collection<File>): List<RenameOp> {
        val taken = folderFiles.map { it.name.lowercase() }.toMutableSet()
        var next = (folderFiles.mapNotNull { ANIME.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1
        val ops = mutableListOf<RenameOp>()
        fun target(dir: File, base: String, ext: String): File {
            var name = "$base.$ext"
            var k = 2
            while (name.lowercase() in taken) name = "${base}_$k.$ext"
            taken += name.lowercase(); return File(dir, name)
        }
        for (g in groups) {
            val src = File(g.source.path); val dir = src.parentFile ?: continue
            val m = ANIME.matchEntire(src.name)
            val num: String
            if (m != null) num = m.groupValues[1]
            else {
                num = "%04d".format(next++)
                ops += RenameOp(src, target(dir, "${num}_anime", src.extension.lowercase()))
            }
            var idx = g.results.mapNotNull { REAL.matchEntire(it.name)?.takeIf { r -> r.groupValues[1] == num }?.groupValues?.get(2)?.toIntOrNull() }
                .maxOrNull() ?: 0
            for (r in g.results) {
                val f = File(r.path)
                if (REAL.matchEntire(f.name)?.groupValues?.get(1) == num) continue
                idx++
                ops += RenameOp(f, target(dir, "${num}_real_$idx", f.extension.lowercase()))
            }
        }
        return ops
    }
}

/** Preimenuje sve ili ništa i vodi log za "Vrati zadnje". */
class Renamer(private val logFile: File) {
    class Outcome(val done: Int, val error: String?)

    fun apply(ops: List<RenameOp>): Outcome {
        val done = mutableListOf<RenameOp>()
        for (op in ops) {
            val ok = !op.to.exists() && op.from.renameTo(op.to)
            if (!ok) {
                done.asReversed().forEach { it.to.renameTo(it.from) }
                return Outcome(0, "Neuspjelo: ${op.from.name} → ${op.to.name}")
            }
            done += op
        }
        if (done.isNotEmpty()) appendLog(done)
        return Outcome(done.size, null)
    }

    fun hasUndo() = readLog().length() > 0

    /** Vraća posljednju seriju; vraća broj vraćenih fajlova. */
    fun undoLast(): Int {
        val log = readLog(); if (log.length() == 0) return 0
        val batch = log.getJSONObject(log.length() - 1).getJSONArray("ops")
        var n = 0
        for (i in batch.length() - 1 downTo 0) {
            val o = batch.getJSONObject(i); val from = File(o.getString("from")); val to = File(o.getString("to"))
            if (to.exists() && !from.exists() && to.renameTo(from)) n++
        }
        val rest = JSONArray(); for (i in 0 until log.length() - 1) rest.put(log.get(i))
        logFile.writeText(rest.toString())
        return n
    }

    private fun readLog(): JSONArray = try { JSONArray(logFile.readText()) } catch (_: Exception) { JSONArray() }
    private fun appendLog(ops: List<RenameOp>) {
        val log = readLog()
        log.put(JSONObject().put("t", System.currentTimeMillis()).put("ops", JSONArray(ops.map {
            JSONObject().put("from", it.from.path).put("to", it.to.path)
        })))
        logFile.writeText(log.toString())
    }
}
