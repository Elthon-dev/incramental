package com.elthon.infinite.core

import java.math.BigDecimal
import java.util.EnumMap

object StatsEngine {
    fun newRun(profile: MetaProfile, seed: Long, now: Long): RunState {
        val run = RunState(
            runId = "run-$now-${seed.toULong().toString(16)}",
            seed = seed,
            rng = Rng(seed)
        )
        run.rerolls = 1 + profile.rank(MetaUpgrade.OMEN)
        val stats = calculate(run, profile)
        run.maxHpSnapshot = stats[StatId.MAX_HP]
        run.player.hp = stats[StatId.MAX_HP]
        run.player.shield = Num.multiply(Num.decimal(profile.rank(MetaUpgrade.AEGIS)), Num.decimal(3))
        run.phase = CombatPhase.IDLE
        return run
    }

    fun calculate(run: RunState, profile: MetaProfile): PlayerStats {
        val values = EnumMap<StatId, BigDecimal>(StatId::class.java)
        StatId.entries.forEach { values[it] = it.defaultValue }
        values[StatId.MAX_HP] = Num.add(values.getValue(StatId.MAX_HP), Num.multiply(Num.decimal(profile.rank(MetaUpgrade.VITALITY)), Num.decimal(8)))
        values[StatId.ATTACK] = Num.multiply(values.getValue(StatId.ATTACK), Num.add(Num.ONE, Num.multiply(Num.decimal(profile.rank(MetaUpgrade.EDGE)), Num.decimal("0.03"))))
        values[StatId.ATTACK_SPEED] = Num.multiply(values.getValue(StatId.ATTACK_SPEED), Num.add(Num.ONE, Num.multiply(Num.decimal(profile.rank(MetaUpgrade.HASTE)), Num.decimal("0.04"))))
        values[StatId.DEFENSE] = Num.add(values.getValue(StatId.DEFENSE), Num.decimal(profile.rank(MetaUpgrade.AEGIS)))
        values[StatId.CRIT_CHANCE] = Num.add(values.getValue(StatId.CRIT_CHANCE), Num.multiply(Num.decimal(profile.rank(MetaUpgrade.PRECISION)), Num.decimal("0.015")))
        values[StatId.REROLLS] = Num.add(Num.ONE, Num.decimal(profile.rank(MetaUpgrade.OMEN)))
        values[StatId.CARD_CHOICES] = Num.add(Num.decimal(3), if (profile.rank(MetaUpgrade.INSIGHT) > 0) Num.ONE else Num.ZERO)
        run.cards.forEach { card -> applyModifiers(values, card.modifiers) }
        applySynergies(values, run)
        applyFractal(values, run)
        values[StatId.CARD_CHOICES] = Num.add(values.getValue(StatId.CARD_CHOICES), Num.decimal(run.cardChoiceBonus))
        StatId.entries.forEach { stat -> values[stat] = stat.sanitize(values.getValue(stat)) }
        return PlayerStats(values)
    }

    fun effective(run: RunState, profile: MetaProfile): PlayerStats {
        val values = EnumMap<StatId, BigDecimal>(calculate(run, profile).values)
        val ratio = playerHealthRatio(run, values.getValue(StatId.MAX_HP))
        run.cards.forEach { card ->
            card.modifiers.filter { it.condition == EffectCondition.BELOW_40 || it.condition == EffectCondition.BELOW_25 }.forEach { modifier ->
                val active = when (modifier.condition) {
                    EffectCondition.BELOW_40 -> ratio < Num.decimal("0.4")
                    EffectCondition.BELOW_25 -> ratio < Num.decimal("0.25")
                    else -> false
                }
                if (active) applyModifier(values, modifier)
            }
        }
        StatId.entries.forEach { stat -> values[stat] = stat.sanitize(values.getValue(stat)) }
        return PlayerStats(values)
    }

    fun playerHealthRatio(run: RunState, maxHp: BigDecimal): BigDecimal {
        if (maxHp.compareTo(Num.ZERO) <= 0) return Num.ZERO
        return Num.clamp(Num.divide(run.player.hp, maxHp), Num.ZERO, Num.ONE)
    }

    private fun applyModifiers(values: MutableMap<StatId, BigDecimal>, modifiers: List<StatModifier>) {
        modifiers.filter { it.condition == EffectCondition.ALWAYS }.forEach { applyModifier(values, it) }
    }

