package com.kang.imageeditapp.ui

import androidx.compose.runtime.saveable.SaverScope
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

    @Test fun resizePreservesCustomPixelScaleAndCenter() {
        val original = ViewportGeometry(800, 600, 4000, 3000)
        val viewport = PreviewViewport(3f, -180f, 90f)
        val originalFrame = original.frame(viewport)
        for (resized in listOf(original.copy(viewWidth = 600, viewHeight = 280), original.copy(viewWidth = 1100, viewHeight = 800))) {
            val projected = resized.remap(viewport, original)
            val frame = resized.frame(projected)
            assertEquals(original.fitScale * viewport.zoom, resized.fitScale * projected.zoom, .00001f)
            assertEquals(originalFrame.xToImage(original.viewWidth / 2f), frame.xToImage(resized.viewWidth / 2f), .00001f)
            assertEquals(originalFrame.yToImage(original.viewHeight / 2f), frame.yToImage(resized.viewHeight / 2f), .00001f)
        }
    }

    @Test fun nativeViewRemainsNativeAcrossPanelAndKeyboardSizes() {
        for (original in listOf(ViewportGeometry(800, 600, 4000, 3000), ViewportGeometry(800, 600, 640, 480))) {
            val state = PreviewViewportState()
            state.update(PreviewViewport(original.nativeZoom), original)
            assertEquals(PreviewViewportMode.CUSTOM, state.mode)
            for (resized in listOf(original.copy(viewHeight = 250), original.copy(viewWidth = 1100, viewHeight = 800))) {
                val frame = resized.frame(state.viewportFor(resized))
                assertEquals(resized.imageWidth.toFloat(), frame.width, .001f)
                assertEquals(resized.imageHeight.toFloat(), frame.height, .001f)
            }
        }
    }

    @Test fun fitFollowsAvailableAreaAndClearsCustomView() {
        val original = ViewportGeometry(800, 600, 4000, 3000)
        val resized = original.copy(viewHeight = 300)
        val state = PreviewViewportState()
        assertEquals(PreviewViewportMode.FIT, state.mode)
        assertEquals(PreviewViewport(), state.viewportFor(resized))
        assertEquals(300f, resized.frame(state.viewportFor(resized)).height, .001f)
        state.update(PreviewViewport(3f, 120f, -90f), original)
        state.fit()
        assertEquals(PreviewViewportMode.FIT, state.mode)
        assertEquals(PreviewViewport(), state.viewportFor(original))
        assertEquals(PreviewViewport(), state.viewportFor(resized))
        state.update(PreviewViewport(2f), original)
        state.update(PreviewViewport(4f), resized, fitted = true)
        assertEquals(PreviewViewportMode.FIT, state.mode)
    }

    @Test fun projectionsDoNotOverwriteTheSavedCustomView() {
        val original = ViewportGeometry(800, 600, 4000, 3000)
        val viewport = PreviewViewport(2f, 350f, -250f)
        val state = PreviewViewportState()
        state.update(viewport, original)
        // A larger area clamps the displayed pan, but restoring the original area restores its view.
        val enlarged = original.copy(viewWidth = 1500, viewHeight = 1100)
        state.viewportFor(enlarged)
        assertEquals(viewport, state.viewportFor(original))
        state.viewportFor(original.copy(viewWidth = 0, viewHeight = 0))
        state.update(PreviewViewport(), original.copy(viewWidth = 0, viewHeight = 0))
        assertEquals(PreviewViewportMode.CUSTOM, state.mode)
        assertEquals(viewport, state.viewportFor(original))
    }

    @Test fun resizedCustomViewClampsItsPanAtImageEdges() {
        val original = ViewportGeometry(800, 600, 4000, 3000)
        val resized = original.copy(viewWidth = 1500, viewHeight = 1100)
        val projected = resized.remap(PreviewViewport(2f, 400f, -300f), original)
        val frame = resized.frame(projected)
        assertEquals(0f, frame.left, .001f)
        assertEquals(resized.viewHeight.toFloat(), frame.bottom, .001f)
        val bounds = resized.visibleBounds(projected)
        assertTrue(bounds.left >= 0f && bounds.right <= 1f)
        assertTrue(bounds.top >= 0f && bounds.bottom <= 1f)
    }

    @Test fun customViewBelowNewFitKeepsItsPixelScaleWhenPanelCloses() {
        val panelOpen = ViewportGeometry(800, 300, 1200, 2400)
        val panelClosed = panelOpen.copy(viewHeight = 900)
        val state = PreviewViewportState()
        state.update(PreviewViewport(1.2f), panelOpen)
        val projected = state.viewportFor(panelClosed)
        assertTrue(projected.zoom < panelClosed.minimumZoom)
        assertEquals(.15f, panelClosed.fitScale * projected.zoom, .00001f)
        assertEquals(360f, panelClosed.frame(projected).height, .001f)
        state.update(panelClosed.constrain(projected.copy(panX = 50f), preserveScale = true), panelClosed)
        assertEquals(.15f, panelClosed.fitScale * state.viewportFor(panelClosed).zoom, .00001f)
        val stationaryGesture = panelClosed.transform(projected, 1f, 0f, 0f, 400f, 450f)
        assertEquals(projected.zoom, stationaryGesture.zoom, .00001f)
        val pinchIn = panelClosed.transform(projected, 1.1f, 0f, 0f, 400f, 450f)
        assertEquals(.165f, panelClosed.fitScale * pinchIn.zoom, .00001f)
    }

    @Test fun customViewAboveNewGestureMaximumDoesNotSnapOnFirstGesture() {
        val original = ViewportGeometry(800, 600, 4000, 3000)
        val resized = original.copy(viewWidth = 100, viewHeight = 75)
        val state = PreviewViewportState()
        state.update(PreviewViewport(8f), original)
        val projected = state.viewportFor(resized)
        assertTrue(projected.zoom > resized.maximumZoom)
        assertEquals(1.6f, resized.fitScale * projected.zoom, .00001f)
        val pan = resized.transform(projected, 1f, 10f, 5f, 50f, 37.5f)
        assertEquals(1.6f, resized.fitScale * pan.zoom, .00001f)
    }

    @Test fun saverRestoresFitAndCustomViewsAcrossActivityRecreation() {
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        val original = ViewportGeometry(800, 300, 1200, 2400)
        val rotated = original.copy(viewWidth = 1100, viewHeight = 700)
        val state = PreviewViewportState()
        state.update(PreviewViewport(1.2f, 0f, 20f), original)
        val savedCustom = with(PreviewViewportState.Saver) { with(scope) { save(state) } }!!
        val restoredCustom = PreviewViewportState.Saver.restore(savedCustom)!!
        assertEquals(PreviewViewportMode.CUSTOM, restoredCustom.mode)
        assertEquals(state.viewportFor(original), restoredCustom.viewportFor(original))
        assertEquals(state.viewportFor(rotated), restoredCustom.viewportFor(rotated))
        state.fit()
        val savedFit = with(PreviewViewportState.Saver) { with(scope) { save(state) } }!!
        val restoredFit = PreviewViewportState.Saver.restore(savedFit)!!
        assertEquals(PreviewViewportMode.FIT, restoredFit.mode)
        assertEquals(PreviewViewport(), restoredFit.viewportFor(rotated))
    }
}
