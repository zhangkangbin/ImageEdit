package com.kang.imageeditapp.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kang.imageeditapp.EditorActions
import com.kang.imageeditapp.EditorUiState
import com.kang.imageeditapp.model.ColorAdjustments
import com.kang.imageeditapp.model.ColorPreset
import com.kang.imageeditapp.model.CropRect
import com.kang.imageeditapp.model.CurveChannel
import com.kang.imageeditapp.model.CurvePoint
import com.kang.imageeditapp.model.EditRecipe
import com.kang.imageeditapp.model.EditorTool
import com.kang.imageeditapp.model.ExportFormat
import com.kang.imageeditapp.model.ExportOptions
import com.kang.imageeditapp.model.ExportResolution
import com.kang.imageeditapp.model.Geometry
import com.kang.imageeditapp.render.CurveInterpolator
import com.kang.imageeditapp.render.PreviewAnalysis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val Ink = Color(0xFF101315)
private val Panel = Color(0xFF1A1E21)
private val Raised = Color(0xFF252B2F)
private val Accent = Color(0xFF80E7CD)
private val Muted = Color(0xFF94A1A9)
private val Divider = Color(0xFF30383C)
private val ToolNames = mapOf(EditorTool.ADJUST to "调色", EditorTool.CURVES to "曲线", EditorTool.HSL to "HSL", EditorTool.PRESETS to "预设", EditorTool.TEXT to "文字", EditorTool.CROP to "裁剪")

/** Single screen editor; rendering, import and storage remain in the ViewModel. */
@Composable
fun ImageEditorApp(state: EditorUiState, actions: EditorActions) {
    var comparing by remember { mutableStateOf(false) }
    var exportDialog by remember { mutableStateOf(false) }
    var closeDialog by remember { mutableStateOf(false) }
    var deleteDraftDialog by remember { mutableStateOf(false) }
    var histogramExpanded by remember { mutableStateOf(false) }
    var clippingEnabled by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Ink, background = Ink, surface = Panel, onSurface = Color(0xFFEAF0F2), secondary = Accent)) {
        BackHandler(enabled = state.source != null) {
            if (!state.isExporting) {
                focus.clearFocus()
                if (state.activeTool != null) actions.cancelTool() else closeDialog = true
            }
        }
        Surface(color = Ink, modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                val wide = maxWidth > maxHeight
                Column(Modifier.fillMaxSize()) {
                    if (state.source == null) {
                        EmptyEditor(state, actions, { deleteDraftDialog = true })
                    } else {
                        EditorHeader(state, actions, comparing, { comparing = it }, {
                            focus.clearFocus()
                            exportDialog = true
                        }, {
                            focus.clearFocus()
                            closeDialog = true
                        })
                        AnalysisPanel(state.analysis, histogramExpanded, { histogramExpanded = !histogramExpanded }, clippingEnabled, { clippingEnabled = !clippingEnabled })
                        state.notice?.let { notice ->
                            Row(Modifier.fillMaxWidth().background(Raised).padding(start = 20.dp, end = 8.dp).testTag("operation-notice"), verticalAlignment = Alignment.CenterVertically) {
                                Text(notice, modifier = Modifier.weight(1f), color = Accent, fontSize = 12.sp)
                                TextButton(onClick = actions::dismissNotice, modifier = Modifier.testTag("dismiss-notice")) { Text("关闭", color = Muted, fontSize = 12.sp) }
                            }
                        }
                        if (wide && state.activeTool != null) {
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                PreviewStage(state, actions, comparing, clippingEnabled, Modifier.weight(1f).fillMaxHeight())
                                Box(Modifier.width(320.dp).fillMaxHeight().background(Panel)) {
                                    ToolPanel(state.activeTool, state, actions, { focus.clearFocus(); actions.applyTool() }, { focus.clearFocus(); actions.cancelTool() })
                                }
                            }
                        } else {
                            PreviewStage(state, actions, comparing, clippingEnabled, Modifier.weight(1f).fillMaxWidth())
                            state.activeTool?.let { tool ->
                                ToolPanel(tool, state, actions, { focus.clearFocus(); actions.applyTool() }, { focus.clearFocus(); actions.cancelTool() })
                            }
                        }
                        ToolNavigation(state.activeTool, !state.isLoading && !state.isExporting) {
                            focus.clearFocus()
                            actions.selectTool(it)
                        }
                    }
                }
            }
            if (state.isLoading || state.isExporting) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .64f)).pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
                }, contentAlignment = Alignment.Center) {
                    Column(Modifier.clip(RoundedCornerShape(22.dp)).background(Panel).padding(horizontal = 32.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        CircularProgressIndicator(color = Accent, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                        Text(if (state.isExporting) "正在保存图片…" else "正在打开图片…", fontSize = 15.sp)
                    }
                }
            }
        }
        if (exportDialog) {
            ExportDialog(state, { exportDialog = false }) { options ->
                exportDialog = false
                actions.export(options)
            }
        }
        if (closeDialog) {
            AlertDialog(onDismissRequest = { closeDialog = false }, title = { Text("结束本次编辑？") }, text = { Text("已应用的修改会自动保存为草稿，返回首页后可以继续编辑。") }, confirmButton = { TextButton(onClick = { closeDialog = false; actions.closePhoto() }) { Text("结束编辑") } }, dismissButton = { TextButton(onClick = { closeDialog = false }) { Text("继续编辑") } }, containerColor = Panel)
        }
        if (deleteDraftDialog) {
            AlertDialog(onDismissRequest = { deleteDraftDialog = false }, title = { Text("删除最近的草稿？") }, text = { Text("草稿中的原图与编辑进度将被删除，已导出的图片仍在相册中。") }, confirmButton = { TextButton(onClick = { deleteDraftDialog = false; actions.deleteDraft() }, modifier = Modifier.testTag("confirm-delete-draft")) { Text("删除草稿") } }, dismissButton = { TextButton(onClick = { deleteDraftDialog = false }) { Text("取消") } }, containerColor = Panel)
        }
        state.error?.let { error ->
            AlertDialog(onDismissRequest = actions::dismissError, title = { Text("暂时无法完成") }, text = { Text(error) }, confirmButton = { TextButton(onClick = actions::dismissError) { Text("知道了") } }, containerColor = Panel)
        }
        if (state.savedUri != null) {
            AlertDialog(onDismissRequest = actions::dismissSaved, title = { Text("图片已保存") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    state.savedSize?.let { Text("${it.width} × ${it.height} 像素", color = Accent, fontSize = 13.sp, modifier = Modifier.testTag("saved-dimensions")) }
                    Text("已保存到相册的 ImageEditApp 文件夹。现在可以分享你的作品。")
                }
            }, confirmButton = { TextButton(onClick = actions::shareSaved, modifier = Modifier.testTag("share-photo")) { Text("分享图片") } }, dismissButton = { TextButton(onClick = actions::dismissSaved) { Text("完成") } }, containerColor = Panel)
        }
    }
}

