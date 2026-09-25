package com.elthon.infinite.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.elthon.infinite.core.Rarity
import kotlin.math.max
import kotlin.math.min

object Ink {
    const val VOID = 0xFF05060E.toInt()
    const val VOID_SOFT = 0xFF0B0E1E.toInt()
    const val PANEL = 0xFF131A31.toInt()
    const val PANEL_LIGHT = 0xFF1C2647.toInt()
    const val LINE = 0xFF2C3A66.toInt()
    const val TEXT = 0xFFF2F6FF.toInt()
    const val TEXT_DIM = 0xFF9AA8C9.toInt()
    const val CYAN = 0xFF55F2D2.toInt()
    const val MAGENTA = 0xFFFF4D8D.toInt()
    const val GOLD = 0xFFFFD166.toInt()
    const val VIOLET = 0xFFB46CFF.toInt()
    const LIME = 0xFF61F2A8.toInt()
    const val DANGER = 0xFFFF3864.toInt()
    const val ARENA = 0xFF0A0E1C.toInt()
    const val ARENA_EDGE = 0xFF16203C.toInt()
}

object Ui {
    const val WIDTH = 720f
    const val HEIGHT = 1280f
    const val MARGIN = 26f

    val display: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val medium: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    val numeric: Typeface = Typeface.create("monospace", Typeface.BOLD)

    fun text(size: Float, color: Int = Ink.TEXT, face: Typeface = medium, align: Paint.Align = Paint.Align.LEFT): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = face
            textAlign = align
        }

    fun fill(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    fun stroke(color: Int, width: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
    }

    fun alpha(color: Int, factor: Float): Int = Color.argb(
        (Color.alpha(color) * factor.coerceIn(0f, 1f)).toInt().coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    fun mix(first: Int, second: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(first) + (Color.alpha(second) - Color.alpha(first)) * t).toInt().coerceIn(0, 255),
            (Color.red(first) + (Color.red(second) - Color.red(first)) * t).toInt().coerceIn(0, 255),
            (Color.green(first) + (Color.green(second) - Color.green(first)) * t).toInt().coerceIn(0, 255),
            (Color.blue(first) + (Color.blue(second) - Color.blue(first)) * t).toInt().coerceIn(0, 255)
        )
    }

    fun panel(canvas: Canvas, rect: RectF, radius: Float, color: Int = Ink.PANEL, border: Int = Ink.LINE) {
        val paint = fill(color)
        canvas.drawRoundRect(rect, radius, radius, paint)
        canvas.drawRoundRect(rect, radius, radius, stroke(border, 2f))
    }

    fun gradientPanel(canvas: Canvas, rect: RectF, radius: Float, top: Int, bottom: Int, border: Int = Ink.LINE) {
        val paint = fill(top)
        paint.shader = LinearGradient(rect.left, rect.top, rect.left, rect.bottom, top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null
        canvas.drawRoundRect(rect, radius, radius, stroke(border, 2f))
    }

    fun glow(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        val paint = fill(color)
        paint.shader = RadialGradient(x, y, max(radius, 1f), alpha(color, 0.55f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, radius, paint)
    }

    fun bar(canvas: Canvas, rect: RectF, fraction: Float, color: Int, background: Int = Ink.PANEL_LIGHT) {
        val radius = rect.height() / 2f
        canvas.drawRoundRect(rect, radius, radius, fill(background))
        val clipped = min(1f, max(0f, fraction))
        if (clipped > 0f) {
            val filled = RectF(rect.left, rect.top, rect.left + rect.width() * clipped, rect.bottom)
            canvas.drawRoundRect(filled, radius, radius, fill(color))
        }
    }

    fun label(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint) {
        canvas.drawText(text.uppercase(), x, y, paint)
    }

    fun centered(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint) {
        canvas.drawText(text, x, y, paint)
    }

    fun wrap(text: String, maxCharacters: Int): List<String> {
        if (text.isEmpty()) return listOf("")
        val words = text.split(' ')
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        words.forEach { word ->
            if (current.isEmpty()) {
                current.append(word)
            } else if (current.length + 1 + word.length <= maxCharacters) {
                current.append(' ').append(word)
            } else {
                lines.add(current.toString())
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }

    fun paragraph(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint, lineHeight: Float, maxCharacters: Int) {
        wrap(text, maxCharacters).forEachIndexed { index, line ->
            canvas.drawText(line, x, y + index * lineHeight, paint)
        }
    }

    fun paragraphHeight(text: String, lineHeight: Float, maxCharacters: Int): Float = wrap(text, maxCharacters).size * lineHeight

    fun polygon(canvas: Canvas, cx: Float, cy: Float, radius: Float, sides: Int, rotation: Float, color: Int, outline: Int? = null) {
        val path = Path()
        for (index in 0 until sides) {
            val angle = rotation + index * (2.0 * Math.PI / sides)
            val x = cx + (kotlin.math.cos(angle) * radius).toFloat()
            val y = cy + (kotlin.math.sin(angle) * radius).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, fill(color))
        outline?.let { canvas.drawPath(path, stroke(it, 3f)) }
    }

    fun star(canvas: Canvas, cx: Float, cy: Float, outer: Float, inner: Float, points: Int, rotation: Float, color: Int) {
        val path = Path()
        val total = points * 2
        for (index in 0 until total) {
            val radius = if (index % 2 == 0) outer else inner
            val angle = rotation + index * (Math.PI / points)
            val x = cx + (kotlin.math.cos(angle) * radius).toFloat()
            val y = cy + (kotlin.math.sin(angle) * radius).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        canvas.drawPath(path, fill(color))
    }

    fun rarityColor(rarity: Rarity): Int = when (rarity) {
        Rarity.COMMON -> 0xFFB6C2DC.toInt()
        Rarity.UNCOMMON -> 0xFF61F2A8.toInt()
        Rarity.RARE -> 0xFF55B8FF.toInt()
        Rarity.EPIC -> 0xFFB46CFF.toInt()
        Rarity.LEGENDARY -> 0xFFFFD166.toInt()
        Rarity.MYTHIC -> 0xFFFF7849.toInt()
        Rarity.ANOMALOUS -> 0xFFFFFFFF.toInt()
    }
}
