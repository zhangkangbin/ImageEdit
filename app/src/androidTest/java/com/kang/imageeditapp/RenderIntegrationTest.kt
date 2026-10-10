package com.kang.imageeditapp

import android.graphics.*
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.kang.imageeditapp.data.ExportRepository
import com.kang.imageeditapp.data.PhotoRepository
import com.kang.imageeditapp.model.*
import com.kang.imageeditapp.render.ImageRenderEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs

class RenderIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun source(width: Int = 96, height: Int = 64, alpha: Boolean = false): PhotoSource {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, if (alpha) 0x8060B0E0.toInt() else Color.CYAN)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (q in 0..3) {
            paint.color = colors[q]
            val x = (q % 2) * width / 2f
            val y = (q / 2) * height / 2f
            canvas.drawRect(x, y, x + width / 2f, y + height / 2f, paint)
        }
        val file = File.createTempFile("render-fixture-", ".png", context.cacheDir)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return PhotoSource(Uri.fromFile(file), file.path, width, height)
    }

    private fun <T> withEngine(block: (ImageRenderEngine) -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        try {
            return executor.submit(Callable { ImageRenderEngine().use { block(it) } }).get()
        } finally { executor.shutdown() }
    }

    private fun assertColor(expected: Int, actual: Int, tolerance: Int = 3) {
        for (channel in listOf(Color::red, Color::green, Color::blue, Color::alpha)) {
            assertTrue("Expected ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}", abs(channel(expected) - channel(actual)) <= tolerance)
        }
    }

    @Test fun allExifDirectionsAndUserTransformsRenderExpectedPixels() = withEngine { engine ->
        val original = source()
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.CYAN)
        for (orientation in 1..8) for (turn in 0..3) {
            val input = original.copy(exifOrientation = orientation)
            val recipe = EditRecipe(quarterTurns = turn, flipHorizontal = turn % 2 == 0, flipVertical = turn % 2 == 1)
            val output = engine.renderExport(input, recipe)
            val size = Geometry.outputSize(input, recipe)
            assertEquals(size.width, output.width); assertEquals(size.height, output.height)
            for (u in listOf(.25f, .75f)) for (v in listOf(.25f, .75f)) {
                val mapped = Geometry.outputToSource(u, v, recipe, orientation)
                val quadrant = (if (mapped.x > .5f) 1 else 0) + (if (mapped.y > .5f) 2 else 0)
                assertColor(colors[quadrant], output.getPixel((u * output.width).toInt(), (v * output.height).toInt()))
            }
            output.recycle()
        }
    }

    @Test fun adjustedPreviewAndExportAgreeAtSameResolution() = withEngine { engine ->
        val input = source()
        val recipe = EditRecipe(
            adjustments = ColorAdjustments(exposure = .3f, brightness = .1f, contrast = .2f, saturation = -.15f, temperature = .2f, tint = -.1f, shadows = .15f, highlights = -.2f),
            curves = CurveSet(rgb = listOf(CurvePoint(0f, .02f), CurvePoint(.5f, .6f), CurvePoint(1f, .98f))),
            hsl = List(8) { HslAdjustment(hue = .2f, saturation = -.1f, lightness = .1f) },
            crop = CropRect(.125f, .125f, .875f, .875f),
        )
        val preview = engine.renderPreview(input, recipe)
        val output = engine.renderExport(input, recipe)
        assertEquals(output.width, preview.width); assertEquals(output.height, preview.height)
        for (x in 5 until output.width step 11) for (y in 5 until output.height step 11) assertColor(preview.getPixel(x, y), output.getPixel(x, y), 2)
        preview.recycle(); output.recycle()
    }

    @Test fun builtInFiltersProduceDistinctPixelsWithMatchingPreviewExportAndAlpha() = withEngine { engine ->
        val colors = listOf(Color.rgb(15, 15, 15), Color.rgb(125, 125, 125), Color.rgb(210, 210, 210),
            Color.rgb(150, 85, 60), Color.rgb(60, 145, 85), Color.rgb(65, 100, 170), 0x8060B0E0.toInt())
        val bitmap = Bitmap.createBitmap(140, 20, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        colors.forEachIndexed { index, color ->
            canvas.drawRect(index * 20f, 0f, (index + 1) * 20f, 20f, Paint().apply { this.color = color })
        }
        val file = File.createTempFile("builtin-filter-", ".png", context.cacheDir)
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val input = PhotoSource(Uri.fromFile(file), file.path, bitmap.width, bitmap.height)
            val signatures = mutableMapOf<String, List<Int>>()
            BuiltInFilters.entries.forEach { filter ->
                val recipe = filter.grade.applyTo(EditRecipe())
                val preview = engine.renderPreview(input, recipe)
                val output = engine.renderExport(input, recipe)
                try {
                    val pixels = colors.indices.map { index -> output.getPixel(index * 20 + 10, 10) }
                    signatures[filter.id] = pixels
                    pixels.forEachIndexed { index, pixel ->
                        assertColor(pixel, preview.getPixel(index * 20 + 10, 10), 2)
                        assertTrue(abs(Color.alpha(colors[index]) - Color.alpha(pixel)) <= 1)
                        if (filter.id == "original") assertColor(colors[index], pixel, 2)
                        if (filter.id == "mono") {
                            assertTrue(abs(Color.red(pixel) - Color.green(pixel)) <= 2)
                            assertTrue(abs(Color.red(pixel) - Color.blue(pixel)) <= 2)
                        }
                    }
                } finally { preview.recycle(); output.recycle() }
            }
            assertEquals(BuiltInFilters.entries.size, signatures.values.distinct().size)
            val warm = signatures.getValue("warm")[1]
            val cool = signatures.getValue("cool")[1]
            assertTrue(Color.red(warm) > Color.blue(warm))
            assertTrue(Color.blue(cool) > Color.red(cool))
            assertTrue(Color.red(signatures.getValue("fade")[0]) > Color.red(colors[0]))
            val sepia = signatures.getValue("sepia")[1]
            assertTrue(Color.red(sepia) > Color.green(sepia) && Color.green(sepia) > Color.blue(sepia))
        } finally { bitmap.recycle(); file.delete() }
    }

    @Test fun transparentPngRetainsAlphaAndJpegUsesWhiteBackground() = withEngine { engine ->
        val input = source(alpha = true)
        val output = engine.renderExport(input, EditRecipe())
        assertColor(0x8060B0E0.toInt(), output.getPixel(72, 48), 3)
        val pngUri = runBlocking { ExportRepository(context).save(output, ExportFormat.PNG) }
        context.contentResolver.openInputStream(pngUri).use { stream ->
            val decoded = BitmapFactory.decodeStream(stream)!!
            assertColor(0x8060B0E0.toInt(), decoded.getPixel(72, 48), 3)
            decoded.recycle()
        }
        val jpegUri = runBlocking { ExportRepository(context).save(output, ExportFormat.JPEG) }
        context.contentResolver.openInputStream(jpegUri).use { stream ->
            val decoded = BitmapFactory.decodeStream(stream)!!
            assertColor(Color.rgb(175, 215, 239), decoded.getPixel(72, 48), 6)
            decoded.recycle()
        }
        for (uri in listOf(pngUri, jpegUri)) {
            context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.IS_PENDING), null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0))
            }
            context.contentResolver.delete(uri, null, null)
        }
        output.recycle()
    }

    @Test fun largeImageTilesHaveNoSeams() = withEngine { engine ->
        val input = source(2056, 1080)
        val output = engine.renderExport(input, EditRecipe(adjustments = ColorAdjustments(exposure = -.2f)))
        for (y in listOf(20, 300, 800, 1050)) {
            assertColor(output.getPixel(1023, y), output.getPixel(1024, y), 1)
            assertColor(output.getPixel(2047, y), output.getPixel(2048, y), 1)
        }
        assertEquals(2056, output.width); assertEquals(1080, output.height)
        output.recycle()
    }

    @Test fun oversizedOutputFailsBeforeAllocation() = withEngine { engine ->
        val input = source().copy(width = 100_000, height = 100_000)
        try { engine.renderExport(input, EditRecipe()); fail("Expected memory guard") }
        catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("内存")) }
    }

    @Test fun watermarkUsesSameRelativePositionAtDifferentSizes() = withEngine { engine ->
        val input = source(1600, 1200)
        val recipe = EditRecipe(crop = CropRect(.1f, .1f, .9f, .9f), watermark = Watermark(text = "中文水印", color = Color.WHITE, sizeFraction = .08f, x = .5f, y = .85f))
        val preview = engine.renderPreview(input, recipe, 640)
        val output = engine.renderExport(input, recipe)
        fun whiteCenter(bitmap: Bitmap): Pair<Float, Float> {
            var count = 0L; var sx = 0L; var sy = 0L
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val c = bitmap.getPixel(x, y)
                if (Color.red(c) > 245 && Color.green(c) > 245 && Color.blue(c) > 245) { count++; sx += x; sy += y }
            }
            assertTrue("Chinese text should be visible", count > 20)
            return sx.toFloat() / count / bitmap.width to sy.toFloat() / count / bitmap.height
        }
        val a = whiteCenter(preview); val b = whiteCenter(output)
        assertEquals(a.first, b.first, .01f); assertEquals(a.second, b.second, .01f)
        assertEquals(.5f, a.first, .03f); assertEquals(.85f, a.second, .05f)
        preview.recycle(); output.recycle()
    }

    @Test fun corruptImportReturnsActionableError() {
        val file = File.createTempFile("broken-", ".png", context.cacheDir).apply { writeText("invalid image") }
        try { runBlocking { PhotoRepository(context).import(Uri.fromFile(file)) }; fail("Expected rejection") }
        catch (expected: IllegalArgumentException) { assertTrue(expected.message!!.contains("损坏")) }
    }

    @Test fun hslRedChannelWrapsSmoothlyThroughZeroDegrees() = withEngine { engine ->
        val bitmap = Bitmap.createBitmap(8, 2, Bitmap.Config.ARGB_8888)
        for (x in 0..7) for (y in 0..1) {
            bitmap.setPixel(x, y, Color.HSVToColor(floatArrayOf(if (x < 4) 359f else 1f, 1f, 1f)))
        }
        val file = File.createTempFile("red-boundary-", ".png", context.cacheDir)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val input = PhotoSource(Uri.fromFile(file), file.path, 8, 2)
        val recipe = EditRecipe(hsl = List(8) { if (it == 0) HslAdjustment(hue = 1f) else HslAdjustment() })
        val output = engine.renderExport(input, recipe)
        val left = output.getPixel(1, 0); val right = output.getPixel(6, 0)
        assertColor(left, right, 12)
        assertTrue(Color.red(left) > 235 && Color.green(left) > 225 && Color.blue(left) < 15)
        output.recycle()
    }

    @Test fun failedEncodingLeavesNoPendingMediaStoreRow() {
        fun count(): Int = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("IMG_EDIT_%"), null,
        )!!.use { it.count }
        val before = count()
        val invalid = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { recycle() }
        try { runBlocking { ExportRepository(context).save(invalid, ExportFormat.PNG) }; fail("Expected encoder failure") }
        catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("recycled")) }
        assertEquals(before, count())
    }
}
