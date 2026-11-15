package com.clashassistant.vision.replay

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class NotAReplayException(message: String) : Exception(message)

data class ReplayAnalysis(
    val detection: ReplayDetection,
    /** Миниатюры карт соперника (PNG) по номеру кластера. */
    val thumbnails: Map<Int, ByteArray>,
    val framesProcessed: Int,
    val seconds: Float,
)

/**
 * Полный разбор видео: декодирование всех кадров пачками (MediaMetadataRetriever.getFramesAtIndex),
 * извлечение признаков, распознавание розыгрышей, миниатюры карт.
 *
 * Память: в каждый момент в памяти только одна пачка кадров (до ~48 МБ) и маленькие признаки
 * (~1,2 КБ на кадр), поэтому подходит для телефонов с 4 ГБ ОЗУ.
 */
class ReplayAnalyzer(private val context: Context) {

    suspend fun analyze(uri: Uri, onProgress: (Float) -> Unit): ReplayAnalysis = withContext(Dispatchers.Default) {
        val startedAt = System.currentTimeMillis()
        val times = readFrameTimes(uri)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val portrait = if (rotation == 90 || rotation == 270) width > height else height > width
            if (!portrait) throw NotAReplayException("Нужна вертикальная запись экрана телефона.")

            val durationSec = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000f
            val frameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
            val total = when {
                frameCount > 0 && times.isNotEmpty() -> min(frameCount, times.size)
                frameCount > 0 -> frameCount
                else -> times.size
            }
            if (total <= 0) throw NotAReplayException("Не удалось прочитать кадры видео.")

            // Размер пачки подбираем так, чтобы кадры занимали не больше ~48 МБ (RGB_565: 2 байта на пиксель).
            val frameBytes = max(1, width * height * 2)
            val batch = (48_000_000 / frameBytes).coerceIn(4, 24)
            val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.RGB_565 }

            val extractor = FeatureExtractor()
            val features = ArrayList<FrameFeature>(total)
            var index = 0
            while (index < total) {
                ensureActive()
                val count = min(batch, total - index)
                val bitmaps: List<Bitmap> = try {
                    retriever.getFramesAtIndex(index, count, params)
                } catch (e: Exception) {
                    // На некоторых устройствах последняя пачка может не читаться — останавливаемся.
                    if (features.isEmpty()) throw e else emptyList()
                }
                if (bitmaps.isEmpty()) break
                for ((j, bmp) in bitmaps.withIndex()) {
                    val i = index + j
                    val tSec = if (i < times.size) times[i] / 1_000_000f else i * durationSec / total
                    features += extractor.extract(bmp, tSec)
                    bmp.recycle()
                }
                index += bitmaps.size
                onProgress(0.95f * index / total)
            }

            val detection = ReplayDetector.detect(features)
                ?: throw NotAReplayException(
                    "Не похоже на повтор: не найдена панель с картами соперника. " +
                        "Автораспознавание работает для повторов из журнала боёв."
                )

            // Миниатюры карт соперника
            val thumbs = HashMap<Int, ByteArray>()
            for (c in detection.clusters) {
                ensureActive()
                val bmp = retriever.getFrameAtTime(
                    (c.representativeTimeSec * 1_000_000L).toLong(),
                    MediaMetadataRetriever.OPTION_CLOSEST,
                ) ?: continue
                extractor.nextThumbnailPng(bmp)?.let { thumbs[c.id] = it }
                bmp.recycle()
            }
            onProgress(1f)
            ReplayAnalysis(detection, thumbs, features.size, (System.currentTimeMillis() - startedAt) / 1000f)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Время показа каждого кадра (мкс) по порядку — без декодирования, через MediaExtractor. */
    private fun readFrameTimes(uri: Uri): List<Long> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return emptyList()
            extractor.selectTrack(track)
            val list = ArrayList<Long>()
            while (true) {
                val ts = extractor.sampleTime
                if (ts < 0) break
                list += ts
                if (!extractor.advance()) break
            }
            list.sort()
            list
        } catch (e: Exception) {
            emptyList()
        } finally {
            extractor.release()
        }
    }
}
