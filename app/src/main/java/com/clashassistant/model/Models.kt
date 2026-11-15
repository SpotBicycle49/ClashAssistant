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

/** Как определена карта розыгрыша. */
enum class IdMethod {
    /** Отмечено вручную. */
    MANUAL,
    /** По циклу колоды: карта снова появилась в «След.» через 3 розыгрыша. */
    CYCLE,
    /** По составу руки и стоимости (последние розыгрыши матча). */
    HAND,
    /** Эликсир потрачен без смены карты — способность чемпиона и т.п. */
    ABILITY,
    UNKNOWN,
}

/**
 * Розыгрыш карты соперником.
 * [timeSec] — время в секундах. В ViewModel это время ВИДЕО; в движок эликсира
 * передаётся уже пересчитанное время МАТЧА (от начала боя).
 * [candidates] — один кандидат при уверенном определении, несколько — при неоднозначном.
 * cardId может быть "cluster:N" (карта распознана, но ещё не подписана) или "ability".
 */
data class PlayEvent(
    val id: Long,
    val timeSec: Float,
    val candidates: List<CardCandidate>,
    val confirmed: Boolean,
    /** Стоимость, увиденная в повторе (по падению эликсира). Если задана — важнее стоимости из базы. */
    val observedCost: Int? = null,
    /** Номер карты соперника из автораспознавания. */
    val clusterId: Int? = null,
    val method: IdMethod = IdMethod.MANUAL,
) {
    val topCandidate: CardCandidate? get() = candidates.maxByOrNull { it.probability }
    val isAbility: Boolean get() = method == IdMethod.ABILITY
}

/** Карта колоды соперника, найденная автораспознаванием (по карте «След.»). */
class OpponentCluster(
    val id: Int,
    /** Признаки картинки — для автоматической подписи в следующих матчах. */
    val signature: FloatArray,
    /** Миниатюра (PNG). */
    val thumbnail: ByteArray?,
    /** Подпись пользователя или из библиотеки; null — не подписана. */
    val cardId: String?,
    val cost: Int?,
    val variableCost: Boolean,
    val timesPlayed: Int,
) {
    fun copy(cardId: String? = this.cardId) =
        OpponentCluster(id, signature, thumbnail, cardId, cost, variableCost, timesPlayed)
}

object CardIds {
    const val ABILITY = "ability"
    private const val CLUSTER_PREFIX = "cluster:"

    fun cluster(id: Int) = "$CLUSTER_PREFIX$id"
    fun clusterOf(cardId: String): Int? =
        if (cardId.startsWith(CLUSTER_PREFIX)) cardId.removePrefix(CLUSTER_PREFIX).toIntOrNull() else null
}
