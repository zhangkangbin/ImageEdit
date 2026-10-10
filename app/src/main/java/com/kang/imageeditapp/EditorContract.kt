package com.kang.imageeditapp

import android.graphics.Bitmap
import android.net.Uri
import com.kang.imageeditapp.model.*
import com.kang.imageeditapp.data.Draft
import com.kang.imageeditapp.render.PreviewAnalysis

/** Pixels for a visible region, positioned in the displayed image's normalized canvas. */
data class DetailPreview(
    val bitmap: Bitmap,
    val bounds: CropRect,
    val overlay: Bitmap?,
    val cropMode: Boolean,
    val comparing: Boolean,
)

data class EditorUiState(
    val source: PhotoSource? = null,
    val recipe: EditRecipe = EditRecipe(),
    val preview: Bitmap? = null,
    val fullPreview: Bitmap? = null,
    val originalPreview: Bitmap? = null,
    val draft: Draft? = null,
    val analysis: PreviewAnalysis? = null,
    val previewOverlay: Bitmap? = null,
    val fullPreviewOverlay: Bitmap? = null,
    val detail: DetailPreview? = null,
    val isLoading: Boolean = false,
    val isRendering: Boolean = false,
    val isExporting: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val error: String? = null,
    val savedUri: Uri? = null,
    val activeTool: EditorTool? = null,
    val exportFormat: ExportFormat = ExportFormat.JPEG,
    val exportOptions: ExportOptions = ExportOptions(),
    val savedSize: ImageSize? = null,
    val presets: List<ColorPreset> = emptyList(),
    val hasCopiedGrade: Boolean = false,
    val isPresetBusy: Boolean = false,
    val notice: String? = null,
)

interface EditorActions {
    fun importPhoto()
    fun selectTool(tool: EditorTool)
    fun updateRecipe(recipe: EditRecipe)
    fun beginGesture()
    fun endGesture()
    fun applyTool()
    fun cancelTool()
    fun undo()
    fun redo()
    fun resetAll()
    fun export(format: ExportFormat)
    fun export(options: ExportOptions)
    fun savePreset(name: String)
    fun renamePreset(id: String, name: String)
    fun deletePreset(id: String)
    fun applyPreset(id: String)
    fun applyBuiltInFilter(id: String)
    fun copyColorGrade()
    fun pasteColorGrade()
    fun dismissNotice()
    fun dismissError()
    fun dismissSaved()
    fun shareSaved()
    fun closePhoto()
    fun resumeDraft()
    fun deleteDraft()
    fun requestDetail(bounds: CropRect?, width: Int = 0, height: Int = 0, cropMode: Boolean = false, comparing: Boolean = false)
}
