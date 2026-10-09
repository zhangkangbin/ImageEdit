package com.kang.imageeditapp.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.Rect
import com.kang.imageeditapp.model.CropRect
import com.kang.imageeditapp.model.EditRecipe
import com.kang.imageeditapp.model.ExportOptions
import com.kang.imageeditapp.model.Geometry
import com.kang.imageeditapp.model.NormalizedPoint
import com.kang.imageeditapp.model.PhotoSource
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Non-destructive image grading. Call all methods, including close, on one worker thread.
 * The context is initialized on the first render, so constructing this class on main is safe.
 */
class ImageRenderEngine : AutoCloseable {
    private var renderer: HeadlessGlRenderer? = null
    private var ownerThread: Thread? = null
    private var closed = false
    private var cachedPreviewPath: String? = null
    private var cachedPreviewSample = 0
    private var cachedPreview: Bitmap? = null

    fun renderPreview(source: PhotoSource, recipe: EditRecipe, maxEdge: Int = 1600): Bitmap {
        val output = renderPreviewGraded(source, recipe, maxEdge)
        return paintWatermark(output, recipe, CropRect())
    }

    fun renderAnalyzedPreview(source: PhotoSource, recipe: EditRecipe, maxEdge: Int = 1600): PreviewRenderResult =
        finishAnalyzed(renderPreviewGraded(source, recipe, maxEdge), recipe, CropRect())

    /** Region is normalized on the final cropped output. Viewport state never enters EditRecipe. */
    fun renderRegion(
        source: PhotoSource, recipe: EditRecipe, region: CropRect, outputWidth: Int, outputHeight: Int,
    ): Bitmap = paintWatermark(renderRegionGraded(source, recipe, region, outputWidth, outputHeight), recipe, region)

    /** ROI analysis is local. Keep renderAnalyzedPreview.analysis for a whole-image histogram. */
    fun renderAnalyzedRegion(
        source: PhotoSource, recipe: EditRecipe, region: CropRect, outputWidth: Int, outputHeight: Int,
    ): PreviewRenderResult = finishAnalyzed(
        renderRegionGraded(source, recipe, region, outputWidth, outputHeight), recipe, region,
    )

    private fun renderPreviewGraded(source: PhotoSource, recipe: EditRecipe, maxEdge: Int): Bitmap {
        require(maxEdge > 0) { "预览尺寸必须大于零" }
        val gl = acquireRenderer()
        val fullSize = Geometry.outputSize(source, recipe)
        val scale = min(1.0, maxEdge.toDouble() / max(fullSize.width, fullSize.height))
        val width = max(1, (fullSize.width * scale).roundToInt())
        val height = max(1, (fullSize.height * scale).roundToInt())
        // One consistently sampled source texture avoids independently sampled tile seams.
        val decodeLimit = min(gl.textureLimit, maxEdge * 2).coerceAtLeast(1)
        var sample = 1
        while (ceil(max(source.width, source.height).toDouble() / sample) > decodeLimit) sample *= 2
        val preview = obtainPreview(source, sample)
        checkMemory(width, height, gl.tileLimit, "预览")
        val output = allocateOutput(width, height)
        try {
            gl.setRecipe(recipe)
            gl.uploadSource(preview)
            eachTile(width, height, gl.tileLimit) { left, top, tileWidth, tileHeight ->
                val coordinates = mappedCorners(source, recipe, left, top, tileWidth, tileHeight, width, height)
                gl.renderTile(output, left, top, tileWidth, tileHeight, coordinates.toTextureCoordinates())
            }
            return output
        } catch (error: Throwable) {
            output.recycle()
            throw renderFailure(error)
        }
    }

    fun renderExport(source: PhotoSource, recipe: EditRecipe): Bitmap = renderExport(source, recipe, ExportOptions())

    /** Render straight into the selected size; no full-size intermediate bitmap is created. */
    fun renderExport(source: PhotoSource, recipe: EditRecipe, options: ExportOptions): Bitmap {
        val size = options.resolveSize(Geometry.outputSize(source, recipe))
        return paintWatermark(
            renderRegionGraded(source, recipe, CropRect(), size.width, size.height, "导出"), recipe, CropRect(),
        )
    }

