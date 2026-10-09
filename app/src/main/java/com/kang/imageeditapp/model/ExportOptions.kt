package com.kang.imageeditapp.model

import kotlin.math.max
import kotlin.math.roundToInt

enum class ExportResolution { ORIGINAL, LONG_2048, LONG_1080, CUSTOM_LONG }

/** Export settings are independent of the edit recipe, drafts and undo history. */
data class ExportOptions(
    val format: ExportFormat = ExportFormat.JPEG,
    val resolution: ExportResolution = ExportResolution.ORIGINAL,
    val customLongEdge: Int = 2048,
    val jpegQuality: Int = 95,
) {
    fun validate() {
        require(jpegQuality in 50..100) { "JPEG 质量必须在 50 到 100 之间" }
        if (resolution == ExportResolution.CUSTOM_LONG) {
            require(customLongEdge in 1..20000) { "自定义长边必须在 1 到 20000 像素之间" }
        }
    }

    /** The input is the final canvas after EXIF orientation, rotation and crop. */
    fun resolveSize(original: ImageSize): ImageSize {
        validate()
        require(original.width > 0 && original.height > 0) { "导出图片尺寸无效" }
        val longEdge = max(original.width, original.height)
        val requested = when (resolution) {
            ExportResolution.ORIGINAL -> longEdge
            ExportResolution.LONG_2048 -> 2048
            ExportResolution.LONG_1080 -> 1080
            ExportResolution.CUSTOM_LONG -> customLongEdge
        }
        if (requested >= longEdge) return original
        val scale = requested.toDouble() / longEdge
        return ImageSize(
            (original.width * scale).roundToInt().coerceAtLeast(1),
            (original.height * scale).roundToInt().coerceAtLeast(1),
        )
    }
}
