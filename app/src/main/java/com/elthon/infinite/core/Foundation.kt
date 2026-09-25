package com.elthon.infinite.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor

object Num {
    val MC: MathContext = MathContext(40, RoundingMode.HALF_EVEN)
    val ZERO: BigDecimal = BigDecimal("0")
    val ONE: BigDecimal = BigDecimal("1")
    val TWO: BigDecimal = BigDecimal("2")
    val TEN: BigDecimal = BigDecimal("10")
    val HUNDRED: BigDecimal = BigDecimal("100")
    val LIMIT: BigDecimal = BigDecimal("1e1000000")
    private val LN10: BigDecimal = decimal("2.3025850929940456840179914546843642076011014886288")
    private val EXP_CUTOFF: BigDecimal = decimal("1000000")
    private val EXP_EPSILON: BigDecimal = decimal("1e-36")
    private val suffixes = arrayOf("", "K", "M", "B", "T", "Qa", "Qi", "Sx", "Sp", "Oc", "No", "Dc")

    fun decimal(value: String): BigDecimal = BigDecimal(value, MC)
    fun decimal(value: Long): BigDecimal = BigDecimal.valueOf(value)
    fun decimal(value: Int): BigDecimal = BigDecimal.valueOf(value.toLong())
    fun decimal(value: Double): BigDecimal = if (value.isFinite()) BigDecimal.valueOf(value).round(MC) else ZERO

    fun add(left: BigDecimal, right: BigDecimal): BigDecimal = sanitize(left.add(right, MC))
    fun subtract(left: BigDecimal, right: BigDecimal): BigDecimal = sanitize(left.subtract(right, MC))
    fun multiply(left: BigDecimal, right: BigDecimal): BigDecimal = sanitize(left.multiply(right, MC))
    fun divide(left: BigDecimal, right: BigDecimal): BigDecimal {
        if (right.compareTo(ZERO) == 0) return ZERO
        return sanitize(left.divide(right, MC))
    }

    fun clamp(value: BigDecimal, minimum: BigDecimal, maximum: BigDecimal): BigDecimal {
        val safe = sanitize(value)
        if (safe.compareTo(minimum) < 0) return minimum
        if (safe.compareTo(maximum) > 0) return maximum
        return safe
    }

    fun min(left: BigDecimal, right: BigDecimal): BigDecimal = if (left.compareTo(right) <= 0) left else right
    fun max(left: BigDecimal, right: BigDecimal): BigDecimal = if (left.compareTo(right) >= 0) left else right
    fun percent(value: BigDecimal, percent: BigDecimal): BigDecimal = divide(multiply(value, percent), HUNDRED)
    fun ratio(value: BigDecimal, denominator: BigDecimal): BigDecimal = divide(value, denominator)
    fun isFinite(value: BigDecimal): Boolean = value.compareTo(LIMIT) <= 0 && value.compareTo(LIMIT.negate()) >= 0
    fun sanitize(value: BigDecimal): BigDecimal = clampWithoutRecursion(value)

    fun ln(value: BigDecimal): BigDecimal {
        val safe = sanitize(value)
        require(safe.compareTo(ZERO) > 0) { "ln requires a positive number" }
        if (safe.compareTo(ONE) == 0) return ZERO
        val absolute = safe.abs()
        val decimalExponent = absolute.precision() - absolute.scale() - 1
        val normalized = absolute.movePointLeft(decimalExponent)
        var estimate = if (normalized.compareTo(ONE) == 0) {
            ZERO
        } else {
            decimal(kotlin.math.ln(normalized.toDouble()))
        }
        repeat(24) {
            estimate = divide(add(estimate, divide(normalized, estimate)), TWO)
        }
        return add(estimate, multiply(decimal(decimalExponent), LN10))
    }

