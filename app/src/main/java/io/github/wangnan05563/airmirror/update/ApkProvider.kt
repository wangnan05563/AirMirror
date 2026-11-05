package io.github.wangnan05563.airmirror.update

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * 把下载好的 APK 暴露给系统安装器。
 *
 * Android 7+ 禁止 `file://` 外发（FileUriExposedException），而项目不引 AndroidX、
 * 用不上 support 的 FileProvider，所以这里实现一个只读、只认缓存目录里 .apk 的最小 Provider。
 * 路径校验是硬要求：任何 `..` 或指向缓存目录之外的请求一律拒绝，避免把私有文件暴露成安装源。
 */
class ApkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("read-only provider")
        val file = resolve(uri) ?: throw SecurityException("unknown apk: $uri")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val file = resolve(uri) ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = arrayOfNulls<Any>(columns.size)
        columns.forEachIndexed { index, column ->
            row[index] = when (column) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        return MatrixCursor(columns).apply { addRow(row) }
    }

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    /** 只允许缓存目录 `updates/` 下的单个 .apk 文件。 */
    private fun resolve(uri: Uri): File? {
        val context = context ?: return null
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return null
        if (!name.endsWith(".apk", ignoreCase = true)) return null
        val root = File(context.cacheDir, DIR).canonicalFile
        val file = File(root, name).canonicalFile
        if (!file.path.startsWith(root.path + File.separator)) return null
        return file.takeIf { it.isFile }
    }

    companion object {
        const val AUTHORITY_SUFFIX = ".updates"
        private const val DIR = "updates"

        fun dir(context: Context): File = File(context.cacheDir, DIR)

        fun uriFor(context: Context, file: File): Uri =
            Uri.Builder()
                .scheme("content")
                .authority(context.packageName + AUTHORITY_SUFFIX)
                .appendPath(file.name)
                .build()
    }
}
