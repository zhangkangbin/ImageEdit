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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kang.imageeditapp.model.CropRect
import com.kang.imageeditapp.model.EditorTool
import com.kang.imageeditapp.model.Geometry
import com.kang.imageeditapp.ui.PreviewViewportMode
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

    private fun assertHeaderHidden() {
        compose.onNodeWithTag("editor-header").assertDoesNotExist()
        compose.onNodeWithTag("open-export").assertDoesNotExist()
        compose.onNodeWithTag("replace-photo").assertDoesNotExist()
    }

    private fun assertHeaderRestored(): Rect {
        compose.onNodeWithTag("editor-header").assertIsDisplayed()
        compose.onNodeWithTag("open-export").assertIsDisplayed()
        compose.onNodeWithTag("replace-photo").assertIsDisplayed()
        return bounds("editor-header").also {
            assertEquals("The restored title bar should be 56dp high", 56f * compose.activity.resources.displayMetrics.density, it.height, 1f)
        }
    }

    private fun assertAnalysisControlsIn(parentTag: String) {
        val parent = bounds(parentTag)
        for (tag in listOf("toggle-histogram", "toggle-clipping")) {
            compose.onAllNodesWithTag(tag).assertCountEquals(1)
            compose.onNodeWithTag(tag).assertIsDisplayed()
            val control = bounds(tag)
            assertTrue("$tag should remain reachable in $parentTag: parent=$parent control=$control", parent.contains(control.center))
        }
    }

    private fun assertSameSize(expected: Rect, actual: Rect) {
        assertEquals("Preview width must stay stable", expected.width, actual.width, 1f)
        assertEquals("Preview height must stay stable", expected.height, actual.height, 1f)
    }

    private fun assertActionsInPreview() {
        val stage = bounds("preview-stage")
        val actions = bounds("preview-actions")
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("Actions belong inside the preview: stage=$stage actions=$actions", actions.left >= stage.left && actions.top >= stage.top && actions.right <= stage.right + 1f && actions.bottom <= stage.bottom + 1f)
        assertTrue("Actions belong in the lower-right corner", actions.center.x >= stage.center.x && actions.center.y >= stage.center.y)
        assertTrue("Actions should stay near the right and bottom edges", stage.right - actions.right <= 48f * density && stage.bottom - actions.bottom <= 48f * density)
        for (tag in listOf("preview-actions", "action-undo", "action-redo", "compare")) {
            compose.onAllNodesWithTag(tag).assertCountEquals(1)
            compose.onNodeWithTag(tag).assertIsDisplayed()
        }
        for (removed in listOf("zoom-percent", "zoom-fit", "zoom-native")) compose.onNodeWithTag(removed).assertDoesNotExist()
    }

    private fun selectTool(tag: String) {
        if (model.state.value.activeTool?.name?.lowercase() == tag) {
            assertHeaderHidden()
            return
        }
        val previousDetail = model.state.value.detail
        val previousTool = model.state.value.activeTool
        clickTool(tag)
        awaitPreview()
        if (previousDetail != null && model.state.value.activeTool != previousTool) {
            compose.waitUntil(30_000) { model.state.value.detail?.let { it !== previousDetail } == true }
        }
        assertHeaderHidden()
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
        assertHeaderRestored()
    }

    private fun adjustExposure() {
        compose.onNodeWithTag("adjust-曝光").performScrollTo().performClick()
        compose.onNodeWithTag("slider-曝光").performScrollTo().performTouchInput {
            swipe(center, Offset(width * .75f, center.y), durationMillis = 300)
        }
        awaitPreview()
        assertTrue(model.state.value.recipe.adjustments.exposure > .2f)
    }

    @Test fun everyToolReservesTheSamePreviewSpace() {
        val header = assertHeaderRestored()
        val idleWorkspace = bounds("editor-workspace")
        selectTool("adjust")
        val stage = bounds("preview-stage")
        val canvas = bounds("photo-canvas")
        val workspace = bounds("editor-workspace")
        assertEquals("Opening a tool should give the entire title bar height to the workspace", idleWorkspace.height + header.height, workspace.height, 1f)
        assertEquals("The workspace should start at the reclaimed title bar position", header.top, workspace.top, 1f)
        assertEquals("Opening a tool should keep the workspace bottom in place", idleWorkspace.bottom, workspace.bottom, 1f)
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
            assertSameSize(workspace, bounds("editor-workspace"))
            assertSameSize(stage, bounds("preview-stage"))
            assertSameSize(canvas, bounds("photo-canvas"))
            assertActionsInPreview()
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
        finishTool("cancel-tool")
        assertSameSize(idleWorkspace, bounds("editor-workspace"))
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
        // Taps on the moved comparison control must not become canvas double taps.
        compose.onNodeWithTag("compare").performTouchInput { doubleClick(center) }
        compose.onNodeWithTag("compare").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "编辑效果"))
        assertFitsCanvas()
        // Panning a fitted photo is a no-op and must keep fit mode for the next resize.
        val point = compose.safePreviewPoint()
        compose.onNodeWithTag("photo-canvas").performTouchInput { swipe(point, point + Offset(40f, 30f), 300) }
        assertEquals(initial, model.state.value.recipe)
        adjustExposure()
        compose.onNodeWithTag("adjust-亮度").performScrollTo().performClick()
        val edited = model.state.value.recipe
        val before = bounds("preview-stage")
        val expandedWorkspace = bounds("editor-workspace")
        val undoBefore = model.state.value.canUndo
        // Tapping the selected navigation tool also toggles its panel.
        clickTool("adjust")
        compose.waitForIdle()
        val header = assertHeaderRestored()
        assertEquals("Restoring the title bar should use only its own height", expandedWorkspace.height - header.height, bounds("editor-workspace").height, 1f)
        val collapsed = bounds("preview-stage")
        assertTrue("Collapsing must release preview space", collapsed.height > before.height || collapsed.width > before.width)
        assertActionsInPreview()
        assertFitsCanvas()
        assertEquals(edited, model.state.value.recipe)
        assertEquals(EditorTool.ADJUST, model.state.value.activeTool)
        assertEquals(undoBefore, model.state.value.canUndo)
        captureEvidence("collapsed")
        compose.onNodeWithTag("toggle-tool-panel").performClick()
        assertHeaderHidden()
        assertSameSize(expandedWorkspace, bounds("editor-workspace"))
        assertSameSize(before, bounds("preview-stage"))
        compose.onNodeWithTag("slider-亮度").performScrollTo().assertIsDisplayed()
        assertEquals("Reopening should retain the selected parameter and its edits", edited, model.state.value.recipe)
        assertFitsCanvas()
        compose.onNodeWithTag("toggle-tool-panel").performClick()
        assertHeaderRestored()
        compose.onNodeWithTag("cancel-tool").assertIsDisplayed().performClick()
        awaitPreview()
        assertHeaderRestored()
        assertEquals(initial, model.state.value.recipe)
        assertFalse(model.state.value.canUndo)

        selectTool("adjust")
        adjustExposure()
        val applied = model.state.value.recipe
        compose.onNodeWithTag("apply-tool").assertIsDisplayed().performClick()
        awaitPreview()
        assertHeaderRestored()
        assertEquals(applied, model.state.value.recipe)
        assertNull(model.state.value.activeTool)
    }

    private fun assertFitsCanvas() {
        compose.waitForIdle()
        val canvas = bounds("photo-canvas")
        val state = model.state.value
        val output = Geometry.outputSize(state.source!!, state.recipe)
        val scale = min(canvas.width / output.width, canvas.height / output.height)
        assertEquals(PreviewViewportMode.FIT, compose.previewMode())
        assertEquals(scale, compose.previewPixelScale(), .0001f)
    }

    private fun awaitNativeBounds(): CropRect {
        assertEquals(1f, compose.previewPixelScale(), .0001f)
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
        compose.showNativePreview()
        val beforePan = awaitNativeBounds()
        val point = compose.safePreviewPoint()
        compose.onNodeWithTag("photo-canvas").performTouchInput { swipe(point, point - Offset(90f, 0f), 300) }
        val canvasWidth = bounds("photo-canvas").width
        compose.waitUntil(30_000) {
            val region = model.state.value.detail?.bounds
            region != null && (canvasWidth >= 1800f || (region.left + region.right) / 2f > (beforePan.left + beforePan.right) / 2f + .005f)
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
        // The title bar returns when folded; native scale and the image center survive both resizes.
        compose.onNodeWithTag("toggle-tool-panel").performClick()
        assertHeaderRestored()
        assertNativeCenter(expectedBounds)
        assertEquals(initial, model.state.value.recipe)
        // Choosing another tool from a folded panel must open it and hide the title bar immediately.
        selectTool("adjust")
        assertNativeCenter(expectedBounds)
        compose.onNodeWithTag("toggle-tool-panel").performClick()
        assertHeaderRestored()
        assertNativeCenter(expectedBounds)
        compose.onNodeWithTag("toggle-tool-panel").performClick()
        assertHeaderHidden()
        assertNativeCenter(expectedBounds)
        assertEquals(initial, model.state.value.recipe)
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
        assertAnalysisControlsIn("preview-stage")
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

        // Wide layouts move the same controls to the restored header without changing edits.
        compose.onNodeWithTag("toggle-tool-panel").performClick()
        assertHeaderRestored()
        assertAnalysisControlsIn(if (wide) "editor-header" else "preview-stage")
        val collapsedCanvas = bounds("photo-canvas")
        compose.onNodeWithTag("toggle-histogram").performClick()
        compose.onNodeWithTag("histogram-plot").assertIsDisplayed()
        assertSameSize(collapsedCanvas, bounds("photo-canvas"))
        compose.onNodeWithTag("toggle-clipping").performClick()
        assertSameSize(collapsedCanvas, bounds("photo-canvas"))
        compose.onNodeWithTag("toggle-tool-panel").performClick()
        assertHeaderHidden()
        assertAnalysisControlsIn("preview-stage")
        compose.onNodeWithTag("histogram-plot").assertIsDisplayed()
        assertSameSize(canvas, bounds("photo-canvas"))
        compose.onNodeWithTag("toggle-histogram").performClick()
        compose.onNodeWithTag("histogram-plot").assertDoesNotExist()
        compose.onNodeWithTag("toggle-clipping").performClick()
        assertSameSize(canvas, bounds("photo-canvas"))
        assertEquals(before.recipe, model.state.value.recipe)
        assertEquals(before.canUndo, model.state.value.canUndo)
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
        assertHeaderHidden()
        assertEquals(initial, model.state.value.recipe)
        assertSameSize(canvas, bounds("photo-canvas"))

        compose.onNodeWithTag("edit-watermark-text").performClick()
        compose.onNodeWithTag("watermark-text").performTextInput("第一行")
        compose.onNodeWithTag("watermark-text").performTextInput("\n第二行")
        compose.onNodeWithTag("confirm-watermark-text").performClick()
        awaitPreview()
        assertEquals("第一行\n第二行", model.state.value.recipe.watermark.text)
        assertHeaderHidden()
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
        assertHeaderRestored()
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
        assertHeaderRestored()
        compose.onNodeWithTag("apply-tool").assertIsDisplayed()
        compose.onNodeWithTag("cancel-tool").assertIsDisplayed()
        assertEquals(cropped, model.state.value.recipe.crop)
        compose.onNodeWithTag("toggle-tool-panel").performClick()
        assertHeaderHidden()
        compose.onNodeWithTag("crop-1:1").performScrollTo().assertIsDisplayed()
        assertEquals("The selected crop must survive panel folding", cropped, model.state.value.recipe.crop)
        captureEvidence("crop")
    }
}