    fun exp(value: BigDecimal): BigDecimal {
        val safe = sanitize(value)
        if (safe.compareTo(EXP_CUTOFF) > 0) {
            val decimalExponent = safe.divide(LN10, MC).setScale(0, RoundingMode.FLOOR)
            val exponent = decimalExponent.toLong()
            val remainder = subtract(safe, multiply(decimalExponent, LN10))
            val mantissa = expSmall(remainder)
            return BigDecimal("${mantissa.toPlainString()}E$exponent")
        }
        if (safe.compareTo(decimal("-120")) < 0) return ZERO
        val decimalExponent = safe.divide(LN10, MC).setScale(0, RoundingMode.FLOOR).toInt()
        val remainder = subtract(safe, multiply(decimal(decimalExponent), LN10))
        return sanitize(expSmall(remainder) * TEN.pow(decimalExponent))
    }

    fun power(base: BigDecimal, exponent: BigDecimal): BigDecimal {
        if (exponent.compareTo(ZERO) == 0) return ONE
        if (exponent.compareTo(ZERO) < 0) return ZERO
        if (base.compareTo(ONE) == 0) return ONE
        if (base.compareTo(ZERO) == 0) return ZERO
        if (base.compareTo(ZERO) < 0) return ZERO
        return exp(multiply(exponent, ln(base)))
    }

    fun toLong(value: BigDecimal, minimum: Long = Long.MIN_VALUE, maximum: Long = Long.MAX_VALUE): Long {
        val safe = sanitize(value)
        val rounded = safe.setScale(0, RoundingMode.HALF_UP)
        val asLong = when {
            rounded.compareTo(decimal(maximum)) > 0 -> maximum
            rounded.compareTo(decimal(minimum)) < 0 -> minimum
            else -> rounded.toLong()
        }
        return asLong.coerceIn(minimum, maximum)
    }

    fun toInt(value: BigDecimal, minimum: Int = Int.MIN_VALUE, maximum: Int = Int.MAX_VALUE): Int =
        toLong(value, minimum.toLong(), maximum.toLong()).toInt()

    fun toFloat(value: BigDecimal, minimum: Float, maximum: Float): Float =
        toDouble(value).toFloat().coerceIn(minimum, maximum)

    fun toDouble(value: BigDecimal, fallback: Double = 0.0): Double {
        val result = sanitize(value).toDouble()
        return if (result.isFinite()) result else fallback
    }

    fun format(value: BigDecimal): String {
        val safe = sanitize(value)
        if (safe.compareTo(ZERO) == 0) return "0"
        val sign = if (safe.signum() < 0) "-" else ""
        val absolute = safe.abs().round(MathContext(9, RoundingMode.HALF_UP))
        val exponent = absolute.precision() - absolute.scale() - 1
        if (exponent < 3) {
            val decimals = if (exponent < 0) 0 else 2
            return sign + absolute.setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        }
        if (exponent < 30) {
            val tier = exponent / 3
            val shortened = absolute.divide(TEN.pow(tier * 3), MathContext(3, RoundingMode.HALF_UP))
            return sign + shortened.stripTrailingZeros().toPlainString() + suffixes[tier.toInt()]
        }
        val mantissa = absolute.movePointLeft(exponent).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return sign + mantissa + "e" + formatExponent(exponent.toLong())
    }

    fun formatStage(stage: Long): String {
        if (stage < 1_000_000) return group(stage)
        if (stage < 1_000_000_000L) return format(decimal(stage).divide(decimal(1_000_000L), MC)) + "M"
        return format(decimal(stage)) + "S"
    }

    private fun expSmall(value: BigDecimal): BigDecimal {
        var term = ONE
        var sum = ONE
        for (index in 1..96) {
            term = divide(multiply(term, value), decimal(index))
            sum = add(sum, term)
            if (term.abs().compareTo(EXP_EPSILON) < 0) break
        }
        return sum
    }

    private fun clampWithoutRecursion(value: BigDecimal): BigDecimal = when {
        value.compareTo(LIMIT) > 0 -> LIMIT
        value.compareTo(LIMIT.negate()) < 0 -> LIMIT.negate()
        else -> value
    }

