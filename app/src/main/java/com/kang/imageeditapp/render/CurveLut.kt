package com.kang.imageeditapp.render

import com.kang.imageeditapp.model.CurvePoint
import com.kang.imageeditapp.model.CurveSet
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** A shared 8-bit, linearly filtered lookup table for the preview and export shader. */
internal object CurveLut {
    const val SIZE = 256

    fun create(curves: CurveSet): ByteBuffer {
        val master = CurveInterpolator(curves.rgb)
        val red = CurveInterpolator(curves.red)
        val green = CurveInterpolator(curves.green)
        val blue = CurveInterpolator(curves.blue)
        return ByteBuffer.allocateDirect(SIZE * 4).apply {
            for (index in 0 until SIZE) {
                val value = master.evaluate(index.toFloat() / (SIZE - 1))
                put((red.evaluate(value) * 255f).roundToInt().toByte())
                put((green.evaluate(value) * 255f).roundToInt().toByte())
                put((blue.evaluate(value) * 255f).roundToInt().toByte())
                put(255.toByte())
            }
            rewind()
        }
    }
}

/** Shape-preserving cubic interpolation: moving a point never causes ringing between points. */
internal class CurveInterpolator(input: List<CurvePoint>) {
    private val points: List<CurvePoint> = buildList {
        val normalized = input.filter { it.x.isFinite() && it.y.isFinite() }
            .map { CurvePoint(it.x.coerceIn(0f, 1f), it.y.coerceIn(0f, 1f)) }
            .associateBy { it.x }.values.sortedBy { it.x }
        if (normalized.firstOrNull()?.x != 0f) add(CurvePoint(0f, 0f))
        addAll(normalized)
        if (normalized.lastOrNull()?.x != 1f) add(CurvePoint(1f, 1f))
    }
    private val intervals = FloatArray(points.size - 1) { points[it + 1].x - points[it].x }
    private val secants = FloatArray(points.size - 1) {
        (points[it + 1].y - points[it].y) / intervals[it]
    }
    private val tangents = FloatArray(points.size).apply {
        this[0] = secants.first()
        this[lastIndex] = secants.last()
        for (i in 1 until lastIndex) {
            val before = secants[i - 1]
            val after = secants[i]
            this[i] = if (before * after <= 0f) 0f else {
                val weight1 = 2f * intervals[i] + intervals[i - 1]
                val weight2 = intervals[i] + 2f * intervals[i - 1]
                (weight1 + weight2) / (weight1 / before + weight2 / after)
            }
        }
    }

    fun evaluate(input: Float): Float {
        val value = input.coerceIn(0f, 1f)
        val index = (points.indexOfFirst { it.x > value } - 1)
            .let { if (it < 0) points.size - 2 else it }
        val left = points[index]
        val right = points[index + 1]
        val h = intervals[index]
        val t = ((value - left.x) / h).coerceIn(0f, 1f)
        val t2 = t * t
        val t3 = t2 * t
        return ((2f * t3 - 3f * t2 + 1f) * left.y +
            (t3 - 2f * t2 + t) * h * tangents[index] +
            (-2f * t3 + 3f * t2) * right.y +
            (t3 - t2) * h * tangents[index + 1]).coerceIn(0f, 1f)
    }
}
