package com.elthon.infinite

import com.elthon.infinite.core.CardEngine
import com.elthon.infinite.core.CardGenerator
import com.elthon.infinite.core.CombatEngine
import com.elthon.infinite.core.CombatInput
import com.elthon.infinite.core.CombatPhase
import com.elthon.infinite.core.GameContent
import com.elthon.infinite.core.GameSave
import com.elthon.infinite.core.MetaProfile
import com.elthon.infinite.core.MetaUpgrade
import com.elthon.infinite.core.Num
import com.elthon.infinite.core.OfflineProgress
import com.elthon.infinite.core.ProgressionEngine
import com.elthon.infinite.core.Rarity
import com.elthon.infinite.core.Rng
import com.elthon.infinite.core.SaveCodec
import com.elthon.infinite.core.ScalingPolicy
import com.elthon.infinite.core.StatId
import com.elthon.infinite.core.StatsEngine
import com.elthon.infinite.core.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal

class FoundationTest {
    @Test
    fun formattingCoversSmallAndLargeValues() {
        assertEquals("0", Num.format(Num.ZERO))
        assertEquals("12", Num.format(Num.decimal(12)))
        assertEquals("1.5", Num.format(Num.decimal("1.5")))
        assertEquals("1.23K", Num.format(Num.decimal(1234)))
        assertEquals("12.3K", Num.format(Num.decimal(12345)))
        assertTrue(Num.format(Num.decimal("1.5e30")).endsWith("e30"))
        assertEquals("1,024", Num.formatStage(1024))
        assertEquals("12.3M", Num.formatStage(12_300_000))
    }

    @Test
    fun powerAndLogStayStable() {
        val eight = Num.power(Num.decimal(2), Num.decimal(3))
        assertTrue("2^3 should be 8, was $eight", eight.subtract(Num.decimal(8)).abs() < Num.decimal("0.000001"))
        assertEquals(Num.ONE, Num.power(Num.ONE, Num.decimal(5)))
        val big = Num.power(Num.decimal(10), Num.decimal(40))
        assertTrue(big > Num.decimal(9999))
        assertTrue(Num.isFinite(big))
        val log = Num.ln(Num.decimal(100))
        assertTrue("ln(100) was $log", log.subtract(Num.decimal("4.60517")).abs() < Num.decimal("0.00001"))
        assertTrue(Num.exp(Num.decimal(1)).subtract(Num.decimal("2.71828")).abs() < Num.decimal("0.00001"))
    }

    @Test
    fun rngIsDeterministicAndRestorable() {
        val first = Rng(42L)
        val second = Rng(42L)
        repeat(64) { assertEquals(first.nextLong(), second.nextLong()) }
        val stateful = Rng(7L)
        repeat(10) { stateful.nextLong() }
        val snapshot = stateful.state()
        val expected = LongArray(20) { stateful.nextLong() }
        stateful.restore(snapshot)
        val actual = LongArray(20) { stateful.nextLong() }
        assertTrue(expected.contentEquals(actual))
        assertTrue(Rng(1L).nextLong(10L) in 0L until 10L)
        assertTrue(Rng(1L).nextInt(7) in 0 until 7)
    }

    @Test
    fun vectorMutatorsAffectTheReceiver() {
        val position = Vec2(10f, 20f)
        position.addInPlace(Vec2(5f, -5f))
        assertEquals(15f, position.x, 0.0001f)
        position.subtractInPlace(Vec2(5f, 5f))
        assertEquals(10f, position.x, 0.0001f)
        assertEquals(10f, position.y, 0.0001f)
        val fresh = Vec2(3f, 4f)
        assertEquals(5f, fresh.length(), 0.0001f)
        assertEquals(1f, fresh.normalized().length(), 0.0001f)
    }
}

class ProgressionTest {
    @Test
    fun scalingIsMonotonicAndBounded() {
        var previous = Num.ZERO
        for (stage in 1L..500L) {
            val hp = ScalingPolicy.hp(stage)
            assertTrue("hp must grow at stage $stage", hp > previous)
            previous = hp
            assertTrue(Num.isFinite(hp))
        }
        assertTrue(ScalingPolicy.enemyCount(1L) >= 1)
        assertTrue(ScalingPolicy.enemyCount(9_000L) <= 8)
    }

    @Test
    fun metaPurchaseSpendsAetherAndRespectsRanks() {
        val profile = MetaProfile()
        profile.aether = Num.decimal(5)
        assertTrue(!ProgressionEngine.purchaseMeta(profile, MetaUpgrade.VITALITY))
        profile.aether = Num.decimal(1000)
        assertTrue(ProgressionEngine.purchaseMeta(profile, MetaUpgrade.VITALITY))
        assertEquals(1, profile.rank(MetaUpgrade.VITALITY))
        repeat(5) { ProgressionEngine.purchaseMeta(profile, MetaUpgrade.VITALITY) }
        assertEquals(MetaUpgrade.VITALITY.maximumRank, profile.rank(MetaUpgrade.VITALITY))
        assertTrue(!ProgressionEngine.purchaseMeta(profile, MetaUpgrade.VITALITY))
    }

