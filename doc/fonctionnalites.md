# Fonctionnalités

Ouvrir un serveur donne accès à cinq onglets.

## Liste des serveurs

Chaque ligne porte une **pastille d'état** à gauche du nom :

| Couleur | Signification |
|---|---|
| Gris | Non connecté, ou état inconnu |
| **Jaune** | Récupération en cours — connexion, authentification ou interrogation |
| Vert | Serveur allumé |
| Orange | Serveur éteint |
| Rouge | Défaut non critique (disque, ventilation) |
| Rouge barré d'une croix | Défaut critique (alimentation) |
| Bleu | LED de localisation (UID) allumée |

Le jaune couvre tous les cas où l'application cherche encore : une session ouverte ne dit rien de l'état d'alimentation, et afficher du vert à ce moment serait une affirmation non vérifiée.

Le bleu prime sur l'état d'alimentation : on allume cette LED délibérément pour retrouver une machine, afficher « allumé » masquerait ce qu'on cherche. La croix sur le rouge critique évite de distinguer deux états par la seule couleur.

Un glissement vers le bas actualise la liste et relance immédiatement les interrogations.

## Alimentation (Alim)

État, santé globale, et les actions : démarrer, redémarrer, arrêter proprement, forcer l'arrêt. Les trois dernières demandent confirmation.

**LED UID** — la lampe de localisation du châssis. Son pilotage fonctionne aussi bien par IPMI que par SSH, mais **seul IPMI sait lire son état**. La CLI répond `enabledstate=enabled` que la LED soit allumée ou non. D'où deux interfaces :

- en IPMI, un bouton unique qui reflète l'état ;
- en SSH, deux boutons explicites, un interrupteur unique risquant de s'afficher à l'envers.

**Rafraîchissement automatique** — l'intervalle se règle dans les réglages de l'application ; il est décompté depuis la **fin** du rafraîchissement précédent.

## Port série virtuel (VSP)

La console série du serveur, telle qu'elle apparaîtrait sur un port physique : messages du BIOS, chargeur d'amorçage, console texte du système. Elle utilise sa propre session SSH, indépendante des autres onglets.

Le bouton `E(` de la barre supérieure envoie la séquence d'échappement qui rend la main à la CLI.

## Console CLI (SSH)

Exécution de commandes iLO arbitraires sur la session de contrôle. Utile pour ce que l'application n'expose pas.

> Une commande visant une cible inexistante peut **bloquer la session** plusieurs minutes — comportement du firmware, observé sur matériel réel. L'application évite d'elle-même les cibles connues pour cela lors de ses balayages.

## Matériel (HW)

Deux sources au choix, configurées par hôte.

**Par SSH** (défaut) — parcourt l'arborescence CLI et fournit un véritable **inventaire** : barrettes mémoire une à une, processeurs, baies de disques, versions de firmware. Compter une quinzaine de secondes, chaque composant exigeant une commande.

**Par IPMI** — lit le répertoire de capteurs : environ 1,5 s, puis 200 ms par actualisation. En contrepartie, ce sont des **capteurs** et non un inventaire : une trentaine de sondes de température, ventilateurs, alimentations, mais aucun détail par barrette ni par processeur, et aucun numéro de série.

Le choix dépend donc de ce que vous cherchez, et l'un n'exclut pas l'autre d'un hôte à l'autre.

## Interface web (Web)

Les iLO3 n'acceptent que TLS 1.0/1.1 avec RC4 et 3DES, que plus aucun navigateur n'accepte. L'application reconstitue un client TLS ancien et l'expose localement, pour qu'un navigateur moderne affiche l'interface d'origine.

| Option | Effet |
|---|---|
| Exposer sur toutes les interfaces | Rend la passerelle accessible aux autres appareils du réseau, **sans authentification** |
| HTTPS local | Certificat auto-signé ; le navigateur avertira |
| Forcer un port | Un port inférieur à 1024 exige un appareil rooté |

Le premier chargement reste lent : le processeur du BMC plafonne les transferts à environ 35 Ko/s, indépendamment du réseau. Les connexions sont réutilisées et les ressources statiques mises en cache, ce qui rend les chargements suivants nettement plus rapides.

## Notifications

Surveillance périodique en arrière-plan, configurable par serveur : échec de connexion, dégradation, erreur critique, extinction, allumage. Chaque vérification ouvre sa propre connexion, puis la referme.

Les alertes se déclenchent sur les **changements** d'état, pas à chaque vérification : un serveur durablement éteint ne notifie qu'une fois.

> Android impose un intervalle minimal de quinze minutes aux tâches périodiques ; un réglage plus court ne sera pas honoré par le système.
