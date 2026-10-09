package com.kang.imageeditapp.model

import java.util.Collections

/** Portable color settings. Geometry and watermarks always belong to the receiving photo. */
data class ColorGrade(
    val adjustments: ColorAdjustments = ColorAdjustments(),
    val curves: CurveSet = CurveSet(),
    val hsl: List<HslAdjustment> = List(HslBand.entries.size) { HslAdjustment() },
) {
    fun applyTo(recipe: EditRecipe): EditRecipe {
        val grade = detachedCopy()
        return recipe.copy(adjustments = grade.adjustments, curves = grade.curves, hsl = grade.hsl)
    }

    /** Detach mutable lists supplied by callers before retaining or publishing parameters. */
    fun detachedCopy(): ColorGrade = copy(
        adjustments = adjustments.copy(),
        curves = CurveSet(
            immutableCopy(curves.rgb), immutableCopy(curves.red),
            immutableCopy(curves.green), immutableCopy(curves.blue),
        ),
        hsl = immutableCopy(hsl),
    )

    companion object {
        fun capture(recipe: EditRecipe): ColorGrade =
            ColorGrade(recipe.adjustments, recipe.curves, recipe.hsl).detachedCopy()

        private fun <T> immutableCopy(values: List<T>): List<T> =
            Collections.unmodifiableList(ArrayList(values))
    }
}

data class ColorPreset(
    val id: String,
    val name: String,
    val grade: ColorGrade,
    val savedAtMillis: Long,
)

data class PresetLibrary(
    val presets: List<ColorPreset> = emptyList(),
    val clipboard: ColorGrade? = null,
)
