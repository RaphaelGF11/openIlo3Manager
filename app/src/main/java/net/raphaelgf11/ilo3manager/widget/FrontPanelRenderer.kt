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
        canvas.drawCircle(cx, cy, 17f, stroke)
        val text = textPaint(24f, bold = true)
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
            drawNicGlyph(canvas, x - 66f, y)
            // Green, not amber: these report a link, not a fault.
            drawLamp(
                canvas, x, y, 13f,
                if (state.nics.getOrElse(index) { LinkLed.OFF } == LinkLed.GREEN) GREEN else null,
            )
            canvas.drawText("${index + 1}", x + 34f, y + 12f, textPaint(32f))
        }
    }

    /**
     * The network glyph: a bus with a node at its left end, one terminal hanging above the middle
     * and two below, the way the silkscreen draws a small LAN.
     */
    private fun drawNicGlyph(canvas: Canvas, cx: Float, cy: Float) {
        val stroke = strokePaint(2.4f)
        canvas.drawLine(cx - 26f, cy, cx + 26f, cy, stroke)

        // Node and terminals are solid blocks on the real label, not outlines.
        val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(SILKSCREEN, 0xD0) }
        canvas.drawCircle(cx - 26f, cy, 5f, solid)

        // Terminal above, centred.
        canvas.drawLine(cx, cy, cx, cy - 10f, stroke)
        canvas.drawRect(cx - 9f, cy - 24f, cx + 9f, cy - 10f, solid)
        // Two terminals below, either side.
        for (dx in floatArrayOf(-15f, 17f)) {
            canvas.drawLine(cx + dx, cy, cx + dx, cy + 10f, stroke)
            canvas.drawRect(cx + dx - 9f, cy + 10f, cx + dx + 9f, cy + 24f, solid)
        }
    }

    // ---------------------------------------------------------- power supplies

    private fun drawPowerSupplies(canvas: Canvas, state: PanelState) {
        drawPsuBox(canvas, RectF(140f, 175f, 285f, 320f), 1, state.psus.getOrElse(0) { Led.OFF })
        drawPsuBox(canvas, RectF(300f, 175f, 445f, 320f), 2, state.psus.getOrElse(1) { Led.OFF })
    }

    private fun drawPsuBox(canvas: Canvas, rect: RectF, index: Int, led: Led) {
        canvas.drawRect(rect, strokePaint(2f))
        drawPsuGlyph(canvas, rect.left + 34f, rect.top + 38f)
        drawLed(canvas, rect.left + 80f, rect.top + 38f, 13f, led)
        canvas.drawText("$index", rect.left + 113f, rect.top + 50f, textPaint(32f))
        canvas.drawText("POWER", rect.centerX(), rect.top + 100f, textPaint(28f))
        canvas.drawText("SUPPLY", rect.centerX(), rect.top + 130f, textPaint(28f))
    }

    /**
     * The power-supply pictogram: a module outline enclosing the mains symbol, with a short lead
     * leaving it on the left.
     */
    private fun drawPsuGlyph(canvas: Canvas, cx: Float, cy: Float) {
        val stroke = strokePaint(2.2f)
        canvas.drawRoundRect(RectF(cx - 18f, cy - 18f, cx + 18f, cy + 18f), 4f, 4f, stroke)
        canvas.drawCircle(cx, cy, 11f, stroke)
        // The stylised sine inside the circle, as on a mains symbol.
        val wave = Path().apply {
            moveTo(cx - 7f, cy + 3f)
            quadTo(cx - 3.5f, cy - 8f, cx, cy)
            quadTo(cx + 3.5f, cy + 8f, cx + 7f, cy - 3f)
        }
        canvas.drawPath(wave, strokePaint(2.2f))
        // Lead leaving the module.
        canvas.drawLine(cx - 18f, cy + 6f, cx - 26f, cy + 6f, stroke)
        canvas.drawLine(cx - 26f, cy + 6f, cx - 26f, cy + 16f, stroke)
    }

    // ------------------------------------------------------ over temp / p-cap

    private fun drawOverTempAndCap(canvas: Canvas, state: PanelState) {
        val temp = RectF(530f, 172f, 755f, 250f)
        canvas.drawRoundRect(temp, 39f, 39f, strokePaint(2f))
        canvas.drawText("OVER", temp.left + 72f, temp.top + 33f, textPaint(28f))
        canvas.drawText("TEMP", temp.left + 72f, temp.top + 63f, textPaint(28f))
        drawThermometer(canvas, temp.left + 148f, temp.centerY())
        drawLed(canvas, temp.right - 28f, temp.centerY(), 13f, state.overTemp)

        val cap = RectF(530f, 266f, 755f, 342f)
        canvas.drawRoundRect(cap, 38f, 38f, strokePaint(2f))
        canvas.drawText("POWER", cap.left + 84f, cap.top + 32f, textPaint(28f))
        canvas.drawText("CAP", cap.left + 84f, cap.top + 62f, textPaint(28f))
        drawLed(canvas, cap.right - 30f, cap.centerY(), 13f, state.powerCap)
    }

    /** A slim thermometer with its bulb and a couple of graduations, as moulded on the label. */
    private fun drawThermometer(canvas: Canvas, cx: Float, cy: Float) {
        val stroke = strokePaint(2f)
        canvas.drawRoundRect(RectF(cx - 5f, cy - 28f, cx + 5f, cy + 8f), 5f, 5f, stroke)
        canvas.drawCircle(cx, cy + 15f, 9f, stroke)
        for (dy in floatArrayOf(-18f, -10f, -2f)) {
            canvas.drawLine(cx + 6f, cy + dy, cx + 12f, cy + dy, stroke)
        }
    }

    // ------------------------------------------------------------------ DIMMs

    private const val DIMM_PITCH = 37.5f
    private const val DIMM_BAR_W = 17f
    private const val DIMM_TOP = 439f
    private const val DIMM_BAR_H = 136f

    private fun drawDimms(canvas: Canvas, state: PanelState) {
        canvas.drawText("DIMMS", 460f, 385f, textPaint(34f))
        // Nine slots per bank, side by side. The left bank counts down from 9, the right counts up
        // from 1, which is what puts slot 1 of each bank either side of the centre line.
        drawDimmBank(canvas, 152f, state.dimmsLeft) { i -> 9 - i }
        drawDimmBank(canvas, 486f, state.dimmsRight) { i -> i + 1 }
    }

    private fun drawDimmBank(canvas: Canvas, originX: Float, leds: List<Led>, slotAt: (Int) -> Int) {
        for (i in 0 until 9) {
            val slot = slotAt(i)
            val x = originX + i * DIMM_PITCH
            val odd = slot % 2 == 1
            drawDimmSlot(canvas, x, leds.getOrElse(slot - 1) { Led.OFF }, odd)
            // Odd slots are numbered above the comb, even ones below — which is also the rule that
            // decides where the indicator sits inside the module.
            if (odd) {
                canvas.drawText("$slot", x, DIMM_TOP - 14f, textPaint(30f))
            } else {
                canvas.drawText("$slot", x, DIMM_TOP + DIMM_BAR_H + 32f, textPaint(30f))
            }
        }
    }

    /**
     * One memory slot.
     *
     * Every module outline is the same height and sits on the same baseline — the comb is perfectly
     * regular. Only the indicator moves: high in the module for an odd slot, low for an even one,
     * which is what makes the dots alternate along the row.
     */
    private fun drawDimmSlot(canvas: Canvas, cx: Float, led: Led, odd: Boolean) {
        val stroke = strokePaint(1.8f)
        val left = cx - DIMM_BAR_W / 2f
        val right = cx + DIMM_BAR_W / 2f
        canvas.drawRect(left, DIMM_TOP, right, DIMM_TOP + DIMM_BAR_H, stroke)
        // The little notch moulded at the top of each slot outline.
        canvas.drawRect(cx - 4f, DIMM_TOP - 6f, cx + 4f, DIMM_TOP, stroke)
        drawLed(canvas, cx, DIMM_TOP + if (odd) 28f else 105f, 9.5f, led)
    }

    // ------------------------------------------------------- processors / AMP

    private fun drawProcessorsAndAmp(canvas: Canvas, state: PanelState) {
        drawProcBox(canvas, RectF(192f, 617f, 336f, 759f), 2, state.procs.getOrElse(1) { Led.OFF })
        drawProcBox(canvas, RectF(625f, 617f, 769f, 759f), 1, state.procs.getOrElse(0) { Led.OFF })

        val amp = RectF(360f, 623f, 599f, 692f)
        canvas.drawRoundRect(amp, 34f, 34f, strokePaint(2f))
        canvas.drawText("AMP", amp.left + 88f, amp.top + 30f, textPaint(28f))
        canvas.drawText("STATUS", amp.left + 88f, amp.top + 59f, textPaint(28f))
        drawLed(canvas, amp.right - 32f, amp.centerY(), 13f, state.ampStatus)
    }

    private fun drawProcBox(canvas: Canvas, rect: RectF, index: Int, led: Led) {
        canvas.drawRect(rect, strokePaint(2f))
        drawChipGlyph(canvas, rect.left + 36f, rect.top + 40f)
        drawLed(canvas, rect.left + 84f, rect.top + 40f, 13f, led)
        canvas.drawText("$index", rect.left + 117f, rect.top + 52f, textPaint(32f))
        canvas.drawText("PROC", rect.centerX(), rect.bottom - 16f, textPaint(28f))
    }

    /** A packaged processor: a body with an exposed die and a row of pins top and bottom. */
    private fun drawChipGlyph(canvas: Canvas, cx: Float, cy: Float) {
        val stroke = strokePaint(2.2f)
        canvas.drawRect(cx - 17f, cy - 17f, cx + 17f, cy + 17f, stroke)
        canvas.drawRect(cx - 8f, cy - 8f, cx + 8f, cy + 8f, stroke)
        for (dx in floatArrayOf(-11f, -3.5f, 4f, 11f)) {
            canvas.drawLine(cx + dx, cy - 17f, cx + dx, cy - 24f, stroke)
            canvas.drawLine(cx + dx, cy + 17f, cx + dx, cy + 24f, stroke)
        }
    }

    // ------------------------------------------------------------------- fans

    private fun drawFans(canvas: Canvas, state: PanelState) {
        canvas.drawText("FANS", 470f, 793f, textPaint(34f))
        val top = 805f
        val bottom = 873f
        val left = 152f
        val cell = 107.7f
        // Silkscreened right to left: the leftmost cell is FAN 6.
        for (i in 0 until 6) {
            val x0 = left + i * cell
            canvas.drawRect(x0, top, x0 + cell, bottom, strokePaint(1.8f))
            val fanNumber = 6 - i
            val cy = (top + bottom) / 2f
            drawFanGlyph(canvas, x0 + 25f, cy)
            drawLed(canvas, x0 + 58f, cy, 12f, state.fans.getOrElse(fanNumber - 1) { Led.OFF })
            canvas.drawText("$fanNumber", x0 + 87f, cy + 11f, textPaint(30f))
        }
    }

    /**
     * Four swept blades around a hub.
     *
     * Drawn as curved petals rather than four discs: discs read as dots at this size, which is
     * precisely what the moulded symbol does not look like.
     */
    private fun drawFanGlyph(canvas: Canvas, cx: Float, cy: Float) {
        val blade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = withAlpha(SILKSCREEN, 0xC8)
        }
        // An impeller, not a clover: each blade is a teardrop, broad at the rim and tapering into
        // the hub, and offset in the direction of rotation. Four symmetric lobes read as a flower.
        val hubR = 3.2f
        val tipR = 11.6f
        for (i in 0 until 4) {
            val a = i * 90f + 45f
            // Leading edge bows outwards, the tip is broad, the trailing edge sweeps back into the
            // hub. A disc on a stalk has none of that, which is why it reads as a dot.
            val path = Path().apply {
                moveTo(polarX(cx, hubR, a - 22f), polarY(cy, hubR, a - 22f))
                quadTo(
                    polarX(cx, tipR * 0.72f, a - 40f), polarY(cy, tipR * 0.72f, a - 40f),
                    polarX(cx, tipR, a - 2f), polarY(cy, tipR, a - 2f),
                )
                quadTo(
                    polarX(cx, tipR * 1.04f, a + 28f), polarY(cy, tipR * 1.04f, a + 28f),
                    polarX(cx, tipR * 0.80f, a + 50f), polarY(cy, tipR * 0.80f, a + 50f),
                )
                quadTo(
                    polarX(cx, tipR * 0.34f, a + 42f), polarY(cy, tipR * 0.34f, a + 42f),
                    polarX(cx, hubR, a + 22f), polarY(cy, hubR, a + 22f),
                )
                close()
            }
            canvas.drawPath(path, blade)
        }
        canvas.drawCircle(cx, cy, hubR, blade)
    }

    private fun polarX(cx: Float, radius: Float, degrees: Float): Float =
        cx + (Math.cos(Math.toRadians(degrees.toDouble())) * radius).toFloat()

    private fun polarY(cy: Float, radius: Float, degrees: Float): Float =
        cy + (Math.sin(Math.toRadians(degrees.toDouble())) * radius).toFloat()

    // ----------------------------------------------------------- right column

    private fun drawRightColumn(canvas: Canvas, state: PanelState) {
        canvas.drawText("UID", 950f, 50f, textPaint(38f))
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
            lineTo(cx + 26f, cy)
        }
        canvas.drawPath(path, stroke)
        // The trace ends on a filled point, which the moulding shows clearly.
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = withAlpha(SILKSCREEN, 0xD0) }
        canvas.drawCircle(cx + 31f, cy, 4.5f, dot)
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
            // Unlit it is a smooth translucent dome; the moulding across it barely catches the
            // light, so it must stay a hint rather than a drawn cross.
            val mould = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.6f
                color = 0x14FFFFFF
            }
            canvas.drawLine(cx - 22f, cy - 4f, cx + 18f, cy + 6f, mould)
            val highlight = Paint(Paint.ANTI_ALIAS_FLAG)
            highlight.shader = RadialGradient(
                cx - 15f, cy - 17f, 30f,
                intArrayOf(0x40FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, 45f, highlight)
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

        // The surrounding plastic, recessed around the cap.
        val bezel = Paint(Paint.ANTI_ALIAS_FLAG)
        bezel.shader = RadialGradient(
            cx - 25f, cy - 30f, 120f,
            intArrayOf(0xFF3E434A.toInt(), 0xFF24282E.toInt(), 0xFF14171B.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, 88f, bezel)

        // What lights up is the gap between the cap and that plastic: a thin bright outline at the
        // very edge of the cap, not a broad band painted across its face.
        val gap = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            this.color = lamp ?: 0xFF101316.toInt()
        }
        canvas.drawCircle(cx, cy, 79f, gap)

        val cap = Paint(Paint.ANTI_ALIAS_FLAG)
        cap.shader = RadialGradient(
            cx - 20f, cy - 26f, 110f,
            intArrayOf(0xFF3A3F46.toInt(), 0xFF262A30.toInt(), 0xFF181B1F.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, 75f, cap)

        // The moulded symbol glows in the lamp's own colour, never white.
        drawPowerGlyph(canvas, cx, cy, lamp ?: 0xFF3A3F45.toInt(), lit = lamp != null)

        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = 0x70000000
        }
        canvas.drawCircle(cx, cy, 88f, rim)
    }

    private fun drawPowerGlyph(canvas: Canvas, cx: Float, cy: Float, tint: Int, lit: Boolean) {
        if (lit) drawGlow(canvas, cx, cy, 70f, tint, 0x40)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 10f
            strokeCap = Paint.Cap.ROUND
            color = tint
        }
        val r = 38f
        canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -66f, 312f, false, paint)
        canvas.drawLine(cx, cy - r - 10f, cx, cy + 4f, paint)
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
