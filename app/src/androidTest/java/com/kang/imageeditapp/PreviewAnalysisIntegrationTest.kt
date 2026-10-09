package com.kang.imageeditapp

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.kang.imageeditapp.model.*
import com.kang.imageeditapp.render.ImageRenderEngine
import com.kang.imageeditapp.render.PixelAnalysis
import com.kang.imageeditapp.render.PreviewRenderResult
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs

class PreviewAnalysisIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun source(width: Int, height: Int, pixel: (Int, Int) -> Int): PhotoSource {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) row[x] = pixel(x, y)
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        val file = File.createTempFile("detail-fixture-", ".png", context.cacheDir)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return PhotoSource(Uri.fromFile(file), file.path, width, height)
    }

    private fun <T> withEngine(block: (ImageRenderEngine) -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        try { return executor.submit(Callable { ImageRenderEngine().use { block(it) } }).get() }
        finally { executor.shutdown() }
    }

    private fun PreviewRenderResult.recycle() { bitmap.recycle(); clippingOverlay.recycle() }

    private fun assertColor(expected: Int, actual: Int, tolerance: Int = 2) {
        for (channel in listOf(Color::red, Color::green, Color::blue, Color::alpha)) {
            assertTrue("Expected ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}",
                abs(channel(expected) - channel(actual)) <= tolerance)
        }
    }

    @Test fun analysisAndClippingExcludeTextAndDoNotAffectExport() = withEngine { engine ->
        val input = source(256, 128) { x, _ -> when {
            x < 64 -> Color.BLACK
            x < 128 -> Color.WHITE
            x < 192 -> Color.rgb(128, 128, 128)
            else -> Color.TRANSPARENT
        } }
        val recipe = EditRecipe(watermark = Watermark("中文水印", sizeFraction = .25f, x = .2f, y = .5f))
        val plain = engine.renderAnalyzedPreview(input, EditRecipe())
        val marked = engine.renderAnalyzedPreview(input, recipe)
        assertEquals(24576L, marked.analysis.sampleCount)
        assertEquals(8192L, marked.analysis.highlightCount)
        assertEquals(8192L, marked.analysis.shadowCount)
        assertArrayEquals(plain.analysis.luminance, marked.analysis.luminance)
        assertArrayEquals(plain.analysis.red, marked.analysis.red)
        assertColor(PixelAnalysis.HIGHLIGHT_COLOR, marked.clippingOverlay.getPixel(100, 100))
        assertColor(PixelAnalysis.SHADOW_COLOR, marked.clippingOverlay.getPixel(20, 100))
        assertEquals(0, marked.clippingOverlay.getPixel(220, 100))
        val exported = engine.renderExport(input, recipe)
        assertTrue("Overlay must never enter saved pixels", exported.sameAs(marked.bitmap))
        plain.recycle(); marked.recycle(); exported.recycle()
    }

    @Test fun croppedFullFrameAnalysisDoesNotBecomeViewportHistogram() = withEngine { engine ->
        val input = source(256, 64) { x, _ -> if (x < 128) Color.BLACK else Color.WHITE }
        val full = engine.renderAnalyzedPreview(input, EditRecipe(crop = CropRect(.25f, 0f, .75f, 1f)))
        val detail = engine.renderAnalyzedRegion(input, EditRecipe(crop = CropRect(.25f, 0f, .75f, 1f)), CropRect(.5f, 0f, 1f, 1f), 64, 64)
        assertEquals(4096L, full.analysis.highlightCount)
        assertEquals(4096L, full.analysis.shadowCount)
        assertEquals(4096L, detail.analysis.highlightCount)
        assertEquals(0L, detail.analysis.shadowCount)
        full.recycle(); detail.recycle()
    }

    @Test fun nativeRegionMatchesExportAcrossExifRotationFlipAndCrop() = withEngine { engine ->
        val input = source(160, 96) { x, y -> Color.rgb((x * 7) % 220 + 15, (y * 11) % 220 + 15, (x + y) % 200 + 20) }
        for (orientation in 1..8) for (turn in 0..3) {
            val source = input.copy(exifOrientation = orientation)
            val recipe = EditRecipe(
                crop = CropRect(.125f, .125f, .875f, .875f), quarterTurns = turn,
                flipHorizontal = turn % 2 == 0, flipVertical = turn % 2 == 1,
                adjustments = ColorAdjustments(exposure = -.2f, temperature = .1f),
            )
            val exported = engine.renderExport(source, recipe)
            val left = exported.width / 4
            val top = exported.height / 4
            val width = exported.width / 2
            val height = exported.height / 2
            val region = CropRect(left.toFloat() / exported.width, top.toFloat() / exported.height,
                (left + width).toFloat() / exported.width, (top + height).toFloat() / exported.height)
            val detail = engine.renderRegion(source, recipe, region, width, height)
            for (y in 0 until height step 7) for (x in 0 until width step 9) {
                assertColor(exported.getPixel(left + x, top + y), detail.getPixel(x, y), 2)
            }
            exported.recycle(); detail.recycle()
        }
    }

    @Test fun detailUsesOriginalPixelsBeyond1600PreviewAndSupportsVeryNarrowRoi() = withEngine { engine ->
        val input = source(4096, 128) { x, _ -> if (x % 2 == 0) Color.BLACK else Color.WHITE }
        val detail = engine.renderRegion(input, EditRecipe(), CropRect(2000f / 4096, 0f, 2064f / 4096, 1f), 64, 128)
        assertEquals(64, detail.width)
        for (x in 0 until 64) assertColor(if (x % 2 == 0) Color.BLACK else Color.WHITE, detail.getPixel(x, 40), 1)
        detail.recycle()
    }

    @Test fun chineseWatermarkRetainsGlobalCanvasPlacementInNativeDetail() = withEngine { engine ->
        val input = source(800, 600) { _, _ -> Color.rgb(80, 100, 120) }
        val recipe = EditRecipe(watermark = Watermark("中文水印\n测试", sizeFraction = .1f, x = .6f, y = .55f))
        val exported = engine.renderExport(input, recipe)
        val region = CropRect(.25f, .25f, .75f, .75f)
        val detail = engine.renderRegion(input, recipe, region, 400, 300)
        var textPixels = 0
        for (y in 0 until 300) for (x in 0 until 400) {
            assertColor(exported.getPixel(x + 200, y + 150), detail.getPixel(x, y), 1)
            if (Color.red(detail.getPixel(x, y)) > 230) textPixels++
        }
        assertTrue("Watermark should remain visible inside its viewport", textPixels > 100)
        exported.recycle(); detail.recycle()
    }

    @Test fun wideSourceViewportUsesBoundedDecoderTextures() = withEngine { engine ->
        val input = source(32768, 16) { _, _ -> Color.rgb(90, 120, 150) }
        val detail = engine.renderRegion(input, EditRecipe(), CropRect(), 16, 16)
        for (x in 0 until 16) assertColor(Color.rgb(90, 120, 150), detail.getPixel(x, 8), 1)
        detail.recycle()
    }

    @Test fun invalidRegionIsRejectedBeforeDecode() = withEngine { engine ->
        val input = source(4, 4) { _, _ -> Color.GRAY }
        for (region in listOf(CropRect(Float.NaN, 0f, 1f, 1f), CropRect(.5f, 0f, .5f, 1f), CropRect(-1f, 0f, 1f, 1f))) {
            try { engine.renderRegion(input, EditRecipe(), region, 2, 2); fail("Expected invalid ROI rejection") }
            catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("范围")) }
        }
    }
}
