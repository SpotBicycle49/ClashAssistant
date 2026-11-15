package com.clashassistant.data.cards

import com.clashassistant.model.Card
import com.clashassistant.model.CardType
import com.clashassistant.model.CardType.BUILDING
import com.clashassistant.model.CardType.CHAMPION
import com.clashassistant.model.CardType.SPELL
import com.clashassistant.model.CardType.TROOP
import com.clashassistant.model.SpecialRule

/**
 * База карт: название и стоимость в эликсире.
 *
 * ВАЖНО: список не исчерпывающий. Supercell регулярно добавляет карты и иногда
 * меняет стоимость. Если карты нет или стоимость устарела — исправьте строку здесь.
 * Изображения карт в приложение намеренно не встроены (это собственность Supercell).
 */
object CardCatalog {

    private fun c(id: String, name: String, cost: Int, type: CardType) = Card(id, name, cost, type)

    val all: List<Card> = listOf(
        // Войска
        c("knight", "Knight", 3, TROOP),
        c("archers", "Archers", 3, TROOP),
        c("goblins", "Goblins", 2, TROOP),
        c("spear_goblins", "Spear Goblins", 2, TROOP),
        c("giant", "Giant", 5, TROOP),
        c("pekka", "P.E.K.K.A", 7, TROOP),
        c("mini_pekka", "Mini P.E.K.K.A", 4, TROOP),
        c("minions", "Minions", 3, TROOP),
        c("minion_horde", "Minion Horde", 5, TROOP),
        c("balloon", "Balloon", 5, TROOP),
        c("witch", "Witch", 5, TROOP),
        c("barbarians", "Barbarians", 5, TROOP),
        c("elite_barbarians", "Elite Barbarians", 6, TROOP),
        c("golem", "Golem", 8, TROOP),
        c("skeletons", "Skeletons", 1, TROOP),
        c("skeleton_army", "Skeleton Army", 3, TROOP),
        c("valkyrie", "Valkyrie", 4, TROOP),
        c("bomber", "Bomber", 2, TROOP),
        c("musketeer", "Musketeer", 4, TROOP),
        c("three_musketeers", "Three Musketeers", 9, TROOP),
        c("baby_dragon", "Baby Dragon", 4, TROOP),
        c("prince", "Prince", 5, TROOP),
        c("dark_prince", "Dark Prince", 4, TROOP),
        c("wizard", "Wizard", 5, TROOP),
        c("ice_wizard", "Ice Wizard", 3, TROOP),
        c("electro_wizard", "Electro Wizard", 4, TROOP),
        c("giant_skeleton", "Giant Skeleton", 6, TROOP),
        c("hog_rider", "Hog Rider", 4, TROOP),
        c("royal_giant", "Royal Giant", 6, TROOP),
        c("guards", "Guards", 3, TROOP),
        c("princess", "Princess", 3, TROOP),
        c("lava_hound", "Lava Hound", 7, TROOP),
        c("ice_spirit", "Ice Spirit", 1, TROOP),
        c("fire_spirit", "Fire Spirit", 1, TROOP),
        c("electro_spirit", "Electro Spirit", 1, TROOP),
        c("heal_spirit", "Heal Spirit", 1, TROOP),
        c("miner", "Miner", 3, TROOP),
        c("sparky", "Sparky", 6, TROOP),
        c("bowler", "Bowler", 5, TROOP),
        c("lumberjack", "Lumberjack", 4, TROOP),
        c("battle_ram", "Battle Ram", 4, TROOP),
        c("inferno_dragon", "Inferno Dragon", 4, TROOP),
        c("ice_golem", "Ice Golem", 2, TROOP),
        c("mega_minion", "Mega Minion", 3, TROOP),
        c("dart_goblin", "Dart Goblin", 3, TROOP),
        c("goblin_gang", "Goblin Gang", 3, TROOP),
        c("hunter", "Hunter", 4, TROOP),
        c("executioner", "Executioner", 5, TROOP),
        c("bandit", "Bandit", 3, TROOP),
        c("royal_recruits", "Royal Recruits", 7, TROOP),
        c("night_witch", "Night Witch", 4, TROOP),
        c("bats", "Bats", 2, TROOP),
        c("royal_ghost", "Royal Ghost", 3, TROOP),
        c("ram_rider", "Ram Rider", 5, TROOP),
        c("zappies", "Zappies", 4, TROOP),
        c("rascals", "Rascals", 5, TROOP),
        c("cannon_cart", "Cannon Cart", 5, TROOP),
        c("mega_knight", "Mega Knight", 7, TROOP),
        c("skeleton_barrel", "Skeleton Barrel", 3, TROOP),
        c("flying_machine", "Flying Machine", 4, TROOP),
        c("wall_breakers", "Wall Breakers", 2, TROOP),
        c("royal_hogs", "Royal Hogs", 5, TROOP),
        c("goblin_giant", "Goblin Giant", 6, TROOP),
        c("fisherman", "Fisherman", 3, TROOP),
        c("magic_archer", "Magic Archer", 4, TROOP),
        c("electro_dragon", "Electro Dragon", 5, TROOP),
        c("firecracker", "Firecracker", 3, TROOP),
        c("elixir_golem", "Elixir Golem", 3, TROOP),
        c("battle_healer", "Battle Healer", 4, TROOP),
        c("skeleton_dragons", "Skeleton Dragons", 4, TROOP),
        c("mother_witch", "Mother Witch", 4, TROOP),
        c("electro_giant", "Electro Giant", 7, TROOP),
        c("phoenix", "Phoenix", 4, TROOP),
        // Чемпионы
        c("archer_queen", "Archer Queen", 5, CHAMPION),
        c("golden_knight", "Golden Knight", 4, CHAMPION),
        c("skeleton_king", "Skeleton King", 4, CHAMPION),
        c("mighty_miner", "Mighty Miner", 4, CHAMPION),
        c("monk", "Monk", 5, CHAMPION),
        c("little_prince", "Little Prince", 3, CHAMPION),
        // Заклинания
        c("arrows", "Arrows", 3, SPELL),
        c("zap", "Zap", 2, SPELL),
        c("the_log", "The Log", 2, SPELL),
        c("fireball", "Fireball", 4, SPELL),
        c("rocket", "Rocket", 6, SPELL),
        c("lightning", "Lightning", 6, SPELL),
        c("poison", "Poison", 4, SPELL),
        c("freeze", "Freeze", 4, SPELL),
        c("rage", "Rage", 2, SPELL),
        c("clone", "Clone", 3, SPELL),
        c("tornado", "Tornado", 3, SPELL),
        c("earthquake", "Earthquake", 3, SPELL),
        c("giant_snowball", "Giant Snowball", 2, SPELL),
        c("barbarian_barrel", "Barbarian Barrel", 2, SPELL),
        c("goblin_barrel", "Goblin Barrel", 3, SPELL),
        c("graveyard", "Graveyard", 5, SPELL),
        c("royal_delivery", "Royal Delivery", 3, SPELL),
        c("void", "Void", 3, SPELL),
        Card("mirror", "Mirror", -1, SPELL, SpecialRule.MIRROR),
        // Здания
        c("cannon", "Cannon", 3, BUILDING),
        c("tesla", "Tesla", 4, BUILDING),
        c("inferno_tower", "Inferno Tower", 5, BUILDING),
        c("bomb_tower", "Bomb Tower", 4, BUILDING),
        c("x_bow", "X-Bow", 6, BUILDING),
        c("mortar", "Mortar", 4, BUILDING),
        c("goblin_hut", "Goblin Hut", 5, BUILDING),
        c("furnace", "Furnace", 4, BUILDING),
        c("barbarian_hut", "Barbarian Hut", 7, BUILDING),
        c("tombstone", "Tombstone", 3, BUILDING),
        c("elixir_collector", "Elixir Collector", 6, BUILDING),
        c("goblin_cage", "Goblin Cage", 4, BUILDING),
        c("goblin_drill", "Goblin Drill", 4, BUILDING),
    ).sortedBy { it.name }

    private val byIdMap: Map<String, Card> = all.associateBy { it.id }

    fun byId(id: String): Card? = byIdMap[id]

    fun costLabel(card: Card): String =
        if (card.special == SpecialRule.MIRROR) "?+1" else card.cost.toString()
}
