package com.elthon.infinite.core

import java.math.BigDecimal
import kotlin.math.min
import kotlin.math.pow

object ScalingPolicy {
    fun hp(stage: Long): BigDecimal {
        val value = Num.decimal(maxOf(1L, stage))
        val logarithmic = Num.power(Num.ln(value), Num.decimal("1.4"))
        return Num.add(Num.ONE, Num.add(Num.multiply(logarithmic, Num.decimal("1.7")), Num.subtract(Num.power(value, Num.decimal("0.32")), Num.ONE)))
    }

    fun damage(stage: Long): BigDecimal {
        val value = Num.decimal(maxOf(1L, stage))
        val logarithmic = Num.power(Num.ln(value), Num.decimal("1.25"))
        return Num.add(Num.ONE, Num.add(Num.multiply(logarithmic, Num.decimal("0.85")), Num.subtract(Num.power(value, Num.decimal("0.18")), Num.ONE)))
    }

    fun defense(stage: Long): BigDecimal {
        val value = Num.decimal(maxOf(1L, stage))
        return Num.add(Num.ONE, Num.multiply(Num.power(Num.ln(value), Num.decimal("1.3")), Num.decimal("0.6")))
    }

    fun enemyCount(stage: Long): Int {
        if (stage <= 1L) return 1
        val value = Num.ln(Num.decimal(stage))
        return (1 + Num.toInt(Num.divide(value, Num.ln(Num.decimal("1.85"))), 0, 7)).coerceIn(1, 8)
    }

    fun eliteChance(stage: Long): BigDecimal {
        if (stage <= 2L) return Num.ZERO
        return Num.min(Num.decimal("0.28"), Num.decimal("0.025") * Num.decimal((stage - 2).coerceAtMost(10_000L)))
    }

    fun reward(stageCleared: Long, kills: Long, elites: Long, events: Int, salvageRank: Int): BigDecimal {
        val base = Num.add(
            Num.decimal(8),
            Num.add(
                Num.multiply(Num.decimal(stageCleared), Num.decimal(3)),
                Num.add(
                    Num.multiply(Num.decimal(kills), Num.decimal(1)),
                    Num.add(Num.multiply(Num.decimal(elites), Num.decimal(4)), Num.multiply(Num.decimal(events), Num.decimal(3)))
                )
            )
        )
        return Num.multiply(base, Num.add(Num.ONE, Num.multiply(Num.decimal(salvageRank), Num.decimal("0.08"))))
    }
}

object GameContent {
    val achievements = listOf(
        AchievementDefinition("first_blood", "First Blood", "Defeat your first enemy", 1, 8),
        AchievementDefinition("stage_10", "Signal Ten", "Reach Stage 10", 10, 15),
        AchievementDefinition("stage_50", "Deep Frequency", "Reach Stage 50", 50, 30),
        AchievementDefinition("stage_100", "Endless Certificate", "Reach Stage 100", 100, 60),
        AchievementDefinition("anomaly", "Impossible Choice", "Discover an Anomalous card", 1, 35),
        AchievementDefinition("massive_hit", "Reality Crack", "Deal a hit worth at least 1M", 1_000_000, 25),
        AchievementDefinition("last_breath", "One More", "Clear a stage below 10% HP", 1, 30),
        AchievementDefinition("synergy", "Rule Breaker", "Activate a synergy", 1, 15),
        AchievementDefinition("collector", "Pattern Collector", "Collect 50 upgrade cards in one run", 50, 25),
        AchievementDefinition("veteran", "Veteran Signal", "Complete 10 runs", 10, 20),
        AchievementDefinition("architect", "Build Architect", "Unlock 6 permanent upgrade ranks", 6, 25),
        AchievementDefinition("executioner", "Clean Exit", "Defeat 100 enemies", 100, 20)
    )

    val titlePrefixes = arrayOf("Voltaic", "Hollow", "Prismatic", "Fractured", "Endless", "Quiet", "Gilded", "Unstable", "Neon", "Velvet", "Iron", "Lucky")
    val titleSuffixes = arrayOf("Crown", "Circuit", "Protocol", "Bloom", "Spiral", "Engine", "Sigil", "Apex", "Rift", "Lattice", "Chorus", "Pulse")

    fun availableEnemyTypes(stage: Long, bestStage: Long): List<EnemyType> {
        val levels = listOf(stage, bestStage, stage / 2, stage / 3)
        val unlocked = linkedSetOf<EnemyType>()
        levels.forEach { unlocked.addAll(EnemyType.entries.filter { type -> type != EnemyType.BOSS && type.available(bestStage.coerceAtLeast(it)) }) }
        return unlocked.toList()
    }

