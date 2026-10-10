package com.kang.imageeditapp

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kang.imageeditapp.data.DraftRepository
import com.kang.imageeditapp.data.PresetRepository
import com.kang.imageeditapp.model.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Test photo, drafts, and color libraries are isolated from the user's private files. */
@RunWith(AndroidJUnit4::class)
class ColorWorkflowIntegrationTest {
    private lateinit var root: File
    private lateinit var application: Application
    private lateinit var fixtureA: File
    private lateinit var fixtureB: File
    private val stores = mutableListOf<ViewModelStore>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val presetGrade = ColorGrade(
        adjustments = ColorAdjustments(.65f, .1f, .2f, -.15f, -.3f, .1f, .2f, -.2f),
        curves = CurveSet(rgb = listOf(CurvePoint(0f, .05f), CurvePoint(.5f, .6f), CurvePoint(1f, .95f))),
        hsl = List(8) { HslAdjustment(hue = (it - 4) / 8f, saturation = .1f, lightness = -.1f) },
    )

    @Before fun setUp() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        root = File(base.filesDir, "color-workflow-${UUID.randomUUID()}").apply { mkdirs() }
        application = IsolatedApplication(base, root)
        fixtureA = fixture("photo-a.png", 96, 64, Color.rgb(75, 110, 150))
        fixtureB = fixture("photo-b.png", 64, 96, Color.rgb(160, 95, 60))
    }

    @After fun tearDown() {
        main { stores.toList().forEach(ViewModelStore::clear); stores.clear() }
        if (File(root, "drafts/draft.json").exists()) {
            val (observer, store) = createModel()
            await("final draft and library flush") {
                observer.state.value.let { !it.isPresetBusy && (it.draft != null || it.error != null) }
            }
            main { store.clear(); stores.remove(store) }
        }
        root.deleteRecursively()
    }

    @Test fun applyingPresetPreservesCropRotationFlipsAndChineseWatermark() {
        val preset = runBlocking { PresetRepository(application).savePreset("摄影预设", presetGrade) }.presets.single()
        val (model, _) = importedModel()
        val receivingRecipe = receivingRecipe()
        commit(model, receivingRecipe)
        awaitDurable(model, receivingRecipe)
        val source = model.state.value.source
        main { model.selectTool(EditorTool.PRESETS); model.applyPreset(preset.id) }
        val expected = presetGrade.applyTo(receivingRecipe)
        assertEquals(expected, model.state.value.recipe)
        assertEquals(source, model.state.value.source)
        assertEquals(receivingRecipe.crop, model.state.value.recipe.crop)
        assertEquals(receivingRecipe.quarterTurns, model.state.value.recipe.quarterTurns)
        assertEquals(receivingRecipe.flipHorizontal, model.state.value.recipe.flipHorizontal)
        assertEquals(receivingRecipe.flipVertical, model.state.value.recipe.flipVertical)
        assertEquals(receivingRecipe.watermark, model.state.value.recipe.watermark)
        main { model.applyTool() }
        awaitDurable(model, expected)
    }

    @Test fun builtInFilterRestoresWithDraftAndCanBeSavedWithoutSeedingPersonalLibrary() {
        val (model, store) = importedModel()
        assertTrue(model.state.value.presets.isEmpty())
        val before = receivingRecipe()
        commit(model, before)
        awaitDurable(model, before)
        val filter = BuiltInFilters.find("film")!!
        val expected = filter.grade.applyTo(before)
        main { model.selectTool(EditorTool.PRESETS); model.applyBuiltInFilter(filter.id) }
        awaitPreview(model)
        assertEquals(expected, model.state.value.recipe)
        assertEquals(before, runBlocking { DraftRepository(application).load() }!!.recipe)
        assertTrue(runBlocking { PresetRepository(application).load() }.presets.isEmpty())
        main { model.applyTool() }
        awaitDurable(model, expected)
        main { store.clear(); stores.remove(store) }
        val (restored, _) = createModel()
        await("filtered draft restoration") { restored.state.value.let { !it.isPresetBusy && it.draft?.recipe == expected } }
        main { restored.resumeDraft() }
        awaitPreview(restored)
        assertEquals(expected, restored.state.value.recipe)
        main { restored.savePreset("胶片副本") }
        await("saved built-in grade") { !restored.state.value.isPresetBusy && restored.state.value.presets.size == 1 }
        assertEquals(filter.grade, runBlocking { PresetRepository(application).load() }.presets.single().grade)
        main { restored.selectTool(EditorTool.PRESETS); restored.applyBuiltInFilter("original"); restored.applyTool() }
        awaitDurable(restored, before)
        assertEquals(1, restored.state.value.presets.size)
    }

    @Test fun cancelingPresetPanelRestoresParametersAndHistoryCheckpoint() {
        val preset = runBlocking { PresetRepository(application).savePreset("待取消预设", presetGrade) }.presets.single()
        val (model, _) = importedModel()
        val before = receivingRecipe()
        commit(model, before)
        awaitDurable(model, before)
        main {
            model.selectTool(EditorTool.PRESETS)
            model.applyPreset(preset.id)
            model.cancelTool()
        }
        awaitDurable(model, before)
        assertEquals(before, model.state.value.recipe)
        assertTrue(model.state.value.canUndo)
        assertFalse(model.state.value.canRedo)
        main { model.undo() }
        awaitDurable(model, EditRecipe())
        assertFalse(model.state.value.canUndo)
        assertTrue(model.state.value.canRedo)
        main { model.redo() }
        awaitDurable(model, before)
    }

    @Test fun applyingPresetIsOneDurableUndoAndRedoStep() {
        val preset = runBlocking { PresetRepository(application).savePreset("可撤销预设", presetGrade) }.presets.single()
        val (model, _) = importedModel()
        val before = receivingRecipe()
        commit(model, before)
        awaitDurable(model, before)
        main { model.selectTool(EditorTool.PRESETS); model.applyPreset(preset.id); model.applyTool() }
        val applied = presetGrade.applyTo(before)
        awaitDurable(model, applied)
        main { model.undo() }
        awaitDurable(model, before)
        assertTrue(model.state.value.canRedo)
        main { model.redo() }
        awaitDurable(model, applied)
        assertFalse(model.state.value.canRedo)
    }

    @Test fun copyFromPhotoAPasteIntoPhotoBAndRestoreClipboardAcrossModels() {
        val (model, store) = importedModel()
        val photoARecipe = presetGrade.applyTo(receivingRecipe())
        commit(model, photoARecipe)
        awaitDurable(model, photoARecipe)
        main { model.copyColorGrade() }
        await("copied color parameters") { !model.state.value.isPresetBusy && model.state.value.hasCopiedGrade }
        assertEquals(presetGrade, runBlocking { PresetRepository(application).load() }.clipboard)

        main { model.importPhoto(Uri.fromFile(fixtureB)) }
        awaitPreview(model)
        awaitDurable(model, EditRecipe())
        assertTrue(model.state.value.hasCopiedGrade)
        val photoBSource = model.state.value.source
        val photoBRecipe = receivingRecipe().copy(
            crop = CropRect(.1f, .25f, .95f, .85f), quarterTurns = 2,
            flipHorizontal = false, watermark = Watermark("照片 B 中文水印", x = .65f, y = .4f),
        )
        commit(model, photoBRecipe)
        awaitDurable(model, photoBRecipe)
        main { model.selectTool(EditorTool.PRESETS); model.pasteColorGrade(); model.applyTool() }
        val expected = presetGrade.applyTo(photoBRecipe)
        awaitDurable(model, expected)
        assertEquals(photoBSource, model.state.value.source)
        main { store.clear(); stores.remove(store) }

        val (restored, _) = createModel()
        await("clipboard and draft restoration") {
            restored.state.value.let { !it.isPresetBusy && it.hasCopiedGrade && it.draft?.recipe == expected }
        }
        main { restored.resumeDraft() }
        awaitPreview(restored)
        assertEquals(expected, restored.state.value.recipe)
        assertFalse(restored.state.value.canUndo)
        assertEquals(presetGrade, runBlocking { PresetRepository(application).load() }.clipboard)
    }

    @Test fun createRenameDeleteAndFailedDuplicateReflectInStateWithoutEditingPhoto() {
        val (model, _) = importedModel()
        val recipe = presetGrade.applyTo(receivingRecipe())
        commit(model, recipe)
        awaitDurable(model, recipe)
        val source = model.state.value.source
        val beforeUndo = model.state.value.canUndo
        main { model.savePreset("人像通透") }
        await("preset creation") { !model.state.value.isPresetBusy && model.state.value.presets.size == 1 }
        val preset = model.state.value.presets.single()
        assertEquals(presetGrade, preset.grade)
        main { model.renamePreset(preset.id, "  暖色人像  ") }
        await("preset rename") { !model.state.value.isPresetBusy && model.state.value.presets.singleOrNull()?.name == "暖色人像" }
        main { model.savePreset("暖色人像") }
        await("duplicate-name error") { !model.state.value.isPresetBusy && model.state.value.error != null }
        assertTrue(model.state.value.error!!.contains("同名"))
        assertEquals(1, model.state.value.presets.size)
        main { model.dismissError(); model.deletePreset(preset.id) }
        await("preset deletion") { !model.state.value.isPresetBusy && model.state.value.presets.isEmpty() }
        assertEquals(source, model.state.value.source)
        assertEquals(recipe, model.state.value.recipe)
        assertEquals(beforeUndo, model.state.value.canUndo)
        assertTrue(File(source!!.localPath).isFile)
        assertEquals(recipe, runBlocking { DraftRepository(application).load() }!!.recipe)
    }

    @Test fun savePresetThenImmediatelyClearStoreStillCommitsLibrary() {
        val (model, store) = importedModel()
        val recipe = presetGrade.applyTo(receivingRecipe())
        commit(model, recipe)
        awaitDurable(model, recipe)
        main {
            model.savePreset("退出前保存")
            store.clear(); stores.remove(store)
        }
        val (restored, _) = createModel()
        await("immediate-clear preset persistence") {
            restored.state.value.let { !it.isPresetBusy && it.presets.singleOrNull()?.name == "退出前保存" }
        }
        assertEquals(presetGrade, restored.state.value.presets.single().grade)
        assertEquals("退出前保存", runBlocking { PresetRepository(application).load() }.presets.single().name)
    }

    @Test fun copyThenImmediatelyClearStoreStillCommitsClipboard() {
        val (model, store) = importedModel()
        val recipe = presetGrade.applyTo(receivingRecipe())
        commit(model, recipe)
        awaitDurable(model, recipe)
        main {
            model.copyColorGrade()
            store.clear(); stores.remove(store)
        }
        val (restored, _) = createModel()
        await("immediate-clear clipboard persistence") { !restored.state.value.isPresetBusy && restored.state.value.hasCopiedGrade }
        assertEquals(presetGrade, runBlocking { PresetRepository(application).load() }.clipboard)
    }

    private fun receivingRecipe() = EditRecipe(
        crop = CropRect(.15f, .1f, .9f, .85f), quarterTurns = 3,
        flipHorizontal = true, flipVertical = true,
        watermark = Watermark("照片中文水印\n第二行", 0xCC99BBFF.toInt(), .07f, .35f, .65f),
    )

    private fun fixture(name: String, width: Int, height: Int, color: Int): File {
        val file = File(root, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        return file
    }

    private fun commit(model: EditorViewModel, recipe: EditRecipe) = main {
        model.selectTool(EditorTool.ADJUST)
        model.beginGesture(); model.updateRecipe(recipe); model.endGesture(); model.applyTool()
    }

    private fun importedModel(): Pair<EditorViewModel, ViewModelStore> {
        val pair = createModel()
        await("initial preset library") { !pair.first.state.value.isPresetBusy }
        main { pair.first.importPhoto(Uri.fromFile(fixtureA)) }
        awaitPreview(pair.first)
        awaitDurable(pair.first, EditRecipe())
        return pair
    }

    private fun createModel(): Pair<EditorViewModel, ViewModelStore> {
        val store = ViewModelStore()
        lateinit var model: EditorViewModel
        main {
            stores += store
            model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[EditorViewModel::class.java]
        }
        return model to store
    }

    private fun awaitPreview(model: EditorViewModel) {
        await("rendered preview") { model.state.value.let { !it.isLoading && !it.isRendering && it.preview != null } }
        assertNull(model.state.value.error)
    }

    private fun awaitDurable(model: EditorViewModel, recipe: EditRecipe) {
        await("durable edit") {
            model.state.value.let { !it.isLoading && !it.isRendering && it.preview != null && it.recipe == recipe && it.draft?.recipe == recipe }
        }
        assertNull(model.state.value.error)
        assertEquals(recipe, runBlocking { DraftRepository(application).load() }!!.recipe)
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync { block() }

    private fun await(description: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(20)
        }
        fail("Timed out waiting for $description")
    }

    private class IsolatedApplication(base: Context, private val directory: File) : Application() {
        init { attachBaseContext(base) }
        override fun getFilesDir(): File = directory
        override fun getCacheDir(): File = File(directory, "cache").apply { mkdirs() }
    }
}