    private fun applyModifier(values: MutableMap<StatId, BigDecimal>, modifier: StatModifier) {
        val current = values.getValue(modifier.stat)
        val next = when (modifier.operation) {
            ModifierOperation.ADD -> Num.add(current, modifier.amount)
            ModifierOperation.MULTIPLY -> Num.multiply(current, modifier.amount)
            ModifierOperation.MAX -> Num.max(current, modifier.amount)
        }
        values[modifier.stat] = modifier.stat.sanitize(next)
    }

    private fun applyFractal(values: MutableMap<StatId, BigDecimal>, run: RunState) {
        val cards = run.cards.count { it.family == CardFamily.FRACTAL }
        if (cards == 0) return
        val growth = values.getValue(StatId.FRACTAL_GROWTH)
        val strength = Num.power(
            Num.add(Num.ONE, Num.multiply(Num.multiply(growth, Num.decimal(run.stats.cardsCollected)), Num.decimal("0.012"))),
            Num.decimal(cards)
        )
        values[StatId.ATTACK] = Num.multiply(values.getValue(StatId.ATTACK), strength)
        values[StatId.ATTACK_SPEED] = Num.multiply(values.getValue(StatId.ATTACK_SPEED), Num.power(strength, Num.decimal("0.45")))
    }

    private fun applySynergies(values: MutableMap<StatId, BigDecimal>, run: RunState) {
        if (run.synergies.contains("ember_cascade")) {
            values[StatId.BURN_POWER] = Num.add(values.getValue(StatId.BURN_POWER), Num.decimal("0.65"))
            values[StatId.BURN_CHANCE] = Num.min(Num.ONE, Num.add(values.getValue(StatId.BURN_CHANCE), Num.decimal("0.2")))
        }
        if (run.synergies.contains("cryoshock")) values[StatId.CRIT_DAMAGE] = Num.add(values.getValue(StatId.CRIT_DAMAGE), Num.decimal("0.12"))
        if (run.synergies.contains("storm_web")) {
            values[StatId.CHAIN_CHANCE] = Num.min(Num.ONE, Num.add(values.getValue(StatId.CHAIN_CHANCE), Num.decimal("0.4")))
            values[StatId.CHAIN_TARGETS] = Num.max(values.getValue(StatId.CHAIN_TARGETS), Num.decimal(2))
        }
        if (run.synergies.contains("venom_engine")) values[StatId.POISON_POWER] = Num.add(values.getValue(StatId.POISON_POWER), Num.decimal("0.7"))
        if (run.synergies.contains("prismatic_lattice")) {
            values[StatId.PROJECTILES] = Num.add(values.getValue(StatId.PROJECTILES), Num.ONE)
            values[StatId.PIERCE] = Num.add(values.getValue(StatId.PIERCE), Num.ONE)
        }
        if (run.synergies.contains("nova_crown")) values[StatId.NOVA_POWER] = Num.add(values.getValue(StatId.NOVA_POWER), Num.decimal("0.5"))
        if (run.synergies.contains("fortress_battery")) {
            values[StatId.DEFENSE] = Num.add(values.getValue(StatId.DEFENSE), Num.decimal(3))
            values[StatId.SHIELD_ON_KILL] = Num.add(values.getValue(StatId.SHIELD_ON_KILL), Num.decimal(2))
        }
        if (run.synergies.contains("deep_foresight")) values[StatId.CARD_CHOICES] = Num.add(values.getValue(StatId.CARD_CHOICES), Num.ONE)
        if (run.synergies.contains("fractal_recursion")) values[StatId.FRACTAL_GROWTH] = Num.add(values.getValue(StatId.FRACTAL_GROWTH), Num.decimal("0.025"))
        if (run.synergies.contains("blood_echo")) values[StatId.LIFESTEAL] = Num.min(Num.ONE, Num.add(values.getValue(StatId.LIFESTEAL), Num.decimal("0.04")))
        if (run.synergies.contains("execution_protocol")) {
            values[StatId.EXECUTE_CHANCE] = Num.min(Num.ONE, Num.add(values.getValue(StatId.EXECUTE_CHANCE), Num.decimal("0.28")))
            values[StatId.EXECUTE_THRESHOLD] = Num.max(values.getValue(StatId.EXECUTE_THRESHOLD), Num.decimal("0.3"))
        }
        if (run.synergies.contains("last_light")) {
            values[StatId.LOW_HP_DAMAGE] = Num.add(values.getValue(StatId.LOW_HP_DAMAGE), Num.decimal("0.45"))
            values[StatId.LOW_HP_DEFENSE] = Num.add(values.getValue(StatId.LOW_HP_DEFENSE), Num.decimal("0.35"))
        }
    }
}

