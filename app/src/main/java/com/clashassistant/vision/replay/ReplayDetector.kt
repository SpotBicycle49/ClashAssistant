package com.clashassistant.vision.replay

import com.clashassistant.model.IdMethod
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Признаки одного кадра видео. [next] — нормированный вектор картинки карты «След.» соперника. */
class FrameFeature(
    val timeSec: Float,
    /** Доля «фиолетовых» пикселей в значке эликсира соперника: >0.08 — панель повтора видна. */
    val panel: Float,
    /** Эликсир соперника по полоске (0..10, с дробной частью). */
    val elixir: Float,
    val next: FloatArray,
)

/** Отрезок времени, когда в «След.» стабильно видна одна и та же карта (кластер). */
data class NextRun(val startSec: Float, val endSec: Float, val cluster: Int)

data class ElixirSample(val videoTimeSec: Float, val elixir: Float)

class ClusterInfo(
    val id: Int,
    val prototype: FloatArray,
    /** Момент видео, где карта лучше всего видна, — для миниатюры. */
    val representativeTimeSec: Float,
    val frames: Int,
)

data class DetectedPlay(
    val videoTimeSec: Float,
    /** Стоимость по падению эликсира. */
    val cost: Int,
    val rawCost: Float,
    val elixirBefore: Float,
    val elixirAfter: Float,
    /** Кластеры-кандидаты: одна карта — определена точно, несколько — неоднозначно, пусто — неизвестно. */
    val clusterCandidates: List<Int>,
    val method: IdMethod,
)

data class ReplayDetection(
    val matchStartVideoSec: Float,
    val panelFirstSec: Float,
    val panelLastSec: Float,
    val plays: List<DetectedPlay>,
    val runs: List<NextRun>,
    val clusters: List<ClusterInfo>,
    /** Стоимость карты (кластера), если она одинакова во всех розыгрышах. */
    val clusterCosts: Map<Int, Int>,
    /** Кластеры с меняющейся стоимостью (например, Зеркало). */
    val variableCostClusters: Set<Int>,
    val elixirSeries: List<ElixirSample>,
    /** Эликсир соперника в момент 3:00 по данным повтора (если удалось оценить). */
    val startElixir: Float?,
)

/**
 * Распознавание розыгрышей соперника в ПОВТОРЕ Clash Royale (вид, где видна рука соперника).
 *
 * 1. Розыгрыш = резкое падение эликсира соперника; стоимость = величина падения + восстановление.
 * 2. Карта «След.» меняется при каждом розыгрыше. Её стабильные состояния группируются в кластеры
 *    (одна карта колоды = один кластер; у карты с эволюцией может быть два).
 * 3. По правилу цикла сыгранная карта снова появляется в «След.» ровно через 3 розыгрыша:
 *    X(i) = «След.» после розыгрыша i+3. Последние 3 розыгрыша определяются по составу руки и стоимости.
 *
 * Все окна заданы в секундах, поэтому алгоритм не зависит от частоты кадров записи.
 * Логика проверена на реальном повторе (тест ReplayDetectorTest).
 */
object ReplayDetector {
    const val PANEL_MIN = 0.08f
    private const val VALID_WIN = 0.7f
    private const val MED_WIN = 0.1f
    private const val DROP_WIN = 0.6f
    private const val DROP_MIN = 0.7f
    private const val BEFORE_WIN = 0.15f
    private const val AFTER_DT = 0.25f
    private const val STABLE_DT = 0.12f
    private const val STABLE_CORR = 0.92f
    const val CLUSTER_CORR = 0.85f
    private const val RUN_GAP = 1.0f
    private const val RUN_MIN = 0.25f
    /** Панель повтора появляется примерно через 2,86 с после 3:00 (замерено по таймеру). */
    const val PANEL_TO_START = 2.86f
    private const val SEC_PER_ELIXIR = 2.8f
    private const val DOUBLE_AT = 120f

