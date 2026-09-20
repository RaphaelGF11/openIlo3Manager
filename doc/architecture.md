# Architecture

Pour contribuer, ou comprendre pourquoi le code a cette forme. La plupart des décisions découlent de contraintes du firmware iLO3 établies **par la mesure**, et non par la documentation — laquelle est soit absente, soit fausse sur ces points.

## Organisation

```
data/        modèle et stockage chiffré
ssh/         sessions SSH (JSch), CLI iLO, console série
ilo/         parsing CLI, contrôleur de session, API web JSON
ipmi/        client IPMI 2.0 (RMCP+) et lecture des capteurs
vpn/         tunnels : WireGuard et rebond SSH
webgateway/  passerelle TLS legacy, pool de connexions, cache
backup/      chiffrement et sauvegarde Drive
ui/          écrans Compose
wgtunnel/    module Go compilé en .aar
```

## Particularités du firmware, vérifiées sur matériel

Ces points ont coûté du temps à isoler ; les ignorer, c'est les redécouvrir.

**Une query string empêche la lecture du corps d'un POST.** N'importe laquelle, même un `?` seul, fait répondre `Malformed object, expected '{' at start of object`. Testé avec `?`, `?null`, `?_=123`, `?x=1`. La passerelle retire donc la query string des requêtes porteuses d'un corps.

**Le TLS se limite à RC4 et 3DES sur TLS 1.0/1.1**, sans aucun AES — établi par `nmap --script ssl-enum-ciphers`. Bouncy Castle n'implémente plus RC4, seul 3DES reste donc utilisable, d'où un plafond d'environ 35 Ko/s imposé par le processeur du BMC. D'où le pool de connexions et le cache : il ne s'agit pas de transférer plus vite, mais de ne pas transférer deux fois.

**L'iLO3 précède RFC 5746** et n'envoie pas l'indication de renégociation sécurisée ; Bouncy Castle interrompt alors la poignée de main de son propre chef. Le client surcharge ce comportement.

**Son groupe Diffie-Hellman fait 1024 bits**, que Bouncy Castle rejette. Les suites DHE sont donc exclues au profit de l'échange RSA simple.

**La CLI n'expose aucun réglage IPMI.** Toutes les autres options de la page Access Settings figurent dans `/map1/config1` ; aucune propriété IPMI n'existe, et tous les noms plausibles sont rejetés. L'activation passe donc par l'API JSON de l'interface web.

**La CLI pilote la LED UID mais ne sait pas la lire.** `start` et `stop` sur `/system1/led1` fonctionnent — confirmé en observant basculer les bits d'identification d'IPMI — mais `show` renvoie `enabledstate=enabled` dans les deux états.

**`ElementName` porte un nom générique** : « System » pour tous les ventilateurs, « Power Supply » pour toutes les alimentations. L'identité réelle est dans `DeviceID`, d'où l'ordre de préférence du parseur.

**Certaines cibles bloquent la session.** `oemhp_vsp1` notamment : la session ne répond plus à aucune commande pendant plusieurs minutes. Ces cibles sont exclues des balayages.

**Le nombre de sessions simultanées est très faible et partagé entre SSH et web.** Les épuiser rend l'iLO inaccessible par les deux voies jusqu'à expiration.

## Client IPMI

Écrit d'après la spécification : aucune bibliothèque IPMI Java maintenue n'est disponible comme dépendance. Quatre points n'ont été trouvés qu'en confrontant l'implémentation au matériel, avec `ipmitool` comme référence.

- L'en-tête de session doit commencer par l'octet **Auth Type/Format** ; sans lui le BMC ignore le datagramme en silence.
- Les identifiants de session se lisent aux offsets de la **charge utile**, pas du paquet.
- IPMI définit son **propre bourrage** de confidentialité, et la clé RAKP est le mot de passe complété par des zéros sur 20 octets. PKCS#5 et le mot de passe brut produisent des paquets ignorés.
- RAKP ne négocie que le **plafond** de privilège : sans *Set Session Privilege Level*, les lectures passent et toute écriture revient en 0xD4.

Deux garde-fous dictés par UDP : les réponses sont **corrélées par étiquette de message**, faute de quoi une retransmission fait lire la réponse tardive du message précédent ; et les réponses d'erreur sont vérifiées **avant** tout découpage, sinon un refus explicite se transforme en exception de tableau.

## Tunnels comme traduction d'adresse

Un tunnel expose chaque service comme **port local redirigé**, à la manière d'un `ssh -L`. Tous les clients réseau prenant déjà un hôte et un port, les router revient à leur fournir une autre adresse : ni JSch, ni Bouncy Castle, ni le client IPMI n'ont été modifiés.

Cela impose aussi la forme de l'API Go, gomobile n'exportant que des types simples.

## WireGuard en espace utilisateur

`VpnService` imposerait une interface TUN, une demande de consentement et la capture du trafic de toutes les applications. Une pile TCP/IP en espace utilisateur (gVisor, via wireguard-go) n'exige rien de tout cela : la seule socket visible du système est une socket UDP ordinaire vers le pair.

Il n'existe pas d'équivalent JVM : la cryptographie de WireGuard serait simple à écrire en Kotlin, mais le tunnel transporte des **paquets IP bruts** et demande donc une pile TCP complète. C'est cette pile qui justifie la bibliothèque native, et son poids.

Le `.aar` est versionné pour que compiler l'application n'exige ni Go ni le NDK. Sa reconstruction est décrite dans [wgtunnel/README.md](../wgtunnel/README.md).

## Sessions et navigation

Les sessions vivent dans un magasin lié au processus, non à la navigation : quitter un serveur ne ferme pas ses connexions. Le magasin publie une **révision** que les écrans observent, sans quoi une liste composée avant l'existence d'une session ne la refléterait jamais.

Un écran modifiant un hôte doit propager l'enregistrement aux sessions vivantes, faute de quoi la modification n'aurait d'effet qu'après redémarrage.

## Pièges Android rencontrés

- `biometric:1.1.0` entraîne `androidx.fragment` 1.2.5, dont `FragmentActivity` refuse les codes de requête générés par les API ActivityResult de Compose. **Tous** les sélecteurs de l'application plantaient. Une version récente de fragment est épinglée.
- Le fournisseur Bouncy Castle doit être passé **par instance**, non par le nom « BC » : Android enregistre sous ce nom son propre fournisseur restreint, qui n'implémente pas `SHA256withRSA`.
- Dans une `Row`, `fillMaxWidth()` réclame la largeur du parent sans déduire celle des voisins ; `weight(1f)` partage ce qui reste.
- `PBKDF2WithHmacSHA256` n'existe qu'à partir d'API 26 ; le projet visant API 21, scrypt de Bouncy Castle est utilisé.

## Tests

Les tests unitaires couvrent ce qui est vérifiable sans matériel : parsers CLI et WireGuard, chiffrement des sauvegardes. Le reste — protocoles IPMI, TLS, tunnels — a été validé par des harnais temporaires exécutés contre un iLO3 réel, puis retirés. Cette méthode a systématiquement été plus rapide que la relecture de code.
