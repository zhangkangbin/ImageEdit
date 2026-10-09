package com.kang.imageeditapp

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kang.imageeditapp.data.DraftRepository
import com.kang.imageeditapp.data.PhotoRepository
import com.kang.imageeditapp.data.UnsupportedDraftVersionException
import com.kang.imageeditapp.model.*
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
class DraftRepositoryTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var repository: DraftRepository
    private lateinit var photos: PhotoRepository

    @Before fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        root = File(application.filesDir, "draft-test-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(application) { override fun getFilesDir(): File = root }
        repository = DraftRepository(context)
        photos = PhotoRepository(context)
    }

    @After fun cleanUp() { root.deleteRecursively() }

    @Test fun durableRoundTripPreservesEveryRecipeFieldAndThumbnail() = runBlocking {
        val source = importedPhoto(Color.RED).copy(exifOrientation = 7)
        val recipe = EditRecipe(
            adjustments = ColorAdjustments(1.2f, -.3f, .2f, -.4f, .7f, -.6f, .8f, -.9f),
            curves = CurveSet(
                listOf(CurvePoint(0f, .1f), CurvePoint(.4f, .7f), CurvePoint(1f, .9f)),
                listOf(CurvePoint(0f, 0f), CurvePoint(.2f, .3f), CurvePoint(1f, 1f)),
                listOf(CurvePoint(0f, .2f), CurvePoint(.6f, .5f), CurvePoint(1f, .8f)),
                listOf(CurvePoint(0f, .3f), CurvePoint(.8f, .9f), CurvePoint(1f, 1f)),
            ),
            hsl = List(8) { HslAdjustment((it - 4) / 4f, it / 8f, -it / 8f) },
            crop = CropRect(.11f, .17f, .82f, .94f), quarterTurns = 3,
            flipHorizontal = true, flipVertical = true,
            watermark = Watermark("中文水印\n第二行", 0x8088AAFF.toInt(), .075f, .24f, .66f),
        )
        val preview = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val saved = try { repository.save(source, recipe, preview) } finally { preview.recycle() }
        val reloaded = DraftRepository(context).load()!!
        assertEquals(source, reloaded.source)
        assertEquals(recipe, reloaded.recipe)
        assertEquals(saved.savedAtMillis, reloaded.savedAtMillis)
        assertEquals(saved.thumbnailPath, reloaded.thumbnailPath)
        assertTrue(File(source.localPath).isFile)
        assertEquals(File(root, "photos").canonicalFile, File(source.localPath).canonicalFile.parentFile)
        val thumbnail = BitmapFactory.decodeFile(reloaded.thumbnailPath)
        try {
            assertEquals(320, thumbnail.width)
            assertEquals(160, thumbnail.height)
            assertEquals(Color.GREEN, thumbnail.getPixel(100, 100))
        } finally { thumbnail.recycle() }
    }

    @Test fun replacingDraftReleasesOldThumbnailButLeavesOriginalForRenderQueue() = runBlocking {
        val previousSource = importedPhoto(Color.RED)
        val first = repository.save(previousSource, EditRecipe())
        val nextSource = importedPhoto(Color.BLUE)
        val next = repository.save(nextSource, EditRecipe(adjustments = ColorAdjustments(exposure = .5f)))
        assertEquals(nextSource, repository.load()!!.source)
        assertTrue(File(previousSource.localPath).isFile)
        assertFalse(File(first.thumbnailPath!!).exists())
        assertTrue(File(next.thumbnailPath!!).isFile)
    }

    @Test fun failedManifestWriteKeepsPreviousDraftAndCleansUncommittedThumbnail() = runBlocking {
        val first = repository.save(importedPhoto(Color.RED), EditRecipe())
        val thumbnailCount = File(root, "drafts").listFiles()!!.count { it.extension == "png" }
        // Simulate unavailable manifest storage without filling the device's disk.
        val blockedWrite = File(root, "drafts/draft.json.new").apply { mkdirs() }
        File(blockedWrite, "occupied").writeText("storage failure")
        try {
            repository.save(importedPhoto(Color.BLUE), EditRecipe())
            fail("The blocked atomic-write destination must fail")
        } catch (_: IOException) { }
        blockedWrite.deleteRecursively()
        assertEquals(first, DraftRepository(context).load())
        assertTrue(File(first.thumbnailPath!!).isFile)
        assertEquals(thumbnailCount, File(root, "drafts").listFiles()!!.count { it.extension == "png" })
    }

    @Test fun invalidImportAndThumbnailDoNotReplaceExistingDraft() = runBlocking {
        val first = repository.save(importedPhoto(Color.RED), EditRecipe())
        val brokenImage = File(root, "broken.png").apply { writeText("not an image") }
        try {
            photos.import(Uri.fromFile(brokenImage))
            fail("Broken imports must fail")
        } catch (_: IllegalArgumentException) { }
        val recycled = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { recycle() }
        try {
            repository.save(importedPhoto(Color.BLUE), EditRecipe(), recycled)
            fail("An unavailable preview must not overwrite a draft")
        } catch (_: IllegalStateException) { }
        assertEquals(first, repository.load())
    }

    @Test fun parameterSaveWithoutPreviewPreservesLastRenderedThumbnail() = runBlocking {
        val source = importedPhoto(Color.RED)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val first = try { repository.save(source, EditRecipe(), bitmap) } finally { bitmap.recycle() }
        val next = repository.save(source, EditRecipe(watermark = Watermark("新水印")))
        assertEquals(first.thumbnailPath, next.thumbnailPath)
        assertEquals("新水印", repository.load()!!.recipe.watermark.text)
        val thumbnail = BitmapFactory.decodeFile(next.thumbnailPath)
        try { assertEquals(Color.GREEN, thumbnail.getPixel(2, 2)) } finally { thumbnail.recycle() }
    }

    @Test fun oversizedMetadataCannotCommitAnUnreadableDraft() = runBlocking {
        val first = repository.save(importedPhoto(Color.RED), EditRecipe())
        try {
            repository.save(first.source, EditRecipe(watermark = Watermark("字".repeat(360_000))))
            fail("Oversized metadata must fail before replacing the readable draft")
        } catch (_: IllegalArgumentException) { }
        assertEquals(first, repository.load())
    }

    @Test fun explicitDeleteReturnsOriginalForDeferredOwnerOnlyCleanup() = runBlocking {
        val saved = repository.save(importedPhoto(Color.RED), EditRecipe())
        val released = repository.delete()
        assertEquals(saved.source, released)
        assertNull(DraftRepository(context).load())
        assertFalse(File(saved.thumbnailPath!!).exists())
        assertTrue(File(saved.source.localPath).exists())
        photos.discard(released!!)
        assertFalse(File(saved.source.localPath).exists())
        val outside = File(root, "user-original.png").apply { writeText("keep") }
        photos.discard(saved.source.copy(localPath = outside.path))
        assertTrue(outside.exists())
    }

    @Test fun corruptAndMissingSourceAreIgnoredButFutureVersionIsExplicit() = runBlocking {
        val saved = repository.save(importedPhoto(Color.RED), EditRecipe())
        val manifest = File(root, "drafts/draft.json")
        val originalText = manifest.readText()
        manifest.writeText("{broken")
        assertNull(repository.load())
        manifest.writeText(JSONObject(originalText).put("version", 99).toString())
        try {
            repository.load()
            fail("A newer schema must surface a version error")
        } catch (future: UnsupportedDraftVersionException) { assertEquals(99, future.version) }
        manifest.writeText(originalText)
        File(saved.source.localPath).delete()
        assertNull(repository.load())
    }

    @Test fun manifestPathsCannotEscapeOwnedPhotoDirectory() = runBlocking {
        repository.save(importedPhoto(Color.RED), EditRecipe())
        val external = File(root, "keep.image").apply { writeText("keep") }
        val manifest = File(root, "drafts/draft.json")
        val json = JSONObject(manifest.readText())
        json.getJSONObject("source").put("fileName", "../keep.image")
        manifest.writeText(json.toString())
        assertNull(repository.load())
        assertNull(repository.delete())
        assertTrue(external.isFile)
    }

    private suspend fun importedPhoto(color: Int): PhotoSource {
        val input = File(root, "input-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        try { input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        return photos.import(Uri.fromFile(input))
    }
}
