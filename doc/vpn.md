# Tunnels VPN

Pour joindre un iLO absent de votre réseau local. Deux mécanismes, aux capacités volontairement différentes.

## Ce qui les distingue

| | WireGuard | Rebond SSH |
|---|---|---|
| CLI iLO, console série, interface web | oui | oui |
| **IPMI** | **oui** | **non** |
| Bibliothèque native | oui (~9 Mo) | non |
| Prérequis | un pair WireGuard | un serveur SSH intermédiaire |

La différence décisive : **SSH ne relaie que du TCP**. IPMI fonctionnant en UDP sur le port 623, il ne peut pas transiter par un rebond SSH. L'application le détecte et repasse automatiquement sur la CLI SSH pour l'onglet Alim, plutôt que d'échouer à l'usage.

## WireGuard

Le tunnel s'exécute **dans le processus de l'application**, sur une pile réseau en espace utilisateur. Conséquences directes :

- aucune interface VPN système n'est créée ;
- Android n'affiche aucune demande d'autorisation VPN ;
- le trafic des **autres applications n'est pas capturé** — seule iLO3 Manager emprunte le tunnel.

C'est ce qui le distingue d'un client WireGuard classique, lequel passe par `VpnService` et capte tout l'appareil.

### Configurer

Trois voies dans l'onglet VPN :

1. **Importer un `.conf`** — le fichier fourni par votre pair WireGuard ;
2. **Scanner un QR code** — celui qu'exportent la plupart des serveurs WireGuard ;
3. **Coller** le contenu directement.

L'application analyse la configuration au fil de la saisie et affiche un résumé — pair, adresse locale, réseaux routés — pour confirmer qu'elle a lu ce que vous croyez. Une configuration invalide est signalée avec le champ fautif.

`AllowedIPs` doit inclure le réseau de l'iLO ; sans cela le trafic ne prendra pas le tunnel.

### Bon à savoir

Le tunnel s'établit au premier besoin et se ferme avec les sessions de l'hôte. WireGuard ne négocie sa poignée de main qu'au premier paquet à émettre, et **ce paquet-là est perdu** : l'application retransmet, ce qui est de toute façon nécessaire sur UDP.

## Rebond SSH

Les connexions vers l'iLO sont relayées par une machine intermédiaire, exactement comme un `ssh -L`. Aucune bibliothèque native, donc aucun surcoût de taille.

| Champ | Rôle |
|---|---|
| Hôte de rebond | Adresse du serveur intermédiaire |
| Port | 22 par défaut |
| Utilisateur | Compte sur le rebond |
| Mot de passe ou clé privée | Authentification sur le rebond |

Le rebond doit pouvoir joindre l'iLO ; l'application n'y exécute rien d'autre qu'une redirection de ports.

## Choisir

- L'iLO est sur votre réseau : **aucun tunnel**.
- Accès distant et IPMI souhaité : **WireGuard**.
- Accès distant, un serveur SSH déjà en place, IPMI superflu : **rebond SSH**.

## Et les autres protocoles ?

**IPsec/IKEv2** — non réalisable ici. Android permet bien d'appliquer IPsec à une socket isolée, mais créer un **tunnel** exige la permission `MANAGE_IPSEC_TUNNELS`, réservée aux applications système. Une application tierce doit donc passer par `VpnService`, précisément ce que cette architecture évite.

**OpenVPN** — écarté : ses versions ne sont pas rétrocompatibles entre elles, ce qui en fait un couple client/serveur pénible à maintenir dans la durée.

**PPTP** — écarté pour deux raisons indépendantes. Sa sécurité est cassée depuis 2012 : la poignée de main MS-CHAPv2 se ramène à un unique cassage de clé DES, quelle que soit la complexité du mot de passe. Et surtout, son canal de données utilise GRE, un protocole IP qu'une application non privilégiée ne peut pas émettre — cela exige `CAP_NET_RAW`, qu'Android n'accorde pas.
