package com.clashassistant.vision.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri

/**
 * Извлечение кадров из видеофайла.
 *
 * Экономия памяти (важно для 4 ГБ): кадр сразу уменьшается до [maxWidth]×[maxHeight]
 * с сохранением пропорций, в памяти одновременно держится только один кадр.
 * Класс потокобезопасен (MediaMetadataRetriever сам по себе — нет).
 */
class VideoFrameSource(private val context: Context) {
    private var retriever: MediaMetadataRetriever? = null
    private val lock = Any()

    /** Открывает видео и возвращает его длительность в секундах. */
    fun open(uri: Uri): Float = synchronized(lock) {
        releaseLocked()
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
        } catch (e: Exception) {
            runCatching { r.release() }
            throw IllegalArgumentException("Не удалось открыть видео: ${e.message}", e)
        }
        retriever = r
        val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        ms / 1000f
    }

    /** Кадр в момент [sec]. [exact] = false — быстрее, но берётся ближайший ключевой кадр. */
    fun frameAt(sec: Float, exact: Boolean = true, maxWidth: Int = 720, maxHeight: Int = 1280): Bitmap? =
        synchronized(lock) {
            val r = retriever ?: return null
            val option = if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            runCatching {
                r.getScaledFrameAtTime((sec * 1_000_000L).toLong(), option, maxWidth, maxHeight)
            }.getOrNull()
        }

    fun close() = synchronized(lock) { releaseLocked() }

    private fun releaseLocked() {
        runCatching { retriever?.release() }
        retriever = null
    }
}
