package com.kang.imageeditapp.data

import com.kang.imageeditapp.model.*
import org.json.JSONArray
import org.json.JSONObject

/** The on-disk schema is deliberately explicit so recipes can be migrated independently of UI state. */
internal object DraftRecipeCodec {
    fun encode(recipe: EditRecipe): JSONObject {
        validate(recipe)
        val a = recipe.adjustments
        val color = JSONObject().put("exposure", a.exposure).put("brightness", a.brightness)
            .put("contrast", a.contrast).put("saturation", a.saturation).put("temperature", a.temperature)
            .put("tint", a.tint).put("shadows", a.shadows).put("highlights", a.highlights)
        val curves = JSONObject()
        CurveChannel.entries.forEach { channel ->
            val points = JSONArray()
            recipe.curves.points(channel).forEach { points.put(JSONArray().put(it.x).put(it.y)) }
            curves.put(channel.name, points)
        }
        val hsl = JSONArray()
        recipe.hsl.forEach { hsl.put(JSONObject().put("hue", it.hue).put("saturation", it.saturation).put("lightness", it.lightness)) }
        val c = recipe.crop
        val w = recipe.watermark
        return JSONObject().put("adjustments", color).put("curves", curves).put("hsl", hsl)
            .put("crop", JSONObject().put("left", c.left).put("top", c.top).put("right", c.right).put("bottom", c.bottom))
            .put("quarterTurns", recipe.quarterTurns).put("flipHorizontal", recipe.flipHorizontal)
            .put("flipVertical", recipe.flipVertical)
            .put("watermark", JSONObject().put("text", w.text).put("color", w.color)
                .put("sizeFraction", w.sizeFraction).put("x", w.x).put("y", w.y))
    }

    fun decode(json: JSONObject): EditRecipe {
        val a = json.getJSONObject("adjustments")
        val curves = json.getJSONObject("curves")
        fun points(channel: CurveChannel): List<CurvePoint> {
            val array = curves.getJSONArray(channel.name)
            return List(array.length()) { index ->
                val point = array.getJSONArray(index)
                require(point.length() == 2)
                CurvePoint(point.number(0), point.number(1))
            }
        }
        val hsl = json.getJSONArray("hsl")
        val c = json.getJSONObject("crop")
        val w = json.getJSONObject("watermark")
        return EditRecipe(
            adjustments = ColorAdjustments(a.number("exposure"), a.number("brightness"), a.number("contrast"),
                a.number("saturation"), a.number("temperature"), a.number("tint"), a.number("shadows"), a.number("highlights")),
            curves = CurveSet(points(CurveChannel.RGB), points(CurveChannel.RED), points(CurveChannel.GREEN), points(CurveChannel.BLUE)),
            hsl = List(hsl.length()) { index -> hsl.getJSONObject(index).let { HslAdjustment(it.number("hue"), it.number("saturation"), it.number("lightness")) } },
            crop = CropRect(c.number("left"), c.number("top"), c.number("right"), c.number("bottom")),
            quarterTurns = json.getInt("quarterTurns"), flipHorizontal = json.getBoolean("flipHorizontal"),
            flipVertical = json.getBoolean("flipVertical"),
            watermark = Watermark(w.getString("text"), w.getInt("color"), w.number("sizeFraction"), w.number("x"), w.number("y")),
        ).also(::validate)
    }

    private fun JSONObject.number(key: String) = getDouble(key).toFloat().also { require(it.isFinite()) }
    private fun JSONArray.number(index: Int) = getDouble(index).toFloat().also { require(it.isFinite()) }

    private fun validate(recipe: EditRecipe) {
        val a = recipe.adjustments
        require(a.exposure.isFinite() && a.exposure in -2f..2f)
        require(listOf(a.brightness, a.contrast, a.saturation, a.temperature, a.tint, a.shadows, a.highlights)
            .all { it.isFinite() && it in -1f..1f })
        require(recipe.hsl.size == HslBand.entries.size)
        require(recipe.hsl.all { band -> listOf(band.hue, band.saturation, band.lightness).all { it.isFinite() && it in -1f..1f } })
        CurveChannel.entries.forEach { channel ->
            val points = recipe.curves.points(channel)
            require(points.size >= 2 && points.first().x == 0f && points.last().x == 1f)
            require(points.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..1f && it.y in 0f..1f })
            require(points.zipWithNext().all { (left, right) -> left.x < right.x })
        }
        val c = recipe.crop
        require(listOf(c.left, c.top, c.right, c.bottom).all { it.isFinite() && it in 0f..1f })
        require(c.width > 0f && c.height > 0f)
        require(recipe.quarterTurns in 0..3)
        val w = recipe.watermark
        require(w.sizeFraction.isFinite() && w.sizeFraction > 0f && w.sizeFraction <= 1f)
        require(w.x.isFinite() && w.y.isFinite() && w.x in 0f..1f && w.y in 0f..1f)
    }
}
