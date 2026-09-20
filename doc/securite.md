# Sécurité

Ce document décrit ce que l'application protège, ce qu'elle ne protège pas, et les compromis assumés.

## Où vivent vos identifiants

Mots de passe, clés privées SSH et configurations de tunnel sont conservés dans l'espace privé de l'application, via `EncryptedSharedPreferences` : les valeurs sont chiffrées par une clé maîtresse détenue par le **Keystore Android**, que le matériel ne restitue pas.

Ils sont donc inaccessibles aux autres applications. Sur un appareil rooté, le fichier est lisible mais son contenu reste chiffré.

En modification d'un hôte, les secrets ne sont jamais réaffichés : les champs restent vides et conservent la valeur enregistrée s'ils le demeurent.

## Verrouillage de l'application

Si votre appareil dispose d'une biométrie, l'application se verrouille en arrière-plan. Deux nuances de conception :

- un **délai de grâce de trente secondes** évite de redemander l'empreinte quand l'application ouvre elle-même un sélecteur de fichier ou le scanner de QR code — sans quoi toute action de ce type ferait reperdre le contexte ;
- l'état de verrouillage vit dans le processus : il survit à une rotation d'écran, mais **une fermeture réelle de l'application exige de nouveau l'authentification**.

## Ce qui sort de l'appareil

| Destination | Quand | Contenu |
|---|---|---|
| Vos serveurs iLO | À l'usage | Commandes d'administration, identifiants de connexion |
| Votre pair WireGuard ou rebond SSH | Si configuré | Le trafic ci-dessus, encapsulé |
| Google Drive | Si vous l'activez | La configuration, **chiffrée** |

Aucune donnée ne part ailleurs. L'application ne contient ni traceur, ni mesure d'audience, ni serveur d'éditeur — il n'en existe pas.

## Risques assumés, et pourquoi

### Le certificat de l'iLO n'est pas validé

La passerelle web accepte le certificat sans le vérifier. Ce n'est pas un oubli : les iLO3 présentent un certificat auto-signé sans chaîne exploitable, qu'aucune validation ne pourrait accepter. La connexion n'est donc pas protégée contre un intercepteur actif sur le trajet vers l'iLO.

De même, `StrictHostKeyChecking` est désactivé pour SSH, l'application ne disposant pas d'un magasin de clés d'hôtes.

**Conséquence pratique** : traitez le lien vers l'iLO comme vous traiteriez un réseau d'administration — de confiance, ou tunnelé.

### IPMI expose une empreinte du mot de passe

La poignée de main RAKP d'IPMI 2.0 **livre par conception une empreinte du mot de passe à quiconque la demande**, attaquable hors ligne. C'est inhérent au protocole, non à cette application ni à votre configuration.

C'est la raison pour laquelle IPMI est désactivé par défaut, et pourquoi son activation demande toujours confirmation en expliquant ce point. Le niveau **Opérateur** est proposé par défaut plutôt qu'Administrateur : l'application n'a jamais besoin du second. Notez toutefois que l'iLO plafonne le niveau accordé d'après les privilèges du compte — un compte restreint sera ramené en lecture seule, ce qui limite d'autant la portée d'un identifiant compromis.

### Exposer la passerelle web

L'option « exposer sur toutes les interfaces » rend la passerelle accessible à tout le réseau **sans authentification** : quiconque connaît l'adresse et le port atteint votre interface iLO. L'avertissement est affiché dans l'application. Par défaut, la passerelle n'écoute que sur `127.0.0.1`.

### Un port privilégié nécessite root

Forcer un port inférieur à 1024 passe par une redirection `iptables` posée par `su`. Cela suppose un appareil rooté, lequel affaiblit par ailleurs l'isolation dont dépend le stockage des identifiants.

## Signature des APK

Les versions publiées sont signées avec une clé propre au projet, conservée hors du dépôt. Une même signature garantit qu'une mise à jour provient de la même source ; c'est aussi pourquoi une build de debug ne peut pas remplacer une release.

## Signaler un problème

Par une issue sur le dépôt. Le projet est personnel et sans garantie : il n'existe ni délai de réponse, ni engagement de correction.
