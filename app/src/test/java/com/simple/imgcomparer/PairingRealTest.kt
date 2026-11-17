package com.simple.imgcomparer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.simple.imgcomparer.pairing.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Radi nad stvarnim slikama iz $SAMPLES_DIR (nisu u repou). Bez njih se testovi preskaču. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PairingRealTest {
    @get:Rule val tmp = TemporaryFolder()

    private val truth = mapOf(
        "189321" to setOf("189970"), "189907" to setOf("189908"), "189337" to setOf("190326"),
        "189316" to setOf("190318", "190327", "190328"), "189315" to setOf("190335", "190342"),
        "189274" to setOf("190448"), "189276" to setOf("190447"), "189273" to setOf("190363"),
        "189291" to setOf("190359", "190361"), "189292" to setOf("190348"),
    )

    private fun samples(): File? = System.getenv("SAMPLES_DIR")?.let(::File)?.takeIf { it.isDirectory }

    private fun copyWithOrder(from: File, strip: Boolean): File {
        val dir = tmp.newFolder()
        from.listFiles()!!.sortedBy { it.name }.forEach { f ->
            val out = File(dir, f.name)
            if (strip) { // re-encode = bez PNG metadata, drugačiji hash
                val b = BitmapFactory.decodeFile(f.path)
                out.outputStream().use { b.compress(if (f.extension == "png") Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it) }
            } else f.copyTo(out)
            out.setLastModified(1_700_000_000_000L + f.name.substringBefore('.').toLong() * 1000) // rezultat je uvijek noviji
        }
        return dir
    }

    private fun id(i: Item) = i.name.substringBefore('.')
    private fun scan(dir: File) = runBlocking { Scanner(null).scan(dir) }

    @Test fun with_metadata_exact_and_visual_groups_are_right() {
        val src = samples(); assumeNotNull(src)
        val dir = copyWithOrder(src!!, strip = false)
        val r = Pairer.pair(scan(dir))
        val got = r.groups.associate { id(it.source) to it.results.map(::id).toSet() }
        assertEquals(truth, got)
        // egzaktni gdje postoji hash, ostalo vizuelno; ništa za pregled
        assertTrue(r.groups.none { it.confidence == Confidence.REVIEW })
        assertEquals(Confidence.EXACT, r.groups.first { id(it.source) == "189321" }.confidence)
        assertEquals(Confidence.VISUAL, r.groups.first { id(it.source) == "189316" }.confidence)
        // rezultati čiji izvor nije u folderu se NE vežu ni za šta
        val grouped = r.groups.flatMap { it.members }.map(::id).toSet()
        for (orphan in listOf("190440", "190365", "190344", "190347", "189325", "189320")) assertFalse("$orphan ne smije u grupu", orphan in grouped)
        assertTrue(r.orphans.any { o -> o.map(::id).toSet() == setOf("190344", "190347") })
    }

    @Test fun without_metadata_visual_pairing_and_direction_still_right() {
        val src = samples(); assumeNotNull(src)
        val dir = copyWithOrder(src!!, strip = true)
        val r = Pairer.pair(scan(dir))
        val bySource = r.groups.associate { id(it.source) to it }
        for ((s, rs) in truth) {
            val g = bySource[s]; assertNotNull("nema grupe za izvor $s: ${r.groups.map { id(it.source) to it.results.map(::id) }}", g)
            assertEquals("rezultati za $s", rs, g!!.results.map(::id).toSet())
        }
        // grupa od dva rezultata (nema anime) ne smije biti automatski potvrđena
        val bad = r.groups.filter { id(it.source) !in truth.keys }
        for (g in bad) assertEquals("pogrešna grupa ${g.members.map(::id)}", Confidence.REVIEW, g.confidence)
        for (n in listOf("189325", "189320", "190440", "190365")) assertTrue(n in r.ungrouped.map(::id))
    }

    @Test fun renaming_follows_scheme_is_idempotent_and_undoable() {
        val src = samples(); assumeNotNull(src)
        val dir = copyWithOrder(src!!, strip = false)
        val log = File(tmp.newFolder(), "log.json")
        val r = Pairer.pair(scan(dir))
        val ops = Namer.plan(r.groups, dir.listFiles()!!.toList())
        assertEquals(24, ops.size) // 10 izvora + 14 rezultata
        val out = Renamer(log).apply(ops)
        assertNull(out.error); assertEquals(24, out.done)
        val names = dir.list()!!.toSet()
        // grupa 189316 (3 varijante) → 0004? broj zavisi od redoslijeda po vremenu; provjeri oblik
        assertEquals(10, names.count { Namer.ANIME.matches(it) })
        assertEquals(14, names.count { Namer.REAL.matches(it) })
        val four = names.filter { it.startsWith("0004_") }.sorted()
        assertTrue(four.toString(), four.size in 2..4)
        // ponovni sken ne predlaže ništa novo
        val again = Pairer.pair(scan(dir))
        assertTrue(Namer.plan(again.groups, dir.listFiles()!!.toList()).isEmpty())
        // vraćanje
        assertEquals(24, Renamer(log).undoLast())
        assertEquals(src.list()!!.toSet(), dir.list()!!.toSet())
    }
}
