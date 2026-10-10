package com.kang.imageeditapp

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kang.imageeditapp.data.Draft
import com.kang.imageeditapp.data.DraftRepository
import com.kang.imageeditapp.data.ExportRepository
import com.kang.imageeditapp.data.PhotoRepository
import com.kang.imageeditapp.data.PresetRepository
import com.kang.imageeditapp.data.UnsupportedDraftVersionException
import com.kang.imageeditapp.model.*
import com.kang.imageeditapp.render.ImageRenderEngine
import com.kang.imageeditapp.render.PreviewRenderResult
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(EditorUiState())
    val state = mutableState.asStateFlow()
    private val photoRepository = PhotoRepository(application)
    private val draftRepository = DraftRepository(application)
    private val exportRepository = ExportRepository(application)
    private val presetRepository = PresetRepository(application)
    private var copiedGrade: ColorGrade? = null
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "ImageEdit-GL") }
    private val renderDispatcher = executor.asCoroutineDispatcher()
    private val engineDelegate = lazy { ImageRenderEngine() }
    private val engine by engineDelegate
    private var history = RecipeHistory()
    private var gestureStart: EditRecipe? = null
    private var toolStart: EditRecipe? = null
    private var toolHistory: RecipeHistory? = null
    private var renderGeneration = 0L
    private var importGeneration = 0L
    private var detailGeneration = 0L
    private val renderRequests = Channel<Long>(Channel.CONFLATED)
    private val detailRequests = Channel<Long>(Channel.CONFLATED)
    private var detailRequest: RegionRequest? = null
    private var originalKey: Pair<PhotoSource, EditRecipe>? = null
    // Only committed recipes enter the durable draft; panel previews stay cancelable.
    private val draftMutex = Mutex()
    private val draftRequests = Channel<Unit>(Channel.CONFLATED)
    private var draftEpoch = 0L
    private var latestCommitted: DraftSave? = null

    init {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(isPresetBusy = true)
            try { publishLibrary(presetRepository.load()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { mutableState.value = mutableState.value.copy(error = "无法读取个人预设：${describe(failure)}") }
            finally { mutableState.value = mutableState.value.copy(isPresetBusy = false) }
        }
        viewModelScope.launch {
            try {
                draftMutex.withLock {
                    awaitPendingDraftFlush()
                    val draft = draftRepository.load()
                    if (mutableState.value.source == null && latestCommitted == null) mutableState.value = mutableState.value.copy(draft = draft)
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value = mutableState.value.copy(error = "无法恢复草稿：${describe(failure)}")
            }
        }
        viewModelScope.launch {
            for (ignored in draftRequests) {
                try { draftMutex.withLock { awaitPendingDraftFlush(); saveLatestDraft() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Throwable) { mutableState.value = mutableState.value.copy(error = "草稿保存失败：${describe(failure)}") }
            }
        }
        viewModelScope.launch {
            for (request in renderRequests) {
                delay(30)
                var latest = request
                while (true) { latest = renderRequests.tryReceive().getOrNull() ?: break }
                val snapshot = mutableState.value
                val source = snapshot.source ?: continue
                val originalRecipe = originalRecipe(snapshot.recipe)
                val key = source to originalRecipe
                try {
                    val result = withContext(renderDispatcher) {
                        var preview: PreviewRenderResult? = null
                        var full: PreviewRenderResult? = null
                        var original: Bitmap? = null
                        try {
                            preview = engine.renderAnalyzedPreview(source, snapshot.recipe)
                            full = if (snapshot.activeTool == EditorTool.CROP) engine.renderAnalyzedPreview(source, snapshot.recipe.copy(crop = CropRect(), watermark = Watermark())) else null
                            original = if (originalKey != key || snapshot.originalPreview == null) engine.renderPreview(source, originalRecipe) else null
                            RenderResult(preview, full, original)
                        } catch (failure: Throwable) {
                            preview?.recycle(); full?.recycle(); original?.recycle()
                            throw failure
                        }
                    }
                    if (latest == renderGeneration && mutableState.value.source == source) {
                        originalKey = key
                        mutableState.value = mutableState.value.copy(
                            preview = result.preview.bitmap, analysis = result.preview.analysis,
                            previewOverlay = result.preview.clippingOverlay,
                            fullPreview = result.full?.bitmap, fullPreviewOverlay = result.full?.clippingOverlay,
                            originalPreview = result.original ?: mutableState.value.originalPreview, isRendering = false,
                        )
                        val committed = latestCommitted
                        if (committed?.source == source && committed.recipe == snapshot.recipe && committed.thumbnail == null) {
                            latestCommitted = committed.copy(thumbnail = result.preview.bitmap)
                            draftRequests.trySend(Unit)
                        }
                        if (detailRequest != null) detailRequests.trySend(++detailGeneration)
                    } else result.recycle()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Throwable) {
                    if (latest == renderGeneration) mutableState.value = mutableState.value.copy(isRendering = false, error = describe(failure))
                }
            }
        }
        viewModelScope.launch {
            for (request in detailRequests) {
                delay(120)
                var latest = request
                while (true) { latest = detailRequests.tryReceive().getOrNull() ?: break }
                val region = detailRequest ?: continue
                val snapshot = mutableState.value
                val source = snapshot.source ?: continue
                if (snapshot.isRendering || snapshot.isExporting) continue
                val recipe = when {
                    region.comparing -> originalRecipe(snapshot.recipe)
                    region.cropMode -> snapshot.recipe.copy(crop = CropRect(), watermark = Watermark())
                    else -> snapshot.recipe
                }
                val fullGeneration = renderGeneration
                try {
                    val result = withContext(renderDispatcher) { engine.renderAnalyzedRegion(source, recipe, region.bounds, region.width, region.height) }
                    if (latest == detailGeneration && fullGeneration == renderGeneration && mutableState.value.source == source && detailRequest == region) {
                        mutableState.value = mutableState.value.copy(detail = DetailPreview(result.bitmap, region.bounds, result.clippingOverlay, region.cropMode, region.comparing))
                    } else result.recycle()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Throwable) {
                    if (latest == detailGeneration && fullGeneration == renderGeneration) mutableState.value = mutableState.value.copy(error = "高清预览失败：${describe(failure)}")
                }
            }
        }
    }

    fun importPhoto(uri: Uri) {
        if (mutableState.value.isExporting || mutableState.value.isLoading) return
        val generation = ++importGeneration
        mutableState.value = mutableState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            var imported: PhotoSource? = null
            var accepted = false
            try {
                val source = photoRepository.import(uri)
                imported = source
                draftMutex.withLock {
                    awaitPendingDraftFlush()
                    if (generation != importGeneration) return@withLock
                    val previous = mutableState.value.source
                    val previousDraft = mutableState.value.draft ?: try { draftRepository.load() } catch (_: UnsupportedDraftVersionException) { null }
                    // A new session is published only after its manifest is durable.
                    val draft = draftRepository.save(source, EditRecipe())
                    accepted = true
                    ++draftEpoch
                    latestCommitted = DraftSave(draftEpoch, source, EditRecipe())
                    resetSession()
                    mutableState.value = newSession(source = source, draft = draft)
                    listOfNotNull(previous, previousDraft?.source).distinctBy { it.localPath }
                        .filter { it.localPath != source.localPath }.forEach(::discardAfterRender)
                    requestRender()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) {
                if (generation == importGeneration) mutableState.value = mutableState.value.copy(isLoading = false, error = describe(failure))
            } finally {
                if (!accepted) imported?.let { input ->
                    // IO may commit just before cancellation is delivered to the main dispatcher.
                    withContext(NonCancellable) {
                        draftMutex.withLock {
                            val retained = runCatching { draftRepository.load()?.source?.localPath == input.localPath }.getOrDefault(true)
                            if (!retained) withContext(Dispatchers.IO) { photoRepository.discard(input) }
                        }
                    }
                }
            }
        }
    }

    fun resumeDraft() {
        if (mutableState.value.isLoading || mutableState.value.isExporting || mutableState.value.source != null) return
        val generation = ++importGeneration
        mutableState.value = mutableState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                draftMutex.withLock {
                    awaitPendingDraftFlush()
                    saveLatestDraft()
                    val draft = draftRepository.load()
                    if (generation != importGeneration) return@withLock
                    if (draft == null) {
                        mutableState.value = mutableState.value.copy(isLoading = false, draft = null, error = "草稿原图已不可用，请重新选择图片")
                    } else {
                        resetSession()
                        latestCommitted = DraftSave(draftEpoch, draft.source, draft.recipe)
                        mutableState.value = newSession(source = draft.source, recipe = draft.recipe, draft = draft)
                        requestRender()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { mutableState.value = mutableState.value.copy(isLoading = false, error = describe(failure)) }
        }
    }

    fun deleteDraft() {
        if (mutableState.value.isLoading || mutableState.value.isExporting || mutableState.value.source != null) return
        ++importGeneration
        mutableState.value = mutableState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                draftMutex.withLock {
                    awaitPendingDraftFlush()
                    val pendingSource = latestCommitted?.source
                    val source = draftRepository.delete()
                    ++draftEpoch; latestCommitted = null
                    mutableState.value = mutableState.value.copy(isLoading = false, draft = null)
                    listOfNotNull(source, pendingSource).distinctBy { it.localPath }.forEach(::discardAfterRender)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { mutableState.value = mutableState.value.copy(isLoading = false, error = describe(failure)) }
        }
    }

    fun selectTool(tool: EditorTool) {
        if (mutableState.value.isExporting || mutableState.value.isLoading || mutableState.value.source == null || mutableState.value.activeTool == tool) return
        endGesture()
        // Switching panels applies the preceding tool's changes.
        if (mutableState.value.activeTool != null) { toolStart = null; toolHistory = null; persistCommitted() }
        toolStart = mutableState.value.recipe
        toolHistory = history.copy()
        mutableState.value = mutableState.value.copy(activeTool = tool)
        requestRender()
    }

    fun beginGesture() {
        if (gestureStart == null && !mutableState.value.isExporting && !mutableState.value.isLoading) gestureStart = mutableState.value.recipe
    }

    fun updateRecipe(recipe: EditRecipe) {
        val current = mutableState.value
        if (current.isExporting || current.isLoading || current.source == null || current.recipe == recipe) return
        if (gestureStart == null) history.record(current.recipe, recipe)
        mutableState.value = current.copy(recipe = recipe, canUndo = history.canUndo, canRedo = history.canRedo)
        requestRender()
        if (current.activeTool == null && gestureStart == null) persistCommitted()
    }

    fun endGesture() {
        val hadGesture = gestureStart != null
        gestureStart?.let { history.record(it, mutableState.value.recipe) }
        gestureStart = null
        refreshHistory()
        if (hadGesture && mutableState.value.activeTool == null) persistCommitted()
    }

    fun applyTool() {
        if (mutableState.value.isExporting || mutableState.value.isLoading) return
        endGesture(); toolStart = null; toolHistory = null
        mutableState.value = mutableState.value.copy(activeTool = null)
        persistCommitted(); requestRender()
    }

    fun cancelTool() {
        if (mutableState.value.isExporting || mutableState.value.isLoading) return
        gestureStart = null
        toolStart?.let { mutableState.value = mutableState.value.copy(recipe = it) }
        toolHistory?.let { history = it }
        toolStart = null; toolHistory = null
        mutableState.value = mutableState.value.copy(activeTool = null)
        refreshHistory(); requestRender(); persistCommitted()
    }

    fun undo() {
        if (mutableState.value.isExporting || mutableState.value.isLoading) return
        endGesture()
        history.undo(mutableState.value.recipe)?.let { mutableState.value = mutableState.value.copy(recipe = it); requestRender() }
        refreshHistory()
        if (mutableState.value.activeTool == null) persistCommitted()
    }

    fun redo() {
        if (mutableState.value.isExporting || mutableState.value.isLoading) return
        endGesture()
        history.redo(mutableState.value.recipe)?.let { mutableState.value = mutableState.value.copy(recipe = it); requestRender() }
        refreshHistory()
        if (mutableState.value.activeTool == null) persistCommitted()
    }

    fun resetAll() { beginGesture(); updateRecipe(EditRecipe()); endGesture() }

    fun requestDetail(bounds: CropRect?, width: Int = 0, height: Int = 0, cropMode: Boolean = false, comparing: Boolean = false) {
        val next = bounds?.takeIf {
            listOf(it.left, it.top, it.right, it.bottom).all(Float::isFinite) &&
                it.left >= 0f && it.top >= 0f && it.right <= 1f && it.bottom <= 1f && it.width > 0 && it.height > 0 &&
                width in 1..4096 && height in 1..4096 && width.toLong() * height <= 12_000_000
        }?.let { RegionRequest(it, width, height, cropMode, comparing) }
        if (detailRequest == next) return
        detailRequest = next
        ++detailGeneration
        if (next == null) mutableState.value = mutableState.value.copy(detail = null)
        else detailRequests.trySend(detailGeneration)
    }

    fun savePreset(name: String) {
        val current = mutableState.value
        if (!canUseGrade(current)) return
        val grade = ColorGrade.capture(current.recipe)
        mutateLibrary("预设已保存") { presetRepository.savePreset(name, grade) }
    }

    fun renamePreset(id: String, name: String) { mutateLibrary("预设已重命名") { presetRepository.renamePreset(id, name) } }
    fun deletePreset(id: String) { mutateLibrary("预设已删除") { presetRepository.deletePreset(id) } }

    fun applyPreset(id: String) {
        val current = mutableState.value
        if (!canUseGrade(current)) return
        val preset = current.presets.firstOrNull { it.id == id } ?: return
        endGesture()
        updateRecipe(preset.grade.applyTo(current.recipe))
        mutableState.value = mutableState.value.copy(notice = "已套用「${preset.name}」")
    }

    fun applyBuiltInFilter(id: String) {
        val current = mutableState.value
        if (!canUseGrade(current)) return
        val filter = BuiltInFilters.find(id) ?: return
        endGesture()
        updateRecipe(filter.grade.applyTo(current.recipe))
        mutableState.value = mutableState.value.copy(notice = if (id == "original") "已恢复原始调色" else "已套用「${filter.name}」滤镜")
    }

    fun copyColorGrade() {
        val current = mutableState.value
        if (!canUseGrade(current)) return
        val grade = ColorGrade.capture(current.recipe)
        mutateLibrary("调色参数已复制，可用于其他照片") { presetRepository.copyGrade(grade) }
    }

    fun pasteColorGrade() {
        val current = mutableState.value
        if (!canUseGrade(current)) return
        val grade = copiedGrade ?: return
        endGesture()
        updateRecipe(grade.applyTo(current.recipe))
        mutableState.value = mutableState.value.copy(notice = "调色参数已粘贴")
    }

    fun dismissNotice() { mutableState.value = mutableState.value.copy(notice = null) }

    private fun canUseGrade(current: EditorUiState) = current.source != null && !current.isLoading && !current.isExporting && !current.isPresetBusy

    private fun mutateLibrary(message: String, operation: suspend () -> PresetLibrary) {
        if (mutableState.value.isPresetBusy || mutableState.value.isExporting || mutableState.value.isLoading) return
        mutableState.value = mutableState.value.copy(isPresetBusy = true, error = null, notice = null)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                // Accepted library changes complete atomically even when the Activity finishes.
                val library = withContext(NonCancellable) { operation() }
                publishLibrary(library)
                mutableState.value = mutableState.value.copy(notice = message)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { mutableState.value = mutableState.value.copy(error = describe(failure)) }
            finally { mutableState.value = mutableState.value.copy(isPresetBusy = false) }
        }
    }

    private fun publishLibrary(library: PresetLibrary) {
        copiedGrade = library.clipboard
        mutableState.value = mutableState.value.copy(presets = library.presets, hasCopiedGrade = library.clipboard != null)
    }

    fun export(format: ExportFormat) = export(ExportOptions(format = format))

    fun export(options: ExportOptions) {
        val snapshot = mutableState.value
        val source = snapshot.source ?: return
        if (snapshot.isExporting || snapshot.isLoading) return
        val outputSize = try { options.validate(); options.resolveSize(Geometry.outputSize(source, snapshot.recipe)) }
        catch (failure: IllegalArgumentException) {
            mutableState.value = mutableState.value.copy(error = describe(failure))
            return
        }
        endGesture()
        mutableState.value = mutableState.value.copy(isExporting = true, exportFormat = options.format, exportOptions = options, error = null, savedUri = null, savedSize = null)
        viewModelScope.launch {
            var bitmap: Bitmap? = null
            try {
                bitmap = withContext(renderDispatcher) { engine.renderExport(source, snapshot.recipe, options) }
                val uri = exportRepository.save(bitmap, options)
                mutableState.value = mutableState.value.copy(isExporting = false, savedUri = uri, savedSize = outputSize)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { mutableState.value = mutableState.value.copy(isExporting = false, error = describe(failure)) }
            finally {
                bitmap?.recycle()
                if (detailRequest != null) detailRequests.trySend(++detailGeneration)
            }
        }
    }

    fun dismissError() { mutableState.value = mutableState.value.copy(error = null) }
    fun dismissSaved() { mutableState.value = mutableState.value.copy(savedUri = null, savedSize = null) }
    fun closePhoto() {
        if (mutableState.value.isExporting || mutableState.value.isLoading) return
        if (mutableState.value.activeTool != null) cancelTool() else persistCommitted()
        ++importGeneration
        resetSession()
        mutableState.value = newSession(draft = mutableState.value.draft)
    }

    /** Libraries and copy/paste are independent of the currently open photograph. */
    private fun newSession(source: PhotoSource? = null, recipe: EditRecipe = EditRecipe(), draft: Draft? = null): EditorUiState {
        val previous = mutableState.value
        return EditorUiState(source = source, recipe = recipe, draft = draft,
            presets = previous.presets, hasCopiedGrade = previous.hasCopiedGrade,
            isPresetBusy = previous.isPresetBusy, exportOptions = previous.exportOptions, exportFormat = previous.exportOptions.format)
    }

    private fun resetSession() {
        ++renderGeneration; ++detailGeneration
        gestureStart = null; toolStart = null; toolHistory = null; originalKey = null
        detailRequest = null; history.clear()
    }

    private fun persistCommitted() {
        val snapshot = mutableState.value
        val source = snapshot.source ?: return
        val recipe = toolStart ?: snapshot.recipe
        latestCommitted = DraftSave(draftEpoch, source, recipe, snapshot.preview?.takeIf { !snapshot.isRendering && snapshot.recipe == recipe })
        draftRequests.trySend(Unit)
    }

    /** Must hold draftMutex. The save channel coalesces edits arriving during IO. */
    private suspend fun saveLatestDraft(): Draft? {
        val request = latestCommitted ?: return mutableState.value.draft
        if (request.epoch != draftEpoch) return mutableState.value.draft
        val draft = draftRepository.save(request.source, request.recipe, request.thumbnail)
        if (request.epoch == draftEpoch) mutableState.value = mutableState.value.copy(draft = draft)
        return draft
    }

    private fun discardAfterRender(source: PhotoSource) { executor.execute { photoRepository.discard(source) } }
    private fun requestRender() {
        ++detailGeneration
        mutableState.value = mutableState.value.copy(isRendering = true, detail = null)
        renderRequests.trySend(++renderGeneration)
    }
    private fun refreshHistory() { mutableState.value = mutableState.value.copy(canUndo = history.canUndo, canRedo = history.canRedo) }
    private fun originalRecipe(recipe: EditRecipe) = EditRecipe(crop = recipe.crop, quarterTurns = recipe.quarterTurns, flipHorizontal = recipe.flipHorizontal, flipVertical = recipe.flipVertical)
    private fun describe(failure: Throwable): String = when (failure) {
        is OutOfMemoryError -> "图片尺寸超过当前设备可用内存，请选择较小的图片后重试"
        is SecurityException -> "图片读取权限失效，请重新选择图片"
        else -> failure.message?.takeIf { it.isNotBlank() } ?: "图片处理失败，请重试"
    }

    override fun onCleared() {
        renderRequests.close(); detailRequests.close(); draftRequests.close()
        val committed = latestCommitted
        if (committed != null) {
            val previousFlush = pendingDraftFlush
            pendingDraftFlush = draftCleanupScope.launch(start = CoroutineStart.UNDISPATCHED) {
                previousFlush?.join()
                try {
                    draftMutex.withLock {
                        if (committed.epoch == draftEpoch) draftRepository.save(committed.source, committed.recipe, committed.thumbnail)
                    }
                } catch (failure: Exception) { Log.w("ImageEdit", "Final draft save failed", failure) }
            }
        }
        executor.execute {
            try { if (engineDelegate.isInitialized()) engine.close() }
            finally { renderDispatcher.close() }
        }
        super.onCleared()
    }

    private suspend fun awaitPendingDraftFlush() { pendingDraftFlush?.join() }

    companion object {
        private val draftCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile private var pendingDraftFlush: Job? = null
    }

    private data class RegionRequest(val bounds: CropRect, val width: Int, val height: Int, val cropMode: Boolean, val comparing: Boolean)
    private data class DraftSave(val epoch: Long, val source: PhotoSource, val recipe: EditRecipe, val thumbnail: Bitmap? = null)
    private data class RenderResult(val preview: PreviewRenderResult, val full: PreviewRenderResult?, val original: Bitmap?) {
        fun recycle() { preview.recycle(); full?.recycle(); original?.recycle() }
    }
}

private fun PreviewRenderResult.recycle() { bitmap.recycle(); clippingOverlay.recycle() }
