package com.elthon.infinite.ui

import com.elthon.infinite.core.CardEngine
import com.elthon.infinite.core.CombatEngine
import com.elthon.infinite.core.CombatInput
import com.elthon.infinite.core.CombatPhase
import com.elthon.infinite.core.GameEvent
import com.elthon.infinite.core.GameEventType
import com.elthon.infinite.core.GameSave
import com.elthon.infinite.core.MetaProfile
import com.elthon.infinite.core.MetaUpgrade
import com.elthon.infinite.core.OfflineProgress
import com.elthon.infinite.core.OfflineReport
import com.elthon.infinite.core.ProgressionEngine
import com.elthon.infinite.core.RunState
import com.elthon.infinite.core.RunSummary
import com.elthon.infinite.platform.AudioEngine
import com.elthon.infinite.platform.SaveStore
import com.elthon.infinite.platform.Sfx
import java.math.BigDecimal
import kotlin.math.max
import kotlin.math.min

enum class Screen { HOME, META, ARCHIVE, SETTINGS, RUN, PAUSE, CARDS, EVENT, GAMEOVER, OFFLINE }

object Ids {
    const val NEW_RUN = "home.newRun"
    const val CONTINUE_RUN = "home.continue"
    const val HOME_META = "home.meta"
    const val HOME_ARCHIVE = "home.archive"
    const val HOME_SETTINGS = "home.settings"
    const val BACK = "nav.back"
    const val PAUSE = "run.pause"
    const val RESUME = "pause.resume"
    const val PAUSE_SAVE = "pause.save"
    const val PAUSE_QUIT = "pause.quit"
    const val REROLL = "cards.reroll"
    const val CARD_PREFIX = "cards.card."
    const val EVENT_PREFIX = "event.pick."
    const val GAMEOVER_RETRY = "over.retry"
    const val GAMEOVER_HOME = "over.home"
    const val OFFLINE_OK = "offline.ok"
    const val META_PREFIX = "meta.buy."
    const val TAB_ACHIEVEMENTS = "archive.tab0"
    const val TAB_CODEX = "archive.tab1"
    const val TOGGLE_PREFIX = "settings.toggle."
}

data class FloatText(
    val text: String,
    val x: Float,
    val y: Float,
    val color: Int,
    val born: Float,
    val size: Float,
    val rise: Float = 58f
)

class GameSession(private val store: SaveStore, private val audio: AudioEngine) {
    var save: GameSave = store.load() ?: GameSave(MetaProfile(), null)
        private set
    var screen: Screen = Screen.HOME
    var paused = false
    val input = TouchState()
    val floating = ArrayList<FloatText>()
    val regions = ArrayList<HitRegion>()
    var animTime = 0f
    var run: RunState? = save.run
    var lastSummary: RunSummary? = null
    var offlineReport: OfflineReport? = null
    var toast: String? = null
    var toastTimer = 0f
    var banner: String? = null
    var bannerTimer = 0f
    var shake = 0f
    var metaScroll = 0f
    var archiveTab = 0
    var codexScroll = 0f
    var achievementScroll = 0f
    var pulse = 0f
    private var eventCursor = 0
    private var autosaveTimer = 0f
    private var lastFrameSeconds = 0f
    private var accumulator = 0f

    val profile: MetaProfile get() = save.profile

    init {
        audio.soundEnabled = save.profile.settings.soundEnabled
        audio.musicEnabled = save.profile.settings.musicEnabled
        val now = System.currentTimeMillis()
        if (save.profile.lastSavedAt <= 0L) {
            save.profile.lastSavedAt = now
            persist()
        } else {
            val report = OfflineProgress.calculate(save.profile, now)
            if (report.energyAwarded.compareTo(BigDecimal.ZERO) > 0) offlineReport = report
        }
        if (run != null && run.phase == CombatPhase.CARD_PICKER) screen = Screen.CARDS
        if (run != null && run.phase == CombatPhase.EVENT) screen = Screen.EVENT
        if (run != null) {
            screen = when (run.phase) {
                CombatPhase.CARD_PICKER -> Screen.CARDS
                CombatPhase.EVENT -> Screen.EVENT
                CombatPhase.GAME_OVER -> Screen.GAMEOVER
                else -> Screen.RUN
            }
        }
        if (offlineReport != null) screen = Screen.OFFLINE
    }

    fun onAppResume() {
        if (save.profile.settings.musicEnabled) audio.startAmbient()
    }

