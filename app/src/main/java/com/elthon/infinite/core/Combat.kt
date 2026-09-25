package com.elthon.infinite.core

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object CombatRules {
    const val ARENA_WIDTH = 720f
    const val ARENA_HEIGHT = 900f
    const val PLAYER_RADIUS = 25f
    const val PLAYER_START_Y = 660f
    const val NOVA_COOLDOWN = 6f
    const val DASH_COOLDOWN = 5f
}

data class CombatInput(
    var moveX: Float = 0f,
    var moveY: Float = 0f,
    var novaPressed: Boolean = false,
    var dashPressed: Boolean = false
)

object CombatEngine {
    fun beginRun(run: RunState, profile: MetaProfile) {
        run.stage = 1L
        run.highestStage = 1L
        run.specialEvent = null
        run.challenge = ChallengeModifier.NONE
        beginStage(run, profile)
    }

    fun advanceAfterChoice(run: RunState, profile: MetaProfile) {
        if (run.phase != CombatPhase.IDLE) return
        if (run.stage < Long.MAX_VALUE) run.stage++
        if (run.stage > run.highestStage) run.highestStage = run.stage
        if (run.stage > 1L && run.stage % 4L == 0L) {
            run.specialEvent = GameContent.specialEventFor(run.stage, run.rng)
        } else {
            run.specialEvent = null
        }
        if (run.specialEvent != null) {
            run.phase = CombatPhase.EVENT
            run.eventChoiceLocked = false
            run.emit(GameEvent(GameEventType.EVENT, text = run.specialEvent!!.type.title, color = 0xFFFFD166.toInt()))
        } else {
            rollChallenge(run)
            beginStage(run, profile)
        }
    }

    fun beginStage(run: RunState, profile: MetaProfile) {
        GameContent.createStage(run)
        val stats = StatsEngine.calculate(run, profile)
        run.maxHpSnapshot = stats[StatId.MAX_HP]
        run.player.hp = Num.min(run.player.hp, run.maxHpSnapshot)
        run.phase = CombatPhase.FIGHTING
        run.stageClearPending = false
        run.player.invulnerable = maxOf(run.player.invulnerable, 1f)
        run.cards.flatMap { it.rules }
            .filter { it.trigger == Trigger.ROUND_START && it.kind == EffectKind.SHIELD }
            .forEach { run.player.shield = Num.add(run.player.shield, it.amount) }
        run.emit(GameEvent(GameEventType.STAGE_CLEAR, text = "STAGE ${GameContent.stageLabel(run.stage)}", color = 0xFF55F2D2.toInt()))
    }

    fun finishStage(run: RunState, profile: MetaProfile) {
        if (run.phase != CombatPhase.FIGHTING || !run.stageClearPending) return
        run.stats.stagesCleared = maxOf(run.stats.stagesCleared, run.stage)
        val healthRatio = StatsEngine.playerHealthRatio(run, StatsEngine.calculate(run, profile)[StatId.MAX_HP])
        run.stats.lowestHealthRatio = Num.min(run.stats.lowestHealthRatio, healthRatio)
        if (run.cards.any { it.rarity == Rarity.ANOMALOUS }) run.stats.anomalyDiscoveries = maxOf(run.stats.anomalyDiscoveries, 1)
        run.cardOffers = CardGenerator.generateOffers(
            run,
            profile,
            if (run.stage % 10L == 0L) Rarity.EPIC else null
        ).toMutableList()
        run.phase = CombatPhase.CARD_PICKER
        run.revision++
        run.emit(GameEvent(GameEventType.CARD_PICK, text = "CHOOSE YOUR UPGRADE", color = 0xFFFFD166.toInt()))
    }

    fun step(run: RunState, profile: MetaProfile, input: CombatInput, frameSeconds: Float) {
        if (run.phase != CombatPhase.FIGHTING) return
        val dt = frameSeconds.coerceIn(0f, 0.05f)
        run.combatTime += dt
        updatePlayer(run, profile, input, dt)
        if (input.novaPressed) useNova(run, profile)
        if (input.dashPressed) useDash(run, profile, input)
        input.novaPressed = false
        input.dashPressed = false
        if (run.phase != CombatPhase.FIGHTING) return
        updateEnemies(run, profile, dt)
        updateProjectiles(run, profile, dt)
        separateEnemies(run)
        tickPlayerStatuses(run, profile, dt)
        tickEnemyStatuses(run, profile, dt)
        run.enemies.removeAll { it.hp.compareTo(Num.ZERO) <= 0 }
        run.projectiles.removeAll { it.life <= 0f }
        if (run.player.hp.compareTo(Num.ZERO) <= 0) {
            run.player.hp = Num.ZERO
            run.phase = CombatPhase.GAME_OVER
            run.emit(GameEvent(GameEventType.PLAYER_HURT, text = "SIGNAL LOST", color = 0xFFFF3864.toInt()))
            return
        }
        if (run.enemies.isEmpty() && !run.stageClearPending) {
            run.stageClearPending = true
            run.emit(GameEvent(GameEventType.STAGE_CLEAR, text = "STAGE CLEAR", color = 0xFF55F2D2.toInt()))
            finishStage(run, profile)
        }
    }

