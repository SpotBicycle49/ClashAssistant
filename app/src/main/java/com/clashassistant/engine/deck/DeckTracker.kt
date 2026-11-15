package com.clashassistant.engine.deck

/**
 * Состояние одной известной карты колоды соперника.
 * [playsUntilInHand] — сколько ещё розыгрышей нужно, чтобы карта вернулась в руку.
 * 0 — карта уже в руке, 1 — карта «следующая».
 */
data class DeckCardState(
    val cardId: String,
    val timesPlayed: Int,
    val lastPlayIndex: Int,
    val playsUntilInHand: Int,
)

data class DeckState(
    val cards: List<DeckCardState>,
    val totalPlays: Int,
    /** Сколько карт колоды ещё не встречалось. */
    val unknownCount: Int,
    /** Все 8 карт известны — цикл восстановлен полностью. */
    val cycleComplete: Boolean,
    /** Разных карт больше 8 — значит, где-то ошибка распознавания или разметки. */
    val tooManyCards: Boolean,
) {
    companion object {
        val EMPTY = DeckState(emptyList(), 0, DeckTracker.DECK_SIZE, false, false)
    }
}

/**
 * Восстановление колоды и цикла.
 * Правила игры: в колоде 8 карт, в руке 4. Сыгранная карта уходит в конец очереди
 * из 4 карт и возвращается в руку после 4 следующих розыгрышей.
 */
object DeckTracker {
    const val DECK_SIZE = 8
    const val HAND_SIZE = 4
    private const val QUEUE_SIZE = DECK_SIZE - HAND_SIZE

    fun analyze(playedCardIds: List<String>): DeckState {
        if (playedCardIds.isEmpty()) return DeckState.EMPTY
        val total = playedCardIds.size
        val lastIndex = HashMap<String, Int>()
        val count = HashMap<String, Int>()
        playedCardIds.forEachIndexed { i, id ->
            lastIndex[id] = i
            count[id] = (count[id] ?: 0) + 1
        }
        val cards = lastIndex.map { (id, idx) ->
            val playsSince = total - 1 - idx
            DeckCardState(
                cardId = id,
                timesPlayed = count[id] ?: 0,
                lastPlayIndex = idx,
                playsUntilInHand = (QUEUE_SIZE - playsSince).coerceAtLeast(0),
            )
        }.sortedWith(compareBy<DeckCardState> { it.playsUntilInHand }.thenBy { it.lastPlayIndex })

        val distinct = cards.size
        return DeckState(
            cards = cards,
            totalPlays = total,
            unknownCount = (DECK_SIZE - distinct).coerceAtLeast(0),
            cycleComplete = distinct == DECK_SIZE,
            tooManyCards = distinct > DECK_SIZE,
        )
    }
}