object CardGenerator {
    fun generateOffers(run: RunState, profile: MetaProfile, forceMinimum: Rarity? = null): List<CardDefinition> {
        val stats = StatsEngine.calculate(run, profile)
        val count = Num.toInt(stats[StatId.CARD_CHOICES], 3, 5)
        val offers = mutableListOf<CardDefinition>()
        val usedFamilies = mutableSetOf<CardFamily>()
        repeat(count) { index ->
            val minimum = when {
                forceMinimum != null && index == 0 -> forceMinimum
                run.stage % 10L == 0L && index == 0 -> Rarity.EPIC
                run.rarePity >= pityLimit(profile) - 1 && index == 0 -> Rarity.RARE
                else -> null
            }
            var rarity = chooseRarity(run.stage, minimum, run.rng)
            var family = chooseFamily(run, profile, usedFamilies)
            var attempts = 0
            while (attempts < 8 && offers.any { it.family == family && it.rarity == rarity }) {
                rarity = chooseRarity(run.stage, minimum, run.rng)
                family = chooseFamily(run, profile, usedFamilies)
                attempts++
            }
            val card = build(run, family, rarity)
            offers.add(card)
            usedFamilies.add(family)
        }
        return offers
    }

    fun build(run: RunState, family: CardFamily, rarity: Rarity): CardDefinition {
        val rank = rarity.rank
        val strength = decimalPercent(Num.decimal(0.1 + rank * 0.055))
        val modifiers = mutableListOf<StatModifier>()
        val rules = mutableListOf<CardEffect>()
        val tags = linkedSetOf(family.name.lowercase(), family.displayName.lowercase())
        when (family) {
            CardFamily.VOLT -> {
                modifiers += StatModifier(StatId.ATTACK, ModifierOperation.MULTIPLY, Num.add(Num.ONE, strength))
                if (rank >= 1) modifiers += StatModifier(StatId.ATTACK_SPEED, ModifierOperation.ADD, Num.multiply(strength, Num.decimal("0.35")))
                if (rank >= 3) rules += effect(EffectKind.DAMAGE_NEXT, Trigger.CRIT, strength, text = "Critical hits deal ${percentText(strength)} bonus damage.")
            }
            CardFamily.FORTRESS -> {
                val health = Num.decimal(10 + rank * 6)
                modifiers += StatModifier(StatId.MAX_HP, ModifierOperation.ADD, health)
                if (rank >= 1) modifiers += StatModifier(StatId.DEFENSE, ModifierOperation.ADD, Num.decimal(1 + rank / 2))
                if (rank >= 2) rules += effect(EffectKind.SHIELD, Trigger.PICK, Num.decimal(4 + rank * 3), text = "Gain ${Num.format(Num.decimal(4 + rank * 3))} shield now.")
            }
            CardFamily.OVERCLOCK -> {
                modifiers += StatModifier(StatId.ATTACK_SPEED, ModifierOperation.ADD, Num.multiply(strength, Num.decimal("0.75")))
                if (rank >= 2) modifiers += StatModifier(StatId.ATTACK, ModifierOperation.MULTIPLY, Num.add(Num.ONE, Num.multiply(strength, Num.decimal("0.45"))))
                if (rank >= 3) rules += effect(EffectKind.DAMAGE_NEXT, Trigger.FIFTH_ATTACK, Num.multiply(strength, Num.decimal("1.2")), text = "Every fifth attack deals ${percentText(Num.multiply(strength, Num.decimal("1.2")))} bonus damage.")
            }
            CardFamily.REAPER -> {
                modifiers += StatModifier(StatId.CRIT_CHANCE, ModifierOperation.ADD, Num.multiply(strength, Num.decimal("0.55")))
                if (rank >= 1) modifiers += StatModifier(StatId.CRIT_DAMAGE, ModifierOperation.MULTIPLY, Num.add(Num.ONE, Num.multiply(strength, Num.decimal("0.7"))))
                if (rank >= 4) rules += effect(EffectKind.DAMAGE_NEXT, Trigger.CRIT, strength, text = "Critical hits gain ${percentText(strength)} additional damage.")
            }
            CardFamily.BLOOD -> {
                modifiers += StatModifier(StatId.LIFESTEAL, ModifierOperation.ADD, Num.multiply(strength, Num.decimal("0.32")))
                if (rank >= 2) modifiers += StatModifier(StatId.REGEN, ModifierOperation.ADD, Num.multiply(strength, Num.decimal("0.35")))
                if (rank >= 3) rules += effect(EffectKind.HEAL, Trigger.CRIT, Num.decimal(1 + rank * 2), text = "Heal ${1 + rank * 2} HP on critical hits.")
            }
            CardFamily.INFERNO -> {
                modifiers += StatModifier(StatId.BURN_CHANCE, ModifierOperation.ADD, Num.min(Num.ONE, strength))
                if (rank >= 1) modifiers += StatModifier(StatId.BURN_POWER, ModifierOperation.ADD, Num.multiply(strength, Num.decimal("1.4")))
                if (rank >= 2) rules += effect(EffectKind.APPLY_STATUS, Trigger.CRIT, Num.decimal(1 + rank / 2), StatusType.BURN, text = "Critical hits apply ${1 + rank / 2} Burn.")
            }
            CardFamily.FROST -> {
                modifiers += StatModifier(StatId.FREEZE_CHANCE, ModifierOperation.ADD, Num.min(Num.ONE, strength))
                if (rank >= 1) modifiers += StatModifier(StatId.FREEZE_POWER, ModifierOperation.ADD, Num.multiply(strength, Num.decimal("0.8")))
                if (rank >= 2) rules += effect(EffectKind.APPLY_STATUS, Trigger.ATTACK, Num.decimal(1 + rank / 2), StatusType.FREEZE, text = "Attacks can Freeze for ${1 + rank / 2} stacks.")
            }
            CardFamily.STORM -> {
                modifiers += StatModifier(StatId.SHOCK_CHANCE, ModifierOperation.ADD, Num.min(Num.ONE, strength))
                if (rank >= 1) modifiers += StatModifier(StatId.SHOCK_POWER, ModifierOperation.ADD, Num.multiply(strength, Num.decimal("1.5")))
                if (rank >= 2) rules += effect(EffectKind.CHAIN, Trigger.ATTACK, Num.decimal(1 + rank / 2), text = "Shock can chain to ${1 + rank / 2} nearby enemies.")
            }
            CardFamily.VENOM -> {
                modifiers += StatModifier(StatId.POISON_CHANCE, ModifierOperation.ADD, Num.min(Num.ONE, strength))
                if (rank >= 1) modifiers += StatModifier(StatId.POISON_POWER, ModifierOperation.ADD, Num.multiply(strength, Num.decimal("1.6")))
                if (rank >= 3) rules += effect(EffectKind.APPLY_STATUS, Trigger.KILL, Num.decimal(1 + rank / 2), StatusType.POISON, text = "Poisoned deaths spread ${1 + rank / 2} Poison nearby.")
            }
            CardFamily.PRISM -> {
                if (rank == 0) modifiers += StatModifier(StatId.PROJECTILES, ModifierOperation.ADD, Num.ONE)
                else modifiers += StatModifier(StatId.PROJECTILES, ModifierOperation.ADD, Num.decimal(1 + rank / 3))
                if (rank >= 1) modifiers += StatModifier(StatId.PIERCE, ModifierOperation.ADD, Num.decimal(1 + rank / 2))
                if (rank >= 2) modifiers += StatModifier(StatId.AREA, ModifierOperation.ADD, Num.decimal("0.12"))
                if (rank >= 4) rules += effect(EffectKind.EXTRA_PROJECTILE, Trigger.ATTACK, Num.decimal(1 + rank / 2), text = "Attacks fire ${1 + rank / 2} extra projectiles.")
            }
            CardFamily.NOVA -> {
                modifiers += StatModifier(StatId.NOVA_POWER, ModifierOperation.MULTIPLY, Num.add(Num.ONE, strength))
                if (rank >= 1) modifiers += StatModifier(StatId.NOVA_RADIUS, ModifierOperation.ADD, Num.decimal(10 + rank * 4))
                if (rank >= 3) rules += effect(EffectKind.DAMAGE_NEXT, Trigger.ROUND_START, strength, text = "Nova damage grows ${percentText(strength)} each stage.")
            }
            CardFamily.IRON_WILL -> {
                modifiers += StatModifier(StatId.DEFENSE, ModifierOperation.ADD, Num.multiply(strength, Num.decimal(2)))
                modifiers += StatModifier(StatId.LOW_HP_DEFENSE, ModifierOperation.ADD, Num.multiply(strength, Num.decimal("1.5")), EffectCondition.BELOW_40)
                if (rank >= 3) rules += effect(EffectKind.SHIELD, Trigger.LOW_HP, Num.decimal(3 + rank * 2), text = "Below 40% HP, gain defense each heartbeat.")
            }
            CardFamily.LAST_STAND -> {
                modifiers += StatModifier(StatId.LOW_HP_DAMAGE, ModifierOperation.ADD, Num.multiply(strength, Num.decimal("1.8")), EffectCondition.BELOW_25)
                if (rank >= 1) modifiers += StatModifier(StatId.ATTACK, ModifierOperation.MULTIPLY, Num.add(Num.ONE, Num.multiply(strength, Num.decimal("0.35"))))
                if (rank >= 4) rules += effect(EffectKind.DAMAGE_NEXT, Trigger.CRIT, strength, text = "Critical damage grows below 25% HP.")
            }
            CardFamily.FRACTAL -> {
                modifiers += StatModifier(StatId.FRACTAL_GROWTH, ModifierOperation.ADD, Num.decimal("0.012").multiply(Num.decimal(1 + rank)))
                modifiers += StatModifier(StatId.ATTACK, ModifierOperation.MULTIPLY, Num.add(Num.ONE, Num.multiply(strength, Num.decimal("0.4"))))
                rules += effect(EffectKind.FRACTAL, Trigger.PICK, Num.multiply(strength, Num.decimal("0.4")), text = "Fractal growth strengthens with every card collected.")
            }
            CardFamily.FORESIGHT -> {
                if (rank == 0) modifiers += StatModifier(StatId.REROLLS, ModifierOperation.ADD, Num.ONE)
                if (rank >= 1) modifiers += StatModifier(StatId.CARD_CHOICES, ModifierOperation.ADD, Num.decimal(1 + rank / 3))
                if (rank >= 3) rules += effect(EffectKind.CARD_CHOICE, Trigger.KILL, Num.decimal(1 + rank / 3), text = "Elite kills grant an extra card choice charge.")
            }
            CardFamily.CARDSMITH -> {
                modifiers += StatModifier(StatId.REROLLS, ModifierOperation.ADD, Num.decimal(1 + rank / 2))
                if (rank >= 1) modifiers += StatModifier(StatId.HEAL_ON_CARD, ModifierOperation.ADD, Num.decimal(1 + rank * 2))
                if (rank >= 2) rules += effect(EffectKind.HEAL, Trigger.PICK, Num.decimal(2 + rank * 2), text = "Heal ${2 + rank * 2} HP whenever you pick a card.")
            }
            CardFamily.ECHO -> {
                modifiers += StatModifier(StatId.HEAL_ON_CARD, ModifierOperation.ADD, Num.decimal(1 + rank * 2))
                if (rank >= 1) modifiers += StatModifier(StatId.AETHER_FIND, ModifierOperation.ADD, Num.decimal(rank * 0.5))
                if (rank >= 3) rules += effect(EffectKind.HEAL, Trigger.PICK, Num.decimal(2 + rank * 3), text = "Each card echoes ${2 + rank * 3} HP back to you.")
            }
            CardFamily.GRAVITY -> {
                modifiers += StatModifier(StatId.MOVE_SPEED, ModifierOperation.ADD, Num.decimal(5 + rank * 3))
                if (rank >= 1) modifiers += StatModifier(StatId.DASH_POWER, ModifierOperation.MULTIPLY, Num.add(Num.ONE, strength))
                if (rank >= 3) rules += effect(EffectKind.SHIELD, Trigger.PICK, Num.decimal(rank * 2), text = "Phase shifts grant ${rank * 2} shield.")
            }
            CardFamily.ANOMALY -> {
                modifiers += StatModifier(StatId.ANOMALY_POWER, ModifierOperation.ADD, Num.decimal(0.2 + rank * 0.12))
                modifiers += StatModifier(StatId.CRIT_CHANCE, ModifierOperation.ADD, strength)
                rules += effect(EffectKind.ANOMALY, Trigger.ATTACK, Num.decimal("0.35").add(Num.multiply(strength, Num.decimal("0.4"))), text = "Every seventh attack repeats at high force.")
            }
        }
        val prefix = GameContent.titlePrefixes[run.rng.nextInt(GameContent.titlePrefixes.size)]
        val suffix = GameContent.titleSuffixes[run.rng.nextInt(GameContent.titleSuffixes.size)]
        val title = "$prefix $suffix"
        if (rarity.rank >= 4) tags.add("finisher")
        if (rarity == Rarity.ANOMALOUS) tags.add("anomalous")
        return CardDefinition(
            id = "${family.name.lowercase()}-${rarity.name.lowercase()}-${stageToken(run.stage)}-${run.rng.nextLong().toULong().toString(16)}",
            title = title,
            family = family,
            rarity = rarity,
            modifiers = modifiers,
            rules = rules,
            tags = tags,
            seed = run.rng.nextLong()
        )
    }

