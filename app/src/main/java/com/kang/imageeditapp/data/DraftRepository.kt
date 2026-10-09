package com.kang.imageeditapp.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.util.AtomicFile
import com.kang.imageeditapp.model.EditRecipe
import com.kang.imageeditapp.model.Geometry
import com.kang.imageeditapp.model.PhotoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

data class Draft(
    val source: PhotoSource,
    val recipe: EditRecipe,
    val thumbnailPath: String?,
    val savedAtMillis: Long,
)

class UnsupportedDraftVersionException(val version: Int) : IOException("草稿版本较新，请使用更新版本的应用继续编辑")

/**
 * A single durable draft. The manifest is the commit record and is always written last.
 * Replacing or deleting a manifest never deletes originals: the caller releases obsolete inputs
 * after the render queue has finished using them. Bitmap ownership also stays with the caller.
 */
class DraftRepository(context: Context) {
    private val directory = File(context.filesDir, "drafts").canonicalFile
    private val photos = File(context.filesDir, "photos").canonicalFile
    private val manifest = AtomicFile(File(directory, "draft.json"))
    // A recreated ViewModel can overlap its predecessor's final flush in the same process.
    private val mutex = directoryLocks.getOrPut(directory.canonicalPath) { Mutex() }

    suspend fun load(): Draft? = mutex.withLock { withContext(Dispatchers.IO) { readDraft() } }

