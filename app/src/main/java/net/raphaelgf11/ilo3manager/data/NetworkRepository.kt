package net.raphaelgf11.ilo3manager.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import net.raphaelgf11.ilo3manager.vpn.HostTunnelManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores the named networks hosts can be reached through.
 *
 * Shares the hosts' encrypted file: these records hold WireGuard private keys and jump-host
 * passwords, so they need exactly the same protection, and keeping one file means one master key.
 */
class NetworkRepository(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            "ilo3manager_hosts",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getNetworks(): List<NetworkConfig> {
        val json = prefs.getString(KEY_NETWORKS, null) ?: return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { i -> fromJson(array.getJSONObject(i)) }
    }

    fun saveNetwork(network: NetworkConfig) {
        val networks = getNetworks().toMutableList()
        val index = networks.indexOfFirst { it.id == network.id }
        // A live tunnel was built from the old configuration and would keep being reused, so an
        // edit would appear to have no effect until the app was restarted.
        if (index >= 0 && networks[index] != network) HostTunnelManager.closeNetwork(network.id)
        if (index >= 0) networks[index] = network else networks.add(network)
        persist(networks)
    }

    /**
     * Removes a network and detaches every host still pointing at it, which sends those hosts back
     * to a direct connection rather than leaving them referring to something gone.
     */
    fun deleteNetwork(id: String, hostRepository: HostRepository) {
        persist(getNetworks().filterNot { it.id == id })
        HostTunnelManager.closeNetwork(id)
        hostRepository.getHosts()
            .filter { it.networkId == id }
            .forEach { hostRepository.saveHost(it.copy(networkId = "")) }
    }

    /**
     * Replaces every stored network, for a restore.
     *
     * Live tunnels are torn down first: they were built from the configurations being replaced, and
     * a restore that left them running would have the phone still talking through the old ones.
     */
    fun replaceAll(networks: List<NetworkConfig>) {
        getNetworks().forEach { HostTunnelManager.closeNetwork(it.id) }
        persist(networks)
    }

    /**
     * Folds the tunnel configurations that used to live inside each host into named networks.
     *
     * Runs on every start and does nothing once there is nothing left to move, so it is safe to
     * call unconditionally.
     */
    fun migrateLegacyVpn(hostRepository: HostRepository) {
        val result = foldLegacyVpn(hostRepository.getHosts(), getNetworks())
        if (result.hosts.isEmpty()) return
        persist(result.networks)
        result.hosts.forEach { hostRepository.saveHost(it) }
    }

    private fun persist(networks: List<NetworkConfig>) {
        val array = JSONArray()
        networks.forEach { array.put(toJson(it)) }
        prefs.edit().putString(KEY_NETWORKS, array.toString()).apply()
    }

    private fun toJson(network: NetworkConfig) = JSONObject().apply {
        put("id", network.id)
        put("name", network.name)
        put("type", network.type.name)
        put("wireGuardConfig", network.wireGuardConfig)
        put("sshTunnelConfig", network.sshTunnelConfig)
        put("interfaceName", network.interfaceName)
        put("secondaryAddress", network.secondaryAddress)
    }

    private fun fromJson(o: JSONObject) = NetworkConfig(
        id = o.getString("id"),
        name = o.optString("name", ""),
        type = runCatching { NetworkType.valueOf(o.getString("type")) }
            .getOrDefault(NetworkType.WIREGUARD),
        wireGuardConfig = o.optString("wireGuardConfig", ""),
        sshTunnelConfig = o.optString("sshTunnelConfig", ""),
        interfaceName = o.optString("interfaceName", ""),
        secondaryAddress = o.optString("secondaryAddress", ""),
    )

    companion object {
        private const val KEY_NETWORKS = "networks_json"
    }
}

/** The networks to store, and only those hosts the migration actually changed. */
data class LegacyVpnMigration(val networks: List<NetworkConfig>, val hosts: List<SshHost>)

/**
 * Works out which networks a set of hosts implies, without touching storage.
 *
 * Hosts carrying the same configuration are folded onto one network rather than one each: sharing
 * a tunnel between servers is the whole point of naming it, and two servers behind the same VPN
 * were the case that motivated this. Identity is the configuration text itself, which is what the
 * user typed — two hosts on the same VPN were configured by pasting the same thing.
 */
fun foldLegacyVpn(
    hosts: List<SshHost>,
    existing: List<NetworkConfig>,
): LegacyVpnMigration {
    val networks = existing.toMutableList()
    val changed = mutableListOf<SshHost>()

    for (host in hosts) {
        // Already migrated, or never tunnelled in the first place.
        if (host.networkId.isNotBlank() || host.vpnType == VpnType.NONE) continue

        val type = when (host.vpnType) {
            VpnType.WIREGUARD -> NetworkType.WIREGUARD
            VpnType.SSH_TUNNEL -> NetworkType.SSH_TUNNEL
            VpnType.NONE -> continue
        }
        val config = when (type) {
            NetworkType.WIREGUARD -> host.wireGuardConfig
            else -> host.sshTunnelConfig
        }
        // An empty configuration names nothing and would fold every such host together; leave the
        // host direct rather than inventing a network out of nothing.
        if (config.isBlank()) continue

        val match = networks.firstOrNull {
            it.type == type && configOf(it) == config
        }
        val network = match ?: NetworkConfig(
            name = defaultName(type, host.name, networks),
            type = type,
            wireGuardConfig = if (type == NetworkType.WIREGUARD) config else "",
            sshTunnelConfig = if (type == NetworkType.SSH_TUNNEL) config else "",
        ).also { networks.add(it) }

        changed.add(host.copy(networkId = network.id))
    }
    return LegacyVpnMigration(networks, changed)
}

private fun configOf(network: NetworkConfig): String = when (network.type) {
    NetworkType.WIREGUARD -> network.wireGuardConfig
    NetworkType.SSH_TUNNEL -> network.sshTunnelConfig
    NetworkType.SECONDARY_IP -> network.secondaryAddress
}

/** Names a migrated network after the host it came from, keeping names distinct. */
private fun defaultName(type: NetworkType, hostName: String, existing: List<NetworkConfig>): String {
    val base = if (hostName.isBlank()) type.label else "${type.label} — $hostName"
    if (existing.none { it.name == base }) return base
    var suffix = 2
    while (existing.any { it.name == "$base ($suffix)" }) suffix++
    return "$base ($suffix)"
}