    private fun renderRegionGraded(
        source: PhotoSource,
        recipe: EditRecipe,
        outputRegion: CropRect,
        outputWidth: Int,
        outputHeight: Int,
        action: String = "局部预览",
    ): Bitmap {
        validateRegion(outputRegion)
        require(outputWidth > 0 && outputHeight > 0) { "预览尺寸必须大于零" }
        val gl = acquireRenderer()
        checkMemory(outputWidth, outputHeight, gl.tileLimit, action)
        val decoder = try {
            BitmapRegionDecoder.newInstance(source.localPath)
        } catch (error: OutOfMemoryError) {
            throw IllegalStateException("设备内存不足，无法打开原图；编辑内容已保留", error)
        } catch (error: Exception) {
            throw IllegalStateException("此图片格式无法分块导出，请先转换为 JPEG 或 PNG", error)
        }
        var output: Bitmap? = null
        try {
            val result = allocateOutput(outputWidth, outputHeight)
            output = result
            gl.setRecipe(recipe)
            // A global sample grid preserves seams when the viewport is smaller than its source ROI.
            val strides = regionPixelStrides(source, recipe, outputRegion, outputWidth, outputHeight)
            var sample = 1
            while (sample <= Int.MAX_VALUE / 2 && sample * 2 <= min(strides.first, strides.second)) sample *= 2
            val largestStride = max(strides.first, strides.second).coerceAtLeast(.000001f)
            // An unusual aspect ratio can map even one output pixel to a very wide source strip.
            while (sample <= Int.MAX_VALUE / 2 && largestStride / sample + 6 > gl.textureLimit) sample *= 2
            val tileLimit = min(gl.tileLimit, floor((gl.textureLimit - 6f) * sample / largestStride).toInt()).coerceAtLeast(1)
            val options = decodeOptions(sample)
            eachTile(outputWidth, outputHeight, tileLimit) { left, top, tileWidth, tileHeight ->
                val corners = mappedCorners(source, recipe, left, top, tileWidth, tileHeight, outputWidth, outputHeight, outputRegion)
                // Include surrounding source pixels so linear filtering is seamless across tiles.
                val region = enclosingRegion(corners, source.width, source.height, sample)
                val bitmap = decoder.decodeRegion(region, options)
                    ?: error("无法读取原图分块，请重新导入图片")
                try {
                    gl.uploadSource(bitmap)
                    val coordinates = FloatArray(8)
                    corners.forEachIndexed { index, point ->
                        coordinates[index * 2] = (point.x * source.width - region.left) / (bitmap.width * sample)
                        coordinates[index * 2 + 1] = (point.y * source.height - region.top) / (bitmap.height * sample)
                    }
                    gl.renderTile(result, left, top, tileWidth, tileHeight, coordinates)
                } finally {
                    bitmap.recycle()
                }
            }
            return result
        } catch (error: Throwable) {
            output?.recycle()
            throw renderFailure(error)
        } finally {
            decoder.recycle()
        }
    }

    private fun finishAnalyzed(output: Bitmap, recipe: EditRecipe, region: CropRect): PreviewRenderResult {
        var overlay: Bitmap? = null
        try {
            checkMemory(output.width, output.height, 0, "分析预览")
            val mask = allocateOutput(output.width, output.height)
            overlay = mask
            val accumulator = PixelAnalysis.Accumulator()
            val pixels = IntArray(output.width)
            val clipping = IntArray(output.width)
            for (y in 0 until output.height) {
                output.getPixels(pixels, 0, output.width, 0, y, output.width, 1)
                accumulator.add(pixels)
                for (x in pixels.indices) clipping[x] = PixelAnalysis.clippingColor(pixels[x])
                mask.setPixels(clipping, 0, output.width, 0, y, output.width, 1)
            }
            WatermarkPainter.draw(output, recipe.watermark, region)
            return PreviewRenderResult(output, accumulator.finish(), mask)
        } catch (error: Throwable) {
            output.recycle()
            overlay?.recycle()
            throw renderFailure(error)
        }
    }

    private fun paintWatermark(output: Bitmap, recipe: EditRecipe, region: CropRect): Bitmap {
        try {
            WatermarkPainter.draw(output, recipe.watermark, region)
            return output
        } catch (error: Throwable) {
            output.recycle()
            throw renderFailure(error)
        }
    }

    private fun validateRegion(region: CropRect) {
        require(listOf(region.left, region.top, region.right, region.bottom).all { it.isFinite() && it in 0f..1f }
            && region.width > 0f && region.height > 0f) { "局部预览范围无效" }
    }

    private fun regionPixelStrides(source: PhotoSource, recipe: EditRecipe, region: CropRect, width: Int, height: Int): Pair<Float, Float> {
        val corners = mappedCorners(source, recipe, 0, 0, width, height, width, height, region)
        val horizontalPixels = max(
            kotlin.math.abs(corners[2].x - corners[0].x) * source.width,
            kotlin.math.abs(corners[2].y - corners[0].y) * source.height,
        ) / width
        val verticalPixels = max(
            kotlin.math.abs(corners[1].x - corners[0].x) * source.width,
            kotlin.math.abs(corners[1].y - corners[0].y) * source.height,
        ) / height
        return horizontalPixels to verticalPixels
    }

    private fun acquireRenderer(): HeadlessGlRenderer {
        check(!closed) { "图片处理器已关闭" }
        val current = Thread.currentThread()
        val owner = ownerThread
        check(owner == null || current === owner) { "图片处理必须在同一个后台线程执行" }
        ownerThread = current
        return renderer ?: HeadlessGlRenderer().also { renderer = it }
    }