@Composable
private fun EmptyEditor(state: EditorUiState, actions: EditorActions, deleteDraft: () -> Unit) {
    val draft = state.draft
    var thumbnail by remember(draft?.thumbnailPath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(draft?.thumbnailPath) {
        thumbnail = withContext(Dispatchers.IO) { draft?.thumbnailPath?.let { BitmapFactory.decodeFile(it) } }
    }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF182422), Ink, Ink)))) {
        Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(Accent), contentAlignment = Alignment.Center) { Glyph("spark", Ink, Modifier.size(20.dp)) }
            Text("图片编辑", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 10.dp))
            Spacer(Modifier.weight(1f))
            Text("离线创作", color = Muted, fontSize = 12.sp)
        }
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Canvas(Modifier.widthIn(max = 290.dp).fillMaxWidth().aspectRatio(1.15f).padding(12.dp)) {
                val card = Size(size.width * .76f, size.height * .73f)
                val origin = Offset(size.width * .12f, size.height * .12f)
                drawRoundRect(Color(0xFF30463F), origin + Offset(14.dp.toPx(), 14.dp.toPx()), card, androidx.compose.ui.geometry.CornerRadius(22.dp.toPx()))
                drawRoundRect(Color(0xFFDAE5DF), origin, card, androidx.compose.ui.geometry.CornerRadius(22.dp.toPx()))
                val picture = Rect(origin.x + 12.dp.toPx(), origin.y + 12.dp.toPx(), origin.x + card.width - 12.dp.toPx(), origin.y + card.height - 29.dp.toPx())
                drawRoundRect(Brush.verticalGradient(listOf(Color(0xFFA1C9BA), Color(0xFF528C7B)), picture.top, picture.bottom), picture.topLeft, picture.size, androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()))
                drawCircle(Color(0xFFF1E2B0), picture.width * .09f, Offset(picture.left + picture.width * .72f, picture.top + picture.height * .26f))
                val hill = Path().apply {
                    moveTo(picture.left, picture.bottom - picture.height * .14f)
                    cubicTo(picture.left + picture.width * .3f, picture.top + picture.height * .14f, picture.left + picture.width * .58f, picture.bottom, picture.right, picture.top + picture.height * .4f)
                    lineTo(picture.right, picture.bottom)
                    lineTo(picture.left, picture.bottom)
                    close()
                }
                drawPath(hill, Color(0xFF315D50))
                val front = Path().apply {
                    moveTo(picture.left, picture.bottom - picture.height * .06f)
                    cubicTo(picture.left + picture.width * .18f, picture.bottom - picture.height * .22f, picture.left + picture.width * .54f, picture.bottom - picture.height * .05f, picture.right, picture.top + picture.height * .62f)
                    lineTo(picture.right, picture.bottom); lineTo(picture.left, picture.bottom); close()
                }
                drawPath(front, Color(0xFF1E4037))
                val badge = Offset(size.width * .84f, size.height * .77f)
                drawCircle(Accent, 27.dp.toPx(), badge)
                drawLine(Ink, badge - Offset(11.dp.toPx(), 0f), badge + Offset(11.dp.toPx(), 0f), 3.dp.toPx())
                drawLine(Ink, badge - Offset(0f, 11.dp.toPx()), badge + Offset(0f, 11.dp.toPx()), 3.dp.toPx())
            }
            Text("让照片，成为作品", fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = .3.sp)
            Text("细调光影与色彩，留下一句心情。\n从一张喜欢的照片开始。", color = Muted, textAlign = TextAlign.Center, lineHeight = 24.sp, fontSize = 15.sp, modifier = Modifier.padding(top = 14.dp, bottom = 28.dp))
            Button(onClick = actions::importPhoto, shape = RoundedCornerShape(16.dp), contentPadding = PaddingValues(horizontal = 30.dp, vertical = 16.dp), colors = ButtonDefaults.buttonColors(containerColor = Accent), modifier = Modifier.testTag("import-photo")) {
                Glyph("add", Ink, Modifier.size(19.dp))
                Text("选择一张照片", modifier = Modifier.padding(start = 9.dp), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            if (draft != null) {
                Row(Modifier.padding(top = 22.dp).widthIn(max = 380.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(54.dp).clip(RoundedCornerShape(9.dp)).background(Raised), contentAlignment = Alignment.Center) {
                        thumbnail?.let { Image(it.asImageBitmap(), "最近草稿", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: Glyph("crop", Muted, Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("最近的草稿", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(draft.savedAtMillis)), color = Muted, fontSize = 10.sp)
                        TextButton(onClick = actions::resumeDraft, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(30.dp).testTag("continue-draft")) { Text("继续编辑", color = Accent, fontSize = 12.sp) }
                    }
                    TextButton(onClick = deleteDraft, modifier = Modifier.testTag("delete-draft"), contentPadding = PaddingValues(6.dp)) { Text("删除", color = Muted, fontSize = 12.sp) }
                }
            }
            Row(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                listOf("专业调色", "文字水印", "自由裁剪").forEach { Text(it, color = Muted, fontSize = 12.sp) }
            }
        }
        Text("原尺寸导出 · 无需登录", color = Muted.copy(alpha = .7f), fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = TextAlign.Center)
    }
}

@Composable
private fun EditorHeader(state: EditorUiState, actions: EditorActions, comparing: Boolean, setComparing: (Boolean) -> Unit, export: () -> Unit, close: () -> Unit) {
    val focus = LocalFocusManager.current
    Row(Modifier.fillMaxWidth().height(65.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconAction("back", "结束编辑", onClick = close)
        Text("编辑 ▾", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(enabled = !state.isLoading && !state.isExporting) { focus.clearFocus(); actions.importPhoto() }.padding(start = 4.dp, top = 8.dp, bottom = 8.dp).testTag("replace-photo").semantics { contentDescription = "更换图片" }, maxLines = 1)
        IconAction("undo", "撤销", enabled = state.canUndo) { focus.clearFocus(); actions.undo() }
        IconAction("redo", "重做", enabled = state.canRedo) { focus.clearFocus(); actions.redo() }
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (comparing) Accent.copy(alpha = .14f) else Color.Transparent).testTag("compare").semantics { contentDescription = "按住查看原图" }.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                setComparing(true)
                try { waitForUpOrCancellation() } finally { setComparing(false) }
            }
        }, contentAlignment = Alignment.Center) { Glyph("compare", if (comparing) Accent else Color(0xFFE2EAED), Modifier.size(21.dp)) }
        Button(onClick = export, enabled = !state.isLoading && !state.isExporting && state.preview != null, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), modifier = Modifier.height(38.dp).padding(start = 6.dp).testTag("open-export")) { Text("导出", fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun AnalysisPanel(analysis: PreviewAnalysis?, expanded: Boolean, toggleExpanded: () -> Unit, clipping: Boolean, toggleClipping: () -> Unit) {
    var luminanceOnly by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(Panel)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (expanded) "直方图 ▴" else "直方图 ▾", color = if (expanded) Accent else Muted, fontSize = 12.sp, modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClick = toggleExpanded).padding(vertical = 7.dp).testTag("toggle-histogram"))
            ChoiceChip("溢出提示${if (clipping) " · 开" else ""}", clipping, toggleClipping, Modifier.testTag("toggle-clipping"))
        }
        if (expanded) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("RGB", !luminanceOnly, { luminanceOnly = false }, Modifier.testTag("histogram-rgb"))
                ChoiceChip("亮度", luminanceOnly, { luminanceOnly = true }, Modifier.testTag("histogram-luminance"))
                Spacer(Modifier.weight(1f))
                Text("全图 · 256 档", color = Muted, fontSize = 10.sp)
            }
            Canvas(Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 20.dp, vertical = 9.dp).testTag("histogram-plot").semantics { contentDescription = if (luminanceOnly) "亮度直方图，256 档" else "RGB 直方图，256 档" }) {
                drawRect(Ink)
                for (i in 1..3) drawLine(Divider, Offset(size.width * i / 4f, 0f), Offset(size.width * i / 4f, size.height), 1f)
                val channels = if (luminanceOnly) listOf(analysis?.luminance to Color(0xFFD5E4E0)) else listOf(analysis?.red to Color(0xFFFF8585), analysis?.green to Color(0xFF91E09C), analysis?.blue to Color(0xFF86B9FF))
                val peak = channels.maxOfOrNull { it.first?.maxOrNull() ?: 0 }?.coerceAtLeast(1) ?: 1
                channels.forEach { (bins, color) ->
                    if (bins != null && bins.size == 256) {
                        val path = Path()
                        bins.forEachIndexed { index, count ->
                            val x = size.width * index / 255f
                            val y = size.height - count.toFloat() / peak * (size.height - 2f)
                            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, color.copy(alpha = .86f), style = Stroke(1.3.dp.toPx()))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                fun percent(count: Long) = if (analysis == null || analysis.sampleCount == 0L) "—" else String.format(Locale.ROOT, "%.1f%%", count * 100.0 / analysis.sampleCount)
                Text("阴影 ${percent(analysis?.shadowCount ?: 0)}", color = Color(0xFF86B9FF), fontSize = 10.sp)
                Text("水印不参与统计", color = Muted, fontSize = 10.sp)
                Text("高光 ${percent(analysis?.highlightCount ?: 0)}", color = Color(0xFFFF8585), fontSize = 10.sp)
            }
        } else if (clipping) {
            Text("红色：高光溢出    蓝色：阴影溢出", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(start = 18.dp, bottom = 7.dp))
        }
    }
}

