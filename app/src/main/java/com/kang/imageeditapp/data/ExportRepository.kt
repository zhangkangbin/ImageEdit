package com.kang.imageeditapp.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.kang.imageeditapp.model.ExportFormat
import com.kang.imageeditapp.model.ExportOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ExportRepository(private val context: Context) {
    suspend fun save(bitmap: Bitmap, format: ExportFormat): Uri = save(bitmap, ExportOptions(format = format))

    suspend fun save(bitmap: Bitmap, options: ExportOptions): Uri = withContext(Dispatchers.IO) {
        options.validate()
        val format = options.format
        if (format == ExportFormat.JPEG) {
            Canvas(bitmap).drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER)
            bitmap.setHasAlpha(false)
        }
        val name = "IMG_EDIT_${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))}.${format.extension}"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ImageEditApp")
            put(MediaStore.Images.Media.WIDTH, bitmap.width)
            put(MediaStore.Images.Media.HEIGHT, bitmap.height)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建图片文件，请检查可用存储空间")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                val compression = if (format == ExportFormat.JPEG) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
                check(bitmap.compress(compression, if (format == ExportFormat.JPEG) options.jpegQuality else 100, output)) {
                    "图片编码失败，请重试"
                }
            } ?: error("无法写入相册，请检查可用存储空间")
            val published = resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            check(published > 0) { "图片保存未完成，请重试" }
            uri
        } catch (failure: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw failure
        }
    }
}
