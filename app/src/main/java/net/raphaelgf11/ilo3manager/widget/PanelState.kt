package net.raphaelgf11.ilo3manager.widget

/**
 * What a single indicator on the Systems Insight Display is doing.
 *
 * On the real panel an unlit indicator means "no fault", not "unknown" — the display only lights up
 * to point at something. [OFF] therefore covers both the healthy case and the case where no sensor
 * exists for that position, which is why nothing here distinguishes them.
 *
 * Per-component indicators are amber only. Red belongs to the overall health LED alone, which is
 * why there is no RED here.
 */
enum class Led { OFF, AMBER }

/**
 * The network port indicators, which report link rather than failure: green when the port is up,
 * dark otherwise. Kept apart from [Led] so a fault can never be painted green, nor a link amber.
 */
enum class LinkLed { OFF, GREEN }

/**
 * The ring around the power button.
 *
 * [OFF] is the server powered down but still plugged in — the amber button. [UNREACHABLE] is the
 * only case that leaves the panel dark: no answer at all, whether because the machine is unplugged
 * or because the network is down. Nothing seen from the phone can tell those two apart.
 */
enum class PowerLed { UNREACHABLE, OFF, ON }

/** The health LED next to the heartbeat glyph. Critical faults blink red on the real machine. */
enum class HealthLed { OFF, GREEN, AMBER, RED }

/**
 * A complete reading of the front panel.
 *
 * Both DIMM banks hold nine slots, indexed 0..8 for DIMM 1..9 — the silkscreen orders them
 * 9 7 5 3 1 over 8 6 4 2 on the left and mirrors that on the right, which is a drawing concern
 * rather than something callers should have to reproduce.
 */
data class PanelState(
    val power: PowerLed = PowerLed.UNREACHABLE,
    val health: HealthLed = HealthLed.OFF,
    val uid: Boolean = false,
    val nics: List<LinkLed> = List(4) { LinkLed.OFF },
    val psus: List<Led> = List(2) { Led.OFF },
    val overTemp: Led = Led.OFF,
    val powerCap: Led = Led.OFF,
    /** Left bank, slots 1..9. */
    val dimmsLeft: List<Led> = List(9) { Led.OFF },
    /** Right bank, slots 1..9. */
    val dimmsRight: List<Led> = List(9) { Led.OFF },
    /** Index 0 is PROC 1. */
    val procs: List<Led> = List(2) { Led.OFF },
    val ampStatus: Led = Led.OFF,
    /** Index 0 is FAN 1. */
    val fans: List<Led> = List(6) { Led.OFF },
) {
    companion object {
        /** Everything dark: no answer from the BMC. */
        val DARK = PanelState()
    }
}