@Composable
private fun PreviewStage(state: EditorUiState, actions: EditorActions, comparing: Boolean, clipping: Boolean, modifier: Modifier = Modifier) {
    val cropMode = state.activeTool == EditorTool.CROP && !comparing
    val bitmap = when { comparing -> state.originalPreview; cropMode -> state.fullPreview; else -> state.preview }
    val source = state.source ?: return
    val output = Geometry.outputSize(source, state.recipe)
    Box(modifier.background(Color(0xFF0B0E10))) {
        if (bitmap != null) {
            PhotoCanvas(bitmap, state, actions, cropMode, !comparing && state.activeTool == EditorTool.TEXT, comparing, clipping && !comparing, Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 38.dp))
        } else {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = Accent, strokeWidth = 2.dp)
        }
        Row(Modifier.align(Alignment.TopCenter).padding(top = 9.dp).clip(RoundedCornerShape(20.dp)).background(Ink.copy(alpha = .8f)).padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(5.dp).background(if (comparing) Color.White else Accent, CircleShape))
            Text(if (comparing) "原图" else "${output.width} × ${output.height}", fontSize = 11.sp, color = Color(0xFFC6D0D5))
        }
        if (state.isRendering) {
            CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(14.dp).size(14.dp), color = Accent, strokeWidth = 1.5.dp)
        }
        Text(when { comparing -> "松开返回编辑效果"; cropMode -> "单指调整裁剪 · 双指缩放查看"; state.activeTool == EditorTool.TEXT && state.recipe.watermark.text.isNotBlank() -> "拖动文字 · 双指缩放查看"; else -> "双指缩放与平移 · 双击查看 100%" }, color = Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
    }
}

@Composable
private fun PhotoCanvas(bitmap: Bitmap, state: EditorUiState, actions: EditorActions, cropMode: Boolean, textMode: Boolean, comparing: Boolean, clipping: Boolean, modifier: Modifier) {
    var frameSize by remember { mutableStateOf(IntSize.Zero) }
    val latestRecipe by rememberUpdatedState(state.recipe)
    val latestActions by rememberUpdatedState(actions)
    val activeAspect by rememberUpdatedState(CropAspect.selected)
    val source = state.source ?: return
    val transformed = Geometry.transformedSize(source, state.recipe)
    val imageSize = if (cropMode) transformed else Geometry.outputSize(source, state.recipe)
    val recipe = state.recipe
    var viewport by remember(source.localPath, cropMode, recipe.quarterTurns, recipe.flipHorizontal, recipe.flipVertical, if (cropMode) null else recipe.crop) { mutableStateOf(PreviewViewport()) }
    val geometry = ViewportGeometry(frameSize.width, frameSize.height, imageSize.width, imageSize.height)
    val density = LocalDensity.current.density
    val frameGeometry = geometry.frame(viewport)
    val frame = Rect(frameGeometry.left, frameGeometry.top, frameGeometry.right, frameGeometry.bottom)
    val visibleBounds = geometry.visibleBounds(viewport)
    val detailWidth = min(frame.width, frameSize.width.toFloat()).roundToInt().coerceAtLeast(1)
    val detailHeight = min(frame.height, frameSize.height.toFloat()).roundToInt().coerceAtLeast(1)
    val wantsDetail = viewport.zoom > 1.01f || geometry.fitScale * viewport.zoom >= .99f
    LaunchedEffect(source.localPath, visibleBounds, detailWidth, detailHeight, cropMode, comparing, wantsDetail) {
        if (geometry.valid && wantsDetail) {
            delay(80)
            actions.requestDetail(visibleBounds, detailWidth, detailHeight, cropMode, comparing)
        } else actions.requestDetail(null)
    }
    val baseOverlay = if (cropMode) state.fullPreviewOverlay else state.previewOverlay
    val detail = state.detail?.takeIf { it.cropMode == cropMode && it.comparing == comparing }
    Box(modifier.clipToBounds().onSizeChanged { frameSize = it }.testTag("photo-canvas").semantics { contentDescription = "图片预览，可双指缩放、平移及双击查看原始像素" }.pointerInput(geometry, cropMode, textMode) {
        var lastTapTime = 0L
        var lastTapAt = Offset.Zero
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val startViewport = viewport
            val startRecipe = latestRecipe
            val f = geometry.frame(startViewport)
            val startFrame = Rect(f.left, f.top, f.right, f.bottom)
            val handle = if (cropMode) cropHandle(down.position, startFrame, startRecipe.crop, 26f * density) else CropHandle.NONE
            val touchingText = textMode && startRecipe.watermark.text.isNotBlank() && watermarkRect(startFrame, startRecipe).inflate(22f * density).contains(down.position)
            var moved = false
            var multiple = false
            var editing = false
            var finalTime = down.uptimeMillis
            try {
                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.count { it.pressed }
                    finalTime = event.changes.maxOf { it.uptimeMillis }
                    if (pressed >= 2) {
                        // A second finger promotes the entire gesture to view movement, not an edit.
                        if (editing) { latestActions.updateRecipe(startRecipe); latestActions.endGesture(); editing = false }
                        multiple = true; moved = true
                        val center = event.calculateCentroid(useCurrent = false)
                        val pan = event.calculatePan()
                        viewport = geometry.transform(viewport, event.calculateZoom(), pan.x, pan.y, center.x, center.y)
                        event.changes.forEach { it.consume() }
                    } else if (multiple) {
                        val pan = event.calculatePan()
                        viewport = geometry.constrain(viewport.copy(panX = viewport.panX + pan.x, panY = viewport.panY + pan.y))
                        event.changes.forEach { it.consume() }
                    } else {
                        val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                        val total = change.position - down.position
                        if (total.getDistance() > viewConfiguration.touchSlop || moved) {
                            moved = true
                            if (geometry.valid && (handle != CropHandle.NONE || touchingText)) {
                                if (!editing) { latestActions.beginGesture(); editing = true }
                                if (handle != CropHandle.NONE) {
                                    val ratio = aspectRatio(activeAspect, transformed.width, transformed.height)?.let { it * transformed.height / transformed.width }
                                    latestActions.updateRecipe(latestRecipe.copy(crop = moveCrop(startRecipe.crop, handle, total.x / startFrame.width, total.y / startFrame.height, ratio)))
                                } else {
                                    val mark = startRecipe.watermark
                                    latestActions.updateRecipe(latestRecipe.copy(watermark = mark.copy(x = (mark.x + total.x / startFrame.width).coerceIn(0f, 1f), y = (mark.y + total.y / startFrame.height).coerceIn(0f, 1f))))
                                }
                            } else {
                                viewport = geometry.constrain(startViewport.copy(panX = startViewport.panX + total.x, panY = startViewport.panY + total.y))
                            }
                            change.consume()
                        }
                    }
                } while (event.changes.any { it.pressed })
            } finally { if (editing) latestActions.endGesture() }
            if (!moved && finalTime - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                if (down.uptimeMillis - lastTapTime in viewConfiguration.doubleTapMinTimeMillis..viewConfiguration.doubleTapTimeoutMillis && (down.position - lastTapAt).getDistance() < 40f * density) {
                    viewport = if (abs(viewport.zoom - geometry.nativeZoom) < .03f) PreviewViewport() else geometry.transform(viewport, geometry.nativeZoom / viewport.zoom, 0f, 0f, down.position.x, down.position.y)
                    lastTapTime = 0L
                } else { lastTapTime = finalTime; lastTapAt = down.position }
            } else lastTapTime = 0L
        }
    }) {
        Canvas(Modifier.fillMaxSize()) {
            clipRect {
                fun drawBitmap(image: Bitmap, destination: Rect) {
                    if (destination.width > 0 && destination.height > 0) drawImage(image.asImageBitmap(), dstOffset = IntOffset(destination.left.roundToInt(), destination.top.roundToInt()), dstSize = IntSize(destination.width.roundToInt().coerceAtLeast(1), destination.height.roundToInt().coerceAtLeast(1)), filterQuality = FilterQuality.Medium)
                }
                fun drawBase() {
                    drawBitmap(bitmap, frame)
                    if (clipping && baseOverlay != null) drawBitmap(baseOverlay, frame)
                }
                if (detail == null) drawBase() else {
                    val region = cropPixelRect(frame, detail.bounds)
                    val detailFrame = Rect(region.left.roundToInt().toFloat(), region.top.roundToInt().toFloat(), region.left.roundToInt() + region.width.roundToInt().coerceAtLeast(1).toFloat(), region.top.roundToInt() + region.height.roundToInt().coerceAtLeast(1).toFloat())
                    // Exclude the detail region instead of layering it over the sampled bitmap:
                    // PNG transparency and clipping masks must be composited exactly once.
                    clipPath(Path().apply { addRect(detailFrame) }, ClipOp.Difference) { drawBase() }
                    val it = detail
                    drawBitmap(it.bitmap, detailFrame)
                    if (clipping && it.overlay != null) drawBitmap(it.overlay, detailFrame)
                }
            }
        }
        if (cropMode) {
            Canvas(Modifier.fillMaxSize().testTag("crop-canvas")) { clipRect { drawCrop(frame, state.recipe.crop, density) } }
        } else if (textMode && state.recipe.watermark.text.isNotBlank()) {
            Canvas(Modifier.fillMaxSize().testTag("watermark-canvas")) {
                clipRect {
                    val bound = watermarkRect(frame, state.recipe).inflate(6f * density)
                    drawRoundRect(Accent.copy(alpha = .8f), bound.topLeft, bound.size, androidx.compose.ui.geometry.CornerRadius(3f * density), style = Stroke(density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * density, 4f * density))))
                    drawCircle(Accent, 3f * density, Offset(bound.right, bound.bottom))
                }
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(11.dp)).background(Ink.copy(alpha = .86f)).padding(3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${(geometry.fitScale * viewport.zoom * 100f).roundToInt()}%", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 7.dp).testTag("zoom-percent"))
            TextButton(onClick = { viewport = PreviewViewport() }, contentPadding = PaddingValues(horizontal = 7.dp), modifier = Modifier.height(30.dp).testTag("zoom-fit")) { Text("适配", color = if (abs(viewport.zoom - 1f) < .01f) Accent else Muted, fontSize = 11.sp) }
            TextButton(onClick = { viewport = geometry.constrain(PreviewViewport(geometry.nativeZoom)) }, contentPadding = PaddingValues(horizontal = 7.dp), modifier = Modifier.height(30.dp).testTag("zoom-native")) { Text("100%", color = if (abs(viewport.zoom - geometry.nativeZoom) < .01f) Accent else Muted, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun ToolNavigation(active: EditorTool?, enabled: Boolean, select: (EditorTool) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        EditorTool.entries.forEach { tool ->
            val selected = active == tool
            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (selected) Accent.copy(alpha = .1f) else Color.Transparent).testTag("tool-${tool.name.lowercase(Locale.ROOT)}").clickable(enabled = enabled) { select(tool) }.padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Glyph(when (tool) { EditorTool.ADJUST -> "adjust"; EditorTool.CURVES -> "curve"; EditorTool.HSL -> "color"; EditorTool.PRESETS -> "preset"; EditorTool.TEXT -> "text"; EditorTool.CROP -> "crop" }, if (selected) Accent else Muted, Modifier.size(23.dp))
                Text(ToolNames.getValue(tool), fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) Accent else Muted)
            }
        }
    }
}

