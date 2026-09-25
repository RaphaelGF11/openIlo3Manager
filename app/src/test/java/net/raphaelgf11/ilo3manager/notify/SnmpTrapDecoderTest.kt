package net.raphaelgf11.ilo3manager.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decoder reads datagrams that arrive unsolicited, from whatever can reach the listening port.
 * Half these tests are about what it does with bytes that are not a trap — that is the half that
 * runs in a background service on someone's phone.
 *
 * The well-formed vectors were built from the RFC structure by a separate encoder, written in
 * another language and in the opposite direction, so a misreading here would have to be matched by
 * the same misreading there to pass.
 */
class SnmpTrapDecoderTest {

    private fun bytes(hex: String) = ByteArray(hex.length / 2) {
        hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    /** An HP-shaped v1 trap: enterprise 1.3.6.1.4.1.232 is Compaq/HP, as an iLO sends. */
    private val iloV1 = bytes(
        "30818102010004067075626c6963a474060a2b0601040181680902094004c0a801e60201060202232b" +
            "4304075bcd1530533017060c2b0601040181680b020b01000407646c333830673730220" +
            "60c2b060104018168090209010004125465737420747261702066726f6d20694c4f30140608" +
            "2b060102010105000408494c4f2d35542d48",
    )

    private val coldStartV1 = bytes(
        "302702010004067075626c6963a41a06072b0601040181684004c0a801e602010002010043012a3000",
    )

    private val iloV2c = bytes(
        "306a02010104067075626c6963a75d020204d20201000201003051300f06082b060102010103004303" +
            "0f12063018060a2b060106030101040100060a2b06010401816800c62b3024060c2b0601040181" +
            "680902090100041456656e74696c617465757220656e2070616e6e65",
    )

    @Test
    fun `a version 1 trap is read field by field`() {
        val trap = decodeSnmpTrap(iloV1)!!
        assertEquals(0, trap.version)
        assertEquals("public", trap.community)
        assertEquals("1.3.6.1.4.1.232.9.2.9", trap.enterprise)
        assertEquals("192.168.1.230", trap.agentAddress)
        assertEquals(6, trap.genericTrap)
        assertEquals(9003, trap.specificTrap)
        assertEquals(123456789L, trap.uptimeTicks)
    }

    @Test
    fun `the variable bindings come back in order, with their text`() {
        val trap = decodeSnmpTrap(iloV1)!!
        assertEquals(3, trap.varbinds.size)
        assertEquals(VarBind("1.3.6.1.4.1.232.11.2.11.1.0", "dl380g7"), trap.varbinds[0])
        assertEquals("Test trap from iLO", trap.varbinds[1].value)
        assertEquals("1.3.6.1.2.1.1.5.0", trap.varbinds[2].oid)
    }

    @Test
    fun `a trap with no bindings at all is still a trap`() {
        val trap = decodeSnmpTrap(coldStartV1)!!
        assertEquals(0, trap.genericTrap)
        assertEquals("Démarrage à froid", GENERIC_TRAP_LABELS[trap.genericTrap])
        assertTrue(trap.varbinds.isEmpty())
    }

    @Test
    fun `a version 2c trap puts its identity in a binding, and it is found there`() {
        val trap = decodeSnmpTrap(iloV2c)!!
        assertEquals(1, trap.version)
        assertTrue(trap.isV2c)
        // The caller should not have to know which version put the identity where.
        assertEquals("1.3.6.1.4.1.232.0.9003", trap.trapOid)
        assertEquals(987654L, trap.uptimeTicks)
        assertEquals("Ventilateur en panne", trap.varbinds.last().value)
    }

    @Test
    fun `a version 1 trap reports its enterprise as its identity`() {
        assertEquals("1.3.6.1.4.1.232.9.2.9", decodeSnmpTrap(iloV1)!!.trapOid)
    }

    @Test
    fun `anything that is not a trap is refused rather than guessed at`() {
        assertNull(decodeSnmpTrap(ByteArray(0)))
        assertNull(decodeSnmpTrap(bytes("00")))
        assertNull(decodeSnmpTrap(bytes("deadbeef")))
        // A well-formed SNMP message that is a GetRequest, not a trap.
        assertNull(decodeSnmpTrap(bytes("300902010004027075a000")))
    }

    @Test
    fun `a truncated trap does not throw, whatever byte it is cut at`() {
        // The service reading these runs in the background; an exception here is a crash on
        // someone's phone, triggered by a packet anyone on the network could send.
        for (cut in 1 until iloV1.size) {
            val partial = iloV1.copyOf(cut)
            runCatching { decodeSnmpTrap(partial) }
                .onFailure { error("coupé à $cut octets : ${it::class.simpleName}") }
        }
    }

    @Test
    fun `a length claiming more than the datagram holds is refused`() {
        // The first thing a hostile packet would carry.
        val lying = iloV1.copyOf()
        lying[1] = 0x7F  // message length far beyond what follows
        assertNull(decodeSnmpTrap(lying))
    }

    @Test
    fun `a run of continuation bytes in an OID cannot overflow the accumulator`() {
        // Every byte with the high bit set continues the arc; eighty of them would shift a
        // sixty-four bit accumulator into nonsense without the guard.
        val hostile = bytes("3010020100040100a4090681" + "ff".repeat(3) + "00")
        runCatching { decodeSnmpTrap(hostile) }
            .onFailure { error("débordement non gardé : ${it::class.simpleName}") }
    }

    @Test
    fun `a datagram beyond any plausible trap is dropped without reading it`() {
        val huge = ByteArray(9000)
        huge[0] = 0x30
        assertNull(decodeSnmpTrap(huge))
    }

    @Test
    fun `the declared length is honoured over the buffer's own size`() {
        // The listener reuses one buffer across datagrams, so the tail holds the previous packet.
        val padded = iloV1.copyOf(iloV1.size + 200)
        val trap = decodeSnmpTrap(padded, iloV1.size)!!
        assertEquals(9003, trap.specificTrap)
        // And a length past the end of the array is refused rather than read.
        assertNull(decodeSnmpTrap(iloV1, iloV1.size + 1))
    }

    @Test
    fun `version 3 is refused, since nothing here could decrypt it`() {
        val v3 = iloV1.copyOf()
        v3[4] = 0x03
        assertNull(decodeSnmpTrap(v3))
    }

    @Test
    fun `a described trap shows what the iLO said, not its numbers`() {
        // Without a MIB the numbers mean nothing to the reader; the iLO's own string is the only
        // thing in the packet that does.
        val summary = describeTrap(decodeSnmpTrap(iloV1)!!, "DL380")
        assertEquals("DL380 : alerte matérielle", summary.title)
        assertTrue(summary.detail.startsWith("Test trap from iLO"))
        assertTrue(summary.detail.contains("192.168.1.230"))
    }

    @Test
    fun `a trap with nothing to say falls back to its standard meaning`() {
        val summary = describeTrap(decodeSnmpTrap(coldStartV1)!!, "DL380")
        assertEquals("Démarrage à froid", summary.detail.substringBefore(" —"))
    }

    @Test
    fun `a vendor trap with no text shows its numbers rather than inventing a meaning`() {
        val bare = decodeSnmpTrap(coldStartV1)!!.copy(genericTrap = 6, specificTrap = 9003)
        val summary = describeTrap(bare, "")
        assertEquals("Alerte matérielle", summary.title)
        assertTrue(summary.detail.contains("9003"))
    }
}
