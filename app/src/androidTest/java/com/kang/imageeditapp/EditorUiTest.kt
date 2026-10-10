package com.kang.imageeditapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kang.imageeditapp.model.EditRecipe
import com.kang.imageeditapp.model.ExportFormat
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

/** Exercises the shipped Activity and Compose gestures, plus real MediaStore output. */
@RunWith(AndroidJUnit4::class)
class EditorUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: EditorViewModel
    private lateinit var fixture: File
    private val saved = mutableListOf<Uri>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before fun preparePhoto() {
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[EditorViewModel::class.java] }
        fixture = File.createTempFile("editor-ui-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for (row in 0 until 480) {
            paint.color = Color.rgb(40 + row / 4, 100 + row / 5, 185 - row / 5)
            canvas.drawRect(0f, row.toFloat(), 640f, row + 1f, paint)
        }
        paint.color = Color.rgb(230, 150, 90)
        canvas.drawCircle(410f, 140f, 70f, paint)
        fixture.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @After fun cleanOutput() {
        saved.forEach { context.contentResolver.delete(it, null, null) }
        if (::fixture.isInitialized) fixture.delete()
    }

    private fun importFixture() {
        compose.onNodeWithTag("import-photo").assertIsDisplayed()
        compose.runOnIdle { model.importPhoto(Uri.fromFile(fixture)) }
        awaitPreview()
        assertEquals(640, model.state.value.source!!.width)
        assertEquals(480, model.state.value.source!!.height)
        compose.onNodeWithTag("tool-adjust").assertIsDisplayed()
    }

    private fun awaitPreview() {
        compose.waitUntil(30_000) {
            val state = model.state.value
            state.error != null || (state.preview != null && !state.isLoading && !state.isRendering)
        }
        assertNull("Rendering failed", model.state.value.error)
    }

    private fun slider(label: String, fraction: Float = .76f) {
        compose.onNodeWithTag("slider-$label").performScrollTo().performTouchInput {
            swipe(center, Offset(center.x * fraction * 2f, center.y), durationMillis = 300)
        }
    }

    private fun applyTool() {
        compose.onNodeWithTag("apply-tool").performClick()
        awaitPreview()
        assertNull(model.state.value.activeTool)
    }

    private fun clickTool(tag: String) {
        val node = compose.onNodeWithTag("tool-$tag")
        if (compose.activity.resources.configuration.screenWidthDp >= 600) node.performScrollTo()
        node.assertIsDisplayed().performClick()
    }

    @Test fun editThroughEveryToolAndExportBothFormats() {
        importFixture()
        clickTool("adjust")
        slider("曝光")
        assertTrue(model.state.value.recipe.adjustments.exposure > .2f)
        applyTool()
        val colored = model.state.value.recipe
        compose.onNodeWithTag("action-undo").performClick()
        assertEquals(0f, model.state.value.recipe.adjustments.exposure, .001f)
        compose.onNodeWithTag("action-redo").performClick()
        assertEquals(colored, model.state.value.recipe)

        clickTool("adjust")
        compose.onNodeWithTag("adjust-亮度").performScrollTo().performClick()
        slider("亮度")
        assertTrue(model.state.value.recipe.adjustments.brightness > .1f)
        compose.onNodeWithTag("cancel-tool").performClick()
        assertEquals(colored, model.state.value.recipe)

        clickTool("curves")
        compose.onNodeWithTag("curves-canvas").performTouchInput { click(center) }
        assertEquals(3, model.state.value.recipe.curves.rgb.size)
        compose.onNodeWithTag("curves-canvas").performTouchInput { swipe(center, Offset(center.x, center.y * .65f), durationMillis = 300) }
        assertTrue(model.state.value.recipe.curves.rgb[1].y > .55f)
        compose.onNodeWithTag("curve-RED").performClick()
        compose.onNodeWithTag("curves-canvas").performTouchInput { click(Offset(center.x * .8f, center.y * 1.2f)) }
        assertEquals(3, model.state.value.recipe.curves.red.size)
        applyTool()

        clickTool("hsl")
        compose.onNodeWithTag("hsl-0").performClick()
        slider("色相", .7f)
        assertTrue(model.state.value.recipe.hsl[0].hue > .1f)
        applyTool()

        clickTool("text")
        compose.onNodeWithTag("edit-watermark-text").performClick()
        compose.onNodeWithTag("watermark-text").performTextInput("你好，光影")
        compose.onNodeWithTag("confirm-watermark-text").performClick()
        assertEquals("你好，光影", model.state.value.recipe.watermark.text)
        applyTool()
        clickTool("text")
        awaitPreview()
        val initialY = model.state.value.recipe.watermark.y
        compose.onNodeWithTag("watermark-canvas").performTouchInput {
            val imageHeight = min(center.y * 2f, center.x * 2f * 3f / 4f)
            val start = Offset(center.x, center.y - imageHeight / 2f + imageHeight * initialY)
            swipe(start, start - Offset(0f, imageHeight * .18f), durationMillis = 300)
        }
        assertTrue("Watermark should move", model.state.value.recipe.watermark.y < initialY - .05f)
        applyTool()

        clickTool("crop")
        awaitPreview()
        compose.onNodeWithTag("crop-1:1").performClick()
        var output = Geometry.outputSize(model.state.value.source!!, model.state.value.recipe)
        assertEquals(output.width, output.height)
        compose.onNodeWithTag("crop-action-rotate").performClick()
        assertEquals(1, model.state.value.recipe.quarterTurns)
        awaitPreview()
        val cropBeforeMove = model.state.value.recipe.crop
        compose.onNodeWithTag("crop-canvas").performTouchInput {
            val imageHeight = min(center.y * 2f, center.x * 2f * 4f / 3f)
            // A real drag must cross Android's touch slop before any crop movement is sent.
            swipe(center, center + Offset(0f, imageHeight * .12f), durationMillis = 400)
        }
        assertTrue("Move did not shift crop: before=$cropBeforeMove after=${model.state.value.recipe.crop}", model.state.value.recipe.crop.top > cropBeforeMove.top + .01f)
        val cropBeforeResize = model.state.value.recipe.crop
        compose.onNodeWithTag("crop-canvas").performTouchInput {
            val imageHeight = min(center.y * 2f, center.x * 2f * 4f / 3f)
            val imageWidth = imageHeight * 3f / 4f
            val start = Offset(center.x - imageWidth / 2f + imageWidth * cropBeforeResize.left + 2f, center.y - imageHeight / 2f + imageHeight * cropBeforeResize.top + 2f)
            swipe(start, start + Offset(imageWidth * .08f, imageHeight * .08f), durationMillis = 300)
        }
        assertTrue(model.state.value.recipe.crop.width < cropBeforeResize.width - .02f)
        compose.onNodeWithTag("crop-action-flipH").performClick()
        assertTrue(model.state.value.recipe.flipHorizontal)
        compose.onNodeWithTag("crop-action-flipV").performClick()
        assertTrue(model.state.value.recipe.flipVertical)
        applyTool()
        assertEquals("你好，光影", model.state.value.recipe.watermark.text)

        for (format in ExportFormat.entries) {
            compose.onNodeWithTag("open-export").performClick()
            compose.onNodeWithTag("format-${format.name}").performClick()
            compose.onNodeWithTag("save-photo").performClick()
            compose.waitUntil(30_000) { model.state.value.savedUri != null || model.state.value.error != null }
            assertNull(model.state.value.error)
            val uri = model.state.value.savedUri!!
            saved += uri
            assertEquals(format.mimeType, context.contentResolver.getType(uri))
            context.contentResolver.openInputStream(uri).use { stream ->
                val exported = BitmapFactory.decodeStream(stream)!!
                output = Geometry.outputSize(model.state.value.source!!, model.state.value.recipe)
                assertEquals(output.width, exported.width)
                assertEquals(output.height, exported.height)
                exported.recycle()
            }
            compose.onNodeWithText("图片已保存").assertIsDisplayed()
            if (format == ExportFormat.PNG) {
                compose.onNodeWithTag("share-photo").performClick()
                awaitSystemActivity("ChooserActivity|ResolverActivity")
                systemBack()
            }
            compose.onNodeWithText("完成").performClick()
        }
    }

    @Test fun photoPickerCancellationAndFailedImportPreserveCurrentWork() {
        importFixture()
        clickTool("adjust")
        slider("曝光")
        applyTool()
        val before = model.state.value
        compose.onNodeWithTag("replace-photo").performClick()
        awaitSystemActivity("PhotoPickerActivity|PickImagesActivity|Photopicker|DocumentsActivity|PickActivity|photopicker")
        systemBack()
        compose.onNodeWithTag("tool-adjust").assertIsDisplayed()
        assertEquals(before.source, model.state.value.source)
        assertEquals(before.recipe, model.state.value.recipe)

        compose.runOnIdle { model.importPhoto(Uri.fromFile(File(context.cacheDir, "missing-image-does-not-exist.png"))) }
        compose.waitUntil(10_000) { model.state.value.error != null }
        compose.onNodeWithText("暂时无法完成").assertIsDisplayed()
        assertEquals(before.source, model.state.value.source)
        assertEquals(before.recipe, model.state.value.recipe)
        compose.onNodeWithText("知道了").performClick()
    }

    @Test fun systemBackCancelsToolAndRequiresConfirmationToClosePhoto() {
        importFixture()
        clickTool("adjust")
        slider("曝光")
        systemBack()
        compose.waitUntil(5_000) { model.state.value.activeTool == null }
        assertEquals(EditRecipe(), model.state.value.recipe)
        assertNull(model.state.value.activeTool)
        assertNotNull(model.state.value.source)
        systemBack()
        awaitCloseDialog()
        compose.onNodeWithText("结束本次编辑？").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        // Wait for the Android Dialog window to release focus before injecting the next BACK.
        compose.waitUntil(10_000) { compose.activity.window.decorView.hasWindowFocus() }
        assertNotNull(model.state.value.source)
        systemBack()
        awaitCloseDialog()
        compose.onNodeWithText("结束编辑").performClick()
        compose.onNodeWithTag("import-photo").assertIsDisplayed()
        assertNull(model.state.value.source)
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun awaitCloseDialog() {
        compose.waitUntil(5_000) { compose.onAllNodesWithText("结束本次编辑？").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitSystemActivity(classPattern: String) {
        val resumed = Regex("(?im)(mResumedActivity|topResumedActivity)\\s*[:=]\\s*[^\\n]*(?:$classPattern)")
        val focused = Regex("(?im)mCurrentFocus\\s*[:=]\\s*[^\\n]*(?:$classPattern)")
        val deadline = System.currentTimeMillis() + 10_000
        var dump = ""
        var windows = ""
        while (System.currentTimeMillis() < deadline) {
            dump = shell("dumpsys activity activities")
            windows = shell("dumpsys window")
            // Resuming precedes input focus during chooser/picker opening animations.
            if (resumed.containsMatchIn(dump) && focused.containsMatchIn(windows)) return
            Thread.sleep(100)
        }
        assertTrue("Expected focused system activity $classPattern. Resumed: ${resumed.find(dump)?.value}; focused: ${Regex("mCurrentFocus[^\\n]*").find(windows)?.value}", resumed.containsMatchIn(dump) && focused.containsMatchIn(windows))
    }

    private fun systemBack() {
        shell("input keyevent KEYCODE_BACK")
        awaitSystemActivity("com.kang.imageeditapp/[^\\n]*MainActivity")
        instrumentation.waitForIdleSync()
        compose.waitForIdle()
    }
}