@Composable
private fun ToolPanel(tool: EditorTool, state: EditorUiState, actions: EditorActions, apply: () -> Unit, cancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Panel)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Divider.copy(alpha = .5f)))
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = cancel, contentPadding = PaddingValues(8.dp), modifier = Modifier.testTag("cancel-tool")) { Text("取消", color = Muted, fontSize = 13.sp) }
            Text(ToolNames.getValue(tool), Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = apply, contentPadding = PaddingValues(8.dp), modifier = Modifier.testTag("apply-tool")) { Text("应用", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
        Box(Modifier.fillMaxWidth().heightIn(max = if (tool == EditorTool.CURVES) 248.dp else 238.dp)) {
            when (tool) {
                EditorTool.ADJUST -> AdjustPanel(state.recipe, actions)
                EditorTool.CURVES -> CurvesPanel(state.recipe, actions)
                EditorTool.HSL -> HslPanel(state.recipe, actions)
                EditorTool.PRESETS -> PresetsPanel(state, actions)
                EditorTool.TEXT -> TextPanel(state.recipe, actions)
                EditorTool.CROP -> CropPanel(state, actions)
            }
        }
    }
}

@Composable
private fun AdjustPanel(recipe: EditRecipe, actions: EditorActions) {
    val a = recipe.adjustments
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 2.dp)) {
        ParameterSlider("曝光", a.exposure, -2f..2f, actions, { recipe.copy(adjustments = a.copy(exposure = it)) }, "${formatDecimal(a.exposure)} EV")
        ParameterSlider("亮度", a.brightness, -1f..1f, actions, { recipe.copy(adjustments = a.copy(brightness = it)) })
        ParameterSlider("对比度", a.contrast, -1f..1f, actions, { recipe.copy(adjustments = a.copy(contrast = it)) })
        ParameterSlider("饱和度", a.saturation, -1f..1f, actions, { recipe.copy(adjustments = a.copy(saturation = it)) })
        ParameterSlider("色温", a.temperature, -1f..1f, actions, { recipe.copy(adjustments = a.copy(temperature = it)) })
        ParameterSlider("色调", a.tint, -1f..1f, actions, { recipe.copy(adjustments = a.copy(tint = it)) })
        ParameterSlider("阴影", a.shadows, -1f..1f, actions, { recipe.copy(adjustments = a.copy(shadows = it)) })
        ParameterSlider("高光", a.highlights, -1f..1f, actions, { recipe.copy(adjustments = a.copy(highlights = it)) })
        TextButton(onClick = { actions.beginGesture(); actions.updateRecipe(recipe.copy(adjustments = ColorAdjustments())); actions.endGesture() }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("重置全部调色", color = Muted, fontSize = 12.sp) }
    }
}

@Composable
private fun ParameterSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, actions: EditorActions, recipeWithValue: (Float) -> EditRecipe, display: String = formatPercent(value), defaultValue: Float = 0f) {
    var gesturing by remember { mutableStateOf(false) }
    val latestUpdate by rememberUpdatedState(recipeWithValue)
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 12.sp, color = Color(0xFFD6DFE3), modifier = Modifier.weight(1f))
            Text(display, fontSize = 11.sp, color = if (abs(value - defaultValue) > .001f) Accent else Muted, modifier = Modifier.widthIn(min = 36.dp), textAlign = TextAlign.End)
            Box(Modifier.padding(start = 8.dp).size(24.dp).clip(CircleShape).clickable { focus.clearFocus(); actions.beginGesture(); actions.updateRecipe(latestUpdate(defaultValue)); actions.endGesture() }.semantics { contentDescription = "重置$label" }, contentAlignment = Alignment.Center) { Glyph("reset", Muted, Modifier.size(13.dp)) }
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = {
            if (!gesturing) { focus.clearFocus(); actions.beginGesture(); gesturing = true }
            actions.updateRecipe(latestUpdate(it))
        }, onValueChangeFinished = { if (gesturing) { actions.endGesture(); gesturing = false } }, valueRange = range, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Divider), modifier = Modifier.fillMaxWidth().height(29.dp).testTag("slider-$label").semantics { contentDescription = label })
    }
}

