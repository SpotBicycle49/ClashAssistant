package com.clashassistant.model

enum class CardType { TROOP, SPELL, BUILDING, CHAMPION }

/** Особые правила стоимости. MIRROR: стоимость = стоимость предыдущей карты + 1. */
enum class SpecialRule { NONE, MIRROR }

data class Card(
    val id: String,
    val name: String,
    /** Стоимость в эликсире. Для Зеркала = -1 (вычисляется движком). */
    val cost: Int,
    val type: CardType,
    val special: SpecialRule = SpecialRule.NONE,
)

/** Один вариант распознавания карты с вероятностью 0..1. */
data class CardCandidate(val cardId: String, val probability: Float)

/**
 * Розыгрыш карты соперником.
 * [timeSec] — время в секундах. В ViewModel это время ВИДЕО; в движок эликсира
 * передаётся уже пересчитанное время МАТЧА (от начала боя).
 * [candidates] — один кандидат с вероятностью 1 при ручной разметке,
 * несколько — при неуверенном автоматическом распознавании.
 */
data class PlayEvent(
    val id: Long,
    val timeSec: Float,
    val candidates: List<CardCandidate>,
    val confirmed: Boolean,
) {
    val topCandidate: CardCandidate? get() = candidates.maxByOrNull { it.probability }
}
