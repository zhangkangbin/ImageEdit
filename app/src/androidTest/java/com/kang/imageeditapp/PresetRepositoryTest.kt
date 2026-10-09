package com.kang.imageeditapp

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kang.imageeditapp.data.CorruptPresetLibraryException
import com.kang.imageeditapp.data.PresetRepository
import com.kang.imageeditapp.data.UnsupportedPresetVersionException
import com.kang.imageeditapp.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PresetRepositoryTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var repository: PresetRepository
    private val manifest get() = File(root, "presets/library.json")

    @Before fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        root = File(application.filesDir, "preset-test-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(application) { override fun getFilesDir(): File = root }
        repository = PresetRepository(context)
    }

    @After fun cleanUp() { root.deleteRecursively() }

    @Test fun emptyLibraryDoesNotCreateFilesAndCannotPaste() = runBlocking<Unit> {
        assertEquals(PresetLibrary(), repository.load())
        assertFalse(File(root, "presets").exists())
    }

    @Test fun durableRoundTripPreservesEveryColorFieldAndClipboardWithoutPhotoGeometry() = runBlocking<Unit> {
        val recipe = EditRecipe(
            adjustments = ColorAdjustments(1.2f, -.3f, .2f, -.4f, .7f, -.6f, .8f, -.9f),
            curves = CurveSet(
                listOf(CurvePoint(0f, .1f), CurvePoint(.4f, .7f), CurvePoint(1f, .9f)),
                listOf(CurvePoint(0f, 0f), CurvePoint(.2f, .3f), CurvePoint(1f, 1f)),
                listOf(CurvePoint(0f, .2f), CurvePoint(.6f, .5f), CurvePoint(1f, .8f)),
                listOf(CurvePoint(0f, .3f), CurvePoint(.8f, .9f), CurvePoint(1f, 1f)),
            ),
            hsl = List(8) { HslAdjustment((it - 4) / 4f, it / 8f, -it / 8f) },
            crop = CropRect(.1f, .2f, .8f, .9f), quarterTurns = 3,
            flipHorizontal = true, watermark = Watermark("不应进入预设"),
        )
        val grade = ColorGrade.capture(recipe)
        val saved = repository.savePreset("  人像暖色  ", grade).presets.single()
        val copied = repository.copyGrade(grade)
        assertEquals(copied, PresetRepository(context).load())
        assertEquals("人像暖色", saved.name)
        assertEquals(grade, copied.clipboard)
        assertEquals(grade, copied.presets.single().grade)
        assertTrue(saved.savedAtMillis > 0)
        val json = JSONObject(manifest.readText())
        val storedGrade = json.getJSONArray("presets").getJSONObject(0).getJSONObject("grade")
        assertEquals(setOf("adjustments", "curves", "hsl"), storedGrade.keys().asSequence().toSet())
        assertFalse(manifest.readText().contains("不应进入预设"))
        assertFalse(storedGrade.has("crop"))
        assertFalse(storedGrade.has("quarterTurns"))
    }

    @Test fun renameAndDeleteKeepClipboardAndOtherPresets() = runBlocking<Unit> {
        val grade = ColorGrade(adjustments = ColorAdjustments(exposure = .5f))
        val first = repository.savePreset("风光", grade).presets.single()
        val second = repository.savePreset("人像", ColorGrade()).presets.first()
        repository.copyGrade(grade)
        val renamed = repository.renamePreset(first.id, "  通透风光 ")
        assertEquals("通透风光", renamed.presets.single { it.id == first.id }.name)
        assertEquals(grade, renamed.clipboard)
        val deleted = repository.deletePreset(first.id)
        assertEquals(listOf(second), deleted.presets)
        assertEquals(grade, deleted.clipboard)
        assertEquals(deleted, PresetRepository(context).load())
        expectFailure<IllegalArgumentException> { repository.deletePreset(first.id) }
        expectFailure<IllegalArgumentException> { repository.renamePreset(first.id, "已删除") }
    }

    @Test fun nameValidationSupportsUnicodeAndRejectsDuplicatesWithoutWriting() = runBlocking<Unit> {
        val first = repository.savePreset("  Portrait  ", ColorGrade()).presets.single()
        val before = manifest.readText()
        listOf("portrait", "  PORTRAIT  ", "", "  ", "字".repeat(25), "包含\n换行").forEach { name ->
            expectFailure<IllegalArgumentException> { repository.savePreset(name, ColorGrade()) }
            assertEquals(before, manifest.readText())
        }
        repository.savePreset("🌈".repeat(24), ColorGrade())
        val other = repository.savePreset("另一个预设", ColorGrade()).presets.first()
        expectFailure<IllegalArgumentException> { repository.renamePreset(other.id, "portrait") }
        assertEquals("Portrait", repository.load().presets.single { it.id == first.id }.name)
    }

    @Test fun thirtyPresetLimitKeepsLibraryAndStillAllowsClipboardUpdate() = runBlocking<Unit> {
        repeat(PresetRepository.MAX_PRESETS) { repository.savePreset("预设 ${it + 1}", ColorGrade()) }
        val before = manifest.readText()
        expectFailure<IllegalArgumentException> { repository.savePreset("第 31 个", ColorGrade()) }
        assertEquals(before, manifest.readText())
        val copied = repository.copyGrade(ColorGrade(adjustments = ColorAdjustments(contrast = .5f)))
        assertEquals(30, copied.presets.size)
        assertEquals(.5f, copied.clipboard!!.adjustments.contrast, 0f)
        repository.deletePreset(copied.presets.first().id)
        assertEquals(30, repository.savePreset("替代预设", ColorGrade()).presets.size)
    }

    @Test fun invalidGradesDoNotReplaceValidLibraryOrClipboard() = runBlocking<Unit> {
        repository.savePreset("保留", ColorGrade())
        repository.copyGrade(ColorGrade(adjustments = ColorAdjustments(temperature = .2f)))
        val before = manifest.readText()
        val invalid = listOf(
            ColorGrade(adjustments = ColorAdjustments(exposure = Float.NaN)),
            ColorGrade(adjustments = ColorAdjustments(exposure = 2.1f)),
            ColorGrade(adjustments = ColorAdjustments(brightness = -1.1f)),
            ColorGrade(hsl = List(7) { HslAdjustment() }),
            ColorGrade(hsl = List(8) { HslAdjustment(hue = Float.POSITIVE_INFINITY) }),
            ColorGrade(curves = CurveSet(rgb = listOf(CurvePoint(0f, 0f)))),
            ColorGrade(curves = CurveSet(red = listOf(CurvePoint(.1f, 0f), CurvePoint(1f, 1f)))),
            ColorGrade(curves = CurveSet(blue = listOf(CurvePoint(0f, 0f), CurvePoint(.5f, 1f), CurvePoint(.5f, .2f), CurvePoint(1f, 1f)))),
        )
        invalid.forEach {
            expectFailure<IllegalArgumentException> { repository.copyGrade(it) }
            expectFailure<IllegalArgumentException> { repository.savePreset("无效参数", it) }
            assertEquals(before, manifest.readText())
        }
    }

    @Test fun corruptAndFutureLibrariesAreReportedAndNeverOverwritten() = runBlocking<Unit> {
        repository.savePreset("原有预设", ColorGrade())
        val valid = manifest.readText()
        val corrupted = listOf("{broken", JSONObject(valid).put("presets", "wrong type").toString())
        corrupted.forEach { text ->
            manifest.writeText(text)
            expectFailure<CorruptPresetLibraryException> { repository.load() }
            expectFailure<CorruptPresetLibraryException> { repository.copyGrade(ColorGrade()) }
            expectFailure<CorruptPresetLibraryException> { repository.savePreset("不能覆盖", ColorGrade()) }
            assertEquals(text, manifest.readText())
        }
        val future = JSONObject(valid).put("version", 99).toString()
        manifest.writeText(future)
        val failure = expectFailure<UnsupportedPresetVersionException> { repository.load() }
        assertEquals(99, failure.version)
        expectFailure<UnsupportedPresetVersionException> { repository.deletePreset("any") }
        expectFailure<UnsupportedPresetVersionException> { repository.copyGrade(ColorGrade()) }
        assertEquals(future, manifest.readText())
    }

    @Test fun failedAtomicWritePreservesExistingLibrary() = runBlocking<Unit> {
        val original = repository.savePreset("保留原有", ColorGrade())
        val blocked = File(root, "presets/library.json.new").apply { mkdirs() }
        File(blocked, "occupied").writeText("simulated storage failure")
        try {
            val failure = expectFailure<IOException> { repository.savePreset("写入失败", ColorGrade()) }
            assertTrue(failure.message!!.contains("空间"))
        } finally { blocked.deleteRecursively() }
        assertEquals(original, PresetRepository(context).load())
    }

    @Test fun parallelRepositoryInstancesDoNotLoseUpdates() = runBlocking<Unit> {
        val other = PresetRepository(context)
        (0 until 12).map { index ->
            async(Dispatchers.IO) {
                val writer = if (index % 2 == 0) repository else other
                writer.savePreset("并发预设 $index", ColorGrade(adjustments = ColorAdjustments(exposure = index / 10f)))
            }
        }.awaitAll()
        val library = PresetRepository(context).load()
        assertEquals(12, library.presets.size)
        assertEquals((0 until 12).map { "并发预设 $it" }.toSet(), library.presets.map { it.name }.toSet())
    }

    private suspend inline fun <reified T : Throwable> expectFailure(noinline action: suspend () -> Unit): T {
        try { action() }
        catch (failure: Throwable) {
            assertTrue("Expected ${T::class.java.simpleName}, got ${failure.javaClass.simpleName}", failure is T)
            return failure as T
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
