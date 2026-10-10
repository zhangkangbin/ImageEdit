package com.kang.imageeditapp.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsPropertyKey
import com.kang.imageeditapp.model.CropRect
import kotlin.math.max
import kotlin.math.min

/** View-only geometry. Coordinates and dimensions are physical screen pixels, never dp. */
data class PreviewViewport(val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f)

enum class PreviewViewportMode { FIT, CUSTOM }

/** Nonvisual view metadata for UI verification; it does not enter the edit recipe. */
val PreviewPixelScaleKey = SemanticsPropertyKey<Float>("PreviewPixelScale")
val PreviewViewportModeKey = SemanticsPropertyKey<PreviewViewportMode>("PreviewViewportMode")

/**
 * Owned by the editing workspace so opening panels or recreating the canvas does not lose the view.
 * Fit follows the available area; a custom view retains its pixel scale and image center when resized.
 * Reset this state when the source image or its actual geometry changes.
 */
@Stable
class PreviewViewportState {
    private data class CustomView(val viewport: PreviewViewport, val geometry: ViewportGeometry)
    private var customView by mutableStateOf<CustomView?>(null)

    val mode: PreviewViewportMode get() = if (customView == null) PreviewViewportMode.FIT else PreviewViewportMode.CUSTOM

    /** Projects the saved view without writing state during composition. */
    fun viewportFor(geometry: ViewportGeometry): PreviewViewport {
        val custom = customView ?: return PreviewViewport()
        return geometry.remap(custom.viewport, custom.geometry)
    }

    fun update(viewport: PreviewViewport, geometry: ViewportGeometry, fitted: Boolean = false) {
        if (fitted) fit()
        else if (geometry.valid) customView = CustomView(geometry.constrain(viewport, preserveScale = true), geometry)
    }

    fun fit() { customView = null }

    companion object {
        val Saver = listSaver<PreviewViewportState, Any>(
            save = { state ->
                val custom = state.customView
                listOf(
                    custom == null,
                    custom?.geometry?.viewWidth ?: 0, custom?.geometry?.viewHeight ?: 0,
                    custom?.geometry?.imageWidth ?: 0, custom?.geometry?.imageHeight ?: 0,
                    custom?.viewport?.zoom ?: 1f, custom?.viewport?.panX ?: 0f, custom?.viewport?.panY ?: 0f,
                )
            },
            restore = { saved ->
                PreviewViewportState().apply {
                    if (!(saved[0] as Boolean)) {
                        val geometry = ViewportGeometry(saved[1] as Int, saved[2] as Int, saved[3] as Int, saved[4] as Int)
                        update(PreviewViewport(saved[5] as Float, saved[6] as Float, saved[7] as Float), geometry)
                    }
                }
            },
        )
    }
}

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

    fun constrain(viewport: PreviewViewport, preserveScale: Boolean = false): PreviewViewport {
        if (!valid) return PreviewViewport()
        val requestedZoom = viewport.zoom.takeIf { it.isFinite() && it > 0f } ?: 1f
        val zoom = if (preserveScale) requestedZoom else requestedZoom.coerceIn(minimumZoom, maximumZoom)
        val excessX = imageWidth * fitScale * zoom - viewWidth
        val excessY = imageHeight * fitScale * zoom - viewHeight
        val limitX = if (excessX > .01f) excessX / 2f else 0f
        val limitY = if (excessY > .01f) excessY / 2f else 0f
        return PreviewViewport(zoom,
            (viewport.panX.takeIf { it.isFinite() } ?: 0f).coerceIn(-limitX, limitX),
            (viewport.panY.takeIf { it.isFinite() } ?: 0f).coerceIn(-limitY, limitY),
        )
    }

    fun frame(viewport: PreviewViewport): PreviewFrame {
        if (!valid) return PreviewFrame(0f, 0f, 0f, 0f)
        val bounded = constrain(viewport, preserveScale = true)
        val width = imageWidth * fitScale * bounded.zoom
        val height = imageHeight * fitScale * bounded.zoom
        val left = (viewWidth - width) / 2f + bounded.panX
        val top = (viewHeight - height) / 2f + bounded.panY
        return PreviewFrame(left, top, left + width, top + height)
    }

    /** Preserve the actual pixel scale and center image point, with pan bounded by the new area. */
    fun remap(viewport: PreviewViewport, previous: ViewportGeometry): PreviewViewport {
        if (!valid || !previous.valid) return PreviewViewport()
        if (previous == this) return constrain(viewport, preserveScale = true)
        val old = previous.constrain(viewport, preserveScale = true)
        val oldFrame = previous.frame(old)
        val scale = previous.fitScale * old.zoom
        val centerX = oldFrame.xToImage(previous.viewWidth / 2f)
        val centerY = oldFrame.yToImage(previous.viewHeight / 2f)
        return constrain(PreviewViewport(
            zoom = scale / fitScale,
            panX = (.5f - centerX) * imageWidth * scale,
            panY = (.5f - centerY) * imageHeight * scale,
        ), preserveScale = true)
    }

    /** Keep the image point under the gesture centroid fixed while zooming and panning. */
    fun transform(viewport: PreviewViewport, factor: Float, deltaX: Float, deltaY: Float, focusX: Float, focusY: Float): PreviewViewport {
        val old = constrain(viewport, preserveScale = true)
        // Resizing can place a custom view outside the usual fit-relative gesture bounds.
        // Include that starting scale so the next gesture never snaps to a different magnification.
        val zoom = (old.zoom * factor).coerceIn(min(minimumZoom, old.zoom), max(maximumZoom, old.zoom))
        val ratio = zoom / old.zoom
        val fromCenterX = focusX - viewWidth / 2f
        val fromCenterY = focusY - viewHeight / 2f
        return constrain(PreviewViewport(zoom, fromCenterX - (fromCenterX - old.panX) * ratio + deltaX, fromCenterY - (fromCenterY - old.panY) * ratio + deltaY), preserveScale = true)
    }

    fun visibleBounds(viewport: PreviewViewport): CropRect {
        val frame = frame(viewport)
        if (!valid) return CropRect()
        return CropRect(frame.xToImage(0f).coerceIn(0f, 1f), frame.yToImage(0f).coerceIn(0f, 1f), frame.xToImage(viewWidth.toFloat()).coerceIn(0f, 1f), frame.yToImage(viewHeight.toFloat()).coerceIn(0f, 1f))
    }
}