    @Test
    fun lockedUpgradesCannotBeBought() {
        val profile = MetaProfile()
        profile.aether = Num.decimal(100_000)
        assertTrue(!ProgressionEngine.purchaseMeta(profile, MetaUpgrade.INSIGHT))
        profile.bestStage = 6
        assertTrue(ProgressionEngine.purchaseMeta(profile, MetaUpgrade.INSIGHT))
    }

    @Test
    fun offlineRewardsAreCappedAndDeterministic() {
        val profile = MetaProfile()
        profile.lastSavedAt = 1_000_000L
        val capped = OfflineProgress.calculate(profile, 1_000_000L + 100L * 3600L)
        val expected = BigDecimal(OfflineProgress.MAX_OFFLINE_SECONDS / OfflineProgress.ENERGY_INTERVAL_SECONDS)
        assertEquals(expected, capped.energyAwarded)
        assertTrue(capped.capped)
        val short = OfflineProgress.calculate(profile, 1_000_000L + 400L)
        assertEquals(BigDecimal(3), short.energyAwarded)
        assertTrue(!short.capped)
        assertEquals(BigDecimal.ZERO, OfflineProgress.calculate(profile, 1_000L).energyAwarded)
    }

    @Test
    fun achievementsUnlockOnceAndPayAether() {
        val profile = MetaProfile()
        val run = ProgressionEngine.startRun(profile, 1_700_000_000_000L)
        run.stats.enemiesDefeated = 1
        ProgressionEngine.updateAchievements(profile, run)
        assertTrue(profile.achievements.contains("first_blood"))
        val afterFirst = profile.aether
        ProgressionEngine.updateAchievements(profile, run)
        assertEquals(afterFirst, profile.aether)
    }
}

class CardEngineTest {
    @Test
    fun offersAreUniqueAndDeterministicForTheSameSeed() {
        val profile = MetaProfile()
        val left = ProgressionEngine.startRun(profile, 123L)
        val right = ProgressionEngine.startRun(MetaProfile(), 123L)
        left.stage = 30L
        right.stage = 30L
        val first = CardGenerator.generateOffers(left, profile)
        val second = CardGenerator.generateOffers(right, MetaProfile())
        assertEquals(first.size, second.size)
        assertEquals(first.map { it.title to it.family }, second.map { it.title to it.family })
        assertEquals(first.size, first.map { it.family }.distinct().size)
    }

    @Test
    fun cardChoiceCountHonorsInsightAndSynergy() {
        val profile = MetaProfile()
        val run = ProgressionEngine.startRun(profile, 55L)
        profile.metaRanks[MetaUpgrade.INSIGHT] = 1
        val boosted = StatsEngine.calculate(run, profile)[StatId.CARD_CHOICES]
        assertTrue(boosted >= BigDecimal(4))
    }

    @Test
    fun pickingCardsRaisesStatsAndAdvancesStage() {
        val profile = MetaProfile()
        val run = ProgressionEngine.startRun(profile, 77L)
        run.enemies.clear()
        run.stageClearPending = true
        CombatEngine.finishStage(run, profile)
        assertEquals(CombatPhase.CARD_PICKER, run.phase)
        val offers = run.cardOffers
        assertTrue(offers.isNotEmpty())
        val attackBefore = StatsEngine.calculate(run, profile)[StatId.ATTACK]
        val chosen = CardEngine.select(run, profile, offers.first().id)
        assertNotNull(chosen)
        assertEquals(CombatPhase.IDLE, run.phase)
        val attackAfter = StatsEngine.calculate(run, profile)[StatId.ATTACK]
        assertTrue(attackAfter >= attackBefore)
        CombatEngine.advanceAfterChoice(run, profile)
        assertEquals(2L, run.stage)
        assertEquals(CombatPhase.FIGHTING, run.phase)
    }

    @Test
    fun rerollConsumesChargesAndRespectsLock() {
        val profile = MetaProfile()
        val run = ProgressionEngine.startRun(profile, 91L)
        run.enemies.clear()
        run.stageClearPending = true
        CombatEngine.finishStage(run, profile)
        val rerolls = run.rerolls
        assertTrue(CardEngine.reroll(run, profile))
        assertEquals(rerolls - 1, run.rerolls)
        run.rerolls = 0
        assertTrue(!CardEngine.reroll(run, profile))
    }

