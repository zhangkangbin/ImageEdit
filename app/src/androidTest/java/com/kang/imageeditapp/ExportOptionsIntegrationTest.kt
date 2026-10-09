package com.kang.imageeditapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.kang.imageeditapp.data.ExportRepository
import com.kang.imageeditapp.model.*
import com.kang.imageeditapp.render.ImageRenderEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs

class ExportOptionsIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun source(width: Int, height: Int, pixel: (Int, Int) -> Int): PhotoSource {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val row = IntArray(width)
        for (y in 0 until height) {
            for (x in row.indices) row[x] = pixel(x, y)
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        val file = File.createTempFile("export-options-", ".png", context.cacheDir)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        return PhotoSource(Uri.fromFile(file), file.path, width, height)
    }

    private fun <T> withEngine(block: (ImageRenderEngine) -> T): T {
        val executor = Executors.newSingleThreadExecutor()
        try { return executor.submit(Callable { ImageRenderEngine().use { block(it) } }).get() }
        finally { executor.shutdown() }
    }

    private fun assertColor(expected: Int, actual: Int, tolerance: Int = 3) {
        for (channel in listOf(Color::red, Color::green, Color::blue, Color::alpha)) {
            assertTrue("Expected ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}",
                abs(channel(expected) - channel(actual)) <= tolerance)
        }
    }

    @Test fun defaultOptionsArePixelIdenticalToOriginalExport() = withEngine { engine ->
        val input = source(320, 240) { x, y -> Color.rgb(x % 256, y % 256, (x + y) % 256) }
        try {
            val recipe = EditRecipe(crop = CropRect(.1f, .2f, .9f, .8f), quarterTurns = 1,
                adjustments = ColorAdjustments(exposure = -.2f), watermark = Watermark("中文", sizeFraction = .1f))
            val old = engine.renderExport(input, recipe)
            val selected = engine.renderExport(input, recipe, ExportOptions())
            try { assertTrue(old.sameAs(selected)) }
            finally { old.recycle(); selected.recycle() }
        } finally { File(input.localPath).delete() }
    }

