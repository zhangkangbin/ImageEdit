package com.kang.imageeditapp.model

/** Stores immutable recipes only. A gesture is a single edit regardless of slider events. */
class RecipeHistory(private val limit: Int = 30) {
    private val undo = ArrayDeque<EditRecipe>()
    private val redo = ArrayDeque<EditRecipe>()
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun record(before: EditRecipe, after: EditRecipe) {
        if (before == after) return
        undo.addLast(before)
        while (undo.size > limit) undo.removeFirst()
        redo.clear()
    }

    fun undo(current: EditRecipe): EditRecipe? {
        if (undo.isEmpty()) return null
        redo.addLast(current)
        return undo.removeLast()
    }

    fun redo(current: EditRecipe): EditRecipe? {
        if (redo.isEmpty()) return null
        undo.addLast(current)
        return redo.removeLast()
    }

    fun clear() { undo.clear(); redo.clear() }
    fun copy(): RecipeHistory = RecipeHistory(limit).also { it.undo.addAll(undo); it.redo.addAll(redo) }
}