    fun useNova(run: RunState, profile: MetaProfile) {
        if (run.phase != CombatPhase.FIGHTING || run.player.novaTimer > 0f) return
        val stats = effectiveStats(run, profile)
        val center = run.player.position
        val radius = Num.toFloat(stats[StatId.NOVA_RADIUS], 50f, 320f)
        run.enemies.filter { distance(center, it.position) <= radius + it.radius }.forEach { enemy ->
            val damage = Num.multiply(stats[StatId.ATTACK], Num.multiply(stats[StatId.NOVA_POWER], stats[StatId.STATUS_POWER]))
            damageEnemy(run, profile, enemy, damage, center, 0xFF55F2D2.toInt(), false)
            if (run.rng.nextDouble() < Num.toDouble(stats[StatId.BURN_CHANCE])) applyStatus(enemy, StatusType.BURN, 2, 4f, stats[StatId.BURN_POWER])
            if (run.rng.nextDouble() < Num.toDouble(stats[StatId.SHOCK_CHANCE])) applyStatus(enemy, StatusType.SHOCK, 2, 3f, stats[StatId.SHOCK_POWER])
        }
        run.player.novaTimer = CombatRules.NOVA_COOLDOWN
        run.stats.abilitiesUsed++
        run.emit(GameEvent(GameEventType.ABILITY, center.x, center.y, text = "NOVA", color = 0xFF55F2D2.toInt()))
    }

    fun useDash(run: RunState, profile: MetaProfile, input: CombatInput) {
        if (run.phase != CombatPhase.FIGHTING || run.player.dashCooldown > 0f || run.player.dashRemaining > 0f) return
        val stats = effectiveStats(run, profile)
        val direction = Vec2(input.moveX, input.moveY).normalized().let { if (it.lengthSquared() < 0.01f) Vec2(kotlin.math.cos(run.player.facing), sin(run.player.facing)) else it }
        run.player.dashRemaining = 0.2f
        run.player.dashCooldown = CombatRules.DASH_COOLDOWN
        run.player.invulnerable = maxOf(run.player.invulnerable, 0.28f)
        run.player.moveInput = direction
        run.player.attackTimer = minOf(run.player.attackTimer, 0.06f)
        run.stats.abilitiesUsed++
        run.emit(GameEvent(GameEventType.ABILITY, run.player.position.x, run.player.position.y, text = "PHASE", color = 0xFFDA70FF.toInt()))
        if (run.cards.any { it.rules.any { rule -> rule.trigger == Trigger.PICK && rule.kind == EffectKind.SHIELD } }) {
            run.player.shield = Num.add(run.player.shield, Num.multiply(stats[StatId.DASH_POWER], Num.decimal(2)))
        }
    }

    private fun updatePlayer(run: RunState, profile: MetaProfile, input: CombatInput, dt: Float) {
        val stats = effectiveStats(run, profile)
        run.player.attackTimer -= dt
        run.player.novaTimer = maxOf(0f, run.player.novaTimer - dt)
        run.player.dashCooldown = maxOf(0f, run.player.dashCooldown - dt)
        run.player.invulnerable = maxOf(0f, run.player.invulnerable - dt)
        run.player.lastHitAge += dt
        val frozen = run.player.statuses.firstOrNull { it.type == StatusType.FREEZE }?.stacks ?: 0
        val move = Vec2(input.moveX, input.moveY).limit(1f)
        if (move.lengthSquared() > 0.01f) run.player.facing = atan2(move.y, move.x)
        val dashPower = Num.toFloat(stats[StatId.DASH_POWER], 0.1f, 5f)
        if (run.player.dashRemaining > 0f) {
            run.player.dashRemaining = maxOf(0f, run.player.dashRemaining - dt)
            run.player.position.addInPlace(run.player.moveInput.multiply(980f * dashPower * dt))
        } else {
            val speed = Num.toFloat(stats[StatId.MOVE_SPEED], 80f, 650f) * if (frozen > 0) 0.38f else 1f
            run.player.position.addInPlace(move.multiply(speed * dt))
        }
        clampPosition(run.player.position, CombatRules.PLAYER_RADIUS)
        val regen = stats[StatId.REGEN]
        if (regen.compareTo(Num.ZERO) > 0) healPlayer(run, Num.multiply(regen, Num.decimal(dt.toDouble())), false)
        val healthRatio = StatsEngine.playerHealthRatio(run, stats[StatId.MAX_HP])
        if (healthRatio < Num.decimal("0.25") && !run.player.lowHealthTriggered) {
            run.player.lowHealthTriggered = true
            run.emit(GameEvent(GameEventType.ABILITY, run.player.position.x, run.player.position.y, text = "LAST LIGHT", color = 0xFFFF4D8D.toInt()))
        }
        if (healthRatio > Num.decimal("0.35")) run.player.lowHealthTriggered = false
        if (run.player.attackTimer <= 0f) performAttack(run, profile)
    }