    fun createEnemy(run: RunState, type: EnemyType, elite: Boolean, index: Int, count: Int): EnemyState {
        val stage = run.stage
        val boss = type == EnemyType.BOSS
        val eliteMultiplierHp = if (boss) Num.decimal("4.2") else if (elite) Num.decimal("1.85") else Num.ONE
        val eliteMultiplierDamage = if (boss) Num.decimal("1.45") else if (elite) Num.decimal("1.3") else Num.ONE
        val maxHp = Num.multiply(Num.multiply(type.baseHp, ScalingPolicy.hp(stage)), eliteMultiplierHp)
        val damage = Num.multiply(Num.multiply(type.baseDamage, ScalingPolicy.damage(stage)), eliteMultiplierDamage)
        val defense = Num.multiply(type.baseDefense, ScalingPolicy.defense(stage))
        val angle = (kotlin.math.PI * 2.0 * index / kotlin.math.max(1, count)).toFloat()
        val radiusX = 280f + (index % 3) * 34f
        val radiusY = 190f + (index / 3) * 38f
        val position = Vec2(kotlin.math.cos(angle) * radiusX, -220f + kotlin.math.sin(angle) * radiusY)
        val id = run.nextEntityId++
        val speedPenalty = if (run.challenge == ChallengeModifier.FRACTURED) 1.22f else 1f
        return EnemyState(
            id = id,
            type = type,
            position = position,
            hp = maxHp,
            maxHp = maxHp,
            damage = damage,
            defense = defense,
            speed = type.speed * speedPenalty,
            radius = if (elite) type.radius * 1.18f else type.radius,
            elite = elite,
            boss = boss,
            attackTimer = 0.35f + index * 0.09f,
            abilityTimer = 1.2f + index * 0.23f,
            statusSeed = run.rng.nextLong()
        )
    }

    fun createStage(run: RunState) {
        run.enemies.clear()
        run.projectiles.clear()
        run.stageClearPending = false
        run.combatTime = 0f
        val count = ScalingPolicy.enemyCount(run.stage)
        if (run.stage % 10L == 0L) {
            run.enemies.add(createEnemy(run, EnemyType.BOSS, false, 0, 1))
            repeat(min(3, count)) { index ->
                val pool = availableEnemyTypes(run.stage, run.highestStage)
                val type = pool[run.rng.nextInt(pool.size)]
                run.enemies.add(createEnemy(run, type, false, index + 1, min(3, count) + 1))
            }
        } else {
            val pool = availableEnemyTypes(run.stage, run.highestStage)
            repeat(count) { index ->
                val type = if (index == 0 && pool.size > 1 && run.rng.nextInt(4) == 0) pool[run.rng.nextInt(pool.size)] else pool[run.rng.nextInt(pool.size)]
                val elite = run.stage > 2 && run.rng.nextDouble() < Num.toDouble(ScalingPolicy.eliteChance(run.stage))
                run.enemies.add(createEnemy(run, type, elite, index, count))
            }
            if (run.challenge == ChallengeModifier.ELITE && run.enemies.size >= 2) {
                run.enemies[1] = createEnemy(run, run.enemies[1].type, true, 1, run.enemies.size)
            }
        }
        run.emit(GameEvent(GameEventType.STAGE_CLEAR, text = if (run.stage == 1L) "SIGNAL ACQUIRED" else "FREQUENCY RISING", color = 0xFF55F2D2.toInt()))
    }

    fun specialEventFor(stage: Long, rng: Rng): SpecialEventState? {
        if (stage < 4L || stage % 5L != 0L && rng.nextDouble() > 0.12) return null
        val type = SpecialEventType.entries[rng.nextInt(SpecialEventType.entries.size)]
        return when (type) {
            SpecialEventType.BLOOD_CACHE -> SpecialEventState(
                type,
                listOf(
                    SpecialEventChoice("Pay 15% HP", "Lose current HP, then gain an Epic-or-better card", "blood_pay"),
                    SpecialEventChoice("Leave it", "Take 4 Aether and continue", "blood_leave")
                )
            )
            SpecialEventType.GAMBLER -> SpecialEventState(
                type,
                listOf(
                    SpecialEventChoice("Gamble", "50% Rare+, 35% Epic+, 14% Legendary, 1% Mythic", "gamble"),
                    SpecialEventChoice("Walk away", "Gain 8 max HP", "gamble_leave")
                )
            )
            SpecialEventType.WEAPON_CACHE -> SpecialEventState(
                type,
                listOf(
                    SpecialEventChoice("Overclock", "Gain 12 Aether and +6% attack this run", "weapon_attack"),
                    SpecialEventChoice("Repair", "Heal 35% max HP and gain 5 shield", "weapon_repair")
                )
            )
            SpecialEventType.TWISTED_GATE -> SpecialEventState(
                type,
                listOf(
                    SpecialEventChoice("Pay 8 max HP", "Gain a guaranteed Rare-or-better card", "gate_pay"),
                    SpecialEventChoice("Refuse", "Gain a random Uncommon-or-better card", "gate_refuse")
                )
            )
        }
    }

    fun stageLabel(stage: Long): String = Num.formatStage(stage)
}
