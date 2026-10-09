package com.kang.imageeditapp.data

import android.content.Context
import android.util.AtomicFile
import com.kang.imageeditapp.model.ColorGrade
import com.kang.imageeditapp.model.ColorPreset
import com.kang.imageeditapp.model.EditRecipe
import com.kang.imageeditapp.model.PresetLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class UnsupportedPresetVersionException(val version: Int) :
    IOException("调色预设版本较新，请更新应用后再使用")

class CorruptPresetLibraryException(cause: Throwable) :
    IOException("调色预设数据损坏，原文件已保留，请检查存储空间或恢复备份", cause)

/** One private, atomic library shared by presets and the color clipboard. */
class PresetRepository(context: Context) {
    private val directory = File(context.filesDir, "presets").canonicalFile
    private val manifestFile = File(directory, "library.json")
    private val manifest = AtomicFile(manifestFile)
    private val mutex = directoryLocks.computeIfAbsent(directory.path) { Mutex() }

    suspend fun load(): PresetLibrary = mutex.withLock {
        withContext(Dispatchers.IO) { readLibrary() }
    }

    suspend fun savePreset(name: String, grade: ColorGrade): PresetLibrary {
        val normalizedName = normalizeName(name)
        val snapshot = grade.detachedCopy()
        return mutate { library ->
            require(library.presets.size < MAX_PRESETS) { "最多保存 30 个调色预设，请先删除不需要的预设" }
            requireUniqueName(library, normalizedName)
            val preset = ColorPreset(UUID.randomUUID().toString(), normalizedName, snapshot, System.currentTimeMillis())
            library.copy(presets = listOf(preset) + library.presets)
        }
    }

    suspend fun renamePreset(id: String, name: String): PresetLibrary {
        val normalizedName = normalizeName(name)
        return mutate { library ->
            require(library.presets.any { it.id == id }) { "预设已不存在，请刷新后重试" }
            requireUniqueName(library, normalizedName, id)
            library.copy(presets = library.presets.map {
                if (it.id == id) it.copy(name = normalizedName) else it
            })
        }
    }

    suspend fun deletePreset(id: String): PresetLibrary = mutate { library ->
        require(library.presets.any { it.id == id }) { "预设已不存在，请刷新后重试" }
        library.copy(presets = library.presets.filterNot { it.id == id })
    }

    suspend fun copyGrade(grade: ColorGrade): PresetLibrary {
        val snapshot = grade.detachedCopy()
        return mutate { it.copy(clipboard = snapshot) }
    }

    private suspend fun mutate(change: (PresetLibrary) -> PresetLibrary): PresetLibrary = mutex.withLock {
        withContext(Dispatchers.IO) {
            // Reading first deliberately blocks overwrite of corrupt or future-version data.
            val next = change(readLibrary())
            val json = encodeLibrary(next)
            ensureActive()
            writeLibrary(json)
            detachedLibrary(next)
        }
    }

    private fun readLibrary(): PresetLibrary {
        if (directory.exists() && !directory.isDirectory) throw IOException("无法读取调色预设，请检查存储空间后重试")
        val text = try {
            manifest.openRead().use { stream ->
                require(stream.channel.size() <= MAX_MANIFEST_BYTES) { "预设数据过大" }
                stream.readBytes().toString(Charsets.UTF_8)
            }
        } catch (_: FileNotFoundException) {
            if (manifestFile.exists() || File(directory, "library.json.bak").exists()) {
                throw IOException("无法读取调色预设，请检查存储空间后重试")
            }
            return PresetLibrary()
        } catch (failure: IllegalArgumentException) {
            throw CorruptPresetLibraryException(failure)
        } catch (failure: IOException) {
            throw IOException("无法读取调色预设，请检查存储空间后重试", failure)
        }
        try {
            val json = JSONObject(text)
            val version = json.getInt("version")
            if (version != VERSION) throw UnsupportedPresetVersionException(version)
            require(json.getDouble("version") == VERSION.toDouble())
            val entries = json.getJSONArray("presets")
            require(entries.length() <= MAX_PRESETS)
            val presets = List(entries.length()) { index ->
                val entry = entries.getJSONObject(index)
                val id = entry.getString("id")
                require(UUID.fromString(id).toString() == id)
                val name = entry.getString("name")
                require(normalizeName(name) == name)
                val savedAt = entry.getLong("savedAtMillis").also { require(it >= 0) }
                ColorPreset(id, name, decodeGrade(entry.getJSONObject("grade")), savedAt)
            }
            require(presets.map { it.id }.distinct().size == presets.size)
            require(presets.map { nameKey(it.name) }.distinct().size == presets.size)
            require(json.has("clipboard"))
            val clipboard = if (json.isNull("clipboard")) null else decodeGrade(json.getJSONObject("clipboard"))
            return detachedLibrary(PresetLibrary(presets, clipboard))
        } catch (future: UnsupportedPresetVersionException) {
            throw future
        } catch (failure: JSONException) {
            throw CorruptPresetLibraryException(failure)
        } catch (failure: IllegalArgumentException) {
            throw CorruptPresetLibraryException(failure)
        }
    }

