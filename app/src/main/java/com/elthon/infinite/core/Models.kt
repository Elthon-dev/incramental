package com.elthon.infinite.core

import java.math.BigDecimal
import java.util.EnumMap
import java.util.LinkedHashSet

enum class StatId(
    val label: String,
    val defaultValue: BigDecimal,
    val minimum: BigDecimal,
    val maximum: BigDecimal
) {
    MAX_HP("Max HP", Num.decimal(100), Num.decimal(1), Num.LIMIT),
    ATTACK("Attack", Num.decimal(12), Num.decimal("0.01"), Num.LIMIT),
    ATTACK_SPEED("Attack speed", Num.decimal("1.15"), Num.decimal("0.2"), Num.decimal(30)),
    DEFENSE("Defense", Num.decimal(2), Num.ZERO, Num.LIMIT),
    MOVE_SPEED("Move speed", Num.decimal(225), Num.decimal(80), Num.decimal(650)),
    CRIT_CHANCE("Crit chance", Num.decimal("0.08"), Num.ZERO, Num.ONE),
    CRIT_DAMAGE("Crit damage", Num.decimal("1.75"), Num.ONE, Num.LIMIT),
    REGEN("Regeneration", Num.decimal("0.45"), Num.ZERO, Num.LIMIT),
    LIFESTEAL("Lifesteal", Num.ZERO, Num.ZERO, Num.ONE),
    BURN_CHANCE("Burn chance", Num.ZERO, Num.ZERO, Num.ONE),
    BURN_POWER("Burn power", Num.decimal(1), Num.ZERO, Num.LIMIT),
    SHOCK_CHANCE("Shock chance", Num.ZERO, Num.ZERO, Num.ONE),
    SHOCK_POWER("Shock power", Num.decimal(1), Num.ZERO, Num.LIMIT),
    FREEZE_CHANCE("Freeze chance", Num.ZERO, Num.ZERO, Num.ONE),
    FREEZE_POWER("Freeze power", Num.decimal(1), Num.ZERO, Num.LIMIT),
    POISON_CHANCE("Poison chance", Num.ZERO, Num.ZERO, Num.ONE),
    POISON_POWER("Poison power", Num.decimal(1), Num.ZERO, Num.LIMIT),
    STATUS_POWER("Status power", Num.decimal(1), Num.ZERO, Num.LIMIT),
    PROJECTILES("Projectiles", Num.decimal(1), Num.decimal(1), Num.decimal(12)),
    PIERCE("Pierce", Num.ZERO, Num.ZERO, Num.decimal(12)),
    AREA("Area", Num.decimal(1), Num.decimal("0.35"), Num.decimal(6)),
    CHAIN_CHANCE("Chain chance", Num.ZERO, Num.ZERO, Num.ONE),
    CHAIN_TARGETS("Chain targets", Num.ZERO, Num.ZERO, Num.decimal(12)),
    EXECUTE_CHANCE("Execute chance", Num.ZERO, Num.ZERO, Num.ONE),
    EXECUTE_THRESHOLD("Execute threshold", Num.decimal("0.18"), Num.ZERO, Num.ONE),
    LOW_HP_DAMAGE("Low HP damage", Num.ZERO, Num.ZERO, Num.LIMIT),
    LOW_HP_DEFENSE("Low HP defense", Num.ZERO, Num.ZERO, Num.LIMIT),
    CARD_CHOICES("Card choices", Num.decimal(3), Num.decimal(3), Num.decimal(5)),
    REROLLS("Rerolls", Num.decimal(1), Num.ZERO, Num.decimal(5)),
    NOVA_RADIUS("Nova radius", Num.decimal(115), Num.decimal(50), Num.decimal(300)),
    NOVA_POWER("Nova power", Num.decimal("1.8"), Num.decimal("0.1"), Num.LIMIT),
    DASH_POWER("Dash power", Num.decimal(1), Num.decimal("0.1"), Num.decimal(5)),
    SHIELD_ON_KILL("Shield on kill", Num.ZERO, Num.ZERO, Num.LIMIT),
    HEAL_ON_CARD("Heal on card", Num.ZERO, Num.ZERO, Num.LIMIT),
    FRACTAL_GROWTH("Fractal growth", Num.ZERO, Num.ZERO, Num.decimal(1000)),
    AETHER_FIND("Aether find", Num.ZERO, Num.ZERO, Num.LIMIT),
    ANOMALY_POWER("Anomaly power", Num.ZERO, Num.ZERO, Num.LIMIT);

    fun sanitize(value: BigDecimal): BigDecimal = Num.clamp(value, minimum, maximum)
}

