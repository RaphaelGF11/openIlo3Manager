package net.raphaelgf11.ilo3manager.setup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.raphaelgf11.ilo3manager.data.SshHost
import net.raphaelgf11.ilo3manager.ipmi.IpmiLanClient
import net.raphaelgf11.ilo3manager.ipmi.IpmiPrivilege
import net.raphaelgf11.ilo3manager.ssh.IloCliClient
import net.raphaelgf11.ilo3manager.vpn.HostTunnelManager
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Runs the setup assistant's steps against a real iLO.
 *
 * Every step opens what it needs and closes it again rather than holding a session across the
 * screens: the user can stop between two steps, or leave the phone on step three for a quarter of
 * an hour, and this BMC allows so few sessions that one left open would lock them out of the web
 * interface too.
 */
object HostSetupRunner {

    /**
     * Whether something accepts TCP connections on [port].
     *
     * Goes through whatever tunnel the draft host names, so scanning a server behind a VPN tests
     * the path the app will actually use rather than the phone's own network.
     */
    suspend fun probeTcpPort(draft: SshHost, port: Int, timeoutMs: Int = 4_000): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val endpoint = HostTunnelManager.endpointFor(draft, port)
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs)
                    true
                }
            }.getOrDefault(false)
        }

    /** Everything one connection can tell us about the iLO, before anything is written to it. */
    data class IloInspection(
        val accessConfig: IloAccessConfig,
        /** The name the server carries in its own configuration; blank if it has none. */
        val serverName: String,
        /** Accounts already present, which decide the suffix a dedicated one may take. */
        val accounts: List<String>,
        /** What the iLO runs, so an outdated one can be said so rather than assumed current. */
        val firmware: IloFirmware = IloFirmware("", ""),
    )

    /**
     * Connects with the credentials the user gave and reads everything the rest needs.
     *
     * Reading is kept apart from creating so the credentials can be proven before the user is asked
     * whether to create an account — and so the account list, which decides the name that account
     * may take, is in hand when the question is put rather than after it.
     */
    suspend fun inspect(draft: SshHost): Result<IloInspection> = withContext(Dispatchers.IO) {
        val cli = IloCliClient()
        try {
            cli.connect(draft)
            // Three reads on one session: this BMC counts them, and they are one trip.
            val config = cli.runCommand("show /map1/config1")
            if (!looksLikeIloCli(config)) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "Ce serveur SSH répond, mais ce n'est pas un iLO : il ne connaît pas la " +
                            "commande « show /map1/config1 »." +
                            cli.serverVersion.takeIf { it.isNotBlank() }
                                ?.let { " Il s'annonce comme « $it » ; un iLO 3 répond " +
                                    "« SSH-2.0-RomSShell »." }
                                .orEmpty() +
                            " Vérifiez l'adresse : c'est celle de l'iLO qu'il faut, pas celle du " +
                            "système installé sur le serveur.",
                    ),
                )
            }
            Result.success(
                IloInspection(
                    accessConfig = parseIloAccessConfig(config),
                    serverName = parseServerName(cli.runCommand("show /system1")),
                    accounts = parseAccountNames(cli.runCommand("show /map1/accounts1")),
                    firmware = parseIloFirmware(cli.runCommand("show /map1/firmware1")),
                ),
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            runCatching { cli.disconnect() }
        }
    }

    /** The account this run created, and the password only this app will ever know. */
    data class CreatedAccount(val username: String, val password: String)

    /**
     * Creates the dedicated account, resolving its name against what is already on the iLO.
     *
     * The account list is read again here rather than reused from [inspect]: minutes may have
     * passed on the intervening screens, and this is the read that decides what gets written.
     */
    suspend fun createDedicatedAccount(
        admin: SshHost,
        desiredUsername: String,
        minPasswordLength: Int?,
        displayNameFor: (username: String) -> String = { it },
    ): Result<CreatedAccount> = withContext(Dispatchers.IO) {
        if (!isCplSafe(desiredUsername)) {
            return@withContext Result.failure(
                IllegalArgumentException(
                    "Le nom « $desiredUsername » contient un espace, une virgule ou un signe " +
                        "égal, que la CLI de l'iLO ne sait pas lire.",
                ),
            )
        }

        val cli = IloCliClient()
        try {
            cli.connect(admin)
            val accounts = parseAccountNames(cli.runCommand("show /map1/accounts1"))
            val accountName = nextFreeDedicatedUsername(desiredUsername, accounts)
                ?: return@withContext Result.failure(
                    IllegalStateException(
                        // The two dead ends are different situations and deserve different words:
                        // a composed name has sixteen variants to exhaust, a typed one has one.
                        if (isComposedDedicatedName(desiredUsername)) {
                            "Les seize variantes de « $desiredUsername » sont déjà prises sur " +
                                "cet iLO. Choisissez un autre nom : l'application n'écrase pas " +
                                "un compte, son mot de passe pourrait servir ailleurs."
                        } else {
                            "Un compte « $desiredUsername » existe déjà sur cet iLO. " +
                                "Choisissez un autre nom : l'application n'écrase pas un compte, " +
                                "son mot de passe pourrait servir ailleurs."
                        },
                    ),
                )

            val password = generateStrongPassword(minPasswordLength)
            val output = cli.runCommand(
                createAccountCommand(accountName, password, displayNameFor(accountName)),
            )
            if (!commandSucceeded(output)) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "L'iLO a refusé la création du compte. Réponse : " +
                            output.lines().filter { it.isNotBlank() }.takeLast(3).joinToString(" "),
                    ),
                )
            }

            // Confirm against the account list rather than the command's own status: a refused
            // create can still report a completed command.
            if (!accountExists(cli.runCommand("show /map1/accounts1"), accountName)) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "Le compte « $accountName » n'apparaît pas après sa création. " +
                            "Rien n'a été enregistré.",
                    ),
                )
            }

            Result.success(CreatedAccount(accountName, password))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            runCatching { cli.disconnect() }
        }
    }

    /**
     * Removes an account the assistant created, for a run the user abandoned.
     *
     * Connects with the credentials that authorised the creation rather than with the new account:
     * an account deleting itself is a needless edge, and those credentials are the ones proven to
     * have the right to do this.
     *
     * Confirms against the account list afterwards, because a refused delete still reports a
     * completed command — the same trap the creation path already guards against.
     */
    suspend fun deleteAccount(admin: SshHost, username: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (username.isBlank()) return@withContext Result.success(Unit)
            val cli = IloCliClient()
            try {
                cli.connect(admin)
                cli.runCommand("delete /map1/accounts1/$username")
                if (accountExists(cli.runCommand("show /map1/accounts1"), username)) {
                    Result.failure(
                        IllegalStateException(
                            "Le compte « $username » est toujours présent sur l'iLO.",
                        ),
                    )
                } else {
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                runCatching { cli.disconnect() }
            }
        }

    /** The outcome of trying IPMI, which iLO 3 leaves off by default. */
    sealed interface IpmiOutcome {
        /** The highest level the BMC will grant this account. */
        data class Available(val privilege: IpmiPrivilege) : IpmiOutcome

        /** Nothing answered, or the account was refused; the host is configured without IPMI. */
        data class Unavailable(val reason: String) : IpmiOutcome
    }

    /**
     * Opens one IPMI session to find out whether it works and how far it gets.
     *
     * Asks for Administrator not to use it but to read the ceiling back: the BMC answers with the
     * highest level it will grant, so one session establishes what the account can reach. Settling
     * for a level chosen blindly would fail later, on a command rather than at login.
     */
    suspend fun probeIpmi(draft: SshHost): IpmiOutcome = withContext(Dispatchers.IO) {
        if (draft.password.isBlank()) {
            return@withContext IpmiOutcome.Unavailable(
                "IPMI s'authentifie par mot de passe ; ce compte n'en a pas.",
            )
        }
        if (!HostTunnelManager.supportsUdp(draft)) {
            return@withContext IpmiOutcome.Unavailable(
                "Le réseau de ce serveur ne transporte pas l'UDP, dont IPMI a besoin.",
            )
        }

        val endpoint = runCatching { HostTunnelManager.endpointFor(draft, draft.ipmiPort, udp = true) }
            .getOrElse { return@withContext IpmiOutcome.Unavailable(it.message ?: "Tunnel indisponible.") }

        val client = IpmiLanClient(
            host = endpoint.host,
            port = endpoint.port,
            username = draft.username,
            password = draft.password,
            privilege = IpmiPrivilege.ADMINISTRATOR,
        )
        try {
            client.open()
            val granted = client.grantedPrivilege
                ?: return@withContext IpmiOutcome.Unavailable(
                    "L'iLO a ouvert la session sans annoncer de niveau de privilège.",
                )
            IpmiOutcome.Available(granted)
        } catch (e: Exception) {
            IpmiOutcome.Unavailable(
                e.message ?: "Aucune réponse sur le port ${draft.ipmiPort}.",
            )
        } finally {
            runCatching { client.close() }
        }
    }
}