    private fun obtainPreview(source: PhotoSource, sample: Int): Bitmap {
        if (cachedPreviewPath == source.localPath && cachedPreviewSample == sample) {
            cachedPreview?.let { if (!it.isRecycled) return it }
        }
        cachedPreview?.recycle()
        cachedPreview = null
        val bitmap = try {
            BitmapFactory.decodeFile(source.localPath, decodeOptions(sample))
                ?: error("无法读取图片，请选择 JPEG、PNG 或 WebP 图片")
        } catch (error: OutOfMemoryError) {
            throw IllegalStateException("设备内存不足，无法创建图片预览", error)
        }
        cachedPreviewPath = source.localPath
        cachedPreviewSample = sample
        cachedPreview = bitmap
        return bitmap
    }

    private fun decodeOptions(sample: Int) = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        inScaled = false
        inMutable = false
    }

    private fun allocateOutput(width: Int, height: Int): Bitmap = try {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, true, ColorSpace.get(ColorSpace.Named.SRGB))
    } catch (error: OutOfMemoryError) {
        throw IllegalStateException("设备内存不足，无法处理此尺寸的图片；编辑内容已保留", error)
    }

    private fun checkMemory(width: Int, height: Int, tileSize: Int, action: String) {
        check(width > 0 && height > 0) { "图片尺寸无效" }
        val outputBytes = width.toLong() * height * 4
        val tileBytes = (tileSize + 4L) * (tileSize + 4L) * 20L
        val runtime = Runtime.getRuntime()
        val available = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
        val reserve = 24L * 1024 * 1024
        check(outputBytes <= Int.MAX_VALUE && outputBytes + tileBytes + reserve < available) {
            val retry = if (action == "导出") "请选择较小导出尺寸或缩小裁剪范围后重试" else "请缩小裁剪范围后重试"
            "设备可用内存不足，无法${action} ${width} × ${height} 图片；编辑内容已保留，$retry"
        }
    }

    private inline fun eachTile(
        width: Int,
        height: Int,
        tileSize: Int,
        render: (left: Int, top: Int, width: Int, height: Int) -> Unit,
    ) {
        var top = 0
        while (top < height) {
            var left = 0
            val tileHeight = min(tileSize, height - top)
            while (left < width) {
                val tileWidth = min(tileSize, width - left)
                render(left, top, tileWidth, tileHeight)
                left += tileWidth
            }
            top += tileHeight
        }
    }

    private fun mappedCorners(
        source: PhotoSource,
        recipe: EditRecipe,
        left: Int,
        top: Int,
        tileWidth: Int,
        tileHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        region: CropRect = CropRect(),
    ): List<NormalizedPoint> {
        val u0 = region.left + left.toFloat() / outputWidth * region.width
        val v0 = region.top + top.toFloat() / outputHeight * region.height
        val u1 = region.left + (left + tileWidth).toFloat() / outputWidth * region.width
        val v1 = region.top + (top + tileHeight).toFloat() / outputHeight * region.height
        return listOf(
            Geometry.outputToSource(u0, v0, recipe, source.exifOrientation),
            Geometry.outputToSource(u0, v1, recipe, source.exifOrientation),
            Geometry.outputToSource(u1, v0, recipe, source.exifOrientation),
            Geometry.outputToSource(u1, v1, recipe, source.exifOrientation),
        )
    }

    private fun enclosingRegion(corners: List<NormalizedPoint>, width: Int, height: Int, sample: Int = 1): Rect {
        val left = ((floor(corners.minOf { it.x }.toDouble() * width / sample).toInt() - 2) * sample).coerceIn(0, width - 1)
        val top = ((floor(corners.minOf { it.y }.toDouble() * height / sample).toInt() - 2) * sample).coerceIn(0, height - 1)
        val right = ((ceil(corners.maxOf { it.x }.toDouble() * width / sample).toInt() + 2) * sample).coerceIn(left + 1, width)
        val bottom = ((ceil(corners.maxOf { it.y }.toDouble() * height / sample).toInt() + 2) * sample).coerceIn(top + 1, height)
        return Rect(left, top, right, bottom)
    }

    private fun List<NormalizedPoint>.toTextureCoordinates() = FloatArray(8).also { result ->
        forEachIndexed { index, point ->
            result[index * 2] = point.x
            result[index * 2 + 1] = point.y
        }
    }

    private fun renderFailure(error: Throwable): Throwable = when (error) {
        is OutOfMemoryError -> IllegalStateException("设备内存不足，图片处理失败；编辑内容已保留", error)
        else -> error
    }

    override fun close() {
        if (closed) return
        check(ownerThread == null || ownerThread === Thread.currentThread()) { "图片处理器必须在原后台线程关闭" }
        cachedPreview?.recycle()
        cachedPreview = null
        renderer?.close()
        renderer = null
        closed = true
    }
}
