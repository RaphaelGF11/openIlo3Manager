# Dépannage

Messages rencontrés et leur cause réelle. La plupart proviennent de particularités du firmware iLO3, constatées sur matériel réel.

## Connexion

**« Connexion impossible » ou délai dépassé**

L'iLO possède sa **propre adresse réseau**, distincte de celle du serveur. Vérifiez que vous visez bien la première. Elle répond même serveur éteint : si le ping échoue alors que le serveur est allumé, c'est l'adresse qui est en cause.

Attention aussi aux **cartes réseau partagées** : lorsque l'iLO partage un port avec le serveur, une machine virtuelle hébergée sur ce même serveur peut se trouver incapable de le joindre, alors que tout autre appareil du réseau y parvient. C'est une limite de topologie, pas un défaut de configuration.

**« No available SSH sessions »**

Un iLO3 n'accepte qu'un très petit nombre de sessions simultanées, **partagées entre SSH et l'interface web**. Elles expirent d'elles-mêmes au bout d'une trentaine de minutes. Fermez les sessions inutiles, ou redémarrez l'iLO depuis Information › Diagnostics.

**Une commande CLI ne rend jamais la main**

Viser une cible inexistante peut bloquer la session plusieurs minutes. L'application évite les cibles connues pour cela et borne ses balayages, mais la console CLI vous laisse libre : en cas de blocage, il faut attendre.

## Onglet Matériel

**Le chargement paraît interminable**

Le parcours CLI exécute une commande par composant contre un processeur lent : une quinzaine de secondes est normale. Les étapes affichées indiquent la progression. Pour aller nettement plus vite, voyez l'option [HW via IPMI](configuration-hote.md#onglet-ipmi).

**Tous les capteurs affichent « indisponible » en IPMI**

C'est généralement exact, et non un défaut de lecture : **serveur éteint, la plupart des capteurs ne renvoient rien**. Ventilateurs, températures et alimentations ne reprennent des valeurs qu'une fois la machine allumée.

## IPMI

**« L'iLO a refusé l'authentification »**

Par ordre de probabilité : IPMI n'est pas activé sur l'iLO ; l'hôte n'a pas de mot de passe enregistré (IPMI ne sait pas utiliser une clé privée) ; le compte n'a pas le privilège demandé.

**« Commande IPMI refusée (code 0xd4) »**

Privilège insuffisant : la lecture d'état fonctionne, les actions non.

Vérifiez d'abord le niveau demandé dans l'onglet IPMI. S'il est déjà sur **Opérateur**, la cause est côté iLO : le niveau accordé est plafonné par les privilèges du compte, et un compte incomplet est ramené en lecture seule. Accordez-lui les privilèges nécessaires dans Administration › Gestion des utilisateurs.

**« Ressources insuffisantes sur l'iLO »**

Trop de sessions IPMI ouvertes. Elles expirent seules ; patientez ou redémarrez l'iLO.

**La pastille bleue n'apparaît jamais**

Seul IPMI rapporte l'état de la LED UID. Un hôte en SSH peut l'allumer et l'éteindre, mais l'application ne peut pas savoir si elle est allumée : la CLI renvoie `enabledstate=enabled` dans les deux cas.

## Passerelle web

**Un réglage ne s'enregistre pas depuis l'interface de l'iLO**

Caractéristique du firmware : l'iLO3 est **incapable de lire le corps d'une requête POST dès qu'une query string est présente**, même réduite à un `?`. L'interface en ajoute systématiquement pour contourner les caches. La passerelle les retire donc pour les requêtes porteuses d'un corps ; si vous observez encore ce problème, signalez-le.

**Le premier chargement est long**

Le processeur du BMC plafonne les transferts à environ 35 Ko/s, quel que soit le réseau. Les ressources statiques sont mises en cache pour la durée de vie de la passerelle : les chargements suivants sont bien plus rapides. Après une mise à jour du firmware, redémarrez la passerelle pour vider ce cache.

**« Échec de liaison au port privilégié »**

Un port inférieur à 1024 exige une redirection posée par root. Même sur un appareil rooté, l'application elle-même ne dispose pas de la capacité réseau nécessaire pour s'y lier directement.

## Tunnels

**IPMI ne fonctionne pas à travers un rebond SSH**

Attendu : SSH ne relaie que du TCP, IPMI fonctionne en UDP. L'application repasse automatiquement sur la CLI SSH pour l'onglet Alim. Utilisez WireGuard si vous voulez IPMI à distance.

**Le tunnel WireGuard ne transporte rien**

Vérifiez qu'`AllowedIPs` inclut le réseau de l'iLO, et que le pair est joignable depuis l'appareil. Une poignée de main qui n'aboutit pas vient presque toujours d'un pair injoignable ou d'une clé erronée.

## Sauvegarde Google Drive

**« Erreur de configuration (code 10) »**

Le client OAuth Android ne correspond pas au paquet ou à l'empreinte SHA-1 de la build installée. Lisez l'empreinte dans l'**APK réellement installé**, et non dans un fichier de keystore supposé être le bon :

```sh
apksigner verify --print-certs application.apk
```

Debug et release ayant des signatures différentes, il faut **un client OAuth par signature** — un client n'en accepte qu'une.

**« Google Drive a répondu 403 »**

L'API Google Drive n'est pas activée dans le projet Cloud. Activez-la, puis patientez quelques minutes.

**« Ce compte est connecté mais n'a pas accordé l'accès »**

Cause la plus fréquente : sur l'écran de consentement, la case « données de configuration dans Google Drive » est restée **décochée**. Google la présente ainsi par défaut, et valider sans la cocher lie le compte sans rien accorder — aucune application ne peut la cocher à votre place. Utilisez le bouton qui la relance ; il révoque d'abord l'accès, sans quoi Google réutiliserait silencieusement l'autorisation précédente et l'écran ne réapparaîtrait pas.

**« Phrase secrète incorrecte »**

La phrase ne correspond pas à celle ayant servi au chiffrement. Il n'existe aucun moyen de récupération : c'est le prix d'un chiffrement dont l'éditeur ne détient pas la clé.