    private fun encodeLibrary(library: PresetLibrary): JSONObject {
        val presets = JSONArray()
        library.presets.forEach { preset ->
            presets.put(JSONObject().put("id", preset.id).put("name", preset.name)
                .put("savedAtMillis", preset.savedAtMillis).put("grade", encodeGrade(preset.grade)))
        }
        return JSONObject().put("version", VERSION).put("presets", presets)
            .put("clipboard", library.clipboard?.let(::encodeGrade) ?: JSONObject.NULL)
    }

    private fun encodeGrade(grade: ColorGrade): JSONObject {
        val recipe = try { DraftRecipeCodec.encode(grade.applyTo(EditRecipe())) }
        catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("调色参数无效，无法保存预设", failure)
        }
        return JSONObject().put("adjustments", recipe.getJSONObject("adjustments"))
            .put("curves", recipe.getJSONObject("curves")).put("hsl", recipe.getJSONArray("hsl"))
    }

    private fun decodeGrade(json: JSONObject): ColorGrade {
        val recipe = DraftRecipeCodec.encode(EditRecipe())
            .put("adjustments", json.getJSONObject("adjustments"))
            .put("curves", json.getJSONObject("curves"))
            .put("hsl", json.getJSONArray("hsl"))
        return ColorGrade.capture(DraftRecipeCodec.decode(recipe))
    }

    private fun writeLibrary(json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_MANIFEST_BYTES) { "调色预设数据过大，请减少曲线控制点后重试" }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法保存调色预设，请检查可用空间后重试")
        val output = try { manifest.startWrite() }
        catch (failure: IOException) { throw IOException("无法保存调色预设，请检查可用空间后重试", failure) }
        try {
            output.write(bytes)
            manifest.finishWrite(output)
        } catch (failure: Throwable) {
            manifest.failWrite(output)
            if (failure is IOException) throw IOException("无法保存调色预设，请检查可用空间后重试", failure)
            throw failure
        }
    }

    private fun requireUniqueName(library: PresetLibrary, name: String, excludingId: String? = null) {
        require(library.presets.none { it.id != excludingId && nameKey(it.name) == nameKey(name) }) {
            "已存在同名预设，请换一个名称"
        }
    }

    private fun normalizeName(name: String): String = name.trim().also {
        require(it.isNotEmpty()) { "请输入预设名称" }
        require(it.codePointCount(0, it.length) <= 24) { "预设名称最多 24 个字" }
        require(it.none { char -> char.isISOControl() }) { "预设名称不能包含换行或控制字符" }
    }

    private fun nameKey(name: String): String = name.lowercase(Locale.ROOT)

    private fun detachedLibrary(library: PresetLibrary): PresetLibrary = PresetLibrary(
        Collections.unmodifiableList(library.presets.map { it.copy(grade = it.grade.detachedCopy()) }),
        library.clipboard?.detachedCopy(),
    )

    companion object {
        const val MAX_PRESETS = 30
        private const val VERSION = 1
        private const val MAX_MANIFEST_BYTES = 1_048_576L
        private val directoryLocks = ConcurrentHashMap<String, Mutex>()
    }
}
