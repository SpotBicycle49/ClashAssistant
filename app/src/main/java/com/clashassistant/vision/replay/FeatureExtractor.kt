package com.clashassistant.vision.replay

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Разметка экрана повтора в координатах эталонной ширины 720 px (замерено на записи 720×1576).
 * Интерфейс игры масштабируется по ширине и привязан к верху экрана, поэтому координаты
 * умножаются на (ширина кадра / 720).
 */
object ReplayLayout {
    const val REF_WIDTH = 720f

    /** Значок-капля с числом эликсира соперника (проверка, что на экране повтор). */
    val elixirDrop = intArrayOf(28, 85, 62, 120)
    /** Полоска эликсира соперника. */
    val elixirBar = intArrayOf(68, 100, 692, 112)
    /** Центр рисунка карты «След.» (без рамки и значка стоимости). */
    val nextCore = intArrayOf(25, 167, 63, 206)
    /** Карта «След.» целиком — для миниатюры. */
    val nextThumb = intArrayOf(18, 160, 70, 222)

    const val VEC_W = 16
    const val VEC_H = 18
    const val VEC_LEN = VEC_W * VEC_H
}

/** Извлекает признаки кадра. Экземпляр не потокобезопасен (переиспользует буферы). */
class FeatureExtractor {
    private var buf = IntArray(0)

    fun extract(bmp: Bitmap, timeSec: Float): FrameFeature {
        val s = bmp.width / ReplayLayout.REF_WIDTH
        return FrameFeature(
            timeSec = timeSec,
            panel = panelScore(bmp, s),
            elixir = elixirLevel(bmp, s),
            next = nextVector(bmp, s),
        )
    }

    private fun region(bmp: Bitmap, r: IntArray, s: Float): IntArray? {
        val x0 = (r[0] * s).toInt().coerceIn(0, bmp.width - 1)
        val y0 = (r[1] * s).toInt().coerceIn(0, bmp.height - 1)
        val x1 = (r[2] * s).toInt().coerceIn(x0 + 1, bmp.width)
        val y1 = (r[3] * s).toInt().coerceIn(y0 + 1, bmp.height)
        val w = x1 - x0
        val h = y1 - y0
        if (w <= 0 || h <= 0) return null
        if (buf.size < w * h) buf = IntArray(w * h)
        bmp.getPixels(buf, 0, w, x0, y0, w, h)
        return intArrayOf(w, h)
    }

    private fun panelScore(bmp: Bitmap, s: Float): Float {
        val (w, h) = region(bmp, ReplayLayout.elixirDrop, s) ?: return 0f
        var cnt = 0
        for (i in 0 until w * h) {
            val c = buf[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (r > 120 && b > 120 && g < 120) cnt++
        }
        return cnt.toFloat() / (w * h)
    }

    /**
     * Полоска: заполненные деления яркие, текущее деление заполняется тусклым цветом, пустое — тёмное.
     * Уровень = самый правый «не тёмный» столбец непрерывной заливки слева (разрывы до 40 px терпим:
     * разделители делений и значок, который появляется поверх полоски).
     */
    private fun elixirLevel(bmp: Bitmap, s: Float): Float {
        val (w, h) = region(bmp, ReplayLayout.elixirBar, s) ?: return 0f
        val maxGap = (40 * s).toInt()
        var last = -1
        var gap = 0
        for (x in 0 until w) {
            var sum = 0
            for (y in 0 until h) {
                val c = buf[y * w + x]
                sum += ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
            }
            val lum = sum / (3f * h)
            if (lum > 60f) { last = x; gap = 0 } else {
                gap++
                if (gap > maxGap) break
            }
        }
        return 10f * (last + 1) / w
    }

    private fun nextVector(bmp: Bitmap, s: Float): FloatArray {
        val out = FloatArray(ReplayLayout.VEC_LEN)
        val (w, h) = region(bmp, ReplayLayout.nextCore, s) ?: return out
        // Усреднение по ячейкам (аналог INTER_AREA) в оттенках серого.
        for (cy in 0 until ReplayLayout.VEC_H) {
            val y0 = cy * h / ReplayLayout.VEC_H
            val y1 = max(y0 + 1, (cy + 1) * h / ReplayLayout.VEC_H)
            for (cx in 0 until ReplayLayout.VEC_W) {
                val x0 = cx * w / ReplayLayout.VEC_W
                val x1 = max(x0 + 1, (cx + 1) * w / ReplayLayout.VEC_W)
                var sum = 0f
                var cnt = 0
                for (y in y0 until min(y1, h)) for (x in x0 until min(x1, w)) {
                    val c = buf[y * w + x]
                    sum += 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
                    cnt++
                }
                out[cy * ReplayLayout.VEC_W + cx] = if (cnt > 0) sum / cnt else 0f
            }
        }
        val mean = out.average().toFloat()
        for (i in out.indices) out[i] -= mean
        ReplayDetector.normalize(out)
        return out
    }

    /** Миниатюра карты «След.» в PNG (для подписи карт пользователем). */
    fun nextThumbnailPng(bmp: Bitmap): ByteArray? {
        val s = bmp.width / ReplayLayout.REF_WIDTH
        val r = ReplayLayout.nextThumb
        val x0 = (r[0] * s).toInt().coerceIn(0, bmp.width - 1)
        val y0 = (r[1] * s).toInt().coerceIn(0, bmp.height - 1)
        val w = ((r[2] - r[0]) * s).toInt().coerceAtMost(bmp.width - x0)
        val h = ((r[3] - r[1]) * s).toInt().coerceAtMost(bmp.height - y0)
        if (w <= 0 || h <= 0) return null
        val crop = Bitmap.createBitmap(bmp, x0, y0, w, h)
        val scaled = Bitmap.createScaledBitmap(crop, 104, 124, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        if (scaled !== crop) scaled.recycle()
        crop.recycle()
        return out.toByteArray()
    }
}
