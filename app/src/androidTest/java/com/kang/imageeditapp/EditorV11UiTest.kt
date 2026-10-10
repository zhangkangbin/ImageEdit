package com.kang.imageeditapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kang.imageeditapp.model.CropRect
import com.kang.imageeditapp.model.EditorTool
import com.kang.imageeditapp.model.Geometry
import com.kang.imageeditapp.model.Watermark
import com.kang.imageeditapp.render.PixelAnalysis
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Checks actual view gestures, original-pixel detail and draft entry points in the Activity. */
@RunWith(AndroidJUnit4::class)
class EditorV11UiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: EditorViewModel
    private lateinit var fixture: File
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun prepare() {
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[EditorViewModel::class.java] }
        fixture = File.createTempFile("editor-v11-", ".png", context.cacheDir)
        writeFixture()
    }

    @After fun cleanup() {
        if (::model.isInitialized) {
            compose.runOnIdle { model.dismissError(); model.closePhoto() }
            compose.waitUntil(10_000) { !model.state.value.isLoading }
            compose.runOnIdle { model.deleteDraft() }
            compose.waitUntil(10_000) { !model.state.value.isLoading }
        }
        if (::fixture.isInitialized) fixture.delete()
    }

    private fun writeFixture(width: Int = 1800, height: Int = 1200, transparentWhite: Boolean = false) {
        val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        val paint = Paint()
        if (transparentWhite) {
            canvas.drawColor(Color.argb(128, 255, 255, 255))
        } else {
            canvas.drawColor(Color.rgb(100, 130, 170))
            paint.color = Color.BLACK
            canvas.drawRect(0f, 0f, width / 3f, height.toFloat(), paint)
            paint.color = Color.WHITE
            canvas.drawRect(width * 2f / 3f, 0f, width.toFloat(), height.toFloat(), paint)
        }
        fixture.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }

    private fun importFixture() {
        compose.runOnIdle { model.importPhoto(Uri.fromFile(fixture)) }
        awaitPreview()
    }

    private fun awaitPreview() {
        compose.waitUntil(30_000) {
            val state = model.state.value
            state.error != null || (state.preview != null && state.analysis != null && !state.isLoading && !state.isRendering)
        }
        assertNull(model.state.value.error)
    }

    private fun awaitDetail() {
        compose.waitUntil(30_000) {
            val state = model.state.value
            val detail = state.detail
            val source = state.source
            if (state.error != null) true else if (detail == null || source == null) false else {
                val pixels = if (detail.cropMode) Geometry.transformedSize(source, state.recipe) else Geometry.outputSize(source, state.recipe)
                detail.cropMode == (state.activeTool == EditorTool.CROP) &&
                    abs(detail.bitmap.width - (pixels.width * detail.bounds.width).roundToInt()) <= 2 &&
                    abs(detail.bitmap.height - (pixels.height * detail.bounds.height).roundToInt()) <= 2
            }
        }
        assertNull(model.state.value.error)
        assertNotNull(model.state.value.detail)
    }

    private fun pinchOut(tag: String = "photo-canvas") {
        val point = compose.safePreviewPoint()
        compose.onNodeWithTag(tag).performTouchInput {
            down(0, point - Offset(35f, 0f))
            down(1, point + Offset(35f, 0f))
            for (step in 1..5) {
                advanceEventTime(32)
                moveTo(0, point - Offset(35f + step * 15f, 0f))
                moveTo(1, point + Offset(35f + step * 15f, 0f))
            }
            up(0); up(1)
        }
    }

    private fun clickTool(tag: String) {
        val node = compose.onNodeWithTag("tool-$tag")
        if (compose.activity.resources.configuration.screenWidthDp >= 600) node.performScrollTo()
        node.assertIsDisplayed().performClick()
    }

    @Test fun homepageResumesAppliedDraftAndDeletesIt() {
        importFixture()
        compose.runOnIdle {
            model.selectTool(EditorTool.ADJUST)
            val recipe = model.state.value.recipe
            model.updateRecipe(recipe.copy(adjustments = recipe.adjustments.copy(exposure = .7f)))
            model.applyTool()
        }
        awaitPreview()
        val expected = model.state.value.recipe
        compose.waitUntil(10_000) { model.state.value.draft?.recipe == expected }
        compose.onNodeWithTag("editor-header").assertIsDisplayed()
        compose.onNodeWithTag("action-back").assertIsDisplayed().performClick()
        compose.onNodeWithText("结束编辑").performClick()
        compose.onNodeWithTag("continue-draft").performScrollTo().assertIsDisplayed().performClick()
        awaitPreview()
        assertEquals(expected, model.state.value.recipe)
        assertFalse(model.state.value.canUndo)
        compose.onNodeWithTag("editor-header").assertIsDisplayed()
        compose.onNodeWithTag("action-back").assertIsDisplayed().performClick()
        compose.onNodeWithText("结束编辑").performClick()
        compose.onNodeWithTag("delete-draft").performScrollTo().performClick()
        compose.onNodeWithTag("confirm-delete-draft").performClick()
        compose.waitUntil(10_000) { model.state.value.draft == null && !model.state.value.isLoading }
        compose.onNodeWithTag("continue-draft").assertDoesNotExist()
        compose.onNodeWithTag("import-photo").assertExists()
    }

    @Test fun histogramAndClippingAreViewOnly() {
        importFixture()
        val before = model.state.value
        val analysis = before.analysis!!
        assertTrue(analysis.highlightCount > 0)
        assertTrue(analysis.shadowCount > 0)
        assertEquals(analysis.sampleCount, analysis.red.sum().toLong())
        compose.onNodeWithTag("histogram-plot").assertDoesNotExist()
        compose.onNodeWithTag("toggle-histogram").performClick()
        compose.onNodeWithTag("histogram-plot").assertIsDisplayed()
        compose.onNodeWithTag("histogram-luminance").performClick()
        compose.onNodeWithContentDescription("亮度直方图，256 档").assertExists()
        compose.onNodeWithTag("toggle-clipping").performClick()
        assertEquals(before.recipe, model.state.value.recipe)
        assertEquals(before.canUndo, model.state.value.canUndo)
        assertArrayEquals(analysis.red, model.state.value.analysis!!.red)
        assertNotNull(model.state.value.previewOverlay)
        compose.onNodeWithTag("toggle-histogram").performClick()
        compose.onNodeWithTag("histogram-plot").assertDoesNotExist()
    }

    @Test fun doubleTapTogglesFitAndOriginalPixelsWithoutChangingAnalysis() {
        importFixture()
        val before = model.state.value
        val red = before.analysis!!.red.copyOf()
        assertTrue(before.preview!!.width < before.source!!.width)
        compose.showNativePreview()
        awaitDetail()
        assertTrue(model.state.value.detail!!.bounds.width < 1f)
        val point = compose.safePreviewPoint()
        compose.onNodeWithTag("photo-canvas").performTouchInput { swipe(point, point - Offset(60f, 0f), 300) }
        compose.waitForIdle()
        assertArrayEquals(red, model.state.value.analysis!!.red)
        assertEquals(before.recipe, model.state.value.recipe)
        compose.fitPreview()
        compose.showNativePreview()
        awaitDetail()
    }

    @Test fun twoFingerZoomInCropAndTextDoesNotCreateEdits() {
        importFixture()
        compose.runOnIdle { model.updateRecipe(model.state.value.recipe.copy(watermark = Watermark(text = "你好，光影", x = .5f, y = .5f))) }
        awaitPreview()
        for (tool in listOf(EditorTool.TEXT, EditorTool.CROP)) {
            compose.runOnIdle { model.selectTool(tool) }
            awaitPreview()
            val before = model.state.value
            pinchOut()
            compose.waitForIdle()
            assertEquals("Two fingers must not change $tool", before.recipe, model.state.value.recipe)
            assertEquals(before.canUndo, model.state.value.canUndo)
            assertEquals(before.canRedo, model.state.value.canRedo)
            compose.fitPreview()
            compose.onNodeWithTag("cancel-tool").performClick()
            awaitPreview()
        }
    }

    @Test fun watermarkAndCropDragUseZoomedImageCoordinates() {
        importFixture()
        compose.runOnIdle { model.updateRecipe(model.state.value.recipe.copy(watermark = Watermark(text = "中文水印", x = .5f, y = .5f))) }
        clickTool("text")
        awaitPreview()
        compose.showNativePreview()
        awaitDetail()
        val textPoint = compose.safePreviewPoint()
        val textCanvas = compose.onNodeWithTag("photo-canvas").fetchSemanticsNode().boundsInRoot
        val region = model.state.value.detail!!.bounds
        val textX = if (1800f >= textCanvas.width) region.left + textPoint.x / 1800f else .5f + (textPoint.x - textCanvas.width / 2f) / 1800f
        val textY = if (1200f >= textCanvas.height) region.top + textPoint.y / 1200f else .5f + (textPoint.y - textCanvas.height / 2f) / 1200f
        compose.runOnIdle {
            val recipe = model.state.value.recipe
            model.updateRecipe(recipe.copy(watermark = recipe.watermark.copy(x = textX, y = textY)))
        }
        awaitPreview()
        awaitDetail()
        compose.onNodeWithTag("watermark-canvas").performTouchInput { swipe(textPoint, textPoint + Offset(90f, 40f), 300) }
        assertEquals(textX + 90f / 1800f, model.state.value.recipe.watermark.x, .012f)
        assertEquals(textY + 40f / 1200f, model.state.value.recipe.watermark.y, .012f)
        compose.onNodeWithTag("apply-tool").performClick()
        clickTool("crop")
        compose.runOnIdle { model.updateRecipe(model.state.value.recipe.copy(crop = CropRect(0f, .25f, .5f, .75f))) }
        awaitPreview()
        compose.showNativePreview()
        awaitDetail()
        val cropPoint = compose.safePreviewPoint()
        compose.onNodeWithTag("crop-canvas").performTouchInput { swipe(cropPoint, cropPoint + Offset(90f, 40f), 300) }
        assertEquals(.05f, model.state.value.recipe.crop.left, .012f)
        assertEquals(.25f + 40f / 1200, model.state.value.recipe.crop.top, .012f)
        assertEquals(.5f, model.state.value.recipe.crop.width, .001f)
    }

    @Test fun nativeSmallCropStillDecodesOriginalWhenFitEnlargesIt() {
        writeFixture(4096, 1024)
        importFixture()
        compose.runOnIdle { model.updateRecipe(model.state.value.recipe.copy(crop = CropRect(.45f, .45f, .55f, .55f))) }
        awaitPreview()
        compose.showNativePreview()
        awaitDetail()
        val detail = model.state.value.detail!!
        assertEquals(410, detail.bitmap.width)
        assertEquals(102, detail.bitmap.height)
        assertEquals(0f, detail.bounds.left, .001f)
        assertEquals(1f, detail.bounds.right, .001f)
    }

    @Test fun translucentClippingPixelsCompositeOnceWhenDetailArrives() {
        writeFixture(transparentWhite = true)
        importFixture()
        compose.onNodeWithTag("toggle-clipping").performClick()
        val fit = compose.onNodeWithTag("photo-canvas").captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }
        compose.showNativePreview()
        awaitDetail()
        val native = compose.onNodeWithTag("photo-canvas").captureToImage().toPixelMap().let { it[it.width / 2, it.height / 2] }
        assertTrue(abs(fit.red - native.red) < .012f)
        assertTrue(abs(fit.green - native.green) < .012f)
        assertTrue(abs(fit.blue - native.blue) < .012f)
    }

    @Test fun rapidAdjustmentsPublishMatchingFinalAnalysisAndDetail() {
        importFixture()
        compose.showNativePreview()
        awaitDetail()
        // Start a larger decode/render, then replace its parameters and region while it runs.
        compose.runOnIdle { model.requestDetail(CropRect(), 2400, 1600) }
        Thread.sleep(150)
        compose.runOnIdle {
            model.beginGesture()
            for (exposure in listOf(1.5f, -.2f, 2f, .6f, -1f)) {
                val recipe = model.state.value.recipe
                model.updateRecipe(recipe.copy(adjustments = recipe.adjustments.copy(exposure = exposure)))
            }
            model.endGesture()
            model.requestDetail(CropRect(.25f, .25f, .75f, .75f), 900, 600)
        }
        awaitPreview()
        awaitDetail()
        val latest = model.state.value
        val analysis = latest.analysis!!
        assertEquals(-1f, latest.recipe.adjustments.exposure, 0f)
        assertEquals(0L, analysis.highlightCount)
        val preview = latest.preview!!
        val pixels = IntArray(preview.width * preview.height)
        preview.getPixels(pixels, 0, preview.width, 0, 0, preview.width, preview.height)
        val expected = PixelAnalysis.analyze(pixels)
        assertArrayEquals(expected.red, analysis.red)
        assertArrayEquals(expected.green, analysis.green)
        assertArrayEquals(expected.blue, analysis.blue)
        assertArrayEquals(expected.luminance, analysis.luminance)
        val color = preview.getPixel(preview.width / 2, preview.height / 2)
        val detail = latest.detail!!.bitmap
        val detailedColor = detail.getPixel(detail.width / 2, detail.height / 2)
        assertTrue(abs(Color.red(color) - Color.red(detailedColor)) <= 1)
        assertTrue(abs(Color.green(color) - Color.green(detailedColor)) <= 1)
        assertTrue(abs(Color.blue(color) - Color.blue(detailedColor)) <= 1)
    }
}
