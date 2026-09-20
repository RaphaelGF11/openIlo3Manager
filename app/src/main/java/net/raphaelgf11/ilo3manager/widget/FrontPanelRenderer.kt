package net.raphaelgf11.ilo3manager.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/**
 * Draws the DL380 G7 Systems Insight Display.
 *
 * The geometry is traced from the real panel: the silkscreened label on the left carrying the NIC,
 * power supply, DIMM, processor and fan matrix, and the moulded column on the right with the UID
 * button, the health LED and the power button.
 *
 * Everything is laid out in a fixed [WIDTH] × [HEIGHT] space and scaled on the way out, so callers
 * can ask for whatever pixel size their surface needs without the proportions drifting.
 */
object FrontPanelRenderer {

    const val WIDTH = 1080f
    const val HEIGHT = 950f
    const val ASPECT = WIDTH / HEIGHT

    private const val GREEN = 0xFF3BE03F.toInt()
    private const val AMBER = 0xFFFF9A15.toInt()
    private const val RED = 0xFFFF2A20.toInt()
    private const val UID_BLUE = 0xFF4FA8FF.toInt()

    private const val SILKSCREEN = 0xFFB6BAC2.toInt()
    private const val PANEL_DARK = 0xFF121418.toInt()
    private const val PANEL_LIGHT = 0xFF20242A.toInt()
    private const val LABEL_FACE = 0xFF1A1D22.toInt()
    private const val LED_WELL = 0xFF23262C.toInt()

    fun render(state: PanelState, widthPx: Int): Bitmap {
        val heightPx = (widthPx / ASPECT).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(widthPx / WIDTH, heightPx / HEIGHT)
        draw(canvas, state)
        return bitmap
    }

    private fun draw(canvas: Canvas, state: PanelState) {
        drawChassis(canvas)
        drawLabel(canvas)
        drawNicRow(canvas, state)
        drawPowerSupplies(canvas, state)
        drawOverTempAndCap(canvas, state)
        drawDimms(canvas, state)
        drawProcessorsAndAmp(canvas, state)
        drawFans(canvas, state)
        drawRightColumn(canvas, state)
        drawAmbient(canvas, state)
    }

    /**
     * A restrained spill of light around the two buttons.
     *
     * On the real chassis the power button is bright enough to light the whole label — which is why
     * every dot in the matrix looks amber on a standby photograph even though those indicators are
     * off. Reproducing that faithfully would defeat the widget: the fault LEDs are the thing worth
     * seeing at a glance, and a floodlit panel hides them. The spill is therefore kept local, just
     * enough to read as a lamp behind plastic.
     */
    private fun drawAmbient(canvas: Canvas, state: PanelState) {
        when (state.power) {
            PowerLed.ON -> drawWash(canvas, 950f, 612f, 300f, GREEN, 0x16)
            PowerLed.OFF -> drawWash(canvas, 950f, 612f, 300f, AMBER, 0x18)
            PowerLed.UNREACHABLE -> Unit
        }
        if (state.uid) drawWash(canvas, 950f, 135f, 260f, UID_BLUE, 0x1C)
    }

    private fun drawWash(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int, alpha: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(withAlpha(color, alpha), withAlpha(color, alpha / 3), withAlpha(color, 0)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, paint)
    }

    // ---------------------------------------------------------------- chassis

    private fun drawChassis(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, 0f, HEIGHT,
            intArrayOf(PANEL_LIGHT, PANEL_DARK, 0xFF0C0E11.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, WIDTH, HEIGHT, paint)

        // The moulded column carrying the buttons sits slightly proud of the label recess; a seam
        // and a highlight are enough to read as a change of surface.
        val seam = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x30FFFFFF
            strokeWidth = 2f
        }
        canvas.drawLine(838f, 0f, 838f, HEIGHT, seam)
        seam.color = 0x50000000
        canvas.drawLine(842f, 0f, 842f, HEIGHT, seam)

