# iLO3 Manager

Application Android permettant de gérer un serveur HP ProLiant via son iLO3 (Integrated Lights-Out 3), entièrement au travers de SSH — sans dépendre de l'interface web historique de l'iLO, incompatible avec les navigateurs modernes.

> **Ce projet est publié tel quel, au cas où il pourrait être utile à quelqu'un d'autre.** Il a été entièrement conçu et développé par une IA (Claude), à la demande et sous la supervision de son auteur, qui n'a pas écrit une ligne de code lui-même. Utilisez-le à vos risques.

## Pourquoi ce projet

Les iLO de génération 3 (HP ProLiant Gen6/Gen7) n'acceptent que du TLS 1.0/1.1 avec des suites de chiffrement obsolètes (RC4, 3DES) sur leur interface web, et un SSH configuré avec des algorithmes tout aussi anciens (`diffie-hellman-group1-sha1`, `ssh-dss`). Aucun client SSH ni navigateur moderne ne s'y connecte directement. Cette application embarque tout ce qu'il faut pour continuer à administrer ce matériel depuis un téléphone Android.

## Fonctionnalités

- **Gestion de plusieurs serveurs** : ajout/édition/suppression, réorganisation par glisser-déposer, connexion par mot de passe ou clé privée (import de fichier, collage, ou génération d'une paire de clés directement dans l'app).
- **Verrouillage biométrique** de l'application (empreinte / visage / code de l'appareil).
- **Tableau de bord Alimentation** : état d'alimentation, LED de santé globale (normal / dégradé / critique), démarrage/arrêt/redémarrage/forçage d'arrêt, auto-rafraîchissement à intervalle configurable.
- **Port série virtuel (VSP)** : accès à la console série du serveur via une session SSH dédiée.
- **Console SSH** : exécution de commandes CLI iLO arbitraires sur la session déjà ouverte.
- **État du matériel** : capteurs, ventilateurs, alimentations, mémoire, CPU, disques — récupéré à la demande pour ne pas multiplier les connexions.
- **Notifications** : surveillance périodique en arrière-plan (WorkManager) avec alertes granulaires configurables par serveur (échec de connexion, dégradation, erreur critique, extinction/allumage).
- **Passerelle web locale** : reconstruit un client TLS legacy (Bouncy Castle) pour dialoguer avec l'interface web HTTPS de l'iLO, et l'expose en HTTP sur `127.0.0.1` afin qu'un navigateur moderne (Chrome, etc.) puisse l'afficher normalement.

## Compatibilité

- Android 5.0 (API 21) et plus.
- Aucune dépendance à des services Google autres que ceux déjà présents sur l'appareil.

## Stack technique

- Kotlin + Jetpack Compose (Material 3)
- [mwiede/jsch](https://github.com/mwiede/jsch) pour SSH (fork maintenu supportant les algorithmes legacy)
- [Bouncy Castle](https://www.bouncycastle.org/) (`bctls`) pour le client TLS legacy indépendant de la pile système
- [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) pour le serveur HTTP local de la passerelle web
- AndroidX WorkManager pour la surveillance périodique
- EncryptedSharedPreferences pour le stockage des identifiants

## Licence

Ce projet est distribué sous licence MIT — voir le fichier [LICENSE](LICENSE).
