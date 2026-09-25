package net.raphaelgf11.ilo3manager.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The assistant creates an account on the user's BMC with a password only the app will ever know.
 * Getting the command wrong leaves an account nobody can log into, on a machine reached over the
 * network — so every rule that shapes that command is pinned here, against real firmware output.
 */
class HostSetupProbeTest {

    /** Verbatim `show /map1/config1` from an iLO 3 at 1.94. */
    private val configOutput = """
        /map1/config1
          Targets
          Properties
            oemhp_mapenable=yes
            oemhp_timeout=30
            oemhp_httpport=80
            oemhp_sslport=443
            oemhp_rcport=17990
            oemhp_vmport=17988
            oemhp_sshport=22
            oemhp_sshstatus=yes
            oemhp_minpwdlen=8
          Verbs
            cd version exit show set
    """.trimIndent()

    @Test
    fun `the firmware version and date are read`() {
        // Verbatim `show /map1/firmware1`.
        val output = """
            /map1/firmware1
              Targets
              Properties
                version=1.94
                date=Dec 06 2020
              Verbs
                cd version exit show load
        """.trimIndent()
        assertEquals(IloFirmware("1.94", "Dec 06 2020"), parseIloFirmware(output))
    }

    @Test
    fun `versions compare as numbers, not as text`() {
        // Sorted as strings, "1.9" lands after "1.88" — which is how firmware versions are
        // routinely misread. The parts are numbers.
        assertTrue(compareIloVersions("1.88", "1.94") < 0)
        assertTrue(compareIloVersions("1.9", "1.88") < 0)
        assertTrue(compareIloVersions("1.94", "1.94") == 0)
        assertTrue(compareIloVersions("2.10", "1.94") > 0)
        assertTrue(compareIloVersions("1.94.1", "1.94") > 0)
    }

    @Test
    fun `an older firmware is flagged and a newer one is not`() {
        assertTrue(isOlderThanTested("1.88"))
        assertTrue(isOlderThanTested("1.50"))
        assertFalse(isOlderThanTested(TESTED_ILO_VERSION))
        assertFalse(isOlderThanTested("1.95"))
        assertFalse(isOlderThanTested("2.00"))
    }

    @Test
    fun `an unreadable version is not called old`() {
        // Announcing "your firmware is out of date" on a string nobody could parse would be a
        // guess dressed as a finding.
        assertFalse(isOlderThanTested(""))
        assertFalse(isOlderThanTested("inconnue"))
    }

    @Test
    fun `an iLO's own reply is recognised as one`() {
        assertTrue(looksLikeIloCli("status=0\nstatus_tag=COMMAND COMPLETED\n  oemhp_sslport=443"))
        assertTrue(looksLikeIloCli(configOutput + "\nstatus_tag=COMMAND COMPLETED"))
    }

    @Test
    fun `an ordinary SSH server is not mistaken for an iLO`() {
        // Verbatim from a Proxmox host asked the same question. Without this check the property
        // parsers simply find nothing, and the assistant reports an iLO that declares no ports
        // rather than a machine that is not an iLO.
        assertFalse(looksLikeIloCli("bash: ligne 1: show : commande introuvable"))
        assertFalse(looksLikeIloCli("sh: show: not found"))
        assertFalse(looksLikeIloCli(""))
    }

    @Test
    fun `half a marker is not enough`() {
        // A shell could echo either word on its own; both together are what the CLP stamps.
        assertFalse(looksLikeIloCli("status_tag=COMMAND COMPLETED"))
        assertFalse(looksLikeIloCli("oemhp_sslport=443"))
    }

    @Test
    fun `the ports and the password floor are read from the access settings`() {
        val config = parseIloAccessConfig(configOutput)
        assertEquals(22, config.sshPort)
        assertEquals(443, config.httpsPort)
        assertEquals(8, config.minPasswordLength)
    }

    @Test
    fun `a non-default HTTPS port is picked up rather than assumed`() {
        val moved = configOutput.replace("oemhp_sslport=443", "oemhp_sslport=8443")
        assertEquals(8443, parseIloAccessConfig(moved).httpsPort)
    }

    @Test
    fun `an absent property reads as null instead of a wrong default`() {
        val stripped = configOutput.replace("    oemhp_sslport=443\n", "")
        assertEquals(null, parseIloAccessConfig(stripped).httpsPort)
    }