enum class Rarity(val displayName: String, val rank: Int) {
    COMMON("Common", 0),
    UNCOMMON("Uncommon", 1),
    RARE("Rare", 2),
    EPIC("Epic", 3),
    LEGENDARY("Legendary", 4),
    MYTHIC("Mythic", 5),
    ANOMALOUS("Anomalous", 6)
}

enum class CardFamily(
    val displayName: String,
    val identity: String,
    val unlockStage: Int,
    val weight: Int
) {
    VOLT("Volt", "Multipliers and attack power", 1, 16),
    FORTRESS("Fortress", "Health, defense, and shields", 1, 12),
    OVERCLOCK("Overclock", "Attack speed", 1, 13),
    REAPER("Reaper", "Critical strikes", 1, 11),
    BLOOD("Blood", "Lifesteal and sustain", 1, 10),
    INFERNO("Inferno", "Burn and critical burn", 2, 11),
    FROST("Frost", "Chill, freeze, and control", 2, 10),
    STORM("Storm", "Shock and chain reactions", 3, 10),
    VENOM("Venom", "Poison and attrition", 3, 10),
    PRISM("Prism", "Projectiles, piercing, and area", 4, 10),
    NOVA("Nova", "Ability power and explosions", 4, 9),
    IRON_WILL("Iron Will", "Low-health survival", 5, 9),
    LAST_STAND("Last Stand", "Low-health damage", 5, 9),
    FRACTAL("Fractal", "Compounding multiplier growth", 6, 8),
    FORESIGHT("Foresight", "Card selection control", 7, 8),
    CARDSMITH("Cardsmith", "Rerolls and healing", 8, 8),
    ECHO("Echo", "Card-triggered effects", 9, 8),
    GRAVITY("Gravity", "Movement and phase dash", 10, 8),
    ANOMALY("Anomaly", "Rule-changing impossibilities", 12, 5)
}

enum class MetaUpgrade(
    val displayName: String,
    val description: String,
    val maximumRank: Int,
    val rankCosts: List<Int>,
    val unlockStage: Int
) {
    VITALITY("Vitality", "+8 starting max HP per rank", 3, listOf(12, 24, 44), 0),
    EDGE("Edge", "+3% starting attack per rank", 3, listOf(14, 28, 50), 0),
    HASTE("Haste", "+4% starting attack speed per rank", 3, listOf(16, 30, 54), 0),
    AEGIS("Aegis", "+1 starting defense and 3 shield per rank", 2, listOf(20, 40), 0),
    PRECISION("Precision", "+1.5% starting crit chance per rank", 2, listOf(22, 44), 0),
    OMEN("Omen", "+1 starting reroll per rank", 2, listOf(24, 46), 2),
    INSIGHT("Insight", "Start each run with +1 card choice", 1, listOf(60), 5),
    LUCK("Luck", "Rare-or-better pity improves by one offer", 1, listOf(65), 8),
    SALVAGE("Salvage", "+8% end-run Aether per rank", 3, listOf(20, 38, 70), 3)
}

