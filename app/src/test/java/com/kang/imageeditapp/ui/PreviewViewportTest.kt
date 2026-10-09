package com.kang.imageeditapp.ui

import org.junit.Assert.*
import org.junit.Test

class PreviewViewportTest {
    @Test fun nativePixelsReachableForLargeAndSmallPhotos() {
        for (g in listOf(ViewportGeometry(1080, 720, 12000, 8000), ViewportGeometry(1080, 720, 640, 480))) {
            val frame = g.frame(PreviewViewport(g.nativeZoom))
            assertEquals(g.imageWidth.toFloat(), frame.width, .01f)
            assertEquals(g.imageHeight.toFloat(), frame.height, .01f)
            assertTrue(g.maximumZoom >= g.nativeZoom)
        }
    }

    @Test fun zoomPreservesImagePointUnderFingers() {
        val g = ViewportGeometry(800, 600, 4000, 3000)
        val first = g.frame(PreviewViewport())
        val next = g.transform(PreviewViewport(), 3f, 0f, 0f, 480f, 330f)
        val frame = g.frame(next)
        assertEquals(first.xToImage(480f), frame.xToImage(480f), .00001f)
        assertEquals(first.yToImage(330f), frame.yToImage(330f), .00001f)
    }

    @Test fun panClampsAtEdgesAndVisibleBoundsMatchScreen() {
        val g = ViewportGeometry(800, 600, 4000, 3000)
        val v = g.constrain(PreviewViewport(2f, 9999f, -9999f))
        assertEquals(400f, v.panX, .01f)
        assertEquals(-300f, v.panY, .01f)
        val bounds = g.visibleBounds(v)
        assertEquals(0f, bounds.left, .00001f)
        assertEquals(.5f, bounds.right, .00001f)
        assertEquals(.5f, bounds.top, .00001f)
        assertEquals(1f, bounds.bottom, .00001f)
    }

    @Test fun fittedAndLetterboxedImagesCannotBePannedOffscreen() {
        val g = ViewportGeometry(800, 600, 3000, 1000)
        val viewport = g.constrain(PreviewViewport(1f, 100f, 200f))
        assertEquals(0f, viewport.panX, 0f)
        assertEquals(0f, viewport.panY, 0f)
        val bounds = g.visibleBounds(viewport)
        assertEquals(0f, bounds.top, 0f)
        assertEquals(1f, bounds.bottom, 0f)
    }

    @Test fun inverseFrameCoordinatesSupportCropAndWatermarkAtZoom() {
        val g = ViewportGeometry(800, 600, 4000, 3000)
        val f = g.frame(PreviewViewport(4f, -200f, 120f))
        assertEquals(.3f, f.xToImage(f.left + .3f * f.width), .00001f)
        assertEquals(.85f, f.yToImage(f.top + .85f * f.height), .00001f)
    }
}