    fun onAppPause() {
        audio.stopAmbient()
        persist(force = true)
    }

    fun tick(deltaSeconds: Float) {
        val dt = deltaSeconds.coerceIn(0f, 0.1f)
        lastFrameSeconds = dt
        animTime += dt
        pulse = (kotlin.math.sin(animTime * 2.4f) + 1f) * 0.5f
        toastTimer = max(0f, toastTimer - dt)
        bannerTimer = max(0f, bannerTimer - dt)
        if (bannerTimer <= 0f) banner = null
        shake = max(0f, shake - dt * 5.5f)
        for (index in floating.indices.reversed()) {
            floating[index] = floating[index].copy(age = floating[index].age + dt)
            if (floating[index].age > 1.25f) floating.removeAt(index)
        }
        input.drainReleases().forEach { handleTap(it) }
        val active = run
        if (screen == Screen.RUN && !paused && active != null) {
            accumulator += dt
            var guard = 0
            while (accumulator >= STEP && guard < 5) {
                accumulator -= STEP
                guard++
                val combatInput: CombatInput = input.consumeAbilityFlags()
                if (combatInput.novaPressed || combatInput.dashPressed) audio.play(Sfx.ABILITY, 0.7f)
                CombatEngine.step(active, save.profile, combatInput, STEP)
                syncAfterStep(active)
            }
            if (guard >= 5) accumulator = 0f
            pumpEvents()
        } else {
            input.consumeAbilityFlags()
            accumulator = 0f
        }
        autosaveTimer += dt
        if (autosaveTimer >= 4f) {
            autosaveTimer = 0f
            persist()
        }
    }

    private fun syncAfterStep(active: RunState) {
        when (active.phase) {
            CombatPhase.CARD_PICKER -> if (screen == Screen.RUN) screen = Screen.CARDS
            CombatPhase.EVENT -> if (screen == Screen.RUN) screen = Screen.EVENT
            CombatPhase.GAME_OVER -> if (screen == Screen.RUN) enterGameOver(active)
            else -> Unit
        }
    }

    private fun enterGameOver(active: RunState) {
        lastSummary = ProgressionEngine.finishRun(active, save.profile)
        screen = Screen.GAMEOVER
        audio.play(Sfx.DEFEAT, 0.9f)
        persist(force = true)
    }

    fun pumpEvents() {
        val active = run ?: return
        if (active.events.size < eventCursor) eventCursor = 0
        while (eventCursor < active.events.size) {
            val event = active.events[eventCursor]
            eventCursor++
            consume(event)
        }
    }

    private fun consume(event: GameEvent) {
        when (event.type) {
            GameEventType.DAMAGE -> {
                if (event.text.isNotEmpty() && save.profile.settings.showDamageNumbers) {
                    floating.add(FloatText(event.text, event.x, event.y, event.color, animTime, 26f))
                }
            }
            GameEventType.CRIT -> {
                floating.add(FloatText(event.text, event.x, event.y, event.color, animTime, 34f))
                audio.play(Sfx.CRIT, 0.6f)
            }
            GameEventType.KILL -> {
                floating.add(FloatText(event.text, event.x, event.y, event.color, animTime, 30f, 74f))
                shake = min(1f, shake + 0.35f)
                audio.play(Sfx.KILL, 0.5f)
            }
            GameEventType.PLAYER_HURT -> {
                if (event.text.isNotEmpty()) {
                    floating.add(FloatText(event.text, event.x, event.y, event.color, animTime, 30f))
                    shake = min(1f, shake + 0.7f)
                    audio.play(Sfx.HURT, 0.8f)
                } else {
                    shake = min(1f, shake + 0.25f)
                }
            }
            GameEventType.HEAL -> floating.add(FloatText(event.text, event.x, event.y, event.color, animTime, 26f))
            GameEventType.ABILITY -> {
                floating.add(FloatText(event.text, event.x, event.y, event.color, animTime, 26f, 40f))
            }
            GameEventType.STAGE_CLEAR -> {
                banner = event.text
                bannerTimer = 1.6f
                audio.play(Sfx.STAGE, 0.6f)
            }
            GameEventType.CARD_PICK -> {
                banner = event.text
                bannerTimer = 1.4f
            }
            GameEventType.SYNERGY, GameEventType.ACHIEVEMENT -> {
                toast = event.text
                toastTimer = 2.6f
                audio.play(Sfx.SYNERGY, 0.8f)
            }
            GameEventType.STATUS -> Unit
            GameEventType.EVENT -> {
                banner = event.text
                bannerTimer = 1.4f
            }
        }
    }

