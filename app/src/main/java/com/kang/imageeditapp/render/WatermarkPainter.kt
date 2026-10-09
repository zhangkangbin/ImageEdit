package com.kang.imageeditapp.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.kang.imageeditapp.model.CropRect
import com.kang.imageeditapp.model.Watermark
import kotlin.math.min

/** The same output-space text layout is used for both preview and full resolution export. */
internal object WatermarkPainter {
    fun draw(bitmap: Bitmap, watermark: Watermark, region: CropRect = CropRect()) {
        if (watermark.text.isBlank()) return
        // The detail viewport is a window onto the final canvas, not a new watermark canvas.
        val canvasWidth = bitmap.width / region.width
        val canvasHeight = bitmap.height / region.height
        val textSize = min(canvasWidth, canvasHeight) * watermark.sizeFraction.coerceIn(.005f, .5f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = watermark.color
            this.textSize = textSize
            typeface = Typeface.DEFAULT
            textAlign = Paint.Align.CENTER
            setShadowLayer(textSize * .045f, 0f, textSize * .025f, Color.argb(150, 0, 0, 0))
        }
        val canvas = Canvas(bitmap)
        val lines = watermark.text.split('\n')
        val metrics = paint.fontMetrics
        val lineSpacing = paint.fontSpacing
        val blockHeight = metrics.descent - metrics.ascent + (lines.size - 1) * lineSpacing
        var baseline = canvasHeight * (watermark.y - region.top) - blockHeight / 2f - metrics.ascent
        val centerX = canvasWidth * (watermark.x - region.left)
        for (line in lines) {
            canvas.drawText(line, centerX, baseline, paint)
            baseline += lineSpacing
        }
    }
}
