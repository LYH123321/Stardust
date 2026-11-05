package com.aicompanion.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

object UriUtils {
    /** 查询 SAF Uri 的显示文件名；失败返回 null */
    fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            } ?: uri.lastPathSegment?.substringAfterLast('/')
        } catch (_: Throwable) {
            uri.lastPathSegment?.substringAfterLast('/')
        }
    }
}