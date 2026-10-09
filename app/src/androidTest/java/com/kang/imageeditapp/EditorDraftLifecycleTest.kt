package com.kang.imageeditapp

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kang.imageeditapp.data.DraftRepository
import com.kang.imageeditapp.model.ColorAdjustments
import com.kang.imageeditapp.model.EditRecipe
import com.kang.imageeditapp.model.EditorTool
import com.kang.imageeditapp.model.Watermark
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** ViewModel/session integration uses a separate private-files root, never the user's live draft. */
@RunWith(AndroidJUnit4::class)
class EditorDraftLifecycleTest {
    private lateinit var root: File
    private lateinit var application: Application
    private lateinit var fixture: File
    private val stores = mutableListOf<ViewModelStore>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun setUp() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        root = File(base.filesDir, "draft-lifecycle-${UUID.randomUUID()}").apply { mkdirs() }
        application = IsolatedApplication(base, root)
        fixture = File(root, "fixture.png")
        val bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(70, 100, 150)) }
        try { fixture.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }

    @After fun tearDown() {
        main { stores.forEach(ViewModelStore::clear); stores.clear() }
        // The last store may launch its final flush; a new store observes the production barrier.
        if (File(root, "drafts/draft.json").exists()) {
            val (observer, store) = createModel()
            await("final draft flush") { observer.state.value.draft != null || observer.state.value.error != null }
            main { store.clear(); stores.remove(store) }
        }
        root.deleteRecursively()
    }

    @Test fun appliedRecipeSurvivesViewModelStoreClearAndResumesWithoutUndoHistory() {
        val (model, store) = importedModel()
        val recipe = adjustedRecipe(.75f)
        commit(model, recipe)
        awaitDurable(model, recipe)
        val source = model.state.value.source!!
        main { store.clear(); stores.remove(store) }

        val (restored, _) = createModel()
        await("draft restoration") { restored.state.value.draft?.recipe == recipe }
        assertNull(restored.state.value.source)
        assertEquals(source, restored.state.value.draft!!.source)
        assertTrue(File(source.localPath).isFile)
        main { restored.resumeDraft() }
        awaitPreview(restored)
        assertEquals(source, restored.state.value.source)
        assertEquals(recipe, restored.state.value.recipe)
        assertFalse(restored.state.value.canUndo)
        assertFalse(restored.state.value.canRedo)
    }

    @Test fun applyingThenImmediatelyClearingStoreStillPersistsCompletedEdit() {
        val (model, store) = importedModel()
        val recipe = adjustedRecipe(1.25f)
        // No main-loop turn is allowed between apply and clear, so the normal save channel cannot run.
        main {
            model.selectTool(EditorTool.ADJUST)
            model.beginGesture(); model.updateRecipe(recipe); model.endGesture(); model.applyTool()
            store.clear(); stores.remove(store)
        }
        val (restored, _) = createModel()
        await("immediate-clear flush") { restored.state.value.draft?.recipe == recipe }
        assertEquals(recipe, runBlocking { DraftRepository(application).load() }!!.recipe)
    }

    @Test fun canceledAndAbandonedPanelChangesNeverBecomeDurable() {
        val (model, store) = importedModel()
        val committed = adjustedRecipe(.5f)
        commit(model, committed)
        awaitDurable(model, committed)
        val previewOnly = committed.copy(watermark = Watermark("未应用的中文文字", x = .2f, y = .3f))
        main {
            model.selectTool(EditorTool.TEXT)
            model.beginGesture(); model.updateRecipe(previewOnly); model.endGesture()
        }
        awaitPreview(model)
        assertEquals(committed, runBlocking { DraftRepository(application).load() }!!.recipe)
        main { model.cancelTool() }
        awaitDurable(model, committed)
        assertEquals(committed, model.state.value.recipe)

        main {
            model.selectTool(EditorTool.TEXT)
            model.beginGesture(); model.updateRecipe(previewOnly); model.endGesture()
            store.clear(); stores.remove(store)
        }
        val (restored, _) = createModel()
        await("abandoned-panel restoration") { restored.state.value.draft != null }
        assertEquals(committed, restored.state.value.draft!!.recipe)
    }

    @Test fun applyUndoAndRedoEachPersistTheirCommittedRecipe() {
        val (model, _) = importedModel()
        val recipe = adjustedRecipe(.8f)
        commit(model, recipe)
        awaitDurable(model, recipe)
        assertTrue(model.state.value.canUndo)
        main { model.undo() }
        awaitDurable(model, EditRecipe())
        assertEquals(EditRecipe(), model.state.value.recipe)
        assertTrue(model.state.value.canRedo)
        main { model.redo() }
        awaitDurable(model, recipe)
        assertEquals(recipe, model.state.value.recipe)
    }

    @Test fun closeResumeAndDeleteRetainThenReleaseOriginal() {
        val (model, _) = importedModel()
        val recipe = adjustedRecipe(.6f)
        commit(model, recipe)
        awaitDurable(model, recipe)
        val original = File(model.state.value.source!!.localPath)
        main { model.closePhoto() }
        assertNull(model.state.value.source)
        assertTrue(original.isFile)
        main { model.resumeDraft() }
        awaitPreview(model)
        assertEquals(recipe, model.state.value.recipe)
        assertEquals(original.path, model.state.value.source!!.localPath)
        main { model.closePhoto(); model.deleteDraft() }
        await("draft deletion") { !model.state.value.isLoading && model.state.value.draft == null && !original.exists() }
        assertNull(runBlocking { DraftRepository(application).load() })
    }

    @Test fun failedImportAndManifestStorageFailurePreserveCurrentWork() {
        val (model, _) = importedModel()
        val recipe = adjustedRecipe(.45f)
        commit(model, recipe)
        awaitDurable(model, recipe)
        val source = model.state.value.source!!
        main { model.importPhoto(Uri.fromFile(File(root, "missing.png"))) }
        await("failed import") { !model.state.value.isLoading && model.state.value.error != null }
        assertEquals(source, model.state.value.source)
        assertEquals(recipe, model.state.value.recipe)
        main { model.dismissError() }

        val blockedWrite = File(root, "drafts/draft.json.new").apply { mkdirs() }
        File(blockedWrite, "occupied").writeText("storage failure")
        main { model.importPhoto(Uri.fromFile(fixture)) }
        await("failed replacement save") { !model.state.value.isLoading && model.state.value.error != null }
        assertEquals(source, model.state.value.source)
        assertEquals(recipe, model.state.value.recipe)
        blockedWrite.deleteRecursively()
        assertEquals(source, runBlocking { DraftRepository(application).load() }!!.source)
        assertEquals(recipe, runBlocking { DraftRepository(application).load() }!!.recipe)
        assertTrue(File(source.localPath).isFile)
    }

    @Test fun autosaveFailurePreservesVisibleEditAndAllowsRetry() {
        val (model, _) = importedModel()
        val previous = adjustedRecipe(.3f)
        commit(model, previous)
        awaitDurable(model, previous)
        awaitPreview(model)
        val source = model.state.value.source
        val blockedWrite = File(root, "drafts/draft.json.new").apply { mkdirs() }
        File(blockedWrite, "occupied").writeText("storage failure")
        val edited = adjustedRecipe(.9f)
        commit(model, edited)
        await("failed autosave") { model.state.value.error?.startsWith("草稿保存失败") == true }
        assertEquals(source, model.state.value.source)
        assertEquals(edited, model.state.value.recipe)
        assertEquals(previous, runBlocking { DraftRepository(application).load() }!!.recipe)
        blockedWrite.deleteRecursively()
        main { model.dismissError(); model.applyTool() }
        awaitDurable(model, edited)
    }

    private fun adjustedRecipe(exposure: Float) = EditRecipe(adjustments = ColorAdjustments(exposure = exposure),
        watermark = Watermark("中文草稿", x = .35f, y = .65f))

    private fun commit(model: EditorViewModel, recipe: EditRecipe) = main {
        model.selectTool(EditorTool.ADJUST)
        model.beginGesture(); model.updateRecipe(recipe); model.endGesture(); model.applyTool()
    }

    private fun importedModel(): Pair<EditorViewModel, ViewModelStore> {
        val pair = createModel()
        main { pair.first.importPhoto(Uri.fromFile(fixture)) }
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
        await("preview") { model.state.value.let { !it.isLoading && !it.isRendering && it.preview != null } }
        assertNull(model.state.value.error)
    }

    private fun awaitDurable(model: EditorViewModel, recipe: EditRecipe) {
        await("durable recipe and rendered thumbnail") {
            val state = model.state.value
            val preview = state.preview
            val path = state.draft?.thumbnailPath
            if (state.draft?.recipe != recipe || state.recipe != recipe || state.isRendering || preview == null || path == null) false
            else {
                val thumbnail = BitmapFactory.decodeFile(path)
                if (thumbnail == null) false else try { thumbnail.getPixel(0, 0) == preview.getPixel(0, 0) }
                finally { thumbnail.recycle() }
            }
        }
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
