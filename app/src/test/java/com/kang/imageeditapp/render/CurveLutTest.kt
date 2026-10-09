package com.kang.imageeditapp.render

import com.kang.imageeditapp.model.*
import org.junit.Assert.*
import org.junit.Test

class CurveLutTest {
    @Test fun defaultCurveIsPixelExactIdentity() {
        val lut = CurveLut.create(CurveSet())
        for (i in 0..255) {
            repeat(3) { assertEquals(i, lut.get().toInt() and 255) }
            assertEquals(255, lut.get().toInt() and 255)
        }
    }

    @Test fun curveMovesOnlySelectedChannelAndCanReset() {
        val adjusted = CurveSet().withPoints(CurveChannel.RED, listOf(CurvePoint(0f, 0f), CurvePoint(.5f, .8f), CurvePoint(1f, 1f)))
        val lut = CurveLut.create(adjusted)
        assertTrue((lut.get(128 * 4).toInt() and 255) > 190)
        assertEquals(128, lut.get(128 * 4 + 1).toInt() and 255)
        assertEquals(128, lut.get(128 * 4 + 2).toInt() and 255)
        assertEquals(CurveSet(), adjusted.withPoints(CurveChannel.RED, CurveSet().red))
    }

    @Test fun shapePreservingCurveHitsPointsAndStaysBounded() {
        val points = listOf(CurvePoint(0f, 0f), CurvePoint(.2f, .1f), CurvePoint(.6f, .8f), CurvePoint(1f, 1f))
        val curve = CurveInterpolator(points)
        points.forEach { assertEquals(it.y, curve.evaluate(it.x), .00001f) }
        var previous = -1f
        for (i in 0..1000) {
            val value = curve.evaluate(i / 1000f)
            assertTrue(value in 0f..1f)
            assertTrue(value >= previous)
            previous = value
        }
    }
}
