package com.elthon.infinite.core

import java.math.BigDecimal
import java.math.RoundingMode

object ProgressionEngine {
    fun startRun(profile: MetaProfile, now: Long): RunState {
        val seed = now xor profile.revision xor profile.totalRuns
        val run = StatsEngine.newRun(profile, seed, now)
        run.highestStage = 1L
        CombatEngine.beginRun(run, profile)
        updateAchievements(profile, run)
        return run
    }

    fun purchaseMeta(profile: MetaProfile, upgrade: MetaUpgrade): Boolean {
        val rank = profile.rank(upgrade)
        if (rank >= upgrade.maximumRank) return false
        if (profile.bestStage < upgrade.unlockStage) return false
        val cost = Num.decimal(upgrade.rankCosts[rank])
        if (profile.aether.compareTo(cost) < 0) return false
        profile.aether = Num.subtract(profile.aether, cost)
        profile.metaRanks[upgrade] = rank + 1
        profile.revision++
        return true
    }

    fun resolveEvent(run: RunState, profile: MetaProfile, tag: String): Boolean {
        if (run.phase != CombatPhase.EVENT || run.eventChoiceLocked || run.specialEvent == null) return false
        run.eventChoiceLocked = true
        when (tag) {
            "blood_pay" -> {
                val payment = Num.multiply(Num.max(Num.ONE, run.player.hp), Num.decimal("0.15"))
                run.player.hp = Num.max(Num.ONE, Num.subtract(run.player.hp, payment))
                CardEngine.awardFamily(run, profile, randomFamily(run, profile), Rarity.EPIC)
            }
            "blood_leave" -> profile.aether = Num.add(profile.aether, Num.decimal(4))
            "gamble" -> {
                val roll = run.rng.nextDouble()
                val rarity = when {
                    roll < 0.5 -> Rarity.RARE
                    roll < 0.85 -> Rarity.EPIC
                    roll < 0.99 -> Rarity.LEGENDARY
                    else -> Rarity.MYTHIC
                }
                CardEngine.awardFamily(run, profile, randomFamily(run, profile), rarity)
            }
            "gamble_leave" -> {
                run.maxHpSnapshot = Num.add(run.maxHpSnapshot, Num.decimal(8))
                run.player.hp = Num.add(run.player.hp, Num.decimal(8))
            }
            "weapon_attack" -> {
                profile.aether = Num.add(profile.aether, Num.decimal(12))
                CardEngine.awardFamily(run, profile, CardFamily.VOLT, Rarity.RARE)
            }
            "weapon_repair" -> {
                run.player.hp = Num.min(run.maxHpSnapshot, Num.add(run.player.hp, Num.multiply(run.maxHpSnapshot, Num.decimal("0.35"))))
                run.player.shield = Num.add(run.player.shield, Num.decimal(5))
            }
            "gate_pay" -> {
                run.maxHpSnapshot = Num.max(Num.decimal(20), Num.subtract(run.maxHpSnapshot, Num.decimal(8)))
                run.player.hp = Num.min(run.player.hp, run.maxHpSnapshot)
                CardEngine.awardFamily(run, profile, randomFamily(run, profile), Rarity.RARE)
            }
            "gate_refuse" -> CardEngine.awardFamily(run, profile, randomFamily(run, profile), Rarity.UNCOMMON)
            else -> return false
        }
        run.stats.eventsResolved++
        run.specialEvent = null
        run.phase = CombatPhase.IDLE
        run.eventChoiceLocked = false
        run.revision++
        updateAchievements(profile, run)
        return true
    }

