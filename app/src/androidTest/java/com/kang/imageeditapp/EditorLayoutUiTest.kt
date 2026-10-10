package com.kang.imageeditapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.accessibilityservice.AccessibilityServiceInfo
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inspector.WindowInspector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kang.imageeditapp.model.CropRect
import com.kang.imageeditapp.model.EditorTool
import com.kang.imageeditapp.model.Geometry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Verifies preview space and view continuity through the shipping tool controls. */
@RunWith(AndroidJUnit4::class)
class EditorLayoutUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: EditorViewModel
    private lateinit var fixture: File
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val wide get() = compose.activity.resources.configuration.screenWidthDp >= 600
    private val tools = listOf("adjust", "curves", "hsl", "presets", "text", "crop")

    @Before fun prepare() {
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[EditorViewModel::class.java] }
        compose.waitUntil(10_000) { !model.state.value.isPresetBusy }
        fixture = File.createTempFile("editor-layout-", ".png", context.cacheDir)
        writeFixture()
        compose.runOnIdle { model.importPhoto(Uri.fromFile(fixture)) }
        awaitPreview()
    }

    private fun writeFixture(width: Int = 1800, height: Int = 1200) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (row in 0 until bitmap.height) {
            val fraction = row.toFloat() / bitmap.height
            paint.color = Color.rgb((48 + fraction * 132).roundToInt(), (106 + fraction * 92).roundToInt(), (187 - fraction * 97).roundToInt())
            canvas.drawRect(0f, row.toFloat(), bitmap.width.toFloat(), row + 1f, paint)
        }
        paint.color = Color.rgb(244, 166, 93)
        canvas.drawCircle(width * .7f, height * .29f, min(width, height) * .158f, paint)
        fixture.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @After fun cleanup() {
        if (::model.isInitialized) {
            compose.runOnIdle { model.dismissError(); model.dismissNotice(); model.closePhoto() }
            compose.waitUntil(10_000) { !model.state.value.isLoading }
            compose.runOnIdle { model.deleteDraft() }
            compose.waitUntil(10_000) { !model.state.value.isLoading }
        }
        if (::fixture.isInitialized) fixture.delete()
    }

    private fun awaitPreview() {
        compose.waitUntil(30_000) {
            val state = model.state.value
            state.error != null || (state.preview != null && !state.isLoading && !state.isRendering)
        }
        assertNull(model.state.value.error)
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    private fun assertSameSize(expected: Rect, actual: Rect) {
        assertEquals("Preview width must stay stable", expected.width, actual.width, 1f)
        assertEquals("Preview height must stay stable", expected.height, actual.height, 1f)
    }

    private fun selectTool(tag: String) {
        if (model.state.value.activeTool?.name?.lowercase() == tag) return
        val previousDetail = model.state.value.detail
        val previousTool = model.state.value.activeTool
        clickTool(tag)
        awaitPreview()
        if (previousDetail != null && model.state.value.activeTool != previousTool) {
            compose.waitUntil(30_000) { model.state.value.detail?.let { it !== previousDetail } == true }
        }
    }

    private fun clickTool(tag: String) {
        val node = compose.onNodeWithTag("tool-$tag")
        if (wide) node.performScrollTo()
        node.assertIsDisplayed().performClick()
    }

    private fun finishTool(tag: String) {
        val previousDetail = model.state.value.detail
        compose.onNodeWithTag(tag).performClick()
        awaitPreview()
        if (previousDetail != null) {
            compose.waitUntil(30_000) { model.state.value.detail?.let { it !== previousDetail } == true }
        }
    }

    private fun adjustExposure() {
        compose.onNodeWithTag("slider-曝光").performScrollTo().performTouchInput {
            swipe(center, Offset(width * .75f, center.y), durationMillis = 300)
        }
        awaitPreview()
        assertTrue(model.state.value.recipe.adjustments.exposure > .2f)
    }

    @Test fun everyToolReservesTheSamePreviewSpace() {
        selectTool("adjust")
        val stage = bounds("preview-stage")
        val canvas = bounds("photo-canvas")
        val workspace = bounds("editor-workspace")
        if (!wide) {
            assertTrue("Portrait preview should keep most of the workspace", stage.height >= workspace.height * .56f)
            assertTrue("Portrait controls belong below the preview", bounds("tool-panel").top >= stage.bottom - 1f)
        } else {
            assertTrue("Landscape controls belong beside the preview", bounds("tool-panel").left >= stage.right - 1f)
            assertEquals(workspace.height, stage.height, 1f)
        }
        assertTrue("Preview must have a usable drawing area", canvas.width > 0f && canvas.height > 0f)
        for (tool in tools) {
            selectTool(tool)
            assertSameSize(stage, bounds("preview-stage"))
            assertSameSize(canvas, bounds("photo-canvas"))
            compose.onNodeWithTag("tool-panel").assertIsDisplayed()
            compose.onNodeWithTag("toggle-tool-panel").assertIsDisplayed()
            compose.onNodeWithTag("apply-tool").assertIsDisplayed()
            compose.onNodeWithTag("cancel-tool").assertIsDisplayed()
            if (tool == "adjust") {
                compose.onNodeWithTag("adjust-亮度").performScrollTo().performClick()
                compose.onNodeWithTag("slider-亮度").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("slider-曝光").assertDoesNotExist()
            } else if (tool == "hsl") {
                compose.onNodeWithTag("hsl-7").performScrollTo().performClick()
                compose.onNodeWithTag("hsl-parameter-明度").performScrollTo().performClick()
                compose.onNodeWithTag("slider-明度").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("slider-色相").assertDoesNotExist()
            }
            assertSameSize(canvas, bounds("photo-canvas"))
        }
    }

    @Test fun collapsingThePanelKeepsEditsCancelableAndApplicable() {
        // A tall photo makes fit adapt to the released height on portrait displays.
        if (!wide) {
            writeFixture(1200, 1800)
            compose.runOnIdle { model.importPhoto(Uri.fromFile(fixture)) }
            awaitPreview()
        }
        selectTool("adjust")
        val initial = model.state.value.recipe
        // Button taps must not bubble into the canvas's native-size double-tap gesture.
        compose.onNodeWithTag("zoom-fit").performTouchInput { doubleClick(center) }
        assertFitsCanvas()
        // Panning a fitted photo is a no-op and must keep fit mode for the next resize.
        compose.onNodeWithTag("photo-canvas").performTouchInput { swipe(center, center + Offset(40f, 30f), 300) }
        assertEquals(initial, model.state.value.recipe)
        adjustExposure()
        val edited = model.state.value.recipe
        val before = bounds("preview-stage")
        val undoBefore = model.state.value.canUndo
        // Tapping the selected navigation tool also toggles its panel.
        clickTool("adjust")
        compose.waitForIdle()
        val collapsed = bounds("preview-stage")
        assertTrue("Collapsing must release preview space", collapsed.height > before.height || collapsed.width > before.width)
        assertFitsCanvas()
        assertEquals(edited, model.state.value.recipe)
        assertEquals(EditorTool.ADJUST, model.state.value.activeTool)
        assertEquals(undoBefore, model.state.value.canUndo)
        compose.onNodeWithTag("cancel-tool").assertIsDisplayed().performClick()
        awaitPreview()
        assertEquals(initial, model.state.value.recipe)
        assertFalse(model.state.value.canUndo)

        selectTool("adjust")
        if (compose.onAllNodesWithTag("slider-曝光").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("toggle-tool-panel").performClick()
        }
        adjustExposure()
        val applied = model.state.value.recipe
        compose.onNodeWithTag("toggle-tool-panel").performClick()
        compose.onNodeWithTag("apply-tool").assertIsDisplayed().performClick()
        awaitPreview()
        assertEquals(applied, model.state.value.recipe)
        assertNull(model.state.value.activeTool)
    }

    private fun assertFitsCanvas() {
        compose.waitForIdle()
        val canvas = bounds("photo-canvas")
        val state = model.state.value
        val output = Geometry.outputSize(state.source!!, state.recipe)
        val percent = (min(canvas.width / output.width, canvas.height / output.height) * 100f).roundToInt()
        compose.onNodeWithTag("zoom-percent").assertTextEquals("$percent%")
    }

    private fun awaitNativeBounds(): CropRect {
        compose.onNodeWithTag("zoom-percent").assertTextEquals("100%")
        compose.waitUntil(30_000) {
            val state = model.state.value
            val detail = state.detail
            val canvas = bounds("photo-canvas")
            state.error != null || (detail != null && detail.cropMode == (state.activeTool == EditorTool.CROP) &&
                abs(detail.bitmap.width - min(1800, canvas.width.roundToInt())) <= 2 &&
                abs(detail.bitmap.height - min(1200, canvas.height.roundToInt())) <= 2)
        }
        assertNull(model.state.value.error)
        return model.state.value.detail!!.bounds
    }

    private fun assertNativeCenter(expected: CropRect) {
        val actual = awaitNativeBounds()
        assertEquals("Horizontal image center should survive layout changes", (expected.left + expected.right) / 2f, (actual.left + actual.right) / 2f, .002f)
        assertEquals("Vertical image center should survive layout changes", (expected.top + expected.bottom) / 2f, (actual.top + actual.bottom) / 2f, .002f)
    }

    @Test fun switchingApplyingAndCancelingToolsKeepNativeScaleAndCenter() {
        compose.onNodeWithTag("zoom-native").performClick()
        awaitNativeBounds()
        compose.onNodeWithTag("photo-canvas").performTouchInput { swipe(center, center + Offset(90f, 0f), 300) }
        val canvasWidth = bounds("photo-canvas").width
        compose.waitUntil(30_000) {
            val region = model.state.value.detail?.bounds
            region != null && (canvasWidth >= 1800f || (region.left + region.right) / 2f < .49f)
        }
        val expectedBounds = awaitNativeBounds()
        val initial = model.state.value.recipe
        // Crop has its own full-image viewport; color and text tools share the output viewport.
        for (tool in tools.filter { it != "crop" }) {
            selectTool(tool)
            assertNativeCenter(expectedBounds)
            assertEquals(initial, model.state.value.recipe)
            assertFalse(model.state.value.canUndo)
        }
        finishTool("apply-tool")
        assertNativeCenter(expectedBounds)

        selectTool("adjust")
        adjustExposure()
        finishTool("apply-tool")
        assertNativeCenter(expectedBounds)
        val applied = model.state.value.recipe
        selectTool("adjust")
        compose.onNodeWithTag("adjust-亮度").performScrollTo().performClick()
        compose.onNodeWithTag("slider-亮度").performScrollTo().performTouchInput {
            swipe(center, Offset(width * .75f, center.y), 300)
        }
        finishTool("cancel-tool")
        assertEquals(applied, model.state.value.recipe)
        assertNativeCenter(expectedBounds)
    }

    @Test fun histogramExpansionKeepsCanvasDimensionsAndEditingState() {
        selectTool("curves")
        val stage = bounds("preview-stage")
        val canvas = bounds("photo-canvas")
        val before = model.state.value
        compose.onNodeWithTag("toggle-histogram").performClick()
        compose.onNodeWithTag("histogram-plot").assertIsDisplayed()
        assertSameSize(stage, bounds("preview-stage"))
        assertSameSize(canvas, bounds("photo-canvas"))
        assertEquals(before.recipe, model.state.value.recipe)
        assertEquals(before.canUndo, model.state.value.canUndo)
        compose.onNodeWithTag("toggle-histogram").performClick()
        compose.onNodeWithTag("histogram-plot").assertDoesNotExist()
        assertSameSize(canvas, bounds("photo-canvas"))
    }

    @Test fun textDialogCancellationDiscardsDraftAndConfirmationCreatesOneUndoStep() {
        selectTool("text")
        val initial = model.state.value.recipe
        val canvas = bounds("photo-canvas")
        compose.onNodeWithTag("edit-watermark-text").performClick()
        compose.onNodeWithTag("watermark-text").performTextInput("待取消的文字")
        assertEquals(initial, model.state.value.recipe)
        assertFalse(model.state.value.canUndo)
        compose.onNodeWithTag("cancel-watermark-text").performClick()
        compose.onNodeWithTag("watermark-text").assertDoesNotExist()
        assertEquals(initial, model.state.value.recipe)
        assertSameSize(canvas, bounds("photo-canvas"))

        compose.onNodeWithTag("edit-watermark-text").performClick()
        compose.onNodeWithTag("watermark-text").performTextInput("第一行")
        compose.onNodeWithTag("watermark-text").performTextInput("\n第二行")
        compose.onNodeWithTag("confirm-watermark-text").performClick()
        awaitPreview()
        assertEquals("第一行\n第二行", model.state.value.recipe.watermark.text)
        assertTrue(model.state.value.canUndo)
        assertSameSize(canvas, bounds("photo-canvas"))
        compose.onNodeWithTag("action-undo").performClick()
        awaitPreview()
        assertEquals(initial, model.state.value.recipe)
        assertFalse("Both input operations must be one confirmed gesture", model.state.value.canUndo)
        compose.onNodeWithTag("action-redo").performClick()
        awaitPreview()
        assertEquals("第一行\n第二行", model.state.value.recipe.watermark.text)
        compose.onNodeWithTag("cancel-tool").performClick()
        awaitPreview()
        assertEquals(initial, model.state.value.recipe)
        assertFalse(model.state.value.canUndo)
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun awaitKeyboard(visible: Boolean) {
        compose.waitUntil(10_000) { shell("dumpsys input_method").contains("mInputShown=$visible") }
        instrumentation.waitForIdleSync()
        compose.waitForIdle()
    }

    private fun assertTextActionsAboveKeyboard() {
        val automation = instrumentation.uiAutomation
        val service = checkNotNull(automation.serviceInfo) { "UI automation accessibility service is unavailable" }
        val originalFlags = service.flags
        var diagnostics = "Waiting for IME and the focused native dialog window"
        var lastSignature: String? = null
        var stableSince = 0L
        try {
            service.flags = originalFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = service
            compose.waitUntil(10_000) {
                val windows = automation.windows
                val keyboard = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                val keyboardBounds = android.graphics.Rect()
                keyboard?.getBoundsInScreen(keyboardBounds)
                var origin: Offset? = null
                val nativeWindows = mutableListOf<String>()
                instrumentation.runOnMainSync {
                    for (view in WindowInspector.getGlobalWindowViews()) {
                        val screen = IntArray(2)
                        val inWindow = IntArray(2)
                        view.getLocationOnScreen(screen)
                        view.getLocationInWindow(inWindow)
                        val windowOrigin = Offset((screen[0] - inWindow[0]).toFloat(), (screen[1] - inWindow[1]).toFloat())
                        nativeWindows += "Native window class=${view.javaClass.name}, focused=${view.hasWindowFocus()}, shown=${view.isShown}, size=${view.width}x${view.height}, screen=${screen.contentToString()}, inWindow=${inWindow.contentToString()}, origin=$windowOrigin"
                        if (view.hasWindowFocus() && view.isShown) origin = windowOrigin
                    }
                }
                val confirmInWindow = compose.onNodeWithTag("confirm-watermark-text").assertIsEnabled().fetchSemanticsNode().boundsInWindow
                val cancelInWindow = compose.onNodeWithTag("cancel-watermark-text").assertIsEnabled().fetchSemanticsNode().boundsInWindow
                val confirmOnScreen = origin?.let { confirmInWindow.translate(it) }
                val cancelOnScreen = origin?.let { cancelInWindow.translate(it) }
                val signature = "IME=$keyboardBounds, nativeOrigin=$origin, confirmInWindow=$confirmInWindow, cancelInWindow=$cancelInWindow, confirmOnScreen=$confirmOnScreen, cancelOnScreen=$cancelOnScreen"
                val windowReport = nativeWindows.joinToString("\n")
                diagnostics = "$signature\n$windowReport"
                val accessible = keyboard != null && keyboardBounds.height() > 0 && confirmOnScreen != null && cancelOnScreen != null &&
                    listOf(confirmOnScreen, cancelOnScreen).all { button ->
                        button.width > 0 && button.height > 0 && button.top >= 0 && button.bottom <= keyboardBounds.top
                    }
                if (!accessible) {
                    lastSignature = null
                    false
                } else {
                    if (signature != lastSignature) {
                        lastSignature = signature
                        stableSince = SystemClock.uptimeMillis()
                    }
                    // Wait for keyboard and optional system-bar animations to finish moving the windows.
                    SystemClock.uptimeMillis() - stableSince >= 350L
                }
            }
            recordGeometry("Text actions in physical screen coordinates: $diagnostics")
        } catch (failure: Throwable) {
            runCatching { recordGeometry("Failed keyboard accessibility verification: $diagnostics") }
            throw AssertionError("Confirmation and cancellation must remain above the real keyboard. $diagnostics", failure)
        } finally {
            service.flags = originalFlags
            automation.serviceInfo = service
        }
    }

    private fun evidencePrefix(): String = (InstrumentationRegistry.getArguments().getString("layoutEvidencePrefix") ?: "api${Build.VERSION.SDK_INT}")
        .replace(Regex("[^A-Za-z0-9_-]"), "-")

    private fun evidenceDirectory(): File {
        val directory = checkNotNull(context.getExternalFilesDir("layout-evidence"))
        assertTrue(directory.isDirectory || directory.mkdirs())
        return directory
    }

    private fun recordGeometry(report: String) {
        File(evidenceDirectory(), "${evidencePrefix()}-geometry.txt").appendText("$report\n")
    }

    private fun captureEvidence(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        val prefix = evidencePrefix()
        val directory = evidenceDirectory()
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Unable to capture the actual display" }
        try {
            File(directory, "$prefix-$name.png").outputStream().use {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { screenshot.recycle() }
    }

    private fun moveSlider(label: String) {
        compose.onNodeWithTag("slider-$label").performScrollTo().assertIsDisplayed().performTouchInput {
            swipe(center, Offset(width * .72f, center.y), 300)
        }
        awaitPreview()
    }

    @Test fun compactControlsRemainUsableWithRealKeyboard() {
        selectTool("adjust")
        compose.onNodeWithTag("adjust-高光").performScrollTo().performClick()
        moveSlider("高光")
        assertTrue(model.state.value.recipe.adjustments.highlights > .1f)
        compose.onNodeWithTag("adjust-曝光").performScrollTo().performClick()
        adjustExposure()
        captureEvidence("adjust")

        selectTool("curves")
        val curveCanvas = compose.onNodeWithTag("curves-canvas").performScrollTo().assertIsDisplayed()
        val visibleCurve = curveCanvas.fetchSemanticsNode().boundsInRoot
        assertTrue("The curve needs enough visible space for a 20-pixel drag: $visibleCurve", visibleCurve.height >= 56f)
        val start = Offset(visibleCurve.width / 2f, visibleCurve.height * .65f)
        val movement = 20f
        val end = start - Offset(0f, movement)
        curveCanvas.performTouchInput { click(start) }
        assertEquals(3, model.state.value.recipe.curves.rgb.size)
        val pointBeforeDrag = model.state.value.recipe.curves.rgb[1]
        curveCanvas.performTouchInput { swipe(start, end, 300) }
        awaitPreview()
        val pointAfterDrag = model.state.value.recipe.curves.rgb[1]
        val plotHeight = 124f * compose.activity.resources.displayMetrics.density
        val requiredChange = movement / plotHeight * .5f
        val curveReport = "Curve drag: visible=$visibleCurve, start=$start, end=$end, before=$pointBeforeDrag, after=$pointAfterDrag, requiredDelta=$requiredChange"
        recordGeometry(curveReport)
        assertTrue(curveReport, pointAfterDrag.y - pointBeforeDrag.y >= requiredChange)
        captureEvidence(if (wide) "curves-landscape" else "curves")

        selectTool("hsl")
        compose.onNodeWithTag("hsl-7").performScrollTo().assertIsDisplayed().performClick()
        for (parameter in listOf("色相", "饱和度", "明度")) {
            compose.onNodeWithTag("hsl-parameter-$parameter").performScrollTo().assertIsDisplayed().performClick()
            moveSlider(parameter)
            val lastColor = model.state.value.recipe.hsl[7]
            val value = when (parameter) { "色相" -> lastColor.hue; "饱和度" -> lastColor.saturation; else -> lastColor.lightness }
            assertTrue("The last HSL color should accept $parameter", value > .1f)
        }
        captureEvidence("hsl")

        selectTool("text")
        val beforeText = model.state.value.recipe
        compose.onNodeWithTag("edit-watermark-text").performScrollTo().performClick()
        compose.onNodeWithTag("watermark-text").performClick()
        awaitKeyboard(true)
        compose.onNodeWithTag("watermark-text").performTextInput("光影的颜色\n留下这一刻")
        compose.onNodeWithTag("confirm-watermark-text").assertIsDisplayed()
        compose.onNodeWithTag("cancel-watermark-text").assertIsDisplayed()
        captureEvidence("text-ime")
        assertTextActionsAboveKeyboard()
        assertEquals("Typing remains a dialog draft until confirmation", beforeText, model.state.value.recipe)
        // Replace the early diagnostic image with the verified, settled window layout.
        captureEvidence("text-ime")
        compose.onNodeWithTag("confirm-watermark-text").performClick()
        awaitKeyboard(false)
        awaitPreview()
        assertEquals("光影的颜色\n留下这一刻", model.state.value.recipe.watermark.text)
        val previousSize = model.state.value.recipe.watermark.sizeFraction
        moveSlider("字号")
        assertTrue(model.state.value.recipe.watermark.sizeFraction > previousSize)
        captureEvidence("text")

        selectTool("crop")
        compose.onNodeWithTag("crop-1:1").performScrollTo().assertIsDisplayed().performClick()
        awaitPreview()
        val cropped = model.state.value.recipe.crop
        val output = Geometry.outputSize(model.state.value.source!!, model.state.value.recipe)
        assertEquals(output.width, output.height)
        compose.onNodeWithTag("toggle-tool-panel").performClick()
        compose.onNodeWithTag("apply-tool").assertIsDisplayed()
        compose.onNodeWithTag("cancel-tool").assertIsDisplayed()
        assertEquals(cropped, model.state.value.recipe.crop)
        compose.onNodeWithTag("toggle-tool-panel").performClick()
        compose.onNodeWithTag("crop-1:1").performScrollTo().assertIsDisplayed()
        assertEquals("The selected crop must survive panel folding", cropped, model.state.value.recipe.crop)
        captureEvidence("crop")
    }
}
