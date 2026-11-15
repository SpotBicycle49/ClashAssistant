package com.clashassistant.engine.elixir

/**
 * Правила восстановления эликсира. Все значения настраиваются в разделе «Настройки»,
 * потому что Supercell может их менять. Время — в секундах от начала боя.
 *
 * Значения по умолчанию (обычный бой 1×1):
 *  - старт 5, максимум 10;
 *  - 1 эликсир за 2,8 с до 2:00 боя;
 *  - двойной эликсир (×2) с 2:00 до [tripleStartSec];
 *  - тройной эликсир (×3) с [tripleStartSec] (последняя минута овертайма).
 */
data class ElixirRules(
    val startElixir: Float = 5f,
    val maxElixir: Float = 10f,
    val secondsPerElixir: Float = 2.8f,
    val doubleStartSec: Float = 120f,
    val tripleStartSec: Float = 240f,
) {
    /** Скорость восстановления (эликсир/с) в момент [t]. */
    fun rateAt(t: Float): Float {
        val base = 1f / secondsPerElixir
        return when {
            t < doubleStartSec -> base
            t < tripleStartSec -> base * 2f
            else -> base * 3f
        }
    }

    /** Сколько эликсира восстановится за отрезок [from, to] без учёта потолка. */
    fun gain(from: Float, to: Float): Float {
        if (to <= from) return 0f
        val boundaries = listOf(doubleStartSec, tripleStartSec).filter { it > from && it < to }.sorted()
        var total = 0f
        var t = from
        for (b in boundaries + to) {
            total += rateAt(t) * (b - t)
            t = b
        }
        return total
    }
}