    @Test
    fun `a generated password never carries a character the iLO parser would split on`() {
        // A space, comma or equals sign would truncate the password inside the create command,
        // leaving an account whose real password is not the one the app stored.
        val random = Random(1)
        repeat(500) {
            val password = generateStrongPassword(8, random)
            assertTrue("mot de passe non transmissible : $password", isCplSafe(password))
        }
    }

    @Test
    fun `a generated password stays within what IPMI can authenticate`() {
        // Measured on the test machine: an account with a 24-character password logs in over SSH
        // and is refused by IPMI, whose RAKP exchange carries at most 20 bytes. The assistant would
        // then report "IPMI désactivé" for a server that supports it perfectly — having itself
        // created the account that cannot use it.
        assertEquals(IPMI_MAX_PASSWORD_LENGTH, generateStrongPassword(8).length)
        assertEquals(IPMI_MAX_PASSWORD_LENGTH, generateStrongPassword(null).length)
        assertEquals(IPMI_MAX_PASSWORD_LENGTH, generateStrongPassword(20).length)
    }

    @Test
    fun `an iLO demanding more than IPMI allows is still obeyed`() {
        // Its accounts cannot use IPMI whatever we generate, so the password policy wins — but the
        // assistant has to be able to say why.
        assertEquals(30, generateStrongPassword(30).length)
        assertTrue(dedicatedAccountLosesIpmi(30))
        assertFalse(dedicatedAccountLosesIpmi(8))
        assertFalse(dedicatedAccountLosesIpmi(null))
        // Asking beyond the firmware's own limit would create an account nobody can log into.
        assertEquals(ILO_MAX_PASSWORD_LENGTH, generateStrongPassword(120).length)
    }

    @Test
    fun `the login name fits what IPMI accepts, and uses all of it`() {
        // Measured: 16 characters authenticate over IPMI, 17 are refused outright. A longer name
        // works over SSH and the web, so the assistant would report "IPMI désactivé" for a server
        // that supports it — having itself created the account that cannot use it.
        val name = defaultDedicatedUsername("rgauthier", "RKY-LX1")
        assertEquals("rga-RKY-LX1-I3M0", name)
        assertEquals(IPMI_MAX_USERNAME_LENGTH, name.length)
    }

    @Test
    fun `the account name is only shortened as far as it has to be`() {
        // "S21" leaves room, so "rgauthier" keeps more of itself than the three-character floor.
        val name = defaultDedicatedUsername("rgauthier", "S21")
        assertEquals("rgauthi-S21-I3M0", name)
        assertEquals(IPMI_MAX_USERNAME_LENGTH, name.length)
    }

    @Test
    fun `a model too long to fit beside a three-character name is cut in turn`() {
        val name = defaultDedicatedUsername("rgauthier", "Standard-PC-i440FX-PIIX-1996")
        assertEquals(IPMI_MAX_USERNAME_LENGTH, name.length)
        assertTrue("le compte garde son plancher", name.startsWith("rga-"))
        assertTrue(name.endsWith("-I3M0"))
    }

    @Test
    fun `short material yields a short name rather than padding`() {
        assertEquals("ab-XY-I3M0", defaultDedicatedUsername("ab", "XY"))
    }

    @Test
    fun `a device model the iLO parser could not carry is cleaned up`() {
        // Build.MODEL routinely holds spaces, and emulators produce runs of underscores.
        val name = defaultDedicatedUsername("rg", "Pixel 6 Pro")
        assertEquals("rg-Pixel-6-I3M0", name)
        assertTrue(isCplSafe(name))
    }

    @Test
    fun `a missing half does not leave a dangling separator`() {
        assertEquals("rgauthier-I3M0", defaultDedicatedUsername("rgauthier", ""))
        assertEquals("RKY-LX1-I3M0", defaultDedicatedUsername("", "RKY-LX1"))
    }

    @Test
    fun `the display name keeps what the login name had to drop`() {
        // The iLO shows it beside the login name, so the phone model survives there even though
        // sixteen characters could not hold it.
        val username = defaultDedicatedUsername("rgauthier", "RKY-LX1")
        val display = dedicatedAccountDisplayName("rgauthier", "RKY-LX1", username)
        assertEquals("rgauthier-RKY-LX1-I3M0", display)
        assertTrue(display.length <= ILO_MAX_USERNAME_LENGTH)
    }

