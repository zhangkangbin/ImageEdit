package com.kang.imageeditapp.model

import org.junit.Assert.*
import org.junit.Test

class RecipeHistoryTest {
    private fun recipe(value: Float) = EditRecipe(adjustments = ColorAdjustments(brightness = value))

    @Test fun undoAndRedoRestoreEntireRecipe() {
        val before = recipe(0f)
        val after = recipe(.5f).copy(crop = CropRect(.2f, .1f, .9f, .8f), watermark = Watermark(text = "中文水印"))
        val history = RecipeHistory()
        history.record(before, after)
        assertEquals(before, history.undo(after))
        assertEquals(after, history.redo(before))
        assertFalse(history.canRedo)
    }

    @Test fun newEditDiscardsRedoBranch() {
        val history = RecipeHistory()
        history.record(recipe(0f), recipe(.1f))
        history.undo(recipe(.1f))
        history.record(recipe(0f), recipe(.2f))
        assertFalse(history.canRedo)
        assertEquals(recipe(0f), history.undo(recipe(.2f)))
    }

    @Test fun remembersOnlyThirtyEdits() {
        val history = RecipeHistory()
        for (i in 0 until 35) history.record(recipe(i.toFloat()), recipe(i + 1f))
        var current = recipe(35f)
        var count = 0
        while (history.canUndo) { current = history.undo(current)!!; count++ }
        assertEquals(30, count)
        assertEquals(recipe(5f), current)
    }

    @Test fun cancellingToolRestoresHistoryCheckpoint() {
        val history = RecipeHistory()
        history.record(recipe(0f), recipe(.1f))
        val checkpoint = history.copy()
        history.record(recipe(.1f), recipe(.2f))
        assertEquals(recipe(0f), checkpoint.undo(recipe(.1f)))
        assertFalse(checkpoint.canUndo)
    }

    @Test fun unchangedGestureDoesNotCreateUndoEntry() {
        val history = RecipeHistory()
        history.record(recipe(0f), recipe(0f))
        assertFalse(history.canUndo)
    }
}