    private fun effect(
        kind: EffectKind,
        trigger: Trigger,
        amount: BigDecimal,
        status: StatusType? = null,
        text: String
    ): CardEffect = CardEffect(kind = kind, trigger = trigger, amount = amount, status = status, text = text)

    private fun pityLimit(profile: MetaProfile): Int = if (profile.rank(MetaUpgrade.LUCK) > 0) 12 else 16

    private fun decimalPercent(fraction: BigDecimal): BigDecimal = Num.multiply(fraction, Num.HUNDRED)

    private fun percentText(value: BigDecimal): String =
        Num.multiply(Num.add(Num.ONE, Num.divide(value, Num.HUNDRED)), Num.decimal("100"))
            .setScale(0, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString() + "%"

    private fun stageToken(stage: Long): String = java.lang.Long.toUnsignedString(stage, 36)

    private fun chooseFamily(run: RunState, profile: MetaProfile, excluded: Set<CardFamily>): CardFamily {
        val available = CardFamily.entries.filter { it.unlockStage <= profile.bestStage.coerceAtLeast(run.stage) && it !in excluded }
            .ifEmpty { CardFamily.entries.filter { it.unlockStage <= profile.bestStage.coerceAtLeast(1L) } }
        var total = available.sumOf { it.weight }
        var roll = run.rng.nextInt(total)
        available.forEach { family ->
            if (roll < family.weight) return family
            roll -= family.weight
        }
        return available.last()
    }

    private fun chooseRarity(stage: Long, minimum: Rarity?, rng: Rng): Rarity {
        val weights = when {
            stage <= 2L -> intArrayOf(64, 28, 8, 0, 0, 0, 0)
            stage <= 4L -> intArrayOf(45, 39, 14, 2, 0, 0, 0)
            stage <= 9L -> intArrayOf(32, 40, 22, 5, 1, 0, 0)
            else -> intArrayOf(24, 39, 26, 8, 2, 1, 0)
        }
        if (minimum != null) {
            for (index in weights.indices.reversed()) weights[index] = 0
            for (index in minimum.rank..weights.lastIndex) weights[index] = if (index == minimum.rank) 70 else 5
        }
        var roll = rng.nextInt(weights.sum())
        for (index in weights.indices) {
            if (roll < weights[index]) return Rarity.entries[index]
            roll -= weights[index]
        }
        return Rarity.COMMON
    }
}

object SynergyEngine {
    val definitions = linkedMapOf(
        "ember_cascade" to Pair("Ember Cascade", "Critical hits spread Burn"),
        "cryoshock" to Pair("Cryoshock", "Chilled enemies take amplified critical damage"),
        "storm_web" to Pair("Storm Web", "Shock attacks chain through enemies"),
        "venom_engine" to Pair("Venom Engine", "Poison becomes devastating attrition"),
        "prismatic_lattice" to Pair("Prismatic Lattice", "Attacks gain projectiles and pierce"),
        "nova_crown" to Pair("Nova Crown", "Nova power and radius grow"),
        "fortress_battery" to Pair("Fortress Battery", "Kills build shield and defense"),
        "deep_foresight" to Pair("Deep Foresight", "Card choices expand"),
        "fractal_recursion" to Pair("Fractal Recursion", "Fractal growth accelerates"),
        "blood_echo" to Pair("Blood Echo", "Lifesteal feeds on repeated systems"),
        "execution_protocol" to Pair("Execution Protocol", "Weakened targets risk execution"),
        "last_light" to Pair("Last Light", "Low HP transforms defense into damage")
    )