@Composable
private fun CurvesPanel(recipe: EditRecipe, actions: EditorActions) {
    var channel by remember { mutableStateOf(CurveChannel.RGB) }
    val points = recipe.curves.points(channel)
    val latestRecipe by rememberUpdatedState(recipe)
    val latestActions by rememberUpdatedState(actions)
    val hue = when (channel) { CurveChannel.RGB -> Accent; CurveChannel.RED -> Color(0xFFFF8585); CurveChannel.GREEN -> Color(0xFF91E09C); CurveChannel.BLUE -> Color(0xFF86B9FF) }
    Column(Modifier.padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CurveChannel.entries.forEach { option ->
                ChoiceChip(when (option) { CurveChannel.RGB -> "RGB"; CurveChannel.RED -> "R"; CurveChannel.GREEN -> "G"; CurveChannel.BLUE -> "B" }, channel == option, { channel = option }, Modifier.weight(1f).testTag("curve-${option.name}"))
            }
            IconAction("reset", "重置当前曲线", size = 32) { actions.beginGesture(); actions.updateRecipe(recipe.copy(curves = recipe.curves.withPoints(channel, listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))))); actions.endGesture() }
        }
        val plotPadding = 10f * LocalDensity.current.density
        val hitRadius = 25f * LocalDensity.current.density
        Canvas(Modifier.fillMaxWidth().height(158.dp).padding(vertical = 8.dp).clip(RoundedCornerShape(9.dp)).background(Ink).testTag("curves-canvas").pointerInput(channel) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val width = size.width.toFloat() - 2f * plotPadding
                val height = size.height.toFloat() - 2f * plotPadding
                if (width <= 0f || height <= 0f) return@awaitEachGesture
                fun pointToOffset(p: CurvePoint) = Offset(plotPadding + p.x * width, plotPadding + (1f - p.y) * height)
                var live = latestRecipe.curves.points(channel)
                val closest = live.indices.minByOrNull { (pointToOffset(live[it]) - down.position).getDistance() } ?: 0
                val existing = (pointToOffset(live[closest]) - down.position).getDistance() < hitRadius
                var index = closest
                val initial = down.position
                var moved = false
                latestActions.beginGesture()
                try {
                if (!existing) {
                    val x = ((down.position.x - plotPadding) / width).coerceIn(.015f, .985f)
                    val y = (1f - (down.position.y - plotPadding) / height).coerceIn(0f, 1f)
                    if (live.none { abs(it.x - x) < .015f } && live.size < 16) {
                        val added = CurvePoint(x, y)
                        live = (live + added).sortedBy { it.x }
                        index = live.indexOf(added)
                        latestActions.updateRecipe(latestRecipe.copy(curves = latestRecipe.curves.withPoints(channel, live)))
                    } else {
                        index = live.indices.minByOrNull { abs(live[it].x - x) } ?: 0
                    }
                }
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        if (!moved && existing && change.uptimeMillis - down.uptimeMillis > 500L && index in 1 until live.lastIndex) {
                            live = live.filterIndexed { i, _ -> i != index }
                            latestActions.updateRecipe(latestRecipe.copy(curves = latestRecipe.curves.withPoints(channel, live)))
                        }
                        change.consume()
                        break
                    }
                    if ((change.position - initial).getDistance() > 3f) moved = true
                    if (moved) {
                        val x = when (index) { 0 -> 0f; live.lastIndex -> 1f; else -> ((change.position.x - plotPadding) / width).coerceIn(live[index - 1].x + .01f, live[index + 1].x - .01f) }
                        val y = (1f - (change.position.y - plotPadding) / height).coerceIn(0f, 1f)
                        live = live.mapIndexed { i, point -> if (i == index) CurvePoint(x, y) else point }
                        latestActions.updateRecipe(latestRecipe.copy(curves = latestRecipe.curves.withPoints(channel, live)))
                        change.consume()
                    }
                }
                } finally { latestActions.endGesture() }
            }
        }.semantics { contentDescription = "${channel.name} 曲线，点击增加控制点，拖动调整，长按删除" }) {
            val w = size.width - 2f * plotPadding
            val h = size.height - 2f * plotPadding
            repeat(5) { i ->
                val fraction = i / 4f
                drawLine(Divider, Offset(plotPadding + w * fraction, plotPadding), Offset(plotPadding + w * fraction, plotPadding + h), 1f)
                drawLine(Divider, Offset(plotPadding, plotPadding + h * fraction), Offset(plotPadding + w, plotPadding + h * fraction), 1f)
            }
            drawLine(Muted.copy(alpha = .35f), Offset(plotPadding, plotPadding + h), Offset(plotPadding + w, plotPadding), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)))
            val line = Path()
            val interpolator = CurveInterpolator(points)
            for (index in 0..128) {
                val fraction = index / 128f
                val x = plotPadding + fraction * w
                val y = plotPadding + (1f - interpolator.evaluate(fraction)) * h
                if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
            }
            drawPath(line, hue, style = Stroke(2.5.dp.toPx()))
            points.forEach { p ->
                val at = Offset(plotPadding + p.x * w, plotPadding + (1f - p.y) * h)
                drawCircle(Ink, 5.dp.toPx(), at)
                drawCircle(hue, 5.dp.toPx(), at, style = Stroke(2.dp.toPx()))
            }
        }
        Text("点击添加控制点 · 拖动调整 · 长按删除", fontSize = 10.sp, color = Muted, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), textAlign = TextAlign.Center)
    }
}

@Composable
private fun HslPanel(recipe: EditRecipe, actions: EditorActions) {
    var selected by remember { mutableIntStateOf(0) }
    val names = listOf("红", "橙", "黄", "绿", "青", "蓝", "紫", "洋红")
    val colors = listOf(0xFFF36C72, 0xFFF4A45C, 0xFFE4D667, 0xFF7BC98C, 0xFF65C9C7, 0xFF6B9EDF, 0xFFA588DD, 0xFFD988C8)
    val hsl = recipe.hsl[selected]
    fun update(hue: Float = hsl.hue, saturation: Float = hsl.saturation, lightness: Float = hsl.lightness): EditRecipe = recipe.copy(hsl = recipe.hsl.mapIndexed { i, original -> if (i == selected) original.copy(hue = hue, saturation = saturation, lightness = lightness) else original })
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            names.forEachIndexed { index, name ->
                Column(Modifier.width(27.dp).testTag("hsl-$index").clickable { selected = index }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(25.dp).border(if (selected == index) 2.dp else 0.dp, if (selected == index) Color.White else Color.Transparent, CircleShape).padding(4.dp).background(Color(colors[index]), CircleShape))
                    Text(name, color = if (selected == index) Color.White else Muted, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
        ParameterSlider("色相", hsl.hue, -1f..1f, actions, { update(hue = it) }, "${(hsl.hue * 60f).roundToInt()}°")
        ParameterSlider("饱和度", hsl.saturation, -1f..1f, actions, { update(saturation = it) })
        ParameterSlider("明度", hsl.lightness, -1f..1f, actions, { update(lightness = it) })
    }
}

@Composable
private fun TextPanel(recipe: EditRecipe, actions: EditorActions) {
    var hasFocus by remember { mutableStateOf(false) }
    val latestRecipe by rememberUpdatedState(recipe)
    val focus = LocalFocusManager.current
    val colors = listOf(Color.White, Color.Black, Accent, Color(0xFFFFD782), Color(0xFFFF8E91), Color(0xFF8CB9FF), Color(0xFFD7A7F2))
    val colorNames = listOf("白色", "黑色", "薄荷绿", "暖黄色", "珊瑚红", "浅蓝色", "淡紫色")
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 2.dp)) {
        OutlinedTextField(value = recipe.watermark.text, onValueChange = { actions.updateRecipe(latestRecipe.copy(watermark = latestRecipe.watermark.copy(text = it.take(240)))) }, placeholder = { Text("写下一句心情…", color = Muted, fontSize = 13.sp) }, modifier = Modifier.fillMaxWidth().testTag("watermark-text").onFocusChanged { focus ->
            if (focus.isFocused && !hasFocus) actions.beginGesture()
            if (!focus.isFocused && hasFocus) actions.endGesture()
            hasFocus = focus.isFocused
        }, shape = RoundedCornerShape(12.dp), maxLines = 3, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), textStyle = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 14.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(15.dp)) {
            colors.forEachIndexed { index, color ->
                val selected = recipe.watermark.color == color.toArgb()
                Box(Modifier.size(27.dp).border(if (selected) 2.dp else 1.dp, if (selected) Accent else Divider, CircleShape).padding(if (selected) 4.dp else 2.dp).background(color, CircleShape).clickable { focus.clearFocus(); actions.beginGesture(); actions.updateRecipe(recipe.copy(watermark = recipe.watermark.copy(color = color.toArgb()))); actions.endGesture() }.semantics { contentDescription = "文字颜色：${colorNames[index]}" })
            }
        }
        ParameterSlider("字号", recipe.watermark.sizeFraction, .015f.. .16f, actions, { recipe.copy(watermark = recipe.watermark.copy(sizeFraction = it)) }, "${(recipe.watermark.sizeFraction * 1000f).roundToInt()}", .05f)
        Text("文字使用系统字体，可在图片上直接拖动。", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(bottom = 10.dp))
    }
}