enum class EnemyType(
    val displayName: String,
    val unlockStage: Int,
    val baseHp: BigDecimal,
    val baseDamage: BigDecimal,
    val baseDefense: BigDecimal,
    val speed: Float,
    val radius: Float,
    val color: Int,
    val shape: EnemyShape
) {
    BASIC("Husk", 1, Num.decimal(48), Num.decimal(10), Num.decimal(1), 72f, 25f, 0xFFFF5C8A.toInt(), EnemyShape.CIRCLE),
    SWARM("Mite", 1, Num.decimal(24), Num.decimal(6), Num.ZERO, 108f, 16f, 0xFFFFC857.toInt(), EnemyShape.TRIANGLE),
    BRUTE("Crusher", 3, Num.decimal(145), Num.decimal(18), Num.decimal(5), 50f, 38f, 0xFFFF7849.toInt(), EnemyShape.HEX),
    MAGE("Caster", 5, Num.decimal(86), Num.decimal(13), Num.decimal(2), 58f, 27f, 0xFFB46CFF.toInt(), EnemyShape.DIAMOND),
    TANK("Bulwark", 7, Num.decimal(260), Num.decimal(15), Num.decimal(12), 42f, 43f, 0xFF55B8FF.toInt(), EnemyShape.SQUARE),
    ASSASSIN("Rift Stalker", 10, Num.decimal(72), Num.decimal(20), Num.decimal(1), 135f, 23f, 0xFFDA70FF.toInt(), EnemyShape.SHARD),
    WEAVER("Weaver", 14, Num.decimal(112), Num.decimal(12), Num.decimal(3), 66f, 30f, 0xFF61F2A8.toInt(), EnemyShape.STAR),
    CORRUPTED("Corruptor", 19, Num.decimal(190), Num.decimal(17), Num.decimal(6), 62f, 34f, 0xFF9DFF4F.toInt(), EnemyShape.CROSS),
    ANOMALY("Paradox", 25, Num.decimal(165), Num.decimal(16), Num.decimal(4), 88f, 31f, 0xFFFFFFFF.toInt(), EnemyShape.PULSE),
    BOSS("Infinite Warden", 10, Num.decimal(620), Num.decimal(24), Num.decimal(8), 52f, 50f, 0xFFFF3864.toInt(), EnemyShape.CROWN);

    fun available(bestStage: Long): Boolean = unlockStage == 10 && bestStage >= 10 || this != BOSS && bestStage >= unlockStage
}

enum class EnemyShape { CIRCLE, TRIANGLE, HEX, DIAMOND, SQUARE, SHARD, STAR, CROSS, PULSE, CROWN }

enum class StatusType(val displayName: String, val color: Int) {
    BURN("Burn", 0xFFFF6B35.toInt()),
    SHOCK("Shock", 0xFF4DE8FF.toInt()),
    FREEZE("Freeze", 0xFF8ED8FF.toInt()),
    POISON("Poison", 0xFF8DFF57.toInt()),
    MARK("Mark", 0xFFFF4D8D.toInt()),
    WEAKEN("Weaken", 0xFFC58BFF.toInt()),
    CORRODE("Corrode", 0xFFFFD166.toInt())
}

enum class ModifierOperation { ADD, MULTIPLY, MAX }

enum class EffectKind {
    ADD_STAT,
    MULTIPLY_DAMAGE,
    ADD_CRIT,
    APPLY_STATUS,
    CHAIN,
    EXTRA_PROJECTILE,
    PIERCE,
    AREA,
    HEAL,
    SHIELD,
    DAMAGE_NEXT,
    ATTACK_SPEED_AFTER_KILL,
    CARD_CHOICE,
    REROLL,
    AETHER,
    FRACTAL,
    ANOMALY
}

enum class Trigger { PICK, ATTACK, CRIT, KILL, LOW_HP, ROUND_START, FIFTH_ATTACK }

enum class EffectCondition {
    ALWAYS,
    BELOW_40,
    BELOW_25,
    ABOVE_60,
    TARGET_CHILLED,
    TARGET_MARKED,
    TARGET_BELOW_HALF,
    EVERY_FIFTH,
    ANOMALOUS
}

data class StatModifier(
    val stat: StatId,
    val operation: ModifierOperation,
    val amount: BigDecimal,
    val condition: EffectCondition = EffectCondition.ALWAYS
)

data class CardEffect(
    val kind: EffectKind,
    val trigger: Trigger,
    val amount: BigDecimal,
    val stat: StatId? = null,
    val status: StatusType? = null,
    val condition: EffectCondition = EffectCondition.ALWAYS,
    val text: String
)

data class CardDefinition(
    val id: String,
    val title: String,
    val family: CardFamily,
    val rarity: Rarity,
    val modifiers: List<StatModifier>,
    val rules: List<CardEffect>,
    val tags: Set<String>,
    val seed: Long
) {
    val description: String = (modifiers.map { modifierText(it) } + rules.map { it.text }).distinct().joinToString("\n")
}

data class StatusState(
    val type: StatusType,
    var stacks: Int = 1,
    var remaining: Float = 0f,
    var tickTimer: Float = 0f,
    var sourcePower: BigDecimal = Num.ONE
)

