package com.kang.imageeditapp

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.kang.imageeditapp.ui.PreviewPixelScaleKey
import com.kang.imageeditapp.ui.PreviewViewportMode
import com.kang.imageeditapp.ui.PreviewViewportModeKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.abs
import kotlin.math.min

internal fun ComposeContentTestRule.previewPixelScale(): Float = onNodeWithTag("photo-canvas").fetchSemanticsNode().config[PreviewPixelScaleKey]

internal fun ComposeContentTestRule.previewMode(): PreviewViewportMode = onNodeWithTag("photo-canvas").fetchSemanticsNode().config[PreviewViewportModeKey]

/** Canvas-local point to the left of the floating actions, away from the top analysis controls. */
internal fun ComposeContentTestRule.safePreviewPoint(): Offset {
    val canvas = onNodeWithTag("photo-canvas").fetchSemanticsNode().boundsInRoot
    val actions = onNodeWithTag("preview-actions").fetchSemanticsNode().boundsInRoot
    val safeRight = min(canvas.width, actions.left - canvas.left - 12f)
    assertTrue("The canvas needs a gesture area beside the actions: canvas=$canvas actions=$actions", safeRight > 24f)
    return Offset(min(canvas.width * .25f, safeRight / 2f), canvas.height / 2f)
}

internal fun ComposeContentTestRule.showNativePreview() {
    if (abs(previewPixelScale() - 1f) > .0001f) {
        val point = safePreviewPoint()
        onNodeWithTag("photo-canvas").performTouchInput { doubleClick(point) }
        waitForIdle()
    }
    assertEquals("A canvas double tap must reach one image pixel per screen pixel", 1f, previewPixelScale(), .0001f)
}

internal fun ComposeContentTestRule.fitPreview() {
    if (previewMode() != PreviewViewportMode.FIT) {
        showNativePreview()
        val point = safePreviewPoint()
        onNodeWithTag("photo-canvas").performTouchInput { doubleClick(point) }
        waitForIdle()
    }
    assertEquals(PreviewViewportMode.FIT, previewMode())
}