    fun updateAchievements(profile: MetaProfile, run: RunState?) {
        val unlocked = mutableListOf<String>()
        GameContent.achievements.forEach { achievement ->
            if (profile.achievements.contains(achievement.id)) return@forEach
            val progress = when (achievement.id) {
                "first_blood" -> run?.stats?.enemiesDefeated ?: profile.totalKills
                "stage_10" -> maxOf(profile.bestStage, run?.highestStage ?: 0L)
                "stage_50" -> maxOf(profile.bestStage, run?.highestStage ?: 0L)
                "stage_100" -> maxOf(profile.bestStage, run?.highestStage ?: 0L)
                "anomaly" -> (run?.stats?.anomalyDiscoveries ?: profile.achievementProgress[achievement.id] ?: 0).toLong()
                "massive_hit" -> if ((run?.stats?.bestHit ?: profile.biggestHit).compareTo(Num.decimal(achievement.target)) >= 0) achievement.target.toLong() else 0L
                "last_breath" -> if ((run?.stats?.lowestHealthRatio ?: Num.ONE) <= Num.decimal("0.1") && (run?.stats?.stagesCleared ?: 0L) > 0L) 1L else 0L
                "synergy" -> (run?.synergies?.size ?: 0).toLong()
                "collector" -> (run?.stats?.cardsCollected ?: 0).toLong()
                "veteran" -> profile.totalRuns
                "architect" -> profile.metaRanks.values.sum().toLong()
                "executioner" -> maxOf(profile.totalKills, run?.stats?.enemiesDefeated ?: 0L)
                else -> 0L
            }.coerceAtLeast(0L)
            val bounded = minOf(progress, achievement.target.toLong()).toInt()
            profile.achievementProgress[achievement.id] = maxOf(profile.achievementProgress[achievement.id] ?: 0, bounded)
            if (progress >= achievement.target.toLong() && bounded >= achievement.target) {
                profile.achievements.add(achievement.id)
                profile.aether = Num.add(profile.aether, Num.decimal(achievement.reward))
                unlocked.add(achievement.title)
            }
        }
        if (run != null) {
            if (run.stage > profile.bestStage) profile.bestStage = run.stage
            if (run.stats.bestHit > profile.biggestHit) profile.biggestHit = run.stats.bestHit
            if (run.pendingSynergies.isNotEmpty()) {
                run.stats.synergiesActivated += run.pendingSynergies.size
                run.pendingSynergies.clear()
            }
            if (run.cards.any { it.rarity == Rarity.ANOMALOUS }) run.stats.anomalyDiscoveries = maxOf(run.stats.anomalyDiscoveries, 1)
        }
        unlocked.forEach { title ->
            run?.emit(GameEvent(GameEventType.ACHIEVEMENT, text = "ACHIEVEMENT: $title", color = 0xFFFFD166.toInt()))
        }
    }

    fun finishRun(run: RunState, profile: MetaProfile): RunSummary {
        val achievementsBefore = profile.achievements.toSet()
        val alreadySettled = profile.settledRunIds.contains(run.runId)
        if (!alreadySettled) {
            profile.settledRunIds.add(run.runId)
            if (profile.settledRunIds.size > 200) {
                val removeCount = profile.settledRunIds.size - 200
                profile.settledRunIds.toList().take(removeCount).forEach { profile.settledRunIds.remove(it) }
            }
            profile.totalRuns++
            profile.totalKills += run.stats.enemiesDefeated
            profile.bestStage = maxOf(profile.bestStage, run.highestStage)
            profile.biggestHit = Num.max(profile.biggestHit, run.stats.bestHit)
        }
        updateAchievements(profile, run)
        val reward = if (alreadySettled) Num.ZERO else ScalingPolicy.reward(
            run.stats.stagesCleared,
            run.stats.enemiesDefeated,
            run.stats.elitesDefeated,
            run.stats.eventsResolved,
            profile.rank(MetaUpgrade.SALVAGE)
        )
        profile.aether = Num.add(profile.aether, reward)
        profile.totalAetherEarned = Num.add(profile.totalAetherEarned, reward)
        val actualNew = GameContent.achievements.filter { it.id !in achievementsBefore && profile.achievements.contains(it.id) }.map { it.title }
        profile.revision++
        return RunSummary(
            stageReached = run.highestStage,
            enemiesDefeated = run.stats.enemiesDefeated,
            cardsCollected = run.stats.cardsCollected,
            bestDamage = run.stats.bestHit,
            aetherEarned = reward,
            synergies = run.synergies.size,
            newAchievements = actualNew
        )
    }

    private fun randomFamily(run: RunState, profile: MetaProfile): CardFamily {
        val unlocked = CardFamily.entries.filter { it.unlockStage <= maxOf(1L, profile.bestStage) }
        return unlocked[run.rng.nextInt(unlocked.size)]
    }
}

object OfflineProgress {
    const val MAX_OFFLINE_SECONDS = 8L * 60L * 60L
    const val ENERGY_INTERVAL_SECONDS = 120L

    fun calculate(profile: MetaProfile, now: Long): OfflineReport {
        val previous = profile.lastSavedAt
        if (previous <= 0L || now <= previous) return OfflineReport(0L, Num.ZERO, false)
        val rawElapsed = now - previous
        val elapsed = minOf(rawElapsed, MAX_OFFLINE_SECONDS)
        val intervals = elapsed / ENERGY_INTERVAL_SECONDS
        val energy = Num.decimal(intervals).setScale(0, RoundingMode.DOWN)
        return OfflineReport(elapsed, energy, rawElapsed > MAX_OFFLINE_SECONDS)
    }

    fun claim(profile: MetaProfile, now: Long): OfflineReport {
        val report = calculate(profile, now)
        profile.signalEnergy = Num.add(profile.signalEnergy, report.energyAwarded)
        profile.totalOfflineEnergy = Num.add(profile.totalOfflineEnergy, report.energyAwarded)
        profile.lastSavedAt = now
        profile.lastOfflineClaimedAt = now
        profile.revision++
        return report
    }
}
