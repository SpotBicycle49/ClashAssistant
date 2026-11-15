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

    /** Значок-капля с числом эликсира соперника. */
    val elixirDrop = intArrayOf(28, 85, 62, 120)
    /** Надпись «След.:» под картой «След.» — признак, что на экране повтор (в меню её нет). */
    val nextLabel = intArrayOf(18, 222, 66, 236)
    /**
     * Верхние ряды полоски эликсира соперника. Нижние ряды не берём: туда заходят
     * «язычки» над картами соперника и завышают показание.
     */
    val elixirBar = intArrayOf(68, 96, 692, 104)
    /** Значок стоимости карты «След.»: фиолетовый — карта цветная, серый — карта ещё «перезаряжается». */
    val nextBadge = intArrayOf(29, 208, 43, 222)
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
        val label = whiteFraction(bmp, ReplayLayout.nextLabel, s)
        val (elixir, saturation) = elixirLevel(bmp, s)
        // Панель повтора видна, если есть надпись «След.:» и полоска эликсира цветная
        // (уведомление телефона закрывает её серым баннером — насыщенность падает до ~10).
        val panel = if (label > 0.02f && saturation > 25f) magentaFraction(bmp, ReplayLayout.elixirDrop, s, 120) else 0f
        return FrameFeature(
            timeSec = timeSec,
            panel = panel,
            elixir = elixir,
            next = nextVector(bmp, s),
            nextColor = magentaFraction(bmp, ReplayLayout.nextBadge, s, 100),
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

    private fun magentaFraction(bmp: Bitmap, r: IntArray, s: Float, maxGreen: Int): Float {
        val (w, h) = region(bmp, r, s) ?: return 0f
        var cnt = 0
        for (i in 0 until w * h) {
            val c = buf[i]
            if (((c shr 16) and 0xFF) > 120 && (c and 0xFF) > 120 && ((c shr 8) and 0xFF) < maxGreen) cnt++
        }
        return cnt.toFloat() / (w * h)
    }

    private fun whiteFraction(bmp: Bitmap, r: IntArray, s: Float): Float {
        val (w, h) = region(bmp, r, s) ?: return 0f
        var cnt = 0
        for (i in 0 until w * h) {
            val c = buf[i]
            if (((c shr 16) and 0xFF) > 200 && ((c shr 8) and 0xFF) > 200 && (c and 0xFF) > 200) cnt++
        }
        return cnt.toFloat() / (w * h)
    }

    /**
     * Полоска: заполненные деления яркие, текущее деление заполняется тусклым цветом, пустое — тёмное.
     * Уровень = самый правый «не тёмный» столбец непрерывной заливки слева (разрывы до 6 px терпим).
     * Заодно считаем насыщенность полоски: под уведомлением телефона она почти серая.
     * Возвращает (эликсир, насыщенность).
     */
    private fun elixirLevel(bmp: Bitmap, s: Float): Pair<Float, Float> {
        val (w, h) = region(bmp, ReplayLayout.elixirBar, s) ?: return 0f to 0f
        val maxGap = max(1, (6 * s).toInt())
        var last = -1
        var gap = 0
        var stopped = false
        var satSum = 0f
        for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (y in 0 until h) {
                val c = buf[y * w + x]
                sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            val mr = sr.toFloat() / h; val mg = sg.toFloat() / h; val mb = sb.toFloat() / h
            satSum += max(mr, max(mg, mb)) - min(mr, min(mg, mb))
            if (stopped) continue
            val lum = (mr + mg + mb) / 3f
            if (lum > 60f) { last = x; gap = 0 } else {
                gap++
                if (gap > maxGap) stopped = true
            }
        }
        return 10f * (last + 1) / w to satSum / w
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