    private fun performAttack(run: RunState, profile: MetaProfile) {
        val stats = effectiveStats(run, profile)
        val target = run.enemies.filter { it.hp.compareTo(Num.ZERO) > 0 }.minByOrNull { distance(run.player.position, it.position) } ?: return
        val cooldown = (1f / Num.toFloat(stats[StatId.ATTACK_SPEED], 0.2f, 30f)).coerceIn(0.08f, 2.5f)
        run.player.attackTimer = cooldown
        run.player.attackCount++
        run.player.facing = atan2(target.position.y - run.player.position.y, target.position.x - run.player.position.x)
        val targets = mutableListOf<EnemyState>()
        val extraProjectiles = Num.toInt(ruleAmount(run, Trigger.ATTACK, EffectKind.EXTRA_PROJECTILE, target), 0, 12)
        val projectileCount = Num.toInt(stats[StatId.PROJECTILES], 1, 12) + extraProjectiles
        val pierce = Num.toInt(stats[StatId.PIERCE], 0, 12) + Num.toInt(ruleAmount(run, Trigger.ATTACK, EffectKind.PIERCE, target), 0, 12)
        val capacity = maxOf(1, projectileCount + pierce)
        run.enemies.sortedWith(compareBy<EnemyState> { distance(run.player.position, it.position) }.thenBy { it.id })
            .filter { it.hp.compareTo(Num.ZERO) > 0 }
            .take(capacity)
            .forEach { targets.add(it) }
        if (targets.isEmpty()) targets.add(target)
        targets.forEachIndexed { index, hitTarget ->
            val shotScale = if (index == 0) Num.ONE else Num.decimal("0.72")
            dealPlayerDamage(run, profile, hitTarget, stats, shotScale)
        }
        val area = stats[StatId.AREA]
        if (area > Num.ONE) {
            val areaRadius = 90f * Num.toFloat(area, 0.35f, 6f)
            run.enemies.filter { it !in targets && distance(target.position, it.position) <= areaRadius }.forEach { nearby ->
                dealPlayerDamage(run, profile, nearby, stats, Num.multiply(area, Num.decimal("0.35")))
            }
        }
        if (run.rng.nextDouble() < Num.toDouble(stats[StatId.CHAIN_CHANCE]) || run.synergies.contains("storm_web")) {
            val chainCount = maxOf(1, Num.toInt(stats[StatId.CHAIN_TARGETS], 1, 12))
            val chained = run.enemies.filter { it !in targets && it.hp.compareTo(Num.ZERO) > 0 }
                .sortedBy { distance(target.position, it.position) }
                .take(chainCount)
            chained.forEach { chainedTarget ->
                dealPlayerDamage(run, profile, chainedTarget, stats, Num.decimal("0.38"))
                applyStatus(chainedTarget, StatusType.SHOCK, 2, 3.5f, stats[StatId.SHOCK_POWER])
            }
        }
        if (run.rng.nextDouble() < Num.toDouble(stats[StatId.BURN_CHANCE])) applyStatus(target, StatusType.BURN, 1 + ruleStacks(run, Trigger.ATTACK, StatusType.BURN, target), 5f, stats[StatId.BURN_POWER])
        if (run.rng.nextDouble() < Num.toDouble(stats[StatId.SHOCK_CHANCE])) applyStatus(target, StatusType.SHOCK, 1 + ruleStacks(run, Trigger.ATTACK, StatusType.SHOCK, target), 4f, stats[StatId.SHOCK_POWER])
        if (run.rng.nextDouble() < Num.toDouble(stats[StatId.FREEZE_CHANCE])) applyStatus(target, StatusType.FREEZE, 1 + ruleStacks(run, Trigger.ATTACK, StatusType.FREEZE, target), 2.5f, stats[StatId.FREEZE_POWER])
        if (run.rng.nextDouble() < Num.toDouble(stats[StatId.POISON_CHANCE])) applyStatus(target, StatusType.POISON, 1 + ruleStacks(run, Trigger.ATTACK, StatusType.POISON, target), 6f, stats[StatId.POISON_POWER])
        if (run.player.attackCount % 7L == 0L && stats[StatId.ANOMALY_POWER].compareTo(Num.ZERO) > 0) {
            val echo = ruleAmount(run, Trigger.ATTACK, EffectKind.ANOMALY, target)
            if (echo.compareTo(Num.ZERO) > 0) dealPlayerDamage(run, profile, target, stats, echo)
        }
    }

