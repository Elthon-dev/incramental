package com.elthon.infinite.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.EnumMap
import java.util.LinkedHashSet
import java.util.zip.CRC32

class SaveException(message: String, cause: Throwable? = null) : Exception(message, cause)

object SaveCodec {
    const val VERSION = 1
    const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024
    private const val MAGIC = 0x494E464E

    fun encode(save: GameSave): ByteArray {
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { output ->
            writeSave(output, save)
        }
        val bytes = payload.toByteArray()
        require(bytes.size <= MAX_PAYLOAD_BYTES) { "save payload is too large" }
        val checksum = CRC32().apply { update(bytes) }.value
        return ByteArrayOutputStream().also { root ->
            DataOutputStream(root).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeInt(bytes.size)
                output.writeInt(checksum.toInt())
                output.write(bytes)
            }
        }.toByteArray()
    }

    fun decode(bytes: ByteArray): GameSave {
        if (bytes.size < 20) throw SaveException("save is truncated")
        return try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                if (input.readInt() != MAGIC) throw SaveException("invalid save magic")
                val version = input.readInt()
                if (version != VERSION) throw SaveException("unsupported save version $version")
                val length = input.readInt()
                val checksum = input.readInt()
                if (length < 0 || length > MAX_PAYLOAD_BYTES || length > bytes.size - 20) throw SaveException("invalid save length")
                val payload = ByteArray(length)
                var offset = 0
                while (offset < length) {
                    val read = input.read(payload, offset, length - offset)
                    if (read < 0) throw SaveException("save payload is truncated")
                    offset += read
                }
                if (input.available() != 0) throw SaveException("save has trailing data")
                val actual = CRC32().apply { update(payload) }.value.toInt()
                if (actual != checksum) throw SaveException("save checksum mismatch")
                DataInputStream(ByteArrayInputStream(payload)).use { payloadInput ->
                    val save = readSave(payloadInput)
                    if (payloadInput.available() != 0) throw SaveException("save payload contains unexpected data")
                    save
                }
            }
        } catch (error: SaveException) {
            throw error
        } catch (error: Exception) {
            throw SaveException("save could not be decoded", error)
        }
    }

    private fun writeSave(output: DataOutputStream, save: GameSave) {
        writeProfile(output, save.profile)
        output.writeBoolean(save.run != null)
        if (save.run != null) writeRun(output, save.run)
    }

    private fun readSave(input: DataInputStream): GameSave {
        val profile = readProfile(input)
        val run = if (input.readBoolean()) readRun(input) else null
        return GameSave(profile, run)
    }

    private fun writeProfile(output: DataOutputStream, profile: MetaProfile) {
        writeBigDecimal(output, profile.aether)
        writeBigDecimal(output, profile.signalEnergy)
        output.writeInt(profile.metaRanks.size)
        profile.metaRanks.forEach { (upgrade, rank) ->
            writeString(output, upgrade.name)
            output.writeInt(rank)
        }
        writeStringSet(output, profile.discoveredCards)
        output.writeInt(profile.discoveredFamilies.size)
        profile.discoveredFamilies.forEach { writeString(output, it.name) }
        writeStringSet(output, profile.achievements)
        output.writeInt(profile.achievementProgress.size)
        profile.achievementProgress.forEach { (id, value) ->
            writeString(output, id)
            output.writeInt(value)
        }
        output.writeBoolean(profile.settings.musicEnabled)
        output.writeBoolean(profile.settings.soundEnabled)
        output.writeBoolean(profile.settings.hapticsEnabled)
        output.writeBoolean(profile.settings.reducedMotion)
        output.writeBoolean(profile.settings.showDamageNumbers)
        output.writeLong(profile.bestStage)
        output.writeLong(profile.totalRuns)
        output.writeLong(profile.totalKills)
        output.writeLong(profile.totalCards)
        writeBigDecimal(output, profile.totalAetherEarned)
        writeBigDecimal(output, profile.biggestHit)
        writeBigDecimal(output, profile.totalOfflineEnergy)
        writeStringSet(output, profile.settledRunIds)
        output.writeLong(profile.lastSavedAt)
        output.writeLong(profile.lastOfflineClaimedAt)
        output.writeLong(profile.revision)
    }

    private fun readProfile(input: DataInputStream): MetaProfile {
        val profile = MetaProfile()
        profile.aether = readBigDecimal(input)
        profile.signalEnergy = readBigDecimal(input)
        val rankCount = boundedCount(input.readInt(), MetaUpgrade.entries.size, "meta ranks")
        repeat(rankCount) {
            val upgrade = enumValue<MetaUpgrade>(readString(input, 64))
            val rank = boundedInt(input.readInt(), 0, upgrade.maximumRank, "meta rank")
            profile.metaRanks[upgrade] = rank
        }
        profile.discoveredCards = readStringSet(input, 5000)
        val familyCount = boundedCount(input.readInt(), CardFamily.entries.size, "card families")
        repeat(familyCount) { profile.discoveredFamilies.add(enumValue<CardFamily>(readString(input, 64))) }
        profile.achievements = readStringSet(input, 1000)
        val progressCount = boundedCount(input.readInt(), 1000, "achievement progress")
        repeat(progressCount) { profile.achievementProgress[readString(input, 128)] = input.readInt().coerceAtLeast(0) }
        profile.settings.musicEnabled = input.readBoolean()
        profile.settings.soundEnabled = input.readBoolean()
        profile.settings.hapticsEnabled = input.readBoolean()
        profile.settings.reducedMotion = input.readBoolean()
        profile.settings.showDamageNumbers = input.readBoolean()
        profile.bestStage = input.readLong().coerceIn(0L, Long.MAX_VALUE)
        profile.totalRuns = input.readLong().coerceAtLeast(0L)
        profile.totalKills = input.readLong().coerceAtLeast(0L)
        profile.totalCards = input.readLong().coerceAtLeast(0L)
        profile.totalAetherEarned = readBigDecimal(input)
        profile.biggestHit = readBigDecimal(input)
        profile.totalOfflineEnergy = readBigDecimal(input)
        profile.settledRunIds = readStringSet(input, 500)
        profile.lastSavedAt = input.readLong().coerceAtLeast(0L)
        profile.lastOfflineClaimedAt = input.readLong().coerceAtLeast(0L)
        profile.revision = input.readLong().coerceAtLeast(0L)
        return profile
    }

    private fun writeRun(output: DataOutputStream, run: RunState) {
        writeString(output, run.runId)
        output.writeLong(run.seed)
        output.writeInt(run.rng.state().size)
        run.rng.state().forEach(output::writeLong)
        output.writeLong(run.stage)
        output.writeLong(run.highestStage)
        writeString(output, run.phase.name)
        writeString(output, run.challenge.name)
        writeSpecialEvent(output, run.specialEvent)
        writePlayer(output, run.player)
        writeStats(output, run.stats)
        output.writeInt(run.enemies.size)
        run.enemies.forEach { writeEnemy(output, it) }
        output.writeInt(run.projectiles.size)
        run.projectiles.forEach { writeProjectile(output, it) }
        output.writeInt(run.cards.size)
        run.cards.forEach { writeCard(output, it) }
        output.writeInt(run.cardOffers.size)
        run.cardOffers.forEach { writeCard(output, it) }
        writeStringSet(output, run.synergies)
        writeStringSet(output, run.pendingSynergies)
        output.writeLong(run.nextEntityId)
        output.writeLong(run.nextProjectileId)
        output.writeInt(run.rarePity.coerceIn(0, 1000))
        output.writeInt(run.rerolls.coerceIn(0, 100))
        output.writeInt(run.cardChoiceBonus.coerceIn(0, 100))
        output.writeFloat(run.combatTime.coerceIn(0f, 86_400f))
        writeBigDecimal(output, run.maxHpSnapshot)
        output.writeBoolean(run.stageClearPending)
        output.writeBoolean(run.cardChoiceLocked)
        output.writeBoolean(run.eventChoiceLocked)
        output.writeLong(run.revision)
    }

    private fun readRun(input: DataInputStream): RunState {
        val runId = readString(input, 128)
        val seed = input.readLong()
        val stateSize = input.readInt()
        if (stateSize != 4) throw SaveException("invalid RNG state")
        val state = LongArray(4) { input.readLong() }
        val rng = Rng(seed)
        rng.restore(state)
        val run = RunState(runId, seed, rng)
        run.stage = input.readLong().coerceIn(1L, Long.MAX_VALUE)
        run.highestStage = input.readLong().coerceIn(1L, Long.MAX_VALUE)
        run.phase = enumValue<CombatPhase>(readString(input, 64))
        run.challenge = enumValue<ChallengeModifier>(readString(input, 64))
        run.specialEvent = readSpecialEvent(input)
        run.player = readPlayer(input)
        run.stats = readStats(input)
        val enemyCount = boundedCount(input.readInt(), 64, "enemies")
        repeat(enemyCount) { run.enemies.add(readEnemy(input)) }
        val projectileCount = boundedCount(input.readInt(), 256, "projectiles")
        repeat(projectileCount) { run.projectiles.add(readProjectile(input)) }
        val cardCount = boundedCount(input.readInt(), 2000, "cards")
        repeat(cardCount) { run.cards.add(readCard(input)) }
        val offerCount = boundedCount(input.readInt(), 8, "card offers")
        repeat(offerCount) { run.cardOffers.add(readCard(input)) }
        run.synergies = readStringSet(input, 100)
        run.pendingSynergies = readStringSet(input, 100)
        run.nextEntityId = input.readLong().coerceAtLeast(1L)
        run.nextProjectileId = input.readLong().coerceAtLeast(1L)
        run.rarePity = input.readInt().coerceIn(0, 1000)
        run.rerolls = input.readInt().coerceIn(0, 100)
        run.cardChoiceBonus = input.readInt().coerceIn(0, 100)
        run.combatTime = input.readFloat().coerceIn(0f, 86_400f) 
        run.maxHpSnapshot = readBigDecimal(input).coerceAtLeast(Num.ONE)
        run.stageClearPending = input.readBoolean()
        run.cardChoiceLocked = input.readBoolean()
        run.eventChoiceLocked = input.readBoolean()
        run.revision = input.readLong().coerceAtLeast(0L)
        return run
    }

    private fun writeSpecialEvent(output: DataOutputStream, event: SpecialEventState?) {
        output.writeBoolean(event != null)
        if (event == null) return
        writeString(output, event.type.name)
        output.writeInt(event.choices.size)
        event.choices.forEach { choice ->
            writeString(output, choice.title)
            writeString(output, choice.description)
            writeString(output, choice.tag)
        }
    }

    private fun readSpecialEvent(input: DataInputStream): SpecialEventState? {
        if (!input.readBoolean()) return null
        val type = enumValue<SpecialEventType>(readString(input, 64))
        val count = boundedCount(input.readInt(), 4, "event choices")
        val choices = mutableListOf<SpecialEventChoice>()
        repeat(count) { choices.add(SpecialEventChoice(readString(input, 128), readString(input, 512), readString(input, 64))) }
        return SpecialEventState(type, choices)
    }

    private fun writePlayer(output: DataOutputStream, player: PlayerState) {
        writeVec(output, player.position)
        writeBigDecimal(output, player.hp)
        writeBigDecimal(output, player.shield)
        writeVec(output, player.moveInput)
        output.writeFloat(player.facing)
        output.writeFloat(player.attackTimer)
        output.writeFloat(player.novaTimer)
        output.writeFloat(player.dashTimer)
        output.writeFloat(player.dashCooldown)
        output.writeFloat(player.dashRemaining)
        output.writeFloat(player.invulnerable)
        output.writeLong(player.attackCount.coerceAtLeast(0L))
        output.writeBoolean(player.lowHealthTriggered)
        output.writeFloat(player.lastHitAge)
        writeStatuses(output, player.statuses)
    }

    private fun readPlayer(input: DataInputStream): PlayerState {
        val position = readVec(input, 2000f)
        val hp = readBigDecimal(input).coerceAtLeast(Num.ZERO)
        val shield = readBigDecimal(input).coerceAtLeast(Num.ZERO)
        val move = readVec(input, 2f)
        val player = PlayerState(position, hp, shield, move)
        player.facing = input.readFloat().finite()
        player.attackTimer = input.readFloat().coerceIn(0f, 10f)
        player.novaTimer = input.readFloat().coerceIn(0f, 60f)
        player.dashTimer = input.readFloat().coerceIn(0f, 60f)
        player.dashCooldown = input.readFloat().coerceIn(0f, 60f)
        player.dashRemaining = input.readFloat().coerceIn(0f, 10f)
        player.invulnerable = input.readFloat().coerceIn(0f, 60f)
        player.attackCount = input.readLong().coerceAtLeast(0L)
        player.lowHealthTriggered = input.readBoolean()
        player.lastHitAge = input.readFloat().coerceIn(0f, 60f)
        player.statuses.addAll(readStatuses(input, 32))
        return player
    }

    private fun writeStats(output: DataOutputStream, stats: CombatStats) {
        output.writeLong(stats.enemiesDefeated.coerceAtLeast(0L))
        output.writeLong(stats.elitesDefeated.coerceAtLeast(0L))
        output.writeLong(stats.bossesDefeated.coerceAtLeast(0L))
        writeBigDecimal(output, stats.damageDealt)
        writeBigDecimal(output, stats.damageTaken)
        writeBigDecimal(output, stats.healingDone)
        writeBigDecimal(output, stats.bestHit)
        output.writeInt(stats.cardsCollected.coerceIn(0, 100_000))
        output.writeLong(stats.stagesCleared.coerceAtLeast(0L))
        output.writeInt(stats.eventsResolved.coerceIn(0, 100_000))
        output.writeInt(stats.synergiesActivated.coerceIn(0, 100_000))
        output.writeInt(stats.anomalyDiscoveries.coerceIn(0, 100_000))
        writeBigDecimal(output, stats.lowestHealthRatio.coerceIn(Num.ZERO, Num.ONE))
        output.writeInt(stats.statusApplications.coerceIn(0, 1_000_000))
        output.writeLong(stats.abilitiesUsed.coerceAtLeast(0L))
    }

    private fun readStats(input: DataInputStream): CombatStats {
        val stats = CombatStats()
        stats.enemiesDefeated = input.readLong().coerceAtLeast(0L)
        stats.elitesDefeated = input.readLong().coerceAtLeast(0L)
        stats.bossesDefeated = input.readLong().coerceAtLeast(0L)
        stats.damageDealt = readBigDecimal(input).coerceAtLeast(Num.ZERO)
        stats.damageTaken = readBigDecimal(input).coerceAtLeast(Num.ZERO)
        stats.healingDone = readBigDecimal(input).coerceAtLeast(Num.ZERO)
        stats.bestHit = readBigDecimal(input).coerceAtLeast(Num.ZERO)
        stats.cardsCollected = input.readInt().coerceIn(0, 100_000)
        stats.stagesCleared = input.readLong().coerceAtLeast(0L)
        stats.eventsResolved = input.readInt().coerceIn(0, 100_000)
        stats.synergiesActivated = input.readInt().coerceIn(0, 100_000)
        stats.anomalyDiscoveries = input.readInt().coerceIn(0, 100_000)
        stats.lowestHealthRatio = readBigDecimal(input).coerceIn(Num.ZERO, Num.ONE)
        stats.statusApplications = input.readInt().coerceIn(0, 1_000_000)
        stats.abilitiesUsed = input.readLong().coerceAtLeast(0L)
        return stats
    }

    private fun writeEnemy(output: DataOutputStream, enemy: EnemyState) {
        output.writeLong(enemy.id)
        writeString(output, enemy.type.name)
        writeVec(output, enemy.position)
        writeBigDecimal(output, enemy.hp)
        writeBigDecimal(output, enemy.maxHp)
        writeBigDecimal(output, enemy.shield)
        writeBigDecimal(output, enemy.damage)
        writeBigDecimal(output, enemy.defense)
        output.writeFloat(enemy.speed.coerceIn(0f, 1000f))
        output.writeFloat(enemy.radius.coerceIn(5f, 200f))
        output.writeBoolean(enemy.elite)
        output.writeBoolean(enemy.boss)
        output.writeFloat(enemy.attackTimer.coerceIn(-10f, 60f))
        writeString(output, enemy.phase.name)
        output.writeFloat(enemy.phaseTimer.coerceIn(-10f, 60f))
        output.writeFloat(enemy.abilityTimer.coerceIn(-10f, 120f))
        output.writeFloat(enemy.targetX.coerceIn(-2000f, 2000f))
        output.writeFloat(enemy.targetY.coerceIn(-2000f, 2000f))
        output.writeBoolean(enemy.rage)
        output.writeFloat(enemy.attackFlash.coerceIn(0f, 10f))
        writeStatuses(output, enemy.statuses)
        output.writeLong(enemy.statusSeed)
    }

    private fun readEnemy(input: DataInputStream): EnemyState {
        val id = input.readLong()
        val type = enumValue<EnemyType>(readString(input, 64))
        val position = readVec(input, 2000f)
        val hp = readBigDecimal(input).coerceAtLeast(Num.ZERO)
        val maxHp = readBigDecimal(input).coerceAtLeast(Num.ONE)
        val shield = readBigDecimal(input).coerceAtLeast(Num.ZERO)
        val damage = readBigDecimal(input).coerceAtLeast(Num.ZERO)
        val defense = readBigDecimal(input).coerceAtLeast(Num.ZERO)
        val speed = input.readFloat().coerceIn(0f, 1000f).finite()
        val radius = input.readFloat().coerceIn(5f, 200f).finite()
        val elite = input.readBoolean()
        val boss = input.readBoolean()
        val enemy = EnemyState(id, type, position, hp, maxHp, shield, damage, defense, speed, radius, elite, boss)
        enemy.attackTimer = input.readFloat().coerceIn(-10f, 60f)
        enemy.phase = enumValue<AttackPhase>(readString(input, 64))
        enemy.phaseTimer = input.readFloat().coerceIn(-10f, 60f)
        enemy.abilityTimer = input.readFloat().coerceIn(-10f, 120f)
        enemy.targetX = input.readFloat().coerceIn(-2000f, 2000f)
        enemy.targetY = input.readFloat().coerceIn(-2000f, 2000f)
        enemy.rage = input.readBoolean()
        enemy.attackFlash = input.readFloat().coerceIn(0f, 10f)
        enemy.statuses.addAll(readStatuses(input, 32))
        enemy.statusSeed = input.readLong()
        return enemy
    }

    private fun writeProjectile(output: DataOutputStream, projectile: ProjectileState) {
        output.writeLong(projectile.id)
        writeString(output, projectile.team.name)
        writeVec(output, projectile.position)
        output.writeFloat(projectile.targetX.coerceIn(-2000f, 2000f))
        output.writeFloat(projectile.targetY.coerceIn(-2000f, 2000f))
        output.writeFloat(projectile.speed.coerceIn(0f, 2000f))
        writeBigDecimal(output, projectile.damage)
        output.writeFloat(projectile.radius.coerceIn(1f, 100f))
        output.writeInt(projectile.color)
        output.writeInt(projectile.piercing.coerceIn(0, 100))
        output.writeFloat(projectile.life.coerceIn(-10f, 60f))
        output.writeBoolean(projectile.homingId != null)
        if (projectile.homingId != null) output.writeLong(projectile.homingId)
        output.writeBoolean(projectile.status != null)
        if (projectile.status != null) writeString(output, projectile.status.name)
        writeBigDecimal(output, projectile.statusChance)
        output.writeInt(projectile.chainCount.coerceIn(0, 100))
    }

    private fun readProjectile(input: DataInputStream): ProjectileState {
        val id = input.readLong()
        val team = enumValue<ProjectileTeam>(readString(input, 64))
        val position = readVec(input, 2000f)
        val targetX = input.readFloat().coerceIn(-2000f, 2000f)
        val targetY = input.readFloat().coerceIn(-2000f, 2000f)
        val speed = input.readFloat().coerceIn(0f, 2000f)
        val damage = readBigDecimal(input).coerceAtLeast(Num.ZERO)
        val radius = input.readFloat().coerceIn(1f, 100f)
        val color = input.readInt()
        val piercing = input.readInt().coerceIn(0, 100)
        val life = input.readFloat().coerceIn(-10f, 60f)
        val homing = if (input.readBoolean()) input.readLong() else null
        val status = if (input.readBoolean()) enumValue<StatusType>(readString(input, 64)) else null
        val chance = readBigDecimal(input).coerceIn(Num.ZERO, Num.ONE)
        val chain = input.readInt().coerceIn(0, 100)
        return ProjectileState(id, team, position, targetX, targetY, speed, damage, radius, color, piercing, life, homing, status, chance, chain)
    }

    private fun writeCard(output: DataOutputStream, card: CardDefinition) {
        writeString(output, card.id)
        writeString(output, card.title)
        writeString(output, card.family.name)
        writeString(output, card.rarity.name)
        output.writeInt(card.modifiers.size)
        card.modifiers.forEach { modifier ->
            writeString(output, modifier.stat.name)
            writeString(output, modifier.operation.name)
            writeBigDecimal(output, modifier.amount)
            writeString(output, modifier.condition.name)
        }
        output.writeInt(card.rules.size)
        card.rules.forEach { rule ->
            writeString(output, rule.kind.name)
            writeString(output, rule.trigger.name)
            writeBigDecimal(output, rule.amount)
            output.writeBoolean(rule.stat != null)
            if (rule.stat != null) writeString(output, rule.stat.name)
            output.writeBoolean(rule.status != null)
            if (rule.status != null) writeString(output, rule.status.name)
            writeString(output, rule.condition.name)
            writeString(output, rule.text)
        }
        writeStringSet(output, card.tags)
        output.writeLong(card.seed)
    }

    private fun readCard(input: DataInputStream): CardDefinition {
        val id = readString(input, 256)
        val title = readString(input, 128)
        val family = enumValue<CardFamily>(readString(input, 64))
        val rarity = enumValue<Rarity>(readString(input, 64))
        val modifierCount = boundedCount(input.readInt(), 32, "card modifiers")
        val modifiers = mutableListOf<StatModifier>()
        repeat(modifierCount) {
            val stat = enumValue<StatId>(readString(input, 64))
            val operation = enumValue<ModifierOperation>(readString(input, 64))
            val amount = readBigDecimal(input)
            val condition = enumValue<EffectCondition>(readString(input, 64))
            modifiers.add(StatModifier(stat, operation, amount, condition))
        }
        val ruleCount = boundedCount(input.readInt(), 32, "card rules")
        val rules = mutableListOf<CardEffect>()
        repeat(ruleCount) {
            val kind = enumValue<EffectKind>(readString(input, 64))
            val trigger = enumValue<Trigger>(readString(input, 64))
            val amount = readBigDecimal(input)
            val stat = if (input.readBoolean()) enumValue<StatId>(readString(input, 64)) else null
            val status = if (input.readBoolean()) enumValue<StatusType>(readString(input, 64)) else null
            val condition = enumValue<EffectCondition>(readString(input, 64))
            val text = readString(input, 512)
            rules.add(CardEffect(kind, trigger, amount, stat, status, condition, text))
        }
        val tags = readStringSet(input, 32)
        val seed = input.readLong()
        return CardDefinition(id, title, family, rarity, modifiers, rules, tags, seed)
    }

    private fun writeStatuses(output: DataOutputStream, statuses: List<StatusState>) {
        output.writeInt(statuses.size)
        statuses.forEach { status ->
            writeString(output, status.type.name)
            output.writeInt(status.stacks.coerceIn(0, 1000))
            output.writeFloat(status.remaining.coerceIn(-10f, 600f))
            output.writeFloat(status.tickTimer.coerceIn(-10f, 60f))
            writeBigDecimal(output, status.sourcePower)
        }
    }

    private fun readStatuses(input: DataInputStream, maximum: Int): List<StatusState> {
        val count = boundedCount(input.readInt(), maximum, "statuses")
        val statuses = mutableListOf<StatusState>()
        repeat(count) {
            val type = enumValue<StatusType>(readString(input, 64))
            val status = StatusState(type)
            status.stacks = boundedInt(input.readInt(), 0, 1000, "status stacks")
            status.remaining = input.readFloat().coerceIn(-10f, 600f)
            status.tickTimer = input.readFloat().coerceIn(-10f, 60f)
            status.sourcePower = readBigDecimal(input).coerceAtLeast(Num.ZERO)
            statuses.add(status)
        }
        return statuses
    }

    private fun writeVec(output: DataOutputStream, vector: Vec2) {
        output.writeFloat(vector.x.coerceIn(-10_000f, 10_000f))
        output.writeFloat(vector.y.coerceIn(-10_000f, 10_000f))
    }

    private fun readVec(input: DataInputStream, limit: Float): Vec2 = Vec2(input.readFloat().coerceIn(-limit, limit).finite(), input.readFloat().coerceIn(-limit, limit).finite())

    private fun writeStringSet(output: DataOutputStream, values: Set<String>) {
        output.writeInt(values.size)
        values.forEach { writeString(output, it) }
    }

    private fun readStringSet(input: DataInputStream, maximum: Int): MutableSet<String> {
        val count = boundedCount(input.readInt(), maximum, "string set")
        val values = linkedSetOf<String>()
        repeat(count) { values.add(readString(input, 512)) }
        return values
    }

    private fun writeString(output: DataOutputStream, value: String) {
        require(value.length <= 16_000) { "string is too long" }
        output.writeUTF(value)
    }

    private fun readString(input: DataInputStream, maximum: Int): String {
        val value = input.readUTF()
        if (value.length > maximum) throw SaveException("string exceeds expected limit")
        return value
    }

    private fun writeBigDecimal(output: DataOutputStream, value: BigDecimal) {
        val safe = Num.sanitize(value)
        val text = safe.toString()
        if (text.length > 4096) throw SaveException("number exceeds save limit")
        output.writeUTF(text)
    }

    private fun readBigDecimal(input: DataInputStream): BigDecimal {
        val text = readString(input, 4096)
        return try {
            Num.sanitize(BigDecimal(text))
        } catch (error: Exception) {
            throw SaveException("invalid number in save", error)
        }
    }

    private fun boundedCount(value: Int, maximum: Int, label: String): Int {
        if (value < 0 || value > maximum) throw SaveException("invalid $label count")
        return value
    }

    private fun boundedInt(value: Int, minimum: Int, maximum: Int, label: String): Int {
        if (value < minimum || value > maximum) throw SaveException("invalid $label")
        return value
    }

    private inline fun <reified T : Enum<T>> enumValue(name: String): T = enumValues<T>().firstOrNull { it.name == name } ?: throw SaveException("unknown enum value $name")

    private fun Float.finite(): Float = if (isFinite()) this else 0f
}