        drawInfoIcon(canvas, 40f, 242f)
        drawPullTab(canvas)
    }

    /** The recessed pull-out tab that holds the serial-number card, left of the label. */
    private fun drawPullTab(canvas: Canvas) {
        val rect = RectF(56f, 290f, 82f, 760f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF08090B.toInt() }
        canvas.drawRoundRect(rect, 13f, 13f, fill)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0x25FFFFFF
        }
        canvas.drawRoundRect(rect, 13f, 13f, rim)
    }

    private fun drawInfoIcon(canvas: Canvas, cx: Float, cy: Float) {
        val stroke = strokePaint(1.8f)
        canvas.drawCircle(cx, cy, 15f, stroke)
        val text = textPaint(20f, bold = true)
        canvas.drawText("i", cx, cy + 7f, text)
    }

    // ------------------------------------------------------------------ label

    private val labelRect = RectF(105f, 70f, 815f, 890f)

    private fun drawLabel(canvas: Canvas) {
        val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LABEL_FACE }
        canvas.drawRoundRect(labelRect, 8f, 8f, face)

        // A faint diagonal sheen: the real label is glossy polycarbonate and never reads as a flat
        // black rectangle, which is most of what makes a rendering look synthetic.
        val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
        sheen.shader = LinearGradient(
            labelRect.left, labelRect.top, labelRect.right, labelRect.bottom,
            intArrayOf(0x14FFFFFF, 0x00FFFFFF, 0x0AFFFFFF),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(labelRect, 8f, 8f, sheen)

        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f
            color = 0x22FFFFFF
        }
        canvas.drawRoundRect(labelRect, 8f, 8f, edge)
    }

    // -------------------------------------------------------------------- NIC

    private val nicX = floatArrayOf(227f, 382f, 545f, 707f)

    private fun drawNicRow(canvas: Canvas, state: PanelState) {
        val y = 120f
        nicX.forEachIndexed { index, x ->
            drawNicGlyph(canvas, x - 60f, y)
            drawLed(canvas, x, y, 13f, state.nics.getOrElse(index) { Led.OFF })
            canvas.drawText("${index + 1}", x + 30f, y + 9f, textPaint(24f))
        }
    }

    /** The little network glyph: a trunk with three drops, as silkscreened. */
    private fun drawNicGlyph(canvas: Canvas, cx: Float, cy: Float) {
        val stroke = strokePaint(2.2f)
        canvas.drawLine(cx - 22f, cy, cx + 22f, cy, stroke)
        canvas.drawLine(cx, cy, cx, cy - 12f, stroke)
        val box = 7f
        for (dx in floatArrayOf(-22f, 0f, 22f)) {
            canvas.drawRect(cx + dx - box, cy + 4f, cx + dx + box, cy + 14f, stroke)
        }
        canvas.drawLine(cx - 22f, cy, cx - 22f, cy + 4f, stroke)
        canvas.drawLine(cx + 22f, cy, cx + 22f, cy + 4f, stroke)
    }

    // ---------------------------------------------------------- power supplies

    private fun drawPowerSupplies(canvas: Canvas, state: PanelState) {
        drawPsuBox(canvas, RectF(140f, 175f, 285f, 320f), 1, state.psus.getOrElse(0) { Led.OFF })
        drawPsuBox(canvas, RectF(300f, 175f, 445f, 320f), 2, state.psus.getOrElse(1) { Led.OFF })
    }

    private fun drawPsuBox(canvas: Canvas, rect: RectF, index: Int, led: Led) {
        canvas.drawRect(rect, strokePaint(2f))
        // Power-supply pictogram: a circle with a plug stem inside a square.
        val gx = rect.left + 32f
        val gy = rect.top + 37f
        canvas.drawRect(gx - 17f, gy - 17f, gx + 17f, gy + 17f, strokePaint(2f))
        canvas.drawCircle(gx, gy, 10f, strokePaint(2f))
        canvas.drawLine(gx, gy - 14f, gx, gy - 4f, strokePaint(2f))

        drawLed(canvas, rect.left + 78f, gy, 13f, led)
        canvas.drawText("$index", rect.left + 108f, gy + 9f, textPaint(24f))
        canvas.drawText("POWER", rect.centerX(), rect.top + 92f, textPaint(21f))
        canvas.drawText("SUPPLY", rect.centerX(), rect.top + 118f, textPaint(21f))
    }

    // ------------------------------------------------------ over temp / p-cap

    private fun drawOverTempAndCap(canvas: Canvas, state: PanelState) {
        val temp = RectF(530f, 178f, 750f, 245f)
        canvas.drawRoundRect(temp, 33f, 33f, strokePaint(2f))
        canvas.drawText("OVER", temp.left + 68f, temp.top + 28f, textPaint(21f))
        canvas.drawText("TEMP", temp.left + 68f, temp.top + 52f, textPaint(21f))
        drawThermometer(canvas, temp.left + 140f, temp.centerY())
        drawLed(canvas, temp.right - 25f, temp.centerY(), 13f, state.overTemp)

        val cap = RectF(525f, 270f, 750f, 335f)
        canvas.drawRoundRect(cap, 32f, 32f, strokePaint(2f))
        canvas.drawText("POWER", cap.left + 78f, cap.top + 28f, textPaint(21f))
        canvas.drawText("CAP", cap.left + 78f, cap.top + 52f, textPaint(21f))
        drawLed(canvas, cap.right - 28f, cap.centerY(), 13f, state.powerCap)
    }

    private fun drawThermometer(canvas: Canvas, cx: Float, cy: Float) {
        val stroke = strokePaint(2f)
        canvas.drawRoundRect(RectF(cx - 5f, cy - 22f, cx + 5f, cy + 10f), 5f, 5f, stroke)
        canvas.drawCircle(cx, cy + 14f, 8f, stroke)
    }

    // ------------------------------------------------------------------ DIMMs

    private const val DIMM_PITCH = 62f
    private const val DIMM_BAR_W = 15f
    private const val DIMM_BAR_H = 96f

    private fun drawDimms(canvas: Canvas, state: PanelState) {
        canvas.drawText("DIMMS", 460f, 392f, textPaint(24f))
        // Left bank counts outward (9 7 5 3 1 over 8 6 4 2); the right bank mirrors it.
        drawDimmBank(canvas, 166f, state.dimmsLeft, mirrored = false)
        drawDimmBank(canvas, 498f, state.dimmsRight, mirrored = true)
    }

    private fun drawDimmBank(canvas: Canvas, originX: Float, leds: List<Led>, mirrored: Boolean) {
        val topLabels = if (mirrored) intArrayOf(1, 3, 5, 7, 9) else intArrayOf(9, 7, 5, 3, 1)
        val bottomLabels = if (mirrored) intArrayOf(2, 4, 6, 8) else intArrayOf(8, 6, 4, 2)

        topLabels.forEachIndexed { i, slot ->
            val x = originX + i * DIMM_PITCH
            canvas.drawText("$slot", x, 438f, textPaint(24f))
            drawDimmSlot(canvas, x, 458f, leds.getOrElse(slot - 1) { Led.OFF })
        }
        bottomLabels.forEachIndexed { i, slot ->
            val x = originX + DIMM_PITCH / 2f + i * DIMM_PITCH
            drawDimmSlot(canvas, x, 516f, leds.getOrElse(slot - 1) { Led.OFF })
            canvas.drawText("$slot", x, 640f, textPaint(24f))
        }
    }

    /**
     * One memory slot: a tall narrow module outline with its indicator at the top.
     *
     * The two rows overlap vertically, which is what turns eighteen separate outlines into the
     * dense comb the silkscreen actually shows.
     */
    private fun drawDimmSlot(canvas: Canvas, cx: Float, top: Float, led: Led) {
        val stroke = strokePaint(1.8f)
        canvas.drawRect(
            cx - DIMM_BAR_W / 2f, top + 16f,
            cx + DIMM_BAR_W / 2f, top + DIMM_BAR_H,
            stroke,
        )
        drawLed(canvas, cx, top + 14f, 10f, led)
    }

    // ------------------------------------------------------- processors / AMP

    private fun drawProcessorsAndAmp(canvas: Canvas, state: PanelState) {
        drawProcBox(canvas, RectF(180f, 655f, 320f, 765f), 2, state.procs.getOrElse(1) { Led.OFF })
        drawProcBox(canvas, RectF(615f, 655f, 755f, 765f), 1, state.procs.getOrElse(0) { Led.OFF })

        val amp = RectF(360f, 650f, 570f, 715f)
        canvas.drawRoundRect(amp, 32f, 32f, strokePaint(2f))
        canvas.drawText("AMP", amp.left + 78f, amp.top + 28f, textPaint(21f))
        canvas.drawText("STATUS", amp.left + 78f, amp.top + 52f, textPaint(21f))
        drawLed(canvas, amp.right - 30f, amp.centerY(), 13f, state.ampStatus)
    }

    private fun drawProcBox(canvas: Canvas, rect: RectF, index: Int, led: Led) {
        canvas.drawRect(rect, strokePaint(2f))
        val gx = rect.left + 30f
        val gy = rect.top + 30f
        canvas.drawRect(gx - 16f, gy - 16f, gx + 16f, gy + 16f, strokePaint(2f))
        canvas.drawRect(gx - 7f, gy - 7f, gx + 7f, gy + 7f, strokePaint(2f))
        drawLed(canvas, rect.left + 72f, gy, 13f, led)
        canvas.drawText("$index", rect.left + 102f, gy + 9f, textPaint(24f))
        canvas.drawText("PROC", rect.centerX(), rect.bottom - 12f, textPaint(21f))
    }

    // ------------------------------------------------------------------- fans

    private fun drawFans(canvas: Canvas, state: PanelState) {
        canvas.drawText("FANS", 470f, 808f, textPaint(24f))
        val top = 830f
        val bottom = 880f
        val left = 150f
        val cell = 107.5f
        // Silkscreened right to left: the leftmost cell is FAN 6.
        for (i in 0 until 6) {
            val x0 = left + i * cell
            canvas.drawRect(x0, top, x0 + cell, bottom, strokePaint(1.8f))
            val fanNumber = 6 - i
            drawFanGlyph(canvas, x0 + 22f, (top + bottom) / 2f)
            drawLed(canvas, x0 + 52f, (top + bottom) / 2f, 11f, state.fans.getOrElse(fanNumber - 1) { Led.OFF })
            canvas.drawText("$fanNumber", x0 + 84f, (top + bottom) / 2f + 9f, textPaint(24f))
        }
    }

    private fun drawFanGlyph(canvas: Canvas, cx: Float, cy: Float) {
        val stroke = strokePaint(1.8f)
        canvas.drawCircle(cx, cy, 4f, stroke)
        for (i in 0 until 4) {
            val angle = Math.toRadians((i * 90 + 45).toDouble())
            val dx = (Math.cos(angle) * 11f).toFloat()
            val dy = (Math.sin(angle) * 11f).toFloat()
            canvas.drawCircle(cx + dx, cy + dy, 5.5f, stroke)
        }
    }

    // ----------------------------------------------------------- right column

    private fun drawRightColumn(canvas: Canvas, state: PanelState) {
        canvas.drawText("UID", 950f, 46f, textPaint(26f))
        drawUidButton(canvas, 950f, 135f, state.uid)

        drawHeartbeatGlyph(canvas, 950f, 292f)
        // The only indicator that can be red, and the only one that is green in normal operation.
        drawLamp(
            canvas, 950f, 358f, 20f,
            when (state.health) {
                HealthLed.OFF -> null
                HealthLed.GREEN -> GREEN
                HealthLed.AMBER -> AMBER
                HealthLed.RED -> RED
            },
        )

        drawPowerButton(canvas, 950f, 612f, state.power)
    }

    /** The heartbeat trace silkscreened above the health LED. */
    private fun drawHeartbeatGlyph(canvas: Canvas, cx: Float, cy: Float) {
        val stroke = strokePaint(2.4f).apply { style = Paint.Style.STROKE }
        val path = Path().apply {
            moveTo(cx - 30f, cy)
            lineTo(cx - 14f, cy)
            lineTo(cx - 8f, cy - 14f)
            lineTo(cx, cy + 14f)
            lineTo(cx + 7f, cy - 6f)
            lineTo(cx + 13f, cy)
            lineTo(cx + 30f, cy)
        }
        canvas.drawPath(path, stroke)
    }

    private fun drawUidButton(canvas: Canvas, cx: Float, cy: Float, on: Boolean) {
        if (on) drawGlow(canvas, cx, cy, 45f * 1.7f, UID_BLUE, 0x62)

        val body = Paint(Paint.ANTI_ALIAS_FLAG)
        body.shader = RadialGradient(
            cx - 14f, cy - 16f, 62f,
            intArrayOf(0xFF6E757F.toInt(), 0xFF3A3F46.toInt(), 0xFF23262B.toInt()),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, 45f, body)

        if (on) {
            val lit = Paint(Paint.ANTI_ALIAS_FLAG)
            lit.shader = RadialGradient(
                cx, cy, 45f,
                intArrayOf(Color.WHITE, 0xFFBFE2FF.toInt(), UID_BLUE),
                floatArrayOf(0f, 0.38f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, 41f, lit)
        } else {
            // The unlit cap is a moulded translucent dome with a cross-hair moulding.
            val mould = strokePaint(2f).apply { color = 0x33FFFFFF }
            canvas.drawLine(cx - 20f, cy, cx + 20f, cy, mould)
            canvas.drawLine(cx, cy - 20f, cx, cy + 20f, mould)
        }

        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = 0x60000000
        }
        canvas.drawCircle(cx, cy, 45f, rim)
    }

    private fun drawPowerButton(canvas: Canvas, cx: Float, cy: Float, power: PowerLed) {
        val lamp = when (power) {
            PowerLed.ON -> GREEN
            PowerLed.OFF -> AMBER
            PowerLed.UNREACHABLE -> null
        }
        if (lamp != null) drawGlow(canvas, cx, cy, 124f, lamp, 0x58)

        // Bezel.
        val bezel = Paint(Paint.ANTI_ALIAS_FLAG)
        bezel.shader = RadialGradient(
            cx - 25f, cy - 30f, 130f,
            intArrayOf(0xFF4A4F57.toInt(), 0xFF2A2E34.toInt(), 0xFF15181C.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, 95f, bezel)

        val well = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF191C21.toInt() }
        canvas.drawCircle(cx, cy, 74f, well)

        // The lit annulus sits just inside the cap, with the glyph floating in the middle.
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 9f
            this.color = lamp ?: 0xFF32363C.toInt()
        }
        canvas.drawCircle(cx, cy, 58f, ring)

        drawPowerGlyph(canvas, cx, cy, if (lamp != null) Color.WHITE else 0xFF3A3F45.toInt())

        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = 0x70000000
        }
        canvas.drawCircle(cx, cy, 95f, rim)
    }

    private fun drawPowerGlyph(canvas: Canvas, cx: Float, cy: Float, tint: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 9f
            strokeCap = Paint.Cap.ROUND
            color = tint
        }
        val r = 30f
        canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -60f, 300f, false, paint)
        canvas.drawLine(cx, cy - r - 6f, cx, cy + 2f, paint)
    }

    // ------------------------------------------------------------------- LEDs

    private fun drawLed(canvas: Canvas, cx: Float, cy: Float, radius: Float, led: Led) {
        drawLamp(
            canvas, cx, cy, radius,
            when (led) {
                Led.OFF -> null
                Led.AMBER -> AMBER
            },
        )
    }

    /** One indicator, lit in [color] or dark when it is null. */
    private fun drawLamp(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int?) {
        if (color == null) {
            val well = Paint(Paint.ANTI_ALIAS_FLAG)
            well.shader = RadialGradient(
                cx - radius * 0.3f, cy - radius * 0.35f, radius * 1.6f,
                intArrayOf(0xFF31353C.toInt(), LED_WELL, 0xFF15171B.toInt()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, radius, well)
            val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.4f
                this.color = 0x33FFFFFF
            }
            canvas.drawCircle(cx, cy, radius, rim)
            return
        }

        drawGlow(canvas, cx, cy, radius * 3.6f, color, 0x9E)

        val body = Paint(Paint.ANTI_ALIAS_FLAG)
        body.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(blend(color, Color.WHITE, 0.75f), color, blend(color, Color.BLACK, 0.25f)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, body)
    }

    /** The bloom a real LED throws onto the surrounding plastic. */
    private fun drawGlow(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int, alpha: Int) {
        val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        glow.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(
                withAlpha(color, alpha),
                withAlpha(color, alpha / 4),
                withAlpha(color, 0),
            ),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, glow)
    }

    // ----------------------------------------------------------------- paints

    private fun strokePaint(width: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        color = withAlpha(SILKSCREEN, 0xD0)
    }

    private fun textPaint(size: Float, bold: Boolean = false): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(SILKSCREEN, 0xDD)
        textSize = size
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    private fun blend(from: Int, to: Int, amount: Float): Int = Color.rgb(
        (Color.red(from) + (Color.red(to) - Color.red(from)) * amount).toInt(),
        (Color.green(from) + (Color.green(to) - Color.green(from)) * amount).toInt(),
        (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount).toInt(),
    )
}
