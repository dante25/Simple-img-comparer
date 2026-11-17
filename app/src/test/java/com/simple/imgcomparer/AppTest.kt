package com.simple.imgcomparer

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import androidx.compose.ui.unit.dp

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class AppTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: Application
    private lateinit var vm: GalleryViewModel
    private val shots = File("build/screenshots").apply { mkdirs() }

    private fun solid(name: String, color: Int): Uri {
        val f = File(app.cacheDir, "$name.png")
        val b = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        Canvas(b).drawColor(color)
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(f)
    }

    private val colors = listOf(AColor.RED, AColor.BLUE, AColor.GREEN, AColor.YELLOW, AColor.MAGENTA, AColor.CYAN)

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        val media = (0 until 12).map {
            Media(it.toLong(), solid("img$it", colors[it % 6]), "IMG_$it.jpg", if (it < 8) "Camera" else "Download")
        }
        vm = GalleryViewModel(app)
        vm.repo = object : MediaRepository(app.contentResolver) {
            override suspend fun loadImages() = media
        }
        vm.refresh()
    }

    private fun px(): android.graphics.Bitmap {
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        return b
    }

    private fun shot(name: String) {
        val img = px()
        File(shots, "$name.png").outputStream().use { img.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun gallery_shows_images_and_filters_by_folder() {
        rule.setContent { AppTheme { GalleryScreen(vm) } }
        rule.waitForIdle()
        assertEquals(12, vm.visible.size)
        rule.onNodeWithText("Download").performClick()
        rule.waitForIdle()
        assertEquals(4, vm.visible.size)
        rule.onNodeWithText("Sve").performClick()
        assertEquals(12, vm.visible.size)
        shot("1_gallery")
    }

    @Test fun compare_selection_flow_and_bottom_bar() {
        rule.setContent { AppTheme { GalleryScreen(vm) } }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Compare").performClick()
        assertTrue(vm.compareMode)
        rule.onNodeWithTag("delete").assertIsNotEnabled()   // bar je tu, ali bez izbora kanta je ugasena
        rule.onNodeWithText("Uporedi").assertDoesNotExist()
    }

    @Test fun selection_bar_order_and_main() {
        rule.setContent { AppTheme { GalleryScreen(vm) } }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Compare").performClick()
        val items = vm.visible
        rule.onNodeWithContentDescription("IMG_0.jpg").performClick()
        rule.onNodeWithTag("delete").assertIsEnabled()
        rule.onNodeWithText("Uporedi").assertDoesNotExist()  // treba bar 2 slike
        rule.onNodeWithContentDescription("IMG_1.jpg").performClick()
        rule.onNodeWithContentDescription("IMG_2.jpg").performClick()
        assertEquals(listOf(0L, 1L, 2L), vm.selected.map { it.id })
        rule.onNodeWithText("Uporedi").assertIsDisplayed()
        rule.onNodeWithTag("delete").assertIsEnabled()
        shot("2_select_mode")
        // tap on B2 thumbnail in bar-> main
        vm.makeMain(items[2])
        assertEquals(2L, vm.selected.first().id)
        // deselect all -> Uporedi nestaje
        vm.selected.clear()
        rule.waitForIdle()
        rule.onNodeWithText("Uporedi").assertDoesNotExist()
    }

    @Test fun long_press_enters_compare_mode_and_selects() {
        rule.setContent { AppTheme { GalleryScreen(vm) } }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("IMG_3.jpg").performTouchInput { longClick() }
        assertTrue(vm.compareMode)
        assertEquals(listOf(3L), vm.selected.map { it.id })
    }

    @Test fun slider_moves_with_drag_and_clips_correctly() {
        vm.toggle(vm.visible[0]); vm.toggle(vm.visible[1])   // A = red, B = blue
        rule.setContent { AppTheme { CompareScreen(vm) } }
        fun sample(fx: Float, fy: Float = 0.5f): Int {
            val b = px(); return b.getPixel((b.width * fx).toInt(), (b.height * fy).toInt())
        }
        // Coil učitava async – čekaj da se pojavi crvena lijevo
        rule.waitUntil(15_000) { AColor.red(sample(0.1f)) > 200 && AColor.blue(sample(0.9f)) > 200 }
        shot("3_compare_50")
        // povuci klizač na 25%
        rule.onRoot().performTouchInput {
            val y = height * 0.4f
            down(androidx.compose.ui.geometry.Offset(width * 0.5f, y))
            moveTo(androidx.compose.ui.geometry.Offset(width * 0.8f, y))
            up()
        }
        rule.waitForIdle()
        val left = sample(0.7f); val right = sample(0.9f)
        assertTrue("0.7 treba biti A(crveno), a jeste ${Integer.toHexString(left)}", AColor.red(left) > 200 && AColor.blue(left) < 60)
        assertTrue("0.9 treba biti B(plavo)", AColor.blue(right) > 200)
        shot("4_compare_80")
        rule.onRoot().performTouchInput {
            val y = height * 0.4f
            down(androidx.compose.ui.geometry.Offset(width * 0.8f, y))
            moveTo(androidx.compose.ui.geometry.Offset(width * 0.2f, y))
            up()
        }
        rule.waitForIdle()
        assertTrue("0.4 treba biti B(plavo) nakon vuče ulijevo", AColor.blue(sample(0.4f)) > 200)
        assertTrue("0.1 treba biti A", AColor.red(sample(0.1f)) > 200)
        shot("5_compare_20")
    }

    @Test fun compare_with_multiple_B_switches_image() {
        listOf(0, 1, 2).forEach { vm.toggle(vm.visible[it]) }   // A red, B1 blue, B2 green
        rule.setContent { AppTheme { CompareScreen(vm) } }
        rule.waitUntil(15_000) { val b = px(); AColor.blue(b.getPixel((b.width*0.9).toInt(), b.height/2)) > 200 }
        rule.onNodeWithText("B2").performClick()
        rule.waitUntil(15_000) { val b = px(); AColor.green(b.getPixel((b.width*0.9).toInt(), b.height/2)) > 200 }
        shot("6_compare_B2")
    }

    @Test fun delete_request_created() {
        var calledMethod: String? = null
        var calledUris: List<Uri>? = null
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle =
                android.os.Bundle().apply {
                    calledMethod = method
                    val clip = extras?.getParcelable("clip_data", android.content.ClipData::class.java)
                    calledUris = clip?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri } }
                    putParcelable("result", android.app.PendingIntent.getActivity(app, 0,
                        android.content.Intent("x"), android.app.PendingIntent.FLAG_IMMUTABLE))
                }
            override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
            override fun getType(u: Uri) = null
            override fun insert(u: Uri, v: ContentValues?) = null
            override fun delete(u: Uri, s: String?, a: Array<String>?) = 0
            override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
        }
        provider.attachInfo(app, android.content.pm.ProviderInfo().apply { authority = "media"; exported = true })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media", provider)
        vm.toggle(vm.visible[0])
        vm.deleteSelected()
        rule.waitUntil(5_000) { calledMethod != null }
        assertEquals("create_delete_request", calledMethod)
        assertEquals(listOf(vm.visible[0].uri), calledUris)
    }

    @Test fun viewer_pages() {
        rule.setContent { AppTheme { ViewerScreen(vm, 2) } }
        rule.waitForIdle()
        shot("7_viewer")
    }

    @Test fun real_repository_query_reads_mediastore() {
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor =
                MatrixCursor(arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.BUCKET_DISPLAY_NAME)).apply {
                    addRow(arrayOf(5L, "a.jpg", "Camera")); addRow(arrayOf(6L, "b.png", null))
                }
            override fun getType(u: Uri) = null
            override fun insert(u: Uri, v: ContentValues?) = null
            override fun delete(u: Uri, s: String?, a: Array<String>?) = 0
            override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
        }
        Robolectric.buildContentProvider(provider::class.java) // no-op safeguard
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
        val list = runBlocking { MediaRepository(app.contentResolver).loadImages() }
        assertEquals(2, list.size)
        assertEquals("Camera", list[0].folder); assertEquals("Ostalo", list[1].folder)
        assertTrue(list[0].uri.toString().endsWith("/5"))
    }
}