    private fun dealPlayerDamage(run: RunState, profile: MetaProfile, target: EnemyState, stats: PlayerStats, scale: BigDecimal) {
        var damage = Num.multiply(stats[StatId.ATTACK], scale)
        val critical = run.rng.nextDouble() < Num.toDouble(stats[StatId.CRIT_CHANCE])
        if (critical) damage = Num.multiply(damage, stats[StatId.CRIT_DAMAGE])
        if (StatsEngine.playerHealthRatio(run, stats[StatId.MAX_HP]) < Num.decimal("0.4") && target.statuses.any { it.type == StatusType.FREEZE }) damage = Num.multiply(damage, Num.decimal("1.2"))
        damage = Num.add(damage, ruleAmount(run, if (critical) Trigger.CRIT else Trigger.ATTACK, EffectKind.DAMAGE_NEXT, target))
        if (run.player.attackCount % 5L == 0L) damage = Num.add(damage, ruleAmount(run, Trigger.FIFTH_ATTACK, EffectKind.DAMAGE_NEXT, target))
        if (target.hp.compareTo(Num.multiply(target.maxHp, stats[StatId.EXECUTE_THRESHOLD])) <= 0 && run.rng.nextDouble() < Num.toDouble(stats[StatId.EXECUTE_CHANCE])) {
            damage = Num.multiply(damage, Num.decimal("2.8"))
            run.emit(GameEvent(GameEventType.CRIT, target.position.x, target.position.y, text = "EXECUTE", color = 0xFFFFD166.toInt()))
        }
        damageEnemy(run, profile, target, damage, run.player.position, if (critical) 0xFFFFD166.toInt() else 0xFFFFFFFF.toInt(), critical)
        if (critical) run.cards.flatMap { it.rules }.filter { it.trigger == Trigger.CRIT && it.kind == EffectKind.HEAL }.forEach { healPlayer(run, it.amount, true) }
        if (target.hp.compareTo(Num.ZERO) <= 0) return
        if (run.rng.nextDouble() < Num.toDouble(stats[StatId.LIFESTEAL])) healPlayer(run, Num.multiply(damage, stats[StatId.LIFESTEAL]), false)
    }

    private fun damageEnemy(run: RunState, profile: MetaProfile, enemy: EnemyState, rawDamage: BigDecimal, source: Vec2, color: Int, critical: Boolean) {
        if (enemy.hp.compareTo(Num.ZERO) <= 0 || run.phase == CombatPhase.GAME_OVER) return
        val mitigation = Num.divide(Num.HUNDRED, Num.add(Num.HUNDRED, enemy.defense))
        var damage = Num.max(Num.decimal("0.1"), Num.multiply(Num.sanitize(rawDamage), mitigation))
        if (enemy.statuses.any { it.type == StatusType.MARK }) damage = Num.multiply(damage, Num.decimal("1.12"))
        if (enemy.statuses.any { it.type == StatusType.WEAKEN }) damage = Num.multiply(damage, Num.decimal("1.08"))
        val shieldDamage = Num.min(enemy.shield, damage)
        enemy.shield = Num.subtract(enemy.shield, shieldDamage)
        val hpDamage = Num.subtract(damage, shieldDamage)
        enemy.hp = Num.max(Num.ZERO, Num.subtract(enemy.hp, hpDamage))
        run.stats.damageDealt = Num.add(run.stats.damageDealt, damage)
        if (damage.compareTo(run.stats.bestHit) > 0) run.stats.bestHit = damage
        run.emit(GameEvent(if (critical) GameEventType.CRIT else GameEventType.DAMAGE, enemy.position.x, enemy.position.y, damage, Num.format(damage), color))
        if (enemy.hp.compareTo(Num.ZERO) <= 0) killEnemy(run, profile, enemy, source)
    }

    private fun killEnemy(run: RunState, profile: MetaProfile, enemy: EnemyState, source: Vec2) {
        run.stats.enemiesDefeated++
        if (enemy.elite) run.stats.elitesDefeated++
        if (enemy.boss) run.stats.bossesDefeated++
        val stats = effectiveStats(run, profile)
        run.player.shield = Num.add(run.player.shield, stats[StatId.SHIELD_ON_KILL])
        run.cards.flatMap { it.rules }.forEach { rule ->
            if (rule.trigger == Trigger.KILL) {
                when (rule.kind) {
                    EffectKind.HEAL -> healPlayer(run, rule.amount, true)
                    EffectKind.SHIELD -> run.player.shield = Num.add(run.player.shield, rule.amount)
                    EffectKind.DAMAGE_NEXT -> Unit
                    EffectKind.ATTACK_SPEED_AFTER_KILL -> run.player.attackTimer = minOf(run.player.attackTimer, 0.04f)
                    EffectKind.CARD_CHOICE -> run.cardChoiceBonus += Num.toInt(rule.amount, 0, 2)
                    EffectKind.AETHER -> run.emit(GameEvent(GameEventType.KILL, enemy.position.x, enemy.position.y, rule.amount, "+${Num.format(rule.amount)} AETHER", 0xFF55F2D2.toInt()))
                    EffectKind.APPLY_STATUS -> if (run.synergies.contains("venom_engine")) {
                        run.enemies.filter { it != enemy && it.hp.compareTo(Num.ZERO) > 0 && distance(enemy.position, it.position) < 180f }.take(3).forEach { applyStatus(it, rule.status ?: StatusType.POISON, Num.toInt(rule.amount, 1, 10), 6f, rule.amount) }
                    }
                    else -> Unit
                }
            }
        }
        run.emit(GameEvent(GameEventType.KILL, enemy.position.x, enemy.position.y, text = if (enemy.boss) "WARDEN BROKEN" else if (enemy.elite) "ELITE DOWN" else "ELIMINATED", color = if (enemy.boss) 0xFFFFD166.toInt() else 0xFF55F2D2.toInt()))
    }

