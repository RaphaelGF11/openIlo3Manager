package net.raphaelgf11.ilo3manager.ilo

import org.junit.Assert.assertEquals
import org.junit.Test

class IloCliParserTest {

    @Test
    fun parseDriveBays_parsesRealDl380Output() {
        val raw = """
            show /system1/drives1
            status=0
            status_tag=COMMAND COMPLETED
            Thu Sep 17 20:42:14 2026



            /system1/drives1
              Targets
              Properties
                Group=1, Firmware Version=1.14
                Bay 1 - drive status=Ok; UID=Off
                Bay 2 - drive status=Ok; UID=Off
                Bay 3 - drive status=Ok; UID=Off
                Bay 4 - drive status=Ok; UID=Off
                Group=2, Firmware Version=1.14
                Bay 5 - drive status=Ok; UID=Off
                Bay 6 - drive status=Ok; UID=Off
                Bay 7 - drive status=Ok; UID=Off
                Bay 8 - drive status=Ok; UID=Off
              Verbs
                cd version exit show set
        """.trimIndent()

        val bays = IloCliParser.parseDriveBays(raw)

        assertEquals(8, bays.size)
        assertEquals(1, bays[0].bay)
        assertEquals(1, bays[0].group)
        assertEquals("1.14", bays[0].firmwareVersion)
        assertEquals("Ok", bays[0].status)
        assertEquals("Off", bays[0].uid)
        assertEquals(5, bays[4].bay)
        assertEquals(2, bays[4].group)
        assertEquals(HealthLevel.OK, IloCliParser.healthFromValue(bays[0].status))
    }

    @Test
    fun parseDriveBays_returnsEmptyForUnrelatedOutput() {
        val raw = "/system1/fan1\n  Properties\n    HealthState=Ok\n"
        assertEquals(emptyList<IloCliParser.DriveBay>(), IloCliParser.parseDriveBays(raw))
    }

    // Property values below are those a real iLO3 returns for these targets.

    @Test
    fun labelOf_prefersDeviceIdOverGenericElementName() {
        val fan = mapOf("DeviceID" to "Fan 1", "ElementName" to "System")
        assertEquals("Fan 1", IloCliParser.labelOf("fan1", fan))
    }

    @Test
    fun labelOf_appendsIndexWhenNameCannotDistinguishSiblings() {
        val psu = mapOf("ElementName" to "Power Supply")
        assertEquals("Power Supply 1", IloCliParser.labelOf("powersupply1", psu))
        assertEquals("Power Supply 2", IloCliParser.labelOf("powersupply2", psu))
    }

    @Test
    fun labelOf_usesLocationForMemoryModules() {
        val dimm = mapOf("size" to "Not Installed", "location" to "PROC 1 DIMM 1G")
        assertEquals("PROC 1 DIMM 1G", IloCliParser.labelOf("memory1", dimm))
    }

    @Test
    fun labelOf_fallsBackToTargetNameWhenNoNameProperty() {
        val cpu = mapOf("number_cores" to "6", "speed" to "2533MHz")
        assertEquals("cpu1", IloCliParser.labelOf("cpu1", cpu))
    }
}
