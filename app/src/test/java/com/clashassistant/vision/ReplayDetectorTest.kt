package com.clashassistant.vision

import com.clashassistant.model.IdMethod
import com.clashassistant.vision.replay.FrameFeature
import com.clashassistant.vision.replay.ReplayDetection
import com.clashassistant.vision.replay.ReplayDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Регрессионный тест на реальном повторе (запись 720×1576, 3:36, 12 кадров/с после прореживания).
 * replay_features.bin — признаки кадров (время, панель, эликсир, вектор карты «След.»),
 * извлечённые тем же способом, что и в приложении.
 *
 * Эталон проверен вручную по кадрам видео:
 *  розыгрыши: Валькирия, Ведьма, Фейерверкер, Кабан, Ярость, Мегарыцарь, Валькирия, Ведьма, Кабан,
 *  Зеркало, Ярость, Маг(эволюция), Валькирия, Фейерверкер, Ведьма, Зеркало, Маг, Валькирия,
 *  Кабан, Ярость, Фейерверкер.
 */
class ReplayDetectorTest {

    private fun loadFixture(): List<FrameFeature> {
        val bytes = javaClass.classLoader!!.getResourceAsStream("replay_features.bin")!!.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val n = bb.int
        val len = bb.int
        return List(n) {
            val t = bb.float
            val p = bb.float
            val e = bb.float
            val v = FloatArray(len) { bb.get().toFloat() }
            val mean = v.average().toFloat()
            for (i in v.indices) v[i] -= mean
            ReplayDetector.normalize(v)
            FrameFeature(t, p, e, v)
        }
    }

    private val det: ReplayDetection by lazy { ReplayDetector.detect(loadFixture())!! }

    private fun one(i: Int): Int = det.plays[i].clusterCandidates.single()

    @Test fun findsAll21Plays() {
        assertEquals(21, det.plays.size)
        assertTrue(det.plays.none { it.method == IdMethod.ABILITY })
    }

    @Test fun costsMatchReplay() {
        assertEquals(
            listOf(4, 5, 3, 4, 2, 7, 4, 5, 4, 5, 2, 5, 4, 3, 5, 6, 5, 4, 4, 2, 3),
            det.plays.map { it.cost },
        )
    }

    @Test fun matchStartNearTimer() {
        // По таймеру 3:00 приходится на 8,89 с видео.
        assertTrue("start=${det.matchStartVideoSec}", det.matchStartVideoSec in 8.5f..9.5f)
    }

    @Test fun cycleIdentityStructure() {
        val valkyrie = one(0); val witch = one(1); val firecracker = one(2); val hog = one(3)
        val rage = one(4); val megaKnight = one(5); val mirror = one(9); val mage = one(16); val mageEvo = one(11)
        listOf(6, 12, 17).forEach { assertEquals(valkyrie, one(it)) }
        listOf(7, 14).forEach { assertEquals(witch, one(it)) }
        assertEquals(firecracker, one(13))
        listOf(8, 18).forEach { assertEquals(hog, one(it)) }
        listOf(10, 19).forEach { assertEquals(rage, one(it)) }
        assertEquals(mirror, one(15))
        // 9 разных кластеров: 8 карт + эволюция мага выглядит как отдельная карта
        assertEquals(9, setOf(valkyrie, witch, firecracker, hog, rage, megaKnight, mirror, mage, mageEvo).size)
        assertTrue(mirror in det.variableCostClusters)
    }

    @Test fun methods() {
        (0..17).forEach { assertEquals("розыгрыш ${it + 1}", IdMethod.CYCLE, det.plays[it].method) }
        (18..20).forEach { assertEquals("розыгрыш ${it + 1}", IdMethod.HAND, det.plays[it].method) }
        // Последний: Фейерверкер (3) или Зеркало после Ярости (2+1) — обе версии допустимы, истинная среди них.
        assertTrue(one(2) in det.plays[20].clusterCandidates)
    }

    @Test fun elixirSeriesAndStart() {
        assertTrue(det.elixirSeries.size > 300)
        assertTrue(det.elixirSeries.all { it.elixir in 0f..10f })
        val start = det.startElixir
        assertNotNull(start)
        assertTrue("startElixir=$start", start!! in 5.5f..6.5f)
    }
}