data class PlayerState(
    val position: Vec2 = Vec2(0f, 150f),
    var hp: BigDecimal = Num.decimal(100),
    var shield: BigDecimal = Num.ZERO,
    var moveInput: Vec2 = Vec2(),
    var facing: Float = 0f,
    var attackTimer: Float = 0f,
    var novaTimer: Float = 0f,
    var dashTimer: Float = 0f,
    var dashCooldown: Float = 0f,
    var dashRemaining: Float = 0f,
    var invulnerable: Float = 0f,
    var attackCount: Long = 0L,
    var lowHealthTriggered: Boolean = false,
    var lastHitAge: Float = 0f,
    val statuses: MutableList<StatusState> = mutableListOf()
)

enum class AttackPhase { APPROACH, WINDUP, RECOVER, SPECIAL }

data class EnemyState(
    val id: Long,
    val type: EnemyType,
    var position: Vec2,
    var hp: BigDecimal,
    val maxHp: BigDecimal,
    var shield: BigDecimal = Num.ZERO,
    var damage: BigDecimal,
    var defense: BigDecimal,
    var speed: Float,
    val radius: Float,
    var elite: Boolean = false,
    var boss: Boolean = false,
    var attackTimer: Float = 0f,
    var phase: AttackPhase = AttackPhase.APPROACH,
    var phaseTimer: Float = 0f,
    var abilityTimer: Float = 0f,
    var targetX: Float = 0f,
    var targetY: Float = 0f,
    var rage: Boolean = false,
    var attackFlash: Float = 0f,
    val statuses: MutableList<StatusState> = mutableListOf(),
    var statusSeed: Long = 0L
)

enum class ProjectileTeam { PLAYER, ENEMY }

data class ProjectileState(
    val id: Long,
    val team: ProjectileTeam,
    val position: Vec2,
    val targetX: Float,
    val targetY: Float,
    val speed: Float,
    val damage: BigDecimal,
    val radius: Float,
    val color: Int,
    val piercing: Int = 0,
    val life: Float = 1.6f,
    val homingId: Long? = null,
    val status: StatusType? = null,
    val statusChance: BigDecimal = Num.ZERO,
    val chainCount: Int = 0
)

enum class CombatPhase { IDLE, FIGHTING, CARD_PICKER, EVENT, GAME_OVER }

enum class ChallengeModifier(val displayName: String, val description: String) {
    NONE("Stable", "No modifier"),
    SWARM("Swarm Signal", "More enemies, less HP each"),
    ELITE("Elite Order", "Two elite enemies enter the stage"),
    FRACTURED("Fractured Time", "Enemies act faster"),
    NIGHT("Night Circuit", "Less healing, enemy damage rises")
}

enum class SpecialEventType(val title: String, val description: String) {
    BLOOD_CACHE("Blood Cache", "Trade health for a powerful card"),
    GAMBLER("The Gambler", "Risk everything for a rarer reward"),
    WEAPON_CACHE("Weapon Cache", "Repair your signal or sharpen your edge"),
    TWISTED_GATE("Twisted Gate", "Trade max HP for permanent power")
}

data class SpecialEventChoice(
    val title: String,
    val description: String,
    val tag: String
)

data class SpecialEventState(
    val type: SpecialEventType,
    val choices: List<SpecialEventChoice>
)

enum class GameEventType { DAMAGE, HEAL, CRIT, STATUS, KILL, PLAYER_HURT, ABILITY, STAGE_CLEAR, CARD_PICK, SYNERGY, ACHIEVEMENT, EVENT }

data class GameEvent(
    val type: GameEventType,
    val x: Float = 0f,
    val y: Float = 0f,
    val amount: BigDecimal = Num.ZERO,
    val text: String = "",
    val color: Int = 0xFFFFFFFF.toInt()
)