    @Test
    fun `both names carry the same hex suffix`() {
        val display = dedicatedAccountDisplayName("rgauthier", "RKY-LX1", "rga-RKY-LX1-I3M3")
        assertTrue("suffixe attendu dans « $display »", display.endsWith("-I3M3"))
    }

    @Test
    fun `a display name beyond the iLO's own limit is trimmed too`() {
        val display = dedicatedAccountDisplayName("rgauthier", "M".repeat(80), "x-I3M0")
        assertEquals(ILO_MAX_USERNAME_LENGTH, display.length)
        assertTrue(display.endsWith("-I3M0"))
    }

    @Test
    fun `a taken name moves to the next hex digit`() {
        val existing = listOf("Administrator", "rgauthier-RKY-LX1-I3M0", "rgauthier-RKY-LX1-I3M1")
        assertEquals(
            "rgauthier-RKY-LX1-I3M2",
            nextFreeDedicatedUsername("rgauthier-RKY-LX1-I3M0", existing),
        )
    }

    @Test
    fun `sixteen installations exhaust the suffixes rather than overwrite one`() {
        val taken = (0..15).map { "u-m-I3M" + "0123456789ABCDEF"[it] }
        assertEquals(null, nextFreeDedicatedUsername("u-m-I3M0", taken))
    }

    @Test
    fun `a name the user typed is taken literally`() {
        // Renaming someone's deliberate choice under them would be worse than refusing.
        assertEquals("monCompte", nextFreeDedicatedUsername("monCompte", listOf("autre")))
        assertEquals(null, nextFreeDedicatedUsername("monCompte", listOf("MONCOMPTE")))
    }

    @Test
    fun `the create command matches the syntax the firmware documents`() {
        val command = createAccountCommand("ilo3manager", "abc123", "ilo3manager")
        assertEquals(
            "create /map1/accounts1 username=ilo3manager password=abc123 " +
                "name=ilo3manager group=admin,config,oemhp_rc,oemhp_power,oemhp_vm",
            command,
        )
    }

    @Test
    fun `a username the parser would split on is refused rather than sent`() {
        listOf("mon utilisateur", "a,b", "a=b").forEach { bad ->
            val failed = runCatching { createAccountCommand(bad, "abc123", "nom") }.isFailure
            assertTrue("« $bad » aurait dû être refusé", failed)
        }
    }

    /** Verbatim `show /system1`, trimmed of its thirty sensor targets. */
    private val systemOutput = """
        /system1
          Targets
            sensor1
            powersupply1
          Properties
            name=ProLiant DL380 G7
            number=CZ220602GY
            oemhp_server_name=dl380g7
            enabledstate=enabled
          Verbs
            cd version exit show reset set start stop
    """.trimIndent()

    @Test
    fun `the server names itself, rather than being listed by its address`() {
        assertEquals("dl380g7", parseServerName(systemOutput))
    }

    @Test
    fun `a server with no name of its own falls back to its model`() {
        val unnamed = systemOutput.replace("oemhp_server_name=dl380g7", "oemhp_server_name=")
        assertEquals("ProLiant DL380 G7", parseServerName(unnamed))
    }

    @Test
    fun `a system node with neither yields nothing rather than a stray property`() {
        assertEquals("", parseServerName("/system1\n  Properties\n    enabledstate=enabled"))
    }

    @Test
    fun `the existing accounts are read as targets, not as properties`() {
        val output = """
            /map1/accounts1
              Targets
                Administrator
                rgauthier
              Properties
              Verbs
                cd version exit show create delete set
        """.trimIndent()

        assertEquals(listOf("Administrator", "rgauthier"), parseAccountNames(output))
        assertTrue(accountExists(output, "rgauthier"))
        // Case-insensitive: the iLO would reject a duplicate differing only in case.
        assertTrue(accountExists(output, "RGAUTHIER"))
        assertFalse(accountExists(output, "ilo3manager"))
    }

    @Test
    fun `the verbs are not mistaken for account names`() {
        val output = """
            /map1/accounts1
              Targets
              Properties
              Verbs
                cd version exit show create delete set
        """.trimIndent()
        assertTrue(parseAccountNames(output).isEmpty())
    }

    @Test
    fun `a failed command is not read as a success`() {
        assertTrue(commandSucceeded("status=0\nstatus_tag=COMMAND COMPLETED"))
        assertFalse(commandSucceeded("status=2\nstatus_tag=COMMAND PROCESSING FAILED"))
        assertFalse(commandSucceeded(""))
    }
}
