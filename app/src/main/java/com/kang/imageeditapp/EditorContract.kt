package com.kang.imageeditapp

import android.graphics.Bitmap
import android.net.Uri
import com.kang.imageeditapp.model.*

data class EditorUiState(
    val source: PhotoSource? = null,
    val recipe: EditRecipe = EditRecipe(),
    val preview: Bitmap? = null,
    val fullPreview: Bitmap? = null,
    val originalPreview: Bitmap? = null,
    val isLoading: Boolean = false,
    val isRendering: Boolean = false,
    val isExporting: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val error: String? = null,
    val savedUri: Uri? = null,
    val activeTool: EditorTool? = null,
    val exportFormat: ExportFormat = ExportFormat.JPEG,
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
    fun dismissError()
    fun dismissSaved()
    fun shareSaved()
    fun closePhoto()
}
