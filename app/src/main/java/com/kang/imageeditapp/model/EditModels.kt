package com.kang.imageeditapp.model

import android.net.Uri
import kotlin.math.roundToInt

data class PhotoSource(
    val uri: Uri,
    val localPath: String,
    val width: Int,
    val height: Int,
    val exifOrientation: Int = 1,
)

data class ColorAdjustments(
    val exposure: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val shadows: Float = 0f,
    val highlights: Float = 0f,
)

data class CurvePoint(val x: Float, val y: Float)
enum class CurveChannel { RGB, RED, GREEN, BLUE }
private fun identityCurve() = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))

data class CurveSet(
    val rgb: List<CurvePoint> = identityCurve(),
    val red: List<CurvePoint> = identityCurve(),
    val green: List<CurvePoint> = identityCurve(),
    val blue: List<CurvePoint> = identityCurve(),
) {
    fun points(channel: CurveChannel): List<CurvePoint> = when (channel) {
        CurveChannel.RGB -> rgb
        CurveChannel.RED -> red
        CurveChannel.GREEN -> green
        CurveChannel.BLUE -> blue
    }

    fun withPoints(channel: CurveChannel, points: List<CurvePoint>): CurveSet {
        val normalized = points.sortedBy { it.x }.map {
            CurvePoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f))
        }
        return when (channel) {
            CurveChannel.RGB -> copy(rgb = normalized)
            CurveChannel.RED -> copy(red = normalized)
            CurveChannel.GREEN -> copy(green = normalized)
            CurveChannel.BLUE -> copy(blue = normalized)
        }
    }
}

data class HslAdjustment(val hue: Float = 0f, val saturation: Float = 0f, val lightness: Float = 0f)
enum class HslBand(val title: String, val centerDegrees: Float, val color: Int) {
    RED("红", 0f, 0xFFEF6C6C.toInt()),
    ORANGE("橙", 30f, 0xFFF2A65A.toInt()),
    YELLOW("黄", 60f, 0xFFEBD56B.toInt()),
    GREEN("绿", 120f, 0xFF71CE8B.toInt()),
    CYAN("青", 180f, 0xFF62DDC8.toInt()),
    BLUE("蓝", 240f, 0xFF77A6F7.toInt()),
    PURPLE("紫", 270f, 0xFFB590ED.toInt()),
    MAGENTA("洋红", 300f, 0xFFE78DCC.toInt()),
}

data class CropRect(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun constrained(): CropRect {
        val l = left.coerceIn(0f, 0.99f)
        val t = top.coerceIn(0f, 0.99f)
        return CropRect(l, t, right.coerceIn(l + 0.01f, 1f), bottom.coerceIn(t + 0.01f, 1f))
    }
}

data class Watermark(
    val text: String = "",
    val color: Int = 0xFFFFFFFF.toInt(),
    val sizeFraction: Float = 0.05f,
    val x: Float = 0.5f,
    val y: Float = 0.85f,
)

data class EditRecipe(
    val adjustments: ColorAdjustments = ColorAdjustments(),
    val curves: CurveSet = CurveSet(),
    val hsl: List<HslAdjustment> = List(8) { HslAdjustment() },
    val crop: CropRect = CropRect(),
    val quarterTurns: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val watermark: Watermark = Watermark(),
)

enum class EditorTool { ADJUST, CURVES, HSL, PRESETS, TEXT, CROP }
enum class ExportFormat(val mimeType: String, val extension: String) {
    JPEG("image/jpeg", "jpg"), PNG("image/png", "png")
}

data class NormalizedPoint(val x: Float, val y: Float)
data class ImageSize(val width: Int, val height: Int)

/** Coordinates denote pixel edges; sampling at (i + .5) / size is consistent for tiles. */
object Geometry {
    fun orientedSize(width: Int, height: Int, exifOrientation: Int): ImageSize =
        if (exifOrientation in 5..8) ImageSize(height, width) else ImageSize(width, height)

    fun transformedSize(source: PhotoSource, recipe: EditRecipe): ImageSize =
        transformedSize(source.width, source.height, source.exifOrientation, recipe.quarterTurns)

    fun transformedSize(width: Int, height: Int, orientation: Int, quarterTurns: Int): ImageSize {
        val size = orientedSize(width, height, orientation)
        return if (Math.floorMod(quarterTurns, 2) == 1) ImageSize(size.height, size.width) else size
    }

    fun outputSize(source: PhotoSource, recipe: EditRecipe): ImageSize {
        val size = transformedSize(source, recipe)
        return ImageSize(
            (size.width * recipe.crop.width).roundToInt().coerceAtLeast(1),
            (size.height * recipe.crop.height).roundToInt().coerceAtLeast(1),
        )
    }

    fun sourceToOriented(x: Float, y: Float, orientation: Int): NormalizedPoint = when (orientation) {
        2 -> NormalizedPoint(1f - x, y)
        3 -> NormalizedPoint(1f - x, 1f - y)
        4 -> NormalizedPoint(x, 1f - y)
        5 -> NormalizedPoint(y, x)
        6 -> NormalizedPoint(1f - y, x)
        7 -> NormalizedPoint(1f - y, 1f - x)
        8 -> NormalizedPoint(y, 1f - x)
        else -> NormalizedPoint(x, y)
    }

    fun orientedToSource(x: Float, y: Float, orientation: Int): NormalizedPoint =
        sourceToOriented(x, y, when (orientation) { 6 -> 8; 8 -> 6; else -> orientation })

    fun sourceToOutput(x: Float, y: Float, recipe: EditRecipe, exifOrientation: Int): NormalizedPoint {
        var p = sourceToOriented(x, y, exifOrientation)
        repeat(Math.floorMod(recipe.quarterTurns, 4)) { p = NormalizedPoint(1f - p.y, p.x) }
        if (recipe.flipHorizontal) p = p.copy(x = 1f - p.x)
        if (recipe.flipVertical) p = p.copy(y = 1f - p.y)
        return NormalizedPoint((p.x - recipe.crop.left) / recipe.crop.width, (p.y - recipe.crop.top) / recipe.crop.height)
    }

    fun outputToSource(u: Float, v: Float, recipe: EditRecipe, exifOrientation: Int): NormalizedPoint {
        var p = NormalizedPoint(recipe.crop.left + u * recipe.crop.width, recipe.crop.top + v * recipe.crop.height)
        if (recipe.flipVertical) p = p.copy(y = 1f - p.y)
        if (recipe.flipHorizontal) p = p.copy(x = 1f - p.x)
        repeat(Math.floorMod(-recipe.quarterTurns, 4)) { p = NormalizedPoint(1f - p.y, p.x) }
        return orientedToSource(p.x, p.y, exifOrientation)
    }
}