    private fun updateEnemies(run: RunState, profile: MetaProfile, dt: Float) {
        val target = run.player.position
        run.enemies.toList().forEach { enemy ->
            if (enemy.hp.compareTo(Num.ZERO) <= 0) return@forEach
            enemy.attackFlash = maxOf(0f, enemy.attackFlash - dt)
            enemy.abilityTimer -= dt
            val frozen = enemy.statuses.filter { it.type == StatusType.FREEZE }.sumOf { it.stacks }
            val slow = if (frozen > 0) 0.25f else 1f
            val distanceToPlayer = distance(enemy.position, target)
            when (enemy.phase) {
                AttackPhase.APPROACH -> updateApproach(run, enemy, target, distanceToPlayer, dt, slow)
                AttackPhase.WINDUP -> {
                    enemy.phaseTimer -= dt
                    if (enemy.phaseTimer <= 0f) {
                        enemy.phase = AttackPhase.RECOVER
                        enemy.phaseTimer = if (enemy.boss) 0.65f else 0.5f
                        performEnemyAttack(run, profile, enemy)
                    }
                }
                AttackPhase.RECOVER -> {
                    enemy.phaseTimer -= dt
                    if (enemy.phaseTimer <= 0f) {
                        enemy.phase = AttackPhase.APPROACH
                        enemy.attackTimer = attackInterval(enemy)
                    }
                }
                AttackPhase.SPECIAL -> {
                    enemy.phaseTimer -= dt
                    if (enemy.phaseTimer <= 0f) {
                        enemy.phase = AttackPhase.APPROACH
                        enemy.abilityTimer = if (enemy.boss) 4.5f else 7f
                    }
                }
            }
        }
    }

    private fun updateApproach(run: RunState, enemy: EnemyState, target: Vec2, distanceToPlayer: Float, dt: Float, slow: Float) {
        val preferred = when (enemy.type) {
            EnemyType.MAGE -> 250f
            EnemyType.BOSS -> 150f
            else -> enemy.radius + 38f
        }
        val direction = target.subtract(enemy.position).normalized()
        if (distanceToPlayer > preferred) enemy.position.addInPlace(direction.multiply(enemy.speed * slow * dt))
        else if (enemy.type == EnemyType.MAGE && distanceToPlayer < 170f) enemy.position.addInPlace(direction.multiply(-enemy.speed * slow * dt))
        clampPosition(enemy.position, enemy.radius)
        if (enemy.attackTimer <= 0f && distanceToPlayer <= enemy.radius + 75f) {
            enemy.targetX = target.x
            enemy.targetY = target.y
            enemy.phase = AttackPhase.WINDUP
            enemy.phaseTimer = when (enemy.type) {
                EnemyType.BRUTE, EnemyType.BOSS -> 0.72f
                EnemyType.ASSASSIN -> 0.28f
                EnemyType.SWARM -> 0.22f
                else -> 0.44f
            }
            enemy.attackFlash = enemy.phaseTimer
        }
        if (enemy.abilityTimer <= 0f && enemy.type in setOf(EnemyType.WEAVER, EnemyType.ASSASSIN, EnemyType.ANOMALY, EnemyType.CORRUPTED, EnemyType.BOSS)) {
            when (enemy.type) {
                EnemyType.WEAVER -> if (run.enemies.size < 10) {
                    repeat(2) { index -> run.enemies.add(GameContent.createEnemy(run, EnemyType.SWARM, false, index, 2)) }
                    enemy.abilityTimer = 7f
                }
                EnemyType.ASSASSIN -> {
                    enemy.phase = AttackPhase.SPECIAL
                    enemy.phaseTimer = 0.45f
                    enemy.targetX = target.x
                    enemy.targetY = target.y
                }
                EnemyType.ANOMALY -> {
                    enemy.phase = AttackPhase.SPECIAL
                    enemy.phaseTimer = 0.7f
                    enemy.rage = !enemy.rage
                    run.emit(GameEvent(GameEventType.ABILITY, enemy.position.x, enemy.position.y, text = if (enemy.rage) "PARADOX SHIFT" else "REALITY FOLD", color = 0xFFFFFFFF.toInt()))
                }
                EnemyType.CORRUPTED -> {
                    applyPlayerStatus(run, StatusType.WEAKEN, 1, 4f, Num.decimal("0.85"))
                    applyPlayerStatus(run, StatusType.CORRODE, 1, 4f, Num.decimal("0.8"))
                    enemy.abilityTimer = 6f
                }
                EnemyType.BOSS -> {
                    repeat(6) { index ->
                        val angle = index * PI.toFloat() / 3f
                        run.projectiles.add(ProjectileState(run.nextProjectileId++, ProjectileTeam.ENEMY, enemy.position, target.x + cos(angle) * 280f, target.y + sin(angle) * 280f, 260f, Num.multiply(enemy.damage, Num.decimal("0.55")), 9f, 0xFFFF3864.toInt(), life = 2.2f))
                    }
                    enemy.abilityTimer = 6f
                }
                else -> Unit
            }
        }
    }

