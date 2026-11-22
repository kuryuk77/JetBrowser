package com.kuryuk.jetbrowser

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/** Telefondan alınan dosyaları başka uygulamalara (kurulum, video oynatıcı vb.) açan küçük sağlayıcı. */
class FileShare : ContentProvider() {
    override fun onCreate() = true

    private fun fileOf(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        val f = File(dir(ctx), name)
        if (f.parentFile?.canonicalPath != dir(ctx).canonicalPath || !f.exists()) throw FileNotFoundException()
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun getType(uri: Uri): String = mime(uri.lastPathSegment ?: "")

    override fun query(uri: Uri, projection: Array<out String>?, sel: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = fileOf(uri)
        val c = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        c.addRow(arrayOf<Any>(f.name, f.length()))
        return c
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        fun dir(ctx: Context): File =
            File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "received").apply { mkdirs() }

        fun uri(ctx: Context, f: File): Uri =
            Uri.parse("content://" + ctx.packageName + ".files/" + Uri.encode(f.name))

        fun mime(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext == "apk") return "application/vnd.android.package-archive"
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }

        /** Güvenli, çakışmayan dosya adı üret. */
        fun target(ctx: Context, raw: String): File {
            var name = raw.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().takeLast(120)
            if (name.isEmpty() || name == "." || name == "..") name = "dosya"
            val d = dir(ctx)
            var f = File(d, name)
            if (!f.exists()) return f
            val base = name.substringBeforeLast('.')
            val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
            var i = 2
            while (f.exists()) { f = File(d, "$base ($i)$ext"); i++ }
            return f
        }
    }
}
