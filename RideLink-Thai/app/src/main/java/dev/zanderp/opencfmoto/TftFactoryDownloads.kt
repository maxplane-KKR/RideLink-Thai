package dev.zanderp.opencfmoto

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.io.InputStream

/** Publishes an exact copy for service. The app-private original is kept separately. */
internal class TftFactoryDownloads(context: Context, private val backup: TftFactoryBackup) {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("tft_factory_downloads", Context.MODE_PRIVATE)
    private val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/RideLink/"

    fun prepareCopy(): TftFactoryBackup.CopyStatus = backup.prepareCopy(::publishCopy)

    private fun publishCopy(original: TftFactoryBackup.Entry, source: InputStream) {
        val previous = prefs.getString("copy_uri", null)?.let(Uri::parse)
        if (previous != null && runCatching { isCurrentCopy(previous) }.getOrDefault(false)) return

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, original.name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create the download")
        try {
            val output = resolver.openOutputStream(uri) ?: throw IOException("Could not open the download")
            output.use { source.copyTo(it) }
            if (!matchesBackup(uri)) throw IOException("Download failed verification")
            val visible = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            if (resolver.update(uri, visible, null, null) != 1) throw IOException("Could not publish the download")
        } catch (error: Exception) {
            // This URI belongs to the copy just created, never an older copy or the original.
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
        prefs.edit().putString("copy_uri", uri.toString()).apply()
    }

    private fun isCurrentCopy(uri: Uri): Boolean {
        val columns = arrayOf(MediaStore.Downloads.RELATIVE_PATH, MediaStore.Downloads.IS_PENDING)
        val published = resolver.query(uri, columns, null, null, null)?.use {
            it.moveToFirst() && it.getString(0) == relativePath && it.getInt(1) == 0
        } ?: false
        return published && matchesBackup(uri)
    }

    private fun matchesBackup(uri: Uri) = backup.matchesOriginal {
        resolver.openInputStream(uri) ?: throw IOException("Could not read the download")
    }

}
