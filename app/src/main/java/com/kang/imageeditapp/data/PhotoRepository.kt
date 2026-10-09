package com.kang.imageeditapp.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.kang.imageeditapp.model.PhotoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class PhotoRepository(private val context: Context) {
    suspend fun import(uri: Uri): PhotoSource = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri)
        require(mime != "image/gif") { "暂不支持动画图片，请选择 JPEG 或 PNG 图片" }
        val directory = File(context.cacheDir, "photos").apply { mkdirs() }
        val target = File(directory, "${UUID.randomUUID()}.image")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取这张图片，请重新选择")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片已损坏，或当前设备不支持此格式" }
            require(bounds.outMimeType != "image/gif") { "暂不支持动画图片，请选择 JPEG 或 PNG 图片" }
            val orientation = runCatching {
                ExifInterface(target).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(1).takeIf { it in 1..8 } ?: 1
            PhotoSource(uri, target.path, bounds.outWidth, bounds.outHeight, orientation)
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }

    /** Delete only inputs owned by this repository, never the user's original URI. */
    fun discard(source: PhotoSource) {
        val directory = File(context.cacheDir, "photos").canonicalFile
        val file = File(source.localPath).canonicalFile
        if (file.parentFile == directory) file.delete()
    }
}