/** UI aspect choice is ephemeral; the saved crop always contains the actual geometry. */
private object CropAspect { var selected by mutableStateOf("自由") }

@Composable
private fun CropPanel(state: EditorUiState, actions: EditorActions) {
    val recipe = state.recipe
    val source = state.source ?: return
    val full = Geometry.transformedSize(source, recipe)
    LaunchedEffect(source.uri) { CropAspect.selected = "自由" }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("自由", "原比例", "1:1", "4:3", "16:9").forEach { name ->
                ChoiceChip(name, CropAspect.selected == name, {
                    CropAspect.selected = name
                    val target = aspectRatio(name, full.width, full.height)
                    actions.beginGesture()
                    actions.updateRecipe(recipe.copy(crop = cropForAspect(recipe.crop, target?.let { it * full.height / full.width })))
                    actions.endGesture()
                }, Modifier.testTag("crop-$name"))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 15.dp, bottom = 15.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            CropAction("rotate", "旋转 90°") {
                CropAspect.selected = "自由"
                actions.beginGesture()
                val c = recipe.crop
                actions.updateRecipe(recipe.copy(quarterTurns = (recipe.quarterTurns + 1) % 4, flipHorizontal = recipe.flipVertical, flipVertical = recipe.flipHorizontal, crop = CropRect(1f - c.bottom, c.left, 1f - c.top, c.right)))
                actions.endGesture()
            }
            CropAction("flipH", "水平翻转") {
                actions.beginGesture(); val c = recipe.crop
                actions.updateRecipe(recipe.copy(flipHorizontal = !recipe.flipHorizontal, crop = CropRect(1f - c.right, c.top, 1f - c.left, c.bottom))); actions.endGesture()
            }
            CropAction("flipV", "垂直翻转") {
                actions.beginGesture(); val c = recipe.crop
                actions.updateRecipe(recipe.copy(flipVertical = !recipe.flipVertical, crop = CropRect(c.left, 1f - c.bottom, c.right, 1f - c.top))); actions.endGesture()
            }
            CropAction("reset", "重置") {
                CropAspect.selected = "自由"
                actions.beginGesture(); actions.updateRecipe(recipe.copy(crop = CropRect(), quarterTurns = 0, flipHorizontal = false, flipVertical = false)); actions.endGesture()
            }
        }
    }
}

@Composable
private fun PresetsPanel(state: EditorUiState, actions: EditorActions) {
    var nameDialog by remember { mutableStateOf(false) }
    var editingPreset by remember { mutableStateOf<ColorPreset?>(null) }
    var deletingPreset by remember { mutableStateOf<ColorPreset?>(null) }
    val enabled = !state.isPresetBusy
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = actions::copyColorGrade, enabled = enabled, modifier = Modifier.weight(1f).testTag("copy-grade")) { Text("复制调色", fontSize = 12.sp) }
            TextButton(onClick = actions::pasteColorGrade, enabled = enabled && state.hasCopiedGrade, modifier = Modifier.weight(1f).testTag("paste-grade")) { Text("粘贴调色", fontSize = 12.sp) }
            TextButton(onClick = { editingPreset = null; nameDialog = true }, enabled = enabled, modifier = Modifier.weight(1f).testTag("save-preset")) { Text("保存预设", fontSize = 12.sp) }
        }
        Text("保存调色、曲线与 HSL，在其他照片中继续使用。", color = Muted, fontSize = 10.sp)
        if (state.isPresetBusy) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(20.dp), color = Accent, strokeWidth = 2.dp) }
        }
        if (state.presets.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Glyph("preset", Muted, Modifier.size(28.dp))
                Text("还没有个人预设", color = Muted, fontSize = 12.sp, modifier = Modifier.testTag("empty-presets"))
                Text("调好一张照片后，点击「保存预设」。", color = Muted, fontSize = 10.sp)
            }
        }
        state.presets.forEach { preset ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Raised).padding(start = 12.dp, end = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).testTag("preset-${preset.id}").clickable(enabled = enabled) { actions.applyPreset(preset.id) }.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(preset.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("点击预览 · 应用后保留", color = Muted, fontSize = 10.sp)
                }
                TextButton(onClick = { editingPreset = preset; nameDialog = true }, enabled = enabled, contentPadding = PaddingValues(horizontal = 7.dp), modifier = Modifier.testTag("rename-preset-${preset.id}")) { Text("改名", color = Muted, fontSize = 11.sp) }
                TextButton(onClick = { deletingPreset = preset }, enabled = enabled, contentPadding = PaddingValues(horizontal = 7.dp), modifier = Modifier.testTag("delete-preset-${preset.id}")) { Text("删除", color = Muted, fontSize = 11.sp) }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    if (nameDialog) {
        val existing = editingPreset
        PresetNameDialog(existing?.name.orEmpty(), existing != null, { nameDialog = false }) { name ->
            nameDialog = false
            if (existing == null) actions.savePreset(name) else actions.renamePreset(existing.id, name)
        }
    }
    deletingPreset?.let { preset ->
        AlertDialog(onDismissRequest = { deletingPreset = null }, title = { Text("删除这个预设？") }, text = { Text("「${preset.name}」将从个人预设中删除，当前照片的调色仍会保留。") }, confirmButton = {
            TextButton(onClick = { deletingPreset = null; actions.deletePreset(preset.id) }, modifier = Modifier.testTag("confirm-delete-preset")) { Text("删除") }
        }, dismissButton = { TextButton(onClick = { deletingPreset = null }, modifier = Modifier.testTag("cancel-delete-preset")) { Text("取消", color = Muted) } }, containerColor = Panel)
    }
}

@Composable
private fun PresetNameDialog(initialName: String, renaming: Boolean, dismiss: () -> Unit, save: (String) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    val trimmed = name.trim()
    val valid = trimmed.isNotEmpty() && trimmed.codePointCount(0, trimmed.length) <= 24
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (renaming) "重命名预设" else "保存个人预设") }, text = {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("预设名称") }, singleLine = true, isError = name.isNotEmpty() && !valid, supportingText = { Text("1–24 个字", color = if (name.isNotEmpty() && !valid) MaterialTheme.colorScheme.error else Muted) }, modifier = Modifier.fillMaxWidth().testTag("preset-name"))
    }, confirmButton = { TextButton(onClick = { save(trimmed) }, enabled = valid, modifier = Modifier.testTag("confirm-preset-name")) { Text("保存") } }, dismissButton = { TextButton(onClick = dismiss, modifier = Modifier.testTag("cancel-preset-name")) { Text("取消", color = Muted) } }, containerColor = Panel)
}

