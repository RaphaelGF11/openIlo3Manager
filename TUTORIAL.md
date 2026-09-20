# Prise en main

Mettre en place un premier serveur, en dix minutes. Pour le détail de chaque point, la [documentation complète](doc/README.md) prend le relais.

## Ce dont vous avez besoin

- Un serveur HP ProLiant Gen6 ou Gen7 avec un **iLO3 joignable sur le réseau** ;
- l'**adresse IP de l'iLO** — pas celle du serveur : le processeur de gestion a la sienne, et répond même serveur éteint ;
- un compte iLO avec son mot de passe.

## 1. Installer

Téléchargez l'APK correspondant à votre appareil depuis la [page des versions](https://github.com/RaphaelGF11/openIlo3Manager/releases) :

- **`arm64-v8a`** pour un téléphone ou une tablette ;
- `x86_64` pour une machine virtuelle.

L'application étant distribuée hors du Play Store, Android affichera un avertissement d'éditeur inconnu : il faut autoriser l'installation depuis des sources inconnues. → [Installation](doc/installation.md)

## 2. Ajouter un serveur

Bouton **+**, puis remplissez deux onglets :

**Général** — un nom, et l'adresse IP de l'iLO. Les ports 22 et 443 conviennent par défaut.

**Authentification** — votre utilisateur iLO et son mot de passe.

> Préférez le mot de passe à la clé privée pour ce premier essai : IPMI, que vous activerez juste après, s'authentifie par mot de passe et ne sait pas utiliser de clé.

**Enregistrer**. Le serveur apparaît dans la liste.

## 3. Se connecter

Touchez le bouton de lecture sur la ligne du serveur. L'application ouvre une session SSH — comptez quelques secondes, l'iLO3 n'étant pas rapide — puis affiche l'onglet **Alim** : état d'alimentation, santé, et les commandes de démarrage et d'arrêt.

La pastille à gauche du nom, dans la liste, résume l'état : jaune pendant la récupération, verte si le serveur tourne, orange s'il est éteint. → [Fonctionnalités](doc/fonctionnalites.md)

## 4. Activer IPMI — fortement recommandé

L'application vous le propose après cette première connexion. **Acceptez** : l'onglet Alim répondra ensuite en une fraction de seconde au lieu de plusieurs secondes, sans même ouvrir de session SSH.

Mais lisez l'avertissement avant de valider. IPMI ouvre le port UDP 623, et son protocole **livre par conception une empreinte de votre mot de passe à quiconque la demande**, attaquable hors ligne. C'est acceptable sur un réseau d'administration de confiance, et discutable ailleurs.

Un point à connaître : l'iLO **plafonne le niveau IPMI selon les privilèges du compte**. Un compte qui ne les détient pas tous est ramené à *User*, c'est-à-dire en lecture seule — l'état s'affiche, mais aucune action d'alimentation ne passe. Pour piloter l'alimentation par IPMI, le compte doit donc disposer des privilèges correspondants côté iLO.

Le sélecteur de niveau sert à demander ce que votre compte peut réellement obtenir : gardez **Opérateur** avec un compte complet, choisissez **Lecture seule** pour un compte volontairement restreint. → [Onglet IPMI](doc/configuration-hote.md#onglet-ipmi)

## 5. Explorer

| Onglet | Ce qu'il apporte |
|---|---|
| **Alim** | Alimentation, santé, LED de localisation |
| **VSP** | Console série du serveur : BIOS, amorçage, console texte |
| **SSH** | Commandes iLO libres |
| **HW** | Inventaire matériel, ou capteurs si vous activez IPMI |
| **Web** | L'interface web d'origine de l'iLO, affichable dans votre navigateur |

L'onglet **Web** mérite un essai : il reconstitue le TLS ancien que plus aucun navigateur n'accepte, puis expose l'interface localement. Démarrez la passerelle, puis ouvrez-la dans le navigateur. Le premier chargement est lent — le processeur de l'iLO plafonne les transferts — les suivants beaucoup moins.

## Pour aller plus loin

**Accéder à distance** — si l'iLO n'est pas sur votre réseau, l'onglet VPN propose un tunnel **WireGuard intégré**, qui ne crée aucune interface VPN système et ne capte pas le trafic des autres applications. Import par fichier ou par QR code. → [Tunnels VPN](doc/vpn.md)

**Être alerté** — l'icône de cloche sur chaque serveur configure une surveillance périodique : panne, dégradation, extinction. → [Notifications](doc/fonctionnalites.md#notifications)

**Sauvegarder la configuration** — réglages › Sauvegarde Google Drive. Elle est chiffrée sur l'appareil avant tout envoi, avec une phrase que vous seul connaissez. Nécessite votre propre projet Google Cloud. → [Sauvegarde](doc/sauvegarde.md)

**Si quelque chose ne marche pas** — le [dépannage](doc/depannage.md) recense les messages d'erreur et leurs causes réelles, la plupart tenant à des particularités du firmware iLO3 plutôt qu'à une mauvaise manipulation.