    private fun performEnemyAttack(run: RunState, profile: MetaProfile, enemy: EnemyState) {
        val target = run.player.position
        val distanceToPlayer = distance(enemy.position, target)
        when (enemy.type) {
            EnemyType.MAGE -> {
                run.projectiles.add(ProjectileState(run.nextProjectileId++, ProjectileTeam.ENEMY, enemy.position, target.x, target.y, 250f, enemy.damage, 11f, enemy.type.color, life = 2.5f, homingId = null))
            }
            EnemyType.ASSASSIN -> {
                val direction = target.subtract(enemy.position).normalized()
                enemy.position.addInPlace(direction.multiply(95f))
                if (distance(enemy.position, target) < 105f) damagePlayer(run, profile, enemy, Num.multiply(enemy.damage, Num.decimal("1.35")))
            }
            EnemyType.BOSS -> {
                if (distanceToPlayer < 120f) damagePlayer(run, profile, enemy, Num.multiply(enemy.damage, Num.decimal("1.2")))
                repeat(4) { index ->
                    val angle = index * PI.toFloat() / 2f
                    run.projectiles.add(ProjectileState(run.nextProjectileId++, ProjectileTeam.ENEMY, enemy.position, target.x + cos(angle) * 240f, target.y + sin(angle) * 240f, 220f, enemy.damage, 10f, enemy.type.color, life = 2.4f))
                }
            }
            EnemyType.CORRUPTED -> {
                damagePlayer(run, profile, enemy, enemy.damage)
                applyPlayerStatus(run, StatusType.WEAKEN, 1, 3f, Num.decimal("0.85"))
            }
            EnemyType.ANOMALY -> {
                if (enemy.rage) run.enemies.filter { it != enemy }.take(3).forEach { it.hp = Num.max(Num.ONE, Num.multiply(it.hp, Num.decimal("1.08"))) }
                if (distanceToPlayer < enemy.radius + 85f) damagePlayer(run, profile, enemy, enemy.damage)
            }
            else -> if (distanceToPlayer < enemy.radius + 100f) damagePlayer(run, profile, enemy, enemy.damage)
        }
    }

    private fun damagePlayer(run: RunState, profile: MetaProfile, source: EnemyState?, rawDamage: BigDecimal, ignoreInvulnerability: Boolean = false) {
        if (run.phase == CombatPhase.GAME_OVER || run.player.hp.compareTo(Num.ZERO) <= 0) return
        if (!ignoreInvulnerability && run.player.invulnerable > 0f) return
        val stats = effectiveStats(run, profile)
        val challengeScale = if (run.challenge == ChallengeModifier.NIGHT) Num.decimal("1.25") else Num.ONE
        val damage = Num.multiply(rawDamage, challengeScale)
        val mitigation = Num.divide(Num.HUNDRED, Num.add(Num.HUNDRED, stats[StatId.DEFENSE]))
        val finalDamage = Num.max(Num.decimal("0.1"), Num.multiply(damage, mitigation))
        val shieldDamage = Num.min(run.player.shield, finalDamage)
        run.player.shield = Num.max(Num.ZERO, Num.subtract(run.player.shield, shieldDamage))
        val hpDamage = Num.subtract(finalDamage, shieldDamage)
        run.player.hp = Num.max(Num.ZERO, Num.subtract(run.player.hp, hpDamage))
        run.player.invulnerable = if (ignoreInvulnerability) run.player.invulnerable else 0.32f
        run.player.lastHitAge = 0f
        run.stats.damageTaken = Num.add(run.stats.damageTaken, finalDamage)
        run.emit(GameEvent(GameEventType.PLAYER_HURT, run.player.position.x, run.player.position.y, finalDamage, "-${Num.format(finalDamage)}", if (shieldDamage.compareTo(Num.ZERO) > 0) 0xFF55B8FF.toInt() else 0xFFFF3864.toInt()))
        if (run.player.hp.compareTo(Num.ZERO) <= 0) {
            run.phase = CombatPhase.GAME_OVER
            run.emit(GameEvent(GameEventType.PLAYER_HURT, text = "SIGNAL LOST", color = 0xFFFF3864.toInt()))
        }
    }

    private fun healPlayer(run: RunState, amount: BigDecimal, showEvent: Boolean) {
        if (amount.compareTo(Num.ZERO) <= 0 || run.player.hp.compareTo(Num.ZERO) <= 0) return
        val maxHp = effectiveMaxHp(run)
        val before = run.player.hp
        run.player.hp = Num.min(maxHp, Num.add(run.player.hp, amount))
        val healed = Num.subtract(run.player.hp, before)
        if (healed.compareTo(Num.ZERO) > 0) {
            run.stats.healingDone = Num.add(run.stats.healingDone, healed)
            if (showEvent) run.emit(GameEvent(GameEventType.HEAL, run.player.position.x, run.player.position.y, healed, "+${Num.format(healed)}", 0xFF61F2A8.toInt()))
        }
    }

