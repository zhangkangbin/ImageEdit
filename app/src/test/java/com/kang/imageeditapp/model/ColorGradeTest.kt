package com.kang.imageeditapp.model

import org.junit.Assert.*
import org.junit.Test

class ColorGradeTest {
    @Test fun appliesAllColorParametersWhilePreservingReceivingGeometryAndText() {
        val donor = EditRecipe(
            adjustments = ColorAdjustments(1.2f, -.3f, .2f, -.4f, .7f, -.6f, .8f, -.9f),
            curves = CurveSet(rgb = listOf(CurvePoint(0f, .1f), CurvePoint(.4f, .7f), CurvePoint(1f, .9f))),
            hsl = List(8) { HslAdjustment((it - 4) / 4f, it / 8f, -it / 8f) },
            crop = CropRect(.1f, .2f, .8f, .9f), quarterTurns = 1,
            watermark = Watermark("供图水印"),
        )
        val receiver = EditRecipe(
            crop = CropRect(.2f, .1f, .9f, .8f), quarterTurns = 3,
            flipHorizontal = true, flipVertical = true,
            watermark = Watermark("接收图片的中文水印", 0x80AABBCC.toInt(), .1f, .2f, .3f),
        )
        val result = ColorGrade.capture(donor).applyTo(receiver)
        assertEquals(donor.adjustments, result.adjustments)
        assertEquals(donor.curves, result.curves)
        assertEquals(donor.hsl, result.hsl)
        assertEquals(receiver.crop, result.crop)
        assertEquals(receiver.quarterTurns, result.quarterTurns)
        assertEquals(receiver.flipHorizontal, result.flipHorizontal)
        assertEquals(receiver.flipVertical, result.flipVertical)
        assertEquals(receiver.watermark, result.watermark)
    }

    @Test fun captureDetachesListsAndApplyingDetachesConstructorInputs() {
        val points = mutableListOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
        val hsl = MutableList(8) { HslAdjustment() }
        val recipe = EditRecipe(curves = CurveSet(rgb = points), hsl = hsl)
        val captured = ColorGrade.capture(recipe)
        val applied = ColorGrade(curves = recipe.curves, hsl = hsl).applyTo(EditRecipe())
        points[0] = CurvePoint(0f, .5f)
        hsl[0] = HslAdjustment(hue = .5f)
        assertEquals(CurvePoint(0f, 0f), captured.curves.rgb.first())
        assertEquals(HslAdjustment(), captured.hsl.first())
        assertEquals(CurvePoint(0f, 0f), applied.curves.rgb.first())
        assertEquals(HslAdjustment(), applied.hsl.first())
    }

    @Test fun publishedCapturedListsCannotBeMutated() {
        val captured = ColorGrade.capture(EditRecipe())
        try {
            (captured.curves.rgb as MutableList<CurvePoint>).clear()
            fail("Captured curve points must be immutable")
        } catch (_: UnsupportedOperationException) { }
        try {
            (captured.hsl as MutableList<HslAdjustment>).clear()
            fail("Captured HSL values must be immutable")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test fun applyingGradeCanBeRecordedAsOneUndoableEdit() {
        val before = EditRecipe(crop = CropRect(.2f, .1f, .9f, .8f), watermark = Watermark("照片"))
        val after = ColorGrade(
            adjustments = ColorAdjustments(exposure = .8f, temperature = -.4f),
            hsl = List(8) { HslAdjustment(hue = .3f) },
        ).applyTo(before)
        val history = RecipeHistory()
        history.record(before, after)
        assertEquals(before, history.undo(after))
        assertFalse(history.canUndo)
        assertEquals(after, history.redo(before))
    }

    @Test fun applyingIdenticalGradeDoesNotCreateUndoHistory() {
        val recipe = EditRecipe(adjustments = ColorAdjustments(exposure = 1f))
        val after = ColorGrade.capture(recipe).applyTo(recipe)
        val history = RecipeHistory()
        history.record(recipe, after)
        assertEquals(recipe, after)
        assertFalse(history.canUndo)
    }
}
