package net.raphaelgf11.ilo3manager.vpn

/**
 * Tracks which hosts depend on each network's tunnel.
 *
 * A tunnel used to belong to one host, so disconnecting that host could simply close it. Shared
 * between the hosts behind one network it cannot: cutting it on the first disconnection would kill
 * the live sessions of every other server on the same VPN. Kept apart from [HostTunnelManager] so
 * this rule can be tested without a device.
 *
 * Not thread-safe; callers hold [HostTunnelManager]'s lock.
 */
class TunnelUsers {

    private val users = mutableMapOf<String, MutableSet<String>>()

    fun claim(networkId: String, hostId: String) {
        users.getOrPut(networkId) { mutableSetOf() }.add(hostId)
    }

    /**
     * Drops a host's claim and returns the networks left with no user at all — those, and only
     * those, are the tunnels that may now be torn down.
     */
    fun release(hostId: String): List<String> {
        val orphaned = users.entries
            .filter { (_, hosts) -> hosts.remove(hostId) && hosts.isEmpty() }
            .map { it.key }
        orphaned.forEach { users.remove(it) }
        return orphaned
    }

    /** Forgets a network outright, for when its tunnel is closed whoever was using it. */
    fun forget(networkId: String) {
        users.remove(networkId)
    }

    fun usersOf(networkId: String): Set<String> = users[networkId].orEmpty()
}
