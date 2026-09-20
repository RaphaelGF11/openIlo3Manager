# Configuration d'un hôte

La fiche d'un serveur s'organise en quatre onglets. Le bouton **Enregistrer** reste visible depuis n'importe lequel : les champs obligatoires étant répartis sur deux onglets, il serait déroutant qu'il dépende de celui affiché.

## Onglet Général

| Champ | Rôle |
|---|---|
| Nom | Libellé affiché dans la liste |
| Adresse | IP ou nom d'hôte de l'**iLO**, pas du serveur qu'il pilote |
| Port SSH | 22 par défaut |
| Port HTTPS | 443 par défaut, utilisé par la passerelle web |

L'adresse est celle du processeur de gestion. Il possède sa propre adresse réseau, distincte de celle du système d'exploitation du serveur, et reste joignable même serveur éteint.

## Onglet Authentification

L'utilisateur et le secret servent à SSH, à IPMI et à l'interface web : c'est le même compte iLO.

**Mot de passe** — le plus simple, et **requis pour IPMI**, qui s'authentifie par mot de passe.

**Clé privée** — importable depuis un fichier, collable, ou générable dans l'application. Dans ce dernier cas, la clé publique s'affiche pour que vous la déposiez dans l'iLO (Administration › Sécurité › SSH).

> Un hôte en clé privée sans mot de passe enregistré **ne peut pas utiliser IPMI**. L'application le signale dans l'onglet IPMI plutôt que d'échouer silencieusement.

En modification, les secrets ne sont **jamais réaffichés** : les champs apparaissent vides et, laissés vides, conservent la valeur enregistrée.

## Onglet IPMI

IPMI est un protocole d'administration matérielle qui court-circuite la CLI. Sur ce matériel, il répond en une fraction de seconde là où SSH demande plusieurs secondes.

**Utiliser IPMI pour l'alimentation** — l'onglet Alim passe entièrement par IPMI et n'ouvre plus aucune session SSH.

**Niveau de privilège** — mesuré sur un iLO3 réel :

| Niveau | Lecture d'état | Alimentation, LED UID |
|---|---|---|
| Lecture seule | oui | non |
| **Opérateur** (défaut) | oui | oui |
| Administrateur | oui | oui |

Opérateur suffit donc à tout ce que fait l'application. C'est important : l'iLO n'accorde le niveau Administrateur qu'à un compte détenant **tous** les privilèges, ce qu'il serait excessif de concéder pour un tableau de bord.

**Afficher l'état dans la liste** — la liste des serveurs interroge périodiquement cet hôte pour y montrer son alimentation et ses défauts. Réservé à IPMI : une session SSH par serveur serait bien trop lente et saturerait le petit nombre de sessions simultanées qu'accepte un iLO3.

**Onglet HW via IPMI** — voir [Fonctionnalités](fonctionnalites.md#matériel-hw).

### Activer IPMI sur l'iLO

IPMI/DCMI par LAN est **désactivé par défaut** sur iLO3. L'application propose de l'activer après votre première connexion SSH, en demandant toujours confirmation.

Cette demande n'est pas une formalité : elle ouvre le port UDP 623, et la poignée de main d'IPMI 2.0 **livre par conception une empreinte du mot de passe à quiconque la demande**, exploitable hors ligne. C'est inhérent au protocole, pas un défaut de configuration. À réserver à un réseau d'administration de confiance.

À noter : la CLI SSH de l'iLO3 n'expose **aucun** réglage IPMI — vérifié cible par cible. L'application passe donc par l'API JSON de l'interface web pour l'activer.

## Onglet VPN

Pour joindre un iLO qui n'est pas sur votre réseau local. Voir [Tunnels VPN](vpn.md).
