package com.kang.imageeditapp.render

import org.junit.Assert.*
import org.junit.Test

class PixelAnalysisTest {
    @Test fun grayscaleHasMatchingRgbAndBrightnessBins() {
        val pixels = IntArray(256) { value -> 0xFF000000.toInt() or (value shl 16) or (value shl 8) or value }
        val analysis = PixelAnalysis.analyze(pixels)
        for (bin in 0..255) {
            assertEquals(1, analysis.red[bin])
            assertEquals(1, analysis.green[bin])
            assertEquals(1, analysis.blue[bin])
            assertEquals(1, analysis.luminance[bin])
        }
        assertEquals(256L, analysis.sampleCount)
        assertEquals(1L, analysis.highlightCount)
        assertEquals(1L, analysis.shadowCount)
    }

    @Test fun knownColorsUseDisplayedSrgbBrightnessAndIgnoreFullyTransparentPixels() {
        val analysis = PixelAnalysis.analyze(intArrayOf(
            0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(),
            0x80808080.toInt(), 0x00FFFFFF, 0x00000000,
        ))
        assertEquals(4L, analysis.sampleCount)
        assertEquals(3L, analysis.highlightCount)
        assertEquals(0L, analysis.shadowCount)
        for (bin in listOf(54, 182, 18, 128)) assertEquals(1, analysis.luminance[bin])
        assertEquals(2, analysis.red[0])
        assertEquals(1, analysis.red[128])
        assertEquals(1, analysis.red[255])
    }

    @Test fun clippingRequiresOneFullChannelForHighlightsAndAllZeroForShadows() {
        assertEquals(PixelAnalysis.HIGHLIGHT_COLOR, PixelAnalysis.clippingColor(0xFF12FF34.toInt()))
        assertEquals(PixelAnalysis.HIGHLIGHT_COLOR, PixelAnalysis.clippingColor(0x01FF2233))
        assertEquals(PixelAnalysis.SHADOW_COLOR, PixelAnalysis.clippingColor(0xFF000000.toInt()))
        assertEquals(0, PixelAnalysis.clippingColor(0xFF000001.toInt()))
        assertEquals(0, PixelAnalysis.clippingColor(0xFFFEFEFE.toInt()))
        assertEquals(0, PixelAnalysis.clippingColor(0x00FFFFFF))
    }

    @Test fun emptyAnalysisHasNoSamplesOrClipping() {
        val analysis = PixelAnalysis.analyze(intArrayOf())
        assertEquals(0L, analysis.sampleCount)
        assertEquals(0L, analysis.highlightCount)
        assertEquals(0L, analysis.shadowCount)
        assertTrue(analysis.luminance.all { it == 0 })
    }
}
