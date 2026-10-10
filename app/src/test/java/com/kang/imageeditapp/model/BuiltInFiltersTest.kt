package com.kang.imageeditapp.model

import org.junit.Assert.*
import org.junit.Test

class BuiltInFiltersTest {
    @Test fun catalogHasUniqueValidGradesAndCannotBeMutated() {
        val filters = BuiltInFilters.entries
        assertEquals(9, filters.size)
        assertEquals(filters.size, filters.map { it.id }.distinct().size)
        assertEquals(filters.size, filters.map { it.name }.distinct().size)
        assertEquals(filters.size, filters.map { it.grade }.distinct().size)
        assertNull(BuiltInFilters.find("missing"))
        filters.forEach { filter ->
            assertTrue(filter.name.isNotBlank() && filter.description.isNotBlank())
            val a = filter.grade.adjustments
            assertTrue(a.exposure.isFinite() && a.exposure in -2f..2f)
            assertTrue(listOf(a.brightness, a.contrast, a.saturation, a.temperature, a.tint, a.shadows, a.highlights).all { it.isFinite() && it in -1f..1f })
            assertEquals(HslBand.entries.size, filter.grade.hsl.size)
            assertTrue(filter.grade.hsl.all { band -> listOf(band.hue, band.saturation, band.lightness).all { it.isFinite() && it in -1f..1f } })
            CurveChannel.entries.forEach { channel ->
                val points = filter.grade.curves.points(channel)
                assertEquals(0f, points.first().x)
                assertEquals(1f, points.last().x)
                assertTrue(points.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f })
                assertTrue(points.zipWithNext().all { (left, right) -> left.x < right.x })
                assertThrows(UnsupportedOperationException::class.java) { (points as MutableList<CurvePoint>).clear() }
            }
            assertThrows(UnsupportedOperationException::class.java) { (filter.grade.hsl as MutableList<HslAdjustment>).clear() }
        }
        assertThrows(UnsupportedOperationException::class.java) { (filters as MutableList<BuiltInFilter>).clear() }
    }

    @Test fun switchingFiltersReplacesTheWholeGradeAndKeepsGeometryAndText() {
        val receiving = EditRecipe(
            crop = CropRect(.1f, .2f, .9f, .8f), quarterTurns = 3, flipHorizontal = true, flipVertical = true,
            watermark = Watermark("照片文字", x = .3f, y = .6f),
        )
        val film = BuiltInFilters.find("film")!!.grade.applyTo(receiving)
        val vivid = BuiltInFilters.find("vivid")!!
        val switched = vivid.grade.applyTo(film)
        assertEquals(vivid.grade.applyTo(receiving), switched)
        assertEquals(vivid.grade, ColorGrade.capture(switched))
        assertEquals(receiving, BuiltInFilters.find("original")!!.grade.applyTo(switched))
    }
}
