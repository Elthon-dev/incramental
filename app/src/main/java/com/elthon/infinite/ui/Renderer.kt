package com.elthon.infinite.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.elthon.infinite.core.ChallengeModifier
import com.elthon.infinite.core.CombatPhase
import com.elthon.infinite.core.CombatRules
import com.elthon.infinite.core.EnemyShape
import com.elthon.infinite.core.EnemyState
import com.elthon.infinite.core.GameContent
import com.elthon.infinite.core.MetaUpgrade
import com.elthon.infinite.core.Num
import com.elthon.infinite.core.ProjectileState
import com.elthon.infinite.core.RunState
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class Renderer(private val session: GameSession) {
    private val rect = RectF()
    private val path = Path()
    private val bigPaint = Ui.text(58f, Ink.TEXT, Ui.display, Paint.Align.CENTER)
    private val sectionPaint = Ui.text(24f, Ink.TEXT_DIM, Ui.display)

    fun draw(canvas: Canvas) {
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        val scale = min(width / Ui.WIDTH, height / Ui.HEIGHT)
        val offsetX = (width - Ui.WIDTH * scale) / 2f
        val offsetY = (height - Ui.HEIGHT * scale) / 2f
        canvas.drawColor(Ink.VOID)
        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)
        session.regions.clear()
        background(canvas)
        when (session.screen) {
            Screen.HOME -> home(canvas)
            Screen.META -> meta(canvas)
            Screen.ARCHIVE -> archive(canvas)
            Screen.SETTINGS -> settings(canvas)
            Screen.RUN -> combat(canvas)
            Screen.PAUSE -> pause(canvas)
            Screen.CARDS -> cards(canvas)
            Screen.EVENT -> event(canvas)
            Screen.GAMEOVER -> gameOver(canvas)
            Screen.OFFLINE -> offline(canvas)
        }
        toast(canvas)
        canvas.restore()
    }

    private fun background(canvas: Canvas) {
        val paint = Ui.fill(Ink.VOID_SOFT)
        paint.shader = android.graphics.LinearGradient(
            0f, 0f, 0f, Ui.HEIGHT, Ink.PANEL, Ink.VOID, android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, Ui.WIDTH, Ui.HEIGHT, paint)
        paint.shader = null
        val glowPaint = Ui.fill(Ink.CYAN)
        glowPaint.shader = android.graphics.RadialGradient(
            Ui.WIDTH * 0.2f, Ui.HEIGHT * 0.12f, Ui.WIDTH * 0.7f,
            Ui.alpha(Ink.CYAN, 0.07f), Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, Ui.WIDTH, Ui.HEIGHT * 0.5f, glowPaint)
        glowPaint.shader = null
        val step = 64f
        var x = 0f
        while (x <= Ui.WIDTH) {
            canvas.drawLine(x, 0f, x, Ui.HEIGHT, Ui.stroke(Ui.alpha(Ink.LINE, 0.18f), 1f))
            x += step
        }
        var y = 0f
        while (y <= Ui.HEIGHT) {
            canvas.drawLine(0f, y, Ui.WIDTH, y, Ui.stroke(Ui.alpha(Ink.LINE, 0.18f), 1f))
            y += step
        }
    }

    private fun topBar(canvas: Canvas, title: String, showBack: Boolean) {
        canvas.drawRect(0f, 0f, Ui.WIDTH, 96f, Ui.fill(Ui.alpha(Ink.VOID, 0.85f)))
        canvas.drawLine(0f, 96f, Ui.WIDTH, 96f, Ui.stroke(Ui.alpha(Ink.LINE, 0.9f), 2f))
        if (showBack) {
            button(canvas, Ids.BACK, 30f, 24f, 56f, 48f, "<", Ink.PANEL_LIGHT)
            canvas.drawText(title, 104f, 58f, Ui.text(30f, Ink.TEXT, Ui.display))
        } else {
            canvas.drawText(title, Ui.MARGIN, 58f, Ui.text(30f, Ink.TEXT, Ui.display))
        }
    }

    private fun home(canvas: Canvas) {
        val profile = session.profile
        val cx = Ui.WIDTH / 2f
        Ui.glow(canvas, cx, 300f, 300f, Ink.CYAN)
        canvas.drawText("INFINITE", cx, 300f, Ui.text(96f, Ink.TEXT, Ui.display, Paint.Align.CENTER))
        canvas.drawText("ENDLESS SIGNAL", cx, 344f, Ui.text(24f, Ink.CYAN, Ui.display, Paint.Align.CENTER))
        Ui.glow(canvas, cx, 470f, 220f, Ink.MAGENTA)
        canvas.drawCircle(cx, 470f, 96f, Ui.stroke(Ui.alpha(Ink.CYAN, 0.55f), 3f))
        canvas.drawCircle(cx, 470f, 62f, Ui.stroke(Ui.alpha(Ink.MAGENTA, 0.45f), 2f))
        Ui.star(canvas, cx, 470f, 44f, 18f, 5, session.animTime * 0.4f, Ui.alpha(Ink.CYAN, 0.85f))

        val bestLabel = "BEST STAGE  ${Num.formatStage(profile.bestStage)}"
        val aetherLabel = "${Num.format(profile.aether)} AETHER"
        rect.set(Ui.MARGIN, 600f, Ui.WIDTH - Ui.MARGIN, 690f)
        Ui.panel(canvas, rect, 18f, Ui.alpha(Ink.PANEL, 0.9f))
        canvas.drawText(bestLabel, Ui.MARGIN + 22f, 634f, Ui.text(22f, Ink.TEXT_DIM, Ui.display))
        canvas.drawText(aetherLabel, Ui.MARGIN + 22f, 668f, Ui.text(26f, Ink.GOLD, Ui.numeric))

        val hasRun = session.run?.phase != null && session.run?.phase != CombatPhase.GAME_OVER
        if (hasRun) {
            val active = session.run!!
            button(canvas, Ids.CONTINUE_RUN, Ui.MARGIN, 720f, Ui.WIDTH - Ui.MARGIN, 830f, "RESUME STAGE ${Num.formatStage(active.stage)}", Ink.CYAN, Ink.VOID)
            button(canvas, Ids.NEW_RUN, Ui.MARGIN, 846f, Ui.WIDTH - Ui.MARGIN, 936f, "NEW TRANSMISSION", Ink.PANEL_LIGHT)
        } else {
            button(canvas, Ids.NEW_RUN, Ui.MARGIN, 720f, Ui.WIDTH - Ui.MARGIN, 830f, "NEW TRANSMISSION", Ink.CYAN, Ink.VOID)
        }
        button(canvas, Ids.HOME_META, Ui.MARGIN, 960f, (Ui.WIDTH - Ui.MARGIN) / 2f - 6f, 1054f, "UPGRADES", Ink.PANEL_LIGHT)
        button(canvas, Ids.HOME_ARCHIVE, (Ui.WIDTH + Ui.MARGIN) / 2f + 6f, 960f, Ui.WIDTH - Ui.MARGIN, 1054f, "ARCHIVE", Ink.PANEL_LIGHT)
        button(canvas, Ids.HOME_SETTINGS, Ui.MARGIN, 1070f, Ui.WIDTH - Ui.MARGIN, 1164f, "SETTINGS", Ink.PANEL_LIGHT)
        canvas.drawText("v1.0.0  deterministic core", Ui.MARGIN, 1216f, Ui.text(19f, Ink.TEXT_DIM, Ui.medium))
    }

    private fun meta(canvas: Canvas) {
        val profile = session.profile
        topBar(canvas, "PERMANENT UPGRADES", true)
        rect.set(Ui.MARGIN, 112f, Ui.WIDTH - Ui.MARGIN, 186f)
        Ui.panel(canvas, rect, 16f, Ui.alpha(Ink.PANEL, 0.92f))
        canvas.drawText("${Num.format(profile.aether)} AETHER", Ui.MARGIN + 20f, 158f, Ui.text(30f, Ink.GOLD, Ui.numeric))
        val rankTotal = MetaUpgrade.entries.sumOf { profile.rank(it) }
        canvas.drawText("RANKS $rankTotal", Ui.WIDTH - Ui.MARGIN - 20f, 156f, Ui.text(22f, Ink.TEXT_DIM, Ui.display, Paint.Align.RIGHT))
        val top = 206f - session.metaScroll
        val height = 108f
        MetaUpgrade.entries.forEachIndexed { index, upgrade ->
            val rowTop = top + index * (height + 12f)
            if (rowTop > 200f && rowTop < Ui.HEIGHT - 60f) {
                metaRow(canvas, upgrade, rowTop, profile)
            }
        }
    }

    private fun metaRow(canvas: Canvas, upgrade: MetaUpgrade, top: Float, profile: com.elthon.infinite.core.MetaProfile) {
        val rank = profile.rank(upgrade)
        val maxed = rank >= upgrade.maximumRank
        val locked = profile.bestStage < upgrade.unlockStage
        val cost = if (maxed) 0 else upgrade.rankCosts[minOf(rank, upgrade.rankCosts.size - 1)]
        val affordable = !maxed && !locked && profile.aether.compareTo(Num.decimal(cost)) >= 0
        rect.set(Ui.MARGIN, top, Ui.WIDTH - Ui.MARGIN, top + 108f)
        Ui.gradientPanel(
            canvas, rect, 16f,
            if (affordable) Ui.mix(Ink.PANEL, Ink.CYAN, 0.16f) else Ink.PANEL,
            Ink.PANEL_LIGHT
        )
        canvas.drawText(upgrade.displayName, Ui.MARGIN + 20f, top + 40f, Ui.text(26f, Ink.TEXT, Ui.display))
        Ui.paragraph(canvas, upgrade.description, Ui.MARGIN + 20f, top + 68f, Ui.text(19f, Ink.TEXT_DIM, Ui.medium), 22f, 52)
        var x = Ui.WIDTH - Ui.MARGIN - 20f
        repeat(upgrade.maximumRank) { index ->
            val lit = index < rank
            canvas.drawCircle(x - index * 22f, top + 36f, 7f, Ui.fill(if (lit) Ink.GOLD else Ui.alpha(Ink.TEXT_DIM, 0.35f)))
        }
        val state = when {
            maxed -> "MAX"
            locked -> "STAGE ${upgrade.unlockStage}"
            else -> "${Num.decimal(cost)} AE"
        }
        val enabled = !maxed && !locked
        button(canvas, Ids.META_PREFIX + upgrade.name, Ui.WIDTH - Ui.MARGIN - 150f, top + 58f, Ui.WIDTH - Ui.MARGIN - 20f, top + 96f, state, when {
            maxed -> Ui.alpha(Ink.PANEL_LIGHT, 0.7f)
            locked -> Ui.alpha(Ink.PANEL_LIGHT, 0.7f)
            affordable -> Ink.CYAN
            else -> Ui.PANEL_LIGHT
        }, if (affordable) Ink.VOID else Ink.TEXT_DIM, enabled)
    }

    private fun archive(canvas: Canvas) {
        val profile = session.profile
        topBar(canvas, "ARCHIVE", true)
        button(canvas, Ids.TAB_ACHIEVEMENTS, Ui.MARGIN, 112f, 352f, 174f, "ACHIEVEMENTS", if (session.archiveTab == 0) Ink.CYAN else Ink.PANEL_LIGHT, if (session.archiveTab == 0) Ink.VOID else Ink.TEXT)
        button(canvas, Ids.TAB_CODEX, 368f, 112f, Ui.WIDTH - Ui.MARGIN, 174f, "CODEX", if (session.archiveTab == 1) Ink.CYAN else Ink.PANEL_LIGHT, if (session.archiveTab == 1) Ink.VOID else Ink.TEXT)
        if (session.archiveTab == 0) {
            val unlocked = profile.achievements.size
            canvas.drawText("$unlocked / ${GameContent.achievements.size} UNLOCKED", Ui.MARGIN, 212f, sectionPaint)
            val top = 232f - session.achievementScroll
            GameContent.achievements.forEachIndexed { index, definition ->
                val rowTop = top + index * 104f
                if (rowTop > 220f && rowTop < Ui.HEIGHT - 40f) {
                    val owned = profile.achievements.contains(definition.id)
                    rect.set(Ui.MARGIN, rowTop, Ui.WIDTH - Ui.MARGIN, rowTop + 92f)
                    Ui.panel(canvas, rect, 14f, if (owned) Ui.mix(Ink.PANEL, Ink.GOLD, 0.12f) else Ink.PANEL)
                    canvas.drawText(definition.title, Ui.MARGIN + 18f, rowTop + 36f, Ui.text(25f, if (owned) Ink.GOLD else Ink.TEXT_DIM, Ui.display))
                    Ui.paragraph(canvas, definition.description, Ui.MARGIN + 18f, rowTop + 62f, Ui.text(19f, Ink.TEXT_DIM, Ui.medium), 20f, 54)
                    val progress = profile.achievementProgress[definition.id] ?: 0
                    Ui.bar(canvas, RectF(Ui.MARGIN + 300f, rowTop + 34f, Ui.WIDTH - Ui.MARGIN - 18f, rowTop + 46f), progress.toFloat() / definition.target.toFloat(), if (owned) Ink.GOLD else Ink.CYAN)
                    canvas.drawText("${min(progress, definition.target)}/${definition.target}", Ui.WIDTH - Ui.MARGIN - 18f, rowTop + 68f, Ui.text(19f, Ink.TEXT_DIM, Ui.numeric, Paint.Align.RIGHT))
                }
            }
        } else {
            val known = profile.discoveredFamilies
            canvas.drawText("${known.size} / ${com.elthon.infinite.core.CardFamily.entries.size} FAMILIES", Ui.MARGIN, 212f, sectionPaint)
            val top = 232f - session.codexScroll
            com.elthon.infinite.core.CardFamily.entries.forEachIndexed { index, family ->
                val rowTop = top + index * 96f
                if (rowTop > 220f && rowTop < Ui.HEIGHT - 40f) {
                    val discovered = known.contains(family)
                    rect.set(Ui.MARGIN, rowTop, Ui.WIDTH - Ui.MARGIN, rowTop + 84f)
                    Ui.panel(canvas, rect, 14f, Ink.PANEL)
                    val ink = if (discovered) Ink.CYAN else Ink.TEXT_DIM
                    canvas.drawText(if (discovered) family.displayName else "???", Ui.MARGIN + 18f, rowTop + 36f, Ui.text(25f, ink, Ui.display))
                    Ui.paragraph(canvas, if (discovered) family.identity else "Undiscovered signal", Ui.MARGIN + 18f, rowTop + 62f, Ui.text(19f, Ink.TEXT_DIM, Ui.medium), 20f, 54)
                    canvas.drawText("STAGE ${family.unlockStage}", Ui.WIDTH - Ui.MARGIN - 18f, rowTop + 40f, Ui.text(19f, Ink.TEXT_DIM, Ui.numeric, Paint.Align.RIGHT))
                }
            }
        }
    }

    private fun settings(canvas: Canvas) {
        val settings = session.profile.settings
        topBar(canvas, "SETTINGS", true)
        val rows = listOf(
            Triple("music", "Ambient signal", settings.musicEnabled),
            Triple("sound", "Combat audio", settings.soundEnabled),
            Triple("haptics", "Haptics", settings.hapticsEnabled),
            Triple("motion", "Reduced motion", settings.reducedMotion),
            Triple("damage", "Damage numbers", settings.showDamageNumbers)
        )
        rows.forEachIndexed { index, row ->
            val top = 150f + index * 132f
            rect.set(Ui.MARGIN, top, Ui.WIDTH - Ui.MARGIN, top + 112f)
            Ui.panel(canvas, rect, 16f, Ink.PANEL)
            canvas.drawText(row.second, Ui.MARGIN + 22f, top + 46f, Ui.text(26f, Ink.TEXT, Ui.display))
            val pill = if (row.third) Ink.CYAN else Ink.PANEL_LIGHT
            button(canvas, Ids.TOGGLE_PREFIX + row.first, Ui.WIDTH - Ui.MARGIN - 190f, top + 30f, Ui.WIDTH - Ui.MARGIN - 22f, top + 82f, if (row.third) "ON" else "OFF", pill, if (row.third) Ink.VOID else Ink.TEXT_DIM)
        }
        rect.set(Ui.MARGIN, 830f, Ui.WIDTH - Ui.MARGIN, 980f)
        Ui.panel(canvas, rect, 16f, Ui.alpha(Ink.PANEL, 0.9f))
        Ui.paragraph(
            canvas,
            "INFINITE is a deterministic endless duel. Every upgrade card, enemy wave and event is generated from a seeded stream, so the same seed always plays the same.",
            Ui.MARGIN + 20f, 866f, Ui.text(19f, Ink.TEXT_DIM, Ui.medium), 24f, 52
        )
    }

    private fun combat(canvas: Canvas) {
        val active = session.run ?: return
        val shakeX = if (session.shake > 0f) (session.animTime * 60f * session.shake % 6f) - 3f else 0f
        val shakeY = if (session.shake > 0f) (session.animTime * 83f * session.shake % 6f) - 3f else 0f
        canvas.save()
        canvas.translate(shakeX, shakeY)
        arena(canvas, active)
        canvas.restore()
        hud(canvas, active)
        controls(canvas, active)
        floatingText(canvas)
        banner(canvas)
        if (session.paused) pause(canvas)
    }

    private fun arena(canvas: Canvas, active: RunState) {
        val top = 96f
        val bottom = 1000f
        canvas.save()
        canvas.clipRect(0f, top, Ui.WIDTH, bottom)
        rect.set(0f, top, Ui.WIDTH, bottom)
        canvas.drawRect(rect, Ui.fill(Ink.ARENA))
        Ui.glow(canvas, Ui.WIDTH / 2f, (top + bottom) / 2f, 380f, Ui.alpha(Ink.CYAN, 0.06f))
        val grid = Ui.stroke(Ui.alpha(Ink.ARENA_EDGE, 0.9f), 1f)
        var gx = 0f
        while (gx <= Ui.WIDTH) {
            canvas.drawLine(gx, top, gx, bottom, grid)
            gx += 60f
        }
        var gy = top
        while (gy <= bottom) {
            canvas.drawLine(0f, gy, Ui.WIDTH, gy, grid)
            gy += 60f
        }
        canvas.drawCircle(Ui.WIDTH / 2f, (top + bottom) / 2f, 200f, Ui.stroke(Ui.alpha(Ink.ARENA_EDGE, 1.1f), 2f))
        active.projectiles.forEach { drawProjectile(canvas, it) }
        active.enemies.forEach { drawEnemy(canvas, it) }
        drawPlayer(canvas, active)
        canvas.restore()
        canvas.drawLine(0f, top, Ui.WIDTH, top, Ui.stroke(Ui.alpha(Ink.LINE, 0.8f), 1f))
        canvas.drawLine(0f, bottom, Ui.WIDTH, bottom, Ui.stroke(Ui.alpha(Ink.LINE, 0.8f), 1f))
    }

    private fun drawEnemy(canvas: Canvas, enemy: EnemyState) {
        val fraction = Num.toDouble(Num.divide(enemy.hp, enemy.maxHp), 0.0).coerceIn(0.0, 1.0).toFloat()
        val color = if (enemy.hp.compareTo(Num.ZERO) <= 0) Ui.alpha(enemy.type.color, 0.3f) else enemy.type.color
        when (enemy.type.shape) {
            EnemyShape.CIRCLE -> canvas.drawCircle(enemy.position.x, enemy.position.y, enemy.radius, Ui.fill(color))
            EnemyShape.TRIANGLE -> Ui.polygon(canvas, enemy.position.x, enemy.position.y, enemy.radius, 3, 0.6f, color)
            EnemyShape.HEX -> Ui.polygon(canvas, enemy.position.x, enemy.position.y, enemy.radius, 6, 0f, color)
            EnemyShape.DIAMOND -> Ui.polygon(canvas, enemy.position.x, enemy.position.y, enemy.radius, 4, 0f, color)
            EnemyShape.SQUARE -> {
                rect.set(enemy.position.x - enemy.radius, enemy.position.y - enemy.radius, enemy.position.x + enemy.radius, enemy.position.y + enemy.radius)
                canvas.drawRoundRect(rect, 6f, 6f, Ui.fill(color))
            }
            EnemyShape.SHARD -> {
                path.reset()
                path.moveTo(enemy.position.x, enemy.position.y - enemy.radius)
                path.lineTo(enemy.position.x + enemy.radius * 0.7f, enemy.position.y)
                path.lineTo(enemy.position.x, enemy.position.y + enemy.radius)
                path.lineTo(enemy.position.x - enemy.radius * 0.7f, enemy.position.y)
                path.close()
                canvas.drawPath(path, Ui.fill(color))
            }
            EnemyShape.STAR -> Ui.star(canvas, enemy.position.x, enemy.position.y, enemy.radius, enemy.radius * 0.45f, 5, session.animTime * 0.8f, color)
            EnemyShape.CROSS -> {
                canvas.drawRect(enemy.position.x - enemy.radius * 0.35f, enemy.position.y - enemy.radius, enemy.position.x + enemy.radius * 0.35f, enemy.position.y + enemy.radius, Ui.fill(color))
                canvas.drawRect(enemy.position.x - enemy.radius, enemy.position.y - enemy.radius * 0.35f, enemy.position.x + enemy.radius, enemy.position.y + enemy.radius * 0.35f, Ui.fill(color))
            }
            EnemyShape.PULSE -> {
                val pulse = 1f + 0.12f * sin(session.animTime * 6f)
                canvas.drawCircle(enemy.position.x, enemy.position.y, enemy.radius * pulse, Ui.fill(color))
                canvas.drawCircle(enemy.position.x, enemy.position.y, enemy.radius * pulse * 0.55f, Ui.fill(Ui.alpha(Ink.VOID, 0.6f)))
            }
            EnemyShape.CROWN -> {
                path.reset()
                path.moveTo(enemy.position.x - enemy.radius, enemy.position.y + enemy.radius * 0.7f)
                path.lineTo(enemy.position.x - enemy.radius * 0.8f, enemy.position.y - enemy.radius * 0.5f)
                path.lineTo(enemy.position.x - enemy.radius * 0.35f, enemy.position.y + enemy.radius * 0.1f)
                path.lineTo(enemy.position.x, enemy.position.y - enemy.radius)
                path.lineTo(enemy.position.x + enemy.radius * 0.35f, enemy.position.y + enemy.radius * 0.1f)
                path.lineTo(enemy.position.x + enemy.radius * 0.8f, enemy.position.y - enemy.radius * 0.5f)
                path.lineTo(enemy.position.x + enemy.radius, enemy.position.y + enemy.radius * 0.7f)
                path.close()
                canvas.drawPath(path, Ui.fill(color))
            }
        }
        if (enemy.attackFlash > 0f && enemy.hp.compareTo(Num.ZERO) > 0) {
            canvas.drawCircle(enemy.position.x, enemy.position.y, enemy.radius + 8f, Ui.stroke(Ui.alpha(Ink.DANGER, 0.85f), 3f))
        }
        if (enemy.elite || enemy.boss) {
            canvas.drawCircle(enemy.position.x, enemy.position.y, enemy.radius + 5f, Ui.stroke(if (enemy.boss) Ink.GOLD else Ink.MAGENTA, 3f))
        }
        val barWidth = max(34f, enemy.radius * 2f)
        if (fraction < 1f || enemy.elite || enemy.boss) {
            Ui.bar(canvas, RectF(enemy.position.x - barWidth / 2f, enemy.position.y - enemy.radius - 16f, enemy.position.x + barWidth / 2f, enemy.position.y - enemy.radius - 8f), fraction, if (enemy.boss) Ink.GOLD else Ink.DANGER)
        }
        var sx = enemy.position.x - (enemy.statuses.size - 1) * 7f
        enemy.statuses.forEach { status ->
            canvas.drawCircle(sx, enemy.position.y + enemy.radius + 12f, 5f, Ui.fill(status.type.color))
            sx += 14f
        }
    }

    private fun drawPlayer(canvas: Canvas, active: RunState) {
        val player = active.player
        val blink = if (player.invulnerable > 0f && (session.animTime * 18f).toInt() % 2 == 0) 0.45f else 1f
        val color = Ui.alpha(if (player.dashRemaining > 0f) Ink.VIOLET else Ink.CYAN, blink)
        Ui.glow(canvas, player.position.x, player.position.y, CombatRules.PLAYER_RADIUS * 2.6f, Ui.alpha(color, 0.4f))
        canvas.drawCircle(player.position.x, player.position.y, CombatRules.PLAYER_RADIUS + 4f, Ui.stroke(Ui.alpha(Ink.CYAN, 0.5f * blink), 2f))
        canvas.drawCircle(player.position.x, player.position.y, CombatRules.PLAYER_RADIUS, Ui.fill(color))
        canvas.drawCircle(player.position.x - 6f, player.position.y - 6f, CombatRules.PLAYER_RADIUS * 0.42f, Ui.fill(Ui.alpha(Ink.VOID, 0.45f * blink)))
        val tipX = player.position.x + cos(player.facing) * (CombatRules.PLAYER_RADIUS + 16f)
        val tipY = player.position.y + sin(player.facing) * (CombatRules.PLAYER_RADIUS + 16f)
        path.reset()
        path.moveTo(tipX, tipY)
        path.lineTo(player.position.x + cos(player.facing + 2.4f) * CombatRules.PLAYER_RADIUS, player.position.y + sin(player.facing + 2.4f) * CombatRules.PLAYER_RADIUS)
        path.lineTo(player.position.x + cos(player.facing - 2.4f) * CombatRules.PLAYER_RADIUS, player.position.y + sin(player.facing - 2.4f) * CombatRules.PLAYER_RADIUS)
        path.close()
        canvas.drawPath(path, Ui.fill(Ui.alpha(Ink.CYAN, 0.8f * blink)))
        if (player.shield.compareTo(Num.ZERO) > 0) {
            canvas.drawCircle(player.position.x, player.position.y, CombatRules.PLAYER_RADIUS + 9f, Ui.stroke(Ui.alpha(Ink.CYAN, 0.8f), 3f))
        }
    }

    private fun drawProjectile(canvas: Canvas, projectile: ProjectileState) {
        val alpha = if (projectile.team == com.elthon.infinite.core.ProjectileTeam.PLAYER) 1f else 0.9f
        Ui.glow(canvas, projectile.position.x, projectile.position.y, projectile.radius * 3.2f, Ui.alpha(projectile.color, 0.5f * alpha))
        canvas.drawCircle(projectile.position.x, projectile.position.y, projectile.radius, Ui.fill(Ui.alpha(projectile.color, alpha)))
    }

    private fun hud(canvas: Canvas, active: RunState) {
        val profile = session.profile
        val player = active.player
        canvas.drawRect(0f, 0f, Ui.WIDTH, 96f, Ui.fill(Ui.alpha(Ink.VOID, 0.88f)))
        canvas.drawLine(0f, 96f, Ui.WIDTH, 96f, Ui.stroke(Ui.alpha(Ink.LINE, 0.9f), 2f))
        canvas.drawText("STAGE ${Num.formatStage(active.stage)}", Ui.MARGIN, 44f, Ui.text(30f, Ink.TEXT, Ui.numeric))
        canvas.drawText("BEST ${Num.formatStage(maxOf(profile.bestStage, active.highestStage))}", Ui.MARGIN, 74f, Ui.text(19f, Ink.TEXT_DIM, Ui.numeric))
        val challenge = active.challenge
        if (challenge != ChallengeModifier.NONE) {
            canvas.drawText(challenge.displayName, Ui.WIDTH / 2f, 74f, Ui.text(19f, Ink.MAGENTA, Ui.display, Paint.Align.CENTER))
        }
        val maxHp = active.maxHpSnapshot
        val barLeft = Ui.MARGIN
        val barRight = Ui.WIDTH - Ui.MARGIN - 120f
        Ui.bar(canvas, RectF(barLeft, 52f, barRight, 70f), Num.toDouble(Num.divide(player.hp, maxHp), 0.0).toFloat(), Ink.CYAN, Ui.alpha(Ink.PANEL_LIGHT, 0.9f))
        if (player.shield.compareTo(Num.ZERO) > 0) {
            Ui.bar(canvas, RectF(barLeft, 72f, barRight, 84f), Num.toDouble(Num.divide(player.shield, maxHp), 0.0).toFloat().coerceAtMost(1f), Ink.MAGENTA, Ui.alpha(Ink.PANEL_LIGHT, 0.9f))
        }
        val hpText = "${Num.format(player.hp)} / ${Num.format(maxHp)}"
        canvas.drawText(hpText, barRight, 48f, Ui.text(19f, Ink.TEXT_DIM, Ui.numeric, Paint.Align.RIGHT))
        canvas.drawText("${Num.format(profile.aether)} AE", Ui.WIDTH - Ui.MARGIN - 62f, 62f, Ui.text(20f, Ink.GOLD, Ui.numeric, Paint.Align.CENTER))
        button(canvas, Ids.PAUSE, Ui.WIDTH - Ui.MARGIN - 52f, 20f, Ui.WIDTH - Ui.MARGIN, 68f, "||", Ui.alpha(Ink.PANEL_LIGHT, 0.9f))
    }

    private fun controls(canvas: Canvas, active: RunState) {
        val touch = session.input
        canvas.drawRect(0f, 1000f, Ui.WIDTH, Ui.HEIGHT, Ui.fill(Ui.alpha(Ink.VOID, 0.75f)))
        canvas.drawLine(0f, 1000f, Ui.WIDTH, 1000f, Ui.stroke(Ui.alpha(Ink.LINE, 0.8f), 1f))
        if (touch.joystickActive()) {
            val origin = touch.joystickOrigin()
            val knob = touch.joystickKnob()
            canvas.drawCircle(origin.x, origin.y, TouchState.MAX_RADIUS, Ui.fill(Ui.alpha(Ink.PANEL, 0.5f)))
            canvas.drawCircle(origin.x, origin.y, TouchState.MAX_RADIUS, Ui.stroke(Ui.alpha(Ink.CYAN, 0.45f), 3f))
            canvas.drawCircle(knob.x, knob.y, 52f, Ui.fill(Ui.alpha(Ink.CYAN, 0.35f)))
            canvas.drawCircle(knob.x, knob.y, 52f, Ui.stroke(Ui.alpha(Ink.CYAN, 0.9f), 3f))
        } else {
            val hintX = 150f
            val hintY = 1140f
            canvas.drawCircle(hintX, hintY, 108f, Ui.fill(Ui.alpha(Ink.PANEL, 0.35f)))
            canvas.drawCircle(hintX, hintY, 108f, Ui.stroke(Ui.alpha(Ink.CYAN, 0.3f), 2f))
            canvas.drawText("MOVE", hintX, hintY + 8f, Ui.text(24f, Ui.alpha(Ink.TEXT_DIM, 0.8f), Ui.display, Paint.Align.CENTER))
        }
        val novaCd = active.player.novaTimer / CombatRules.NOVA_COOLDOWN
        val dashCd = active.player.dashCooldown / CombatRules.DASH_COOLDOWN
        abilityButton(canvas, TouchState.NOVA_ID, 566f, 1082f, 76f, "NOVA", 1f - novaCd, Ink.CYAN)
        abilityButton(canvas, TouchState.DASH_ID, 566f, 1224f, 58f, "PHASE", 1f - dashCd, Ink.VIOLET)
        canvas.drawText("KILLS ${active.stats.enemiesDefeated}", Ui.MARGIN, 1244f, Ui.text(20f, Ink.TEXT_DIM, Ui.numeric))
        canvas.drawText("CARDS ${active.stats.cardsCollected}", Ui.MARGIN, 1270f, Ui.text(20f, Ink.TEXT_DIM, Ui.numeric))
        val synergyCount = active.synergies.size
        if (synergyCount > 0) {
            canvas.drawText("SYNERGY x$synergyCount", Ui.MARGIN, 1016f, Ui.text(20f, Ink.GOLD, Ui.display))
        }
    }

    private fun abilityButton(canvas: Canvas, id: String, cx: Float, cy: Float, radius: Float, label: String, ready: Float, color: Int) {
        val fraction = ready.coerceIn(0f, 1f)
        canvas.drawCircle(cx, cy, radius, Ui.fill(Ui.alpha(Ink.PANEL, 0.9f)))
        canvas.drawCircle(cx, cy, radius, Ui.stroke(Ui.alpha(color, 0.85f), 3f))
        val sweep = Ui.stroke(Ui.alpha(color, 0.85f), 6f)
        sweep.strokeCap = Paint.Cap.ROUND
        rect.set(cx - radius + 6f, cy - radius + 6f, cx + radius - 6f, cy + radius - 6f)
        canvas.drawArc(rect, -90f, 360f * fraction, false, sweep)
        sweep.strokeCap = Paint.Cap.BUTT
        canvas.drawCircle(cx, cy, radius * 0.72f, Ui.fill(Ui.alpha(color, 0.16f + 0.2f * fraction)))
        canvas.drawText(label, cx, cy + 7f, Ui.text(if (radius > 70f) 24f else 20f, if (fraction > 0.5f) Ink.TEXT else Ink.TEXT_DIM, Ui.display, Paint.Align.CENTER))
        session.regions.add(HitRegion(id, cx - radius, cy - radius, cx + radius, cy + radius, enabled = true))
    }

    private fun pause(canvas: Canvas) {
        canvas.drawColor(Ui.alpha(Ink.VOID, 0.82f))
        val cx = Ui.WIDTH / 2f
        canvas.drawText("PAUSED", cx, 330f, bigPaint)
        canvas.drawText("The signal is frozen", cx, 376f, Ui.text(22f, Ink.TEXT_DIM, Ui.display, Paint.Align.CENTER))
        button(canvas, Ids.RESUME, Ui.MARGIN, 440f, Ui.WIDTH - Ui.MARGIN, 540f, "RESUME", Ink.CYAN, Ink.VOID)
        button(canvas, Ids.PAUSE_SAVE, Ui.MARGIN, 560f, Ui.WIDTH - Ui.MARGIN, 660f, "SAVE NOW", Ink.PANEL_LIGHT)
        button(canvas, Ids.PAUSE_QUIT, Ui.MARGIN, 680f, Ui.WIDTH - Ui.MARGIN, 780f, "ABANDON RUN", Ui.alpha(Ink.DANGER, 0.85f), Ink.VOID)
    }

    private fun cards(canvas: Canvas) {
        val active = session.run ?: return
        canvas.drawColor(Ui.alpha(Ink.VOID, 0.9f))
        val cx = Ui.WIDTH / 2f
        canvas.drawText("UPGRADE", cx, 190f, bigPaint)
        canvas.drawText("Stage ${Num.formatStage(active.stage)} cleared", cx, 232f, Ui.text(22f, Ink.TEXT_DIM, Ui.display, Paint.Align.CENTER))
        val offers = active.cardOffers
        val top = 268f
        val bottomLimit = 1096f
        val gap = 12f
        val count = max(1, offers.size)
        val height = min(300f, (bottomLimit - top - (count - 1) * gap) / count)
        val compact = height < 250f
        offers.forEachIndexed { index, card ->
            val cardTop = top + index * (height + gap)
            cardPanel(canvas, card, cardTop, height, Ids.CARD_PREFIX + card.id, compact)
        }
        val rerollEnabled = active.rerolls > 0
        button(
            canvas, Ids.REROLL, Ui.MARGIN, 1116f, Ui.WIDTH - Ui.MARGIN, 1212f,
            "REROLL  x${active.rerolls}", if (rerollEnabled) Ink.VIOLET else Ui.alpha(Ink.PANEL_LIGHT, 0.6f),
            if (rerollEnabled) Ink.VOID else Ink.TEXT_DIM, rerollEnabled
        )
    }

    private fun cardPanel(
        canvas: Canvas,
        card: com.elthon.infinite.core.CardDefinition,
        top: Float,
        height: Float,
        id: String,
        compact: Boolean
    ) {
        val accent = Ui.rarityColor(card.rarity)
        rect.set(Ui.MARGIN, top, Ui.WIDTH - Ui.MARGIN, top + height)
        Ui.gradientPanel(canvas, rect, 18f, Ui.mix(Ink.PANEL, accent, 0.22f), Ink.PANEL_LIGHT, accent)
        val titleSize = if (compact) 26f else 30f
        canvas.drawText(card.title, Ui.MARGIN + 24f, top + if (compact) 44f else 52f, Ui.text(titleSize, Ink.TEXT, Ui.display))
        canvas.drawText(card.rarity.displayName.uppercase(), Ui.WIDTH - Ui.MARGIN - 24f, top + if (compact) 44f else 52f, Ui.text(20f, accent, Ui.display, Paint.Align.RIGHT))
        canvas.drawText(card.family.displayName, Ui.MARGIN + 24f, top + if (compact) 70f else 80f, Ui.text(20f, Ui.alpha(accent, 0.95f), Ui.display))
        var lineY = top + if (compact) 100f else 116f
        val modifierLimit = if (compact) 1 else 3
        val ruleLimit = if (compact) 1 else 2
        card.modifiers.take(modifierLimit).forEach { modifier ->
            val text = modifierText(modifier)
            Ui.paragraph(canvas, text, Ui.MARGIN + 24f, lineY, Ui.text(20f, Ink.TEXT, Ui.medium), 24f, 46)
            lineY += Ui.paragraphHeight(text, 24f, 46) + 4f
        }
        card.rules.take(ruleLimit).forEach { rule ->
            Ui.paragraph(canvas, rule.text, Ui.MARGIN + 24f, lineY, Ui.text(19f, Ui.alpha(Ink.GOLD, 0.95f), Ui.medium), 22f, 48)
            lineY += Ui.paragraphHeight(rule.text, 22f, 48) + 4f
        }
        session.regions.add(HitRegion(id, rect.left, rect.top, rect.right, rect.bottom))
    }

    private fun modifierText(modifier: com.elthon.infinite.core.StatModifier): String {
        val sign = if (modifier.operation == com.elthon.infinite.core.ModifierOperation.MULTIPLY) "x" else "+"
        val value = if (modifier.operation == com.elthon.infinite.core.ModifierOperation.MULTIPLY) {
            Num.format(Num.add(Num.ONE, modifier.amount)) + "x"
        } else {
            Num.format(modifier.amount)
        }
        val suffix = if (modifier.condition == com.elthon.infinite.core.EffectCondition.ALWAYS) "" else " (conditional)"
        return "$sign$value ${modifier.stat.label}$suffix"
    }

    private fun event(canvas: Canvas) {
        val active = session.run ?: return
        val state = active.specialEvent ?: return
        canvas.drawColor(Ui.alpha(Ink.VOID, 0.92f))
        val cx = Ui.WIDTH / 2f
        Ui.glow(canvas, cx, 300f, 240f, Ink.MAGENTA)
        canvas.drawText(state.type.title, cx, 300f, Ui.text(44f, Ink.TEXT, Ui.display, Paint.Align.CENTER))
        Ui.paragraph(canvas, state.type.description, cx, 350f, Ui.text(22f, Ink.TEXT_DIM, Ui.medium, Paint.Align.CENTER), 28f, 40)
        state.choices.forEachIndexed { index, choice ->
            val top = 470f + index * 260f
            rect.set(Ui.MARGIN, top, Ui.WIDTH - Ui.MARGIN, top + 220f)
            Ui.gradientPanel(canvas, rect, 18f, Ui.mix(Ink.PANEL, Ink.MAGENTA, 0.18f), Ink.PANEL_LIGHT, Ui.alpha(Ink.MAGENTA, 0.8f))
            canvas.drawText(choice.title, Ui.MARGIN + 26f, top + 58f, Ui.text(30f, Ink.TEXT, Ui.display))
            Ui.paragraph(canvas, choice.description, Ui.MARGIN + 26f, top + 100f, Ui.text(21f, Ink.TEXT_DIM, Ui.medium), 26f, 44)
            session.regions.add(HitRegion(Ids.EVENT_PREFIX + choice.tag, rect.left, rect.top, rect.right, rect.bottom))
        }
    }

    private fun gameOver(canvas: Canvas) {
        val summary = session.lastSummary
        canvas.drawColor(Ui.alpha(Ink.VOID, 0.94f))
        val cx = Ui.WIDTH / 2f
        Ui.glow(canvas, cx, 250f, 260f, Ink.DANGER)
        canvas.drawText("SIGNAL LOST", cx, 250f, Ui.text(52f, Ink.DANGER, Ui.display, Paint.Align.CENTER))
        canvas.drawText("Stage ${Num.formatStage(summary?.stageReached ?: 0L)} reached", cx, 300f, Ui.text(24f, Ink.TEXT_DIM, Ui.display, Paint.Align.CENTER))
        rect.set(Ui.MARGIN, 350f, Ui.WIDTH - Ui.MARGIN, 700f)
        Ui.panel(canvas, rect, 20f, Ink.PANEL)
        val lines = listOf(
            "Enemies defeated" to Num.formatStage(summary?.enemiesDefeated ?: 0L),
            "Cards collected" to "${summary?.cardsCollected ?: 0}",
            "Synergies active" to "${summary?.synergies ?: 0}",
            "Best hit" to Num.format(summary?.bestDamage ?: Num.ZERO),
            "Aether earned" to Num.format(summary?.aetherEarned ?: Num.ZERO)
        )
        lines.forEachIndexed { index, line ->
            val y = 412f + index * 62f
            canvas.drawText(line.first, Ui.MARGIN + 28f, y, Ui.text(24f, Ink.TEXT_DIM, Ui.medium))
            canvas.drawText(line.second, Ui.WIDTH - Ui.MARGIN - 28f, y, Ui.text(26f, Ink.GOLD, Ui.numeric, Paint.Align.RIGHT))
        }
        val achievements = summary?.newAchievements.orEmpty()
        if (achievements.isNotEmpty()) {
            canvas.drawText("ACHIEVEMENTS", cx, 760f, Ui.text(24f, Ink.GOLD, Ui.display, Paint.Align.CENTER))
            achievements.forEachIndexed { index, title ->
                canvas.drawText("- $title", cx, 800f + index * 32f, Ui.text(22f, Ink.TEXT, Ui.medium, Paint.Align.CENTER))
            }
        }
        button(canvas, Ids.GAMEOVER_RETRY, Ui.MARGIN, 900f, Ui.WIDTH - Ui.MARGIN, 1010f, "TRANSMIT AGAIN", Ink.CYAN, Ink.VOID)
        button(canvas, Ids.GAMEOVER_HOME, Ui.MARGIN, 1030f, Ui.WIDTH - Ui.MARGIN, 1140f, "RETURN TO BASE", Ink.PANEL_LIGHT)
    }

    private fun offline(canvas: Canvas) {
        val report = session.offlineReport
        canvas.drawColor(Ui.alpha(Ink.VOID, 0.95f))
        val cx = Ui.WIDTH / 2f
        Ui.glow(canvas, cx, 300f, 240f, Ink.CYAN)
        canvas.drawText("OFFLINE SIGNAL", cx, 300f, Ui.text(44f, Ink.TEXT, Ui.display, Paint.Align.CENTER))
        rect.set(Ui.MARGIN, 360f, Ui.WIDTH - Ui.MARGIN, 640f)
        Ui.panel(canvas, rect, 20f, Ink.PANEL)
        val hours = (report?.elapsedSeconds ?: 0L) / 3600L
        val minutes = ((report?.elapsedSeconds ?: 0L) % 3600L) / 60L
        canvas.drawText("Away for ${hours}h ${minutes}m", cx, 420f, Ui.text(24f, Ink.TEXT_DIM, Ui.display, Paint.Align.CENTER))
        canvas.drawText("+${Num.format(report?.energyAwarded ?: Num.ZERO)}", cx, 510f, Ui.text(56f, Ink.GOLD, Ui.numeric, Paint.Align.CENTER))
        canvas.drawText("SIGNAL ENERGY", cx, 552f, Ui.text(22f, Ink.TEXT_DIM, Ui.display, Paint.Align.CENTER))
        if (report?.capped == true) {
            Ui.paragraph(canvas, "Offline gains are capped at 8 hours.", cx, 600f, Ui.text(19f, Ink.TEXT_DIM, Ui.medium, Paint.Align.CENTER), 24f, 40)
        }
        button(canvas, Ids.OFFLINE_OK, Ui.MARGIN, 720f, Ui.WIDTH - Ui.MARGIN, 830f, "COLLECT", Ink.CYAN, Ink.VOID)
    }

    private fun floatingText(canvas: Canvas) {
        session.floating.forEach { item ->
            val progress = ((session.animTime - item.born) / 1.25f).coerceIn(0f, 1f)
            val alpha = if (progress < 0.7f) 1f else 1f - (progress - 0.7f) / 0.3f
            val y = item.y - item.rise * progress
            canvas.drawText(item.text, item.x, y, Ui.text(item.size, Ui.alpha(item.color, alpha), Ui.numeric, Paint.Align.CENTER))
        }
    }

    private fun banner(canvas: Canvas) {
        val text = session.banner ?: return
        val alpha = (session.bannerTimer / 0.6f).coerceIn(0f, 1f)
        val paint = Ui.text(30f, Ui.alpha(Ink.CYAN, alpha), Ui.display, Paint.Align.CENTER)
        rect.set(Ui.MARGIN, 380f, Ui.WIDTH - Ui.MARGIN, 448f)
        canvas.drawRoundRect(rect, 14f, 14f, Ui.fill(Ui.alpha(Ink.VOID, 0.7f * alpha)))
        canvas.drawText(text, Ui.WIDTH / 2f, 424f, paint)
    }

    private fun toast(canvas: Canvas) {
        val text = session.toast ?: return
        val alpha = (session.toastTimer / 0.5f).coerceIn(0f, 1f)
        val lines = Ui.wrap(text, 44)
        val height = 34f + lines.size * 30f
        val top = Ui.HEIGHT - height - 150f
        rect.set(Ui.MARGIN, top, Ui.WIDTH - Ui.MARGIN, top + height)
        canvas.drawRoundRect(rect, 14f, 14f, Ui.fill(Ui.alpha(Ink.VOID, 0.92f)))
        canvas.drawRoundRect(rect, 14f, 14f, Ui.stroke(Ui.alpha(Ink.GOLD, alpha), 2f))
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, Ui.WIDTH / 2f, top + 40f + index * 30f, Ui.text(22f, Ui.alpha(Ink.TEXT, alpha), Ui.medium, Paint.Align.CENTER))
        }
    }

    private fun button(
        canvas: Canvas,
        id: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        label: String,
        color: Int,
        textColor: Int = Ink.TEXT,
        enabled: Boolean = true
    ) {
        rect.set(left, top, right, bottom)
        val radius = (bottom - top) / 2f
        val fillColor = if (enabled) color else Ui.alpha(Ink.PANEL_LIGHT, 0.55f)
        canvas.drawRoundRect(rect, radius, radius, Ui.fill(fillColor))
        canvas.drawRoundRect(rect, radius, radius, Ui.stroke(if (enabled) Ui.alpha(Ink.CYAN, 0.35f) else Ui.alpha(Ink.LINE, 0.6f), 2f))
        val size = min(30f, (bottom - top) * 0.42f)
        val paint = Ui.text(size, if (enabled) textColor else Ui.alpha(Ink.TEXT_DIM, 0.6f), Ui.display, Paint.Align.CENTER)
        val baseline = top + (bottom - top) / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(label, (left + right) / 2f, baseline, paint)
        session.regions.add(HitRegion(id, left, top, right, bottom, enabled))
    }
}
