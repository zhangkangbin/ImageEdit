package com.kang.imageeditapp.render

import android.graphics.Bitmap
import kotlin.math.roundToInt

/** Analysis of the graded sRGB image, before text. Bins count nontransparent pixels. */
data class PreviewAnalysis(
    val red: IntArray,
    val green: IntArray,
    val blue: IntArray,
    val luminance: IntArray,
    val sampleCount: Long,
    val highlightCount: Long,
    val shadowCount: Long,
)

data class PreviewRenderResult(
    val bitmap: Bitmap,
    val analysis: PreviewAnalysis,
    /** Transparent everywhere except red highlights and blue shadows. Never exported. */
    val clippingOverlay: Bitmap,
)

/** Pure pixel arithmetic is shared by the preview analysis and its unit tests. */
object PixelAnalysis {
    const val HIGHLIGHT_COLOR: Int = 0xBFFF3030.toInt()
    const val SHADOW_COLOR: Int = 0xBF307BFF.toInt()

    fun analyze(pixels: IntArray): PreviewAnalysis {
        val accumulator = Accumulator()
        accumulator.add(pixels)
        return accumulator.finish()
    }

    fun clippingColor(pixel: Int): Int {
        if (pixel ushr 24 == 0) return 0
        val red = pixel ushr 16 and 255
        val green = pixel ushr 8 and 255
        val blue = pixel and 255
        return when {
            red == 255 || green == 255 || blue == 255 -> HIGHLIGHT_COLOR
            red == 0 && green == 0 && blue == 0 -> SHADOW_COLOR
            else -> 0
        }
    }

    internal class Accumulator {
        private val red = IntArray(256)
        private val green = IntArray(256)
        private val blue = IntArray(256)
        private val luminance = IntArray(256)
        private var count = 0L
        private var highlights = 0L
        private var shadows = 0L

        fun add(pixels: IntArray, length: Int = pixels.size) {
            for (index in 0 until length) {
                val pixel = pixels[index]
                if (pixel ushr 24 == 0) continue
                val r = pixel ushr 16 and 255
                val g = pixel ushr 8 and 255
                val b = pixel and 255
                red[r]++
                green[g]++
                blue[b]++
                // Rec. 709 weights applied to the displayed, gamma-encoded sRGB values.
                luminance[(.2126 * r + .7152 * g + .0722 * b).roundToInt().coerceIn(0, 255)]++
                count++
                if (r == 255 || g == 255 || b == 255) highlights++
                if (r == 0 && g == 0 && b == 0) shadows++
            }
        }

        fun finish() = PreviewAnalysis(red, green, blue, luminance, count, highlights, shadows)
    }
}
