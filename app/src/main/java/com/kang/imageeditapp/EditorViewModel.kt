package com.kang.imageeditapp

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kang.imageeditapp.data.ExportRepository
import com.kang.imageeditapp.data.PhotoRepository
import com.kang.imageeditapp.model.*
import com.kang.imageeditapp.render.ImageRenderEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(EditorUiState())
    val state = mutableState.asStateFlow()
    private val photoRepository = PhotoRepository(application)
    private val exportRepository = ExportRepository(application)
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
    private val renderRequests = Channel<Long>(Channel.CONFLATED)
    private var originalKey: Pair<PhotoSource, EditRecipe>? = null

    init {
        viewModelScope.launch {
            for (request in renderRequests) {
                delay(30)
                var latest = request
                while (true) { latest = renderRequests.tryReceive().getOrNull() ?: break }
                val snapshot = mutableState.value
                val source = snapshot.source ?: continue
                val originalRecipe = EditRecipe(
                    crop = snapshot.recipe.crop,
                    quarterTurns = snapshot.recipe.quarterTurns,
                    flipHorizontal = snapshot.recipe.flipHorizontal,
                    flipVertical = snapshot.recipe.flipVertical,
                )
                val key = source to originalRecipe
                try {
                    val result = withContext(renderDispatcher) {
                        val preview = engine.renderPreview(source, snapshot.recipe)
                        val full = if (snapshot.activeTool == EditorTool.CROP) {
                            engine.renderPreview(source, snapshot.recipe.copy(crop = CropRect(), watermark = Watermark()))
                        } else null
                        val original = if (originalKey != key || snapshot.originalPreview == null) {
                            engine.renderPreview(source, originalRecipe)
                        } else null
                        RenderResult(preview, full, original)
                    }
                    if (latest == renderGeneration && mutableState.value.source == source) {
                        originalKey = key
                        mutableState.value = mutableState.value.copy(
                            preview = result.preview,
                            fullPreview = result.full ?: mutableState.value.fullPreview,
                            originalPreview = result.original ?: mutableState.value.originalPreview,
                            isRendering = false,
                        )
                    } else result.recycle()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Throwable) {
                    if (latest == renderGeneration) mutableState.value = mutableState.value.copy(isRendering = false, error = describe(failure))
                }
            }
        }
    }

    fun importPhoto(uri: Uri) {
        if (mutableState.value.isExporting) return
        val generation = ++importGeneration
        mutableState.value = mutableState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                val source = photoRepository.import(uri)
                if (generation != importGeneration) {
                    withContext(Dispatchers.IO) { photoRepository.discard(source) }
                    return@launch
                }
                val previous = mutableState.value.source
                history.clear()
                gestureStart = null; toolStart = null; toolHistory = null; originalKey = null
                mutableState.value = EditorUiState(source = source)
                previous?.let { discardAfterRender(it) }
                requestRender()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) {
                if (generation == importGeneration) mutableState.value = mutableState.value.copy(isLoading = false, error = describe(failure))
            }
        }
    }

    fun selectTool(tool: EditorTool) {
        if (mutableState.value.isExporting || mutableState.value.source == null || mutableState.value.activeTool == tool) return
        endGesture()
        toolStart = mutableState.value.recipe
        toolHistory = history.copy()
        mutableState.value = mutableState.value.copy(activeTool = tool)
        requestRender()
    }

    fun beginGesture() {
        if (gestureStart == null && !mutableState.value.isExporting) gestureStart = mutableState.value.recipe
    }

    fun updateRecipe(recipe: EditRecipe) {
        val current = mutableState.value
        if (current.isExporting || current.source == null || current.recipe == recipe) return
        if (gestureStart == null) history.record(current.recipe, recipe)
        mutableState.value = current.copy(recipe = recipe, canUndo = history.canUndo, canRedo = history.canRedo)
        requestRender()
    }

    fun endGesture() {
        gestureStart?.let { history.record(it, mutableState.value.recipe) }
        gestureStart = null
        refreshHistory()
    }

    fun applyTool() {
        if (mutableState.value.isExporting) return
        endGesture(); toolStart = null; toolHistory = null
        mutableState.value = mutableState.value.copy(activeTool = null)
    }

    fun cancelTool() {
        if (mutableState.value.isExporting) return
        gestureStart = null
        toolStart?.let { mutableState.value = mutableState.value.copy(recipe = it) }
        toolHistory?.let { history = it }
        toolStart = null; toolHistory = null
        mutableState.value = mutableState.value.copy(activeTool = null)
        refreshHistory(); requestRender()
    }

    fun undo() {
        if (mutableState.value.isExporting) return
        endGesture()
        history.undo(mutableState.value.recipe)?.let { mutableState.value = mutableState.value.copy(recipe = it); requestRender() }
        refreshHistory()
    }

    fun redo() {
        if (mutableState.value.isExporting) return
        endGesture()
        history.redo(mutableState.value.recipe)?.let { mutableState.value = mutableState.value.copy(recipe = it); requestRender() }
        refreshHistory()
    }

    fun resetAll() {
        beginGesture(); updateRecipe(EditRecipe()); endGesture()
    }

    fun export(format: ExportFormat) {
        val snapshot = mutableState.value
        val source = snapshot.source ?: return
        if (snapshot.isExporting || snapshot.isLoading) return
        endGesture()
        mutableState.value = mutableState.value.copy(isExporting = true, exportFormat = format, error = null, savedUri = null)
        viewModelScope.launch {
            var bitmap: Bitmap? = null
            try {
                bitmap = withContext(renderDispatcher) { engine.renderExport(source, snapshot.recipe) }
                val uri = exportRepository.save(bitmap, format)
                mutableState.value = mutableState.value.copy(isExporting = false, savedUri = uri)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { mutableState.value = mutableState.value.copy(isExporting = false, error = describe(failure)) }
            finally { bitmap?.recycle() }
        }
    }

    fun dismissError() { mutableState.value = mutableState.value.copy(error = null) }
    fun dismissSaved() { mutableState.value = mutableState.value.copy(savedUri = null) }
    fun closePhoto() {
        if (mutableState.value.isExporting) return
        val previous = mutableState.value.source
        ++importGeneration; ++renderGeneration
        gestureStart = null; toolStart = null; toolHistory = null; originalKey = null; history.clear()
        mutableState.value = EditorUiState()
        previous?.let { discardAfterRender(it) }
    }

    private fun discardAfterRender(source: PhotoSource) {
        executor.execute { photoRepository.discard(source) }
    }

    private fun requestRender() {
        mutableState.value = mutableState.value.copy(isRendering = true)
        renderRequests.trySend(++renderGeneration)
    }

    private fun refreshHistory() {
        mutableState.value = mutableState.value.copy(canUndo = history.canUndo, canRedo = history.canRedo)
    }

    private fun describe(failure: Throwable): String = when (failure) {
        is OutOfMemoryError -> "图片尺寸超过当前设备可用内存，请选择较小的图片后重试"
        is SecurityException -> "图片读取权限失效，请重新选择图片"
        else -> failure.message?.takeIf { it.isNotBlank() } ?: "图片处理失败，请重试"
    }

    override fun onCleared() {
        renderRequests.close()
        val source = mutableState.value.source
        executor.execute {
            try {
                if (engineDelegate.isInitialized()) engine.close()
                source?.let { photoRepository.discard(it) }
            } finally { renderDispatcher.close() }
        }
        super.onCleared()
    }

    // The cleanup task runs on the same GL thread and never creates an unused context.
    private data class RenderResult(val preview: Bitmap, val full: Bitmap?, val original: Bitmap?) {
        fun recycle() { preview.recycle(); full?.recycle(); original?.recycle() }
    }
}
