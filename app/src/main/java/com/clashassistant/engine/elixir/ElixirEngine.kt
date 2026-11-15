package com.clashassistant.engine.elixir

import com.clashassistant.model.Card
import com.clashassistant.model.PlayEvent
import com.clashassistant.model.SpecialRule
import kotlin.math.max
import kotlin.math.min

/** Оценка эликсира соперника в момент времени: интервал [low; high] и ожидаемое значение. */
data class ElixirPoint(val timeSec: Float, val low: Float, val high: Float, val expected: Float)

data class ResolvedPlay(
    val event: PlayEvent,
    val minCost: Int,
    val maxCost: Int,
    val expectedCost: Float,
    /** true, если по модели у соперника не могло быть столько эликсира — вероятна ошибка времени или карты. */
    val inconsistent: Boolean,
    val before: ElixirPoint,
    val after: ElixirPoint,
)

data class ElixirTimeline(
    val points: List<ElixirPoint>,
    val plays: List<ResolvedPlay>,
    val endTimeSec: Float,
    /** События, для которых не удалось определить стоимость (неизвестная карта). */
    val skippedEvents: Int,
) {
    /** Оценка в момент [t] (последняя точка не позже t). */
    fun estimateAt(t: Float): ElixirPoint? = points.lastOrNull { it.timeSec <= t } ?: points.firstOrNull()

    companion object {
        val EMPTY = ElixirTimeline(emptyList(), emptyList(), 0f, 0)
    }
}

/**
 * Математический движок эликсира.
 *
 * Модель: вместо одного числа хранится интервал возможных значений.
 *  - Восстановление увеличивает обе границы (с потолком maxElixir).
 *  - Розыгрыш карты стоимостью c означает, что эликсира было не меньше c:
 *    нижняя граница поднимается до c, затем обе границы уменьшаются на c.
 *  - Если распознавание неуверенное (несколько кандидатов), используются минимальная
 *    и максимальная стоимость кандидатов — интервал расширяется, ожидаемое значение
 *    считается по вероятностям.
 *
 * Не учитывается (пока): эликсир от Сборщика эликсира, точное время «утечки» на 10.
 */
class ElixirEngine(
    private val rules: ElixirRules,
    private val cardLookup: (String) -> Card?,
) {

    private data class CostRange(val min: Int, val max: Int, val expected: Float)

    /**
     * @param events розыгрыши, время — в секундах ОТ НАЧАЛА БОЯ.
     * @param endTimeSec до какого момента строить график.
     * @param stepSec шаг точек графика.
     */
    fun compute(events: List<PlayEvent>, endTimeSec: Float, stepSec: Float = 0.5f): ElixirTimeline {
        val sorted = events.filter { it.timeSec >= 0f }.sortedBy { it.timeSec }
        val end = max(endTimeSec, sorted.lastOrNull()?.timeSec ?: 0f)
        val cap = rules.maxElixir

        var t = 0f
        var low = rules.startElixir
        var high = rules.startElixir
        var exp = rules.startElixir
        val points = ArrayList<ElixirPoint>()
        val plays = ArrayList<ResolvedPlay>()
        var skipped = 0
        var previousCost: CostRange? = null

        points += ElixirPoint(0f, low, high, exp)

        fun advanceTo(target: Float) {
            while (t < target - 1e-4f) {
                val next = min(target, (kotlin.math.floor(t / stepSec + 1e-4f) + 1f) * stepSec)
                val g = rules.gain(t, next)
                low = min(cap, low + g)
                high = min(cap, high + g)
                exp = min(cap, exp + g)
                t = next
                points += ElixirPoint(t, low, high, exp)
            }
        }

        for (event in sorted) {
            advanceTo(event.timeSec)
            val cost = resolveCost(event, previousCost)
            if (cost == null) {
                skipped++
                continue
            }
            val before = ElixirPoint(t, low, high, exp)
            val inconsistent = high + 0.05f < cost.min

            if (inconsistent) {
                // Модель противоречит наблюдению: соперник точно имел не меньше min.
                high = max(high, cost.min.toFloat())
            }
            low = max(low, cost.min.toFloat())
            exp = max(exp, cost.expected).coerceIn(low, high)

            low = max(0f, low - cost.max)
            high = max(0f, high - cost.min)
            exp = (exp - cost.expected).coerceIn(low, high)

            val after = ElixirPoint(t, low, high, exp)
            points += after
            plays += ResolvedPlay(event, cost.min, cost.max, cost.expected, inconsistent, before, after)
            previousCost = cost
        }
        advanceTo(end)
        return ElixirTimeline(points, plays, end, skipped)
    }

    private fun resolveCost(event: PlayEvent, previous: CostRange?): CostRange? {
        // Стоимость, увиденная в повторе, точнее любой оценки по базе карт.
        event.observedCost?.let { c -> return CostRange(c, c, c.toFloat()) }
        val options = event.candidates.mapNotNull { cand ->
            val card = cardLookup(cand.cardId) ?: return@mapNotNull null
            if (cand.probability <= 0f) return@mapNotNull null
            val range = when (card.special) {
                SpecialRule.MIRROR ->
                    // Зеркало повторяет предыдущую карту за +1. Без предыдущей — неизвестно.
                    if (previous == null) CostRange(1, 10, 5.5f)
                    else CostRange(
                        min(10, previous.min + 1),
                        min(10, previous.max + 1),
                        min(10f, previous.expected + 1f),
                    )
                SpecialRule.NONE -> CostRange(card.cost, card.cost, card.cost.toFloat())
            }
            range to cand.probability
        }
        if (options.isEmpty()) return null
        val totalP = options.sumOf { it.second.toDouble() }.toFloat()
        return CostRange(
            min = options.minOf { it.first.min },
            max = options.maxOf { it.first.max },
            expected = options.sumOf { (it.first.expected * it.second).toDouble() }.toFloat() / totalP,
        )
    }
}
