package com.kang.imageeditapp.model

import org.junit.Assert.*
import org.junit.Test

class GeometryTest {
    @Test fun exifDirectionsHaveExpectedMappings() {
        val expected = listOf(
            NormalizedPoint(.2f, .3f), NormalizedPoint(.8f, .3f),
            NormalizedPoint(.8f, .7f), NormalizedPoint(.2f, .7f),
            NormalizedPoint(.3f, .2f), NormalizedPoint(.7f, .2f),
            NormalizedPoint(.7f, .8f), NormalizedPoint(.3f, .8f),
        )
        for (orientation in 1..8) {
            val p = Geometry.sourceToOriented(.2f, .3f, orientation)
            assertEquals(expected[orientation - 1].x, p.x, 0.00001f)
            assertEquals(expected[orientation - 1].y, p.y, 0.00001f)
        }
    }

    @Test fun allGeometryCombinationsRoundTrip() {
        for (exif in 1..8) for (turn in 0..3) for (h in listOf(false, true)) for (v in listOf(false, true)) {
            val recipe = EditRecipe(crop = CropRect(.1f, .2f, .8f, .9f), quarterTurns = turn, flipHorizontal = h, flipVertical = v)
            for (p in listOf(NormalizedPoint(.15f, .25f), NormalizedPoint(.8f, .7f), NormalizedPoint(.5f, .5f))) {
                val output = Geometry.sourceToOutput(p.x, p.y, recipe, exif)
                val restored = Geometry.outputToSource(output.x, output.y, recipe, exif)
                assertEquals("EXIF $exif turn $turn h $h v $v", p.x, restored.x, .00001f)
                assertEquals(p.y, restored.y, .00001f)
            }
        }
    }

    @Test fun dimensionsSwapExactlyOnceForQuarterTurnsAndExif() {
        assertEquals(ImageSize(400, 300), Geometry.transformedSize(400, 300, 1, 0))
        assertEquals(ImageSize(300, 400), Geometry.transformedSize(400, 300, 6, 0))
        assertEquals(ImageSize(400, 300), Geometry.transformedSize(400, 300, 6, 1))
        assertEquals(ImageSize(300, 400), Geometry.transformedSize(400, 300, 1, -1))
    }

    @Test fun cropBoundsCannotBeEmptyOrLeaveImage() {
        val crop = CropRect(-.5f, 2f, -1f, 3f).constrained()
        assertTrue(crop.left >= 0 && crop.top >= 0)
        assertTrue(crop.right <= 1 && crop.bottom <= 1)
        assertTrue(crop.width > 0 && crop.height > 0)
    }
}