    fun handleTap(id: String) {
        if (save.profile.settings.soundEnabled) audio.play(Sfx.TAP, 0.4f)
        when {
            id == Ids.NEW_RUN -> startNewRun()
            id == Ids.CONTINUE_RUN -> resumeRun()
            id == Ids.HOME_META -> go(Screen.META)
            id == Ids.HOME_ARCHIVE -> go(Screen.ARCHIVE)
            id == Ids.HOME_SETTINGS -> go(Screen.SETTINGS)
            id == Ids.BACK -> goBack()
            id == Ids.PAUSE -> {
                paused = true
                go(Screen.PAUSE)
            }
            id == Ids.RESUME -> {
                paused = false
                go(Screen.RUN)
            }
            id == Ids.PAUSE_SAVE -> {
                persist(force = true)
                notify("Progress secured")
            }
            id == Ids.PAUSE_QUIT -> abandonRun()
            id == Ids.REROLL -> reroll()
            id == Ids.GAMEOVER_RETRY -> startNewRun()
            id == Ids.GAMEOVER_HOME -> goHome()
            id == Ids.OFFLINE_OK -> {
                val report = OfflineProgress.claim(save.profile, System.currentTimeMillis())
                offlineReport = null
                notify("Offline signal +${com.elthon.infinite.core.Num.format(report.energyAwarded)}")
                goHome()
                persist(force = true)
            }
            id == Ids.TAB_ACHIEVEMENTS -> {
                archiveTab = 0
                audio.play(Sfx.TAP, 0.4f)
            }
            id == Ids.TAB_CODEX -> {
                archiveTab = 1
                audio.play(Sfx.TAP, 0.4f)
            }
            id.startsWith(Ids.CARD_PREFIX) -> pickCard(id.removePrefix(Ids.CARD_PREFIX))
            id.startsWith(Ids.EVENT_PREFIX) -> chooseEvent(id.removePrefix(Ids.EVENT_PREFIX))
            id.startsWith(Ids.META_PREFIX) -> buyMeta(id.removePrefix(Ids.META_PREFIX))
            id.startsWith(Ids.TOGGLE_PREFIX) -> toggleSetting(id.removePrefix(Ids.TOGGLE_PREFIX))
        }
    }

    private fun go(target: Screen) {
        screen = target
        input.reset()
        if (target == Screen.RUN || target == Screen.HOME || target == Screen.META) audio.startAmbient()
    }

    fun goHome() {
        paused = false
        run = save.run
        go(Screen.HOME)
    }

    fun goBack() {
        when (screen) {
            Screen.RUN -> {
                paused = true
                go(Screen.PAUSE)
            }
            Screen.PAUSE -> {
                paused = false
                go(Screen.RUN)
            }
            Screen.CARDS, Screen.EVENT, Screen.GAMEOVER -> goHome()
            else -> goHome()
        }
    }

    fun onBackPressed(): Boolean {
        if (screen == Screen.RUN) {
            paused = true
            go(Screen.PAUSE)
            return true
        }
        if (screen == Screen.PAUSE) {
            paused = false
            go(Screen.RUN)
            return true
        }
        if (screen == Screen.HOME) return false
        goHome()
        return true
    }

    fun startNewRun() {
        val active = ProgressionEngine.startRun(save.profile, System.currentTimeMillis())
        attach(active)
        banner = "SIGNAL ACQUIRED"
        bannerTimer = 1.6f
        go(Screen.RUN)
        persist(force = true)
    }

    fun resumeRun() {
        val active = save.run
        if (active == null) {
            notify("No active transmission")
            return
        }
        attach(active)
        when (active.phase) {
            CombatPhase.CARD_PICKER -> go(Screen.CARDS)
            CombatPhase.EVENT -> go(Screen.EVENT)
            CombatPhase.GAME_OVER -> go(Screen.GAMEOVER)
            CombatPhase.FIGHTING -> go(Screen.RUN)
            CombatPhase.IDLE -> {
                CombatEngine.advanceAfterChoice(active, save.profile)
                go(Screen.RUN)
            }
        }
    }

    private fun attach(active: RunState) {
        run = active
        save = GameSave(save.profile, active)
        save.profile.lastSavedAt = System.currentTimeMillis()
        eventCursor = 0
        floating.clear()
        paused = false
        audio.startAmbient()
    }