    private fun applyStatus(enemy: EnemyState, type: StatusType, stacks: Int, duration: Float, power: BigDecimal) {
        if (enemy.hp.compareTo(Num.ZERO) <= 0 || stacks <= 0) return
        val current = enemy.statuses.firstOrNull { it.type == type }
        if (current == null) {
            enemy.statuses.add(StatusState(type, stacks.coerceAtMost(20), duration.coerceAtLeast(0.2f), initialTick(type), power))
        } else {
            current.stacks = (current.stacks + stacks).coerceAtMost(20)
            current.remaining = maxOf(current.remaining, duration)
            current.sourcePower = Num.max(current.sourcePower, power)
        }
    }

    private fun applyPlayerStatus(run: RunState, type: StatusType, stacks: Int, duration: Float, power: BigDecimal) {
        val current = run.player.statuses.firstOrNull { it.type == type }
        if (current == null) run.player.statuses.add(StatusState(type, stacks.coerceAtMost(10), duration, initialTick(type), power))
        else {
            current.stacks = (current.stacks + stacks).coerceAtMost(10)
            current.remaining = maxOf(current.remaining, duration)
        }
    }

    private fun tickEnemyStatuses(run: RunState, profile: MetaProfile, dt: Float) {
        run.enemies.toList().forEach { enemy ->
            enemy.statuses.toList().forEach { status ->
                status.remaining -= dt
                status.tickTimer -= dt
                if (status.tickTimer <= 0f && enemy.hp.compareTo(Num.ZERO) > 0) {
                    status.tickTimer = statusTickInterval(status.type)
                    val damage = when (status.type) {
                        StatusType.BURN -> Num.multiply(Num.decimal(status.stacks), Num.multiply(status.sourcePower, Num.decimal("0.32")))
                        StatusType.POISON -> Num.multiply(Num.decimal(status.stacks), Num.multiply(status.sourcePower, Num.decimal("0.2")))
                        StatusType.SHOCK -> Num.multiply(Num.decimal(status.stacks), Num.multiply(status.sourcePower, Num.decimal("0.14")))
                        else -> Num.ZERO
                    }
                    if (damage.compareTo(Num.ZERO) > 0) damageEnemy(run, profile, enemy, damage, enemy.position, status.type.color, false)
                }
                if (status.remaining <= 0f) enemy.statuses.remove(status)
            }
        }
    }

    private fun tickPlayerStatuses(run: RunState, profile: MetaProfile, dt: Float) {
        run.player.statuses.toList().forEach { status ->
            status.remaining -= dt
            status.tickTimer -= dt
            if (status.tickTimer <= 0f && run.player.hp.compareTo(Num.ZERO) > 0) {
                status.tickTimer = statusTickInterval(status.type)
                val damage = when (status.type) {
                    StatusType.BURN -> Num.multiply(Num.decimal(status.stacks), Num.multiply(status.sourcePower, Num.decimal("0.32")))
                    StatusType.POISON -> Num.multiply(Num.decimal(status.stacks), Num.multiply(status.sourcePower, Num.decimal("0.2")))
                    StatusType.SHOCK -> Num.multiply(Num.decimal(status.stacks), Num.multiply(status.sourcePower, Num.decimal("0.14")))
                    else -> Num.ZERO
                }
                if (damage.compareTo(Num.ZERO) > 0) damagePlayer(run, profile, null, damage, true)
            }
            if (status.remaining <= 0f) run.player.statuses.remove(status)
        }
    }

    private fun effectiveStats(run: RunState, profile: MetaProfile): PlayerStats {
        val base = StatsEngine.effective(run, profile)
        val values = base.values.toMutableMap()
        val weaken = run.player.statuses.filter { it.type == StatusType.WEAKEN }.sumOf { it.stacks }
        val corrode = run.player.statuses.filter { it.type == StatusType.CORRODE }.sumOf { it.stacks }
        if (weaken > 0) values[StatId.ATTACK_SPEED] = StatId.ATTACK_SPEED.sanitize(Num.multiply(values.getValue(StatId.ATTACK_SPEED), Num.power(Num.decimal("0.85"), Num.decimal(weaken))))
        if (corrode > 0) values[StatId.DEFENSE] = StatId.DEFENSE.sanitize(Num.multiply(values.getValue(StatId.DEFENSE), Num.power(Num.decimal("0.8"), Num.decimal(corrode))))
        return PlayerStats(values)
    }

