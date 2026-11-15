package com.clashassistant.engine

import com.clashassistant.engine.deck.DeckTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckTrackerTest {
    @Test fun cardReturnsToHandAfterFourPlays() {
        val s = DeckTracker.analyze(listOf("a", "b", "c", "d", "e"))
        val byId = s.cards.associateBy { it.cardId }
        assertEquals(0, byId["a"]!!.playsUntilInHand) // после 4 розыгрышей снова в руке
        assertEquals(1, byId["b"]!!.playsUntilInHand) // следующая
        assertEquals(4, byId["e"]!!.playsUntilInHand) // только что сыграна
        assertEquals(3, s.unknownCount)
        assertFalse(s.cycleComplete)
    }

    @Test fun fullDeckDetected() {
        val s = DeckTracker.analyze(listOf("1", "2", "3", "4", "5", "6", "7", "8", "1"))
        assertTrue(s.cycleComplete)
        assertEquals(0, s.unknownCount)
        assertEquals(2, s.cards.first { it.cardId == "1" }.timesPlayed)
    }

    @Test fun tooManyCardsFlagged() {
        val s = DeckTracker.analyze((1..9).map { it.toString() })
        assertTrue(s.tooManyCards)
    }
}