    fun detect(frames: List<FrameFeature>): ReplayDetection? {
        val n = frames.size
        if (n < 10) return null
        val t = FloatArray(n) { frames[it].timeSec }
        val on = BooleanArray(n) { frames[it].panel > PANEL_MIN }
        val first = on.indexOfFirst { it }
        if (first < 0) return null
        val last = on.indexOfLast { it }
        val start = t[first] - PANEL_TO_START

        // Кадр «надёжен», если панель видна весь интервал ±VALID_WIN.
        val offPrefix = IntArray(n + 1)
        for (i in 0 until n) offPrefix[i + 1] = offPrefix[i] + if (on[i]) 0 else 1
        val valid = BooleanArray(n) { i ->
            val lo = lowerBound(t, t[i] - VALID_WIN)
            val hi = upperBound(t, t[i] + VALID_WIN)
            offPrefix[hi] - offPrefix[lo] == 0
        }
        if (valid.none { it }) return null

        // Медианное сглаживание эликсира.
        val em = FloatArray(n) { i ->
            median(frames, lowerBound(t, t[i] - MED_WIN), upperBound(t, t[i] + MED_WIN))
        }

        val drops = findDrops(t, em, valid, start)
        val (clusters, runs) = clusterNext(frames, t, valid)
        val identified = identify(drops, runs)

        val series = ArrayList<ElixirSample>()
        var nextSample = t[first]
        for (i in 0 until n) {
            if (valid[i] && t[i] >= nextSample) {
                series += ElixirSample(t[i], em[i])
                nextSample = t[i] + 0.5f
            }
        }
        val firstDropT = drops.firstOrNull()?.t ?: Float.MAX_VALUE
        val startElixir = series.firstOrNull { it.videoTimeSec < firstDropT && it.elixir < 9.9f }?.let {
            (it.elixir - (it.videoTimeSec - start) / SEC_PER_ELIXIR).coerceIn(0f, 10f)
        }

        return ReplayDetection(
            matchStartVideoSec = start,
            panelFirstSec = t[first],
            panelLastSec = t[last],
            plays = identified.plays,
            runs = runs,
            clusters = clusters,
            clusterCosts = identified.costs,
            variableCostClusters = identified.variable,
            elixirSeries = series,
            startElixir = startElixir,
        )
    }

    // ---------- 1. Падения эликсира ----------

    private class Drop(val t: Float, val cost: Int, val raw: Float, val before: Float, val after: Float)

    private fun findDrops(t: FloatArray, em: FloatArray, valid: BooleanArray, start: Float): List<Drop> {
        val n = t.size
        val drops = ArrayList<Drop>()
        var i = 0
        while (i < n - 1) {
            if (!valid[i]) { i++; continue }
            var j = i + 1
            while (j < n && t[j] - t[i] <= DROP_WIN && valid[j]) j++
            if (j - i > 1) {
                var k = i
                for (m in i until j) if (em[m] < em[k]) k = m
                if (em[i] - em[k] > DROP_MIN) {
                    val b0 = lowerBound(t, t[i] - BEFORE_WIN)
                    var before = em[b0]
                    for (m in b0..i) before = max(before, em[m])
                    val a = min(n - 1, lowerBound(t, t[k] + AFTER_DT))
                    val after = em[a]
                    val elapsed = t[i] - start
                    val rate = (1f / SEC_PER_ELIXIR) * (if (elapsed >= DOUBLE_AT) 2f else 1f)
                    val raw = before - after + rate * (t[a] - t[i])
                    drops += Drop(t[i], raw.roundToInt(), raw, before, after)
                    i = a + 1
                    continue
                }
            }
            i++
        }
        return drops
    }

    // ---------- 2. Кластеры карты «След.» ----------

    private fun clusterNext(frames: List<FrameFeature>, t: FloatArray, valid: BooleanArray): Pair<List<ClusterInfo>, List<NextRun>> {
        val n = t.size
        val protos = ArrayList<FloatArray>()
        val counts = ArrayList<Int>()
        val lab = IntArray(n) { -1 }
        for (i in 0 until n) {
            if (!valid[i]) continue
            val p = upperBound(t, t[i] - STABLE_DT) - 1
            val q = lowerBound(t, t[i] + STABLE_DT)
            if (p < 0 || q >= n) continue
            val v = frames[i].next
            if (dot(v, frames[p].next) < STABLE_CORR || dot(v, frames[q].next) < STABLE_CORR) continue
            var best = -1
            var bestSim = -2f
            for (c in protos.indices) {
                val s = dot(v, protos[c])
                if (s > bestSim) { bestSim = s; best = c }
            }
            val c: Int
            if (best >= 0 && bestSim >= CLUSTER_CORR) {
                c = best
                val pr = protos[c]
                for (d in pr.indices) pr[d] = pr[d] * 0.95f + v[d] * 0.05f
                normalize(pr)
                counts[c] = counts[c] + 1
            } else {
                protos += v.copyOf()
                counts += 1
                c = protos.size - 1
            }
            lab[i] = c
        }

        // Отрезки одинаковых состояний
        val raw = ArrayList<FloatArray>() // [t0, t1, cluster]
        for (i in 0 until n) {
            if (lab[i] < 0) continue
            val lastRun = raw.lastOrNull()
            if (lastRun != null && lastRun[2].toInt() == lab[i] && t[i] - lastRun[1] < RUN_GAP) {
                lastRun[1] = t[i]
            } else {
                raw += floatArrayOf(t[i], t[i], lab[i].toFloat())
            }
        }
        val runs = ArrayList<NextRun>()
        for (r in raw) {
            if (r[1] - r[0] < RUN_MIN) continue
            val c = r[2].toInt()
            val prev = runs.lastOrNull()
            if (prev != null && prev.cluster == c) runs[runs.size - 1] = prev.copy(endSec = r[1])
            else runs += NextRun(r[0], r[1], c)
        }

        val clusters = protos.indices.map { c ->
            val longest = runs.filter { it.cluster == c }.maxByOrNull { it.endSec - it.startSec }
            val rep = longest?.let { (it.startSec + it.endSec) / 2f }
                ?: t[lab.indexOfFirst { it == c }.coerceAtLeast(0)]
            ClusterInfo(c, protos[c], rep, counts[c])
        }
        return clusters to runs
    }