    suspend fun save(source: PhotoSource, recipe: EditRecipe, thumbnail: Bitmap? = null): Draft =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val canonicalSource = source.copy(localPath = File(source.localPath).canonicalPath)
                requireOwnedSource(canonicalSource)
                val recipeJson = DraftRecipeCodec.encode(recipe)
                check(directory.isDirectory || directory.mkdirs()) { "无法保存草稿，请检查可用空间" }
                val previous = try { readDraft() } catch (_: UnsupportedDraftVersionException) { null }
                var newThumbnail: File? = null
                try {
                    val thumbnailFile = if (thumbnail == null && previous?.source == canonicalSource && previous.thumbnailPath != null) {
                        File(previous.thumbnailPath)
                    } else {
                        File(directory, "${UUID.randomUUID()}.png").also { file ->
                            newThumbnail = file
                            writeThumbnail(canonicalSource, recipe, thumbnail, file)
                        }
                    }
                    val savedAt = System.currentTimeMillis()
                    val json = JSONObject().put("version", VERSION).put("savedAtMillis", savedAt)
                        .put("source", JSONObject().put("uri", canonicalSource.uri.toString()).put("fileName", File(canonicalSource.localPath).name)
                            .put("width", canonicalSource.width).put("height", canonicalSource.height).put("exifOrientation", canonicalSource.exifOrientation))
                        .put("recipe", recipeJson).put("thumbnail", thumbnailFile.name)
                    ensureActive()
                    writeManifest(json)
                    val committed = Draft(canonicalSource, recipe, thumbnailFile.path, savedAt)
                    // Cleanup is best effort after commit; it must never invalidate the new draft.
                    runCatching { previous?.thumbnailPath?.let { path ->
                        if (path != committed.thumbnailPath) ownedThumbnail(path)?.delete()
                    } }
                    committed
                } catch (failure: Throwable) {
                    newThumbnail?.delete()
                    throw failure
                }
            }
        }

    /** Remove metadata and thumbnail; return the original for deferred, owner-only cleanup. */
    suspend fun delete(): PhotoSource? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val draft = readDraft()
            manifest.delete()
            if (File(directory, "draft.json").exists()) throw IOException("无法删除草稿，请检查存储空间后重试")
            runCatching { draft?.thumbnailPath?.let { ownedThumbnail(it)?.delete() } }
            draft?.source
        }
    }

    private fun readDraft(): Draft? {
        return try {
            val text = manifest.openRead().use { stream ->
                require(stream.channel.size() <= MAX_MANIFEST_BYTES) { "草稿数据过大" }
                stream.readBytes().toString(Charsets.UTF_8)
            }
            val json = JSONObject(text)
            val version = json.getInt("version")
            if (version != VERSION) throw UnsupportedDraftVersionException(version)
            val photo = json.getJSONObject("source")
            val name = photo.getString("fileName")
            require(name == File(name).name && name.endsWith(".image"))
            val source = PhotoSource(Uri.parse(photo.getString("uri")), File(photos, name).path,
                photo.getInt("width"), photo.getInt("height"), photo.getInt("exifOrientation"))
            requireOwnedSource(source)
            val thumbnail = if (json.isNull("thumbnail")) null else {
                val nameInManifest = json.getString("thumbnail")
                require(nameInManifest == File(nameInManifest).name)
                ownedThumbnail(File(directory, nameInManifest).path)?.takeIf { it.isFile }?.path
            }
            Draft(source, DraftRecipeCodec.decode(json.getJSONObject("recipe")), thumbnail, json.getLong("savedAtMillis"))
        } catch (_: FileNotFoundException) { null }
        catch (future: UnsupportedDraftVersionException) { throw future }
        catch (_: JSONException) { null }
        catch (_: IllegalArgumentException) { null }
        catch (_: IOException) { null }
    }

    private fun requireOwnedSource(source: PhotoSource) {
        val file = File(source.localPath).canonicalFile
        require(file.parentFile == photos.canonicalFile && file.name.endsWith(".image")) { "草稿图片不属于当前应用" }
        require(file.isFile && file.length() > 0) { "草稿原图已丢失，请重新选择图片" }
        require(source.width > 0 && source.height > 0 && source.exifOrientation in 1..8) { "草稿图片信息无效" }
    }

    private fun ownedThumbnail(path: String): File? = File(path).canonicalFile.takeIf {
        it.parentFile == directory.canonicalFile && it.name.endsWith(".png")
    }

    private fun writeManifest(json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_MANIFEST_BYTES) { "草稿内容过大，请缩短水印文字后重试" }
        val output = manifest.startWrite()
        try {
            output.write(bytes)
            manifest.finishWrite(output)
        } catch (failure: Throwable) {
            manifest.failWrite(output)
            throw failure
        }
    }

    private fun writeThumbnail(source: PhotoSource, recipe: EditRecipe, supplied: Bitmap?, target: File) {
        val thumbnail = if (supplied != null) {
            check(!supplied.isRecycled) { "草稿预览已失效，请重试" }
            scaledThumbnail(supplied)
        } else sourceThumbnail(source, recipe)
        try {
            target.outputStream().use { output ->
                check(thumbnail.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法保存草稿缩略图" }
                output.fd.sync()
            }
        } finally {
            if (thumbnail !== supplied) thumbnail.recycle()
        }
    }

    private fun scaledThumbnail(bitmap: Bitmap): Bitmap {
        val scale = (THUMBNAIL_EDGE.toFloat() / max(bitmap.width, bitmap.height)).coerceAtMost(1f)
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
    }

    /** The initial import has no rendered preview yet; use an oriented/cropped source thumbnail. */
    private fun sourceThumbnail(source: PhotoSource, recipe: EditRecipe): Bitmap {
        val outputSize = Geometry.outputSize(source, recipe)
        val ratio = (THUMBNAIL_EDGE.toFloat() / max(outputSize.width, outputSize.height)).coerceAtMost(1f)
        var sampleSize = 1
        while (max(source.width, source.height) / (sampleSize * 2) >= THUMBNAIL_EDGE) sampleSize *= 2
        val input = BitmapFactory.decodeFile(source.localPath, BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }) ?: throw IOException("无法读取草稿缩略图")
        try {
            val output = Bitmap.createBitmap((outputSize.width * ratio).roundToInt().coerceAtLeast(1),
                (outputSize.height * ratio).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val points = listOf(Geometry.sourceToOutput(0f, 0f, recipe, source.exifOrientation),
                Geometry.sourceToOutput(1f, 0f, recipe, source.exifOrientation),
                Geometry.sourceToOutput(0f, 1f, recipe, source.exifOrientation))
            val destinations = points.flatMap { listOf(it.x * output.width, it.y * output.height) }.toFloatArray()
            val matrix = Matrix().apply { setPolyToPoly(floatArrayOf(0f, 0f, input.width.toFloat(), 0f, 0f, input.height.toFloat()),
                0, destinations, 0, 3) }
            Canvas(output).drawBitmap(input, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            return output
        } finally { input.recycle() }
    }

    companion object {
        private val directoryLocks = ConcurrentHashMap<String, Mutex>()
        private const val VERSION = 1
        private const val THUMBNAIL_EDGE = 320
        private const val MAX_MANIFEST_BYTES = 1_048_576L
    }
}