    private fun formatExponent(value: Long): String {
        if (value < 1_000) return value.toString()
        val high = value / 1_000
        val low = value % 1_000
        return "${formatExponent(high)}.${low.toString().padStart(3, '0')}"
    }

    private fun group(value: Long): String {
        val negative = value < 0
        val digits = kotlin.math.abs(value).toString()
        val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
        return if (negative) "-$grouped" else grouped
    }
}

data class Vec2(var x: Float = 0f, var y: Float = 0f) {
    fun add(other: Vec2): Vec2 = Vec2(x + other.x, y + other.y)
    fun subtract(other: Vec2): Vec2 = Vec2(x - other.x, y - other.y)
    fun addInPlace(other: Vec2): Vec2 {
        x += other.x
        y += other.y
        return this
    }

    fun subtractInPlace(other: Vec2): Vec2 {
        x -= other.x
        y -= other.y
        return this
    }

    fun multiply(value: Float): Vec2 = Vec2(x * value, y * value)
    fun lengthSquared(): Float = x * x + y * y
    fun length(): Float = kotlin.math.sqrt(lengthSquared())
    fun normalized(): Vec2 {
        val magnitude = length()
        return if (magnitude <= 0.0001f) Vec2() else Vec2(x / magnitude, y / magnitude)
    }
    fun limit(maximum: Float): Vec2 {
        val magnitudeSquared = lengthSquared()
        return if (magnitudeSquared <= maximum * maximum) this else normalized().multiply(maximum)
    }
}

class Rng(seed: Long) {
    private var s0: Long
    private var s1: Long
    private var s2: Long
    private var s3: Long

    init {
        var value = seed
        fun splitMix(): Long {
            value += -0x61c8864680b583ebL
            var z = value
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }
        s0 = splitMix()
        s1 = splitMix()
        s2 = splitMix()
        s3 = splitMix()
    }

    fun nextLong(): Long {
        val result = java.lang.Long.rotateLeft(s1 * 5L, 7) * 9L
        val temporary = s1 shl 17
        s2 = s2 xor s0
        s3 = s3 xor s1
        s1 = s1 xor s2
        s0 = s0 xor s3
        s2 = s2 xor temporary
        s3 = java.lang.Long.rotateLeft(s3, 45)
        return result
    }

    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() * (1.0 / 9007199254740992.0)
    fun nextFloat(): Float = nextDouble().toFloat()
    fun nextBoolean(): Boolean = (nextLong() and 1L) != 0L

    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        val value = bound.toLong()
        val limit = Long.MAX_VALUE - Long.MAX_VALUE % value
        while (true) {
            val candidate = nextLong() ushr 1
            if (candidate < limit) return (candidate % value).toInt()
        }
    }

    fun nextLong(bound: Long): Long {
        require(bound > 0) { "bound must be positive" }
        val limit = Long.MAX_VALUE - Long.MAX_VALUE % bound
        while (true) {
            val candidate = nextLong() ushr 1
            if (candidate < limit) return candidate % bound
        }
    }

    fun nextRange(minimum: Int, maximum: Int): Int {
        require(maximum >= minimum) { "maximum must be at least minimum" }
        return minimum + nextInt(maximum - minimum + 1)
    }

    fun pick(values: List<String>): String = values[nextInt(values.size)]
    fun state(): LongArray = longArrayOf(s0, s1, s2, s3)

    fun restore(state: LongArray) {
        require(state.size == 4) { "invalid RNG state" }
        s0 = state[0]
        s1 = state[1]
        s2 = state[2]
        s3 = state[3]
        if ((s0 or s1 or s2 or s3) == 0L) s0 = -0x61c8864680b583ebL
    }

    fun fork(salt: Long): Rng {
        val derived = nextLong() xor salt
        return Rng(derived)
    }
}
