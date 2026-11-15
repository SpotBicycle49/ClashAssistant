package com.clashassistant.engine

import com.clashassistant.data.cards.CardCatalog
import com.clashassistant.engine.elixir.ElixirEngine
import com.clashassistant.engine.elixir.ElixirRules
import com.clashassistant.model.CardCandidate
import com.clashassistant.model.PlayEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElixirEngineTest {
    private val rules = ElixirRules()
    private val engine = ElixirEngine(rules, CardCatalog::byId)
    private val eps = 1e-3f

    private fun play(id: Long, t: Float, vararg c: Pair<String, Float>) =
        PlayEvent(id, t, c.map { CardCandidate(it.first, it.second) }, confirmed = true)

    @Test fun startsWithFive() {
        val p = engine.compute(emptyList(), 10f).estimateAt(0f)!!
        assertEquals(5f, p.low, eps); assertEquals(5f, p.high, eps)
    }

    @Test fun regeneratesOnePer2_8Seconds() {
        val p = engine.compute(emptyList(), 10f).estimateAt(3f)!!
        assertEquals(5f + 3f / 2.8f, p.expected, eps)
    }

    @Test fun capsAtTen() {
        val p = engine.compute(emptyList(), 30f).estimateAt(30f)!!
        assertEquals(10f, p.high, eps); assertEquals(10f, p.low, eps)
    }

    @Test fun doubleAndTripleGain() {
        assertEquals(1f, rules.gain(120f, 121.4f), eps)
        assertEquals(3f, rules.gain(240f, 242.8f), eps)
        // Отрезок через границу 2:00: 1 с обычного + 1 с двойного
        assertEquals(1f / 2.8f + 2f / 2.8f, rules.gain(119f, 121f), eps)
    }

    @Test fun playSubtractsCost() {
        val tl = engine.compute(listOf(play(1, 0f, "knight" to 1f)), 10f)
        val after = tl.plays.single().after
        assertEquals(2f, after.low, eps); assertEquals(2f, after.high, eps)
        assertFalse(tl.plays.single().inconsistent)
    }

    @Test fun uncertainCandidatesWidenInterval() {
        val tl = engine.compute(listOf(play(1, 20f, "knight" to 0.5f, "musketeer" to 0.5f)), 25f)
        val after = tl.plays.single().after
        assertEquals(6f, after.low, eps)
        assertEquals(7f, after.high, eps)
        assertEquals(6.5f, after.expected, eps)
    }

    @Test fun playLiftsLowerBound() {
        // На старте 5; через 14 с (10 эликсира) сыграли Голема (8) — интервал схлопнут: 2..2
        val tl = engine.compute(listOf(play(1, 14f, "golem" to 1f)), 20f)
        assertEquals(2f, tl.plays.single().after.low, eps)
    }

    @Test fun detectsImpossiblePlay() {
        val tl = engine.compute(listOf(play(1, 0f, "golem" to 1f)), 5f)
        assertTrue(tl.plays.single().inconsistent)
        assertTrue(tl.plays.single().after.low >= 0f)
    }

    @Test fun mirrorCostsPreviousPlusOne() {
        val tl = engine.compute(listOf(play(1, 20f, "knight" to 1f), play(2, 30f, "mirror" to 1f)), 40f)
        assertEquals(4, tl.plays[1].minCost)
        assertEquals(4, tl.plays[1].maxCost)
    }

    @Test fun unknownCardIsSkipped() {
        val tl = engine.compute(listOf(play(1, 5f, "no_such_card" to 1f)), 10f)
        assertEquals(1, tl.skippedEvents)
        assertTrue(tl.plays.isEmpty())
    }
}