    private fun effectiveMaxHp(run: RunState): BigDecimal = run.maxHpSnapshot
    private fun ruleAmount(run: RunState, trigger: Trigger, kind: EffectKind, target: EnemyState?): BigDecimal = run.cards
        .flatMap { it.rules }
        .filter { it.trigger == trigger && it.kind == kind && conditionMatches(it, target, run) }
        .fold(Num.ZERO) { total, effect -> Num.add(total, effect.amount) }
    private fun ruleStacks(run: RunState, trigger: Trigger, status: StatusType, target: EnemyState): Int = run.cards
        .flatMap { it.rules }
        .filter { it.trigger == trigger && it.kind == EffectKind.APPLY_STATUS && it.status == status && conditionMatches(it, target, run) }
        .fold(0) { total, effect -> total + Num.toInt(effect.amount, 1, 20) }
    private fun conditionMatches(effect: CardEffect, target: EnemyState?, run: RunState): Boolean = when (effect.condition) {
        EffectCondition.ALWAYS -> true
        EffectCondition.BELOW_40 -> StatsEngine.playerHealthRatio(run, effectiveMaxHp(run)) < Num.decimal("0.4")
        EffectCondition.BELOW_25 -> StatsEngine.playerHealthRatio(run, effectiveMaxHp(run)) < Num.decimal("0.25")
        EffectCondition.ABOVE_60 -> StatsEngine.playerHealthRatio(run, effectiveMaxHp(run)) > Num.decimal("0.6")
        EffectCondition.TARGET_CHILLED -> target?.statuses?.any { it.type == StatusType.FREEZE } == true
        EffectCondition.TARGET_MARKED -> target?.statuses?.any { it.type == StatusType.MARK } == true
        EffectCondition.TARGET_BELOW_HALF -> target != null && target.hp.compareTo(Num.multiply(target.maxHp, Num.decimal("0.5"))) <= 0
        EffectCondition.EVERY_FIFTH -> run.player.attackCount % 5L == 0L
        EffectCondition.ANOMALOUS -> run.player.attackCount % 7L == 0L
    }
    private fun updateProjectiles(run: RunState, profile: MetaProfile, dt: Float) {
        run.projectiles.toList().forEach { projectile ->
            projectile.life -= dt
            if (projectile.team == ProjectileTeam.PLAYER) {
                val target = projectile.homingId?.let { id -> run.enemies.firstOrNull { it.id == id && it.hp.compareTo(Num.ZERO) > 0 } }
                if (target != null) {
                    projectile.targetX = target.position.x
                    projectile.targetY = target.position.y
                }
            }
            val direction = Vec2(projectile.targetX - projectile.position.x, projectile.targetY - projectile.position.y)
            val step = projectile.speed * dt
            if (direction.length() <= step) {
                projectile.position.x = projectile.targetX
                projectile.position.y = projectile.targetY
                if (projectile.team == ProjectileTeam.PLAYER) {
                    run.enemies.filter { distance(projectile.position, it.position) <= it.radius + projectile.radius }.forEach { enemy ->
                        damageEnemy(run, profile, enemy, projectile.damage, projectile.position, projectile.color, false)
                        if (projectile.status != null && run.rng.nextDouble() < Num.toDouble(projectile.statusChance)) applyStatus(enemy, projectile.status, 1, 3f, Num.ONE)
                    }
                } else if (distance(projectile.position, run.player.position) <= CombatRules.PLAYER_RADIUS + projectile.radius) {
                    damagePlayer(run, profile, null, projectile.damage)
                }
                projectile.life = 0f
            } else {
                projectile.position.addInPlace(direction.normalized().multiply(step))
            }
        }
    }

    private fun separateEnemies(run: RunState) {
        for (first in 0 until run.enemies.lastIndex) {
            for (second in first + 1 until run.enemies.size) {
                val a = run.enemies[first]
                val b = run.enemies[second]
                if (a.hp.compareTo(Num.ZERO) <= 0 || b.hp.compareTo(Num.ZERO) <= 0) continue
                val delta = b.position.subtract(a.position)
                val distanceValue = delta.length()
                val minimum = a.radius + b.radius
                if (distanceValue > 0.01f && distanceValue < minimum) {
                    val push = delta.normalized().multiply((minimum - distanceValue) * 0.5f)
                    a.position.subtractInPlace(push)
                    b.position.addInPlace(push)
                }
            }
        }
    }

    private fun rollChallenge(run: RunState) {
        if (run.stage < 3L || run.rng.nextDouble() > 0.16) {
            run.challenge = ChallengeModifier.NONE
            return
        }
        val choices = ChallengeModifier.entries.filter { it != ChallengeModifier.NONE && it != run.challenge }
        run.challenge = choices[run.rng.nextInt(choices.size)]
    }

    private fun attackInterval(enemy: EnemyState): Float = when (enemy.type) {
        EnemyType.SWARM -> 0.95f
        EnemyType.MAGE -> 1.8f
        EnemyType.BOSS -> 1.15f
        EnemyType.ANOMALY -> 1.35f
        else -> 1.45f
    }

    private fun initialTick(type: StatusType): Float = when (type) {
        StatusType.BURN -> 0.5f
        StatusType.POISON -> 1f
        StatusType.SHOCK -> 0.75f
        else -> 1f
    }

    private fun statusTickInterval(type: StatusType): Float = when (type) {
        StatusType.BURN -> 0.5f
        StatusType.POISON -> 1f
        StatusType.SHOCK -> 0.75f
        else -> 1f
    }

    private fun clampPosition(position: Vec2, radius: Float) {
        position.x = position.x.coerceIn(radius, CombatRules.ARENA_WIDTH - radius)
        position.y = position.y.coerceIn(radius + 12f, CombatRules.ARENA_HEIGHT - radius - 18f)
    }

    private fun distance(first: Vec2, second: Vec2): Float = sqrt((first.x - second.x) * (first.x - second.x) + (first.y - second.y) * (first.y - second.y))
}