    @Test
    fun synergiesTriggerOnFamilyDiversity() {
        val profile = MetaProfile()
        val run = ProgressionEngine.startRun(profile, 33L)
        val inferno = CardGenerator.build(run, com.elthon.infinite.core.CardFamily.INFERNO, Rarity.RARE)
        val infernoTwo = CardGenerator.build(run, com.elthon.infinite.core.CardFamily.INFERNO, Rarity.EPIC)
        run.cards.add(inferno)
        run.cards.add(infernoTwo)
        val unlocked = com.elthon.infinite.core.SynergyEngine.recalculate(run)
        assertTrue(unlocked.any { it.first == "ember_cascade" })
        assertTrue(run.synergies.contains("ember_cascade"))
    }
}

class CombatTest {
    @Test
    fun stageSpawnsEnemiesInsideTheArena() {
        val profile = MetaProfile()
        val run = ProgressionEngine.startRun(profile, 5L)
        assertTrue(run.enemies.isNotEmpty())
        run.enemies.forEach { enemy ->
            assertTrue(enemy.hp > Num.ZERO)
            assertTrue(enemy.maxHp >= enemy.hp)
            assertTrue(enemy.position.x >= -enemy.radius)
            assertTrue(enemy.position.x <= 720f + enemy.radius)
        }
    }

    @Test
    fun playerActuallyMoves() {
        val profile = MetaProfile()
        val run = ProgressionEngine.startRun(profile, 9L)
        val startX = run.player.position.x
        val startY = run.player.position.y
        val input = CombatInput(moveX = 1f, moveY = 0f)
        repeat(30) { CombatEngine.step(run, profile, input, 1f / 60f) }
        assertTrue("player must move right", run.player.position.x > startX + 5f)
        assertTrue(run.player.position.y > startY - 0.001f)
    }

    @Test
    fun playerStaysInsideBoundsWhilePushingAgainstWalls() {
        val profile = MetaProfile()
        val run = ProgressionEngine.startRun(profile, 12L)
        val input = CombatInput(moveX = -1f, moveY = -1f)
        repeat(400) { CombatEngine.step(run, profile, input, 1f / 60f) }
        val position = run.player.position
        assertTrue(position.x >= 0f)
        assertTrue(position.y >= 0f)
        assertTrue(position.x <= 720f)
        assertTrue(position.y <= 900f)
    }

    @Test
    fun autoAttackKillsEnemiesAndClearsTheStage() {
        val profile = MetaProfile()
        val run = ProgressionEngine.startRun(profile, 21L)
        val input = CombatInput()
        var guard = 0
        while (run.phase == CombatPhase.FIGHTING && guard < 40_000) {
            run.player.hp = run.maxHpSnapshot
            CombatEngine.step(run, profile, input, 1f / 60f)
            guard++
        }
        assertTrue("combat should not stall", guard < 40_000)
        assertEquals(CombatPhase.CARD_PICKER, run.phase)
        assertTrue(run.stats.enemiesDefeated > 0)
        assertTrue(run.enemies.isEmpty())
    }

    @Test
    fun abilitiesCooldownAndConsumeInput() {
        val profile = MetaProfile()
        val run = ProgressionEngine.startRun(profile, 31L)
        val input = CombatInput(moveX = 1f, novaPressed = true, dashPressed = true)
        CombatEngine.step(run, profile, input, 1f / 60f)
        assertTrue(run.player.novaTimer > 0f)
        assertTrue(run.player.dashCooldown > 0f)
        assertTrue(!input.novaPressed)
        assertTrue(!input.dashPressed)
    }

    @Test
    fun fullAutoRunReachesDeepStagesWithoutCrashing() {
        val profile = MetaProfile()
        val run = ProgressionEngine.startRun(profile, 4_242L)
        val input = CombatInput()
        var guard = 0
        while (run.stage < 12L && run.phase != CombatPhase.GAME_OVER && guard < 400_000) {
            when (run.phase) {
                CombatPhase.FIGHTING -> {
                    run.player.hp = run.maxHpSnapshot
                    run.player.shield = Num.decimal(5_000)
                    CombatEngine.step(run, profile, input, 1f / 60f)
                }
                CombatPhase.CARD_PICKER -> {
                    val offers = run.cardOffers
                    if (offers.isEmpty()) fail("card picker offered nothing")
                    CardEngine.select(run, profile, offers.first().id)
                    CombatEngine.advanceAfterChoice(run, profile)
                }
                CombatPhase.EVENT -> {
                    val event = requireNotNull(run.specialEvent) { "event phase without an event" }
                    ProgressionEngine.resolveEvent(run, profile, event.choices.last().tag)
                    CombatEngine.advanceAfterChoice(run, profile)
                }
                else -> fail("unexpected phase ${run.phase}")
            }
            guard++
        }
        assertEquals(CombatPhase.FIGHTING, run.phase)
        assertTrue("run stalled at stage ${run.stage}", run.stage >= 12L)
        assertTrue(run.stats.enemiesDefeated > 20)
        assertTrue(run.stats.cardsCollected > 5)
        run.player.hp = Num.ZERO
        CombatEngine.step(run, profile, input, 1f / 60f)
        assertEquals(CombatPhase.GAME_OVER, run.phase)
        val summary = ProgressionEngine.finishRun(run, profile)
        assertTrue(summary.enemiesDefeated > 0)
        assertTrue(profile.totalRuns == 1L)
        val replay = ProgressionEngine.finishRun(run, profile)
        assertTrue("re-finishing a run must not pay twice", replay.aetherEarned.compareTo(Num.ZERO) == 0)
    }

