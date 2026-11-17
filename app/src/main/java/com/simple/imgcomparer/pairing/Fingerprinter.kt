package com.simple.imgcomparer.pairing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** Sve izvedeno iz jednog fajla; keširano po (putanja, veličina, mtime). */
class Features(
    val sig: FloatArray, val aspect: Float, val score: Float,
    val meta: PromptInfo?, val sha: String?,
)

object Fingerprinter {
    const val W = 24
    const val H = 32
    private const val DECODE_W = 512

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Dekodira umanjeno (inSampleSize) pa računa potpis i mjeru ivica. null = nečitljiva slika. */
    fun compute(file: File, withMeta: Boolean = true): Features? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val w0 = bounds.outWidth; val h0 = bounds.outHeight
        if (w0 <= 0 || h0 <= 0) return null
        var sample = 1
        while (w0 / (sample * 2) >= DECODE_W) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val gw = DECODE_W; val gh = max(1, (DECODE_W.toLong() * h0 / w0).toInt())
        val scaled = if (bmp.width == gw && bmp.height == gh) bmp else Bitmap.createScaledBitmap(bmp, gw, gh, true)
        val px = IntArray(gw * gh); scaled.getPixels(px, 0, gw, 0, 0, gw, gh)
        if (scaled !== bmp) scaled.recycle(); bmp.recycle()
        val g = FloatArray(gw * gh) { i ->
            val c = px[i]; 0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)
        }
        return Features(signature(g, gw, gh), w0.toFloat() / h0, edgeScore(g, gw, gh), if (withMeta) PngMeta.read(file) else null, null)
    }

    /** Centar-crop na 24:32, box-average na 24x32, normalizacija. */
    internal fun signature(g: FloatArray, gw: Int, gh: Int): FloatArray {
        val target = W.toFloat() / H
        var cx = 0; var cy = 0; var cw = gw; var ch = gh
        if (gw.toFloat() / gh > target) { cw = (gh * target).toInt(); cx = (gw - cw) / 2 }
        else { ch = (gw / target).toInt(); cy = (gh - ch) / 2 }
        val out = FloatArray(W * H)
        for (ty in 0 until H) for (tx in 0 until W) {
            val x0 = cx + tx * cw / W; val x1 = max(x0 + 1, cx + (tx + 1) * cw / W)
            val y0 = cy + ty * ch / H; val y1 = max(y0 + 1, cy + (ty + 1) * ch / H)
            var s = 0f; var n = 0
            for (y in y0 until y1) for (x in x0 until x1) { s += g[y * gw + x]; n++ }
            out[ty * W + tx] = s / n
        }
        val mean = out.average().toFloat()
        var v = 0f; for (x in out) v += (x - mean) * (x - mean)
        val sd = sqrt(v / out.size) + 1e-6f
        for (i in out.indices) out[i] = (out[i] - mean) / sd
        return out
    }

    /** udio jakih ivica (konture) − 0.1·udio srednjih gradijenata (mekane sjene/tekstura). Anime = veće. */
    internal fun edgeScore(g: FloatArray, gw: Int, gh: Int): Float {
        var strong = 0; var mid = 0; var n = 0
        for (y in 0 until gh - 1) for (x in 0 until gw - 1) {
            val i = y * gw + x
            val m = hypot(kotlin.math.abs(g[i + 1] - g[i]), kotlin.math.abs(g[i + gw] - g[i]))
            if (m > 60f) strong++ else if (m > 3f && m < 12f) mid++
            n++
        }
        return strong.toFloat() / n - 0.1f * mid.toFloat() / n
    }
}
