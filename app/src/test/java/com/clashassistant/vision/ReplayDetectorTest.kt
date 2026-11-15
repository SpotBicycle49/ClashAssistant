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
 * Регрессионные тесты на 4 реальных повторах (запись экрана 720×1576, прорежено до 12 кадров/с).
 * replay_rN.bin — признаки кадров (время, панель, эликсир, цвет значка «След.», вектор «След.»),
 * извлечённые тем же способом, что и в приложении (включая формат кадров RGB_565).
 *
 * Эталон каждого розыгрыша проверен вручную по кадрам: какая карта ушла из руки соперника.
 * Обозначения карт — только для теста. Эволюционный вид карты выглядит в «След.» как отдельная
 * карта (суффикс «Эво»/«Свет»), это ожидаемо: пользователь подписывает обе версии одинаково.
 */
class ReplayDetectorTest {

    private fun load(name: String): List<FrameFeature> {
        val bytes = javaClass.classLoader!!.getResourceAsStream("replay_$name.bin")!!.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val n = bb.int
        val len = bb.int
        return List(n) {
            val t = bb.float
            val p = bb.float
            val e = bb.float
            val col = bb.float
            val v = FloatArray(len) { bb.get().toFloat() }
            val mean = v.average().toFloat()
            for (i in v.indices) v[i] -= mean
            ReplayDetector.normalize(v)
            FrameFeature(t, p, e, v, col)
        }
    }

    private val cache = HashMap<String, ReplayDetection>()
    private fun detect(name: String) = cache.getOrPut(name) { ReplayDetector.detect(load(name))!! }

    private fun check(name: String, truth: List<String>, costs: List<Int?>, methods: String, virtual: Set<Int> = emptySet()) {
        val det = detect(name)
        val plays = det.plays.filter { it.method != IdMethod.ABILITY }
        assertEquals("$name: способности", 0, det.plays.size - plays.size)
        assertEquals("$name: число розыгрышей", truth.size, plays.size)
        assertEquals("$name: стоимости", costs, plays.map { it.cost })
        assertEquals(
            "$name: способы определения",
            methods,
            plays.joinToString("") { when (it.method) { IdMethod.CYCLE -> "C"; IdMethod.HAND -> "H"; else -> "?" } },
        )
        assertEquals("$name: розыгрыши без видимого падения", virtual, plays.withIndex().filter { it.value.virtual }.map { it.index + 1 }.toSet())

        // Одна и та же карта — один кластер; разные карты — разные кластеры.
        val clusterOf = HashMap<String, Int>()
        for ((i, p) in plays.withIndex()) {
            val c = p.clusterCandidates.singleOrNull() ?: continue
            val prev = clusterOf.putIfAbsent(truth[i], c)
            if (prev != null) assertEquals("$name #${i + 1} ${truth[i]}", prev, c)
        }
        assertEquals("$name: разные карты в разных кластерах", clusterOf.size, clusterOf.values.toSet().size)
        // Неоднозначные розыгрыши: истинная карта среди кандидатов.
        for ((i, p) in plays.withIndex()) {
            if (p.clusterCandidates.size > 1) {
                val c = clusterOf[truth[i]]
                assertTrue("$name #${i + 1}: ${truth[i]} среди ${p.clusterCandidates}", c != null && c in p.clusterCandidates)
            }
        }
    }

    @Test fun replay0_witchMirrorDeck() {
        check(
            "r0",
            listOf("Валькирия", "Ведьма", "Фейерверкер", "Кабан", "Ярость", "Мегарыцарь", "Валькирия", "Ведьма", "Кабан",
                "Зеркало", "Ярость", "МагЭво", "Валькирия", "Фейерверкер", "Ведьма", "Зеркало", "Маг", "Валькирия",
                "Кабан", "Ярость", "Фейерверкер"),
            listOf(4, 5, 3, 4, 2, 7, 4, 5, 4, 5, 2, 5, 4, 3, 5, 6, 5, 4, 4, 2, 3),
            "CCCCCCCCCCCCCCCCCCHHH",
        )
        val det = detect("r0")
        assertTrue("start=${det.matchStartVideoSec}", det.matchStartVideoSec in 8.5f..9.5f)
        assertTrue(det.elixirSeries.size > 300)
        val start = det.startElixir
        assertNotNull(start)
        assertTrue("startElixir=$start", start!! in 5.5f..6.5f)
        // Зеркало: стоимость меняется (копия предыдущей карты +1)
        assertEquals(1, det.variableCostClusters.size)
    }

    @Test fun replay1_cheapCycleDeck() {
        // Почти все карты по 3 эликсира: последние розыгрыши неоднозначны, но истинная карта среди кандидатов.
        check(
            "r1",
            listOf("Шахтёр", "Женщина", "Дротик", "ЛедДух", "Пушка", "Шахтёр", "Бревно", "Женщина", "ЛедДух", "Гоблин",
                "Шахтёр", "Пушка", "Женщина"),
            listOf(3, 3, 3, 1, 3, 3, 2, 3, 1, 3, 3, 3, 3),
            "CCCCCCCCCCHHH",
        )
        val start = detect("r1").matchStartVideoSec
        assertTrue("start=$start", start in 3.0f..4.2f)
    }

    @Test fun replay2_menuBeforeReplayAndVariants() {
        // Запись начинается в главном меню — оно не должно приниматься за повтор.
        check(
            "r2",
            listOf("Женщина", "ЛедДух", "Валькирия", "ВедьмаСвет", "Кабан", "ЖенщинаЭво", "Дротик", "ЛедДух", "Ведьма",
                "Кабан", "Женщина", "Валькирия", "Дротик", "ЛедДух", "Кабан", "Женщина"),
            listOf(3, 1, 4, 5, 4, 3, 3, 1, 5, 4, 3, 4, 3, 1, 4, 3),
            "CCCCCCCCCCCCCHHH",
        )
        val det = detect("r2")
        assertTrue("panel=${det.panelFirstSec}", det.panelFirstSec > 30f)
        assertTrue("start=${det.matchStartVideoSec}", det.matchStartVideoSec in 27.8f..29.0f)
    }

    @Test fun replay3_notificationsCoverElixirBar() {
        // Два уведомления закрывают полоску эликсира. Под вторым соперник сыграл карту:
        // её находим по смене «След.», стоимость неизвестна.
        check(
            "r3",
            listOf("Бочка", "Дротик", "Бревно", "Шахтёр", "Гоблины", "МиниПекка", "Дротик", "БочкаЭво", "Шахтёр",
                "Гоблины", "МиниПекка", "Дротик", "Дракончик", "Гоблин3", "Гоблины", "Шахтёр", "МиниПекка", "Дротик"),
            listOf(3, 3, 2, 3, 2, 4, 3, 3, 3, 2, 4, 3, null, 3, 2, 3, 4, 3),
            "CCCCCCCCCCCCCCCHHH",
            virtual = setOf(13),
        )
        val start = detect("r3").matchStartVideoSec
        assertTrue("start=$start", start in 11.0f..12.6f)
    }
}