    @Test fun downsizedCropPreservesExifRotationFlipAndColors() = withEngine { engine ->
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.CYAN)
        val input = source(320, 192) { x, y -> colors[(if (x < 160) 0 else 1) + (if (y < 96) 0 else 2)] }
        try {
            val options = ExportOptions(resolution = ExportResolution.CUSTOM_LONG, customLongEdge = 80)
            for (exif in 1..8) for (turn in 0..3) {
                val oriented = input.copy(exifOrientation = exif)
                val recipe = EditRecipe(crop = CropRect(.125f, .125f, .875f, .875f), quarterTurns = turn,
                    flipHorizontal = turn % 2 == 0, flipVertical = turn % 2 == 1)
                val output = engine.renderExport(oriented, recipe, options)
                try {
                    val expected = options.resolveSize(Geometry.outputSize(oriented, recipe))
                    assertEquals(expected.width, output.width); assertEquals(expected.height, output.height)
                    for (u in listOf(.25f, .75f)) for (v in listOf(.25f, .75f)) {
                        val p = Geometry.outputToSource(u, v, recipe, exif)
                        val color = colors[(if (p.x < .5f) 0 else 1) + (if (p.y < .5f) 0 else 2)]
                        assertColor(color, output.getPixel((u * output.width).toInt(), (v * output.height).toInt()))
                    }
                } finally { output.recycle() }
            }
        } finally { File(input.localPath).delete() }
    }

    @Test fun resizedPngRetainsAlphaAndJpegFlattensToWhiteAtChosenSize() = withEngine { engine ->
        val input = source(640, 480) { x, _ -> if (x < 320) Color.TRANSPARENT else 0x8060B0E0.toInt() }
        val uris = mutableListOf<Uri>()
        val options = ExportOptions(resolution = ExportResolution.CUSTOM_LONG, customLongEdge = 320)
        try {
            for (format in ExportFormat.entries) {
                val selected = options.copy(format = format, jpegQuality = 50)
                val output = engine.renderExport(input, EditRecipe(), selected)
                val uri = try { runBlocking { ExportRepository(context).save(output, selected) } }
                finally { output.recycle() }
                uris += uri
                val decoded = context.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it)!! }
                try {
                    assertEquals(320, decoded.width); assertEquals(240, decoded.height)
                    if (format == ExportFormat.PNG) {
                        assertEquals(0, Color.alpha(decoded.getPixel(40, 100)))
                        assertColor(0x8060B0E0.toInt(), decoded.getPixel(240, 100))
                    } else {
                        assertColor(Color.WHITE, decoded.getPixel(40, 100), 6)
                        assertColor(Color.rgb(175, 215, 239), decoded.getPixel(240, 100), 8)
                    }
                } finally { decoded.recycle() }
            }
        } finally { uris.forEach { context.contentResolver.delete(it, null, null) }; File(input.localPath).delete() }
    }

    @Test fun selectedJpegQualityChangesActualEncodedFileSize() = withEngine { engine ->
        val input = source(512, 384) { x, y ->
            val seed = (x * 73856093) xor (y * 19349663)
            Color.rgb(seed and 255, (seed ushr 8) and 255, (seed ushr 16) and 255)
        }
        val uris = mutableListOf<Uri>()
        try {
            val sizes = listOf(50, 95).map { quality ->
                val selected = ExportOptions(jpegQuality = quality)
                val output = engine.renderExport(input, EditRecipe(), selected)
                val uri = try { runBlocking { ExportRepository(context).save(output, selected) } }
                finally { output.recycle() }
                uris += uri
                context.contentResolver.openInputStream(uri)!!.use { it.readBytes().size }
            }
            assertTrue("Quality 95 should retain more detail and use more bytes than quality 50: $sizes", sizes[1] > sizes[0] * 1.2)
        } finally { uris.forEach { context.contentResolver.delete(it, null, null) }; File(input.localPath).delete() }
    }

    @Test fun chineseWatermarkRetainsRelativePlacementAfterExportDownsize() = withEngine { engine ->
        val input = source(1600, 1200) { _, _ -> Color.rgb(60, 80, 100) }
        try {
            val recipe = EditRecipe(crop = CropRect(.1f, .1f, .9f, .9f),
                watermark = Watermark("中文水印", sizeFraction = .12f, x = .6f, y = .7f))
            val original = engine.renderExport(input, recipe)
            val reduced = engine.renderExport(input, recipe, ExportOptions(resolution = ExportResolution.CUSTOM_LONG, customLongEdge = 320))
            try {
                fun whiteCenter(bitmap: Bitmap): Pair<Float, Float> {
                    var count = 0L; var sx = 0L; var sy = 0L
                    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                        val c = bitmap.getPixel(x, y)
                        if (Color.red(c) > 245 && Color.green(c) > 245 && Color.blue(c) > 245) { count++; sx += x; sy += y }
                    }
                    assertTrue(count > 20)
                    return sx.toFloat() / count / bitmap.width to sy.toFloat() / count / bitmap.height
                }
                val a = whiteCenter(original); val b = whiteCenter(reduced)
                assertEquals(a.first, b.first, .015f); assertEquals(a.second, b.second, .015f)
                assertEquals(.6f, b.first, .03f); assertEquals(.7f, b.second, .05f)
            } finally { original.recycle(); reduced.recycle() }
        } finally { File(input.localPath).delete() }
    }

    @Test fun downsizedLargePhotoDoesNotAllocateAnOriginalSizeOutput() = withEngine { engine ->
        // One-bit indexed PNG creates a genuine 400 MP source with only a scanline-sized fixture buffer.
        val input = indexedLargeSource(20000, 20000)
        try {
            try { engine.renderExport(input, EditRecipe()); fail("The original 1.6 GB canvas must hit the memory guard") }
            catch (error: IllegalStateException) { assertTrue(error.message!!.contains("内存")) }
            val result = engine.renderExport(input, EditRecipe(), ExportOptions(resolution = ExportResolution.LONG_1080))
            try {
                assertEquals(1080, result.width); assertEquals(1080, result.height)
                assertTrue(result.allocationByteCount <= 1080 * 1080 * 4)
                for (x in listOf(0, 512, 1023, 1024, 1079)) for (y in listOf(0, 512, 1023, 1024, 1079)) {
                    assertColor(Color.rgb(80, 100, 120), result.getPixel(x, y), 2)
                }
            } finally { result.recycle() }
        } finally { File(input.localPath).delete() }
    }

    @Test fun invalidOptionsAreRejectedBeforeDecodeOrMediaStoreWrite() = withEngine { engine ->
        val missing = PhotoSource(Uri.EMPTY, "/nonexistent-export-fixture.png", 10, 10)
        try {
            engine.renderExport(missing, EditRecipe(), ExportOptions(resolution = ExportResolution.CUSTOM_LONG, customLongEdge = 0))
            fail("Invalid edge must be rejected before source decode")
        } catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("长边")) }
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            try { runBlocking { ExportRepository(context).save(bitmap, ExportOptions(jpegQuality = 101)) }; fail("Invalid quality must be rejected before publishing") }
            catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("质量")) }
        } finally { bitmap.recycle() }
    }

    private fun indexedLargeSource(width: Int, height: Int): PhotoSource {
        val file = File.createTempFile("large-export-", ".png", context.cacheDir)
        fun chunk(output: DataOutputStream, type: String, data: ByteArray) {
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            output.writeInt(data.size); output.write(typeBytes); output.write(data)
            val crc = CRC32().apply { update(typeBytes); update(data) }
            output.writeInt(crc.value.toInt())
        }
        DataOutputStream(file.outputStream().buffered()).use { output ->
            output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            val header = ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use { data -> data.writeInt(width); data.writeInt(height); data.write(byteArrayOf(1, 3, 0, 0, 0)) }
            }.toByteArray()
            chunk(output, "IHDR", header)
            chunk(output, "PLTE", byteArrayOf(80, 100, 120, 80, 100, 120))
            val pixels = ByteArrayOutputStream()
            DeflaterOutputStream(pixels).use { deflater ->
                val row = ByteArray((width + 7) / 8 + 1)
                repeat(height) { deflater.write(row) }
            }
            chunk(output, "IDAT", pixels.toByteArray())
            chunk(output, "IEND", ByteArray(0))
        }
        return PhotoSource(Uri.fromFile(file), file.path, width, height)
    }
}
