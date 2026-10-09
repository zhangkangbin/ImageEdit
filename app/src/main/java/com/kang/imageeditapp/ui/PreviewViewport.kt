package com.kang.imageeditapp.ui

import com.kang.imageeditapp.model.CropRect
import kotlin.math.max
import kotlin.math.min

/** View-only geometry. Coordinates and dimensions are physical screen pixels, never dp. */
data class PreviewViewport(val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f)

data class PreviewFrame(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun xToImage(x: Float) = (x - left) / width
    fun yToImage(y: Float) = (y - top) / height
}

/** Defines fit, native pixel scale, bounded pan, and the exact visible decode region. */
data class ViewportGeometry(val viewWidth: Int, val viewHeight: Int, val imageWidth: Int, val imageHeight: Int) {
    val valid get() = viewWidth > 0 && viewHeight > 0 && imageWidth > 0 && imageHeight > 0
    val fitScale: Float get() = if (valid) min(viewWidth.toFloat() / imageWidth, viewHeight.toFloat() / imageHeight) else 1f
    val nativeZoom: Float get() = 1f / fitScale
    // Small images can be fitted larger than 100%, so native size must also be reachable below fit.
    val minimumZoom: Float get() = min(1f, nativeZoom)
    val maximumZoom: Float get() = max(8f, nativeZoom)

    fun constrain(viewport: PreviewViewport): PreviewViewport {
        if (!valid) return PreviewViewport()
        val zoom = viewport.zoom.coerceIn(minimumZoom, maximumZoom)
        val excessX = imageWidth * fitScale * zoom - viewWidth
        val excessY = imageHeight * fitScale * zoom - viewHeight
        val limitX = if (excessX > .01f) excessX / 2f else 0f
        val limitY = if (excessY > .01f) excessY / 2f else 0f
        return PreviewViewport(zoom, viewport.panX.coerceIn(-limitX, limitX), viewport.panY.coerceIn(-limitY, limitY))
    }

    fun frame(viewport: PreviewViewport): PreviewFrame {
        if (!valid) return PreviewFrame(0f, 0f, 0f, 0f)
        val bounded = constrain(viewport)
        val width = imageWidth * fitScale * bounded.zoom
        val height = imageHeight * fitScale * bounded.zoom
        val left = (viewWidth - width) / 2f + bounded.panX
        val top = (viewHeight - height) / 2f + bounded.panY
        return PreviewFrame(left, top, left + width, top + height)
    }

    /** Keep the image point under the gesture centroid fixed while zooming and panning. */
    fun transform(viewport: PreviewViewport, factor: Float, deltaX: Float, deltaY: Float, focusX: Float, focusY: Float): PreviewViewport {
        val old = constrain(viewport)
        val zoom = (old.zoom * factor).coerceIn(minimumZoom, maximumZoom)
        val ratio = zoom / old.zoom
        val fromCenterX = focusX - viewWidth / 2f
        val fromCenterY = focusY - viewHeight / 2f
        return constrain(PreviewViewport(zoom, fromCenterX - (fromCenterX - old.panX) * ratio + deltaX, fromCenterY - (fromCenterY - old.panY) * ratio + deltaY))
    }

    fun visibleBounds(viewport: PreviewViewport): CropRect {
        val frame = frame(viewport)
        if (!valid) return CropRect()
        return CropRect(frame.xToImage(0f).coerceIn(0f, 1f), frame.yToImage(0f).coerceIn(0f, 1f), frame.xToImage(viewWidth.toFloat()).coerceIn(0f, 1f), frame.yToImage(viewHeight.toFloat()).coerceIn(0f, 1f))
    }
}
