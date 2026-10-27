package com.maeilnews.app

import android.content.ContentValues
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL

/** Saves the cards and videos into the phone's gallery: Pictures/MaeilNews and Movies/MaeilNews. */
object Downloader {

    fun toast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    /** Saves a Korean article's cards and video. Returns how many files were saved. */
    fun saveArticle(context: Context, card: Card): Int {
        val media = card.media ?: return 0
        var saved = 0
        media.cards.forEach { url ->
            if (save(context, url, "image/png", "Pictures/MaeilNews")) saved++
        }
        media.video?.let { url ->
            if (save(context, url, "video/mp4", "Movies/MaeilNews")) saved++
        }
        return saved
    }

    /** Saves the weekly quiz's cards and video. Returns how many files were saved. */
    fun saveQuiz(context: Context, quiz: Quiz): Int {
        val media = quiz.media ?: return 0
        var saved = 0
        media.cards.forEach { url ->
            if (save(context, url, "image/png", "Pictures/MaeilNews")) saved++
        }
        media.video?.let { url ->
            if (save(context, url, "video/mp4", "Movies/MaeilNews")) saved++
        }
        return saved
    }

    /** Streams one file from the site into MediaStore. The file name comes from the URL. */
    private fun save(context: Context, url: String, mime: String, folder: String): Boolean {
        return try {
            val name = url.substringAfterLast('/')
            val resolver = context.contentResolver
            val collection = if (mime.startsWith("video")) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return false

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            try {
                val out = resolver.openOutputStream(uri) ?: return false
                out.use { o -> conn.inputStream.use { it.copyTo(o) } }
            } finally {
                conn.disconnect()
            }

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            false
        }
    }
}
