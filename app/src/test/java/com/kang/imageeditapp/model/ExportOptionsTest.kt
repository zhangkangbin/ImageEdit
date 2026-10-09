package com.kang.imageeditapp.model

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class ExportOptionsTest {
    @Test fun defaultsRetainOriginalSizeAndJpegQuality95() {
        val options = ExportOptions()
        assertEquals(ExportFormat.JPEG, options.format)
        assertEquals(95, options.jpegQuality)
        assertEquals(ImageSize(4032, 3024), options.resolveSize(ImageSize(4032, 3024)))
    }

    @Test fun longEdgePresetsPreserveLandscapeAndPortraitAspectRatios() {
        assertEquals(ImageSize(2048, 1536), ExportOptions(resolution = ExportResolution.LONG_2048).resolveSize(ImageSize(4032, 3024)))
        assertEquals(ImageSize(810, 1080), ExportOptions(resolution = ExportResolution.LONG_1080).resolveSize(ImageSize(3024, 4032)))
    }

    @Test fun customEdgeRoundsToPixelsWithoutLosingThinDimension() {
        val options = ExportOptions(resolution = ExportResolution.CUSTOM_LONG, customLongEdge = 101)
        assertEquals(ImageSize(101, 67), options.resolveSize(ImageSize(3000, 2000)))
        assertEquals(ImageSize(101, 1), options.resolveSize(ImageSize(20000, 1)))
    }

    @Test fun noPresetOrCustomResolutionUpscales() {
        val original = ImageSize(640, 480)
        for (resolution in ExportResolution.entries) {
            assertEquals(original, ExportOptions(resolution = resolution, customLongEdge = 20000).resolveSize(original))
        }
    }

    @Test fun outputResolutionUsesPostExifRotationAndCropCanvas() {
        for (orientation in 1..8) for (turn in 0..3) {
            val transformed = Geometry.transformedSize(4000, 3000, orientation, turn)
            val cropped = ImageSize((transformed.width * .5f).roundToInt(), (transformed.height * .75f).roundToInt())
            val result = ExportOptions(resolution = ExportResolution.CUSTOM_LONG, customLongEdge = 1000).resolveSize(cropped)
            assertEquals(1000, maxOf(result.width, result.height))
            assertEquals(cropped.width.toDouble() / cropped.height, result.width.toDouble() / result.height, .002)
            assertTrue(result.width <= cropped.width && result.height <= cropped.height)
        }
    }

    @Test fun invalidQualityAndCustomEdgeHaveActionableErrors() {
        for (quality in listOf(-1, 49, 101)) {
            expectInvalid("质量") { ExportOptions(jpegQuality = quality).validate() }
        }
        for (edge in listOf(-1, 0, 20001, Int.MAX_VALUE)) {
            expectInvalid("长边") { ExportOptions(resolution = ExportResolution.CUSTOM_LONG, customLongEdge = edge).resolveSize(ImageSize(4000, 3000)) }
        }
    }

    @Test fun validQualityAndCustomEdgeBoundariesAreAccepted() {
        for (quality in listOf(50, 95, 100)) for (edge in listOf(1, 20000)) {
            val size = ExportOptions(resolution = ExportResolution.CUSTOM_LONG, customLongEdge = edge, jpegQuality = quality)
                .resolveSize(ImageSize(40000, 30000))
            assertEquals(edge, maxOf(size.width, size.height))
            assertTrue(size.width > 0 && size.height > 0)
        }
    }

    @Test fun unusedCustomEdgeDoesNotInvalidateOriginalExport() {
        assertEquals(ImageSize(100, 50), ExportOptions(customLongEdge = 0).resolveSize(ImageSize(100, 50)))
    }

    @Test fun invalidOriginalDimensionsAreRejected() {
        for (size in listOf(ImageSize(0, 1), ImageSize(1, 0), ImageSize(-1, 100))) {
            expectInvalid("尺寸") { ExportOptions().resolveSize(size) }
        }
    }

    private fun expectInvalid(fragment: String, action: () -> Unit) {
        try { action(); fail("Expected invalid option rejection") }
        catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains(fragment)) }
    }
}
