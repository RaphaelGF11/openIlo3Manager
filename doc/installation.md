# Installation

## Choisir l'APK

Les versions publiées fournissent **un APK par architecture** :

| Fichier | Pour |
|---|---|
| `…-arm64-v8a.apk` | Tous les téléphones et tablettes récents |
| `…-x86_64.apk` | Machines virtuelles Android, émulateurs |

Cette séparation existe parce que le tunnel WireGuard embarque une bibliothèque native, qu'il serait inutile de télécharger en double. En cas de doute, prenez `arm64-v8a` : c'est l'architecture de pratiquement tout appareil physique vendu depuis 2015.

Pour vérifier :

```sh
adb shell getprop ro.product.cpu.abi
```

## Installer

Les APK sont **auto-signés** : ils ne transitent pas par le Play Store. Android affichera donc un avertissement d'éditeur inconnu, et il faut autoriser l'installation depuis des sources inconnues pour l'application qui ouvre le fichier (navigateur ou gestionnaire de fichiers).

C'est le comportement normal pour une application distribuée hors magasin ; cela signifie que vous accordez votre confiance au dépôt plutôt qu'à Google.

## Mettre à jour

Installer une version par-dessus la précédente conserve vos hôtes et vos réglages, à une condition : **la signature doit être identique**.

En pratique, un APK de *release* ne peut pas remplacer une installation issue d'une build de *debug*, et inversement. Android refuse alors l'installation avec `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. La seule issue est de désinstaller d'abord — ce qui **efface toutes les données locales**, y compris les hôtes enregistrés.

Si cela doit vous arriver, faites d'abord une [sauvegarde](sauvegarde.md).

## Mises à jour automatiques

L'application interroge la page des versions de GitHub au démarrage et propose la mise à jour si une version plus récente existe — avec **Plus tard**, **Ne plus demander** et **Voir plus**, qui mène aux réglages.

Les réglages permettent aussi de vérifier à tout moment, de consulter les notes de version, puis de télécharger et lancer l'installation. L'application ne peut pas installer elle-même : elle présente le paquet au système, qui vous demande confirmation. Android exige en outre l'autorisation « installer des applications inconnues » pour cette application, accordée depuis les réglages du système ; le bouton vous y conduit si elle manque.

L'APK correspondant à l'architecture de l'appareil est choisi automatiquement.

> La mise à jour remplace l'application installée, ce qui suppose une **signature identique** : une build de debug ne peut pas être remplacée par une version publiée. Voir plus haut.

« Ne plus demander » désactive le dialogue de démarrage mais **pas** la vérification dans les réglages, qui reste disponible.

## Prérequis côté serveur

- Un iLO3 accessible sur le réseau, avec **SSH activé** (c'est le cas par défaut).
- Un compte iLO disposant au minimum des privilèges de console virtuelle et d'alimentation.
- Pour IPMI : l'activer explicitement sur l'iLO, ce que l'application sait faire elle-même — voir [IPMI](configuration-hote.md#onglet-ipmi).

## Permissions demandées

| Permission | Quand |
|---|---|
| Internet, état du réseau | En permanence : c'est l'objet de l'application |
| Caméra | Uniquement à l'ouverture du scanner de QR code WireGuard |
| Notifications | Si vous activez la surveillance d'un serveur |
| Service de premier plan | Tant que la passerelle web tourne |
| Biométrie | Pour le verrouillage de l'application |

Aucune permission d'accès aux fichiers, aux contacts ou à la position n'est demandée.
