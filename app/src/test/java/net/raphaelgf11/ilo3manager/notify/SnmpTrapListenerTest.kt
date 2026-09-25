package net.raphaelgf11.ilo3manager.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the socket for real, on loopback, rather than around it. */
class SnmpTrapListenerTest {

    private fun bytes(hex: String) = ByteArray(hex.length / 2) {
        hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    private val iloV1 = bytes(
        "30818102010004067075626c6963a474060a2b0601040181680902094004cb00711e0201060202232b" +
            "4304075bcd1530533017060c2b0601040181680b020b01000407646c333830673730220" +
            "60c2b060104018168090209010004125465737420747261702066726f6d20694c4f30140608" +
            "2b060102010105000408494c4f2d35542d48",
    )

    private fun send(port: Int, payload: ByteArray) {
        DatagramSocket().use {
            it.send(DatagramPacket(payload, payload.size, InetAddress.getByName("127.0.0.1"), port))
        }
    }

    @Test
    fun `a trap sent to the bound port comes back decoded`() {
        val received = CountDownLatch(1)
        var seen: SnmpTrap? = null
        val listener = SnmpTrapListener(onTrap = {
            seen = it
            received.countDown()
        })
        val thread = Thread { listener.listen() }.apply { isDaemon = true; start() }

        send(listener.port, iloV1)
        assertTrue("aucune trap reçue", received.await(5, TimeUnit.SECONDS))
        assertEquals(9003, seen!!.specificTrap)

        listener.close()
        thread.join(2_000)
    }

    @Test
    fun `rubbish on the port is reported apart, and does not stop the listener`() {
        // Anything that can reach the port can send anything; the next datagram is the one that
        // might be a real incident.
        val decoded = CountDownLatch(1)
        val rejected = CountDownLatch(1)
        val listener = SnmpTrapListener(
            onTrap = { decoded.countDown() },
            onUndecodable = { _, _ -> rejected.countDown() },
        )
        val thread = Thread { listener.listen() }.apply { isDaemon = true; start() }

        send(listener.port, "pas du tout une trap".toByteArray())
        assertTrue("le rebut doit être signalé", rejected.await(5, TimeUnit.SECONDS))

        send(listener.port, iloV1)
        assertTrue("l'écouteur doit survivre au rebut", decoded.await(5, TimeUnit.SECONDS))

        listener.close()
        thread.join(2_000)
    }

    @Test
    fun `a callback that throws does not take the listener down`() {
        val second = CountDownLatch(1)
        var calls = 0
        val listener = SnmpTrapListener(onTrap = {
            calls++
            if (calls == 1) error("le premier appel échoue")
            second.countDown()
        })
        val thread = Thread { listener.listen() }.apply { isDaemon = true; start() }

        send(listener.port, iloV1)
        send(listener.port, iloV1)
        assertTrue("la seconde trap doit être traitée", second.await(5, TimeUnit.SECONDS))

        listener.close()
        thread.join(2_000)
    }

    @Test
    fun `closing stops the loop`() {
        val listener = SnmpTrapListener(onTrap = {})
        val stopped = CountDownLatch(1)
        Thread {
            listener.listen()
            stopped.countDown()
        }.apply { isDaemon = true; start() }

        Thread.sleep(200)
        listener.close()
        assertTrue("la boucle doit rendre la main", stopped.await(5, TimeUnit.SECONDS))
    }
}