data class CombatStats(
    var enemiesDefeated: Long = 0L,
    var elitesDefeated: Long = 0L,
    var bossesDefeated: Long = 0L,
    var damageDealt: BigDecimal = Num.ZERO,
    var damageTaken: BigDecimal = Num.ZERO,
    var healingDone: BigDecimal = Num.ZERO,
    var bestHit: BigDecimal = Num.ZERO,
    var cardsCollected: Int = 0,
    var stagesCleared: Long = 0L,
    var eventsResolved: Int = 0,
    var synergiesActivated: Int = 0,
    var anomalyDiscoveries: Int = 0,
    var lowestHealthRatio: BigDecimal = Num.ONE,
    var statusApplications: Int = 0,
    var abilitiesUsed: Long = 0L
)

data class RunState(
    val runId: String,
    val seed: Long,
    val rng: Rng,
    var stage: Long = 1L,
    var highestStage: Long = 1L,
    var phase: CombatPhase = CombatPhase.IDLE,
    var challenge: ChallengeModifier = ChallengeModifier.NONE,
    var specialEvent: SpecialEventState? = null,
    var player: PlayerState = PlayerState(),
    var stats: CombatStats = CombatStats(),
    var enemies: MutableList<EnemyState> = mutableListOf(),
    var projectiles: MutableList<ProjectileState> = mutableListOf(),
    var cards: MutableList<CardDefinition> = mutableListOf(),
    var cardOffers: MutableList<CardDefinition> = mutableListOf(),
    var synergies: MutableSet<String> = linkedSetOf(),
    var pendingSynergies: MutableList<String> = mutableListOf(),
    var nextEntityId: Long = 1L,
    var nextProjectileId: Long = 1L,
    var rarePity: Int = 0,
    var rerolls: Int = 1,
    var cardChoiceBonus: Int = 0,
    var combatTime: Float = 0f,
    var maxHpSnapshot: BigDecimal = Num.decimal(100),
    var stageClearPending: Boolean = false,
    var cardChoiceLocked: Boolean = false,
    var eventChoiceLocked: Boolean = false,
    var revision: Long = 0L,
    val events: MutableList<GameEvent> = mutableListOf()
) {
    fun emit(event: GameEvent) {
        if (events.size >= 160) events.removeAt(0)
        events.add(event)
    }
}

data class PlayerStats(val values: Map<StatId, BigDecimal>) {
    operator fun get(stat: StatId): BigDecimal = values[stat] ?: stat.defaultValue
}

data class GameSettings(
    var musicEnabled: Boolean = true,
    var soundEnabled: Boolean = true,
    var hapticsEnabled: Boolean = true,
    var reducedMotion: Boolean = false,
    var showDamageNumbers: Boolean = true
)

data class MetaProfile(
    var aether: BigDecimal = Num.ZERO,
    var signalEnergy: BigDecimal = Num.ZERO,
    var metaRanks: MutableMap<MetaUpgrade, Int> = EnumMap(MetaUpgrade::class.java),
    var discoveredCards: MutableSet<String> = linkedSetOf(),
    var discoveredFamilies: MutableSet<CardFamily> = linkedSetOf(),
    var achievements: MutableSet<String> = linkedSetOf(),
    var achievementProgress: MutableMap<String, Int> = mutableMapOf(),
    var settings: GameSettings = GameSettings(),
    var bestStage: Long = 0L,
    var totalRuns: Long = 0L,
    var totalKills: Long = 0L,
    var totalCards: Long = 0L,
    var totalAetherEarned: BigDecimal = Num.ZERO,
    var biggestHit: BigDecimal = Num.ZERO,
    var totalOfflineEnergy: BigDecimal = Num.ZERO,
    var settledRunIds: MutableSet<String> = linkedSetOf(),
    var lastSavedAt: Long = 0L,
    var lastOfflineClaimedAt: Long = 0L,
    var revision: Long = 0L
) {
    fun rank(upgrade: MetaUpgrade): Int = metaRanks[upgrade] ?: 0
}

data class AchievementDefinition(
    val id: String,
    val title: String,
    val description: String,
    val target: Int,
    val reward: Int
)

data class RunSummary(
    val stageReached: Long,
    val enemiesDefeated: Long,
    val cardsCollected: Int,
    val bestDamage: BigDecimal,
    val aetherEarned: BigDecimal,
    val synergies: Int,
    val newAchievements: List<String>
)

data class OfflineReport(
    val elapsedSeconds: Long,
    val energyAwarded: BigDecimal,
    val capped: Boolean
)

data class GameSave(
    val profile: MetaProfile,
    val run: RunState?
)
