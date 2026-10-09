package com.kang.imageeditapp.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.kang.imageeditapp.model.PhotoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class PhotoRepository(private val context: Context) {
    suspend fun import(uri: Uri): PhotoSource {
        var pendingTarget: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val mime = context.contentResolver.getType(uri)
                require(mime != "image/gif") { "暂不支持动画图片，请选择 JPEG 或 PNG 图片" }
                val directory = ownedDirectory()
                check(directory.isDirectory || directory.mkdirs()) { "无法创建图片存储目录，请检查可用空间" }
                val target = File(directory, "${UUID.randomUUID()}.image")
                pendingTarget = target
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output); output.fd.sync() }
                } ?: error("无法读取这张图片，请重新选择")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(target.path, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片已损坏，或当前设备不支持此格式" }
                require(bounds.outMimeType != "image/gif") { "暂不支持动画图片，请选择 JPEG 或 PNG 图片" }
                val orientation = runCatching {
                    ExifInterface(target).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }.getOrDefault(1).takeIf { it in 1..8 } ?: 1
                PhotoSource(uri, target.path, bounds.outWidth, bounds.outHeight, orientation)
            }
        } catch (failure: Throwable) {
            // This also catches cancellation delivered while handing the IO result to the caller.
            withContext(NonCancellable + Dispatchers.IO) { pendingTarget?.delete() }
            throw failure
        }
    }

    /** Explicit cleanup only: session closure must not discard the persisted draft's source. */
    fun discard(source: PhotoSource) {
        val directory = ownedDirectory().canonicalFile
        val file = File(source.localPath).canonicalFile
        if (file.parentFile == directory) file.delete()
    }

    internal fun ownedDirectory(): File = File(context.filesDir, "photos").canonicalFile
}
