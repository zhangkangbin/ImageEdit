package com.kang.imageeditapp.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.kang.imageeditapp.model.Watermark
import kotlin.math.min

/** The same output-space text layout is used for both preview and full resolution export. */
internal object WatermarkPainter {
    fun draw(bitmap: Bitmap, watermark: Watermark) {
        if (watermark.text.isBlank()) return
        val textSize = min(bitmap.width, bitmap.height) * watermark.sizeFraction.coerceIn(.005f, .5f)
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
        var baseline = bitmap.height * watermark.y - blockHeight / 2f - metrics.ascent
        val centerX = bitmap.width * watermark.x
        for (line in lines) {
            canvas.drawText(line, centerX, baseline, paint)
            baseline += lineSpacing
        }
    }
}
