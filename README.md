# iLO3 Manager

Application Android pour administrer un serveur HP ProLiant via son iLO3 (Integrated Lights-Out 3), dont l'interface d'origine n'est plus accessible aux clients modernes.

> **Ce projet est publié tel quel, au cas où il pourrait être utile à quelqu'un d'autre.** Il a été entièrement conçu et développé par une IA (Claude), à la demande et sous la supervision de son auteur, qui n'a pas écrit une ligne de code lui-même. Utilisez-le à vos risques.

## Pourquoi ce projet

Les iLO de génération 3 (HP ProLiant Gen6/Gen7) n'acceptent que du TLS 1.0/1.1 avec des suites obsolètes (RC4, 3DES) sur leur interface web, et un SSH configuré avec des algorithmes tout aussi anciens (`diffie-hellman-group1-sha1`, `ssh-dss`). Aucun navigateur ni client SSH récent ne s'y connecte directement. Cette application embarque tout le nécessaire pour continuer à administrer ce matériel depuis un téléphone Android.

## Fonctionnalités

- **Gestion de plusieurs serveurs** : ajout, édition, suppression, réorganisation par glisser-déposer, authentification par mot de passe ou clé privée (import, collage, ou génération d'une paire de clés dans l'app). Fiche d'hôte organisée en onglets Général / Authentification / IPMI / VPN.
- **Verrouillage biométrique** de l'application, avec restauration de l'écran en cours après déverrouillage.
- **Tableau de bord Alimentation** : état, santé, démarrage/arrêt/redémarrage/forçage, LED de localisation (UID), rafraîchissement automatique configurable.
- **IPMI (optionnel, désactivé par défaut)** : pilote l'alimentation en ~150 ms au lieu de plusieurs secondes, sans ouvrir de session SSH. Peut également alimenter l'onglet Matériel et l'affichage d'état dans la liste des serveurs. L'application sait activer IPMI sur l'iLO elle-même, le CLI SSH n'exposant aucun réglage pour cela.
- **Port série virtuel (VSP)** et **console CLI iLO** sur session SSH.
- **État du matériel** : par l'arborescence CLI (inventaire détaillé : barrettes mémoire, processeurs, baies de disques) ou par les capteurs IPMI (températures, ventilateurs, alimentations — bien plus rapide, mais sans inventaire).
- **Notifications** : surveillance périodique en arrière-plan, alertes configurables par serveur.
- **Passerelle web locale** : client TLS legacy reconstruit avec Bouncy Castle, exposé en HTTP local pour qu'un navigateur moderne affiche l'interface de l'iLO. Connexions réutilisées et ressources statiques mises en cache, le processeur du BMC plafonnant les transferts à environ 35 Ko/s.
- **Tunnels** pour joindre un iLO hors du réseau local :
  - **WireGuard** intégré, en espace utilisateur — aucune interface système, aucune demande de VPN Android, et le trafic des autres applications n'est pas capturé. Import par fichier `.conf` ou par QR code. Transporte TCP et UDP, donc IPMI également.
  - **Rebond SSH** (port forwarding) — sans dépendance native, mais TCP uniquement : IPMI reste alors indisponible.
- **Sauvegarde Google Drive (optionnelle)** : la configuration est chiffrée sur l'appareil puis déposée dans l'espace privé de l'application. Voir [CONFIDENTIALITY.md](CONFIDENTIALITY.md).

## Compatibilité

- Android 5.0 (API 21) et plus.
- Un APK par architecture (`arm64-v8a`, `x86_64`) : le tunnel WireGuard embarque une bibliothèque native.
- Les services Google Play ne sont requis que pour la sauvegarde Drive. Toutes les autres fonctions s'en passent.

## Stack technique

- Kotlin + Jetpack Compose (Material 3)
- [mwiede/jsch](https://github.com/mwiede/jsch) — SSH avec algorithmes legacy
- [Bouncy Castle](https://www.bouncycastle.org/) — client TLS legacy indépendant de la pile système, et scrypt pour le chiffrement des sauvegardes
- Client **IPMI v2.0 (RMCP+)** écrit pour ce projet : session RAKP-HMAC-SHA1, AES-CBC-128, lecture du répertoire de capteurs
- [wireguard-go](https://git.zx2c4.com/wireguard-go/) + pile TCP/IP gVisor, liés via gomobile (voir [wgtunnel/](wgtunnel/))
- [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) — serveur HTTP local de la passerelle
- [ZXing](https://github.com/journeyapps/zxing-android-embedded) — lecture des QR codes WireGuard, sans dépendance aux services Google
- AndroidX WorkManager, EncryptedSharedPreferences

## Compilation

```sh
./gradlew assembleRelease
```

La bibliothèque native WireGuard est fournie compilée (`app/libs/wgtunnel.aar`), de sorte que compiler l'application ne demande ni Go ni le NDK Android. Sa reconstruction est décrite dans [wgtunnel/README.md](wgtunnel/README.md).

La sauvegarde Drive nécessite un projet Google Cloud avec l'API Drive activée et un client OAuth Android déclarant le nom du paquet et l'empreinte SHA-1 de votre certificat de signature. Sans cela, cette seule fonction reste inopérante.

## Licence

Ce projet est distribué sous licence MIT — voir le fichier [LICENSE](LICENSE).
