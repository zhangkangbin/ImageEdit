package com.kang.imageeditapp.model

import java.util.Collections

data class BuiltInFilter(val id: String, val name: String, val description: String, val grade: ColorGrade)

/** Bundled color grades stay independent of the editable personal preset library. */
object BuiltInFilters {
    val entries: List<BuiltInFilter> = Collections.unmodifiableList(listOf(
        filter("original", "原色", "仅恢复原始调色", ColorGrade()),
        filter("natural", "自然", "轻提亮 · 保留细节", ColorGrade(
            adjustments = ColorAdjustments(exposure = .10f, contrast = .06f, saturation = .05f, shadows = .12f, highlights = -.10f),
        )),
        filter("vivid", "鲜艳", "明快色彩 · 增强层次", ColorGrade(
            adjustments = ColorAdjustments(contrast = .16f, saturation = .28f, shadows = .08f, highlights = -.12f),
            hsl = bands(HslBand.GREEN to HslAdjustment(saturation = .12f), HslBand.BLUE to HslAdjustment(saturation = .12f)),
        )),
        filter("warm", "暖阳", "温暖光线 · 柔和肤色", ColorGrade(
            adjustments = ColorAdjustments(exposure = .12f, contrast = -.04f, temperature = .28f, tint = .04f, shadows = .12f, highlights = -.12f),
            hsl = bands(HslBand.ORANGE to HslAdjustment(saturation = -.06f, lightness = .06f)),
        )),
        filter("cool", "冷调", "清冷色温 · 通透蓝色", ColorGrade(
            adjustments = ColorAdjustments(contrast = .08f, saturation = -.06f, temperature = -.28f, shadows = .08f, highlights = -.08f),
            hsl = bands(HslBand.CYAN to HslAdjustment(saturation = .10f), HslBand.BLUE to HslAdjustment(saturation = .10f)),
        )),
        filter("film", "胶片", "柔和反差 · 暖色高光", ColorGrade(
            adjustments = ColorAdjustments(saturation = -.18f, temperature = .12f, highlights = -.18f),
            curves = CurveSet(
                rgb = curve(0f to .05f, .25f to .22f, .75f to .80f, 1f to .96f),
                red = curve(0f to 0f, .5f to .52f, 1f to 1f),
                blue = curve(0f to .04f, .5f to .48f, 1f to .94f),
            ),
        )),
        filter("fade", "褪色", "抬升暗部 · 低饱和", ColorGrade(
            adjustments = ColorAdjustments(contrast = -.12f, saturation = -.28f, shadows = .12f, highlights = -.12f),
            curves = CurveSet(rgb = curve(0f to .12f, .5f to .52f, 1f to .94f)),
        )),
        filter("mono", "黑白", "去除色彩 · 突出光影", ColorGrade(
            adjustments = ColorAdjustments(contrast = .18f, saturation = -1f, shadows = .08f, highlights = -.14f),
            curves = CurveSet(rgb = curve(0f to 0f, .25f to .20f, .75f to .80f, 1f to 1f)),
        )),
        filter("sepia", "复古", "棕褐色调 · 旧日氛围", ColorGrade(
            adjustments = ColorAdjustments(contrast = .06f, saturation = -1f, highlights = -.12f),
            curves = CurveSet(
                red = curve(0f to .08f, .5f to .58f, 1f to 1f),
                green = curve(0f to .04f, .5f to .49f, 1f to .94f),
                blue = curve(0f to .02f, .5f to .36f, 1f to .78f),
            ),
        )),
    ))

    fun find(id: String): BuiltInFilter? = entries.firstOrNull { it.id == id }

    private fun filter(id: String, name: String, description: String, grade: ColorGrade) =
        BuiltInFilter(id, name, description, grade.detachedCopy())

    private fun curve(vararg points: Pair<Float, Float>) = points.map { CurvePoint(it.first, it.second) }

    private fun bands(vararg values: Pair<HslBand, HslAdjustment>): List<HslAdjustment> {
        val adjustments = values.toMap()
        return HslBand.entries.map { adjustments[it] ?: HslAdjustment() }
    }
}