    fun recalculate(run: RunState): List<Pair<String, String>> {
        val familyCards = run.cards.groupBy { it.family }
        val active = linkedSetOf<String>()
        if (familyCards.getOrDefault(CardFamily.INFERNO, emptyList()).map { it.id }.distinct().size >= 2) active += "ember_cascade"
        if (familyCards.getOrDefault(CardFamily.FROST, emptyList()).map { it.id }.distinct().size >= 2) active += "cryoshock"
        if (familyCards.getOrDefault(CardFamily.STORM, emptyList()).map { it.id }.distinct().size >= 2) active += "storm_web"
        if (familyCards.getOrDefault(CardFamily.VENOM, emptyList()).map { it.id }.distinct().size >= 2) active += "venom_engine"
        if (familyCards.getOrDefault(CardFamily.PRISM, emptyList()).map { it.id }.distinct().size >= 2) active += "prismatic_lattice"
        if (familyCards.getOrDefault(CardFamily.NOVA, emptyList()).map { it.id }.distinct().size >= 2) active += "nova_crown"
        if (familyCards.getOrDefault(CardFamily.FORTRESS, emptyList()).map { it.id }.distinct().size >= 2) active += "fortress_battery"
        if (familyCards.getOrDefault(CardFamily.FORESIGHT, emptyList()).map { it.id }.distinct().size >= 2) active += "deep_foresight"
        if (familyCards.getOrDefault(CardFamily.FRACTAL, emptyList()).map { it.id }.distinct().size >= 2) active += "fractal_recursion"
        if (familyCards.getOrDefault(CardFamily.BLOOD, emptyList()).map { it.id }.distinct().size >= 2 && familyCards.getOrDefault(CardFamily.ECHO, emptyList()).isNotEmpty()) active += "blood_echo"
        if (run.cards.count { it.rarity.rank >= Rarity.RARE.rank } >= 3) active += "execution_protocol"
        if (familyCards.getOrDefault(CardFamily.IRON_WILL, emptyList()).isNotEmpty() && familyCards.getOrDefault(CardFamily.LAST_STAND, emptyList()).isNotEmpty()) active += "last_light"
        val newlyActive = active.filterNot { run.synergies.contains(it) }
        run.pendingSynergies.addAll(newlyActive)
        run.synergies.addAll(newlyActive)
        return newlyActive.map { it to (definitions[it]?.first ?: it) }
    }

