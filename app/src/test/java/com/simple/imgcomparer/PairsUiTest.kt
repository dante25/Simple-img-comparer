package com.simple.imgcomparer

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.simple.imgcomparer.pairing.*
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PairsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var app: Application
    private lateinit var vm: GalleryViewModel

    @Before fun setUp() { app = ApplicationProvider.getApplicationContext(); vm = GalleryViewModel(app) }

    private fun until(timeoutMs: Long = 60_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) fail("timeout")
            Thread.sleep(30)
        }
    }

    private fun png(file: File, color: Int) {
        val b = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888); Canvas(b).drawColor(color)
        file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); v.draw(Canvas(b))
        File("build/screenshots").apply { mkdirs() }.let { d -> File(d, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    // ───── galerija: grupe ─────
    private fun setMedia(dir: File, names: List<String>) {
        val list = names.mapIndexed { i, n ->
            val f = File(dir, n).also { png(it, AColor.rgb(40 * i % 255, 90, 160)) }
            Media(i.toLong(), Uri.fromFile(f), n, dir.name, f.path)
        }
        vm.repo = object : MediaRepository(app.contentResolver) { override suspend fun loadImages() = list }
        vm.refresh(); rule.waitForIdle()
    }

    @Test fun gallery_collapses_group_shows_badge_and_opens_compare() {
        setMedia(tmp.newFolder("a"), listOf("0001_anime.png", "0001_real_1.png", "0001_real_2.png", "other.png"))
        rule.setContent { AppTheme { GalleryScreen(vm) } }
        rule.waitForIdle()
        assertEquals(listOf("0001_anime.png", "other.png"), vm.visible.map { it.name })   // varijante skrivene
        rule.onNodeWithText("+2").assertIsDisplayed(); shot("10_gallery_group")
        rule.onNodeWithContentDescription("0001_anime.png").performClick()
        assertTrue(vm.screen is Screen.Compare)
        assertEquals(listOf("0001_anime.png", "0001_real_1.png", "0001_real_2.png"), vm.selected.map { it.name })
        assertTrue(vm.back()); assertTrue(vm.screen is Screen.Gallery); assertTrue(vm.selected.isEmpty())
    }

    @Test fun real_without_anime_sibling_stays_visible_and_groups_do_not_mix_across_folders() {
        val d1 = tmp.newFolder("x"); val d2 = tmp.newFolder("y")
        val f = { d: File, n: String -> File(d, n).also { png(it, AColor.GRAY) } }
        val l = listOf(
            Media(0, Uri.fromFile(f(d1, "0001_anime.png")), "0001_anime.png", "x", d1.path + "/0001_anime.png"),
            Media(1, Uri.fromFile(f(d2, "0001_real_1.png")), "0001_real_1.png", "y", d2.path + "/0001_real_1.png"),
        )
        vm.repo = object : MediaRepository(app.contentResolver) { override suspend fun loadImages() = l }
        vm.refresh(); rule.waitForIdle()
        assertEquals(2, vm.visible.size)   // real iz drugog foldera nema svoju anime → ne sakriva se
        assertNull(vm.groupOfAnime(l[0])?.reals?.firstOrNull())
    }

    @Test fun deleting_B_inside_group_compare_keeps_A() {
        setMedia(tmp.newFolder("d"), listOf("0001_anime.png", "0001_real_1.png"))
        vm.openGroup(vm.groupOfAnime(vm.all[0])!!)
        vm.silentDelete = { true }
        // jedini B obrisan → nazad u galeriju, A i dalje izabrana je očišćena
        vm.delete(vm.selected.drop(1))
        until { vm.screen is Screen.Gallery }
        assertEquals(listOf("0001_anime.png"), vm.selected.map { it.name })
    }

    // ───── PNG metadata ─────
    private fun pngWithPrompt(prompt: String): ByteArray {
        val o = ByteArrayOutputStream(); val d = DataOutputStream(o)
        d.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        fun chunk(t: String, data: ByteArray) { d.writeInt(data.size); d.write(t.toByteArray(Charsets.ISO_8859_1)); d.write(data); d.writeInt(0) }
        chunk("IHDR", ByteArray(13))
        chunk("tEXt", "workflow\u0000{\"big\":true}".toByteArray())
        chunk("tEXt", ("prompt\u0000$prompt").toByteArray())
        chunk("IEND", ByteArray(0))
        return o.toByteArray()
    }

    @Test fun png_meta_separates_source_from_reference_nodes() {
        val h1 = "a".repeat(64); val h2 = "b".repeat(64)
        val json = """{"227":{"class_type":"LoadImage","inputs":{"image":"$h1.png"},"_meta":{"title":"Load Image"}},
            "245":{"class_type":"LoadImage","inputs":{"image":"$h2.png"},"_meta":{"title":"EDIT slika 2 | druga referenca (Picture 2)"}},
            "9":{"class_type":"SaveImage","inputs":{"filename_prefix":"ComfyUI"}}}"""
        val f = tmp.newFile("r.png").also { it.writeBytes(pngWithPrompt(json)) }
        val m = PngMeta.read(f)!!
        assertEquals(setOf(h1), m.sourceHashes); assertEquals(setOf(h2), m.refHashes)
        assertNull(PngMeta.read(tmp.newFile("plain.png").also { png(it, AColor.RED) }))
        assertNull(PngMeta.read(tmp.newFile("x.jpg").also { it.writeText("nope") }))
    }

    // ───── imenovanje ─────
    private fun item(path: String) = Item(path, File(path).name, null, null, FloatArray(0), 1f, 0f, 0L)

    @Test fun namer_scheme_numbering_reuse_and_collision() {
        val d = tmp.newFolder("n")
        val names = listOf("a.png", "b.JPG", "c.png", "0003_anime.png", "0003_real_1.png", "d.png", "e.png")
        val files = names.map { File(d, it).also { f -> f.writeText("x") } }
        val g1 = Group(item(File(d, "a.png").path), listOf(item(File(d, "b.JPG").path), item(File(d, "c.png").path)), Confidence.EXACT)
        val g2 = Group(item(File(d, "0003_anime.png").path), listOf(item(File(d, "0003_real_1.png").path), item(File(d, "d.png").path)), Confidence.EXACT)
        val ops = Namer.plan(listOf(g1, g2), files).associate { it.from.name to it.to.name }
        assertEquals("0004_anime.png", ops["a.png"])     // sljedeći slobodan broj iza 0003
        assertEquals("0004_real_1.jpg", ops["b.JPG"])     // ekstenzija u mala slova
        assertEquals("0004_real_2.png", ops["c.png"])
        assertEquals("0003_real_2.png", ops["d.png"])     // nastavlja postojeću grupu
        assertFalse(ops.containsKey("0003_anime.png")); assertFalse(ops.containsKey("0003_real_1.png"))
    }

    // ───── stvarne slike: ekran Parovi end-to-end ─────
    private fun samples() = System.getenv("SAMPLES_DIR")?.let(::File)?.takeIf { it.isDirectory }
    private fun copy(from: File): File { val d = tmp.newFolder("s"); from.listFiles()!!.forEach { it.copyTo(File(d, it.name)); File(d, it.name).setLastModified(1_700_000_000_000L + it.name.substringBefore('.').toLong() * 1000) }; return d }

    @Test fun pairs_screen_auto_renames_confident_then_undo_restores() {
        val src = samples(); assumeNotNull(src)
        val dir = copy(src!!)
        rule.setContent { AppTheme { PairsScreen(vm) } }
        vm.choosePairsFolder(dir.path)
        try { until(30_000) { vm.pairing != null && !vm.scanning && vm.canUndo } } catch (e: AssertionError) {
            fail("msg=${vm.pairsMessage} scanning=${vm.scanning} pairing=${vm.pairing?.groups?.size} canUndo=${vm.canUndo} files=${dir.list()!!.sorted().take(8)}")
        }
        val names = dir.list()!!
        assertEquals(10, names.count { Namer.ANIME.matches(it) })
        assertEquals(14, names.count { Namer.REAL.matches(it) })
        // ne-parovi su ostali netaknuti
        for (n in listOf("189325.png", "189320.png", "190440.png", "190365.png", "190344.png", "190347.jpg")) assertTrue("$n", n in names)
        rule.waitForIdle()
        rule.onNodeWithTag("summary").assertIsDisplayed()
        rule.onNodeWithTag("undo").assertIsEnabled().performClick()
        until { !vm.scanning && vm.pairsMessage?.startsWith("Vraćeno") == true }
        assertEquals(src.list()!!.toSet(), dir.list()!!.toSet())
    }

    @Test fun auto_off_only_previews_and_apply_button_renames() {
        val src = samples(); assumeNotNull(src)
        val dir = copy(src!!)
        vm.toggleAutoApply(false)
        vm.choosePairsFolder(dir.path)
        until { vm.pairing != null && !vm.scanning }
        assertEquals(src.list()!!.toSet(), dir.list()!!.toSet())      // ništa preimenovano
        assertEquals(10, vm.pairing!!.groups.size)
        rule.setContent { AppTheme { PairsScreen(vm) } }
        Thread.sleep(2500); shadowOf(android.os.Looper.getMainLooper()).idle(); shot("9_pairs_preview")
        rule.onNodeWithTag("applyAll").assertIsEnabled().performClick()
        until { vm.canUndo && !vm.scanning && Namer.ANIME.matches(dir.list()!!.first { it.contains("anime") }) }
        assertEquals(10, dir.list()!!.count { Namer.ANIME.matches(it) })
    }
}
