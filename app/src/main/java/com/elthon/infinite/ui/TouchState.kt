package com.elthon.infinite.ui

import com.elthon.infinite.core.CombatInput
import com.elthon.infinite.core.Vec2
import kotlin.math.max
import kotlin.math.min

data class HitRegion(
    val id: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val enabled: Boolean = true
) {
    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom
}

class TouchState {
    private val activePointers = HashMap<Int, Pointer>()
    private var movePointerId = -1
    private var novaPointerId = -1
    private var dashPointerId = -1

    var moveX = 0f
    var moveY = 0f
    var novaPressed = false
    var dashPressed = false
    var usingJoystick = false

    private val base = Vec2()
    private val current = Vec2()
    private val knob = Vec2()

    fun reset() {
        activePointers.clear()
        movePointerId = -1
        novaPointerId = -1
        dashPointerId = -1
        moveX = 0f
        moveY = 0f
        novaPressed = false
        dashPressed = false
        usingJoystick = false
    }

    fun down(id: Int, x: Float, y: Float, regions: List<HitRegion>) {
        val region = regions.firstOrNull { it.enabled && it.contains(x, y) }
        if (region != null) {
            activePointers[id] = Pointer(region.id, x, y)
            when (region.id) {
                NOVA_ID -> {
                    novaPointerId = id
                    novaPressed = true
                }
                DASH_ID -> {
                    dashPointerId = id
                    dashPressed = true
                }
            }
            return
        }
        if (x < Ui.WIDTH * 0.55f && movePointerId < 0) {
            movePointerId = id
            base.set(x, y)
            current.set(x, y)
            knob.set(x, y)
            usingJoystick = true
            activePointers[id] = Pointer(MOVE_ID, x, y)
        }
    }

    fun move(id: Int, x: Float, y: Float) {
        val pointer = activePointers[id] ?: return
        pointer.x = x
        pointer.y = y
        if (id == movePointerId) {
            current.set(x, y)
            updateMove()
        }
    }

    fun up(id: Int, x: Float, y: Float, regions: List<HitRegion>) {
        activePointers.remove(id)
        if (id == movePointerId) {
            movePointerId = -1
            moveX = 0f
            moveY = 0f
            usingJoystick = false
        }
        if (id == novaPointerId) novaPointerId = -1
        if (id == dashPointerId) dashPointerId = -1
        regions.firstOrNull { it.enabled && it.contains(x, y) }?.let { pendingReleases.add(it.id) }
    }

    fun cancel(id: Int) {
        activePointers.remove(id)
        if (id == movePointerId) {
            movePointerId = -1
            moveX = 0f
            moveY = 0f
            usingJoystick = false
        }
        if (id == novaPointerId) novaPointerId = -1
        if (id == dashPointerId) dashPointerId = -1
    }

    val pendingReleases = ArrayList<String>()

    fun drainReleases(): List<String> {
        if (pendingReleases.isEmpty()) return emptyList()
        val copy = ArrayList(pendingReleases)
        pendingReleases.clear()
        return copy
    }

    fun consumeAbilityFlags(): CombatInput {
        val input = CombatInput(moveX, moveY, novaPressed, dashPressed)
        novaPressed = false
        dashPressed = false
        return input
    }

    fun joystickOrigin(): Vec2 = base

    fun joystickKnob(): Vec2 = knob

    fun joystickActive(): Boolean = usingJoystick

    private fun updateMove() {
        val delta = current.subtract(base)
        val length = delta.length()
        if (length <= 0.0001f) {
            moveX = 0f
            moveY = 0f
            knob.set(base.x, base.y)
            return
        }
        val clamped = min(length, MAX_RADIUS)
        val direction = delta.multiply(1f / length)
        knob.set(base.x + direction.x * clamped, base.y + direction.y * clamped)
        val strength = (clamped / MAX_RADIUS).coerceIn(0f, 1f)
        val curve = strength * strength * (3f - 2f * strength)
        moveX = max(-1f, min(1f, direction.x * curve))
        moveY = max(-1f, min(1f, direction.y * curve))
    }

    private data class Pointer(val id: String, var x: Float, var y: Float)

    companion object {
        const val MOVE_ID = "joystick"
        const val NOVA_ID = "action.nova"
        const val DASH_ID = "action.dash"
        const val MAX_RADIUS = 132f
    }
}

fun Vec2.set(x: Float, y: Float) {
    this.x = x
    this.y = y
}