    fun abandonRun() {
        val active = save.run ?: return goHome()
        lastSummary = ProgressionEngine.finishRun(active, save.profile)
        save = GameSave(save.profile, null)
        run = null
        goHome()
        persist(force = true)
    }

    fun pickCard(cardId: String) {
        val active = run ?: return
        CardEngine.select(active, save.profile, cardId) ?: return
        audio.play(Sfx.CARD, 0.7f)
        val unlocked = SynergyNotifier.take(active)
        if (unlocked.isNotEmpty()) {
            toast = "SYNERGY: ${unlocked.joinToString(", ")}"
            toastTimer = 2.8f
            audio.play(Sfx.SYNERGY, 0.9f)
        }
        ProgressionEngine.updateAchievements(save.profile, active)
        val stats = com.elthon.infinite.core.StatsEngine.calculate(active, save.profile)
        active.maxHpSnapshot = stats[com.elthon.infinite.core.StatId.MAX_HP]
        CombatEngine.advanceAfterChoice(active, save.profile)
        if (active.phase == CombatPhase.EVENT) {
            go(Screen.EVENT)
        } else {
            go(Screen.RUN)
        }
        persist(force = true)
    }

    fun reroll() {
        val active = run ?: return
        if (CardEngine.reroll(active, save.profile)) {
            audio.play(Sfx.CARD, 0.5f)
            persist()
        } else {
            notify("No rerolls remaining")
        }
    }

    fun chooseEvent(tag: String) {
        val active = run ?: return
        if (ProgressionEngine.resolveEvent(active, save.profile, tag)) {
            audio.play(Sfx.CARD, 0.7f)
            CombatEngine.advanceAfterChoice(active, save.profile)
            if (active.phase == CombatPhase.EVENT) go(Screen.EVENT) else go(Screen.RUN)
            persist(force = true)
        }
    }

    fun buyMeta(key: String) {
        val upgrade = MetaUpgrade.entries.firstOrNull { it.name == key } ?: return
        if (ProgressionEngine.purchaseMeta(save.profile, upgrade)) {
            audio.play(Sfx.SYNERGY, 0.7f)
            notify("${upgrade.displayName} upgraded to rank ${save.profile.rank(upgrade)}")
            persist(force = true)
        } else {
            notify("Not enough Aether or locked")
        }
    }

    fun toggleSetting(key: String) {
        val settings = save.profile.settings
        when (key) {
            "music" -> settings.musicEnabled = !settings.musicEnabled
            "sound" -> settings.soundEnabled = !settings.soundEnabled
            "haptics" -> settings.hapticsEnabled = !settings.hapticsEnabled
            "motion" -> settings.reducedMotion = !settings.reducedMotion
            "damage" -> settings.showDamageNumbers = !settings.showDamageNumbers
        }
        audio.soundEnabled = settings.soundEnabled
        audio.musicEnabled = settings.musicEnabled
        if (settings.musicEnabled) audio.startAmbient() else audio.stopAmbient()
        persist(force = true)
    }

    fun scrollBy(delta: Float) {
        when (screen) {
            Screen.META -> metaScroll = (metaScroll + delta).coerceIn(0f, 1_400f)
            Screen.ARCHIVE -> {
                if (archiveTab == 0) achievementScroll = (achievementScroll + delta).coerceIn(0f, 900f)
                else codexScroll = (codexScroll + delta).coerceIn(0f, 1_600f)
            }
            else -> Unit
        }
    }

    fun notify(message: String) {
        toast = message
        toastTimer = 2.2f
    }

    fun persist(force: Boolean = false) {
        save.profile.lastSavedAt = System.currentTimeMillis()
        store.saveAsync(save)
        if (force) autosaveTimer = 0f
    }

    fun flush(): Boolean = store.flushBlocking(save)

    fun release() {
        audio.stopAmbient()
    }

    val frameSeconds: Float get() = lastFrameSeconds

    companion object {
        const val STEP = 1f / 60f
    }
}

private object SynergyNotifier {
    fun take(run: RunState): List<String> {
        if (run.pendingSynergies.isEmpty()) return emptyList()
        val names = run.pendingSynergies.map { id ->
            com.elthon.infinite.core.SynergyEngine.definitions[id]?.first ?: id
        }
        run.pendingSynergies.clear()
        return names
    }
}
