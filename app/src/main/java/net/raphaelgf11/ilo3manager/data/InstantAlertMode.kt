package net.raphaelgf11.ilo3manager.data

/**
 * How an incident reaches the phone without waiting for the next periodic check.
 *
 * None of these replace that check. An SNMP trap is UDP, fire-and-forget: a lost one is a missed
 * incident with no way to tell it apart from a quiet server, and no trap at all arrives when the
 * iLO itself is dead. The periodic check stays as the safety net; these only remove the latency.
 */
enum class InstantAlertMode(val label: String, val detail: String) {
    DISABLED(
        "Désactivé",
        "Seule la vérification périodique alerte, avec la latence de son intervalle.",
    ),

    /**
     * The phone itself receives the traps. No infrastructure, but it needs a socket held open and
     * the iLO has to be able to reach the phone — which through a tunnel means the tunnel stays up.
     */
    SNMP_DIRECT(
        "SNMP direct",
        "Le téléphone écoute lui-même les traps de l'iLO. Aucune infrastructure, mais impose un " +
            "service en arrière-plan permanent, et l'iLO doit pouvoir joindre le téléphone — donc " +
            "un tunnel maintenu ouvert.",
    ),

    /**
     * A self-hosted relay receives the traps and the app holds one outbound connection to it.
     * Keeps everything on the user's own infrastructure, at the cost of a machine that must be
     * running — and not the one being watched.
     */
    SNMP_GATEWAY(
        "Passerelle SNMP auto-hébergée",
        "Une passerelle à vous reçoit les traps et l'application y garde une connexion sortante. " +
            "Rien ne passe par un tiers, mais la passerelle doit tourner ailleurs que sur le " +
            "serveur surveillé : sinon elle tombe avec lui, au moment précis où elle sert.",
    ),

    /**
     * The relay pushes through Firebase, so the phone keeps no connection of its own — the one
     * Play Services already maintains carries it. The message transits Google.
     */
    FIREBASE(
        "Firebase (FCM)",
        "La passerelle pousse via Firebase : le téléphone ne garde aucune connexion, celle des " +
            "services Play suffit. C'est le plus économe en batterie, mais la notification " +
            "transite par Google — envoyez-y un message opaque, le détail étant lu ensuite par " +
            "l'application.",
    ),
}