    fun progress(run: RunState, id: String): String {
        val counts = run.cards.groupBy { it.family }.mapValues { (_, cards) -> cards.map { card -> card.id }.distinct().size }
        return when (id) {
            "blood_echo" -> "${counts[CardFamily.BLOOD] ?: 0}/2 Blood + ${if ((counts[CardFamily.ECHO] ?: 0) > 0) "1" else "0"}/1 Echo"
            "execution_protocol" -> "${run.cards.count { it.rarity.rank >= Rarity.RARE.rank }}/3 Rare+"
            "last_light" -> "${if ((counts[CardFamily.IRON_WILL] ?: 0) > 0) "1" else "0"}/1 Iron Will + ${if ((counts[CardFamily.LAST_STAND] ?: 0) > 0) "1" else "0"}/1 Last Stand"
            else -> "${counts[familyForSynergy(id)] ?: 0}/2"
        }
    }

    private fun familyForSynergy(id: String): CardFamily = when (id) {
        "ember_cascade" -> CardFamily.INFERNO
        "cryoshock" -> CardFamily.FROST
        "storm_web" -> CardFamily.STORM
        "venom_engine" -> CardFamily.VENOM
        "prismatic_lattice" -> CardFamily.PRISM
        "nova_crown" -> CardFamily.NOVA
        "fortress_battery" -> CardFamily.FORTRESS
        "deep_foresight" -> CardFamily.FORESIGHT
        "fractal_recursion" -> CardFamily.FRACTAL
        else -> CardFamily.FORTRESS
    }
}

object CardEngine {
    fun select(run: RunState, profile: MetaProfile, cardId: String): CardDefinition? {
        if (run.phase != CombatPhase.CARD_PICKER || run.cardChoiceLocked) return null
        val selected = run.cardOffers.firstOrNull { it.id == cardId } ?: return null
        run.cardChoiceLocked = true
        val oldMaxHp = StatsEngine.calculate(run, profile)[StatId.MAX_HP]
        run.cards.add(selected)
        run.stats.cardsCollected++
        profile.discoveredCards.add(selected.id)
        profile.discoveredFamilies.add(selected.family)
        profile.totalCards++
        val newMaxHp = StatsEngine.calculate(run, profile)[StatId.MAX_HP]
        run.maxHpSnapshot = newMaxHp
        val gained = Num.subtract(newMaxHp, oldMaxHp)
        if (gained.compareTo(Num.ZERO) > 0) run.player.hp = Num.min(newMaxHp, Num.add(run.player.hp, gained))
        val cardHeal = Num.add(StatsEngine.calculate(run, profile)[StatId.HEAL_ON_CARD], ruleAmount(run, Trigger.PICK, EffectKind.HEAL))
        run.player.hp = Num.min(newMaxHp, Num.add(run.player.hp, cardHeal))
        val shield = ruleAmount(run, Trigger.PICK, EffectKind.SHIELD)
        run.player.shield = Num.add(run.player.shield, shield)
        run.cardOffers.clear()
        run.rarePity = if (selected.rarity.rank >= Rarity.RARE.rank) 0 else run.rarePity + 1
        SynergyEngine.recalculate(run)
        run.phase = CombatPhase.IDLE
        run.cardChoiceLocked = false
        run.revision++
        return selected
    }