@Composable
private fun ExportDialog(state: EditorUiState, dismiss: () -> Unit, export: (ExportOptions) -> Unit) {
    var options by remember { mutableStateOf(state.exportOptions) }
    var customEdge by remember { mutableStateOf(options.customLongEdge.toString()) }
    val customValue = customEdge.toIntOrNull()
    val valid = options.resolution != ExportResolution.CUSTOM_LONG || (customValue != null && customValue in 1..20000)
    val effectiveOptions = options.copy(customLongEdge = if (valid && customValue != null) customValue else options.customLongEdge)
    val originalSize = state.source?.let { Geometry.outputSize(it, state.recipe) }
    val size = originalSize?.let(effectiveOptions::resolveSize)
    AlertDialog(onDismissRequest = dismiss, title = { Text("导出作品", fontWeight = FontWeight.SemiBold) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (valid) "${size?.width ?: 0} × ${size?.height ?: 0} 像素" else "请输入有效的导出尺寸", color = if (valid) Accent else MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.testTag("export-dimensions"))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChoiceChip("JPEG", options.format == ExportFormat.JPEG, { options = options.copy(format = ExportFormat.JPEG) }, Modifier.weight(1f).testTag("format-JPEG"))
                ChoiceChip("PNG", options.format == ExportFormat.PNG, { options = options.copy(format = ExportFormat.PNG) }, Modifier.weight(1f).testTag("format-PNG"))
            }
            Text("输出尺寸", fontSize = 12.sp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("原尺寸", options.resolution == ExportResolution.ORIGINAL, { options = options.copy(resolution = ExportResolution.ORIGINAL) }, Modifier.weight(1f).testTag("resolution-original"))
                    ChoiceChip("长边 2048", options.resolution == ExportResolution.LONG_2048, { options = options.copy(resolution = ExportResolution.LONG_2048) }, Modifier.weight(1f).testTag("resolution-2048"))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("长边 1080", options.resolution == ExportResolution.LONG_1080, { options = options.copy(resolution = ExportResolution.LONG_1080) }, Modifier.weight(1f).testTag("resolution-1080"))
                    ChoiceChip("自定义长边", options.resolution == ExportResolution.CUSTOM_LONG, { options = options.copy(resolution = ExportResolution.CUSTOM_LONG) }, Modifier.weight(1f).testTag("resolution-custom"))
                }
            }
            if (options.resolution == ExportResolution.CUSTOM_LONG) {
                OutlinedTextField(value = customEdge, onValueChange = { customEdge = it.filter(Char::isDigit).take(6) }, label = { Text("长边像素") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = !valid, supportingText = { Text("1–20000 像素，保持比例，小图保留原尺寸。", color = Muted, fontSize = 10.sp) }, modifier = Modifier.fillMaxWidth().testTag("custom-export-edge"))
            } else {
                Text("保持裁剪比例，小图保留原尺寸。", color = Muted, fontSize = 10.sp)
            }
            if (options.format == ExportFormat.JPEG) {
                Column {
                    Row(Modifier.fillMaxWidth()) {
                        Text("JPEG 质量", fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(options.jpegQuality.toString(), color = Accent, fontSize = 12.sp, modifier = Modifier.testTag("jpeg-quality-value"))
                    }
                    Slider(value = options.jpegQuality.toFloat().coerceIn(50f, 100f), onValueChange = { options = options.copy(jpegQuality = it.roundToInt()) }, valueRange = 50f..100f, steps = 49, colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Divider), modifier = Modifier.testTag("jpeg-quality").semantics { contentDescription = "JPEG 质量" })
                }
            }
            Text(if (options.format == ExportFormat.JPEG) "JPEG 透明区域使用白色背景。" else "无损 PNG，保留原图的透明区域。", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
        }
    }, confirmButton = { TextButton(onClick = { export(effectiveOptions) }, enabled = valid, modifier = Modifier.testTag("save-photo")) { Text("保存到相册", fontWeight = FontWeight.SemiBold) } }, dismissButton = { TextButton(onClick = dismiss, modifier = Modifier.testTag("cancel-export")) { Text("取消", color = Muted) } }, containerColor = Panel)
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.height(32.dp).clip(RoundedCornerShape(9.dp)).background(if (selected) Accent.copy(alpha = .13f) else Raised).border(1.dp, if (selected) Accent.copy(alpha = .55f) else Color.Transparent, RoundedCornerShape(9.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (selected) Accent else Muted, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

@Composable
private fun CropAction(icon: String, label: String, action: () -> Unit) {
    Column(Modifier.widthIn(min = 55.dp).clip(RoundedCornerShape(10.dp)).testTag("crop-action-$icon").clickable(onClick = action).padding(vertical = 8.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Glyph(icon, Color(0xFFD7E1E5), Modifier.size(21.dp))
        Text(label, color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun IconAction(icon: String, label: String, enabled: Boolean = true, size: Int = 40, onClick: () -> Unit) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(11.dp)).testTag("action-$icon").clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Glyph(icon, if (enabled) Color(0xFFDEE7EB) else Muted.copy(alpha = .28f), Modifier.size(21.dp))
    }
}

/** Small vector glyph set avoids a large icon dependency and remains crisp at every density. */
@Composable
private fun Glyph(name: String, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension / 24f
        val stroke = 1.65f * s
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, p(x1, y1), p(x2, y2), stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        fun path(vararg points: Pair<Float, Float>) {
            val path = Path()
            points.forEachIndexed { i, point -> if (i == 0) path.moveTo(point.first * s, point.second * s) else path.lineTo(point.first * s, point.second * s) }
            drawPath(path, tint, style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
        when (name) {
            "add" -> { line(4f, 12f, 20f, 12f); line(12f, 4f, 12f, 20f) }
            "back" -> path(14f to 5f, 7f to 12f, 14f to 19f)
            "undo", "redo" -> {
                val flip = name == "redo"
                fun x(v: Float) = if (flip) 24f - v else v
                val arc = Path().apply { moveTo(x(4f) * s, 9f * s); cubicTo(x(20f) * s, 3f * s, x(23f) * s, 18f * s, x(13f) * s, 20f * s) }
                drawPath(arc, tint, style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                path(x(4f) to 4f, x(4f) to 9f, x(9f) to 10f)
            }
            "reset", "rotate" -> {
                drawArc(tint, -60f, 280f, false, p(4f, 4f), Size(16f * s, 16f * s), style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                path(17f to 2f, 17f to 7f, 22f to 7f)
            }
            "compare" -> { drawRoundRect(tint, p(3f, 4f), Size(18f * s, 16f * s), androidx.compose.ui.geometry.CornerRadius(2f * s), style = Stroke(stroke)); line(12f, 2f, 12f, 22f); drawRect(tint.copy(alpha = .45f), p(4f, 5f), Size(7f * s, 14f * s)) }
            "adjust" -> { listOf(5f, 12f, 19f).forEach { line(it, 3f, it, 21f) }; listOf(5f to 8f, 12f to 16f, 19f to 10f).forEach { drawCircle(Panel, 2.4f * s, p(it.first, it.second)); drawCircle(tint, 2.4f * s, p(it.first, it.second), style = Stroke(stroke)) } }
            "curve" -> { path(3f to 3f, 3f to 21f, 21f to 21f); val curve = Path().apply { moveTo(5f * s, 18f * s); cubicTo(16f * s, 19f * s, 8f * s, 5f * s, 20f * s, 4f * s) }; drawPath(curve, tint, style = Stroke(stroke)) }
            "color" -> { drawCircle(tint, 5.5f * s, p(9f, 9f), style = Stroke(stroke)); drawCircle(tint, 5.5f * s, p(15f, 9f), style = Stroke(stroke)); drawCircle(tint, 5.5f * s, p(12f, 15f), style = Stroke(stroke)) }
            "preset" -> { drawRoundRect(tint, p(3f, 3f), Size(18f * s, 18f * s), androidx.compose.ui.geometry.CornerRadius(3f * s), style = Stroke(stroke)); path(7f to 8f, 17f to 8f); path(7f to 12f, 14f to 12f); path(7f to 16f, 11f to 16f) }
            "text" -> { path(4f to 6f, 4f to 4f, 20f to 4f, 20f to 6f); line(12f, 4f, 12f, 20f); line(8f, 20f, 16f, 20f) }
            "crop" -> { path(7f to 3f, 7f to 17f, 21f to 17f); path(3f to 7f, 17f to 7f, 17f to 21f); line(10f, 14f, 14f, 10f) }
            "flipH" -> { line(12f, 2f, 12f, 22f); path(9f to 5f, 3f to 18f, 9f to 18f, 9f to 5f); path(15f to 5f, 21f to 18f, 15f to 18f, 15f to 5f) }
            "flipV" -> { line(2f, 12f, 22f, 12f); path(5f to 9f, 18f to 3f, 18f to 9f, 5f to 9f); path(5f to 15f, 18f to 21f, 18f to 15f, 5f to 15f) }
            "spark" -> path(12f to 2f, 14.5f to 9f, 22f to 12f, 14.5f to 14.5f, 12f to 22f, 9.5f to 14.5f, 2f to 12f, 9.5f to 9f, 12f to 2f)
        }
    }
}

private fun formatPercent(value: Float): String = (value * 100f).roundToInt().let { if (it > 0) "+$it" else "$it" }
private fun formatDecimal(value: Float): String = String.format(Locale.ROOT, if (value > .001f) "+%.1f" else "%.1f", value)
private fun watermarkRect(frame: Rect, recipe: EditRecipe): Rect {
    val mark = recipe.watermark
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = min(frame.width, frame.height) * mark.sizeFraction; typeface = android.graphics.Typeface.DEFAULT }
    val lines = mark.text.lines()
    val width = lines.maxOfOrNull { paint.measureText(it) } ?: 0f
    val height = paint.fontMetrics.descent - paint.fontMetrics.ascent + (lines.size - 1) * paint.fontSpacing
    val x = frame.left + mark.x * frame.width; val y = frame.top + mark.y * frame.height
    return Rect(x - width / 2f, y - height / 2f, x + width / 2f, y + height / 2f)
}

private enum class CropHandle { NONE, MOVE, TL, TR, BL, BR, LEFT, RIGHT, TOP, BOTTOM }
private fun cropPixelRect(frame: Rect, crop: CropRect) = Rect(frame.left + crop.left * frame.width, frame.top + crop.top * frame.height, frame.left + crop.right * frame.width, frame.top + crop.bottom * frame.height)
private fun cropHandle(at: Offset, frame: Rect, crop: CropRect, threshold: Float): CropHandle {
    val r = cropPixelRect(frame, crop)
    val corners = listOf(r.topLeft to CropHandle.TL, r.topRight to CropHandle.TR, r.bottomLeft to CropHandle.BL, r.bottomRight to CropHandle.BR)
    corners.firstOrNull { (it.first - at).getDistance() < threshold }?.let { return it.second }
    if (at.y in r.top..r.bottom && abs(at.x - r.left) < threshold) return CropHandle.LEFT
    if (at.y in r.top..r.bottom && abs(at.x - r.right) < threshold) return CropHandle.RIGHT
    if (at.x in r.left..r.right && abs(at.y - r.top) < threshold) return CropHandle.TOP
    if (at.x in r.left..r.right && abs(at.y - r.bottom) < threshold) return CropHandle.BOTTOM
    return if (r.contains(at)) CropHandle.MOVE else CropHandle.NONE
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCrop(frame: Rect, crop: CropRect, density: Float) {
    val r = cropPixelRect(frame, crop)
    val shade = Color.Black.copy(alpha = .6f)
    drawRect(shade, frame.topLeft, Size(frame.width, max(0f, r.top - frame.top)))
    drawRect(shade, Offset(frame.left, r.bottom), Size(frame.width, max(0f, frame.bottom - r.bottom)))
    drawRect(shade, Offset(frame.left, r.top), Size(max(0f, r.left - frame.left), r.height))
    drawRect(shade, Offset(r.right, r.top), Size(max(0f, frame.right - r.right), r.height))
    drawRect(Color.White.copy(alpha = .8f), r.topLeft, r.size, style = Stroke(density))
    for (i in 1..2) {
        drawLine(Color.White.copy(alpha = .35f), Offset(r.left + r.width * i / 3f, r.top), Offset(r.left + r.width * i / 3f, r.bottom), density * .6f)
        drawLine(Color.White.copy(alpha = .35f), Offset(r.left, r.top + r.height * i / 3f), Offset(r.right, r.top + r.height * i / 3f), density * .6f)
    }
    val arm = 15f * density; val thick = 3f * density
    listOf(Triple(r.topLeft, 1f, 1f), Triple(r.topRight, -1f, 1f), Triple(r.bottomLeft, 1f, -1f), Triple(r.bottomRight, -1f, -1f)).forEach { (at, x, y) ->
        drawLine(Accent, at, at + Offset(arm * x, 0f), thick)
        drawLine(Accent, at, at + Offset(0f, arm * y), thick)
    }
    drawLine(Accent, Offset(r.center.x - 7f * density, r.top), Offset(r.center.x + 7f * density, r.top), thick)
    drawLine(Accent, Offset(r.center.x - 7f * density, r.bottom), Offset(r.center.x + 7f * density, r.bottom), thick)
    drawLine(Accent, Offset(r.left, r.center.y - 7f * density), Offset(r.left, r.center.y + 7f * density), thick)
    drawLine(Accent, Offset(r.right, r.center.y - 7f * density), Offset(r.right, r.center.y + 7f * density), thick)
}

private fun aspectRatio(name: String, width: Int, height: Int): Float? = when (name) { "原比例" -> width.toFloat() / height; "1:1" -> 1f; "4:3" -> 4f / 3f; "16:9" -> 16f / 9f; else -> null }

private fun cropForAspect(crop: CropRect, normalizedRatio: Float?): CropRect {
    if (normalizedRatio == null) return crop
    val centerX = (crop.left + crop.right) / 2f; val centerY = (crop.top + crop.bottom) / 2f
    val w = min(crop.width, crop.height * normalizedRatio)
    val h = w / normalizedRatio
    return CropRect(centerX - w / 2f, centerY - h / 2f, centerX + w / 2f, centerY + h / 2f)
}

/** Drag calculations are relative to the original gesture bounds to prevent jitter. */
private fun moveCrop(c: CropRect, handle: CropHandle, dx: Float, dy: Float, ratio: Float?): CropRect {
    val minimum = .04f
    if (handle == CropHandle.MOVE) {
        val x = dx.coerceIn(-c.left, 1f - c.right); val y = dy.coerceIn(-c.top, 1f - c.bottom)
        return CropRect(c.left + x, c.top + y, c.right + x, c.bottom + y)
    }
    var l = c.left; var t = c.top; var r = c.right; var b = c.bottom
    val left = handle in listOf(CropHandle.TL, CropHandle.BL, CropHandle.LEFT)
    val right = handle in listOf(CropHandle.TR, CropHandle.BR, CropHandle.RIGHT)
    val top = handle in listOf(CropHandle.TL, CropHandle.TR, CropHandle.TOP)
    val bottom = handle in listOf(CropHandle.BL, CropHandle.BR, CropHandle.BOTTOM)
    if (ratio == null) {
        val minW = min(minimum, c.width)
        val minH = min(minimum, c.height)
        if (left) l = (c.left + dx).coerceIn(0f, c.right - minW)
        if (right) r = (c.right + dx).coerceIn(c.left + minW, 1f)
        if (top) t = (c.top + dy).coerceIn(0f, c.bottom - minH)
        if (bottom) b = (c.bottom + dy).coerceIn(c.top + minH, 1f)
    } else if ((left || right) && (top || bottom)) {
        val anchorX = if (left) c.right else c.left
        val anchorY = if (top) c.bottom else c.top
        val desiredW = if (left) c.width - dx else c.width + dx
        val desiredH = if (top) c.height - dy else c.height + dy
        val maxW = if (left) anchorX else 1f - anchorX
        val maxH = if (top) anchorY else 1f - anchorY
        val minW = min(maxW, min(maxH * ratio, max(minimum, minimum * ratio)))
        val targetW = if (abs(dx) > abs(dy) * ratio) desiredW else desiredH * ratio
        val width = targetW.coerceIn(minW, min(maxW, maxH * ratio))
        val height = width / ratio
        l = if (left) anchorX - width else anchorX; r = if (left) anchorX else anchorX + width
        t = if (top) anchorY - height else anchorY; b = if (top) anchorY else anchorY + height
    } else if (left || right) {
        val centerY = (c.top + c.bottom) / 2f
        val maxH = 2f * min(centerY, 1f - centerY)
        val maxW = min(if (left) c.right else 1f - c.left, maxH * ratio)
        val minW = min(maxW, max(minimum, minimum * ratio))
        val width = (if (left) c.width - dx else c.width + dx).coerceIn(minW, maxW)
        if (left) l = c.right - width else r = c.left + width
        t = centerY - width / ratio / 2f; b = centerY + width / ratio / 2f
    } else if (top || bottom) {
        val centerX = (c.left + c.right) / 2f
        val maxW = 2f * min(centerX, 1f - centerX)
        val maxH = min(if (top) c.bottom else 1f - c.top, maxW / ratio)
        val minH = min(maxH, max(minimum, minimum / ratio))
        val height = (if (top) c.height - dy else c.height + dy).coerceIn(minH, maxH)
        if (top) t = c.bottom - height else b = c.top + height
        l = centerX - height * ratio / 2f; r = centerX + height * ratio / 2f
    }
    return CropRect(l.coerceIn(0f, 1f), t.coerceIn(0f, 1f), r.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
}
