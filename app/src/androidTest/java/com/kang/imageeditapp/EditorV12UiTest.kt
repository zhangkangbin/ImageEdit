package com.kang.imageeditapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kang.imageeditapp.model.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises personal presets and export controls through the shipping Activity. */
@RunWith(AndroidJUnit4::class)
class EditorV12UiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var model: EditorViewModel
    private lateinit var fixture: File
    private val presetIds = mutableListOf<String>()
    private val savedUris = mutableListOf<Uri>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun prepare() {
        compose.runOnIdle { model = ViewModelProvider(compose.activity)[EditorViewModel::class.java] }
        awaitLibrary()
        fixture = File.createTempFile("editor-v12-", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(90, 125, 155))
        fixture.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        importFixture()
    }

    @After fun cleanup() {
        savedUris.forEach { context.contentResolver.delete(it, null, null) }
        if (::model.isInitialized) {
            compose.runOnIdle { model.dismissError(); model.dismissSaved(); model.dismissNotice() }
            awaitLibrary()
            presetIds.forEach { id ->
                if (model.state.value.presets.any { it.id == id }) {
                    compose.runOnIdle { model.deletePreset(id) }
                    awaitLibrary()
                }
            }
            compose.runOnIdle { model.closePhoto() }
            compose.waitUntil(10_000) { !model.state.value.isLoading }
            compose.runOnIdle { model.deleteDraft() }
            compose.waitUntil(10_000) { !model.state.value.isLoading }
        }
        if (::fixture.isInitialized) fixture.delete()
    }

    private fun importFixture() {
        compose.runOnIdle { model.importPhoto(Uri.fromFile(fixture)) }
        awaitPreview()
    }

    private fun awaitLibrary() {
        compose.waitUntil(10_000) { !model.state.value.isPresetBusy }
        assertNull(model.state.value.error)
    }

    private fun awaitPreview() {
        compose.waitUntil(30_000) {
            val state = model.state.value
            state.error != null || (state.preview != null && !state.isLoading && !state.isRendering)
        }
        assertNull(model.state.value.error)
    }

    private fun setRecipe(recipe: EditRecipe) {
        compose.runOnIdle {
            model.selectTool(EditorTool.ADJUST)
            model.beginGesture()
            model.updateRecipe(recipe)
            model.endGesture()
            model.applyTool()
        }
        awaitPreview()
    }

    private fun savePreset(name: String): ColorPreset {
        compose.onNodeWithTag("tool-presets").performClick()
        compose.onNodeWithTag("save-preset").performClick()
        compose.onNodeWithTag("preset-name").performTextInput(name)
        compose.onNodeWithTag("confirm-preset-name").performClick()
        compose.waitUntil(10_000) { model.state.value.presets.any { it.name == name } && !model.state.value.isPresetBusy }
        assertNull(model.state.value.error)
        return model.state.value.presets.first { it.name == name }.also { presetIds += it.id }
    }

    @Test fun personalPresetNameValidationRenameAndDeleteConfirmation() {
        compose.onNodeWithTag("tool-presets").assertIsDisplayed().performClick()
        val before = model.state.value.recipe
        compose.onNodeWithTag("save-preset").performClick()
        compose.onNodeWithTag("confirm-preset-name").assertIsNotEnabled()
        compose.onNodeWithTag("preset-name").performTextInput(" ")
        compose.onNodeWithTag("confirm-preset-name").assertIsNotEnabled()
        compose.onNodeWithTag("preset-name").performTextReplacement("长".repeat(25))
        compose.onNodeWithTag("confirm-preset-name").assertIsNotEnabled()
        compose.onNodeWithTag("preset-name").performTextReplacement("🌤".repeat(24))
        compose.onNodeWithTag("confirm-preset-name").assertIsEnabled()
        val name = "晨光${System.nanoTime()}"
        compose.onNodeWithTag("preset-name").performTextReplacement(name)
        compose.onNodeWithTag("confirm-preset-name").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { model.state.value.presets.any { it.name == name } && !model.state.value.isPresetBusy }
        val preset = model.state.value.presets.first { it.name == name }
        presetIds += preset.id
        assertEquals(before, model.state.value.recipe)
        compose.onNodeWithTag("rename-preset-${preset.id}").performScrollTo().performClick()
        compose.onNodeWithTag("preset-name").assertTextContains(name)
        val renamed = "夜色${System.nanoTime()}"
        compose.onNodeWithTag("preset-name").performTextReplacement(renamed)
        compose.onNodeWithTag("confirm-preset-name").performClick()
        compose.waitUntil(10_000) { model.state.value.presets.any { it.id == preset.id && it.name == renamed } && !model.state.value.isPresetBusy }
        compose.onNodeWithTag("delete-preset-${preset.id}").performScrollTo().performClick()
        compose.onNodeWithTag("cancel-delete-preset").performClick()
        assertTrue(model.state.value.presets.any { it.id == preset.id })
        compose.onNodeWithTag("delete-preset-${preset.id}").performClick()
        compose.onNodeWithTag("confirm-delete-preset").performClick()
        compose.waitUntil(10_000) { model.state.value.presets.none { it.id == preset.id } && !model.state.value.isPresetBusy }
        compose.onNodeWithTag("preset-${preset.id}").assertDoesNotExist()
        assertEquals(before, model.state.value.recipe)
    }

    @Test fun presetPreviewCancelsAndAppliedPresetSupportsUndoWithoutReplacingGeometry() {
        val colored = EditRecipe(
            adjustments = ColorAdjustments(exposure = .5f, temperature = .2f),
            curves = CurveSet(rgb = listOf(CurvePoint(0f, 0f), CurvePoint(.5f, .6f), CurvePoint(1f, 1f))),
            hsl = List(8) { index -> HslAdjustment(hue = if (index == 0) .3f else 0f) },
        )
        setRecipe(colored)
        val preset = savePreset("暖调${System.nanoTime()}")
        compose.onNodeWithTag("apply-tool").performClick()
        val receiving = EditRecipe(crop = CropRect(.2f, .1f, .8f, .9f), quarterTurns = 1, flipHorizontal = true, watermark = Watermark(text = "接收照片的文字", x = .3f, y = .6f))
        setRecipe(receiving)
        compose.onNodeWithTag("tool-presets").performClick()
        compose.onNodeWithTag("preset-${preset.id}").performScrollTo().performClick()
        awaitPreview()
        val expected = ColorGrade.capture(colored).applyTo(receiving)
        assertEquals(expected, model.state.value.recipe)
        compose.onNodeWithTag("cancel-tool").performClick()
        awaitPreview()
        assertEquals(receiving, model.state.value.recipe)
        compose.onNodeWithTag("tool-presets").performClick()
        compose.onNodeWithTag("preset-${preset.id}").performScrollTo().performClick()
        compose.onNodeWithTag("apply-tool").performClick()
        awaitPreview()
        assertEquals(expected, model.state.value.recipe)
        compose.onNodeWithTag("action-undo").performClick()
        awaitPreview()
        assertEquals(receiving, model.state.value.recipe)
        compose.onNodeWithTag("action-redo").performClick()
        awaitPreview()
        assertEquals(expected, model.state.value.recipe)
    }

    @Test fun copiedColorCanBePastedAcrossPhotosAndCanceled() {
        val colored = EditRecipe(adjustments = ColorAdjustments(contrast = .35f, saturation = .2f), watermark = Watermark(text = "来源文字"))
        setRecipe(colored)
        compose.onNodeWithTag("tool-presets").performClick()
        compose.onNodeWithTag("copy-grade").performClick()
        compose.waitUntil(10_000) { model.state.value.hasCopiedGrade && !model.state.value.isPresetBusy }
        compose.onNodeWithTag("paste-grade").assertIsEnabled()
        importFixture()
        assertTrue(model.state.value.hasCopiedGrade)
        val receiving = EditRecipe(crop = CropRect(.1f, .1f, .9f, .8f), watermark = Watermark(text = "新的文字"))
        setRecipe(receiving)
        compose.onNodeWithTag("tool-presets").performClick()
        compose.onNodeWithTag("paste-grade").performClick()
        awaitPreview()
        val expected = ColorGrade.capture(colored).applyTo(receiving)
        assertEquals(expected, model.state.value.recipe)
        compose.onNodeWithTag("cancel-tool").performClick()
        awaitPreview()
        assertEquals(receiving, model.state.value.recipe)
        compose.onNodeWithTag("tool-presets").performClick()
        compose.onNodeWithTag("paste-grade").performClick()
        compose.onNodeWithTag("apply-tool").performClick()
        awaitPreview()
        assertEquals(expected, model.state.value.recipe)
    }

    @Test fun exportSizeQualityValidationAndFormatProduceExpectedPixels() {
        val before = model.state.value.recipe
        val originalOptions = model.state.value.exportOptions
        compose.onNodeWithTag("open-export").performClick()
        compose.onNodeWithTag("resolution-1080").performClick()
        compose.onNodeWithTag("cancel-export").performClick()
        assertEquals(originalOptions, model.state.value.exportOptions)
        compose.onNodeWithTag("open-export").performClick()
        compose.onNodeWithTag("export-dimensions").assertTextEquals("1600 × 1200 像素")
        compose.onNodeWithTag("resolution-2048").performClick()
        compose.onNodeWithTag("export-dimensions").assertTextEquals("1600 × 1200 像素")
        compose.onNodeWithTag("resolution-1080").performClick()
        compose.onNodeWithTag("export-dimensions").assertTextEquals("1080 × 810 像素")
        compose.onNodeWithTag("jpeg-quality").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(70f) }
        compose.onNodeWithTag("jpeg-quality-value").assertTextEquals("70")
        compose.onNodeWithTag("resolution-custom").performScrollTo().performClick()
        compose.onNodeWithTag("custom-export-edge").performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("save-photo").assertIsNotEnabled()
        compose.onNodeWithTag("custom-export-edge").performTextReplacement("20001")
        compose.onNodeWithTag("save-photo").assertIsNotEnabled()
        compose.onNodeWithTag("custom-export-edge").performTextReplacement("800")
        compose.onNodeWithTag("export-dimensions").performScrollTo().assertTextEquals("800 × 600 像素")
        compose.onNodeWithTag("save-photo").assertIsEnabled().performClick()
        val jpeg = awaitSaved()
        assertEquals(ExportOptions(ExportFormat.JPEG, ExportResolution.CUSTOM_LONG, 800, 70), model.state.value.exportOptions)
        assertExport(jpeg, 800, 600, "image/jpeg")
        compose.onNodeWithTag("saved-dimensions").assertTextEquals("800 × 600 像素")
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithTag("open-export").performClick()
        compose.onNodeWithTag("format-PNG").performClick()
        compose.onNodeWithTag("jpeg-quality").assertDoesNotExist()
        compose.onNodeWithTag("resolution-original").performClick()
        compose.onNodeWithTag("save-photo").performClick()
        val png = awaitSaved()
        assertExport(png, 1600, 1200, "image/png")
        assertEquals(before, model.state.value.recipe)
        assertFalse(model.state.value.canUndo)
    }

    private fun awaitSaved(): Uri {
        compose.waitUntil(30_000) { model.state.value.savedUri != null || model.state.value.error != null }
        assertNull(model.state.value.error)
        return model.state.value.savedUri!!.also { savedUris += it }
    }

    private fun assertExport(uri: Uri, width: Int, height: Int, mime: String) {
        assertEquals(mime, context.contentResolver.getType(uri))
        context.contentResolver.openInputStream(uri).use { stream ->
            val bitmap = BitmapFactory.decodeStream(stream)!!
            assertEquals(width, bitmap.width)
            assertEquals(height, bitmap.height)
            bitmap.recycle()
        }
    }
}
