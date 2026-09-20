# Politique de confidentialité — iLO3 Manager

**Dernière mise à jour : 20 septembre 2026**

iLO3 Manager est une application Android permettant d'administrer des serveurs HP ProLiant via leur processeur de gestion iLO3. Elle est distribuée gratuitement, sous licence MIT, et son code source est intégralement public.

Ce document décrit quelles données l'application manipule et ce qu'elle en fait.

## En résumé

L'application **ne collecte aucune donnée personnelle**, ne contient ni publicité, ni traceur, ni outil de mesure d'audience, et n'envoie aucune donnée à son auteur ou à un tiers. Elle ne communique qu'avec deux destinataires : les serveurs que vous configurez vous-même, et — uniquement si vous activez cette fonction — votre propre compte Google Drive.

## Données traitées par l'application

Pour fonctionner, l'application enregistre sur votre appareil les informations que vous saisissez :

- adresses, ports et noms des serveurs ;
- identifiants de connexion : noms d'utilisateur, mots de passe, clés privées SSH et leurs phrases secrètes ;
- configurations de tunnel VPN, y compris les clés privées WireGuard ;
- préférences de l'application (intervalles de rafraîchissement, options de notification).

Ces données sont stockées **localement**, dans l'espace privé de l'application, au moyen d'`EncryptedSharedPreferences` (chiffrement dont la clé maîtresse réside dans le Keystore Android). Elles ne sont accessibles ni aux autres applications, ni à l'auteur de celle-ci.

L'application ne lit ni vos contacts, ni votre position, ni vos fichiers personnels, ni l'identité de votre appareil.

## Permissions demandées

| Permission | Usage |
|---|---|
| Internet / état du réseau | Joindre les serveurs que vous configurez |
| Caméra | Uniquement pour lire un QR code de configuration WireGuard. Aucune image n'est enregistrée ni transmise |
| Notifications | Vous alerter d'un incident sur un serveur surveillé |
| Service de premier plan | Maintenir la passerelle web active tant que vous l'utilisez |
| Biométrie | Verrouiller l'accès à l'application |

## Sauvegarde Google Drive (fonction optionnelle)

Si — et seulement si — vous activez cette fonction et connectez un compte Google :

**Portée d'accès.** L'application demande le seul périmètre `https://www.googleapis.com/auth/drive.appdata`. Il donne accès à un **espace privé réservé à l'application**, invisible dans l'interface de Google Drive. Il **ne permet pas** de lire, modifier ou supprimer vos propres fichiers Drive, et l'application n'en demande aucun autre.

**Contenu envoyé.** Uniquement la configuration décrite plus haut : vos serveurs et leurs identifiants. Aucune donnée d'usage, aucune statistique.

**Chiffrement.** La sauvegarde est **chiffrée sur votre appareil avant tout envoi**, avec une clé dérivée d'une phrase secrète que vous choisissez (scrypt, puis AES-256-GCM). Ce qui est déposé sur Drive est un bloc illisible sans cette phrase — que l'application ne transmet jamais et que son auteur ne connaît pas. Une phrase perdue rend la sauvegarde définitivement irrécupérable.

**Destinataire.** Votre propre compte Google Drive, et personne d'autre. Les données ne transitent par aucun serveur intermédiaire : l'application dialogue directement avec l'API Google Drive.

**Suppression.** Les réglages de l'application comportent un bouton **Supprimer la sauvegarde**, qui efface le fichier de votre Drive. C'est la voie à privilégier : l'espace applicatif n'étant pas exposé par l'interface de Drive, vous ne pouvez pas y supprimer ce fichier vous-même.

Indépendamment, **Déconnecter** révoque l'accès de l'application à votre compte sans rien effacer, et vous pouvez retirer cette autorisation depuis [la page des applications tierces de votre compte Google](https://myaccount.google.com/permissions). Désinstaller l'application efface toutes les données locales, mais **pas** la sauvegarde Drive : supprimez-la avant, si c'est votre intention.

## Partage avec des tiers

Aucun. Les données ne sont ni vendues, ni louées, ni transmises à quiconque. L'auteur de l'application n'y a pas accès et n'exploite aucun serveur.

## Conservation

Les données restent sur votre appareil aussi longtemps que l'application y est installée. La sauvegarde Drive, si vous en créez une, demeure dans votre compte jusqu'à ce que vous la supprimiez.

## Enfants

L'application s'adresse à l'administration de matériel informatique et ne vise pas les mineurs de moins de 13 ans.

## Modifications

Toute évolution de cette politique sera publiée dans ce fichier, au sein du dépôt public du projet, avec mise à jour de la date ci-dessus.

## Contact

Questions ou demandes relatives à cette politique : ouvrez une issue sur le dépôt du projet,
[github.com/RaphaelGF11/openIlo3Manager](https://github.com/RaphaelGF11/openIlo3Manager).