    // ---------- 3. Какая карта сыграна ----------

    private class Identified(val plays: List<DetectedPlay>, val costs: Map<Int, Int>, val variable: Set<Int>)

    private fun identify(drops: List<Drop>, runs: List<NextRun>): Identified {
        val n = drops.size
        fun runAfter(after: Float, limit: Float): Int? =
            runs.firstOrNull { it.startSec > after && it.startSec < limit }?.cluster
        fun runBefore(time: Float): Int? = runs.lastOrNull { it.startSec < time }?.cluster

        val nextAfter = Array(n) { k -> runAfter(drops[k].t, if (k + 1 < n) drops[k + 1].t else Float.MAX_VALUE) }
        val nextBefore = Array(n) { k -> runBefore(drops[k].t) }
        // Способность чемпиона и т.п.: эликсир потрачен, а «След.» не изменилась.
        val isAbility = BooleanArray(n) { k -> nextAfter[k] != null && nextAfter[k] == nextBefore[k] }

        val cardIdx = (0 until n).filter { !isAbility[it] }
        val m = cardIdx.size
        val x = arrayOfNulls<List<Int>>(m)
        val method = arrayOfNulls<IdMethod>(m)

        for (i in 0 until m) {
            if (i + 3 < m) {
                val c = nextAfter[cardIdx[i + 3]]
                if (c != null) { x[i] = listOf(c); method[i] = IdMethod.CYCLE }
            }
        }

        // Стоимость кластеров по уже определённым розыгрышам
        val costLists = HashMap<Int, MutableList<Int>>()
        for (i in 0 until m) {
            val c = x[i]?.singleOrNull() ?: continue
            costLists.getOrPut(c) { mutableListOf() } += drops[cardIdx[i]].cost
        }
        val costs = HashMap<Int, Int>()
        val variable = HashSet<Int>()
        for ((c, list) in costLists) {
            if (list.toSet().size == 1) costs[c] = list[0] else variable += c
        }
        val deck = runs.map { it.cluster }.toSet()

        for (i in 0 until m) {
            if (x[i] != null) continue
            val queue = HashSet<Int>()
            val nPrev = if (i >= 1) nextAfter[cardIdx[i - 1]] else nextBefore[cardIdx[0]]
            if (nPrev != null) queue += nPrev
            for (j in (i - 3) until i) {
                if (j >= 0) x[j]?.singleOrNull()?.let { queue += it }
            }
            val hand = deck - queue
            val obs = drops[cardIdx[i]].cost
            val prevCost = if (i >= 1) drops[cardIdx[i - 1]].cost else null
            var cand = hand.filter { c ->
                costs[c] == obs || (c in variable && prevCost != null && obs == prevCost + 1)
            }
            if (cand.isEmpty()) cand = hand.filter { it !in costs && it !in variable }
            x[i] = cand.sorted()
            method[i] = if (cand.isEmpty()) IdMethod.UNKNOWN else IdMethod.HAND
        }

        val plays = ArrayList<DetectedPlay>()
        var ci = 0
        for (k in 0 until n) {
            val d = drops[k]
            if (isAbility[k]) {
                plays += DetectedPlay(d.t, d.cost, d.raw, d.before, d.after, emptyList(), IdMethod.ABILITY)
            } else {
                plays += DetectedPlay(d.t, d.cost, d.raw, d.before, d.after, x[ci] ?: emptyList(), method[ci] ?: IdMethod.UNKNOWN)
                ci++
            }
        }
        return Identified(plays, costs, variable)
    }

    // ---------- Вспомогательное ----------

    /** Первый индекс с a[i] >= x. */
    fun lowerBound(a: FloatArray, x: Float): Int {
        var lo = 0; var hi = a.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (a[mid] < x) lo = mid + 1 else hi = mid }
        return lo
    }

    /** Первый индекс с a[i] > x. */
    fun upperBound(a: FloatArray, x: Float): Int {
        var lo = 0; var hi = a.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (a[mid] <= x) lo = mid + 1 else hi = mid }
        return lo
    }

    private fun median(frames: List<FrameFeature>, from: Int, to: Int): Float {
        val k = to - from
        if (k <= 0) return frames[from.coerceAtMost(frames.size - 1)].elixir
        val buf = FloatArray(k) { frames[from + it].elixir }
        buf.sort()
        return if (k % 2 == 1) buf[k / 2] else (buf[k / 2 - 1] + buf[k / 2]) / 2f
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    fun normalize(v: FloatArray) {
        var s = 0f
        for (x in v) s += x * x
        val norm = sqrt(s) + 1e-6f
        for (i in v.indices) v[i] /= norm
    }
}
