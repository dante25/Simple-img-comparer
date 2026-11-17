package com.simple.imgcomparer.pairing

/** Sve što pairer zna o jednom fajlu. Čista logika – bez Androida. */
class Item(
    val path: String,
    val name: String,
    val sha: String?,
    val meta: PromptInfo?,
    /** 24x32 luminanca, normalizirana (srednja 0, std 1). */
    val sig: FloatArray,
    val aspect: Float,
    /** "anime-ness" unutar para: udio jakih ivica − 0.1·udio srednjih gradijenata. */
    val score: Float,
    val modified: Long,
)

enum class Confidence { EXACT, VISUAL, REVIEW }

class Group(val source: Item, val results: List<Item>, val confidence: Confidence, val note: String = "") {
    val members get() = listOf(source) + results
}

class PairingResult(
    val groups: List<Group>,
    /** Rezultati čiji izvor nije u folderu (ili grupe koje se ne mogu pouzdano usmjeriti). */
    val orphans: List<List<Item>>,
    val ungrouped: List<Item>,
)

object Pairer {
    const val VISUAL_MIN_NCC = 0.72f
    const val ASPECT_TOL = 0.05f
    const val DIRECTION_MARGIN = 0.0115f

    fun ncc(a: FloatArray, b: FloatArray): Float {
        var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s / a.size
    }

    fun pair(items: List<Item>): PairingResult {
        val n = items.size
        val bySha = HashMap<String, Int>()
        items.forEachIndexed { i, it -> it.sha?.let { s -> bySha.putIfAbsent(s, i) } }

        val parent = IntArray(n) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; var c = x; while (parent[c] != r) { val nx = parent[c]; parent[c] = r; c = nx }; return r }
        val hashSource = arrayOfNulls<Int>(n)          // result -> source index (potvrđeno hashom)
        val orphanMeta = BooleanArray(n)                // ima metadata, ali izvor nije u folderu
        val confirmed = HashMap<Int, MutableSet<Int>>() // root -> potvrđeni izvori

        // 1) egzaktno: hash iz metadata → izvor u folderu
        for (i in 0 until n) {
            val meta = items[i].meta ?: continue
            val cands = meta.sourceHashes.mapNotNull { bySha[it] }.filter { it != i }
            if (cands.isEmpty()) { orphanMeta[i] = true; continue }
            val s = cands.maxBy { ncc(items[i].sig, items[it].sig) }
            hashSource[i] = s
            val a = find(i); val b = find(s)
            if (a != b) parent[a] = b
            confirmed.getOrPut(find(s)) { mutableSetOf() }.add(s)
        }
        fun merge(a: Int, b: Int): Boolean {
            val ra = find(a); val rb = find(b); if (ra == rb) return true
            val ca = confirmed[ra].orEmpty(); val cb = confirmed[rb].orEmpty()
            if (ca.isNotEmpty() && cb.isNotEmpty() && ca != cb) return false // dva različita potvrđena izvora – ne spajaj
            parent[ra] = rb
            confirmed.getOrPut(rb) { mutableSetOf() }.addAll(ca)
            return true
        }

        // 2) vizuelno: samo fajlovi bez metadata traže pandane
        val visualJoined = BooleanArray(n)
        for (a in 0 until n) {
            if (items[a].meta != null) continue
            for (b in 0 until n) {
                if (a == b) continue
                if (kotlin.math.abs(items[a].aspect - items[b].aspect) >= ASPECT_TOL) continue
                if (ncc(items[a].sig, items[b].sig) < VISUAL_MIN_NCC) continue
                if (merge(a, b)) { visualJoined[a] = true; visualJoined[b] = true }
            }
        }

        // 3) komponente → grupe
        val comps = (0 until n).groupBy { find(it) }.values
        val groups = mutableListOf<Group>(); val orphans = mutableListOf<List<Item>>(); val single = mutableListOf<Item>()
        for (c in comps) {
            if (c.size == 1) { single += items[c[0]]; continue }
            val confirmedSrc = c.filter { i -> c.any { hashSource[it] == i } }.toSet()
            if (confirmedSrc.size == 1) {
                val s = confirmedSrc.first()
                val rs = c.filter { it != s }
                val allExact = rs.all { hashSource[it] == s }
                groups += Group(
                    items[s], rs.map { items[it] }.sortedWith(byTime),
                    if (allExact) Confidence.EXACT else Confidence.VISUAL,
                    if (allExact) "izvor potvrđen metadata (hash)" else "izvor potvrđen metadata; neke varijante uparene vizuelno",
                )
                continue
            }
            if (c.any { orphanMeta[it] }) { orphans += c.map { items[it] }; continue }
            // čisto vizuelno: smjer iz ivica + vremena
            val sorted = c.sortedByDescending { items[it].score }
            val src = items[sorted[0]]; val margin = src.score - items[sorted[1]].score
            val rs = c.filter { it != sorted[0] }.map { items[it] }
            val dateContradicts = rs.all { it.modified > 0 && src.modified > it.modified }
            val conf = if (margin >= DIRECTION_MARGIN && !dateContradicts) Confidence.VISUAL else Confidence.REVIEW
            val note = when {
                dateContradicts -> "izvor je noviji od svih varijanti – provjeri smjer"
                margin < DIRECTION_MARGIN -> "nije jasno koja je anime – provjeri smjer"
                else -> "upareno vizuelno"
            }
            groups += Group(src, rs.sortedWith(byTime), conf, note)
        }
        groups.sortWith(compareBy({ it.source.modified }, { it.source.name }))
        return PairingResult(groups, orphans, single)
    }

    private val byTime = compareBy<Item>({ it.modified }, { it.name })
}
