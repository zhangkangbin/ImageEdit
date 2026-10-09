package com.kang.imageeditapp

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kang.imageeditapp.model.*
import com.kang.imageeditapp.ui.ImageEditorApp

class MainActivity : ComponentActivity() {
    private lateinit var model: EditorViewModel
    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { model.importPhoto(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        model = ViewModelProvider(this)[EditorViewModel::class.java]
        val actions = object : EditorActions {
            override fun importPhoto() { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            override fun selectTool(tool: EditorTool) = model.selectTool(tool)
            override fun updateRecipe(recipe: EditRecipe) = model.updateRecipe(recipe)
            override fun beginGesture() = model.beginGesture()
            override fun endGesture() = model.endGesture()
            override fun applyTool() = model.applyTool()
            override fun cancelTool() = model.cancelTool()
            override fun undo() = model.undo()
            override fun redo() = model.redo()
            override fun resetAll() = model.resetAll()
            override fun export(format: ExportFormat) = model.export(format)
            override fun dismissError() = model.dismissError()
            override fun dismissSaved() = model.dismissSaved()
            override fun closePhoto() = model.closePhoto()
            override fun shareSaved() {
                val state = model.state.value
                val uri = state.savedUri ?: return
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = state.exportFormat.mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("编辑后的图片", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "分享图片"))
            }
        }
        setContent {
            val state = model.state.collectAsStateWithLifecycle().value
            ImageEditorApp(state, actions)
        }
    }
}
