# Sauvegarde Google Drive

Fonction **optionnelle**. Tout le reste de l'application fonctionne sans compte Google ni services Play.

## Ce qui est sauvegardé

La configuration complète de vos hôtes : adresses, ports, comptes, mots de passe, clés privées SSH, configurations de tunnel. Autrement dit, des données sensibles.

## Comment elle est protégée

Le fichier est déposé dans l'**espace privé de l'application** sur votre Drive : invisible dans l'interface Drive, non téléchargeable depuis celle-ci — le même mécanisme qu'utilisent les services de jeu pour leurs sauvegardes.

**Mais l'invisibilité n'est pas la confidentialité.** Un fichier caché reste stocké en clair chez Google. La sauvegarde est donc **chiffrée sur l'appareil avant tout envoi** : dérivation de clé par scrypt depuis une phrase secrète que vous choisissez, puis AES-256-GCM. Ce qui arrive chez Google est un bloc illisible sans cette phrase, que l'application ne transmet jamais.

### Pourquoi une phrase secrète, et non une clé de l'appareil

Une clé du Keystore Android **ne peut pas quitter le téléphone qui l'a créée**. Une sauvegarde scellée avec elle serait irrécupérable sur un nouvel appareil — précisément la situation où une sauvegarde sert.

La clé est donc dérivée de votre phrase, qui voyage avec vous. Une copie est conservée chiffrée sur l'appareil pour ne pas vous la redemander à chaque synchronisation, mais ce n'est qu'un cache : **la référence est celle que vous retenez**.

> Une phrase oubliée rend la sauvegarde définitivement illisible. Personne ne peut la récupérer.

## Utiliser

1. **Réglages** › Sauvegarde Google Drive ;
2. connecter un compte, et **accorder l'accès Drive** — les deux sont distincts, voir ci-dessous ;
3. saisir une phrase secrète ou un code ;
4. **Sauvegarder**, ou **Restaurer** sur un nouvel appareil.

La restauration n'écrit **qu'après** déchiffrement réussi : une phrase erronée ne laisse jamais l'appareil à moitié restauré. Elle remplace les hôtes présents.

### Connexion et consentement sont deux choses

Un compte peut être lié alors que la demande d'autorisation Drive a été refusée ou ignorée : l'application dispose alors d'un compte inutilisable. Elle vérifie donc explicitement l'autorisation, l'affiche, et désactive Sauvegarder et Restaurer tant qu'elle manque.

Le bouton **Déconnecter** révoque l'accès plutôt que de simplement oublier le compte, afin que la prochaine connexion reparte d'un consentement explicite. La sauvegarde déjà présente sur Drive n'est pas supprimée.

## Prérequis : votre propre projet Google Cloud

Cette fonction exige une configuration que l'auteur de l'application ne peut pas fournir à votre place, Google liant l'autorisation à la signature de **votre** build.

1. Créer un projet sur [Google Cloud Console](https://console.cloud.google.com/) ;
2. y **activer l'API Google Drive** ;
3. configurer l'écran de consentement, périmètre `drive.appdata`, et vous ajouter comme **utilisateur de test** ;
4. créer un client OAuth de type **Android** avec le nom du paquet `net.raphaelgf11.ilo3manager` et l'empreinte SHA-1 du certificat qui signe votre APK.

L'empreinte se lit dans l'APK lui-même, et non dans un fichier de keystore supposé être le bon :

```sh
apksigner verify --print-certs mon-application.apk
```

Rien n'est à saisir dans l'application : les services Play l'identifient par son paquet et sa signature, puis résolvent le client dans votre projet.

> Le périmètre `drive.appdata` étant classé sensible, un projet non vérifié reste limité aux comptes déclarés comme testeurs. Pour une diffusion large, Google exige une vérification, avec une politique de confidentialité hébergée sur un domaine que vous contrôlez — voir [CONFIDENTIALITY.md](../CONFIDENTIALITY.md).

Les erreurs fréquentes de cette configuration sont détaillées dans le [dépannage](depannage.md#sauvegarde-google-drive).