    fun reroll(run: RunState, profile: MetaProfile): Boolean {
        if (run.phase != CombatPhase.CARD_PICKER || run.rerolls <= 0 || run.cardChoiceLocked) return false
        run.rerolls--
        run.cardOffers = CardGenerator.generateOffers(run, profile).toMutableList()
        run.revision++
        return true
    }

    fun awardCard(run: RunState, profile: MetaProfile, minimum: Rarity): CardDefinition {
        val card = CardGenerator.build(run, CardFamily.entries[run.rng.nextInt(CardFamily.entries.size)], minimum)
        val oldMaxHp = StatsEngine.calculate(run, profile)[StatId.MAX_HP]
        run.cards.add(card)
        profile.discoveredCards.add(card.id)
        profile.discoveredFamilies.add(card.family)
        profile.totalCards++
        run.stats.cardsCollected++
        val newMaxHp = StatsEngine.calculate(run, profile)[StatId.MAX_HP]
        run.maxHpSnapshot = newMaxHp
        val gained = Num.subtract(newMaxHp, oldMaxHp)
        if (gained.compareTo(Num.ZERO) > 0) run.player.hp = Num.min(newMaxHp, Num.add(run.player.hp, gained))
        SynergyEngine.recalculate(run)
        return card
    }

    fun awardFamily(run: RunState, profile: MetaProfile, family: CardFamily, minimum: Rarity): CardDefinition {
        val card = CardGenerator.build(run, family, minimum)
        val oldMaxHp = StatsEngine.calculate(run, profile)[StatId.MAX_HP]
        run.cards.add(card)
        profile.discoveredCards.add(card.id)
        profile.discoveredFamilies.add(card.family)
        profile.totalCards++
        run.stats.cardsCollected++
        val newMaxHp = StatsEngine.calculate(run, profile)[StatId.MAX_HP]
        run.maxHpSnapshot = newMaxHp
        val gained = Num.subtract(newMaxHp, oldMaxHp)
        if (gained.compareTo(Num.ZERO) > 0) run.player.hp = Num.min(newMaxHp, Num.add(run.player.hp, gained))
        SynergyEngine.recalculate(run)
        return card
    }

    private fun ruleAmount(run: RunState, trigger: Trigger, kind: EffectKind): BigDecimal = run.cards
        .flatMap { it.rules }
        .filter { it.trigger == trigger && it.kind == kind }
        .fold(Num.ZERO) { total, effect -> Num.add(total, effect.amount) }

}