    @Test
    fun eventChoicesAreExclusiveAndEffectful() {
        val profile = MetaProfile()
        val run = ProgressionEngine.startRun(profile, 61L)
        run.phase = CombatPhase.EVENT
        run.specialEvent = GameContent.specialEventFor(5L, run.rng) ?: run.specialEvent
        if (run.specialEvent == null) return
        val choice = run.specialEvent!!.choices.first()
        val before = run.cards.size
        assertTrue(ProgressionEngine.resolveEvent(run, profile, choice.tag))
        assertTrue(!ProgressionEngine.resolveEvent(run, profile, choice.tag))
        assertTrue(run.cards.size >= before)
        assertEquals(CombatPhase.IDLE, run.phase)
    }

}

class SaveCodecTest {
    @Test
    fun roundTripPreservesProfileAndRun() {
        val profile = MetaProfile()
        val run = ProgressionEngine.startRun(profile, 4_100L)
        repeat(3) {
            run.enemies.clear()
            run.stageClearPending = true
            CombatEngine.finishStage(run, profile)
            CardEngine.select(run, profile, run.cardOffers.first().id)
            CombatEngine.advanceAfterChoice(run, profile)
        }
        val original = GameSave(profile, run)
        val decoded = SaveCodec.decode(SaveCodec.encode(original))
        val restoredRun = decoded.run
        assertNotNull(restoredRun)
        requireNotNull(restoredRun)
        assertEquals(original.profile.aether, decoded.profile.aether)
        assertEquals(original.profile.bestStage, decoded.profile.bestStage)
        assertEquals(original.run!!.stage, restoredRun.stage)
        assertEquals(original.run!!.cards.size, restoredRun.cards.size)
        assertEquals(original.run!!.cards.map { it.id }, restoredRun.cards.map { it.id })
        assertEquals(original.run!!.player.hp, restoredRun.player.hp)
        assertEquals(original.run!!.maxHpSnapshot, restoredRun.maxHpSnapshot)
        assertEquals(original.run!!.enemies.map { it.id }, restoredRun.enemies.map { it.id })
        assertEquals(original.run!!.stats.enemiesDefeated, restoredRun.stats.enemiesDefeated)
    }

    @Test
    fun identicalSeedsProduceIdenticalSaves() {
        val left = ProgressionEngine.startRun(MetaProfile(), 8_888L)
        val right = ProgressionEngine.startRun(MetaProfile(), 8_888L)
        repeat(2) {
            left.enemies.clear(); right.enemies.clear()
            left.stageClearPending = true; right.stageClearPending = true
            CombatEngine.finishStage(left, MetaProfile())
            CombatEngine.finishStage(right, MetaProfile())
            CardEngine.select(left, MetaProfile(), left.cardOffers.first().id)
            CardEngine.select(right, MetaProfile(), right.cardOffers.first().id)
            CombatEngine.advanceAfterChoice(left, MetaProfile())
            CombatEngine.advanceAfterChoice(right, MetaProfile())
        }
        val leftBytes = SaveCodec.encode(GameSave(MetaProfile(), left))
        val rightBytes = SaveCodec.encode(GameSave(MetaProfile(), right))
        assertTrue(leftBytes.contentEquals(rightBytes))
    }

    @Test
    fun corruptedAndTruncatedSavesAreRejected() {
        val bytes = SaveCodec.encode(GameSave(MetaProfile(), null))
        val corrupted = bytes.copyOf()
        corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1] + 7).toByte()
        try {
            SaveCodec.decode(corrupted)
            fail("corrupted save must be rejected")
        } catch (expected: com.elthon.infinite.core.SaveException) {
            assertTrue(expected.message!!.isNotEmpty())
        }
        try {
            SaveCodec.decode(bytes.copyOf(bytes.size / 2))
            fail("truncated save must be rejected")
        } catch (expected: com.elthon.infinite.core.SaveException) {
            assertTrue(expected.message!!.isNotEmpty())
        }
        try {
            SaveCodec.decode(ByteArray(4))
            fail("empty save must be rejected")
        } catch (expected: com.elthon.infinite.core.SaveException) {
            assertTrue(expected.message!!.isNotEmpty())
        }
    }
}
